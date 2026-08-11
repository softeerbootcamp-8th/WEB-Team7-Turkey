---
title: Turkey 프로젝트 지침
status: active
updated_at: 2026-08-10
owner: WEB-Team7-Turkey
source_of_truth: true
---

# CLAUDE.md

Claude Code가 Turkey(퀵배송 매칭 서비스) 저장소를 수정할 때 지키는 규칙 문서다.
배경·설계 근거·의사결정 상세는 GitHub Wiki(ADR)와 `docs/`가 정본이고, 이 문서는 **작업 시 즉시 참조할 규칙과 확정 사실**만 담는다.

## 프로젝트 문서

- [프로젝트 컨텍스트](docs/00-project-context.md) — 목적, 아키텍처, 기술 스택 개요 (정본)
- [기능 명세](docs/01-functional-spec.pdf) — 기능별 사전조건·처리흐름·성공조건·예외처리·우선순위
- [도메인 정책](docs/02-domain-policy.md) — 상태 전이, 배차·취소·포인트·정산·SSE 정책
- [ERD](docs/03-erd.md) — 핵심 엔터티, 관계, 애플리케이션-DB 제약 역할 분담
- [로깅 공통 규칙](docs/logging-guidelines.md) — Filter+MDC / Service / 선택적 AOP 규칙
- [로컬 개발 환경(DB)](docs/05-local-dev.md) — Docker MySQL 기동·초기화·트러블슈팅

문서 충돌 시 판단 순서: ADR → ERD/DDL → 도메인 정책 → 기능 명세 → 프로젝트 컨텍스트.

## 이슈 기반 기능 개발 절차

이슈 번호가 있는 기능 개발은 `mvp-feature` 스킬(`.claude/skills/mvp-feature/`)이 정본 절차다:
이슈 읽기 → 범위 판정 → 계약 확정(사람 확인) → 구현 → 단위·통합·E2E 테스트 → 작업 기록.
프롬프트에 이슈 번호가 나오면 `UserPromptSubmit` 훅(`.claude/hooks/issue-mvp-trigger.py`)이 스킬 로드를 안내한다.
작업 기록은 `docs/worklog/`에 이슈당 한 파일로 남기고, 사람이 고른 선택과 근거를 적는다.

## 부하테스트 절차

k6 부하테스트는 `loadtest` 스킬(`.claude/skills/loadtest/`)이 정본 절차다. 목적만 정하면
대상 선정 → 시나리오 → 계측 스택 기동 → 시드 → 실행 → 지표 수집 → 리포트까지 진행한다.
엔드포인트별 사전조건(그 요청이 실제로 일을 하게 만드는 상태 조건)은
`.claude/skills/loadtest/references/preconditions.md` 가 정본이다 — OpenAPI 스펙에 없는 정보이고,
빠뜨리면 **200 만 받고 아무 일도 안 한 결과를 성능 수치로 오해**한다(AVAILABLE 라이더로 위치
갱신을 때리는 경우가 실례다).

- **대상에 따라 디렉터리가 갈린다**(2026-08-11 결정): `backend/loadtest/local/`(로컬 docker 앱) 과
  `backend/loadtest/remote/`(배포 서버). 같은 명령처럼 보이면 실수로 팀 공용 환경을 때리기 때문이다.
  `collect.py` 와 arm 스크립트는 최상위 공용이고, 대상은 `BASE_URL` 로만 갈린다 — arm 을 대상별로
  복사하면 한쪽만 고쳐져 로컬·배포 수치를 직접 비교할 수 없게 된다.
  - `remote/` 는 가드가 따로 있다(`remote/README.md`): 사전 공지, 끝난 뒤 정리 의무, 부하 생성기
    위치를 리포트에 기록. **로컬 절차를 그대로 옮겨 쓰지 않는다.**
  - k6 결과는 어느 대상이든 **로컬 Prometheus** 로 보낸다. 배포 Prometheus 로 밀면 보존 상한(8GB)에
    닿을 때 오래된 블록부터 지워져 **운영 지표가 밀려 사라진다** — k6 와 운영 지표를 구분하지 않는다.
- 측정 경로(k6 → app → mysql/redis)는 한 docker 네트워크 안에서 끝낸다. 관측(스크레이프·결과
  전송)은 호스트를 경유해도 무해하다.
- 런당 원본 수치는 `docs/loadtest/`에 한 파일, 여러 런을 비교한 결론은
  `backend/loadtest/README.md`에 누적한다.
- k6 종료 시 `PostToolUse` 훅(`.claude/hooks/k6-report.py`)이 `backend/loadtest/collect.py` 를
  돌려 지표 표를 올려 준다. **사용자가 자기 터미널에서 돌린 k6 에는 붙지 않는다**(훅은 세션의
  tool 호출에만 발동) — 그때는 `collect.py` 를 직접 부른다.

## 작업 원칙

