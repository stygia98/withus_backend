# 워크플로우 엔진 설계 Plan

- 작성자: 팀원2 (campaign·workflow)
- 상태: **PL 승인 대기**
- 근거: PRD 6장(워크플로우 엔진 명세)·8.2(발송 큐 연동)·10.1(연결 인터페이스)·10.3(완료 기준), DB_SCHEMA 5.2(config_json)·6장(상태 전이)·7장(쿼리 패턴), API_SPEC 6장(캠페인·워크플로우)

> 이 Plan은 코드 작성 전 PL 승인을 받기 위한 문서다(CLAUDE.md "워크플로우 엔진은 코드를 쓰기 전에 Plan을 먼저 제시하고 승인받는다"). W3 구현(구조 검증·엔진·트리거·상태 전이)은 이 Plan 승인 이후 시작한다. 발송 큐 Plan(`send-queue-plan.md`, 승인 완료)과 겹치는 송신 로직(발송 직전 재확인·렌더링·재시도)은 다시 다루지 않고 연결점만 명시한다.

## 1. workflow_instance 상태 전이

```
(생성, 트리거) ──▶ WAITING ──(선점, next_run_at 경과)──▶ RUNNING ──(END 도달)──▶ COMPLETED
                      ▲                                      │
                      │ 노드 연속 실행 뒤 다음 대기 시각 기록   ├──(오류, 3회까지)──▶ WAITING(재시도, +5분)
                      └──────────────────────────────────────┤
                                                              └──(3회 초과)──▶ FAILED
WAITING/RUNNING ──(캠페인 종료 또는 고객 삭제)──▶ CANCELLED
RUNNING ──(10분 초과, 서버 중단 추정)──▶ WAITING(next_run_at = now, 그 인스턴스의 현재 단계부터 재실행)
```

- `RUNNING`은 선점부터 다음 대기 시각(또는 종료 상태) 기록까지만 머무는 중간 상태다(발송 큐의 `SENDING`과 같은 역할).
- `next_run_at IS NULL AND status = WAITING`은 "SEND 노드의 발송 결과를 기다리는 중"이라는 특별한 의미다(4장에서 상세) — 선점 쿼리의 `next_run_at <= now()` 조건에서 자동으로 제외된다.

## 2. 스케줄러 (WorkflowScheduler)

### 2.1 선점 (DB_SCHEMA 7장 쿼리 + PAUSED 제외)

```sql
UPDATE workflow_instance SET status = 'RUNNING', updated_at = now()
WHERE instance_id IN (
    SELECT instance_id FROM workflow_instance wi
    WHERE status = 'WAITING' AND next_run_at <= now()
      AND NOT EXISTS (
          SELECT 1 FROM campaign c WHERE c.campaign_id = wi.campaign_id AND c.status = 'PAUSED'
      )
    ORDER BY next_run_at
    LIMIT 500
    FOR UPDATE SKIP LOCKED
)
RETURNING *;
```

- PAUSED 제외는 발송 큐 Plan 15장 A1과 같은 이유다: 제외하지 않으면 일시정지된 캠페인의 인스턴스가 매 주기 500개 슬롯을 차지해 다른 캠페인이 밀린다. 재개(ACTIVE 복귀)하면 다음 주기에 자연히 다시 선점되므로 별도의 "깨우기" 로직이 필요 없다.
- `@Scheduled(fixedDelay = 60000)`(1분)로 반복 실행하고, 발송 큐의 `SendDispatcher.dispatch()`처럼 처리할 건이 없을 때까지 선점을 반복한다(10만 건이 한 주기 안에서 소진, PRD 6.5-1).
- `withus.scheduler.workflow-scheduler.enabled` 토글을 둔다(발송 큐 Plan에서 확립한 패턴 재사용 — 커밋 기반 통합 테스트에서 끈다).

### 2.2 노드 연속 실행과 트랜잭션 경계

**발송 큐와의 핵심 차이**: SEND 노드는 `SendQueueService.enqueueWorkflowStep`(DB INSERT)만 호출하고 SMTP/SES 같은 외부 호출을 직접 하지 않는다. 그래서 발송 큐 디스패처처럼 "트랜잭션 밖 구간"이 필요 없다 — 선점된 인스턴스 1건의 노드 연속 실행 전체를 **트랜잭션 하나(tx2)** 로 묶을 수 있다.

