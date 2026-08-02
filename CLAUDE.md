---
title: Turkey 프로젝트 지침
status: active
updated_at: 2026-07-24
owner: WEB-Team7-Turkey
source_of_truth: true
---

# CLAUDE.md

Claude Code가 Turkey(퀵배송 매칭 서비스) 저장소를 수정할 때 지켜야 하는 규칙 문서다.
배경·설계 근거·의사결정 상세는 GitHub Wiki(ADR)와 `docs/`를 정본으로 하고, 이 문서는 **작업 시 즉시 참조할 규칙과 확정 사실**만 담는다.

## 프로젝트 문서

- [프로젝트 컨텍스트](docs/00-project-context.md) — 목적, 아키텍처, 기술 스택 개요 (정본)
- [기능 명세](docs/01-functional-spec.pdf) — 기능별 사전조건·처리흐름·성공조건·예외처리·우선순위
- [도메인 정책](docs/02-domain-policy.md) — 상태 전이, 배차·취소·포인트·정산·SSE 정책
- [ERD](docs/03-erd.md) — 핵심 엔터티, 관계, 애플리케이션-DB 제약 역할 분담
- [로깅 공통 규칙](docs/logging-guidelines.md) — Filter+MDC / Service / 선택적 AOP 규칙
- [로컬 개발 환경(DB)](docs/05-local-dev.md) — Docker MySQL 기동·초기화·트러블슈팅

문서 간 내용이 충돌하면 ADR → ERD/DDL → 도메인 정책 → 기능 명세 → 프로젝트 컨텍스트 순으로 판단한다.

## 이슈 기반 기능 개발 절차

이슈 번호가 있는 기능 개발은 `mvp-feature` 스킬(`.claude/skills/mvp-feature/`)이 정본 절차다.
이슈 읽기 → 범위 판정 → 계약 확정(사람 확인) → 구현 → 단위·통합·E2E 테스트 → 작업 기록 문서까지 한 사이클로 진행한다.
프롬프트에 이슈 번호가 등장하면 `UserPromptSubmit` 훅(`.claude/hooks/issue-mvp-trigger.py`)이 이 스킬을 로드하도록 안내한다.

작업 기록은 `docs/worklog/`에 이슈당 한 파일로 남긴다. 사람이 고른 선택과 그 근거를 여기에 적는다.

## 작업 원칙

- 기존 디렉터리 구조와 팀 합의를 우선한다. 요청 없이 대규모 구조 변경·기술 교체를 하지 않는다.
- 새 라이브러리·AWS 서비스·인프라를 추가하기 전에 기존 기술로 해결 가능한지 먼저 검토한다.
- 확정된 정책(아래 「확정된 결정」)은 임의로 바꾸지 않는다. 변경이 필요하면 코드보다 먼저 영향 범위와 대안을 설명한다.
- 인증·배차·포인트·상태 전이·정산처럼 정합성이 중요한 코드는 트랜잭션 경계와 실패 시 동작을 명확히 한다.
- 정상 흐름뿐 아니라 중복 요청, 동시 요청, 연결 종료, 재시도, 부분 실패를 고려한다.
- 불확실한 정책은 추측해 고정하지 말고 「확인이 필요한 항목」에 남긴다.

## 금지 사항

- **Spring Security, Spring Batch, Spring AI 사용 금지**
- **Docker 사용 제약 없음**(2026-07-29 변경). 로컬 개발 DB(`backend/docker-compose.yml`) 외에도 애플리케이션 컨테이너화, 배포 파이프라인, 테스트 인프라(Testcontainers 등)에 자유롭게 사용할 수 있다. (변경 전 정책: 로컬 개발 DB 실행 용도로만 허용)

## 저장소 구조

- 백엔드(Spring Boot)와 프론트엔드(`frontend/`)를 하나의 GitHub 모노레포로 관리한다.
- 프론트엔드 라우트는 액터(고객/라이더) 기준으로 구성하며, 폴더명은 ERD 엔터티에 맞춰 `customer/` · `rider/`를 사용한다(ADR-0002, 2026-07-23).
- 기능별 패키지 구성: `com.turkey.quick.{customer, rider, order, matching, location, payment, common}`
- `common` 하위: `config`, `exception`, `response`
- DB 스키마는 Flyway(`src/main/resources/db/migration`)로 관리하며 앱 기동 시 자동 실행된다.
- CI/CD는 경로 필터로 프론트엔드 배포와 백엔드 배포를 분리한다(`.github/workflows`).

## 기술 스택

