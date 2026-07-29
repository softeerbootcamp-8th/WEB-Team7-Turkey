---
title: Turkey 프로젝트 컨텍스트
status: draft
updated_at: 2026-07-24
owner: WEB-Team7-Turkey
source_of_truth: true
---

# Turkey 프로젝트 컨텍스트

## 1. 프로젝트 목적

Turkey는 고객과 주변 라이더를 실시간으로 연결하는 퀵배송 매칭 서비스다.
카카오 T 퀵의 사용자 흐름을 참고하며 다음 핵심 흐름을 구현한다.

```text
배송요청 생성
→ 주변 라이더 탐색
→ 배차 수락
→ 픽업지 이동
→ 물품 인수
→ 실시간 위치 추적
→ 배송 완료
→ 정산
```

프로젝트 문서와 코드에서는 `퀵 신청` 대신 `배송요청`이라는 용어를 사용한다.

## 2. 프로젝트 기본 정보

- 소속: Softeer Bootcamp 8기 Team 7
- 기간: 2026년 7월 ~ 8월
- 팀 구성: 백엔드 개발자 5명
- 구조: 모놀리식 Spring Boot 애플리케이션, **수평 확장 가능**(2026-07-29 요구사항 변경 — 그 전에는
  단일 인스턴스 전제였다)
- 개발 방향: 기능 수보다 핵심 난제의 안정성, 테스트, 의사결정 기록을 우선한다.

## 3. 핵심 목표

- Redis GEO 기반 주변 라이더 검색
- 동시 수락 상황에서 단일 배차 보장
- Redis와 SSE를 이용한 실시간 위치 처리
- 라이더 상태와 배송 상태의 책임 분리
- 유효한 배송 상태 전이 보장
- 배송 완료와 정산의 트랜잭션 정합성 확보
- 중복 요청, 연결 단절 및 장애 상황 검증

## 4. 확정된 아키텍처

```text
Customer Client
  ├─ 쿠키 세션 요청
  └─ SSE 위치 구독
             │
Rider Client │
  ├─ 쿠키 세션 요청
  └─ 위치 전송
             ↓
   Load Balancer
             ↓
  ┌──────────┴──────────┐
  ↓                     ↓
Spring Boot WAS #1 … #N   (같은 애플리케이션 N대)
  ├─ 인증 및 세션
  ├─ 고객 / 라이더
  ├─ 배송요청 / 배차
  ├─ 상태 전이
  ├─ 위치 / SSE
  ├─ 포인트
  └─ 정산
       │
       ├──────────────┐
       ↓              ↓
    MySQL           Redis
  영속 데이터      세션
  트랜잭션          최신 위치
  위치 이력         GEO 검색
```

- WAS는 **여러 대로 수평 확장**한다(2026-07-29 요구사항 변경). 상태를 JVM에 두지 않고 MySQL·Redis에
  두므로 스티키 세션이 필요 없다.
- 기능별 책임은 코드 수준에서 분리하되 MSA로 분리하지 않는다. **수평 확장은 같은 모놀리스를 여러 대
  띄우는 것이라 MSA와 무관하다.**