```
┌──────────────┐    ┌───────────────────────────────────────────┐
│  tx1 (짧음)   │    │         tx2 (인스턴스 1건당 트랜잭션 1개)      │
│  선점         │ ─▶ │  WAIT·END 를 만날 때까지 노드 연속 실행        │
│  WAITING→     │    │  (SEND 적재 포함, 전부 DB 안, 외부 호출 없음)  │
│  RUNNING      │    │  → current_step_id·status·next_run_at 기록  │
└──────────────┘    └───────────────────────────────────────────┘
   FOR UPDATE              실패하면 tx2 전체 롤백 → 재시도(5장)는
   SKIP LOCKED              별도의 짧은 트랜잭션(tx3)으로 기록
```

- tx2 안에서 예외가 나면(DB 오류 등) 그 인스턴스의 모든 변경(이미 적재한 SEND 포함)이 함께 롤백된다. 재시도 때 SEND 노드를 다시 실행해도 `(instance_id, step_id)` 유니크 제약이 중복 적재를 막아주므로 안전하다(5장).
- `processOne(instance)`을 `TransactionTemplate`으로 감싼다(발송 큐의 `SendQueueService`와 같은 방식). `@Transactional` 애노테이션 대신 수동 템플릿을 쓰는 이유는 재시도 기록(tx3)을 같은 메서드 안에서 "tx2가 롤백돼도 영향받지 않는 별도 트랜잭션"으로 둬야 하기 때문이다.

## 3. 노드별 실행 규칙

`current_step_id`는 "다음에 실행할 노드"를 가리킨다. TRIGGER·WAIT는 "거쳐가는" 노드라 실행 후 자기 자신을 가리키지 않고 바로 다음(또는 그다음) 노드로 넘긴다 — 이렇게 하면 WAIT 재방문 시 "처음 보는 WAIT인지, 대기가 끝나 돌아온 것인지" 구분할 필요가 없다.

| 노드 | 동작 | current_step_id 갱신 | 루프 계속 여부 |
|---|---|---|---|
| TRIGGER | 인스턴스 생성 시 1회만 의미 있음(6장) | 생성 시 `nextStepId`로 건너뜀 | - |
| SEND_EMAIL / SEND_SMS | `SendQueueService.enqueueWorkflowStep(campaignId, instanceId, stepId, customerId, channel)` 호출(내부에서 수신동의 확인 후 PENDING/SKIPPED 적재, PRD 6.5-3) | `nextStepId` | 계속 |
| WAIT | 3.1 참고 | `nextStepId`(WAIT 자신을 건너뜀) | 멈춤(이 인스턴스 처리 종료) |
| CONDITION | `TrackEventRepository.existsHumanEvent` 또는 누적구매액 비교(3.2) | `yesStepId`/`noStepId` | 계속 |
| END | `status = COMPLETED` | 변경 없음 | 멈춤 |

### 3.1 WAIT 처리 (PRD 6.5-4)

직전에 실행한 노드가 SEND_EMAIL/SEND_SMS였는지(`justSent` 플래그, 같은 루프 안에서만 유효)에 따라 둘로 나뉜다.

| 직전 노드 | next_run_at | 비고 |
|---|---|---|
| SEND(방금 적재, 결과 미확정) | `NULL` | "발송 결과를 기다리는 중" — 4장 `wake()`가 채운다 |
| 그 외(CONDITION, 또는 WAIT가 TRIGGER 바로 다음 등) | `now + 대기시간` | 즉시 계산 가능 |

두 경우 모두 `current_step_id = WAIT.nextStepId`(WAIT를 건너뛴 값), `status = WAITING`으로 저장하고 그 인스턴스 처리를 멈춘다.

### 3.2 CONDITION 판정