- Backend: Java 21, Spring ruBoot 3.4.x, Gradle, Lombok, JUnit + AssertJ, SSE, Flyway
- Data: MySQL 8.4, Redis
- Infra: AWS EC2(백엔드), S3(프론트 빌드 산출물), CloudFront(CDN), GitHub Actions
- 데이터 접근 기술은 jpa
- Frontend: React, TanStack Router(파일 기반 라우팅, `routeTree.gen.ts` 자동 생성), TanStack Query, Orval(OpenAPI 기반 API 클라이언트 자동 생성), axios, shadcn/ui

## 도메인 상태값

상태 변경은 요청 값으로 덮어쓰지 않고, **현재 상태 + 수행 행위**로 검증한다. 허용되지 않은 전이는 서버에서 거부한다.

**라이더 상태**: `UNAVAILABLE`(운행 종료/로그아웃) · `AVAILABLE`(배차 가능) · `BUSY`(배송 수행 중)

**배송 상태**: `WAITING` → `ASSIGNED` → `MOVING_TO_PICKUP` → `PICKED_UP` → `DELIVERING` → `COMPLETED`, 그리고 `CANCELED`

원자적으로 처리해야 하는 전이(하나의 트랜잭션):

- 배차 확정: 배송 `WAITING→ASSIGNED` + 라이더 `AVAILABLE→BUSY` + 배차 관계 생성
- 배송 완료: 배송 `DELIVERING→COMPLETED` + 라이더 `BUSY→AVAILABLE` + 정산 내역 생성
- 고객 일반 취소는 배차 전에만 허용: 배송 `WAITING→CANCELED`

**동시성 보장 조건** (배차):

- 하나의 배송요청에는 최대 한 명의 라이더만 배정된다.
- 하나의 라이더는 동시에 최대 한 건의 진행 중 배송만 담당한다.
- 경쟁에서 실패한 수락 요청은 명확한 실패 결과를 받는다(부분 성공 없음).
- 구체적 구현 방식(DB 락 vs 조건부 업데이트)은 ADR에서 결정한다.

**진행 중 배송요청 제한**:

- 고객은 동시에 진행 중 배송요청(`WAITING`~`DELIVERING`)을 최대 1건만 가진다. `COMPLETED`/`CANCELED` 주문만 있는 고객만 새 배송요청을 생성할 수 있다.
- 배차 이후(`ASSIGNED` 이상) 취소는 MVP 범위에서 제외한다(라이더 배차 포기 기능도 MVP 범위 밖).

## 프론트엔드 아키텍처

라우트는 액터(고객/라이더) 기준으로 구성한다(ADR-0002, 결정일 2026-07-23).

