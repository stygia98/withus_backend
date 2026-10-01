# 발송 큐 설계 Plan

- 작성자: 팀원2 (campaign·workflow)
- 상태: **PL 승인 대기** (3/3 작성 완료 — 아래 14장 참고)
- 근거: PRD 8.2(발송 큐·재확인·재시도)·8.4(법규)·6.5(WAIT·인스턴스 깨우기), DB_SCHEMA 6장(상태 전이)·7장(쿼리 패턴)

> 이 Plan은 코드 작성 전 PL 승인을 받기 위한 문서다. W2 발송 큐 구현(큐 적재, 발송 작업, 재확인, 렌더링)은 이 Plan 승인 이후 시작한다.

## 1. send_log 상태 전이

```
PENDING ──(우선순위 순 선점·SENDING 커밋)──▶ SENDING ──(발송 성공)──▶ SENT ──(SES 반송 웹훅, 팀원1)──▶ BOUNCED
   ▲                                            │
   │ 일시 오류(TRANSIENT), 1·5·15분 재시도       ├──(영구 오류 또는 3회 초과)──▶ FAILED
   └────────────────────────────────────────────┤
                                                 └──(SENDING 10분 초과, 결과 불명)──▶ FAILED(UNKNOWN_RESULT)

PENDING ──(발송 직전 재확인 실패: 동의 N·suppression·고객 삭제·캠페인 COMPLETED·쿠폰 유효기간 밖)──▶ SKIPPED
PENDING ──(시간창 08:00~20:50 밖, 광고성·NOTICE)──▶ PENDING (next_attempt_at = 다음 날 08:00, attempt_count 미증가)
PENDING ──(캠페인 PAUSED)──▶ PENDING (next_attempt_at 변경 없음, attempt_count 미증가)
```

- `SENDING`은 중간 상태이며 재확인·렌더링·실제 발송 호출 동안만 머문다. 바로 `SENT`/`FAILED`/`SKIPPED`로 넘어간다.
- `SKIPPED`는 종단 상태다(재시도하지 않는다). `BOUNCED`는 팀원1 SES 웹훅이 `SENT` 이후에 별도로 전이시킨다(이 Plan 범위 밖).

## 2. 적재 (SendQueueService.enqueue)

- 호출자: 캠페인(일회성·워크플로우 SEND 노드), 팀원1(F-12 수신동의 안내), 팀원3(쿠폰 메일), 테스트 발송.
- 대상 고객을 **500건씩** 나눠 조회하고, 묶음마다 짧은 트랜잭션으로 `INSERT`한다(10만 건 적재가 긴 트랜잭션 하나로 묶이지 않게).
- 묶음 안에서 각 고객에 대해:
  1. `ConsentService.isSendable(customerId, channel)`로 수신동의를 확인한다(쓰기 아닌 조회이므로 팀원1 서비스 직접 호출).
  2. 거부면 `status='SKIPPED'`로, 가능하면 `status='PENDING'`으로 적재한다. **둘 다 적재한다** — SKIPPED 이력도 남겨야 대시보드 집계(팀원3)와 10.3 검증("적재 후 수신거부 SKIPPED")이 가능하다.
  3. `recipient`는 적재 시점 이메일/휴대폰(이후 고객 정보가 바뀌어도 발송 이력은 그대로 유지).
  4. `priority`는 kind로 결정: TEST=1, WORKFLOW·NOTICE=2, CAMPAIGN(대량)=3.
- **유니크 충돌 처리**: `uq_send_log_one_time (campaign_id, customer_id) WHERE instance_id IS NULL`, `uq_send_log_step (instance_id, step_id)`. `INSERT ... ON CONFLICT (대상 컬럼) WHERE 조건 DO NOTHING`으로 멱등 처리한다 — 이미 적재된 것으로 보고 건너뛴다(캠페인 재시작, 워크플로우 SEND 재실행 대비).
- 적재 자체는 발송이 아니므로 트랜잭션 안에서 완결되고 외부 호출이 없다.

### 적재 SQL 초안