- 기존 디렉터리 구조와 팀 합의를 우선한다. 요청 없이 대규모 구조 변경·기술 교체를 하지 않는다.
- 새 라이브러리·AWS 서비스·인프라 추가 전, 기존 기술로 해결 가능한지 먼저 검토한다.
- 「확정된 결정」은 임의로 바꾸지 않는다. 변경이 필요하면 코드보다 먼저 영향 범위와 대안을 설명한다.
- 인증·배차·포인트·상태 전이·정산 등 정합성 코드는 트랜잭션 경계와 실패 시 동작을 명확히 한다.
- 정상 흐름 외에 중복 요청·동시 요청·연결 종료·재시도·부분 실패를 고려한다.
- 불확실한 정책은 추측해 고정하지 말고 「확인이 필요한 항목」에 남긴다.

## 금지 사항

- **Spring Security, Spring Batch, Spring AI 사용 금지.**
- **Docker 사용 제약 없음**(2026-07-29). 로컬 개발 DB 외에 애플리케이션 컨테이너화·배포 파이프라인·테스트 인프라(Testcontainers 등)에 자유롭게 쓴다.

## 저장소 구조

- 백엔드(Spring Boot)와 프론트엔드(`frontend/`)를 하나의 GitHub 모노레포로 관리한다.
- 프론트 라우트는 액터(고객/라이더) 기준으로 구성하고, 폴더명은 ERD 엔터티에 맞춰 `customer/`·`rider/`를 쓴다(ADR-0002).
- 기능별 패키지: `com.turkey.quick.{customer, rider, order, matching, location, payment, common}`. `common` 하위: `config`, `exception`, `response`.
- DB 스키마는 Flyway(`src/main/resources/db/migration`)로 관리하며 앱 기동 시 자동 실행된다.
- CI/CD는 경로 필터로 프론트/백엔드 배포를 분리한다(`.github/workflows`).

## 기술 스택

- Backend: Java 21, Spring Boot 4.1.0, Gradle, Lombok, JPA, JUnit + AssertJ, SSE, Flyway
- Data: MySQL 8.4, Redis
- Infra: AWS EC2(백엔드), S3(프론트 빌드 산출물), CloudFront(CDN), GitHub Actions
- Frontend: React, TanStack Router(파일 기반 라우팅, `routeTree.gen.ts` 자동 생성), TanStack Query, Orval(OpenAPI 기반 API 클라이언트 자동 생성), axios, shadcn/ui

## 도메인 상태값

상태 변경은 요청 값으로 덮어쓰지 않고 **현재 상태 + 수행 행위**로 검증한다. 허용되지 않은 전이는 서버에서 거부한다.

- **라이더 상태**: `UNAVAILABLE`(운행 종료/로그아웃) · `AVAILABLE`(배차 가능) · `BUSY`(배송 수행 중)
- **배송 상태**: `WAITING` → `ASSIGNED` → `MOVING_TO_PICKUP` → `PICKED_UP` → `DELIVERING` → `COMPLETED`, 그리고 `CANCELED`

원자적 전이(하나의 트랜잭션):

- 배차 확정: 배송 `WAITING→ASSIGNED` + 라이더 `AVAILABLE→BUSY` + 배차 관계 생성
- 배송 완료: 배송 `DELIVERING→COMPLETED` + 라이더 `BUSY→AVAILABLE` + 정산 내역 생성
- 고객 일반 취소(배차 전에만): 배송 `WAITING→CANCELED`

**배차 동시성 보장**: 배송요청당 최대 1명 배정 / 라이더당 동시 진행 배송 최대 1건 / 경쟁 실패 수락은 명확한 실패 결과(부분 성공 없음). 구현 방식은 ADR에서 결정.

**진행 중 배송요청 제한**: 고객은 진행 중 배송요청(`WAITING`~`DELIVERING`)을 동시에 최대 1건만 가진다(`COMPLETED`/`CANCELED`만 있는 고객만 새로 생성 가능). 배차 이후(`ASSIGNED` 이상) 취소와 라이더 배차 포기는 MVP 범위 밖.

## 프론트엔드 아키텍처

라우트는 액터(고객/라이더) 기준으로 구성한다(ADR-0002).

