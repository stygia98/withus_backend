# withus_backend

위드어스 백엔드 (Spring Boot 4.0.x, JDK 21, MyBatis, Flyway, PostgreSQL 17).
규칙은 메인 저장소의 `CLAUDE.md` 4·6장, 협업 절차는 `docs/workflow-git.md` 를 따른다.

## 실행
```bash
# 1) 메인 저장소 infra/.env.example 을 infra/.env 로 복사하고 값 입력
# 2) cd ../infra && docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
./mvnw test          # PR 전 필수 (DB 컨테이너 필요)
```
- Swagger UI: http://localhost:8080/swagger-ui/index.html
- Mailpit: http://localhost:8025
- `local` 프로필은 `../infra/.env` 를 읽는다. 없으면 OS 환경변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 를 쓴다.

## 팀원용 사용법

**로그인해서 API 테스트하기 (Swagger)**
1. `GET /api/v1/auth/csrf` 실행 → `XSRF-TOKEN` 쿠키 발급 (Swagger 가 이후 POST 에 헤더를 자동으로 붙인다)
2. `POST /api/v1/auth/login` 에 `infra/.env` 의 `OWNER_EMAIL` / `OWNER_PASSWORD` 입력
3. 이후 모든 API 가 쿠키로 인증된다. Access 30분 만료 시 `POST /api/v1/auth/refresh`

**컨트롤러 작성 규칙**
```java
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")            // 권한표: PRD 3장
@PostMapping("/api/v1/segments")
public ApiResponse<SegmentResponse> create(@Valid @RequestBody SegmentRequest req,
        @AuthenticationPrincipal AuthMember me) {          // me.memberId() → created_by
    return ApiResponse.ok(segmentService.create(req, me.memberId()));
}
```
- 응답은 항상 `ApiResponse.ok(data)`, 목록은 `ApiResponse.ok(PageResponse.of(content, page, size, total))`
- 오류는 도메인별 `ErrorCode` enum 을 만들어 `throw new BusinessException(SegmentErrorCode.SEGMENT_INVALID_RULE)` (예시: `auth.domain.AuthErrorCode`). 새 코드는 `docs/api/API_SPEC.md` 12장에도 추가
- `@Valid` 실패는 자동으로 400 `COMMON_INVALID_INPUT` + 필드별 `details`
- 공개 경로(`/t/**`, `/api/v1/public/**`, `/api/v1/unsubscribe/one-click/**`, `/api/webhooks/**`)는 인증·CSRF 없이 열려 있다. 그 외 경로를 공개해야 하면 `auth.security.SecurityConfig` 변경 → PL 리뷰

**테스트**
- `@SpringBootTest @AutoConfigureMockMvc @Transactional` + 로컬 Docker DB (예시: `src/test/java/com/withus/auth/AuthFlowTest.java`)

## 버전 메모
MyBatis 스타터(4.0.1)가 Spring Boot 4.0.x 까지만 지원해 4.0.8 을 쓴다. 4.1 지원 버전이 나오면 올린다.

## 구간 간 인터페이스 (PRD 10.1, 시그니처 변경은 PL 리뷰)
| 인터페이스 | 제공 | 호출 |
|---|---|---|
| `segment.service.SegmentService` | 팀원1 | 팀원2 |
| `customer.service.ConsentService` | 팀원1 | 팀원2 |
| `tracking.service.TrackingLinkService` | 팀원3 | 팀원2 |
| `tracking.service.TrackEventRepository` | 팀원3 | 팀원2, 팀원1 |
| `coupon.service.CouponService` | 팀원3 | 팀원2, 팀원1 |
| `common.render.PlaceholderRenderer` | 팀원3 | 팀원2 |

제공 측은 W1 수요일까지 고정값을 돌려주는 stub 구현을 먼저 병합하고, 이후 실제 구현으로 교체한다.

## Flyway
`src/main/resources/db/migration`. 적용된 파일은 수정 금지, 번호 대역은 `docs/workflow-git.md`.