- `EMAIL_OPENED`/`EMAIL_CLICKED`: 구조 검증(워크플로우 구조 2/3 작업)이 "이 조건 앞에는 반드시 SEND_EMAIL→WAIT가 있다"를 보장하므로, **이 인스턴스의 가장 최근 send_log 1건**(`SendLogMapper`에 `findLatestSendLogId(instanceId)` 신규 메서드 필요)을 가져와 `TrackEventRepository.existsHumanEvent(sendLogId, "OPEN"|"CLICK")`로 판정한다. SKIPPED·FAILED로 끝난 건은 `track_event`가 없으므로 자연히 `false`(NO) — PRD 6.5-4의 "SKIPPED·FAILED면 NO" 요구사항을 별도 분기 없이 만족한다.
- `PURCHASE_GTE`: `customer.total_purchase >= config.amount`(이미 `MessageComposer`의 `findPlaceholderSource`가 쓰는 컬럼과 같다 — 같은 값을 재사용).

## 4. 발송 결과 ↔ 워크플로우 연결 (WorkflowWakeup)

발송 큐 Plan 11장에서 연결점만 예고했던 부분을 여기서 확정한다.

### 4.1 인터페이스

```java
public interface WorkflowWakeup {
    /** sendLog 가 워크플로우 발송 건(instanceId != null)이면 다음 대기 시각을 정한다. 아니면 아무 일도 하지 않는다 */
    void wake(SendLog sendLog);
}
```

- 패키지: `com.withus.workflow.service`. `SendDispatcher`(campaign)가 호출하므로 **구간 간 연결 인터페이스**(PRD 10.1)로 등록한다.

### 4.2 동작

`SendDispatcher`의 결과 기록(발송 큐 Plan 3.3, tx2) 안에서 `sendLog.instanceId != null`이면 **같은 트랜잭션**으로 `wake(sendLog)`를 호출한다.

```
sendStep = workflowStepMapper.find(sendLog.stepId)
nextStep = workflowStepMapper.find(sendStep.nextStepId)      // current_step_id 가 이미 가리키는 바로 그 노드
baseTime = sendLog.status == SENT ? sendLog.sentAt : now()   // SKIPPED·FAILED는 now (PRD 6.5-4)
nextRunAt = nextStep.nodeType == WAIT ? baseTime + nextStep.config.duration : now
UPDATE workflow_instance SET next_run_at = nextRunAt
WHERE instance_id = sendLog.instanceId AND next_run_at IS NULL
```

- `WHERE next_run_at IS NULL` 조건으로, 이미 다른 경로(멈춤 복구 등)로 값이 채워진 경우 덮어쓰지 않는다.
- `nextStep`이 WAIT가 아닌 경우(SEND 바로 뒤에 CONDITION 등이 오는 구조)는 `now`로 즉시 재개한다 — 다음 스케줄러 주기에 이어서 처리된다.

## 5. 멱등성과 멈춤 복구

- **SEND 재실행**: `uq_send_log_step (instance_id, step_id)` 유니크 제약 + `ON CONFLICT DO NOTHING`(발송 큐 구현에 이미 있음)으로 중복 적재를 막는다. 엔진은 `enqueueWorkflowStep`의 반환값(삽입 건수)을 보지 않고 항상 다음 노드로 진행한다 — 재시도로 다시 불려도 자연히 멱등하다.
- **RUNNING 10분 초과 복구**: `WorkflowRecoveryJob`(발송 큐의 `SendRecoveryJob`과 같은 구조), 1분 주기.

```sql
UPDATE workflow_instance SET status = 'WAITING', next_run_at = now()
WHERE status = 'RUNNING' AND updated_at < now() - INTERVAL '10 minutes';
```

- **오류 재시도**: tx2(2.2) 실행 중 예외가 나면 롤백되고(이미 적재된 SEND도 함께 롤백 — 재시도 때 다시 적재되므로 안전), 별도의 짧은 트랜잭션(tx3)으로 `retry_count += 1`, `next_run_at = now + 5분`, `status = WAITING`을 기록한다. `retry_count`가 3을 넘으면 재시도 대신 `status = FAILED`, `last_error`에 예외 메시지를 남긴다.

## 6. 트리거 (인스턴스 생성)

### 6.1 SEGMENT_SCHEDULED