```sql
INSERT INTO send_log (campaign_id, instance_id, step_id, customer_id, recipient, channel, status, kind, priority)
VALUES (#{campaignId}, #{instanceId}, #{stepId}, #{customerId}, #{recipient}, #{channel}, #{status}, #{kind}, #{priority})
ON CONFLICT (campaign_id, customer_id) WHERE instance_id IS NULL DO NOTHING;
-- 워크플로우 SEND는 ON CONFLICT (instance_id, step_id) DO NOTHING 대상 인덱스를 쓴다.
```

## 3. 발송 작업 (SendDispatcher)

### 3.1 선점 (DB_SCHEMA 7장 쿼리 그대로 사용)

```sql
UPDATE send_log SET status = 'SENDING', updated_at = now()
WHERE send_log_id IN (
    SELECT send_log_id FROM send_log
    WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now())
    ORDER BY priority, send_log_id
    LIMIT 50
    FOR UPDATE SKIP LOCKED
)
RETURNING *;
```

- `@Scheduled(fixedDelay=...)`로 반복 실행. 한 번 실행에 처리할 건수 상한(예: 선점 묶음 여러 번 반복하되 총 처리 건수 또는 실행 시간 상한)을 두어 스케줄러 풀(5)을 오래 점유하지 않는다.
- 선점은 **tx1**: `SELECT ... FOR UPDATE SKIP LOCKED` 후 `UPDATE`, 바로 커밋.

### 3.2 트랜잭션 밖 처리 (SendRecheck → MessageComposer → TokenBucket → MessageSender)

트랜잭션 밖에서 순서대로:
1. **SendRecheck**: 발송 직전 재확인(동의·suppression·삭제·캠페인 상태·시간창·쿠폰 유효기간). 아래 4장에서 상세.
2. **MessageComposer**: 재확인 통과 건만 렌더링(치환 → 광고 문구 → 추적 치환 → 쿠폰 발급). 아래 5장에서 상세.
3. **TokenBucket.acquire()**: `ses.max-send-rate` 기준 초당 토큰 리필, 토큰 없으면 대기.
4. **MessageSender.send(OutboundMessage)**: SMTP(Mailpit, local) 또는 SES(prod) 호출. 결과로 `SendResult(success, providerMessageId, errorType, errorMessage)`를 받는다.

이 구간은 DB 트랜잭션을 잡지 않는다(PRD 8.2: SES·SMS 호출은 트랜잭션 밖). `processOne(sendLogId)` 메서드 하나로 묶어 재확인·렌더링·재시도 로직을 이후 작업(W2-BE 재확인·렌더링)에서 끼워 넣는다.

### 3.3 결과 기록 (tx2)

- `SENT`: `sent_at = now()`, `provider_message_id` 저장.
- `FAILED`: `error_message`에 사유 코드 저장.
- **같은 트랜잭션에서** 워크플로우 인스턴스 깨우기(`WorkflowWakeup.wake(sendLog)`)를 호출한다 — PRD 6.5: "발송 작업은 워크플로우 발송 건의 결과를 기록하는 같은 트랜잭션에서 인스턴스를 깨운다"(W3에서 구현, 이 Plan은 연결점만 명시).
- 재시도 대상(TRANSIENT 오류)은 `status='PENDING'`으로 되돌리고 `attempt_count+1`, `next_attempt_at`을 재시도 간격에 따라 설정한다(6장에서 상세).

## 4. 토큰 버킷 (TokenBucket)

- 외부 라이브러리 없이 직접 구현한다(stdlib `synchronized` + 시간 비교).
- `ses.max-send-rate`(초당 허용 건수)만큼 매초 토큰을 리필하고, `acquire()`는 토큰이 없으면 다음 리필까지 블로킹 대기한다.
- 로컬 Mailpit에는 자체 한도가 없지만, PRD 8.2 요구사항("로컬에서도 같은 속도 제한이 동작하는지 테스트")에 따라 TokenBucket은 프로필과 무관하게 항상 적용한다.
- 시간 공급자를 주입 가능하게 만들어(`Clock` 또는 `LongSupplier` 나노초 공급자) 단위 테스트에서 가짜 시계로 검증한다.

## 5. 클래스 후보

