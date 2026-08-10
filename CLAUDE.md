---
title: Turkey 프로젝트 지침
status: active
updated_at: 2026-08-02
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
| `AVAILABLE` | `rider/requests`(콜 목록) | "운행 종료" → `UNAVAILABLE` / 콜 수락 → `BUSY` | 없음(#342) |
| `BUSY` | `rider/delivery`(진행 배송) | 배송 완료 → `AVAILABLE` | 고빈도(busy) |

- `rider/delivery`(진행 중 배송)는 id 없는 고정 경로다. 동시 진행 배송이 최대 1건이므로 동적 세그먼트가 불필요하고, 새로고침·재로그인 후에도 "진행 중 배송 조회 → 해당 화면 복귀"로 복구 가능해야 한다.
- 위치 전송은 라우트가 아니라 공용 훅 `shared/hooks/useLocationSender`로 구현하고, 라이더 상태를 인자로 받아 화면(홈 제외, `rider/requests`·`rider/delivery`) 생명주기에 부착한다. 위치 수집은 **포그라운드 실행 전제**이며(정책 §7), 비활성 탭에서의 백그라운드 전송은 보장하지 않는다.
- 고객의 실시간 위치 구독은 별도 훅 `shared/hooks/useTrackingStream`으로 SSE 연결·재연결·종료를 처리한다.
- API 연동은 Orval 자동 생성 훅(`src/api/generated/`, 수정 금지)을 기본으로 사용한다.
## 확정된 결정

### 계정·인증

- 하나의 계정은 `CUSTOMER` 또는 `RIDER` 중 하나의 역할만 가진다(동시 지원 안 함).
- 인증은 **쿠키 기반 서버 세션** 방식. 세션은 Redis에 저장하고, 쿠키에는 세션 식별자만 담는다.
  - Spring Security 없이 필터/인터셉터로 세션 확인·역할 검증·만료·로그아웃을 직접 구현한다.
  - **인증이 선언적으로 자동 적용되지 않는다(Spring Security의 SecurityFilterChain과 다른 점).**
    보호할 API마다 해당 인터셉터의 `addPathPatterns`에 경로를 직접 등록해야 인증이 걸린다.
    (고객 예시: `customer/config/CustomerWebMvcConfig`, #27) 새 고객/라이더 전용 API를 추가할 때
    이 등록을 빠뜨리면 그 API는 인증 없이 열린 채로 배포된다 — 리뷰 시 반드시 확인할 것.
  - **`/api/customer/deliveries` 아래는 정확 경로로 한 건씩 등록한다**(#37). 형제 경로 `/quote`가
    비로그인 API라 `/**`로 넓히면 조용히 401이 된다(`CustomerDeliveryCreateE2ETest`가 회귀 고정).
- 세션 TTL은 로그인 시점 **2시간 고정, 슬라이딩 갱신 없음**(#27). `SessionStore.findMemberId()`는
  조회만 하고 TTL을 건드리지 않는다.
- 고객·라이더 세션 인터셉터는 각 패키지에 중복돼 있다(`customer/auth/CustomerSessionInterceptor` #27,
  `rider/auth/RiderSessionInterceptor` #50). **세 번째 액터가 생기기 전까지 `common/auth`로 공용
  추출하지 않는다** — 지금 추출하면 아직 존재하지 않는 차이를 미리 상정해 설계해야 한다.

### 인프라·배포

- Redis는 현재 **세션 저장 / 휴대전화 인증번호(TTL) / 라이더 최신 위치(`RiderLocationRepository`,
  **BUSY 라이더만**, TTL 10분) / SSE 이벤트 팬아웃(Pub/Sub)** 에 쓴다. 영속 원본 저장소로는 쓰지 않는다.
  뒤의 둘은 #297(2026-08-02)로 제거했다가 **스케일 아웃 대비로 #317(2026-08-03)에서 되돌린
  것이다** — 단, 서버측 위치 필터는 되살리지 않았다(아래 SSE 항목).
  **GEO 저장소(`OrderGeoRepository`, 키 `order:geo`)는 호출자가 0이다** — #342(2026-08-04, 디스커션
  #338)에서 라이더-측 사용처를 전부 제거하고 #339에서 개명했다. 배차 위치 검색을 라이더가 아니라
  주문 픽업지 인덱싱으로 뒤집기로 확정(#101 미구현)했기 때문이며, **데드 코드처럼 보이지만
  의도된 상태다**(주문 GEO 이슈에서 재사용).
  Pub/Sub은 **SSE 이벤트 팬아웃 용도로만** 쓴다. 작업 큐·도메인 이벤트 버스·인스턴스 간 RPC로
  확장하지 않는다.
- Redis 배포 방식은 **EC2 인스턴스에 직접 설치**(2026-07-29 변경, 디스커션 #176). 관리 부담을 줄이는
  ElastiCache(관리형)를 먼저 검토했으나 **비용 문제**로 EC2 직접 설치로 결정을 뒤집었다.
- 영속성·트랜잭션 정합성이 필요한 데이터는 MySQL이 정본(사용자·배송요청·배차·상태·포인트 원장·정산·위치 이력).
- 여러 인스턴스로 수평 확장 가능한 모놀리식 Spring Boot WAS(코드 수준에서만 책임 분리, MSA 아님).
  **실제 배포는 단일 인스턴스다.**
- 프론트 빌드 산출물은 S3에 배포하고 CloudFront로 제공. **CloudFront 배포 하나에 `/api/*`·SSE
  behavior를 붙여 EC2를 origin으로 묶었다**(#26, 2026-07-29 AWS 반영 완료, SSE 경로 CachingDisabled).
  이 단일 오리진 전제 위에서 세션 쿠키는 `SameSite=Lax` + 프로파일별 `Secure`다
  (`common/auth/SessionCookie`) — API를 별도 오리진으로 분리하면 `SameSite=None`으로 재검토해야 한다.
- `rider_location_history`(V15)는 스키마에 남아 있지만 아무 코드도 쓰지 않는다. Flyway 마이그레이션은
  한 번 적용되면 되돌리지 않는다는 원칙에 따라 지우지 않는다.

### 실시간 위치·SSE

- 실시간 라이더 위치 전달은 **SSE** 사용(Polling 아님). **서버는 위치를 검증·필터링하지 않고
  받는 즉시 중계한다**(#297) — "변경됐을 때만"이 아니라 유효한 위치가 올 때마다 그 배송을
  구독 중인 고객에게 전송한다. 최신 위치 저장은 중계와 별개로 이루어진다(위 Redis 항목).
  - **위치 갱신 요청은 좌표만 담는다.** 배송 식별자는 `location/service/RiderLocationService`가
    세션의 라이더로 DB에서 풀어낸다(#317). 상태 조건이 붙은 그 조회가 안전장치다 — 배송이
    완료되면 결과가 비어 발행이 멈춘다. **라이더→배송 매핑을 캐시하지 않는다**(무효화를 놓치면
    다음 배송 경로가 이전 고객에게 흘러간다). AVAILABLE 라이더는 발행할 채널이 없어 조회조차 하지 않는다.
  - 전달 경로는 **Redis Pub/Sub 팬아웃**이다(#317). 라이더 위치 POST →
    `location/sse/TrackingPublisher`가 배송별 채널(`location/sse/TrackingChannel`,
    `tracking:order:{deliveryId}`)로 발행 → **모든 인스턴스**가 패턴 구독
    (`common/config/RedisMessageListenerConfig`) → `location/sse/TrackingSubscriber`가
    **자기 JVM의** `SseRegistry`를 조회해 `SseRelay`로 전송. `SseEmitter`는 그 JVM의 열린 응답에
    묶여 있어 다른 인스턴스가 대신 보낼 방법이 없기 때문이다.
  - **위치 갱신 경로에서 `SseRelay`를 직접 부르면 안 된다** — 다른 인스턴스에 연결된 고객이
    이벤트를 못 받는다. 반드시 `TrackingPublisher`를 거친다. 이걸 어겨도 단일 인스턴스
    테스트는 전부 통과하므로, `TrackingFanoutMultiInstanceE2ETest`(2인스턴스)가 유일한 방어선이다.
  - 구독자는 **페이로드를 파싱하지 않고 발행 지점의 JSON 문자열을 그대로 흘린다.** 계약이 한
    곳에만 존재하는 대신 **필드 추가만 허용하고 제거·의미 변경은 하지 않는다.** 채널 접두어도 같다.
  - SSE 프레임에는 **이벤트 이름을 붙이지 않는다** — 브라우저 기본 `message` 이벤트로 도착해야
    프론트 `useTrackingStream.onmessage`가 받는다. `measuredAt`은 반드시 문자열이어야 한다
    (숫자 타임스탬프가 되면 `parseLocationPing`이 프레임을 통째로 버려 지도가 조용히 멈춘다).
- SSE 외에 **위치 폴링 API도 있다**(`GET /api/customer/deliveries/{deliveryId}/location`,
  `location/service/CustomerLocationQueryService`, #311) — Redis 최신 위치를 읽는다. 프론트의 백업
  경로다. SSE `subscribeTracking`은 `"connected"` 코멘트만 보내고 **위치 스냅샷 init 이벤트가 없다.**
- 추적 스냅샷(`GET .../tracking`)과 스트림은 **같은 게이트**를 쓴다. 판정은 `OrderStatus.isTerminal()`
  이라 **WAITING도 통과하고 COMPLETED·CANCELED만 409**다(#401).
- **`DeliveryOrderRepository.findWithAssignedRiderById`는 반드시 `left join fetch`여야 한다**(#401/#421).
  WAITING은 `assigned_rider_id`가 NULL이라 inner join이면 주문이 있는데도 결과가 비어 500이 난다.
  같은 이유로 `DeliveryTrackingQueryService`는 라이더가 null이면 `DeliveryRouteEstimator`를 호출하지
  않는다. `CustomerDeliveryTrackingE2ETest`의 WAITING 케이스가 이 조합을 지킨다.
- **위치 전송 주기·임계값**(#81): AVAILABLE 30초 / BUSY 5초 / UNAVAILABLE 미전송, 최소 이동 20m,
  최대 속도 50 m/s, 정확도 상한 100m, 허용 과거 60초·미래 5초, 정지 시 강제 전송 120초,
  Redis 최신 위치 TTL 10분. **지금 이 값들은 클라이언트(안드로이드)만 쓴다** — 서버측 필터
  (`LocationAcceptancePolicy`)는 #297에서 제거됐고 되살리지 않았다. 다시 만들면 두 값이 같아야 한다.
- **위치 갱신 실패 응답의 경계**(#81): 좌표 범위 밖·필수 값 누락·정확도 음수·미래 시각은 400,
  **정확도 상한 초과와 60초 초과 과거 fix는 200 + `reason`** 으로 수용·폐기한다(실내 측위·탭 복귀에서
  정상 발생). 그래서 정확도 상한을 `@DecimalMax`로 달 수 없다 — Bean Validation 위반은 400이 된다.
- **SSE 연결 수 제한은 두지 않는다**(#317). 예전엔 배송당 3개(Redis ZSET)로 막았으나 제거했고,
  필요성이 재확인되기 전까지 만들지 않는다.
- **끊긴 연결 탐지에는 쓰기가 최소 두 번 필요하다**(#317 실측). 첫 쓰기는 소켓 버퍼에 들어가 성공하는
  경우가 많다 — **"위치를 한 번 보내면 정리된다"고 가정하는 테스트를 쓰지 말 것.**
- **"진행 중" 상태 집합이 두 곳에 있다**(#317): V10 마이그레이션의 CASE 식(`active_rider_id` 생성 컬럼)과
  `OrderStatus.trackableStatuses()`. **배송 상태를 추가하면 Flyway 마이그레이션도 함께 고쳐야 한다**
  (`DeliveryOrderActiveRiderIntegrationTest`가 동치를 고정하지만 규칙이 코드로 강제되지는 않는다).

### 주문·배차·상태

- 배차 동시성은 **조건부 UPDATE(Compare-And-Set)** 다([ADR-006](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90006-%EB%B0%B0%EC%B0%A8-%EB%8F%99%EC%8B%9C%EC%84%B1-%EC%B2%98%EB%A6%AC), 2026-07-28).
  주문→라이더 순서 고정, 실패 시 재조회로 사유 구분(#56).
- **배차 대기 시간 초과 자동 취소(#42)는 폴링 스캐너(1분 주기) + 지연 만료 하이브리드**다.
  단일 인스턴스 전제에서 조건부 UPDATE만으로 정합성이 보장돼 분산 락 없이 간다.
- **고객 취소(#47)는 시간 제약 없는 범용 "취소하기"** 다. 타임아웃 이후에만 뜨는 조건부 "환급받기"
  버튼은 만들지 않는다(취소=환급이라 후자가 전자의 부분집합).
- **배송 완료 인증(#61)은 주문당 1건**(`uk_delivery_proof_order`), 종류는
  `PHOTO`/`RECIPIENT_CONFIRMATION`/`AUTH_CODE` 3종. 인증 등록과 완료 전이를 나누지 않고
  `RiderDeliveryService.complete()` 한 트랜잭션에 통합한다 — 완료 요청에는 인증정보가 항상 함께 온다.
- **배송요청 생성은 `estimatedFare`를 받아 서버 재계산값과 대조하고, 다르면 409로 거부한다**(#37).
  대조 전용이며 **결제 금액으로 쓰이지 않는다**(차감은 항상 서버 계산값). 프론트는 `/quote` 응답의
  `fare.totalFare`를 그대로 실어 보낸다.
- 예상 요금과 최종 요금의 차이는 허용한다.
- 라이더 콜 목록(#55/#367/#60)은 좌표를 선택 파라미터(`latitude`/`longitude`)로 받는다. 주면
  bounding box 인덱스(`idx_delivery_waiting_location`)를 타고, 안 주면 반경 필터를 건너뛰고 전체를
  반환한다. 운임·배송거리 필터와 페이지 자르기·커서 비교는 **자바 메모리에서** 수행한다(SQL 커서
  아님) — bounding box 후보가 수백 건 수준이라는 실측 전제. 응답은 `{items, hasNext}`(총 건수 없음).
- 라이더 운행 기록 상세(`GET /api/rider/history/{deliveryId}`, #71)는 목록과 달리 **확정 운임+정산액을
  함께 담는다** — "배송 기록=금액 없음" 원칙의 의도된 예외(정산 확인 화면). 소유+COMPLETED를 쿼리
  조건에 넣어 없음·타인 것·미완료를 같은 404로 응답한다.

### 결제·포인트

- **order ↔ payment 서비스 의존 방향은 order → payment 로 고정한다**(#37/#40).
  `DeliveryService`가 `CustomerPaymentService`를 주입받고, **payment 서비스는 order 서비스를
  주입받지 않는다.** 반대로 하면 스프링 빈 순환이 되는데, Boot 3.4는 순환 참조가 기본 금지라
  **컴파일이 아니라 기동이 실패한다.** payment가 order를 참조하는 것은 엔터티까지다.
- **주문 생성과 포인트 차감은 하나의 트랜잭션이다**(#37/#40). `CustomerPaymentService.payForOrder`는
  `Propagation.MANDATORY` — 기본값이면 이 메서드만 따로 불렀을 때 조용히 새 트랜잭션이 열려
  **주문 없이 포인트만 빠져나가는** 경로가 생긴다.
- **포인트 부족은 402 PAYMENT_REQUIRED 다**(#40). 프론트가 `message` 파싱 없이 충전 화면으로 분기할
  수 있게 하려는 것이다.
- **포인트 관련 트랜잭션의 잠금 순서는 `point_charge` → `point_wallet` 로 고정한다**(#33).
  배송 결제·정산이 붙을 때도 같은 순서를 지켜야 한다 — 엇갈리면 데드락이다.
- **`point_transaction`의 요청키 유니크는 `(request_key, transaction_type)`** 이다(V18, #40).
  ORDER_USE와 ORDER_REFUND가 같은 주문 요청키를 공유하고, 그 공유 자체가 추적 근거가 된다.
- **`point_charge.failure_reason`은 FAILED·CANCELED 두 의미를 겸한다**(#34). 값을 해석할 때 반드시
  `status`를 함께 봐야 한다.
- 결제는 MVP에서 포인트 기반 또는 모킹 흐름 우선(실 PG 연동 아님).
- 라이더 출금 최소 금액은 5,000P(`RiderPaymentService.MIN_WITHDRAWAL_AMOUNT`, #68, 잠정값).
  계좌 미등록·잔액 부족은 둘 다 409다.

### 경로 탐색·ETA

- **경로 탐색은 자체 호스팅 OSRM**(`common/routing/OsrmRoutingClient`, #416/#420). 한때 카카오모빌리티로
  바꿨다가(#431) 다시 되돌렸다(2026-08-10) — 카카오모빌리티 길찾기 API를 실제로 쓸 수 없는 상황이
  됐기 때문이다. 계약은 `Optional<Duration>`(경로 좌표를 요청하지 않음, `overview=false`).
  타임아웃 연결 300ms/읽기 700ms, 재시도 없음, 실패는 예외 대신 빈 값, 4xx는 "경로 없음"이라 실패로
  안 셈, 연속 3회 실패 시 호출 스킵(1초 → 2배씩 → 30초 상한, `RoutingFailureBackoff`).
  평일 출퇴근(Asia/Seoul 07-09시·18-20시)에는 raw duration에 **×1.3** 을 곱한다(잠정값).
- **추적 스냅샷이 ETA를 싣는다**(#421). 출발점은 Redis 최신 위치, 도착점은 픽업 전
  (`ASSIGNED`·`MOVING_TO_PICKUP`)이면 픽업지, 픽업 후(`PICKED_UP`·`DELIVERING`)면 도착지다.
  **라이더 위치가 없으면 픽업지로 대체하지 않고 null로 둔다** — 출발=도착이 되어 "지금 도착"이라는
  틀린 값이 나온다. WAITING도 라이더가 없어 ETA는 null이다.
- **예상 경로 좌표는 싣지 않는다**(#421, 한 번 구현했다가 걷어냄). 다시 실을 때는
  `{latitude, longitude}` **이름 붙은 객체 배열**로 한다 — GeoJSON `[경도, 위도]`를 그대로 흘리면
  카카오맵과 반대인데 서울 좌표는 뒤집어도 유효 범위라 조용히 엉뚱한 곳에 그려진다.
- `DeliveryTrackingQueryService.getTracking`에는 **`@Transactional`이 없다**(외부 HTTP 호출이 DB
  커넥션을 잡지 않게). 대신 지연 로딩을 쓸 수 없다 — **이 경로에 조회를 추가하며 연관을 만지면
  `LazyInitializationException`이다.**

### API 규약

- **오류 응답에 Content-Type을 명시한다**(`GlobalExceptionHandler`, #77). 지정하지 않으면 스프링이
  `Accept`로 컨텐트 협상을 하고, 브라우저 `EventSource`는 `Accept: text/event-stream`만 보내므로
  401·409·429가 전부 **406** 으로 바뀌어 상태코드와 `ApiResponse` 본문을 둘 다 잃는다.
- **새 컨트롤러에 `@Operation(operationId = "...")`을 명시한다**(#194). 생략하면 springdoc이 메서드명을
  쓰고 동명 메서드에 `_1`을 붙여 프론트 훅이 액터를 구분하지 못한다(`useLogin`/`useLogin1`).
  `OpenApiOperationIdE2ETest`가 잡는다.
- `GlobalExceptionHandler`는 `IllegalStateException`을 일괄 400으로 바꾼다. **서버 정합성 오류는
  `BusinessException`으로 명시**해야 사용자 입력 오류와 구분된다(#67).

### 테스트

- 통합·E2E는 **로컬 Docker Redis(`redis:7.4`)** 에 붙는다(단위 테스트만 인메모리 대체). 테스트는
  로직 DB `database: 1`을 쓰고 `RedisCleaner`가 매 테스트 전 `FLUSHDB` 한다.
  - **개발 PC에 Redis를 직접 설치하지 않는다.** 호스트 Redis(`127.0.0.1:6379`)가 컨테이너(`*:6379`)를
    이겨서 **조용히** 호스트 인스턴스에 연결된다. `RedisCleaner`가 `redis_version`을 확인해 테스트
    실패로 만든다.
- **Windows에서 전체 테스트를 한 번에 돌리면 E2E 컨텍스트가 무작위로 실패한다**(Tomcat 바인드 실패,
  `java.net.SocketException: Bad address: listen`). 단독 실행하면 통과한다 — Windows 예약 포트 범위
  (`netsh interface ipv4 show excludedportrange protocol=tcp`)가 흔한 원인이다.
  **테스트 실패를 보면 먼저 이 패턴인지 확인할 것.**

### 기타

- 라이더 상태와 배송 상태를 분리한다.
- 용어: "퀵 신청" 대신 **"배송요청"**을 사용한다.
- 핵심 테이블(ERD 확정): `member`, `rider_profile`, `rider_payout_account`, `term`, `member_term_agreement`, `delivery_order`, `order_status_history`, `fare_policy`, `item_type_surcharge`, `order_fare_snapshot`, `delivery_proof`, `rider_location_history`, `point_wallet`, `point_charge`, `point_transaction`, `rider_settlement`, `rider_withdrawal`. 고객/라이더는 별도 테이블 없이 `member.role`로 구분하며, 배차는 별도 테이블 없이 `delivery_order.assigned_rider_id` FK로 처리한다. `member_notification`은 2·3차 MVP 이후 구현 예정이라 아직 없다. 세부 컬럼·제약은 `docs/03-erd.md`와 최종 DDL을 따른다.

## 협업

- 흐름: Issue → Branch → 구현·테스트 → PR → Code Review → Merge (Squash Merge 우선)
- 브랜치명·PR에 Issue 번호와 작업 내용을 포함한다. PR에는 변경 내용·선택 이유·테스트 결과·집중 리뷰 영역을 적는다.
- 배차 동시성·상태 전이·정산·포인트 등 핵심 로직은 최소 2명 교차 리뷰한다.
- 기술적 의사결정은 Wiki의 ADR 문서(ADR-001~010)에 `문제 → 대안 → 결정 → 이유 → 장단점 → 영향 → 검증` 형식으로 기록한다.
- **"사람 확인" 태그는 실제 확인 없이 붙이지 않는다**(#431에서 근거 없는 "사람 확인" 기록이 실제로 나왔다).

## 확인이 필요한 항목

**여기에는 답이 정해지지 않았고 다음 작업에 실제로 영향을 주는 것만 남긴다.** 결정이 나면
「확정된 결정」으로 옮기고 여기서 지운다. 이미 결론이 난 논의를 흔적으로 남기지 않는다.

### 지금 구현을 막고 있는 것

- **`RiderPointApi`의 `getSettlements`·`getWithdrawals`가 `return null` 스텁이다**(#69에서
  `getPointTransactions`만 구현). 어느 화면·이슈가 이 둘을 담당하는지 정해지지 않았다.
- **필수 약관 목록 조회 API(#72)가 없어 회원가입(#25)의 `agreedTermIds`를 채울 방법이 없다.**
  #72 구현 시 응답 스키마(`termId`)를 맞춰야 한다.
- **역할 무관 세션 확인 API(`GET /api/session`)가 없다.** 두 역할 API 병렬 조회는 반대 역할의 401이
  공용 `SESSION_ID` 쿠키를 만료시키는 버그를 냈고(#288), 프론트가 로그인 시 저장한 역할 힌트로
  한쪽만 조회하도록 임시 완화한 상태다. 역할 무관 API가 생기면 힌트를 제거하고 서버 응답의 역할을
  정본으로 써야 한다.
- **`#22`(아이디 찾기)가 `VerificationCodeStore.consumeVerifiedToken`을 아직 쓰지 않는다** —
  구현 시 연결 필요.
- **라이더 콜 상세(#57)가 물품 무게·수량을 못 준다** — `delivery_order`에 컬럼이 없다(`itemType`만).
  화면에서 필요해지면 Flyway 마이그레이션 + 주문 생성 저장까지 함께 논의.
- **배송요청 생성 화면의 기사님 전달사항(#205)을 저장할 컬럼·계약이 없다.** 필요하면 최대 길이·
  라이더 노출 시점과 함께 별도 이슈로.

### 결정이 필요한 계약·정책

- **에러코드 체계가 없다.** 배차 실패 사유(취소/이미 배차/타 배송 수행 중, #56)와 배송요청 생성의
  409 세 가지(진행 중 1건 위반·동시 재전송·요금 변경, #40)를 프론트가 `message` 문자열로만
  구분한다. 사유별 UX가 필요해지면 에러코드 필드 신설을 논의해야 한다.
- **Orval 생성 훅의 `error`가 전부 `AxiosError<unknown>`** 이다(#194). springdoc에 공통 에러 스키마를
  노출하고 `override.errorType`을 줄지, 화면에서 좁혀 쓸지 미결.
- **화면 결제수단(카카오페이·휴대폰)과 `PaymentMethod`(`CARD`·`BANK_TRANSFER`)이 불일치**(#32).
  `ck_point_charge_method`도 두 값만 허용 — enum을 넓힐지 화면을 줄일지.
- 정산 생성 시점과 실패 처리 방식.
- 동일 요청 재전송에 대한 API 멱등성 정책(요청 식별값 기준).
- 로그인·회원가입 화면(`customer/login`·`rider/login`·`*/signup`)에 비인증 가드를 걸지(#195).
  현재는 로그인 상태로도 로그인 화면이 열린다.

### 알려진 결함 (고칠지 감내할지 미정)

- **`GlobalExceptionHandler`에 `HttpMessageNotReadableException` 핸들러가 없다**(#81). 본문 JSON 파싱
  실패 400이 `ApiResponse`가 아닌 스프링 기본 오류 본문으로 나갈 것으로 보인다 — **전체 엔드포인트에
  해당**한다. 실제 본문을 확인하고 이슈로 올릴지 판단 필요.
- **인증번호 확인(`PhoneVerificationService.confirm`)에 원자적 보호가 없다**(#21). 같은 유효 코드로
  동시 확인 요청이 오면 인증 완료 토큰이 중복 발급될 수 있다.
- **heartbeat가 없다.** 프록시 유휴 타임아웃에 조용한 스트림이 끊길 수 있고, #401로 WAITING 구간
  (길면 수 분)이 완전히 무음이라 emitter 타임아웃(5분)에 더 쉽게 걸린다. emitter 타임아웃 5분이
  실제 정책값인지도 확정된 적이 없다.
- **SSE 연결 중 세션이 만료돼도 스트림이 끊기지 않는다.** 인터셉터는 연결 시점에 한 번만 돈다.
- **주문 완료·취소 시 능동적 SSE 종료가 없다.** 중계는 조용해지지만 연결은 타임아웃까지 열려 있고
  완료 알림이 프론트로 가지 않는다.
- **오래 PENDING인 충전 요청을 정리할 방법이 없다**(#34). 결제창을 열어 둔 채 브라우저를 닫으면
  그 건은 영구히 PENDING이다.
- **자동 취소 스캐너에 최대 1분의 창이 남는다**(#42). 고객이 아무 행동도 안 하면 실제 취소·환급까지
  스캔 주기만큼 지연된다. 감내 가능한 백스톱으로 명시적으로 확정하지는 않았다.
- **고객이 자기 주문의 "만료됨" 상태를 능동적으로 조회할 방법이 없다**(#44/#46 미구현). 클라이언트
  사이드 카운트다운(`requestedAt + 5분`)도 미구현 — #46/#47 프론트 연동 시 반영해야 한다.
- **CI가 테스트를 돌리지 않는다**(`deploy.yml`이 `-x test`). 켜려면 MySQL·Redis 서비스 컨테이너가
  필요하다. **이 공백이 실제로 물렸다**(#70): 컴파일 안 되는 테스트가 dev에 병합됐다.
- CSRF 대응 정책(`SameSite=Lax`가 어느 정도 방어가 되지만 별도 토큰 방식 여부 미결).

### 실 PG 연동 시점에 판단 (MVP 범위 밖)

- **PG 벤더 선정 기준 2가지**(#33): ① 승인 API가 인증 토큰 기준으로 **멱등**한가(아니면 PG 호출을
  트랜잭션 밖에 두는 현재 파사드 구조가 성립하지 않는다) ② 결제 **조회** API가 있는가(타임아웃 건의
  성사 여부를 판정할 유일한 수단). 토스페이먼츠는 둘 다 있음.
- 실 PG에 필요하나 지금 없는 것: 망 취소, 거래·정산 대사, 웹훅 수신, `PaymentGateway`의 결제 조회.
- **취소가 승인을 이기면 "돈은 나갔는데 CANCELED"가 될 수 있다**(#34). 경쟁 창이 PG 호출 **앞에**
  있어서 `APPROVING` 상태 추가만으로는 안 풀린다 — PG 호출 전에 상태를 선점해야 한다
  (검토 내용은 워크로그 `2026-07-31-34-point-charge-cancel.md` 부록). 모의 PG로는 검증 불가.
- 충전 금액 정책이 잠정값(#32): 1,000 / 1,000,000 / 1,000원 단위. 화면 프리셋 화이트리스트로 좁힐지.

### 인프라 (배포 구성 확정 시)

- EC2 사이징, MySQL 배치 방식(EC2 직접 설치 vs RDS). Redis 단일 인스턴스가 SPOF인지도 별도 확인.
- 배포된 OSRM 서버(#416) 사이징이 지금 트래픽에 맞는지.
- GitHub Actions의 AWS 인증 방식(OIDC + 최소 권한 IAM Role 권장)과 배포 권한 범위.
- 라이더 콜 목록의 `radiusMeters` 상한이 없다(#55). 좌표를 안 보내는 요청은 여전히 WAITING 전체를
  훑으므로, WAITING이 크게 늘어나면(다도시 동시 운영) 계약을 다시 열어야 한다.
- 러시아워 배수(×1.3)와 시간대(07-09, 18-20시)가 실측 없이 정한 잠정값이다. 실제 배송 데이터로 재조정.
- 외부 SMS 발송 연동(현재 로그만 남기는 모킹) — 벤더 선정 시 `SmsSender` 구현체 교체(#20).