- Redis Pub/Sub과 Redis Streams는 사용하지 않는다. → **재검토 중.** 이 금지는 WAS가 1대라는 전제
  위에서만 비용이 0이었다. 다중 인스턴스에서는 SSE 이벤트 팬아웃 수단이 필요하다
  ([Discussion #246](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/246)).
- ~~위치 요청을 처리한 WAS가 기존 `SseEmitter`에 직접 이벤트를 전달한다.~~ → **무효.** 위치를 처리한
  인스턴스와 고객의 SSE 연결을 들고 있는 인스턴스가 다를 수 있다. 대체 방식은 위 Discussion에서 결정한다.

### 4.1 프론트엔드 라우트 구조 (ADR-0002, 결정일 2026-07-23)

라우트는 액터(고객/라이더) 기준으로 구성한다.

- 라우트 폴더는 ERD 엔터티에 맞춰 `customer/` · `rider/`를 사용하고, 동적 세그먼트는 `$deliveryId`로 통일한다.
- 인증 라우트는 가드 요구에 따라 분리한다: `auth/`(로그인 전, 비인증 가드) vs `account/`(로그인 후, 인증 가드 — 회원정보·계정 관리·알림함은 고객/라이더 공용).
- 인가는 프론트 라우트 가드가 1차, 서버가 2차다(Spring Security 미사용).
- 라이더 상태(`UNAVAILABLE`/`AVAILABLE`/`BUSY`)는 화면과 매핑된다: 홈(`rider/index`) → 콜 목록(`rider/requests`, 위치 저빈도 전송) → 진행 배송(`rider/delivery`, 위치 고빈도 전송, id 없는 고정 경로). 상태 전이는 각 화면의 버튼으로 일어나며 별도 상태변경 화면은 없다.
- 위치 전송은 라우트가 아니라 공용 훅(`shared/hooks/useLocationSender`)으로 구현하며, 웹 클라이언트의 위치 수집은 포그라운드 실행을 전제로 한다(§5 정책과 일치).
- 고객의 실시간 위치 구독은 별도 훅(`shared/hooks/useTrackingStream`)으로 SSE 연결·재연결·종료를 처리한다.

## 5. 확정된 핵심 정책

- 계정은 `CUSTOMER` 또는 `RIDER` 역할 하나만 가진다.
- 인증은 쿠키 기반 서버 세션을 사용하며 세션은 Redis에 저장한다.
- 최신 라이더 위치는 Redis에 저장하고 주변 검색은 Redis GEO를 사용한다.
- 고객에게 라이더 위치를 전달하는 방식은 SSE다.
- 웹 클라이언트의 위치 수집은 포그라운드 실행을 전제로 한다.
- 라이더 상태는 `UNAVAILABLE`, `AVAILABLE`, `BUSY`다.
- 배송 상태는 `WAITING`, `ASSIGNED`, `MOVING_TO_PICKUP`, `PICKED_UP`, `DELIVERING`, `COMPLETED`, `CANCELED`다.
- 고객의 일반 취소는 `WAITING` 상태에서만 허용한다.
- 한 고객은 동시에 진행 중인 배송요청을 최대 1건만 가진다.

세부 정책은 [02-domain-policy.md](./02-domain-policy.md)를 따른다.

## 6. 기술 스택과 제약

### Backend

- Java 21
- Spring Boot
- Gradle
- JUnit
- AssertJ
- SSE

### Frontend

- React
- TanStack Router (파일 기반 라우팅, `routeTree.gen.ts` 자동 생성)
- TanStack Query
- Orval (OpenAPI 기반 API 클라이언트 자동 생성, `src/api/generated/` 수정 금지)
- axios
- shadcn/ui

### Data

- MySQL 8.4
- Redis

### Infrastructure

- WAS(Spring Boot)는 AWS EC2 직접 설치·운영
- Redis는 AWS ElastiCache(관리형) 사용, EC2 직접 설치 아님(2026-07-29 결정, CLAUDE.md 「확정된 결정」 참고)
- MySQL 배치 방식(EC2 직접 설치 vs RDS)은 미결
- Docker 사용 제약 없음(2026-07-29 변경, CLAUDE.md 「금지 사항」 참고)
- GitHub Actions 기반 CI/CD 검토

### 사용 불가

- Spring Security
- Spring Batch
- Spring AI

## 7. 문서 우선순위

문서 간 내용이 충돌하면 다음 순서로 판단한다.

1. 가장 최근에 승인된 ADR(근데 clauede code에서 접근 어렵다고함)
2. ERD와 DDL
3. 도메인 정책
4. 기능 명세
5. 프로젝트 컨텍스트


## 8. 연관 문서

- [기능 명세](./01-functional-spec.pdf)
- [도메인 정책](./02-domain-policy.md)
- [ERD](./03-erd.md)
- [API 명세](./04-api-spec.md)
- [ADR 목록](./adr/README.md)
  - ADR-0002: 프론트엔드 라우트 구조·위치 전송 훅 설계 (Wiki)