- 캠페인 예약 시각이 되면 `WorkflowTriggerService.startSegmentScheduled(campaignId)`를 호출한다. (캠페인 4/4 스케줄러는 `campaign.type`에 따라 갈라져야 한다: `ONE_TIME`은 `SendQueueService.enqueueOneTime`, `WORKFLOW`는 이 메서드를 부른다 — 캠페인 4/4 작업과의 연결점.)
- `SegmentService.findTargetCustomers(segmentId)` 결과를 500건씩 나눠 `workflow_instance`를 일괄 `INSERT`한다. `current_step_id = TRIGGER.nextStepId`, `status = WAITING`, `next_run_at = now()`.
- `uq_workflow_instance (campaign_id, customer_id)`로 멱등(재실행해도 중복 생성 안 됨).
- 모든 인스턴스가 `COMPLETED`/`CANCELLED`가 되면 캠페인을 자동 `COMPLETED`로 바꾼다(PRD 6.7).

### 6.2 CUSTOMER_REGISTERED

- `customer` 도메인이 등록 트랜잭션 안에서 발행하는 `CustomerRegisteredEvent`를 `@EventListener`로 받는다(2026-10-01 PL 답변으로 이미 확정 — `CustomerRegisteredEvent`·`CustomerDeletedEvent` 클래스는 이미 `customer` 도메인에 존재한다).
- `onCustomerRegistered(event)`: `trigger_type = CUSTOMER_REGISTERED`이고 `status = ACTIVE`인 캠페인을 찾아, 각 캠페인의 세그먼트에 이 고객이 해당하면 인스턴스 1건을 생성한다.
- **세그먼트 멤버십 확인 방법**: `SegmentService`에는 `findTargetCustomers(segmentId)` 전체 목록만 있고 "고객 1명이 세그먼트에 속하는지"를 바로 묻는 메서드가 없다. 이 Plan에서는 `findTargetCustomers(segmentId).contains(customerId)`로 대체한다(등록은 건당 1번씩 일어나므로 대량 처리와 달리 성능 영향은 적다고 판단) — 8장 미확정 사항 1번에서 PL 확인을 구한다.
- 업로드로 등록된 고객은 이벤트가 발행되지 않으므로(F-01, `CustomerRegisteredEvent` 주석에 명시) 별도 필터링이 필요 없다.

## 7. 일시정지·종료·고객 삭제

| 상황 | 처리 | 위치 |
|---|---|---|
| 캠페인 PAUSED | 선점 쿼리에서 제외(2.1) — 재개하면 다음 주기에 자연히 다시 선점됨, 별도 "깨우기" 불필요 | WorkflowScheduler |
| 캠페인 COMPLETED(수동 종료) | `WAITING`/`RUNNING` 인스턴스를 일괄 `CANCELLED` | 캠페인 상태 전이 API(`/complete`) |
| 고객 삭제 | `CustomerDeletedEvent`를 `@EventListener`로 받아 그 고객의 `WAITING`/`RUNNING` 인스턴스를 `CANCELLED` | workflow(이 Plan), 삭제 트랜잭션과 같은 트랜잭션(이벤트 클래스 주석에 명시) |

## 8. 미확정 사항 (PL 확인 필요, 추측 구현 금지)

1. **CUSTOMER_REGISTERED 세그먼트 멤버십 확인** — `SegmentService.findTargetCustomers` 전체 조회 후 `contains`로 대체(6.2). 세그먼트가 매우 크거나 등록이 잦아지면 `SegmentService.isMember(segmentId, customerId)` 같은 메서드가 필요할 수 있다 — 인터페이스 추가 여부(동결 인터페이스라 PL 리뷰 필요).
2. **오류 재시도(5장) 대상 범위** — PRD는 "오류 발생 시"로만 명시한다. 이 Plan은 tx2(노드 연속 실행) 중 발생하는 모든 예외(DB 오류 포함)를 재시도 대상으로 본다. 비즈니스 예외(예: 설정값 오류처럼 재시도해도 같은 결과)도 같은 취급이 맞는지 PL 확인.
3. **WAIT 대기 단위 상한** — PRD·API_SPEC에 MINUTE/HOUR/DAY 단위는 있으나 상한값 명시가 없다(예: 365일 대기 허용?). 빌더 검증(워크플로우 구조 2/3)에서 범위를 둘지 PL 확인.