| 클래스 | 역할 | 패키지 |
|---|---|---|
| `SendQueueService` | `enqueue(...)` — 큐 적재 (팀원1·3·워크플로우가 호출하는 진입점) | `com.withus.campaign.service` |
| `SendDispatcher` | `@Scheduled` 선점·결과 기록, `processOne(sendLogId)` | `com.withus.campaign.service` |
| `SendRecheck` | 발송 직전 재확인 | `com.withus.campaign.service` |
| `MessageComposer` | 렌더링 조립(치환·광고 문구·추적·쿠폰) | `com.withus.campaign.service` |
| `TokenBucket` | 초당 속도 제한 | `com.withus.campaign.service` |
| `SendLogMapper` | `send_log` 테이블 접근(선점·적재·결과 기록 쿼리) | `com.withus.campaign.mapper` |

## 6. 트랜잭션 경계 그림

```
┌─────────────┐    ┌──────────────────────────────────────────┐    ┌──────────────────────────┐
│  tx1 (짧음)  │    │              트랜잭션 밖                    │    │       tx2 (짧음)          │
│  선점        │ ─▶ │  재확인 조회 → 렌더링 → TokenBucket → 발송   │ ─▶ │  결과 기록 + 인스턴스 깨우기 │
│  PENDING→    │    │  (SELECT만, 외부 호출 포함)                 │    │  SENT/FAILED/PENDING(재시도)│
│  SENDING     │    │                                            │    │                           │
└─────────────┘    └──────────────────────────────────────────┘    └──────────────────────────┘
     FOR UPDATE          커밋 상태 조회만 (다른 도메인 SELECT 허용)         같은 트랜잭션 안에서
     SKIP LOCKED                                                        WorkflowWakeup 호출
```

- 이 구조는 CLAUDE.md "외부 호출은 DB 트랜잭션 밖에서, 잠금을 잡은 채 외부 API를 부르지 않는다"를 그대로 따른다.
- tx1과 tx2 사이에 서버가 멈추면 해당 건은 `SENDING`에 머물다 10분 초과 시 `FAILED(UNKNOWN_RESULT)`로 처리된다(2/3에서 상세).

## 7. 발송 직전 재확인 (SendRecheck)

`processOne`에서 선점(tx1) 직후, 렌더링 전에 아래 항목을 **순서대로** 확인한다. 하나라도 걸리면 그 즉시 결과를 정하고 뒤 항목은 보지 않는다.

| 순서 | 확인 항목 | 조회 대상 | 걸렸을 때 처리 | attempt_count |
|---|---|---|---|---|
| 1 | 고객 삭제 여부 | `customer`(SELECT, TEST는 customer_id NULL이라 건너뜀) | `SKIPPED` | 미증가 |
| 2 | 해당 채널 수신동의 N | `customer`/`consent`(팀원1 SELECT) | `SKIPPED` | 미증가 |
| 3 | `suppression` 등재 | `suppression`(팀원1 SELECT) | `SKIPPED` | 미증가 |
| 4 | 캠페인 상태 `COMPLETED` | `campaign`(자기 테이블) | `SKIPPED` | 미증가 |
| 5 | 캠페인 상태 `PAUSED` | `campaign`(자기 테이블) | `PENDING`으로 복귀(건너뛰지 않음) | 미증가 |
| 6 | 광고성(`template.ad_yn=Y`)·NOTICE(`kind=NOTICE`)인데 현재 08:00~20:50 밖 | `template`(자기 테이블), `Clock` | `PENDING` 유지, `next_attempt_at`=다음날 08:00 | 미증가 |
| 7 | 연결 쿠폰이 유효기간 밖 | `coupon`(팀원3 SELECT) | `SKIPPED`(사유 `COUPON_INVALID`) | 미증가 |

- 1~4, 7은 **종단 SKIPPED**(재시도 없음). 5·6은 **보류**(PENDING 유지, 다음 선점 주기에 다시 검사).
- TEST(`kind=TEST`, `customer_id IS NULL`)는 1~3번을 건너뛰고 6번(시간창)도 적용하지 않는다(PRD 8.2 "테스트 발송만 예외").
- 쿠폰 유효기간 확인(7번)은 쿠폰이 연결된 경우에만 수행하며, `CouponService.issue` 호출 **전에** 먼저 걸러 불필요한 발급 시도를 막는다.

## 8. 보류·재시도 시각 계산 규칙

