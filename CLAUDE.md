---
title: Turkey 프로젝트 지침
status: active
updated_at: 2026-07-23
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

문서 간 내용이 충돌하면 ADR → ERD/DDL → 도메인 정책 → 기능 명세 → 프로젝트 컨텍스트 순으로 판단한다.

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

- **Spring Security, Spring Batch, Spring AI, Docker 사용 금지**
- **Redis Pub/Sub, Redis Streams 사용 금지**

## 저장소 구조

- 백엔드 단일 Spring Boot 애플리케이션이며, 프론트엔드가 추가되면 하나의 GitHub 모노레포로 관리한다.
- 기능별 패키지 구성: `com.turkey.quick.{customer, rider, order, matching, location, payment, common}`
- `common` 하위: `config`, `exception`, `response`
- DB 스키마는 Flyway(`src/main/resources/db/migration`)로 관리하며 앱 기동 시 자동 실행된다.
- CI/CD는 경로 필터로 프론트엔드 배포와 백엔드 배포를 분리한다(`.github/workflows`).

## 기술 스택

- Backend: Java 21, Spring ruBoot 3.4.x, Gradle, Lombok, JUnit + AssertJ, SSE, Flyway
- Data: MySQL 8.4, Redis
- Infra: AWS EC2(백엔드), S3(프론트 빌드 산출물), CloudFront(CDN), GitHub Actions
- 데이터 접근 기술은 jpa

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

## 확정된 결정

- 하나의 계정은 `CUSTOMER` 또는 `RIDER` 중 하나의 역할만 가진다(동시 지원 안 함).
- 인증은 **쿠키 기반 서버 세션** 방식. 세션은 Redis에 저장하고, 쿠키에는 세션 식별자만 담는다.
  - Spring Security 없이 필터/인터셉터로 세션 확인·역할 검증·만료·로그아웃을 직접 구현한다.
- Redis 용도는 3가지로 한정: **세션 저장 / 라이더 최신 위치 / GEO 위치 검색**. 영속 원본 저장소로 쓰지 않는다.
- 영속성·트랜잭션 정합성이 필요한 데이터는 MySQL이 정본(사용자·배송요청·배차·상태·포인트 원장·정산·위치 이력).
- 실시간 라이더 위치 전달은 **SSE** 사용(Polling 아님). 위치가 실제로 변경됐을 때만 이벤트 전송.
  - 초기엔 단일 WAS이므로 Redis를 이벤트 브로커로 쓰지 않고, 위치 갱신을 처리한 앱이 기존 SSE 연결로 직접 전달한다.
- 라이더 상태와 배송 상태를 분리한다.
- 초기 구조는 단일 모놀리식 Spring Boot WAS(코드 수준에서만 책임 분리, MSA 아님).
- 프론트 빌드 산출물은 S3에 배포하고 CloudFront로 제공. 정적 요청은 CloudFront·S3, API·SSE는 EC2 Spring Boot가 처리.
- 결제는 MVP에서 포인트 기반 또는 모킹 흐름 우선(실 PG 연동 아님).
- 용어: "퀵 신청" 대신 **"배송요청"**을 사용한다.
- 핵심 테이블(ERD 확정): `member`, `customer`, `rider`, `delivery_order`, `delivery_assignment`, `point_account`, `point_transaction`, `settlement`, `delivery_proof`, `rider_location_history`. 세부 컬럼·제약은 `docs/03-erd.md`와 최종 DDL을 따른다.

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
- 배송 완료 인증 데이터 구조(단건/다건, 사진·수령인 확인·인증코드 중 채택 범위)
- 정산 생성 시점과 실패 처리 방식
- 주소·좌표 컬럼 구조, 배차 결과를 별도 테이블로 둘지 주문 FK로 단순화할지
- 포인트 잔액 캐시 컬럼 유지 여부, 논리 삭제 사용 범위
- EC2 구성 및 MySQL·Redis 배치 방식
- S3·CloudFront·도메인·인증서 구성, 캐시/invalidation 정책
- 프론트 Origin과 API Origin 분리 시 CORS·쿠키 설정
- GitHub Actions의 AWS 인증 방식(OIDC + 최소 권한 IAM Role 권장)과 배포 권한 범위
- Redis 장애 시 세션·위치 기능 대응 방식
- 쿠키 보안 속성(`Secure`/`HttpOnly`/`SameSite`/`Domain`)과 CSRF 대응 정책