## 9. PRD 10.3 대응 — 워크플로우 관련 완료 기준 검증 방법

| PRD 10.3 항목 | 이 Plan에서 담당하는 설계 | 검증 방법 |
|---|---|---|
| 6.4 예시 구조가 클릭/미클릭 고객에 따라 다른 메시지를 보낸다 | 3.2 CONDITION 판정(EMAIL_CLICKED) | 통합 테스트: 6.4 구조대로 인스턴스 생성 → 한 고객은 클릭 이벤트 기록, 한 고객은 미기록 → 다음 스케줄러 주기에 서로 다른 SEND_EMAIL 단계로 갈라지는지 확인(엔진 4/4 작업에서 수행) |
| 6.4 예시 워크플로우에서 경로에 따라 VIP 쿠폰과 일반 쿠폰이 각각 발급된다 | 3장 SEND 노드가 `config.couponId`를 `step_id` 경유로 `send_log`에 연결(`MessageComposer.findCouponIdByStepId`, 이미 구현됨) | 통합 테스트: 두 경로의 `send_log`에 서로 다른 `coupon_id`로 쿠폰이 발급되는지 확인 |
| 야간 보류된 메일 뒤의 WAIT는 실제 발송 시각부터 계산되어 클릭 분기가 정상 동작한다 | 3.1·4.2(발송이 `next_attempt_at`으로 다음 날 08:00까지 보류됐다가 실제 SENT된 `sent_at` 기준으로 `wake()`가 계산) | 통합 테스트: 20:50 이후 선점되어 보류된 SEND → 다음 날 08:00 실제 발송 → `sent_at` 기준으로 `next_run_at`이 설정되는지 확인 |
| 1만 명 대상 SEGMENT_SCHEDULED 워크플로우가 한 번의 스케줄 주기 안에서 모두 큐에 적재된다 | 6.1 트리거(500건씩 일괄 INSERT, 외부 호출 없음) | 통합 테스트: 1만 customerId로 `startSegmentScheduled` 호출 → 단일 호출 안에서 1만 건 `workflow_instance`가 모두 생성되는지 확인(W4 부하 작업에서 10만 건 규모로 재검증) |
| 처리 도중 서버를 강제 종료해도 RUNNING 인스턴스는 10분 뒤 복구되고, SENDING 건은 다시 나가지 않는다 | 5장 `WorkflowRecoveryJob` | 단위 테스트: `updated_at`을 11분 전으로 만든 RUNNING 행에 복구 스케줄러 실행 → WAITING(next_run_at=now)으로 바뀌는지 확인. SENDING 쪽은 발송 큐 Plan에서 이미 검증 완료 |
| 적재 후 발송 전에 수신거부한 고객에게는 발송되지 않는다(SKIPPED) | 3장 SEND 노드가 호출하는 `enqueueWorkflowStep` 내부의 수신동의 확인(발송 큐 Plan 2장, 이미 구현됨) | 발송 큐 Plan에서 이미 검증 — 워크플로우 SEND 노드는 같은 `SendQueueService` 메서드를 그대로 재사용하므로 추가 테스트 불필요 |

## 10. PL 승인

- **승인 상태: 승인 대기.**
- CLAUDE.md("워크플로우 엔진은 코드를 쓰기 전에 Plan을 먼저 제시하고 승인받는다")에 따라, **W3 구현(구조 검증·엔진·트리거·상태 전이)은 아래 승인 기록이 남기 전에는 시작하지 않는다.**
- 승인 절차: 이 파일을 포함한 PR을 올리거나 PL에게 직접 공유 → 8장 미확정 사항 3건에 대한 답변을 받아 반영 → PL이 PR 코멘트 또는 메시지로 승인 → 아래에 기록.

```
승인자:
승인 일시:
승인 방식(PR 코멘트 / 메시지 등):
미확정 사항 답변: (1) CUSTOMER_REGISTERED 세그먼트 멤버십 — / (2) 재시도 대상 범위 — / (3) WAIT 대기 상한 —
```