**시간창 보류(6번)**
- 판정 시각은 `Clock.instant()`를 Asia/Seoul로 변환해 사용(단위 테스트에서 Clock 주입으로 경계값 검증).
- 08:00~20:50 밖이면 `next_attempt_at = 다음_날_08:00` (오늘 08:00 이전이면 오늘 08:00이 아니라 **오늘 08:00**로 당긴다 — 예: 07:30에 선점됐다면 즉시 보낼 수 없으니 오늘 08:00로 설정). 20:50 이후면 다음 날 08:00.

**PAUSED 보류(5번)**
- `next_attempt_at`을 바꾸지 않는다(그대로 두거나 NULL 유지) — 재개 시점에 바로 선점 대상이 되어야 하므로 특정 미래 시각을 넣지 않는다. 재개(resume) API가 캠페인 상태만 `ACTIVE`로 바꾸면 다음 스케줄러 주기에 자동으로 다시 선점된다.

**재시도(TRANSIENT 오류)**
- `attempt_count`를 1 올리고 아래 표로 `next_attempt_at`을 설정, `status='PENDING'`으로 되돌린다.

| attempt_count (오류 발생 후) | next_attempt_at |
|---|---|
| 1 | now + 1분 |
| 2 | now + 5분 |
| 3 | now + 15분 |
| 4 (3회 초과) | 재시도 없음 → `FAILED` |

- 영구 오류(PERMANENT)는 attempt_count와 무관하게 즉시 `FAILED`.
- SENDING 10분 초과 복구는 재시도가 아니라 `FAILED(UNKNOWN_RESULT)` 종단 처리다(DB_SCHEMA 7장 쿼리 그대로, `updated_at < now() - interval '10 minutes'`).

## 9. 렌더링 순서 (MessageComposer)

재확인을 전부 통과한 건만 아래 순서로 조립한다. 순서가 바뀌면 안 되는 이유를 함께 적는다.

1. **쿠폰 발급** (`CouponService.issue`, 연결된 경우만) — SKIPPED가 될 건에는 쿠폰을 발급하지 않아야 하므로 재확인(7장) 뒤, 다른 렌더링보다 먼저 수행해 `couponUrl` 값을 확보한다.
2. **치환자 치환** (`PlaceholderRenderer.render`) — 1에서 받은 `couponUrl`을 포함한 값 맵으로 본문을 채운다.
3. **광고 문구 삽입** — (광고) 제목/본문 접두, 발신자 정보, 수신거부 링크. 템플릿 본문을 전부 채운 뒤에 시스템이 덧붙이는 고정 문구라 치환보다 뒤에 온다(치환자가 광고 문구 안의 텍스트를 건드리면 안 되므로).
4. **추적 치환** (`TrackingLinkService.rewrite`, EMAIL만) — 광고 문구까지 포함해 완성된 HTML에서 일반 링크만 추적 URL로 바꾼다. 수신거부·쿠폰·mailto·tel 링크는 제외(PRD 8.1). 광고 문구를 넣기 **전에** 추적 치환을 하면 수신거부 링크가 아직 없어 잘못 치환될 위험이 있어 반드시 3번 다음에 수행한다.
5. **헤더 추가** (EMAIL만) — `List-Unsubscribe`, `List-Unsubscribe-Post: List-Unsubscribe=One-Click`.

## 10. 스케줄러 목록

`spring.task.scheduling.pool.size=5`(고정, CLAUDE.md 7장)를 공유하는 `@Scheduled(fixedDelay=...)` 작업 목록이다.

| 스케줄러 | 주기(안) | 역할 |
|---|---|---|
| `SendDispatcher.dispatch` | 1~2초 | PENDING 선점(최대 50건) → 처리 |
| `SendRecoveryJob.recoverStuckSending` | 1분 | SENDING 10분 초과 → FAILED(UNKNOWN_RESULT) |
| `CampaignScheduleJob.activateScheduled`(캠페인 2/4·4/4에서 구현) | 1분 | SCHEDULED → ACTIVE 전환, 적재 |
| `CampaignCompleteJob`(캠페인 4/4) | 1분 | PENDING·SENDING 소진된 캠페인 자동 COMPLETED |

