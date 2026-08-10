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

- 하나의 계정은 `CUSTOMER` 또는 `RIDER` 중 하나의 역할만 가진다(동시 지원 안 함).
- 인증은 **쿠키 기반 서버 세션** 방식. 세션은 Redis에 저장하고, 쿠키에는 세션 식별자만 담는다.
  - Spring Security 없이 필터/인터셉터로 세션 확인·역할 검증·만료·로그아웃을 직접 구현한다.
  - **인증이 선언적으로 자동 적용되지 않는다(Spring Security의 SecurityFilterChain과 다른 점).**
    보호할 API마다 해당 인터셉터의 `addPathPatterns`에 경로를 직접 등록해야 인증이 걸린다.
    (고객 예시: `customer/config/CustomerWebMvcConfig`, #27) 새 고객/라이더 전용 API를 추가할 때
    이 등록을 빠뜨리면 그 API는 인증 없이 열린 채로 배포된다 — 리뷰 시 반드시 확인할 것.
- Redis는 현재 **세션 저장 / 휴대전화 인증번호(TTL) / 라이더 최신 위치(`RiderLocationRepository`,
  **BUSY 라이더만**, TTL 10분) / SSE 이벤트 팬아웃(Pub/Sub)** 에 쓴다. 영속 원본 저장소로는 쓰지 않는다.
  뒤의 둘은 #297(2026-08-02)로 제거했다가 **스케일 아웃 대비로 #317(2026-08-03)에서 되돌린
  것이다** — 단, 서버측 위치 필터는 되살리지 않았다(아래 SSE 항목).
  **GEO 배차 후보 저장(`riders:geo`, `RiderGeoRepository`)은 #342(2026-08-04, 디스커션 #338)에서
  라이더-측 사용처를 전부 제거했다** — 배차 위치 검색을 라이더가 아니라 주문 픽업지 인덱싱으로
  뒤집기로 확정(#101 미구현)했다. 클래스는 호출자 0으로 남아 #339(개명)·형제 이슈 ③(주문 GEO)에서
  재사용된다.
  Pub/Sub은 **SSE 이벤트 팬아웃 용도로만** 쓴다. 작업 큐·도메인 이벤트 버스·인스턴스 간 RPC로
  확장하지 않는다.
- Redis 배포 방식은 **EC2 인스턴스에 직접 설치**(2026-07-29 변경, 디스커션 #176). 관리 부담을 줄이는
  ElastiCache(관리형)를 먼저 검토했으나 **비용 문제**로 EC2 직접 설치로 결정을 뒤집었다.
  MySQL 배치 방식(EC2 직접 설치 vs RDS)도 별도로 아직 미결(아래 「확인이 필요한 항목」).
- 영속성·트랜잭션 정합성이 필요한 데이터는 MySQL이 정본(사용자·배송요청·배차·상태·포인트 원장·정산·위치 이력).
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
    곳에만 존재하는 대신 **필드 추가만 허용하고 제거·의미 변경은 하지 않는다**(Flyway, Redis 값
    형식에 이은 세 번째 배포 호환성 표면). 채널 접두어도 같은 표면이다.
  - SSE 프레임에는 **이벤트 이름을 붙이지 않는다** — 브라우저 기본 `message` 이벤트로 도착해야
    프론트 `useTrackingStream.onmessage`가 받는다. `measuredAt`은 반드시 문자열이어야 한다
    (숫자 타임스탬프가 되면 `parseLocationPing`이 프레임을 통째로 버려 지도가 조용히 멈춘다).
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
- **배차 대기 시간 초과 자동 취소(#42)가 폴링 기반 스캐너(1분 주기) + 지연 만료 하이브리드로
  확정됨**(사람 확인, 2026-08-04). 다만 남은 미결이 있다:
  - 고객이 재주문·배차 수락 시도를 하지 않고 그냥 기다리기만 하면, 실제 취소·환급까지 최대
    스캔 주기(1분)만큼 창이 남는다. 이걸 감내 가능한 백스톱으로 명시적으로 확정하지는 않았다 —
    창을 없애려면 주문별 정밀 타이머(`TaskScheduler.schedule`)가 필요한데, 인스턴스 재시작 시
    예약이 사라지는 문제가 있어 도입하지 않았다.
  - 고객이 자기 주문의 "만료됨" 상태를 능동적으로 조회할 방법이 없다(#44/#46 미구현). 그 화면이
    생기면 `DeliveryTimeoutService.cancelIfExpired`류 판정을 같은 원칙으로 적용해야 한다.
    포인트 잔액 조회(`CustomerPaymentService.getPointBalance`)에 적용하려면 payment → order
    의존이 생겨 아래 "order → payment 의존 방향 고정" 확정 결정과 충돌하므로(순환 참조),
    조정 로직을 어느 레이어에 둘지 별도 설계가 필요하다.
  - 다중 인스턴스로 확장되면 스캐너 중복 실행(정합성 문제 아님, 효율 문제)을 Redis 기반 분산
    락으로 막을지 재검토해야 한다. 지금은(단일 인스턴스 전제) 조건부 UPDATE만으로 정합성이
    보장돼 락 없이 간다.
- **고객 배송 주문 취소(#47)가 시간 제약 없는 범용 "취소하기"로 구현됨**(사람 확인,
  2026-08-04) — 배차 대기 타임아웃(#42) 이후에만 노출되는 조건부 "환급받기" 버튼은 만들지
  않기로 했다(취소=환급이라 후자가 전자의 부분집합). 다만 상세화면에서 `requestedAt + 5분`
  기준 클라이언트 사이드 카운트다운으로 화면을 켜둔 채 자동으로 "만료됨" 안내로 전환되는
  기능은 아직 미구현이다 — 서버 재폴링 없이 순수 클라이언트 계산으로 만들어야 하며,
  #46/#47 프론트 연동 시 반영해야 한다.
- **위치 추적이 #297(2026-08-02)에서 단순화됐고, 그중 두 가지를 #317(2026-08-03)에서 되돌렸다.**
  되돌린 것: Redis 최신 위치 저장(`RiderLocationRepository`, Lua 조건부 갱신), SSE Pub/Sub
  팬아웃(`TrackingChannel`·`TrackingPublisher`·`TrackingSubscriber`·`RedisMessageListenerConfig`).
  **되돌리지 않은 것**: 서버측 위치 필터(`LocationAcceptancePolicy`·`LocationFilter`), MySQL 이력
  저장, heartbeat, 연결 수 제한. 아래가 남은 미결 항목이다.
- **SSE emitter 타임아웃 5분이 실제 정책값인지 미결**(`CustomerTrackingStreamController.TTL_MILLIS`,
  `application.yml`의 `spring.mvc.async.request-timeout`과 같은 값). heartbeat가 없다는 전제가
  예전(#266, heartbeat 15초)과 다르다
- **heartbeat가 없다.** 그래서 CloudFront 등 프록시의 유휴 타임아웃에 조용한 스트림이 끊길 수
  있다(예전에 heartbeat를 둔 이유이기도 하다). **#401(2026-08-06)로 이 공백의 실제 영향이
  커졌다** — WAITING 상태에서도 SSE 연결을 허용하면서, 배차 대기 시간(길면 수 분)만큼 연결이
  완전히 침묵한 채 열려 있을 수 있다. BUSY 라이더의 5초 위치 전송과 달리 이 구간은 대기 시간
  전체가 무음이라 프록시 유휴 타임아웃·emitter 타임아웃(5분)에 더 쉽게 걸린다
- **끊긴 연결 탐지에는 쓰기가 최소 두 번 필요하다**(#317에서 실측). heartbeat가 없어 서버는
  클라이언트가 닫은 것을 스스로 모르고, **끊긴 연결에 대한 첫 쓰기는 소켓 버퍼에 들어가 성공하는
  경우가 많다** — 실패는 그 다음 쓰기부터 올라온다. 탭을 닫은 고객의 연결은 위치 전송 두 주기
  (BUSY 5초 기준 약 10초)까지 레지스트리에 남고, 최종 상한은 emitter 타임아웃 5분이다.
  **"위치를 한 번 보내면 정리된다"고 가정하는 테스트를 쓰지 말 것**
- **연결 수 제한이 없다.** 예전엔 배송당 3개(Redis ZSET)로 막았는데, 지금은 같은 배송에 몇 명이든
  무제한 구독 가능하다. 필요성이 재확인되기 전까지는 만들지 않기로 했다
- **팬아웃 디스패처 풀 크기 4가 적절한지 미검증**(`RedisMessageListenerConfig`). 같은 채널 메시지의
  처리 순서가 뒤집힐 수 있고, 그 복구는 프론트가 `measuredAt`이 역행하는 이벤트를 버리는 것에
  의존하는데 **현재 `useTrackingStream`에 그 가드가 없다.** 부하 테스트에서 확인할 항목
- ~~**위치 관련 Redis 저장소가 둘이고 대상이 운행 상태로 갈린다.** 배차 후보(`RiderGeoRepository`,
  `riders:geo` GEO ZSET)는 **AVAILABLE** 라이더의 집합이고, 최신 위치(`RiderLocationRepository`,
  `rider:location:{riderId}`)는 **BUSY** 만 저장한다(#317).~~ **변경(#342, 2026-08-04, 디스커션
  #338)**: `riders:geo`에 라이더를 쓰거나 읽는 **라이더-측 사용처를 전부 제거**했다. 배차 위치
  검색을 라이더가 아니라 주문 픽업지 인덱싱으로 뒤집기로 확정(#101 미구현)한 결과다. 이제 위치
  Redis 저장소는 `rider:location:{riderId}`(BUSY 최신 위치, TTL 10분) **하나뿐**이다.
  `RiderGeoRepository` 클래스는 **호출자 0**으로 남겨, 이름 변경(#339)·주문 GEO 재사용(형제 이슈 ③)에서
  재활용한다 — 그 전까지 데드 코드처럼 보이지만 의도된 상태다. **서버측 필터(#82)를 되살려도 GEO를
  라이더로 되돌리지는 않는다**(주문 GEO 방향이 확정).
- ~~저장한 최신 위치를 읽는 코드가 없다(#317). `RiderLocationRepository`에 `saveIfNewer`만 있고
  `find`/`decode`는 만들지 않았다 — 호출자가 없어서다.~~ **해소(#311, 2026-08-03)**: 고객 위치
  폴링 API(`GET /api/customer/deliveries/{deliveryId}/location`, `CustomerLocationQueryService`)가
  첫 호출자로 `find`/`decode`를 추가했다.
- **폴링 응답과 SSE `init` 스냅샷의 DTO 공유는 아직 미결이다**(#311). 이슈 #311은 원래 SSE의
  `TrackingInitPayload`(orderId, status, location)와 응답을 공유할 계획이었으나, 그 타입은 #77에서
  만들어졌다가 위치 추적 단순화 PR #289(커밋 `e04b35a`)에서 삭제됐고 #317이 SSE 팬아웃을 되돌릴 때도
  복원되지 않았다 — 지금 SSE `subscribeTracking`은 `"connected"` 코멘트만 보내고 위치 스냅샷을 담은
  init 이벤트가 없다. 그래서 #311은 폴링 전용 `location/dto/CustomerDeliveryLocationResponse`를
  새로 만들었다(사람 확인, 2026-08-03). SSE에 init 스냅샷이 다시 필요해지면 그 이슈에서 이 DTO를
  재사용할지 새로 만들지 판단한다.
- ~~라이더 위치 POST가 배송 ID를 요청 본문으로 직접 받는다(#290) — 아무 배송 ID나 넣으면 남의
  고객 화면에 위치를 흘려보낼 수 있는 구멍(#291)~~ **해소(#317)**: 요청 본문은 좌표만 담고,
  `location/service/RiderLocationService`가 세션의 라이더로 수행 중 배송을 DB에서 풀어 채널을
  정한다. 라이더가 자기 배송 외의 채널로 발행할 방법이 없다. (그 필드가 `@NotNull`이라 좌표만
  보내는 안드로이드 클라이언트의 위치 전송이 **전부 400이던 버그**도 같이 닫혔다.)
- **추적 가능 상태 집합이 두 곳에 있다**(#317). 위치 갱신 핫패스가
  `DeliveryOrderRepository.findInProgressIdByActiveRiderId`(생성 컬럼 `active_rider_id` +
  `uk_delivery_active_rider` 유니크 인덱스 단일 행 조회)를 쓰면서, "어느 상태가 진행 중인가"가
  **V10 마이그레이션의 CASE 식과 `OrderStatus.trackableStatuses()` 양쪽에 존재**한다.
  `DeliveryOrderActiveRiderIntegrationTest`가 모든 상태를 실제로 만들어 동치를 고정하지만,
  **상태를 추가할 때 Flyway 마이그레이션도 함께 바꿔야 한다는 규칙이 코드로 강제되지는 않는다.**
  화면 진입 시 1회 부르는 `findInProgressByRiderId`(상태까지 필요)는 그대로 남아 있다 —
  그쪽은 이력 전체를 훑는 형태라 5초 주기 경로에 쓰면 안 된다.
- **라이더 위치 갱신 한 번이 MySQL 3회 + Redis 3회를 쓴다**(#317). 인터셉터의 `member`·
  `rider_profile` PK 조회 2회 + 배송 조회 1회, GEO 반영 + 최신 위치 Lua + PUBLISH. BUSY 5초
  주기라 동시 배송 수에 비례한다. 인터셉터 비용은 모든 라이더 API 공통 구조여서 손대지 않았고
  **#311 폴링 arm 비교의 핵심 변수**다 — 부하 테스트에서 확인할 항목
- **오류 응답에 Content-Type 을 명시해야 한다**(#77 에서 드러남, `GlobalExceptionHandler` 에 적용,
  단순화 이후에도 유효). 지정하지 않으면 스프링이 `Accept` 로 컨텐트 협상을 하고, 브라우저
  `EventSource` 는 `Accept: text/event-stream` 만 보내므로 401·409·429 가 전부 **406** 으로
  바뀌어 상태코드와 `ApiResponse` 본문을 둘 다 잃는다
- `server.shutdown` 을 설정한 적이 없는데 graceful 로 동작한다(#77 에서 테스트 로그로 확인:
  `Graceful shutdown aborted with one or more requests still active`). SSE 는 끝나지 않으므로
  **종료마다 대기 시간을 소진한 뒤 강제 중단**돼 배포가 그만큼 느려진다. `immediate` 로 명시할지 미결
- **ElastiCache 클러스터 모드를 켜면 SSE 팬아웃이 동작하지 않는다**(#78, #317에서 재도입되며 다시
  유효해진 제약). cluster mode enabled 에서는 Redis 7 의 sharded pub/sub(`SSUBSCRIBE`)이 필요한데
  `RedisMessageListenerContainer` 는 일반 pub/sub 만 다루고 `PSUBSCRIBE` 는 클러스터에서 문제가
  된다. **클러스터 모드 비활성을 전제로 설계했다** — 배포 구성 확정 시 확인 필요
- 라이더 위치 전송 주기와 필터 임계값 >> **#81 에서 확정함**(사람 확인, 2026-07-29). 전송 주기
  AVAILABLE 30초 / BUSY 5초 / UNAVAILABLE 미전송, 최소 이동 거리 20m, 허용 최대 속도 50 m/s
  (180km/h), 정확도 상한 100m, 허용 과거 60초·미래 오차 5초, 정지 시 강제 전송 120초, Redis 최신
  위치 TTL 10분. **지금은 이 값들을 클라이언트(안드로이드 `RiderLocationService`)만 쓴다** —
  서버측 필터(`LocationAcceptancePolicy`)는 #297에서 제거됐고 #317에서도 되살리지 않았다.
  서버 필터를 다시 만들면 두 값이 같아야 한다(클라이언트가 통과시킨 좌표는 서버도 통과해야
  둘이 서로 싸우지 않는다). Redis 최신 위치 TTL 10분은 #317에서 그대로 복원했다.
- 위치 갱신 실패 응답의 경계 >> **#81 에서 확정함**(사람 확인). 좌표 범위 밖·필수 값 누락·정확도
  음수·미래 시각은 400 이지만, **정확도 상한 초과와 60초 초과 과거 fix 는 200 + `reason`** 으로
  수용·폐기한다(실내·지하 측위나 탭 복귀 직후에 정상적으로 발생해 클라이언트가 고칠 것이 없다).
  그래서 정확도 상한을 `@DecimalMax` 로 달 수 없다 — Bean Validation 위반은 400 이 된다.
- ~~운행 종료 후 최대 10분간 라이더 최신 위치가 Redis 에 남는다(#81)~~ **해소(#54, #83)**: GO_OFFLINE
  시 `RiderGeoRepository.remove`(ZREM)를 호출해 운행 종료 즉시 배차 후보에서 뺀다. `location`
  패키지 단순화(#297)로 원래 쓰던 `RiderLocationStore`가 삭제되면서 #83이 만든 GEO 저장소로
  교체했다(같은 의미의 멱등 삭제 연산이라 그대로 대체). remove 를 DB 트랜잭션 커밋 전에 호출하는
  트레이드오프는 부하 테스트 후 재검토(worklog 2026-07-31-54 참조).
- **조건부 갱신에 진 것(`saveIfNewer` 가 false)을 클라이언트에게 알리지 않는다**(#317). 예전에는
  응답에 `NON_MONOTONIC` 사유가 있었지만 #290 이후 위치 갱신 응답이 `ApiResponse<Void>` 라
  반환값을 읽는 곳이 없다. 경쟁 빈도를 알아야 할 때(부하 테스트, 인스턴스 수 조정) 로그나 지표를
  추가할지 판단한다
- **Redis 값 형식 변경도 배포 호환성 검토 대상이다**(#250 에서 처음 드러남). 형식을 바꾸면 이전
  형식 값이 새 파서로 읽히지 않는다 — 조건부 갱신이 손상된 값을 덮으므로 첫 위치 요청에서 해소되고
  TTL 이 10분이라 감내했지만, **롤링 배포 중 구·신 버전이 공존하면 서로의 값을 못 읽는 구간이
  생긴다.** Flyway 마이그레이션 호환성(「확정된 결정」)과 같은 종류의 문제인데 Redis 값에서는
  규칙이 없다. 규칙으로 못 박을지 미결. **#317에서 SSE 팬아웃 페이로드가 세 번째 표면으로
  추가됐다**(구독자가 파싱하지 않고 흘리므로 필드 추가만 허용)
- SSE 연결 중 세션 만료·로그아웃 처리는 여전히 미결이다. 인터셉터는 연결 시점에 한 번만 돌고
  heartbeat 가 없어, 세션이 만료돼도 **emitter 타임아웃(5분)까지 위치가 계속 흐를 수 있다**
- 주문 완료·취소 시 **능동적** SSE 연결 종료가 없다. 라이더가 그 배송에 더는 위치를 안 보내면
  중계 자체는 조용해지지만, 연결은 타임아웃까지 열려 있고 완료 알림이 프론트로 가지 않는다
- `rider_location_history` 테이블(V15)은 스키마에 남아 있지만 지금은 아무 코드도 쓰지 않는다 —
  Flyway 마이그레이션은 한 번 적용되면 되돌리지 않는다는 원칙과 같은 이유로 지우지 않기로 했다
- **emitter 레지스트리가 테스트 사이에 살아남는 문제는 해결됨**(#291). `SseRegistry.clear()`를
  추가하고 `IntegrationTestSupport`가 매 테스트 전에 부른다 — 다중 인스턴스 카운팅 자체가 없어져서
  Redis 쪽도 같이 풀어줘야 했던 예전 `TrackingEmitterCleaner`보다 훨씬 간단해졌다
- ~~배송 완료 인증 데이터 구조(단건/다건, 사진·수령인 확인·인증코드 중 채택 범위)~~
  **해소(#61 검토, 2026-08-04)**: 단건(주문당 1건, `uk_delivery_proof_order`),
  `PHOTO`/`RECIPIENT_CONFIRMATION`/`AUTH_CODE` 3종 채택. 인증 등록(#61)과 완료 전이(#62)를
  별도 API로 분리하지 않고 `RiderDeliveryService.complete()` 하나의 트랜잭션에 통합하기로
  결정함 — 완료 요청에는 인증정보가 항상 함께 필요하다(사람 확인, `docs/worklog/2026-08-04-61-delivery-completion-proof.md`).
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
- **로그아웃 요청이 실패하면(네트워크·5xx) 서버 세션이 남는다**(#222). 설정 화면
  (`account/settings.tsx`)은 이 경우 이 기기의 쿼리 캐시와 역할 힌트만 비우고 실패를 안내한다 —
  서버 세션은 TTL(2시간)까지 유효해서, 쿠키를 쥔 채로는 API 를 계속 부를 수 있다. 재로그인하면
  새 세션으로 덮인다. 재시도 큐를 둘지 그대로 감내할지 미결
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
    MySQL·Redis 서비스 컨테이너가 필요하다. **이 공백이 실제로 물렸다**(#70, 2026-08-04): #339의
    rename 커밋(`1106b52`)이 `RiderGeoRepositoryTest`를 `OrderGeoRepositoryTest.java`로 파일명만 바꾸고
    내용(클래스명·타입 참조·KEY)을 안 고쳐 **컴파일 안 되는 테스트가 dev에 병합**됐다 — CI가 테스트를
    돌렸다면 막혔을 것이다. #70에서 최소 수정해 되살렸다
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
- 역할 무관 세션 확인 API(`GET /api/session`)가 없다. 기존 두 역할 API 병렬 조회는 반대 역할의 401이
  공용 `SESSION_ID` 쿠키를 만료시키는 버그를 일으켜(#288), 프론트가 로그인 성공 시 역할 힌트를 저장하고
  해당 역할 API 하나만 조회하도록 임시 완화했다. 힌트가 없으면 조회하지 않고 비로그인으로 판정하므로,
  역할 무관 API가 생기면 힌트를 제거하고 서버 응답의 역할을 정본으로 사용해야 한다
- 로그인·회원가입 화면(`customer/login`·`rider/login`·`*/signup`)에 비인증 가드를 걸지 미결(#195).
  현재는 로그인 상태로도 로그인 화면이 열린다(비인증 가드는 `auth/*` 에만 적용)
- 프론트 세션 캐시 유효 시간 5분이 적절한지 미검증(#195). 세션 TTL 2시간 고정·슬라이딩 없음(#27)을
  전제로 정한 값이다
- 새 컨트롤러에 `@Operation(operationId = "...")` 명시가 사실상 필수가 됨(#194). 생략하면 springdoc 이
  메서드명을 쓰고 동명 메서드에 `_1` 을 붙여 프론트 훅 이름이 액터를 구분하지 못한다
  (`useLogin` / `useLogin1`, 배정 순서는 컨트롤러 스캔 순서 의존). 현재는 `OpenApiOperationIdE2ETest`
  실패로만 알게 되는데, 리뷰 체크리스트나 PR 템플릿에 넣을지 미결
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
- **PG 벤더 선정 기준 2가지**(#33). ① 승인 API 가 인증 토큰 기준으로 <b>멱등</b>한가 — 아니면 현재
  구조(PG 호출을 트랜잭션 밖에 두는 파사드)가 성립하지 않는다. ② 결제 <b>조회</b> API 가 있는가 —
  타임아웃 건의 성사 여부를 판정할 유일한 수단이다. 토스페이먼츠는 둘 다 있음
- 실 PG 연동 시 필요하나 현재 없는 것(#33): 망 취소, 거래·정산 대사(스케줄러), 웹훅 수신 엔드포인트,
  `PaymentGateway` 의 결제 조회 메서드. 모의 PG 는 돈이 움직이지 않아 지금 만들면 검증이 불가능하다
- 동시 승인 요청이 PG 를 각각 호출할 수 있음(#33). 확정 구간만 잠그기 때문이며, 이중 증액은 상태
  재확인이 막지만 이중 <b>결제</b>는 PG 멱등성에 의존한다. claim 패턴(PG 호출 권한 선점)으로 막을 수
  있으나 상태·컬럼 추가가 따라온다
- **포인트 관련 트랜잭션의 잠금 순서는 `point_charge` → `point_wallet` 로 고정한다**(#33). 배송 결제
  (`ORDER_USE`)·정산이 붙을 때도 같은 순서를 지켜야 한다 — 엇갈리면 데드락이다. 현재 문서와 주석에만
  있고 코드로 강제할 수단은 없다
- `status='PENDING' AND provider_payment_key IS NOT NULL` 은 "PG 승인은 받았는데 포인트 반영이 끝나지
  않은 건"을 뜻한다(#33). 사실상 `APPROVING` 상태를 enum 값 없이 표현한 것이라 PENDING 의 의미가 둘이다
- `PointChargeApprover.alreadyApprovedResponse` 가 트랜잭션 밖에서 조회한 엔터티를 인자로 받는다(#33).
  현재는 단순 getter 만 써서 안전하지만 연관을 건드리면 `LazyInitializationException` 이 난다
- **취소가 승인을 이기면 실 PG 에서 "돈은 나갔는데 CANCELED" 가 될 수 있다**(#34). `confirmPointCharge`
  는 잠금 없이 사전 검증한 뒤 PG 를 부르는데, 그 사이 취소가 커밋되면 승인을 받아 온 뒤
  `finalizeApproval` 이 409 로 막는다. 이때 `recordApprovalReceived` 는 상태가 PENDING 이 아니라 아무것도
  하지 않으므로 **승인 식별자가 DB 에 남지 않아** `status='PENDING' AND provider_payment_key IS NOT NULL`
  대사 조건에도 걸리지 않는다. 모의 PG 는 무해하지만 실 PG 에서는 추적 불가능한 건이 된다.
  CANCELED 에도 승인 식별자를 기록할지, claim 패턴으로 취소를 막을지 미결. **`APPROVING` 상태를
  추가하는 것만으로는 안 풀린다** — 경쟁 창이 PG 호출 앞에 있어서, PG 호출 <b>전에</b> 상태를
  선점해야 닫힌다(검토 내용은 워크로그 `2026-07-31-34-point-charge-cancel.md` 부록). 모의 PG 로는
  검증할 수 없어 MVP 에서는 감수하고 실 PG 벤더 선정 시점에 판단한다
- **오래 PENDING 인 충전 요청을 정리할 방법이 없다**(#34 에서 범위 밖으로 뺌). 결제창을 열어 둔 채
  브라우저를 닫으면 취소 요청이 오지 않아 그 건은 영구히 PENDING 이다. 만료 스케줄러를 둘지, 둔다면
  기준(요청 후 N분)과 실 PG 결제 조회 API 와의 관계를 정해야 한다
- **`point_charge.failure_reason` 이 FAILED·CANCELED 두 의미를 겸한다**(#34, 사람 확인). CANCELED 전용
  사유 컬럼을 두지 않고 재사용하기로 했다 — 마이그레이션이 없고 승인 전 취소 사유는 사실상 고정값
  하나이기 때문이다. **값을 해석할 때 반드시 `status` 를 함께 봐야 한다.** 사유별 집계가 붙으면
  전용 컬럼으로 나눌지 재검토
- `RiderDeliveryRequestApi`의 `getDeliveryRequest`/`acceptDeliveryRequest`/`skipDeliveryRequest`
  세 메서드에 라이더 식별 파라미터(`AuthenticatedRider`)가 빠져 있음(#55) — #56/#57 구현 시
  `getDeliveryRequests`와 같은 방식(`@RequestAttribute`)으로 추가 필요
- 라이더 콜 목록(`GET /api/rider/requests`, #55)에 `radiusMeters` 상한이 없음 — WAITING 주문이
  크게 늘어나는 시점(다도시 동시 운영 등)에 성능 문제가 되면 계약을 다시 열어야 함(페이지네이션은
  #60으로 해소됨, 아래 항목 참고). **#367로 위치 검색 자체는 bounding box 인덱스
  (`idx_delivery_waiting_location`)를 타도록 바뀌었지만
  ([#367 worklog](docs/worklog/2026-08-05-367-rider-call-list-location-search.md)), 좌표를 안
  보내는 요청(#367에서 선택 파라미터로 확정, 사람 확인)은 여전히 WAITING 전체를 걸러 정렬한 뒤
  페이지 크기만큼 잘라 반환한다** — 후보 자체가 줄어들지 않는다는 뜻이라, radiusMeters 상한
  미비는 그대로 남아 있다.
- **콜 목록 필터·정렬 확장(#60)이 완료됨**(사람 확인, 2026-08-06) — 운임·배송거리 범위 필터,
  정렬 방향(`sortDirection`), keyset(커서) 페이지네이션을 추가함
  ([#60 worklog](docs/worklog/2026-08-06-60-rider-quick-request-filter-sort.md)). 운임·배송거리
  필터는 반경 필터와 같은 구조(자바 메모리 필터)로 두었고, 페이지 자르기·커서 비교도 그 필터가
  끝난 최종 목록 위에서 자바로 수행한다(SQL 커서 쿼리 아님) — bounding box 후보가 수백 건
  수준이라는 전제(#367 실측)에서 성능 문제가 없다고 판단했다. 응답이 `List`에서
  `{items, hasNext}`로 바뀌었다(총 건수는 안 담음 — keyset은 매 요청 재조회라 COUNT 비용이
  이 조회 자체와 안 맞음). **새로 생긴 미결**: (a) 여러 정렬 기준 조합(예: "가까우면서 비싼
  순")은 지원하지 않음 — 이슈 입력값이 단일 기준만 전제했음, 필요해지면 별도 이슈. (b) 총
  건수 노출이 필요해지면 keyset과 어떻게 공존시킬지 판단 필요.
- **라이더 좌표를 요청 파라미터로 받는 계약 변경(#367)이 완료됨**(사람 확인, 2026-08-05) — #342가
  없앤 `findPosition(self)`(옛 `riders:geo` 조회)의 대체 경로로, `latitude`/`longitude`를 선택 쿼리
  파라미터로 추가했다. 둘 다 없으면 #55 원래 계약(위치 없음 → 반경 필터 스킵, 전체 반환)을 그대로
  유지한다. 실측(EXPLAIN, 합성 데이터 21.8만 건)으로 `idx_delivery_waiting_location`이 실제로
  선택되고 후보가 크게 줄어드는 것을 확인함(자세한 수치는 위 worklog 참고). 프론트가 GPS 좌표를
  실제로 실어 보내는 연동은 이 백엔드 이슈 범위 밖 — 별도 프론트 이슈 필요
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
- ~~다중 인스턴스 대비 Redis Pub/Sub 재도입은 아직 이슈가 없다~~ **해소(#317, 2026-08-03)**:
  예전 설계(#78, 채널 `tracking:order:{id}` + 패턴 구독)를 `e04b35a^`에서 그대로 꺼내 되돌렸다.
  실제 배포는 여전히 단일 인스턴스이지만 스케일 아웃 준비는 끝났다 — 2인스턴스 E2E 테스트가
  이를 검증한다. 서버측 위치 필터는 되돌리지 않았다(위 SSE 미결 항목 참고).
- 라이더 콜 상세(`GET /api/rider/requests/{deliveryId}`, #57)가 물품 무게·수량을 제공하지 못함 —
  `delivery_order`에 관련 컬럼이 없음(`itemType` 열거형만 존재). 화면에서 실제로 필요해지면 스키마
  변경(Flyway 마이그레이션)과 주문 생성(REQ-ORD-002) 쪽 값 저장까지 함께 논의해야 함
  (2026-07-30: #56 rebase 중 dev 병합 과정에서 이 항목이 유실됐던 걸 복구함)
- `RiderPointApi`의 `getSettlements`(정산 전용)·`getWithdrawals`(출금 전용, 상태 포함)가
  여전히 `return null` 스텁이다(#69에서 `getPointTransactions`만 구현). 어느 화면·이슈가
  이 둘을 담당하는지 명시돼 있지 않다 — `getSettlements`는 아직 없는 `/api/rider/history`
  주간 요약용으로, `getWithdrawals`는 #103(출금 요청 상세)류와 겹치는 것으로 추정만 함
- 라이더 운행 기록 상세(`GET /api/rider/history/{deliveryId}`, #71)는 목록(#70)과 달리 확정 운임+정산액을
  함께 담는다(사람 확인, 2026-08-05) — "배송 기록=금액 없음, 정산=포인트 API" 원칙의 의도된 예외
  (정산 확인 화면). 소유+COMPLETED 를 쿼리 조건에 넣어 없음·타인 것·미완료를 같은 404 로 응답한다.
  프론트 `history/$deliveryId` 목업 연결(#217 계열)은 아직 미구현. 타임라인·요금 변환 공용 팩토리
  (`DeliveryStatusStepResponse.timelineOf`, `FareBreakdownResponse.from`)를 신설했으나 기존 복제 3곳
  통합은 범위 밖으로 남김
- 포인트 지갑 없음의 응답 코드가 고객(400)과 라이더(500)로 갈린다(#67). 라이더는
  `BusinessException(INTERNAL_SERVER_ERROR)`로 `RiderPointApi` 문서와 맞췄지만, 고객
  (`CustomerPaymentService`)은 `IllegalStateException` → `GlobalExceptionHandler` → 400 이라
  `CustomerPointApi` 문서에 적힌 500 과 어긋난 상태다. 지갑은 회원가입 트랜잭션에서 함께 생성되므로
  정상 경로에서는 둘 다 발생하지 않지만, 고객 쪽을 500 으로 맞추면 기존 API 의 응답 계약이 바뀌어
  프론트 영향 검토가 필요하다 — 별도 이슈로 올릴지 미결
- `GlobalExceptionHandler`가 `IllegalStateException`을 일괄 400 으로 바꾼다(#67에서 드러남). 서버
  정합성 오류를 사용자 입력 오류와 구분하려면 매번 `BusinessException`을 명시해야 한다는 뜻인데,
  이 관례를 `references/backend.md`에 못 박을지 미결
- **order ↔ payment 서비스 의존 방향은 order → payment 로 고정한다**(#37/#40, 사람 확인 2026-08-01).
  `DeliveryService` 가 `CustomerPaymentService` 를 주입받고, **payment 서비스는 order 서비스를
  주입받지 않는다.** 반대로 하면 스프링 빈 순환이 되는데, Boot 3.4 는 순환 참조가 기본 금지라
  **컴파일이 아니라 기동이 실패한다.** payment 가 order 를 참조하는 것은 엔터티까지다
  (`PointTransaction.deliveryOrder`, `RiderSettlement.deliveryOrder`).
- **주문 생성과 포인트 차감은 하나의 트랜잭션이다**(#37/#40). `CustomerPaymentService.payForOrder`
  는 `Propagation.MANDATORY` 라 상위 트랜잭션 밖에서는 호출 자체가 실패한다 — 기본값이면 이 메서드만
  따로 불렀을 때 조용히 새 트랜잭션이 열려 **주문 없이 포인트만 빠져나가는** 경로가 생긴다.
- **배송요청 생성은 `estimatedFare` 를 받아 서버 재계산값과 대조하고, 다르면 409로 거부한다**(#37,
  사람 확인). 대조 전용이며 **결제 금액으로 쓰이지 않는다**(차감은 항상 서버 계산값). 화면이 5,000원을
  안내하고 5,500원이 빠져나가는 것을 막기 위한 것이다. 프론트는 `/quote` 응답의 `fare.totalFare` 를
  그대로 실어 보낸다.
- **포인트 부족은 402 PAYMENT_REQUIRED 다**(#40, 사람 확인). 이 저장소에서 402를 쓰는 첫 API이며,
  프론트가 `message` 파싱 없이 충전 화면으로 분기할 수 있게 하려는 것이다. 이 엔드포인트의 409는
  진행 중 1건 위반·동시 재전송·요금 변경 **세 가지를 겸한다** — 앞의 둘은 같은 INSERT 의 제약 위반이라
  앱에서 구분할 수 없다(제약명 파싱은 DB 벤더 문자열 의존이라 하지 않았다). #56의 에러코드 체계
  논의와 같은 사안이다.
- **`point_transaction` 의 요청키 유니크는 `(request_key, transaction_type)` 이다**(V18, #40에서 완화).
  원래 `request_key` 전역 유니크였는데, 그러면 `uk_point_transaction_order_type` 이 허용하는
  "한 주문에 ORDER_USE + ORDER_REFUND"를 키가 막았다(`CHAR(36)` 고정폭이라 접미사도 못 붙인다).
  이제 ORDER_USE 와 ORDER_REFUND 가 같은 주문 요청키를 공유할 수 있고, 그 공유 자체가 "같은 주문
  요청에서 나온 결제와 환불"이라는 추적 근거가 된다. 멱등성은 유형까지 포함한 조합으로 유지된다
- **`/api/customer/deliveries` 아래는 인터셉터에 정확 경로로 한 건씩 등록한다**(#37). 형제 경로
  `/quote`(요금 견적, #39)가 비로그인 API 라 `/api/customer/deliveries/**` 로 넓히면 그 API 가 조용히
  401 이 되어 프론트 견적 화면이 깨진다. `CustomerDeliveryCreateE2ETest` 가 "/quote 는 쿠키 없이
  200" 을 회귀로 고정해 둔다
- **SSE 재연결이 연결 자리를 재사용하지 않는다**(#79 에서 확인, 사람 결정으로 범위에서 제외).
  연결 한도 3 + 서버 `retry: 3000` + stale 임계 45초라서, **45초 안에 3번 끊겼다 붙으면 4번째가 429**
  이고 브라우저 `EventSource` 는 200 아닌 응답에 재연결하지 않아 **추적 화면이 수동 새로고침까지
  죽는다**(지하철·엘리베이터에서 현실적으로 발생). 해법은 클라이언트가 보낸 불투명 식별자를 emitter
  id 로 쓰는 것이다 — `RedisTrackingConnectionLimiter` 의 Lua 가 이미 같은 멤버의 재획득을 허용한다.
  단 그때 **죽어 가는 이전 연결의 늦은 `onCompletion` 이 같은 id 를 이어받은 새 연결을 지우는**
  use-after-free 를 함께 막아야 한다(`complete()` 는 콜백을 동기로 돌리지 않는다) — 레지스트리 제거를
  값까지 비교하는 형태로 바꾸면 된다. 별도 이슈로 올릴지 미결
- 추적 스냅샷(`GET /api/customer/deliveries/{deliveryId}/tracking`, #79)이 **스트림과 같은 게이트를 쓴다**
  (사람 확인) — **#401(2026-08-06)로 WAITING은 이 게이트를 통과하도록 바뀌었다**(라이더 배정 전이라도
  상태 전이 SSE는 받을 수 있어야 하므로, `OrderStatus.isTerminal()`로 판정을 바꿈). 지금은
  **COMPLETED·CANCELED만 409고, 완료·취소된 배송의 추적 화면을 그릴 API가 없다**(배송요청 상세 API는
  아직 스텁). 또 `estimatedArrivalAt`은 항상 null이며(산정 근거 없음), `steps`는 `order_status_history`가
  아니라 `delivery_order` 시각 컬럼에서 파생한다 — 그 테이블은 엔터티만 있고 **행을 쓰는 코드가 없어
  런타임에 비어 있다**(상태 전이 API 이슈에서 작성기 필요)
- 배차 확정(`POST /api/rider/requests/{deliveryId}/accept`, #56) 실패 사유(취소/이미 배차/라이더
  다른 배송 수행 중)를 `ApiResponse`에 에러코드 필드 없이 `message` 문자열로만 구분함(ADR-006).
  프론트가 사유별로 다른 UX를 보여줘야 하면 에러코드 체계 신설을 별도 이슈로 논의해야 함
- 배송요청 생성 화면의 기사님 전달사항(#205)을 저장할 백엔드 계약과 `delivery_order` 컬럼이 없다.
  저장이 필요하면 최대 길이·라이더 노출 시점과 함께 DTO·Flyway 범위를 별도 이슈로 정해야 함
- 라이더 출금 최소 금액이 잠정값(#68, 사람 확인): `RiderPaymentService.MIN_WITHDRAWAL_AMOUNT` 5,000P.
  충전 최소 단위(#32, 1,000원)와는 별개 상수이며 화면 프리셋·이체 수수료 정책이 확정되면 재검토
  필요. 계좌 미등록·잔액 부족은 둘 다 409 로 응답한다(`RiderPointApi` 문서에서 이미 확정).
