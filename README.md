# Turkey

> 카카오 T 퀵의 사용자 흐름을 참고한 실시간 퀵배송 서비스

**Softeer Bootcamp 8기 Team 7 종합 프로젝트**

<br>

## 🎬 시연

> 시연 영상 / GIF 추가 예정

<!-- 데모 영상이나 GIF를 이 자리에 추가합니다.
     예: [![데모 영상](썸네일_URL)](유튜브_URL)
     또는: ![데모](./docs/images/demo.gif) -->

<br>

## 🛠️ 기술 스택

### 💻 Frontend

서비스의 사용자 인터페이스(UI)와 상호작용 로직을 담당합니다.

![Vite](https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white) ![pnpm](https://img.shields.io/badge/pnpm-F69220?style=for-the-badge&logo=pnpm&logoColor=white)

![React](https://img.shields.io/badge/React-61DAFB?style=for-the-badge&logo=react&logoColor=black) ![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white)

![TanStack Query](https://img.shields.io/badge/TanStack_Query-FF4154?style=for-the-badge&logo=reactquery&logoColor=white) ![TanStack Router](https://img.shields.io/badge/TanStack_Router-0EA5E9?style=for-the-badge&logo=tanstack&logoColor=white)

![Tailwind CSS](https://img.shields.io/badge/Tailwind_CSS-06B6D4?style=for-the-badge&logo=tailwindcss&logoColor=white) ![shadcn/ui](https://img.shields.io/badge/shadcn%2Fui-000000?style=for-the-badge&logo=shadcnui&logoColor=white)

![axios](https://img.shields.io/badge/axios-5A29E4?style=for-the-badge&logo=axios&logoColor=white) ![Orval](https://img.shields.io/badge/Orval-FF6B6B?style=for-the-badge)

![Capacitor](https://img.shields.io/badge/Capacitor-119EFF?style=for-the-badge&logo=capacitor&logoColor=white) ![Vitest](https://img.shields.io/badge/Vitest-6E9F18?style=for-the-badge&logo=vitest&logoColor=white)

### ⚙️ Backend

서비스의 비즈니스 로직과 API를 담당합니다. Spring Security는 사용하지 않고 인증·인가를 직접 구현합니다.

![Java](https://img.shields.io/badge/Java%2021-007396?style=for-the-badge&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white)

![JPA](https://img.shields.io/badge/JPA-59666C?style=for-the-badge&logo=hibernate&logoColor=white) ![Lombok](https://img.shields.io/badge/Lombok-C1272D?style=for-the-badge) ![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=for-the-badge&logo=flyway&logoColor=white)

![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=for-the-badge&logo=junit5&logoColor=white) ![AssertJ](https://img.shields.io/badge/AssertJ-2A2A2A?style=for-the-badge) ![SSE](https://img.shields.io/badge/Server_Sent_Events-000000?style=for-the-badge)

### 🗄️ Data

영속 데이터, 세션, 실시간 위치를 저장합니다.

![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white) ![Redis](https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white)

### ☁️ Infrastructure

배포와 정적 자원 서빙을 담당합니다.

![AWS EC2](https://img.shields.io/badge/AWS%20EC2-FF9900?style=for-the-badge&logo=amazonec2&logoColor=white) ![AWS S3](https://img.shields.io/badge/AWS%20S3-569A31?style=for-the-badge&logo=amazons3&logoColor=white) ![AWS CloudFront](https://img.shields.io/badge/CloudFront-8C4FFF?style=for-the-badge&logo=amazonaws&logoColor=white)

![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white) ![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white)

### 📈 Monitoring

서비스 상태와 성능 지표를 관찰합니다.

![Spring Boot Actuator](https://img.shields.io/badge/Actuator-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white) ![Grafana](https://img.shields.io/badge/Grafana-F46800?style=for-the-badge&logo=grafana&logoColor=white)

<br>

## 🚚 프로젝트 소개

Turkey는 물품을 빠르게 배송하려는 고객과 주변 라이더를 실시간으로 연결하는 웹 기반 퀵서비스 플랫폼입니다.

고객은 출발지와 도착지를 입력해 배송을 요청하고, 라이더는 주변 배송 요청을 확인한 뒤 원하는 요청을 수락할 수 있습니다.

주문 생성부터 배차, 라이더 위치 추적, 배송 완료까지 퀵서비스의 핵심 흐름을 구현합니다.

<br>

## 🎯 프로젝트 목표

* 동시 배송 수락 상황에서 중복 배차 방지
* 라이더 위치의 실시간 수집 및 전달
* 안전한 배송 상태 전이 관리
* 네트워크 단절 및 장애 상황을 고려한 배송 흐름 설계

<br>

## 서비스 아키텍처
![architecture_img](./imgs/architecture.png)

<br>

## 📌 주요 기능

### 고객

* 출발지와 도착지를 기반으로 배송 요청
* 예상 거리와 배송 요금 확인
* 배차 진행 상태 확인
* 배정된 라이더 정보 확인
* 라이더 위치와 배송 상태 실시간 조회
* 배송 완료 증빙 및 배송 내역 확인

### 라이더

* 대기 상태 활성화 및 현재 위치 공유
* 주변 배송 요청 조회
* 배송 요청 상세 정보 확인 및 수락
* 픽업지 이동 및 물품 수령 처리
* 배송 중 실시간 위치 전송
* 배송 완료 사진 등록

<br>

## 🔄 서비스 흐름

```text
고객 배송 요청 및 포인트 사용
    ↓
라이더의 배차 수락 경쟁
    ↓
라이더 한 명 배정
    ↓
픽업지 이동
    ↓
물품 수령
    ↓
실시간 배송 위치 추적
    ↓
배송 완료
```

<br>

## 🧭 배송 상태

```text
배차 대기(WAITING) ──취소──▶ 취소됨(CANCELED)
      │
      ▼
라이더 배정(ASSIGNED)
      │
      ▼
픽업지 이동(MOVING_TO_PICKUP)
      │
      ▼
물품 수령(PICKED_UP)
      │
      ▼
배송 중(DELIVERING)
      │
      ▼
배송 완료(COMPLETED)
```

배송 상태는 정해진 순서에 따라서만 변경되며, 상태값을 요청으로 덮어쓰지 않고 **현재 상태 + 수행 행위** 기준으로 검증합니다.

고객의 주문 취소는 배차 전(`WAITING`) 상태에서만 허용됩니다. 

잘못된 상태 전이, 중복 요청, 권한이 없는 사용자의 상태 변경은 도메인 검증을 통해 차단합니다.

<br>

## 🔥 핵심 기술 과제

### 배차 동시성 제어

하나의 배송 요청을 여러 라이더가 동시에 수락하더라도 단 한 명의 라이더만 배차에 성공하도록 처리합니다.

조건부 업데이트 방식을 적용합니다. 배송 완료시 비관적 락을 사용합니다.


### 실시간 위치 추적

라이더의 현재 위치를 주기적으로 서버에 전달하고, 해당 배송을 기다리는 고객에게 실시간으로 전송합니다.

```text
라이더 → 서버: polling
서버 → 고객: SSE
```

### 배송 상태 정합성 및 정산 트랜잭션

배송 상태와 요청 사용자의 권한을 함께 검증합니다.

동시에 여러 상태 변경 요청이 발생해도 상태가 역행하거나 중간 단계를 건너뛰지 않도록 제어합니다.

배송 완료 시 다음 작업을 하나의 트랜잭션으로 처리합니다.

* 배송 완료 처리
* 고객 결제 포인트 확정
* 라이더 포인트 적립

문제 정의와 담당자별 역할 분담은 [핵심 기술 과제 문서(Wiki)](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/기술적-난제-정의와-역할-분담)에서 확인할 수 있습니다.

<br>

## 📚 상세 문서

* [서비스 기획서](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/180)
* Frontend README (작성 예정)
* Backend README (작성 예정)
* [API 명세(초안)](./docs/04-frontend-api-map.md)
* [ERD](./docs/03-erd.md)
* [시스템 아키텍처](./docs/00-project-context.md)
* [핵심 기술 과제(Wiki)](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/기술적-난제-정의와-역할-분담)
* [협업 규칙 및 컨벤션(Wiki)](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki)

<br>

## 🤝 협업 방식

* GitHub Issue를 기준으로 작업을 관리합니다.
* 기능 개발 전에 API와 데이터 모델을 합의합니다.
* 모든 변경 사항은 Pull Request를 통해 병합합니다.
* 최소 한 명 이상의 코드 리뷰를 받은 후 병합합니다.
* 매일 데일리 스크럼을 진행합니다.
* 중요한 기술 결정은 문서로 기록합니다.

### 협업 기록

* 회의록 (작성 예정)
* 데일리 스크럼 (작성 예정)
* 페어 프로그래밍 기록 (작성 예정)
* KPT 회고 (작성 예정)
* [기술 의사결정 기록(ADR, Wiki)](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki)

브랜치, 커밋, Pull Request 규칙은 [협업 규칙 및 컨벤션(Wiki)](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki)에서 확인할 수 있습니다.

<br>

## 👥 팀원

| 이름  | 역할      | GitHub                                      | 담당                              |
| --- | ------- | -------------------------------------------- | --------------------------------- |
| 정상진 | Backend | [jsj3473](https://github.com/jsj3473)       | 위치·실시간 통신(SSE), 인증(세션), AWS 인프라 |
| 백홍빈 | Backend | [githings](https://github.com/githings)     | 고객 서비스, 결제·정산, 웹앱                    |
| 유승종 | Backend | [bigbell999](https://github.com/bigbell999) | 위치·실시간 통신, AWS 인프라                        |
| 박민서 | Backend | [minseo6753](https://github.com/minseo6753) | 배차 동시성, 라이더 서비스, AWS 인프라        |
| 주민석 | Backend | [emes-g](https://github.com/emes-g)         | 배차 동시성, 라이더 서비스, AWS 인프라        |

<br>

## 📝 안내

본 프로젝트는 Softeer Bootcamp 8기 교육 과정에서 제작되었습니다.