- 동적 세그먼트는 `$deliveryId`로 통일한다(엔터티 `delivery_order`와 매칭).
- 인증 라우트는 가드 요구에 따라 분리한다: `auth/`(로그인 전, 비인증 가드) vs `account/`(로그인 후, 인증 가드 — 회원정보·계정 관리·알림함은 고객/라이더 공용, `account/notifications`).
- 인가는 프론트 라우트 가드가 1차, 서버가 2차다(Spring Security 미사용 원칙 유지).
- **보호가 필요한 화면은 `customer/_authed/` · `rider/_authed/` 하위에 둔다**(#195). `_authed` 는 경로 없는
  레이아웃이라 URL 은 바뀌지 않는다. 그 밖에 만들면 가드 없이 열린다 — 백엔드에서 인터셉터
  `addPathPatterns` 등록을 빠뜨리는 것과 같은 종류의 실수다. `account/`(역할 무관 인증) ·
  `auth/`(비인증)는 각 디렉터리의 `route.tsx` 가 가드를 담당한다. 판정 로직은 `shared/auth/guard.ts`.
- 401 공통 처리는 `axiosInstance` 인터셉터 한 곳에 있다(#195). 화면 컴포넌트는 401 을 개별 처리하지
  않는다. 단 세션 확인·로그인 경로는 제외한다 — 전자의 401 은 가드의 정상 판정 신호, 후자는 자격 증명
  오류라 폼에 표시해야 한다.
- `시스템` 대분류(요금·상태 전이·포인트 원장·배차)는 별도 화면을 두지 않는다. 각 화면이 API로 소비한다.

**라이더 상태 ↔ 화면 매핑** — 상태 전이는 별도 상태변경 화면이 아니라 각 화면의 버튼으로 일어난다:

| 상태 | 화면 | 전이 트리거 | 위치 전송 |
|---|---|---|---|
| `UNAVAILABLE` | `rider/index`(홈) | "콜 받기" → `AVAILABLE` | 없음 |
| `AVAILABLE` | `rider/requests`(콜 목록) | "운행 종료" → `UNAVAILABLE` / 콜 수락 → `BUSY` | 저빈도(idle) |
| `BUSY` | `rider/delivery`(진행 배송) | 배송 완료 → `AVAILABLE` | 고빈도(busy) |

- `rider/delivery`(진행 중 배송)는 id 없는 고정 경로다. 동시 진행 배송이 최대 1건이므로 동적 세그먼트가 불필요하고, 새로고침·재로그인 후에도 "진행 중 배송 조회 → 해당 화면 복귀"로 복구 가능해야 한다.
- 위치 전송은 라우트가 아니라 공용 훅 `shared/hooks/useLocationSender`로 구현하고, 라이더 상태를 인자로 받아 화면(홈 제외, `rider/requests`·`rider/delivery`) 생명주기에 부착한다. 위치 수집은 **포그라운드 실행 전제**이며(정책 §7), 비활성 탭에서의 백그라운드 전송은 보장하지 않는다.
- 고객의 실시간 위치 구독은 별도 훅 `shared/hooks/useTrackingStream`으로 SSE 연결·재연결·종료를 처리한다.
- API 연동은 Orval 자동 생성 훅(`src/api/generated/`, 수정 금지)을 기본으로 사용한다.

## 확정된 결정

- 하나의 계정은 `CUSTOMER` 또는 `RIDER` 중 하나의 역할만 가진다(동시 지원 안 함).
- 인증은 **쿠키 기반 서버 세션** 방식. 세션은 Redis에 저장하고, 쿠키에는 세션 식별자만 담는다.
  - Spring Security 없이 필터/인터셉터로 세션 확인·역할 검증·만료·로그아웃을 직접 구현한다.
  - **인증이 선언적으로 자동 적용되지 않는다(Spring Security의 SecurityFilterChain과 다른 점).**
    보호할 API마다 해당 인터셉터의 `addPathPatterns`에 경로를 직접 등록해야 인증이 걸린다.
    (고객 예시: `customer/config/CustomerWebMvcConfig`, #27) 새 고객/라이더 전용 API를 추가할 때
    이 등록을 빠뜨리면 그 API는 인증 없이 열린 채로 배포된다 — 리뷰 시 반드시 확인할 것.
- Redis는 현재 **세션 저장 / 라이더 최신 위치 / GEO 위치 검색 / 휴대전화 인증번호(TTL) / SSE 이벤트
  팬아웃(Pub/Sub, #246)** 에 쓴다. 영속 원본 저장소로는 쓰지 않는다.
- Redis 배포 방식은 **EC2 인스턴스에 직접 설치**(2026-07-29 변경, 디스커션 #176). 관리 부담을 줄이는
  ElastiCache(관리형)를 먼저 검토했으나 **비용 문제**로 EC2 직접 설치로 결정을 뒤집었다.
  MySQL 배치 방식(EC2 직접 설치 vs RDS)도 별도로 아직 미결(아래 「확인이 필요한 항목」).
- 영속성·트랜잭션 정합성이 필요한 데이터는 MySQL이 정본(사용자·배송요청·배차·상태·포인트 원장·정산·위치 이력).
- 실시간 라이더 위치 전달은 **SSE** 사용(Polling 아님). 위치가 실제로 변경됐을 때만 이벤트 전송.
  - 다중 인스턴스 환경에서는 위치를 처리한 인스턴스가 Redis Pub/Sub으로 발행하고, 해당 emitter를
    들고 있는 인스턴스가 구독해 고객에게 전달한다(#246). `SseEmitter` 레지스트리는 인스턴스
    로컬(인메모리)로 유지한다.
- 라이더 상태와 배송 상태를 분리한다.
- 여러 인스턴스로 수평 확장 가능한 모놀리식 Spring Boot WAS(코드 수준에서만 책임 분리, MSA 아님).
- 프론트 빌드 산출물은 S3에 배포하고 CloudFront로 제공. 정적 요청은 CloudFront·S3, API·SSE는 EC2 Spring Boot가 처리.
- 결제는 MVP에서 포인트 기반 또는 모킹 흐름 우선(실 PG 연동 아님).
- 용어: "퀵 신청" 대신 **"배송요청"**을 사용한다.
- 핵심 테이블(ERD 확정): `member`, `rider_profile`, `rider_payout_account`, `term`, `member_term_agreement`, `delivery_order`, `order_status_history`, `fare_policy`, `item_type_surcharge`, `order_fare_snapshot`, `delivery_proof`, `rider_location_history`, `point_wallet`, `point_charge`, `point_transaction`, `rider_settlement`, `rider_withdrawal`. 고객/라이더는 별도 테이블 없이 `member.role`로 구분하며, 배차는 별도 테이블 없이 `delivery_order.assigned_rider_id` FK로 처리한다. `member_notification`은 2·3차 MVP 이후 구현 예정이라 아직 없다. 세부 컬럼·제약은 `docs/03-erd.md`와 최종 DDL을 따른다.

## 협업

- 흐름: Issue → Branch → 구현·테스트 → PR → Code Review → Merge (Squash Merge 우선)
- 브랜치명·PR에 Issue 번호와 작업 내용을 포함한다. PR에는 변경 내용·선택 이유·테스트 결과·집중 리뷰 영역을 적는다.
- 배차 동시성·상태 전이·정산·포인트 등 핵심 로직은 최소 2명 교차 리뷰한다.
- 기술적 의사결정은 Wiki의 ADR 문서(ADR-001~010)에 `문제 → 대안 → 결정 → 이유 → 장단점 → 영향 → 검증` 형식으로 기록한다.

## 확인이 필요한 항목

- 배차 동시성 제어 방식(DB 락 vs 조건부 업데이트) >> [ADR-006](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90006-%EB%B0%B0%EC%B0%A8-%EB%8F%99%EC%8B%9C%EC%84%B1-%EC%B2%98%EB%A6%AC)에서 조건부 UPDATE(Compare-And-Set)로 확정됨(2026-07-28). 주문→라이더 순서 고정, 실패 시 재조회로 사유 구분(#56에서 구현)
- 동일 요청 재전송에 대한 API 멱등성 정책(요청 식별값 기준)
- 예상 요금과 최종 요금의 차이 허용 >> 허용하기로 했음
- 포인트 차감·환불 시점, 포인트 동시성 처리 방식(선차감 vs 결제 승인 모킹 포함)
- 라이더 위치 이력의 MySQL 저장 기준(시간/이동거리/상태변화)
- SSE 타임아웃·재연결·heartbeat·중복 연결 정책 >> **#77 에서 확정함**(사람 확인, 2026-07-30).
  emitter 타임아웃 5분 / heartbeat 15초 / 동일 주문 연결 한도 3 / stale 임계 45초 /
  서버가 `retry: 3000` 지정. 값은 `location/sse/TrackingStreamPolicy` 한 곳에 있다.
  **`new SseEmitter()` 로 만들면 Tomcat 기본값 30초가 적용되고 그 30초는 쓰기로 갱신되지 않는다** —
  이벤트가 흐르고 있어도 끊긴다. 타임아웃은 반드시 명시할 것. CloudFront 오리진 응답 타임아웃
  상한이 120초·keep-alive 300초라(사람 확인) 이 값들은 안전하지만, **오리진 응답 타임아웃을
  20초 아래로 내리면 heartbeat 도 함께 내려야 한다**
- **SSE 팬아웃은 주문별 채널명 + 패턴 구독이다**(#78, 2026-07-30). 채널은 `tracking:order:{id}` 이고
  모든 인스턴스가 `tracking:order:*` 를 구독해 자기 emitter 가 있는 것만 전송한다. 주문별로
  구독·해제하지 않은 이유: 같은 주문이 1→0 과 0→1 을 동시에 겪을 때 구독 호출 순서가 뒤집혀
  "구독은 없는데 연결은 있다"가 되면 **고객 스트림이 조용히 죽는다.** 대가는 모든 인스턴스가 모든
  이벤트를 받는 것이고, 채널 이름이 주문별이라 비용이 문제되면 값 형식 변경 없이 전환할 수 있다.
  **`RedisMessageListenerContainer` 의 `taskExecutor` 를 반드시 지정할 것** — 미지정이면 메시지마다
  새 OS 스레드를 만든다
- **`Pub/Sub` 페이로드 형식이 세 번째 배포 호환성 표면이다**(#78). Flyway 마이그레이션, Redis 값
  형식에 이어. 수신단이 페이로드를 파싱하지 않고 그대로 흘리므로 롤링 배포 중 구·신 형식이 그대로
  클라이언트로 나갈 수 있다 — **필드 추가만 허용하고 제거·의미 변경은 하지 않는다**
- **오류 응답에 Content-Type 을 명시해야 한다**(#77 에서 드러남, `GlobalExceptionHandler` 에 적용).
  지정하지 않으면 스프링이 `Accept` 로 컨텐트 협상을 하고, 브라우저 `EventSource` 는
  `Accept: text/event-stream` 만 보내므로 401·409·429 가 전부 **406** 으로 바뀌어 상태코드와
  `ApiResponse` 본문을 둘 다 잃는다
- `server.shutdown` 을 설정한 적이 없는데 graceful 로 동작한다(#77 에서 테스트 로그로 확인:
  `Graceful shutdown aborted with one or more requests still active`). SSE 는 끝나지 않으므로
  **종료마다 대기 시간을 소진한 뒤 강제 중단**돼 배포가 그만큼 느려진다. `immediate` 로 명시할지 미결
- **ElastiCache 클러스터 모드를 켜면 현재 SSE 팬아웃이 동작하지 않는다**(#78). cluster mode enabled
  에서는 Redis 7 의 sharded pub/sub(`SSUBSCRIBE`)이 필요한데 `RedisMessageListenerContainer` 는
  일반 pub/sub 만 다루고 `PSUBSCRIBE` 는 클러스터에서 문제가 된다. **클러스터 모드 비활성을 전제로
  설계했다** — 배포 구성 확정 시 확인 필요
- **Redis Pub/Sub 은 로직 DB 로 격리되지 않는다**(#78). 채널은 `SELECT` 를 무시하므로 테스트(DB 1)와
  개발용 앱(DB 0)이 **같은 채널을 공유한다.** `DatabaseCleaner` 의 TRUNCATE 가 AUTO_INCREMENT 를
  리셋해 테스트 주문이 매번 낮은 id 를 받으므로, 개발용 앱을 띄운 채 테스트를 돌리면 채널명이
  겹칠 수 있다. 채널 접두어를 프로파일로 분리할지 미결
- **emitter 레지스트리는 테스트 사이에 살아남는다**(#77). 인메모리 싱글턴이라
  `IntegrationTestSupport`(MySQL·Redis 만 비운다)로 정리되지 않고, 앞 테스트의 연결이 남아 실제로
  한 테스트를 실패시켰다. 세 번째 클리너를 둘지 미결
- SSE 연결 중 세션 만료·로그아웃 처리(#77). 인터셉터는 연결 시점에 한 번만 도므로 세션이 만료된
  뒤에도 **emitter 타임아웃(최대 5분)까지 위치가 흐른다.** heartbeat tick 에서 세션 존재를 확인하면
  15초로 줄지만, 연결마다 세션 식별자를 JVM 에 들고 있어야 해 새 위험 표면이 생긴다 — 미결
- 주문 완료·취소 시 **능동적** SSE 연결 종료가 없다(#80 범위). 채널 키가 주문이라 발행이 멈춰
  스트림은 조용해지지만, 연결 자체는 타임아웃까지 열려 있고 완료 알림이 프론트로 가지 않는다
- 라이더 위치 전송 주기와 필터 임계값 >> **#81 에서 확정함**(사람 확인, 2026-07-29). 전송 주기
  AVAILABLE 30초 / BUSY 5초 / UNAVAILABLE 미전송, 최소 이동 거리 20m, 허용 최대 속도 50 m/s
  (180km/h), 정확도 상한 100m, 허용 과거 60초·미래 오차 5초, 정지 시 강제 전송 120초, Redis 최신
  위치 TTL 10분. **서버(`location/service/LocationAcceptancePolicy`)와 프론트 1차 필터가 같은 값을
  쓴다** — 클라이언트가 통과시킨 좌표는 서버도 통과해야 두 필터가 서로 싸우지 않는다. 부하 테스트
  결과에 따라 조정할 수 있다(#82 비고).
- 위치 갱신 실패 응답의 경계 >> **#81 에서 확정함**(사람 확인). 좌표 범위 밖·필수 값 누락·정확도
  음수·미래 시각은 400 이지만, **정확도 상한 초과와 60초 초과 과거 fix 는 200 + `reason`** 으로
  수용·폐기한다(실내·지하 측위나 탭 복귀 직후에 정상적으로 발생해 클라이언트가 고칠 것이 없다).
  그래서 정확도 상한을 `@DecimalMax` 로 달 수 없다 — Bean Validation 위반은 400 이 된다.
- ~~운행 종료 후 최대 10분간 라이더 최신 위치가 Redis 에 남는다(#81)~~ **해소(#54, #83)**: GO_OFFLINE
  시 `RiderGeoRepository.remove`(ZREM)를 호출해 운행 종료 즉시 배차 후보에서 뺀다. `location`
  패키지 단순화(#297)로 원래 쓰던 `RiderLocationStore`가 삭제되면서 #83이 만든 GEO 저장소로
  교체했다(같은 의미의 멱등 삭제 연산이라 그대로 대체). remove 를 DB 트랜잭션 커밋 전에 호출하는
  트레이드오프는 부하 테스트 후 재검토(worklog 2026-07-31-54 참조).
- 위치 갱신 응답의 `NON_MONOTONIC` 이 **두 원인을 겸한다**(#250, 사람 확인 2026-07-29). 클라이언트가
  순서를 어겨 보낸 것과 **인스턴스 간 경쟁에서 진 것**(`saveIfNewer` 가 거절)이 같은 사유·같은 로그
  라인으로 나간다. 계약을 늘리지 않으려고 통일한 것이고, 경쟁 빈도를 알아야 할 때(부하 테스트,
  인스턴스 수 조정) 값을 나눌지 판단한다
- **Redis 값 형식 변경도 배포 호환성 검토 대상이다**(#250 에서 처음 드러남). 최신 위치 값 형식을
  바꾸면서 이전 형식 값이 새 파서로 읽히지 않게 됐다 — 조건부 갱신이 덮으므로 첫 위치 요청에서
  해소되고 TTL 이 10분이라 감내했지만, **롤링 배포 중 구·신 버전이 공존하면 서로의 값을 못 읽는
  구간이 생긴다.** Flyway 마이그레이션 호환성(「확정된 결정」)과 같은 종류의 문제인데 Redis 값에서는
  규칙이 없다. 규칙으로 못 박을지 미결
- 배송 완료 인증 데이터 구조(단건/다건, 사진·수령인 확인·인증코드 중 채택 범위)
- 정산 생성 시점과 실패 처리 방식
- 주소·좌표 컬럼 구조, 배차 결과를 별도 테이블로 둘지 주문 FK로 단순화할지
- 포인트 잔액 캐시 컬럼 유지 여부, 논리 삭제 사용 범위
- EC2 구성(사이징 등) 및 MySQL 배치 방식(EC2 직접 설치 vs RDS). Redis는 EC2 인스턴스 직접 설치로
  결정됨(위 「확정된 결정」, #176) — 다중 인스턴스 WAS에서 이 Redis 한 대가 단일 장애점이 되는지는
  별도 확인 필요.
- S3·CloudFront·도메인·인증서 구성, 캐시/invalidation 정책. **#26 결정에 따라 CloudFront 배포
  하나에 `/api/*`(및 SSE 경로) behavior를 추가해 EC2를 origin으로 묶는 구성을 2026-07-29 기준
  실제 AWS에 반영 완료(S3·CloudFront·`/api/*` behavior 연결, SSE 경로 CachingDisabled 설정 확인 완료).**
- 프론트 Origin과 API Origin 분리 시 CORS·쿠키 설정. **#26에서 위 CloudFront 단일 배포 전제로
  `SameSite=Lax`+프로파일별 `Secure`로 구현함(`common/auth/SessionCookie`). 만약 나중에 API가
  별도 CloudFront 배포나 EC2 직접 노출로 바뀌면(크로스사이트) `SameSite=None`+EC2 자체 HTTPS로
  재검토 필요.**
- GitHub Actions의 AWS 인증 방식(OIDC + 최소 권한 IAM Role 권장)과 배포 권한 범위
- Redis 장애 시 세션·위치 기능 대응 방식
- CSRF 대응 정책 (`SameSite=Lax`가 어느 정도 기본 방어가 되지만 별도 토큰 방식 여부는 미결)
- 세션 슬라이딩 갱신(활동 중 TTL 연장) 여부 >> **#27에서 "안 함"으로 결정함**(로그인 시점 2시간
  고정 TTL 유지, `SessionStore.findMemberId()`는 조회만 하고 TTL을 건드리지 않음). 프론트가 세션
  확인 API를 자주 호출하는 흐름이 붙으면서 "너무 자주 로그아웃된다"는 문제가 실제로 생기면
  재검토.
- 고객·라이더 세션 인증 인터셉터가 각자 패키지에 중복돼 있음(`customer/auth/CustomerSessionInterceptor`
  #27/#29, `rider/auth/RiderSessionInterceptor` #50) — 둘 다 쿠키 파싱 → 세션 조회 → 회원
  조회 → 역할·상태 확인 → 실패 시 만료 쿠키 응답 구조가 거의 같다. 세 번째 액터가 생기거나 둘의
  로직이 실제로 갈리기 전까지는 `common/auth`로 공용 추출하지 않기로 함(지금 추출하면 아직
  존재하지 않는 차이를 미리 상정해 설계해야 함).
- 계정 정지(SUSPENDED) 기능 자체가 아직 없음(관리자 기능 미구현) — `member.status`는 정의돼 있지만 정지시키는 코드 경로가 없음
- Redis 테스트 방식 >> **해소됨**(2026-07-29, 사람 확인). 통합·E2E는 인메모리 대체 대신 **로컬 Docker
  Redis(`redis:7.4`)에 붙는다.** 단위 테스트는 계속 인메모리 대체를 쓴다(스프링 없이 도는 것이 층 구분의
  핵심). 테스트는 개발용과 다른 로직 DB(`database: 1`)를 쓰고 `RedisCleaner`가 매 테스트 전 `FLUSHDB`
  한다 — MySQL과 달리 개발 세션은 지워지지 않는다. 상세는 `docs/05-local-dev.md` 「Redis」와
  `.claude/skills/mvp-feature/references/testing.md`.
  - **개발 PC에 Redis를 직접 설치하지 않는다.** 호스트 Redis는 `127.0.0.1:6379`, 컨테이너는 `*:6379`에
    바인딩하는데 더 구체적인 호스트 쪽이 이겨서, 애플리케이션·테스트가 컨테이너가 아니라 호스트
    인스턴스에 **조용히** 연결된다(2026-07-29에 실제로 그 상태였다 — 호스트 8.8.0 vs 컨테이너 7.4.10).
    `RedisCleaner`가 연결된 인스턴스의 `redis_version`을 확인해 이 상황을 테스트 실패로 만든다.
  - 남은 것: **실제 TTL 만료 동작**(키가 시간이 지나 사라지는 것)은 여전히 검증하지 않는다 — 대기 시간
    때문이다. 그리고 **CI는 아직 테스트를 돌리지 않는다**(`deploy.yml`이 `-x test`). CI에서 켜려면
    MySQL·Redis 서비스 컨테이너가 필요하다
- `GlobalExceptionHandler`에 `HttpMessageNotReadableException` 핸들러가 없고
  `ResponseEntityExceptionHandler`를 상속하지도 않음(#81 에서 발견) — 본문 JSON 파싱 실패 400이
  `ApiResponse` 형태가 아닌 스프링 기본 오류 본문으로 나갈 것으로 보임. 프론트가 의존하는 응답
  계약이 깨지는 지점이고 **전체 엔드포인트에 해당**한다. 실제 본문을 확인하고 별도 이슈로 올릴지 판단 필요
- 외부 SMS 발송 연동(현재는 로그만 남기는 모킹) — 실제 벤더 선정 시 `SmsSender` 구현체 교체 필요(#20)
- 인증번호 확인(`PhoneVerificationService.confirm`)에 원자적 보호가 없어, 동시에 같은 유효 코드로 확인
  요청이 오면 인증 완료 토큰이 중복 발급될 수 있음(#21) — **수평 확장으로 위험도 격상**(2026-07-29).
  단일 인스턴스에서는 경쟁 창이 좁았지만, 인스턴스가 늘면 두 요청이 서로 다른 JVM에서 동시에 진행되어
  통과할 창이 넓어진다. "재검토 필요"에서 "고쳐야 함"으로 판단을 옮길지 결정 필요
- 인증 완료 토큰 소비(조회+1회성 삭제, `VerificationCodeStore.consumeVerifiedToken`)는 `#25`(고객
  회원가입)에서 구현·적용됨. `#22`(아이디 찾기)는 아직 이 메서드를 쓰지 않으므로 구현 시 연결 필요
- 회원가입(`#25`) 필수 약관 목록을 프론트가 조회하는 API(`#72`)가 아직 없어, `agreedTermIds`를
  채우는 방법이 정해지지 않음 — `#72` 구현 시 응답 스키마(`termId`)를 맞춰야 함
- 회원가입 동시 요청(같은 아이디로 동시 가입)은 DB unique 제약 + 예외 변환으로 막지만, 실제
  멀티스레드 경쟁 재현 통합 테스트는 없음(#25)
- Orval 생성 훅의 `error` 가 전부 `AxiosError<unknown>` 임(#194) — 스펙에 공통 에러 응답 스키마가
  선언돼 있지 않다. springdoc 에 에러 스키마를 노출하고 orval `override.errorType` 을 지정할지,
  화면에서 `unknown` 을 좁혀 쓸지 미결
- 역할 무관 세션 확인 API(`GET /api/session`)가 없어, 프론트 가드가 고객·라이더 세션 API 를 둘 다
  조회해 합성한다(#195, 사람 확인). 역할 불일치도 401 이라 한쪽만 보면 "비로그인"과 "다른 역할로
  로그인"을 구분할 수 없기 때문이다. 요청이 2배가 되는 것이 문제되면 역할 무관 세션 API 를 추가하고
  `shared/auth/session.ts` 의 `ensureSessionInfo` 를 단순화한다
- 로그인·회원가입 화면(`customer/login`·`rider/login`·`*/signup`)에 비인증 가드를 걸지 미결(#195).
  현재는 로그인 상태로도 로그인 화면이 열린다(비인증 가드는 `auth/*` 에만 적용)
- 프론트 세션 캐시 유효 시간 5분이 적절한지 미검증(#195). 세션 TTL 2시간 고정·슬라이딩 없음(#27)을
  전제로 정한 값이다
- 새 컨트롤러에 `@Operation(operationId = "...")` 명시가 사실상 필수가 됨(#194). 생략하면 springdoc 이
  메서드명을 쓰고 동명 메서드에 `_1` 을 붙여 프론트 훅 이름이 액터를 구분하지 못한다
  (`useLogin` / `useLogin1`, 배정 순서는 컨트롤러 스캔 순서 의존). 현재는 `OpenApiOperationIdE2ETest`
  실패로만 알게 되는데, 리뷰 체크리스트나 PR 템플릿에 넣을지 미결
- `RiderDeliveryRequestApi`의 `getDeliveryRequest`/`acceptDeliveryRequest`/`skipDeliveryRequest`
  세 메서드에 라이더 식별 파라미터(`AuthenticatedRider`)가 빠져 있음(#55) — #56/#57 구현 시
  `getDeliveryRequests`와 같은 방식(`@RequestAttribute`)으로 추가 필요
- 라이더 콜 목록(`GET /api/rider/requests`, #55)에 `radiusMeters` 상한이 없고 페이지네이션도
  없음 — WAITING 주문이 크게 늘어나는 시점(다도시 동시 운영 등)에 성능 문제가 되면 계약을 다시 열어야 함
- 충전 금액 정책이 잠정값(#32): `CustomerPaymentService` 의 1,000 / 1,000,000 / 1,000원 단위.
  화면 프리셋만 허용하는 화이트리스트로 좁히는 선택지도 있음
- 충전 준비의 동시 재전송은 409 로 거부한다(#32, 실측 8건 중 2건). 순차 재전송은 기존 건을 돌려준다.
  패자에게도 같은 응답을 주려면 삽입을 별도 트랜잭션으로 떼야 함
- 화면 결제수단(카카오페이·휴대폰)과 `PaymentMethod`(`CARD`·`BANK_TRANSFER`)이 불일치(#32).
  `ck_point_charge_method` 도 두 값만 허용 — enum 을 넓힐지 화면을 줄일지 미결
- `PaymentGateway.prepare()` 필요 여부는 벤더에 따라 갈림(#32, 일단 유지). 카카오페이류는 `ready`
  호출 필수, 토스페이먼츠류는 준비 단계에 PG 호출 없음
- 충전 준비 API 가 이슈에 없는 `chargeRequestKey`·`paymentMethod` 를 필수로 받는다(#32). 또 이미 PAID
  인 키로 재요청하면 PAID 건을 그대로 돌려줘 프론트가 맥락 없는 409 를 본다(가드 미구현)
- (#246 후속) 다중 인스턴스 배포 구성 전반 미결 — 채널 설계, ALB 도입, 롤링 배포 등은 구현
  이슈(#77~#80)에서 결정
- (#246 후속) 세션 만료 정리(#52)에 Pub/Sub을 쓸지는 #246 범위(SSE 팬아웃) 밖이라 별도 결정 필요
- ~~PR #284(#54, 라이더 운행 상태 변경 API)가 merge conflict 상태다~~ **해소**: `location` 패키지
  단순화(#297)로 그 PR이 쓰던 `RiderLocationStore`/`RedisRiderLocationStore`가 삭제됐던 것을,
  #83이 만든 `RiderGeoRepository.remove(riderId)`(의미상 완전히 같은 멱등 삭제 연산)로 교체해
  PR #284를 `feature/83-ride-loc-geo-candidate` 위로 리베이스했다(2026-08-02). 이 PR을 다시
  `dev` 기준으로 열려면 #83·#290·#291이 먼저 `dev`에 merge돼야 한다(현재 스택: dev ← #290/#291
  ← #83 ← #54).
- **다중 인스턴스 대비 Redis Pub/Sub 재도입은 아직 이슈가 없다.** 실제 배포는 단일 인스턴스이지만
  스케일 아웃에 대비해 두기로 했다(2026-08-02, 사람 확인). 예전 설계(#78, 채널 `tracking:order:{id}`
  + 패턴 구독)가 참고 대상이나 #297로 제거된 상태라 처음부터 다시 설계해야 한다
- 라이더 콜 상세(`GET /api/rider/requests/{deliveryId}`, #57)가 물품 무게·수량을 제공하지 못함 —
  `delivery_order`에 관련 컬럼이 없음(`itemType` 열거형만 존재). 화면에서 실제로 필요해지면 스키마
  변경(Flyway 마이그레이션)과 주문 생성(REQ-ORD-002) 쪽 값 저장까지 함께 논의해야 함
  (2026-07-30: #56 rebase 중 dev 병합 과정에서 이 항목이 유실됐던 걸 복구함)
- 배차 확정(`POST /api/rider/requests/{deliveryId}/accept`, #56) 실패 사유(취소/이미 배차/라이더
  다른 배송 수행 중)를 `ApiResponse`에 에러코드 필드 없이 `message` 문자열로만 구분함(ADR-006).
  프론트가 사유별로 다른 UX를 보여줘야 하면 에러코드 체계 신설을 별도 이슈로 논의해야 함