- 동적 세그먼트는 `$deliveryId`로 통일(엔터티 `delivery_order`와 매칭).
- 인증 라우트는 가드 요구로 분리: `auth/`(로그인 전, 비인증 가드) vs `account/`(로그인 후, 인증 가드 — 회원정보·계정 관리·알림함은 고객/라이더 공용, `account/notifications`).
- 인가는 프론트 라우트 가드가 1차, 서버가 2차(Spring Security 미사용 원칙 유지).
- **보호가 필요한 화면은 `customer/_authed/`·`rider/_authed/` 하위에 둔다**(#195). `_authed`는 경로 없는 레이아웃이라 URL은 안 바뀐다. 그 밖에 만들면 가드 없이 열린다 — 백엔드 인터셉터 `addPathPatterns` 등록 누락과 같은 종류의 실수다. `account/`·`auth/`는 각 디렉터리의 `route.tsx`가 가드를 담당하고, 판정 로직은 `shared/auth/guard.ts`.
- **401 공통 처리는 `axiosInstance` 인터셉터 한 곳**(#195). 화면 컴포넌트는 401을 개별 처리하지 않는다. 단 세션 확인·로그인 경로는 제외 — 전자의 401은 가드의 정상 판정 신호, 후자는 자격 증명 오류라 폼에 표시해야 한다.
- `시스템` 대분류(요금·상태 전이·포인트 원장·배차)는 별도 화면 없이 각 화면이 API로 소비한다.

**라이더 상태 ↔ 화면 매핑** — 상태 전이는 별도 화면이 아니라 각 화면의 버튼으로 일어난다:

| 상태 | 화면 | 전이 트리거 | 위치 전송 |
|---|---|---|---|
| `UNAVAILABLE` | `rider/index`(홈) | "콜 받기" → `AVAILABLE` | 없음 |
| `AVAILABLE` | `rider/requests`(콜 목록) | "운행 종료" → `UNAVAILABLE` / 콜 수락 → `BUSY` | 없음(#342) |
| `BUSY` | `rider/delivery`(진행 배송) | 배송 완료 → `AVAILABLE` | 고빈도(busy) |

- `rider/delivery`는 id 없는 고정 경로다(동시 진행 배송 최대 1건). 새로고침·재로그인 후 "진행 중 배송 조회 → 해당 화면 복귀"로 복구 가능해야 한다.
- 위치 전송은 공용 훅 `shared/hooks/useLocationSender`로 구현하고, 라이더 상태를 인자로 받아 화면(홈 제외) 생명주기에 부착한다. 위치 수집은 **포그라운드 실행 전제**(정책 §7)이며 비활성 탭 백그라운드 전송은 보장하지 않는다.
- 고객 실시간 위치 구독은 훅 `shared/hooks/useTrackingStream`이 SSE 연결·재연결·종료를 처리한다.
- API 연동은 Orval 자동 생성 훅(`src/api/generated/`, **수정 금지**)을 기본으로 쓴다.

## 확정된 결정

### 계정·인증

- 하나의 계정은 `CUSTOMER` 또는 `RIDER` 중 하나의 역할만 가진다(동시 지원 안 함).
- 인증은 **쿠키 기반 서버 세션**. 세션은 Redis에 저장하고 쿠키에는 세션 식별자만 담는다. Spring Security 없이 필터/인터셉터로 세션 확인·역할 검증·만료·로그아웃을 직접 구현한다.
- **인증은 선언적으로 자동 적용되지 않는다.** 보호할 API마다 인터셉터의 `addPathPatterns`에 경로를 직접 등록해야 걸린다(고객 예: `customer/config/CustomerWebMvcConfig`, #27). 새 전용 API 추가 시 이 등록을 빠뜨리면 인증 없이 열린 채 배포된다 — **리뷰 시 반드시 확인**.
- **`/api/customer/deliveries` 아래는 정확 경로로 한 건씩 등록**(#37). 형제 경로 `/quote`가 비로그인 API라 `/**`로 넓히면 조용히 401이 된다(`CustomerDeliveryCreateE2ETest`가 회귀 고정).
- 세션 TTL은 로그인 시점 **2시간 고정, 슬라이딩 갱신 없음**(#27). `SessionStore.findMemberId()`는 조회만 하고 TTL을 안 건드린다.
- 고객·라이더 세션 인터셉터는 각 패키지에 중복돼 있다(`CustomerSessionInterceptor` #27, `RiderSessionInterceptor` #50). **세 번째 액터가 생기기 전까지 `common/auth`로 공용 추출하지 않는다**(아직 없는 차이를 미리 상정하게 됨).

### 인프라·배포

- Redis 용도: **세션 저장 / 휴대전화 인증번호(TTL) / 라이더 최신 위치(`RiderLocationRepository`, BUSY 라이더만, TTL 10분) / SSE 이벤트 팬아웃(Pub/Sub)**. 영속 원본 저장소로는 쓰지 않는다. Pub/Sub은 **SSE 팬아웃 용도로만** 쓰고 작업 큐·이벤트 버스·인스턴스 간 RPC로 확장하지 않는다.
- **GEO 저장소(`OrderGeoRepository`, 키 `order:geo`)는 호출자가 0**이다(#342/#339). 배차 위치 검색을 라이더가 아니라 주문 픽업지 인덱싱으로 뒤집기로 확정(#101 미구현)했기 때문 — **데드 코드처럼 보이지만 의도된 상태**(주문 GEO 이슈에서 재사용).
- Redis 배포는 **EC2에 직접 설치**(2026-07-29, 디스커션 #176). ElastiCache는 비용 문제로 제외.
- MySQL도 **EC2에 직접 설치**(RDS 아님, 사람 확인). 인스턴스 사이징: WAS `t4g.micro`, DB `t3.micro`(사람 확인, 2026-08-11).
- 영속성·트랜잭션 정합성이 필요한 데이터는 MySQL이 정본(사용자·배송요청·배차·상태·포인트 원장·정산·위치 이력).
- 수평 확장 가능한 모놀리식 Spring Boot WAS(코드 수준 책임 분리, MSA 아님). **실제 배포는 단일 인스턴스.**
- 프론트 산출물은 S3 배포 + CloudFront 제공. **CloudFront 배포 하나에 `/api/*`·SSE behavior를 붙여 EC2를 origin으로 묶었다**(#26, SSE 경로 CachingDisabled). 이 단일 오리진 전제 위에서 세션 쿠키는 `SameSite=Lax` + 프로파일별 `Secure`(`common/auth/SessionCookie`) — API를 별도 오리진으로 분리하면 `SameSite=None`으로 재검토해야 한다.
- `rider_location_history`(V15)는 스키마에 있으나 아무 코드도 안 쓴다. Flyway는 적용 후 되돌리지 않는 원칙이라 지우지 않는다.

### 실시간 위치·SSE

- 실시간 라이더 위치는 **SSE**(Polling 아님). **서버는 위치를 검증·필터링하지 않고 받는 즉시 중계**한다(#297) — 유효한 위치가 올 때마다 그 배송을 구독 중인 고객에게 전송. 최신 위치 저장은 중계와 별개(위 Redis 항목).
- **위치 갱신 요청은 좌표만 담는다.** 배송 식별자는 `RiderLocationService`가 세션의 라이더로 DB에서 푼다(#317). 상태 조건이 붙은 그 조회가 안전장치 — 완료되면 결과가 비어 발행이 멈춘다. **라이더→배송 매핑을 캐시하지 않는다**(무효화를 놓치면 다음 배송 경로가 이전 고객에게 흘러감). AVAILABLE 라이더는 발행 채널이 없어 조회조차 안 한다.
- 전달 경로는 **Redis Pub/Sub 팬아웃**(#317): 위치 POST → `TrackingPublisher`가 배송별 채널(`tracking:order:{deliveryId}`)로 발행 → **모든 인스턴스**가 패턴 구독(`RedisMessageListenerConfig`) → `TrackingSubscriber`가 **자기 JVM의** `SseRegistry`를 조회해 `SseRelay`로 전송. `SseEmitter`는 그 JVM의 열린 응답에 묶여 있어 다른 인스턴스가 대신 못 보낸다.
- **위치 갱신 경로에서 `SseRelay`를 직접 부르면 안 된다** — 다른 인스턴스에 연결된 고객이 못 받는다. 반드시 `TrackingPublisher`를 거친다. 단일 인스턴스 테스트는 어겨도 통과하므로 `TrackingFanoutMultiInstanceE2ETest`(2인스턴스)가 유일한 방어선이다.
- 구독자는 **페이로드를 파싱하지 않고 발행 지점의 JSON 문자열을 그대로 흘린다.** 계약이 한 곳에만 존재하는 대신 **필드 추가만 허용, 제거·의미 변경 금지**(채널 접두어도 동일).
- SSE 프레임에 **이벤트 이름을 붙이지 않는다** — 브라우저 기본 `message` 이벤트로 도착해야 `useTrackingStream.onmessage`가 받는다. **`measuredAt`은 반드시 문자열**(숫자가 되면 `parseLocationPing`이 프레임을 통째로 버려 지도가 조용히 멈춘다).
- SSE 외에 **위치 폴링 API**도 있다(`GET /api/customer/deliveries/{deliveryId}/location`, `CustomerLocationQueryService`, #311) — Redis 최신 위치를 읽는 프론트 백업 경로. SSE `subscribeTracking`은 `"connected"` 코멘트만 보내고 **위치 스냅샷 init 이벤트가 없다.**
- 추적 스냅샷(`GET .../tracking`)과 스트림은 **같은 게이트**(`OrderStatus.isTerminal()`)를 쓴다 — **WAITING도 통과, COMPLETED·CANCELED만 409**(#401).
- **`DeliveryOrderRepository.findWithAssignedRiderById`는 반드시 `left join fetch`**(#401/#421). WAITING은 `assigned_rider_id`가 NULL이라 inner join이면 결과가 비어 500이 난다. 같은 이유로 `DeliveryTrackingQueryService`는 라이더가 null이면 `DeliveryRouteEstimator`를 호출하지 않는다(`CustomerDeliveryTrackingE2ETest`의 WAITING 케이스가 고정).
- **위치 전송 주기·임계값**(#81, BUSY 간격은 #391로 갱신): AVAILABLE 30초 / BUSY는 고정 주기가 아니라 최소 0.5초 간격으로 "최소 이동 20m 또는 정지 120초"일 때만 전송(#391) / UNAVAILABLE 미전송. 최대 속도 50 m/s, 정확도 상한 100m, 허용 과거 60초·미래 5초, Redis 최신 위치 TTL 10분. **이 값들은 지금 클라이언트(안드로이드)만 쓴다** — 서버측 필터(`LocationAcceptancePolicy`)는 #297에서 제거 후 안 되살렸다. 다시 만들면 두 값이 같아야 한다.
- **위치 갱신 실패 응답 경계**(#81): 좌표 범위 밖·필수 값 누락·정확도 음수·미래 시각은 400. **정확도 상한 초과·60초 초과 과거 fix는 200 + `reason`** 으로 수용·폐기(실내 측위·탭 복귀에서 정상 발생). 그래서 정확도 상한을 `@DecimalMax`로 달 수 없다(Bean Validation 위반은 400).
- **SSE 연결 수 제한은 두지 않는다**(#317, 예전 배송당 3개 ZSET 제한 제거).
- **끊긴 연결 탐지에는 쓰기가 최소 두 번 필요**하다(#317 실측, 첫 쓰기는 소켓 버퍼에 들어가 성공). **"한 번 보내면 정리된다"고 가정하는 테스트를 쓰지 말 것.**
- **"진행 중" 상태 집합이 두 곳**에 있다(#317): V10 마이그레이션의 CASE 식(`active_rider_id` 생성 컬럼)과 `OrderStatus.trackableStatuses()`. **배송 상태 추가 시 Flyway 마이그레이션도 함께 고쳐야 한다**(`DeliveryOrderActiveRiderIntegrationTest`가 동치를 고정하나 코드로 강제되진 않음).

### 주문·배차·상태

- 배차 동시성은 **조건부 UPDATE(Compare-And-Set)**([ADR-006](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90006-%EB%B0%B0%EC%B0%A8-%EB%8F%99%EC%8B%9C%EC%84%B1-%EC%B2%98%EB%A6%AC)). 주문→라이더 순서 고정, 실패 시 재조회로 사유 구분(#56).
- **배차 초과 자동 취소(#42)는 폴링 스캐너(1분 주기) + 지연 만료 하이브리드.** 단일 인스턴스 전제라 조건부 UPDATE만으로 정합성이 보장돼 분산 락 없이 간다.
- **고객 취소(#47)는 시간 제약 없는 범용 "취소하기".** 타임아웃 이후에만 뜨는 조건부 "환급받기"는 만들지 않는다(취소=환급, 후자가 전자의 부분집합).
- **배송 완료 인증(#61)은 주문당 1건**(`uk_delivery_proof_order`), 종류 `PHOTO`/`RECIPIENT_CONFIRMATION`/`AUTH_CODE`. 인증 등록과 완료 전이를 나누지 않고 `RiderDeliveryService.complete()` 한 트랜잭션에 통합(완료 요청에 인증정보가 항상 함께 온다).
- **배송요청 생성은 `estimatedFare`를 받아 서버 재계산값과 대조, 다르면 409**(#37). 대조 전용이며 **결제 금액이 아니다**(차감은 항상 서버 계산값). 프론트는 `/quote` 응답의 `fare.totalFare`를 그대로 실어 보낸다. 예상 요금과 최종 요금 차이는 허용한다.
- 라이더 콜 목록(#55/#367/#60)은 좌표를 선택 파라미터(`latitude`/`longitude`)로 받는다. 주면 bounding box 인덱스(`idx_delivery_waiting_location`)를 타고, 안 주면 반경 필터를 건너뛰고 전체 반환. 운임·거리 필터와 페이지 자르기·커서 비교는 **자바 메모리에서** 수행(bounding box 후보가 수백 건이라는 실측 전제). 응답은 `{items, hasNext}`(총 건수 없음).
- 라이더 운행 기록 상세(`GET /api/rider/history/{deliveryId}`, #71)는 목록과 달리 **확정 운임+정산액을 함께 담는다**("배송 기록=금액 없음" 원칙의 의도된 예외). 소유+COMPLETED를 쿼리 조건에 넣어 없음·타인 것·미완료를 같은 404로 응답.

### 결제·포인트

- **의존 방향은 order → payment 로 고정**(#37/#40). `DeliveryService`가 `CustomerPaymentService`를 주입받고, payment는 order를 주입받지 않는다(참조는 엔터티까지). 반대로 하면 빈 순환이 되는데 Boot 3.4는 순환 참조가 기본 금지라 **기동이 실패**한다.
- **주문 생성과 포인트 차감은 하나의 트랜잭션**(#37/#40). `CustomerPaymentService.payForOrder`는 `Propagation.MANDATORY` — 기본값이면 단독 호출 시 새 트랜잭션이 열려 **주문 없이 포인트만 빠지는** 경로가 생긴다.
- **포인트 부족은 402 PAYMENT_REQUIRED**(#40, 프론트가 `message` 파싱 없이 충전 화면으로 분기).
- **포인트 트랜잭션 잠금 순서는 `point_charge` → `point_wallet` 고정**(#33). 배송 결제·정산도 같은 순서를 지킨다(엇갈리면 데드락).
- **`point_transaction` 요청키 유니크는 `(request_key, transaction_type)`**(V18, #40). ORDER_USE와 ORDER_REFUND가 같은 주문 요청키를 공유하고, 그 공유가 추적 근거가 된다.
- **`point_charge.failure_reason`은 FAILED·CANCELED 두 의미를 겸한다**(#34). 해석 시 반드시 `status`를 함께 본다.
- 결제는 MVP에서 포인트 기반/모킹 흐름 우선(실 PG 연동 아님).
- 라이더 출금 최소 금액은 5,000P(`RiderPaymentService.MIN_WITHDRAWAL_AMOUNT`, #68, 잠정). 계좌 미등록·잔액 부족은 둘 다 409.

### 경로 탐색·ETA

- **경로 탐색은 자체 호스팅 OSRM**(`common/routing/OsrmRoutingClient`, #416/#420). 카카오모빌리티로 바꿨다가(#431) 실제 사용 불가로 되돌렸다(2026-08-10). 계약은 `Optional<Duration>`(`overview=false`, 좌표 미요청). 연결 300ms/읽기 700ms 타임아웃, 재시도 없음, 실패는 예외 대신 빈 값, 4xx는 "경로 없음"이라 실패로 안 셈, 연속 3회 실패 시 호출 스킵(1초→2배씩→30초 상한, `RoutingFailureBackoff`). 평일 출퇴근(Asia/Seoul 07-09·18-20시)엔 raw duration에 **×1.3**(잠정).
- **추적 스냅샷이 ETA를 싣는다**(#421). 출발점은 Redis 최신 위치, 도착점은 픽업 전(`ASSIGNED`·`MOVING_TO_PICKUP`)이면 픽업지, 픽업 후(`PICKED_UP`·`DELIVERING`)면 도착지. **라이더 위치가 없으면 픽업지로 대체하지 않고 null**(출발=도착이 되어 "지금 도착"이라는 틀린 값이 나옴). WAITING도 라이더가 없어 ETA는 null.
- **예상 경로 좌표는 싣지 않는다**(#421, 구현했다 걷어냄). 다시 실을 땐 `{latitude, longitude}` **이름 붙은 객체 배열**로 — GeoJSON `[경도, 위도]`를 그대로 흘리면 카카오맵과 반대인데 서울 좌표는 뒤집어도 유효 범위라 조용히 엉뚱한 곳에 그려진다.
- `DeliveryTrackingQueryService.getTracking`에는 **`@Transactional`이 없다**(외부 HTTP가 DB 커넥션을 안 잡게). 대신 지연 로딩 불가 — **이 경로에 조회를 추가하며 연관을 만지면 `LazyInitializationException`.**

### API 규약

- **오류 응답에 Content-Type을 명시한다**(`GlobalExceptionHandler`, #77). 생략하면 스프링이 `Accept`로 컨텐트 협상을 하고, 브라우저 `EventSource`는 `Accept: text/event-stream`만 보내 401·409·429가 전부 **406**으로 바뀌어 상태코드·`ApiResponse` 본문을 둘 다 잃는다.
- **새 컨트롤러에 `@Operation(operationId = "...")`을 명시**(#194). 생략하면 springdoc이 메서드명을 쓰고 동명 메서드에 `_1`을 붙여 프론트 훅이 액터를 못 구분한다(`useLogin`/`useLogin1`). `OpenApiOperationIdE2ETest`가 잡는다.
- `GlobalExceptionHandler`는 `IllegalStateException`을 일괄 400으로 바꾼다. **서버 정합성 오류는 `BusinessException`으로 명시**해야 사용자 입력 오류와 구분된다(#67).

### 테스트

- 통합·E2E는 **로컬 Docker Redis(`redis:7.4`)**에 붙는다(단위 테스트만 인메모리 대체). 로직 DB `database: 1`을 쓰고 `RedisCleaner`가 매 테스트 전 `FLUSHDB`.
- **개발 PC에 Redis를 직접 설치하지 않는다.** 호스트 Redis(`127.0.0.1:6379`)가 컨테이너를 이겨 **조용히** 호스트에 연결된다 — `RedisCleaner`가 `redis_version`을 확인해 테스트 실패로 만든다.
- **Windows에서 전체 테스트를 한 번에 돌리면 E2E 컨텍스트가 무작위 실패**한다(Tomcat 바인드 실패 `java.net.SocketException: Bad address: listen`). 단독 실행은 통과 — Windows 예약 포트 범위(`netsh interface ipv4 show excludedportrange protocol=tcp`)가 흔한 원인. 테스트 실패 시 먼저 이 패턴인지 확인.

### 기타

- 라이더 상태와 배송 상태를 분리한다.
- 용어: "퀵 신청" 대신 **"배송요청"**.
- 핵심 테이블(ERD 확정): `member`, `rider_profile`, `rider_payout_account`, `term`, `member_term_agreement`, `delivery_order`, `order_status_history`, `fare_policy`, `item_type_surcharge`, `order_fare_snapshot`, `delivery_proof`, `rider_location_history`, `point_wallet`, `point_charge`, `point_transaction`, `rider_settlement`, `rider_withdrawal`. 고객/라이더는 `member.role`로 구분하고, 배차는 별도 테이블 없이 `delivery_order.assigned_rider_id` FK로 처리. `member_notification`은 아직 없다(2·3차 MVP 이후). 세부 컬럼·제약은 `docs/03-erd.md`와 최종 DDL을 따른다.

## 협업

- 흐름: Issue → Branch → 구현·테스트 → PR → Code Review → Merge (Squash Merge 우선)
- 브랜치명·PR에 이슈 번호와 작업 내용을 포함한다. PR에는 변경 내용·선택 이유·테스트 결과·집중 리뷰 영역을 적는다.
- 배차 동시성·상태 전이·정산·포인트 등 핵심 로직은 최소 2명 교차 리뷰.
- 기술적 의사결정은 Wiki ADR에 `문제 → 대안 → 결정 → 이유 → 장단점 → 영향 → 검증` 형식으로 기록한다.
- **"사람 확인" 태그는 실제 확인 없이 붙이지 않는다**(#431에서 근거 없는 기록이 나온 적 있음).

## 확인이 필요한 항목

**답이 정해지지 않았고 다음 작업에 실제로 영향을 주는 것만 남긴다.** 결정이 나면 「확정된 결정」으로 옮기고 여기서 지운다.

### 지금 구현을 막고 있는 것

- **`RiderPointApi`의 `getSettlements`·`getWithdrawals`가 `return null` 스텁**(#69에서 `getPointTransactions`만 구현). 담당 화면·이슈 미정.
- **필수 약관 목록 조회 API(#72)가 없어 회원가입(#25)의 `agreedTermIds`를 채울 방법이 없다.** #72 구현 시 응답 스키마(`termId`)를 맞춰야 한다.
- **역할 무관 세션 확인 API(`GET /api/session`)가 없다.** 두 역할 API 병렬 조회 시 반대 역할의 401이 공용 `SESSION_ID` 쿠키를 만료시키는 버그(#288)로, 프론트가 로그인 시 저장한 역할 힌트로 한쪽만 조회하도록 임시 완화 중. 역할 무관 API가 생기면 힌트를 제거하고 서버 응답의 역할을 정본으로 쓴다.
- **`#22`(아이디 찾기)가 `VerificationCodeStore.consumeVerifiedToken`을 아직 안 쓴다** — 구현 시 연결 필요.
- **라이더 콜 상세(#57)가 물품 무게·수량을 못 준다** — `delivery_order`에 컬럼 없음(`itemType`만). 필요해지면 Flyway 마이그레이션 + 주문 생성 저장까지 함께 논의.
- **배송요청 생성 화면의 기사님 전달사항(#205)을 저장할 컬럼·계약이 없다.** 필요하면 최대 길이·라이더 노출 시점과 함께 별도 이슈로.

### 결정이 필요한 계약·정책

- **에러코드 체계가 없다.** 배차 실패 사유(#56)와 배송요청 생성 409 세 가지(#40)를 프론트가 `message` 문자열로만 구분한다. 사유별 UX가 필요해지면 에러코드 필드 신설 논의.
- **Orval 생성 훅의 `error`가 전부 `AxiosError<unknown>`**(#194). springdoc 공통 에러 스키마 + `override.errorType`을 줄지, 화면에서 좁혀 쓸지 미결.
- **화면 결제수단(카카오페이·휴대폰)과 `PaymentMethod`(`CARD`·`BANK_TRANSFER`) 불일치**(#32). `ck_point_charge_method`도 두 값만 허용 — enum을 넓힐지 화면을 줄일지.
- 정산 생성 시점과 실패 처리 방식.
- 동일 요청 재전송 멱등성 정책(요청 식별값 기준).
- 로그인·회원가입 화면에 비인증 가드를 걸지(#195). 현재는 로그인 상태로도 열린다.

### 알려진 결함 (고칠지 감내할지 미정)

- **`GlobalExceptionHandler`에 `HttpMessageNotReadableException` 핸들러가 없다**(#81). 본문 JSON 파싱 실패 400이 `ApiResponse`가 아닌 스프링 기본 오류 본문으로 나갈 것으로 보임 — **전체 엔드포인트 해당**. 실제 본문 확인 후 이슈화 판단.
- **인증번호 확인(`PhoneVerificationService.confirm`)에 원자적 보호가 없다**(#21). 같은 유효 코드로 동시 확인 시 인증 완료 토큰이 중복 발급될 수 있다.
- **heartbeat가 없다.** 프록시 유휴 타임아웃에 조용한 스트림이 끊길 수 있고, #401로 WAITING 구간이 무음이라 emitter 타임아웃(5분)에 더 쉽게 걸린다(5분이 정책값인지도 미확정).
- **SSE 연결 중 세션이 만료돼도 스트림이 안 끊긴다**(인터셉터는 연결 시점 1회만).
- ~~**주문 완료·취소 시 능동적 SSE 종료가 없다.**~~ **해소(#450, 2026-08-10)**: 완료(`RiderDeliveryService.complete`)와 자동 취소(`DeliveryTimeoutService.cancelAndRefund`)가 `TrackingPublisher.publishClose`로 **별도 채널**(`tracking:close:{id}`, `TrackingCloseSubscriber`)에 발행해 emitter를 닫는다. 닫는 것 자체는 신호가 아니라 **재질의를 유발하는 계기**다 — 브라우저 자동 재연결 → 서버 409 → `EventSource` CLOSED → 프론트 REST 재조회. **이 신호가 유실돼도 정합성은 안 깨진다**(emitter 5분 만료가 같은 사슬을 만든다). 종료 채널 접두어는 데이터 채널과 **완전히 분리해야 한다** — Redis glob의 `*`는 콜론을 포함해 매칭하므로 `tracking:order:{id}:close`로 두면 `TrackingSubscriber`가 종료 신호를 데이터 프레임으로 흘려보낸다. 수동 취소는 일부러 발행하지 않는다(취소한 당사자의 화면이 재조회로 스스로 닫는다).
- **오래 PENDING인 충전 요청을 정리할 방법이 없다**(#34). 결제창을 연 채 브라우저를 닫으면 영구 PENDING.
- **자동 취소 스캐너에 최대 1분의 창이 남는다**(#42). 감내 가능한 백스톱으로 명시 확정은 안 됨.
- **고객이 자기 주문의 "만료됨"을 능동 조회할 방법이 없다**(#44/#46 미구현). 클라이언트 카운트다운(`requestedAt + 5분`)도 미구현 — #46/#47 프론트 연동 시 반영.
- **CI가 테스트를 안 돌린다**(`deploy.yml`이 `-x test`). 켜려면 MySQL·Redis 서비스 컨테이너 필요. **이 공백이 실제로 물렸다**(#70, 컴파일 안 되는 테스트가 dev에 병합).
- CSRF 대응 정책(`SameSite=Lax`가 일부 방어, 별도 토큰 방식 여부 미결).

### 실 PG 연동 시점에 판단 (MVP 범위 밖)

- **PG 벤더 선정 기준 2가지**(#33): ① 승인 API가 인증 토큰 기준으로 **멱등**한가(아니면 PG 호출을 트랜잭션 밖에 두는 현재 파사드 구조가 성립 안 함) ② 결제 **조회** API가 있는가(타임아웃 건 성사 판정의 유일한 수단). 토스페이먼츠는 둘 다 있음.
- 실 PG에 필요하나 지금 없는 것: 망 취소, 거래·정산 대사, 웹훅 수신, `PaymentGateway`의 결제 조회.
- **취소가 승인을 이기면 "돈은 나갔는데 CANCELED"가 될 수 있다**(#34). 경쟁 창이 PG 호출 **앞에** 있어 `APPROVING` 상태 추가만으로는 안 풀린다 — PG 호출 전에 상태를 선점해야 함(워크로그 `2026-07-31-34-point-charge-cancel.md` 부록). 모의 PG로는 검증 불가.
- 충전 금액 정책 잠정값(#32): 1,000 / 1,000,000 / 1,000원 단위. 화면 프리셋 화이트리스트로 좁힐지.

### 인프라 (배포 구성 확정 시)

- Redis 단일 인스턴스 SPOF 여부.
- 배포된 OSRM 서버(#416) 사이징이 지금 트래픽에 맞는지.
- GitHub Actions AWS 인증 방식(OIDC + 최소 권한 IAM Role 권장)과 배포 권한 범위.
- 라이더 콜 목록 `radiusMeters` 상한 없음(#55). 좌표 미전송 요청은 WAITING 전체를 훑어, WAITING이 크게 늘면 계약을 다시 열어야 한다.
- 러시아워 배수(×1.3)·시간대(07-09, 18-20시)가 실측 없는 잠정값. 실제 배송 데이터로 재조정.
- 외부 SMS 발송 연동(현재 로그만 남기는 모킹) — 벤더 선정 시 `SmsSender` 구현체 교체(#20).
- **Flyway 자동 적용 안 되던 버그가 아직 운영에 반영 안 됨**(#373, 수정은 PR #460, 아직 dev·main 머지 전). 운영 `flyway_schema_history`는 V18(정상 — V19가 어느 브랜치에도 아직 안 들어갔으니 당연한 상태). PR #460 머지·배포 후 실제로 새 마이그레이션이 자동 적용되는지 재확인 필요.
- **BUSY 위치 전송 서버 요청량 영향 미확인**(#391). 완료 조건에 있던 항목이 체크 안 된 채 닫혔다 — #259 부하테스트가 답해야 할 항목.