- fixedDelay를 쓰는 이유(CLAUDE.md 4장): 이전 실행이 끝난 뒤에만 다음 실행이 시작돼, 처리가 느려져도 스케줄러가 겹쳐 실행되지 않는다.
- 5개 스레드를 여러 스케줄러가 공유하므로, `SendDispatcher`가 한 번에 너무 오래 돌면 다른 스케줄러(워크플로우 포함, W3)가 밀릴 수 있다 — 3.1에서 언급한 "한 번 실행 처리 건수 상한"이 이걸 막는 장치다.

## 11. 워크플로우 인스턴스 깨우기 연결점

- `SendDispatcher`의 결과 기록(tx2, 3.3)에서 `sendLog.instanceId != null`이면 **같은 트랜잭션 안에서** `WorkflowWakeup.wake(sendLog)`를 호출한다.
- `WorkflowWakeup`은 workflow 패키지(W3)가 제공하는 인터페이스이며, 이 Plan에서는 **호출 시점과 전달 데이터**(sendLog의 status·sent_at·instance_id·step_id)만 확정한다. 실제 깨우기 로직(다음 노드가 WAIT인지 판단, next_run_at 계산)은 워크플로우 엔진 Plan(W3-DOC)에서 다룬다.
- SKIPPED·FAILED로 끝난 경우도 `wake`를 호출해야 한다(PRD 6.5: "발송이 SKIPPED·FAILED로 끝나면 대기 없이 다음 노드로 진행"). sent_at이 없으므로 `now()`를 기준으로 전달한다.

## 12. 미확정 사항 (PL 확인 필요, 추측 구현 금지)

1. **수신거부 토큰 생성 주체** — PRD 8.3: `Base64URL(send_log_id:customer_id:HMAC-SHA256)`. HMAC 키는 환경변수 비밀값이라 생성 모듈을 어느 도메인에 둘지(렌더링 시점에 campaign이 직접 만들지, common/auth 쪽 공용 유틸을 쓸지) PL 확인 전에는 정하지 않는다. 검증(`/unsubscribe/[token]`)은 팀원1 담당이라 생성·검증 로직이 같은 HMAC 키·같은 포맷을 써야 한다.
2. **20:50 도달 시 발송 중인 배치 처리** — PRD 8.4: "발송 도중 20:50이 되면 남은 건을 멈추고 다음 날 08:00에 이어서 보낸다." 이미 `SENDING`으로 선점된 50건이 20:50을 넘겨 처리되는 중이면 그 50건은 끝까지 보낼지, 재확인(7장 6번)에서 매번 시각을 다시 보므로 20:50 **이후에 선점되는** 건부터는 자연히 막히는지 확인이 필요하다 — 현재 설계로는 "이미 선점된 50건은 끝까지 처리, 다음 선점부터 차단"이 되는데 이 정도 오차(최대 50건)가 허용되는지 PL 확인.
3. **보류 건의 next_attempt_at 설정 방식** — PAUSED 보류(8장)에서 `next_attempt_at`을 그대로 둘지, 아니면 명시적으로 NULL 처리할지. 재개 직후 선점 우선순위가 다른 PENDING 건과 섞일 때 순서 보장이 필요한지(PRD에 명시 없음) PL 확인.

## 13. PRD 10.3 대응 — 발송 관련 완료 기준 검증 방법

