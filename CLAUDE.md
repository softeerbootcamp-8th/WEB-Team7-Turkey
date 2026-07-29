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
- 변경 후 관련 단위/통합 테스트를 작성·수정한다. 실행하지 않은 테스트를 실행했다고 표현하지 않는다.
- 비밀정보(환경 변수, AWS·세션 키, DB 비밀번호)를 저장소에 커밋하지 않는다.
- 기능과 무관한 포맷팅·파일 이동·이름 변경을 함께 수행하지 않는다.
- 불확실한 정책은 추측해 고정하지 말고 「확인이 필요한 항목」에 남긴다.

## 금지 사항

- **Spring Security, Spring Batch, Spring AI 사용 금지**
- **Docker 사용 제약 없음**(2026-07-29 변경). 로컬 개발 DB(`backend/docker-compose.yml`) 외에도 애플리케이션 컨테이너화, 배포 파이프라인, 테스트 인프라(Testcontainers 등)에 자유롭게 사용할 수 있다. (변경 전 정책: 로컬 개발 DB 실행 용도로만 허용)
- **Redis Pub/Sub, Redis Streams 사용 금지**

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
- Redis 용도는 4가지로 한정: **세션 저장 / 라이더 최신 위치 / GEO 위치 검색 / 휴대전화 인증번호(TTL)**. 영속 원본 저장소로 쓰지 않는다(#20 작업 중 확장, `docs/worklog/2026-07-28-20-phone-verification-request.md` 참고).
- Redis 배포 방식은 **AWS ElastiCache(관리형)**. EC2에 직접 설치하지 않는다(2026-07-29 결정).
  이유: 페일오버·노드 장애 감지·재연결 로직을 직접 구현할 필요가 없고, 자동 백업·모니터링·헬스체크를
  AWS가 제공한다. 팀 프로젝트 종료 후 지속 운영 인력이 없는 구조라 "위임하면 알아서 돌아가는 시스템"이
  안전하다는 판단. 트레이드오프: EC2 직접 설치 대비 비용 프리미엄이 있고(프리티어/크레딧 확인 필요),
  `redis.conf` 세밀한 튜닝은 파라미터 그룹으로 제한되나 이 프로젝트 스코프에서는 문제 없다고 판단함.
  MySQL 배치 방식(EC2 직접 설치 vs RDS)은 별도이며 아직 미결(아래 「확인이 필요한 항목」).
- 영속성·트랜잭션 정합성이 필요한 데이터는 MySQL이 정본(사용자·배송요청·배차·상태·포인트 원장·정산·위치 이력).
- 실시간 라이더 위치 전달은 **SSE** 사용(Polling 아님). 위치가 실제로 변경됐을 때만 이벤트 전송.
  - 초기엔 단일 WAS이므로 Redis를 이벤트 브로커로 쓰지 않고, 위치 갱신을 처리한 앱이 기존 SSE 연결로 직접 전달한다.
- 라이더 상태와 배송 상태를 분리한다.
- 초기 구조는 단일 모놀리식 Spring Boot WAS(코드 수준에서만 책임 분리, MSA 아님).
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

- 배차 동시성 제어 방식(DB 락 vs 조건부 업데이트)
- 동일 요청 재전송에 대한 API 멱등성 정책(요청 식별값 기준)
- 예상 요금과 최종 요금의 차이 허용 >> 허용하기로 했음
- 포인트 차감·환불 시점, 포인트 동시성 처리 방식(선차감 vs 결제 승인 모킹 포함)
- 라이더 위치 이력의 MySQL 저장 기준(시간/이동거리/상태변화)
- SSE 타임아웃·재연결·heartbeat·중복 연결 정책
- 라이더 위치 전송 주기(AVAILABLE 저빈도 / BUSY 고빈도)의 구체적 수치와 중복·이상치 필터 임계값(ADR-0002 후속)
- 배송 완료 인증 데이터 구조(단건/다건, 사진·수령인 확인·인증코드 중 채택 범위)
- 정산 생성 시점과 실패 처리 방식
- 주소·좌표 컬럼 구조, 배차 결과를 별도 테이블로 둘지 주문 FK로 단순화할지
- 포인트 잔액 캐시 컬럼 유지 여부, 논리 삭제 사용 범위
- EC2 구성 및 MySQL 배치 방식(EC2 직접 설치 vs RDS). Redis는 ElastiCache로 결정됨(위 「확정된 결정」).
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
- 통합/E2E 테스트가 Redis를 인메모리 대체(`InMemoryVerificationCodeStore`)로만 검증함 — 실제 Redis TTL 만료 동작은 아직 검증되지 않음(#20)
- 외부 SMS 발송 연동(현재는 로그만 남기는 모킹) — 실제 벤더 선정 시 `SmsSender` 구현체 교체 필요(#20)
- 인증번호 확인(`PhoneVerificationService.confirm`)에 원자적 보호가 없어, 동시에 같은 유효 코드로 확인
  요청이 오면 인증 완료 토큰이 중복 발급될 수 있음(#21) — 발생 가능성·영향 재검토 필요
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
- #237에서 회원가입 시 `PointWallet` 생성 누락을 고쳤는데, 이 수정 이전에 가입된 기존 회원(로컬/dev
  데이터)은 `point_wallet` 행이 없는 상태로 남아 있다. 배포 전 백필 마이그레이션이 필요한지, 아니면
  포인트 조회/충전 API를 만들 때 "없으면 지연 생성"으로 방어할지 결정 필요
