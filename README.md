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