| PRD 10.3 항목 | 이 Plan에서 담당하는 설계 | 검증 방법 |
|---|---|---|
| 발송 도중 서버를 재시작해도 남은 건부터 이어서 발송된다 | 3.1 선점(FOR UPDATE SKIP LOCKED, 즉시 커밋) — 서버가 죽어도 `PENDING`/`SENDING` 상태가 DB에 남는다 | 통합 테스트: PENDING 1,000건 적재 → 처리 중 애플리케이션 강제 종료(또는 Dispatcher 빈 중지) → 재기동 → 재시작 전 미처리 건이 전부 소진되는지 확인(10.3 "재시작 이어서 발송") |
| 10만 건 대량 발송이 쌓여 있어도 신규 가입 환영 메일(워크플로우, priority 2)이 먼저 나간다 | 3.1 선점 쿼리의 `ORDER BY priority, send_log_id` | 통합 테스트: priority 3 건 10만 개 선적재 → priority 2 건 1개 적재 → 다음 선점 주기에 priority 2가 먼저 `SENDING`으로 바뀌는지 확인(W4 부하 작업에서 10만 건 규모로 재검증) |
| 적재 후 발송 전에 수신거부한 고객에게는 발송되지 않는다(SKIPPED) | 7장 재확인 2번(수신동의 N) | 통합 테스트: PENDING 적재 → 적재 후 해당 고객 수신동의를 N으로 변경 → 선점·재확인 통과 시점에 SKIPPED로 바뀌는지 확인 |
| 처리 도중 서버를 강제 종료해도 RUNNING 인스턴스는 10분 뒤 복구되고, SENDING 건은 다시 나가지 않는다 | 1장 상태 전이(SENDING 10분 초과 → FAILED UNKNOWN_RESULT), DB_SCHEMA 7장 복구 쿼리 | 단위 테스트: `updated_at`을 11분 전으로 만든 SENDING 행에 복구 스케줄러 실행 → FAILED(UNKNOWN_RESULT)로 바뀌고 재선점 대상(PENDING)이 되지 않는지 확인. "다시 나가지 않는다"가 핵심이므로 FAILED 이후 재시도 로직이 호출되지 않는 것도 함께 검증 |
| SES 스로틀링 오류를 흉내 내면 1분·5분·15분 간격으로 재시도된다 | 8장 재시도 시각 계산표 | 단위 테스트: `MessageSender`를 목으로 대체해 `SendResult(false, null, TRANSIENT, ...)` 3연속 반환 → attempt_count 1·2·3과 next_attempt_at이 각각 now+1분·+5분·+15분인지 확인, 4번째는 FAILED |
| 테스트 발송은 대시보드 통계에 잡히지 않는다 | `kind=TEST`는 send_log에 그대로 남지만 집계 쿼리(팀원3)가 `kind != 'TEST'`로 걸러야 함 | 이 Plan의 책임은 "TEST로 올바르게 적재되는가"까지다 — W2 미리보기·테스트발송 작업에서 `kind=TEST, priority=1, customer_id=NULL`로 적재되는지 단위 테스트. 집계 제외 자체는 팀원3 대시보드 쿼리 책임이므로 팀원3과 쿼리 조건(`kind != 'TEST' AND bot_yn != 'Y'`)을 맞춘다 |
| 발송 큐가 10만 건 처리 중이어도 다른 스케줄 작업이 멈추지 않는다 | 10장 스케줄러 설계(풀 5 공유, SendDispatcher 처리 건수 상한) | W4 부하 작업(10만 건)에서 SendDispatcher가 도는 동안 SendRecoveryJob·CampaignScheduleJob 등이 정상 주기로 실행되는지 로그 타임스탬프로 확인 |

위 표 밖의 10.3 항목(광고 제목 삽입, SMS 광고 문구, 쿠폰 링크 포함, 수신거부·쿠폰 링크 추적 제외 등)은 9장 렌더링 순서 설계로 이미 다뤘고, 실제 검증은 해당 구현 작업(렌더링 2/3·3/3)의 단위·통합 테스트에서 수행한다.

## 14. PL 승인

- **승인 상태: 대기 중.** 이 문서는 1~13장까지 작성을 완료했으나, 아직 실제 PL 리뷰를 거치지 않았다.
- CLAUDE.local.md 체크리스트("발송 큐 설계 Plan 작성 → PL 리뷰·승인, W2 착수 전")에 따라 **W2 작업(큐 적재·발송 작업·재확인·렌더링 구현)은 아래 승인 기록이 남기 전에는 시작하지 않는다.**
- 승인 절차(권장): 이 파일을 포함한 PR을 올리거나 PL에게 직접 공유 → 12장 미확정 사항 3건에 대한 답변을 받아 Plan에 반영 → PL이 PR 코멘트 또는 메시지로 승인 → 아래에 기록.

```
승인자:
승인 일시:
승인 방식(PR 코멘트 / 메시지 등):
미확정 사항 답변: (1) 수신거부 토큰 생성 주체 — / (2) 20:50 배치 처리 — / (3) PAUSED next_attempt_at — 
```
