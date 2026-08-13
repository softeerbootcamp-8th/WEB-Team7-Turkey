# Turkey

> 카카오 T 퀵의 사용자 흐름을 참고한 실시간 퀵배송 서비스

**Softeer Bootcamp 8기 Team 7 종합 프로젝트**

<br>

## 🚚 서비스 소개

Turkey는 물품을 빠르게 배송하려는 고객과 주변 라이더를 실시간으로 연결하는 웹 기반 퀵서비스 플랫폼입니다.

 **고객 배송요청 → 배차 → 실시간 위치 추적 → 배송 완료**로 이어지는 핵심 흐름을 제대로 구현하고, 그 과정에서 마주치는 기술적 도전에 집중하는 것을 목표로 삼았습니다.

- 🔗 [배포 링크](https://dw1nqa61d1no6.cloudfront.net/)

- 📑 [API 문서 (Swagger)](https://dw1nqa61d1no6.cloudfront.net/swagger-ui/index.html#/)

<br>

## 🎬 시연 영상

> 시연 영상 추가 예정

<!-- 예: [![데모 영상](썸네일_URL)](유튜브_URL) -->

<br>

## 📌 주요 기능

**레디스 사용** — 세션, 휴대전화 인증번호, 라이더 최신 위치, SSE 이벤트 팬아웃까지 하나의 Redis 인스턴스를 자료구조별로 나눠 쓴다.
[ADR-002](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR‐002-Redis-사용)

**라이더 상태와 배송 상태 분리** — 라이더의 배차 가능 여부와 배송 주문의 진행 단계를 서로 다른 축으로 관리해 상태 전이 책임을 분리한다.
[ADR-003](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR‐003-라이더-상태와-배송-상태-분리)

**배차 동시성 처리** — 조건부 UPDATE(CAS)로 하나의 배송 요청에 라이더 한 명만 배정되도록 보장한다.
[ADR-006](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR‐006-배차-동시성-처리)

<br>

## 🔥 기술적 도전 / 트러블슈팅

**SSE vs Polling** — 라이더 위치를 고객에게 전달하는 방식을 SSE와 Polling 두 후보로 놓고 부하테스트로 비교해 SSE로 확정했다.
[ADR-010](<https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR‐010:-위치-전달-방식(SSE)-부하테스트-검증>)

**GC 방식 선택** — SerialGC와 G1GC를 N=500 부하테스트로 비교해 GC 전략을 검증했다.
[ADR-011](<https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR‐011:-GC-방식-비교-부하테스트-검증(SerialGC-vs-G1GC,-N=500)>)

<br>

## 👥 팀원 소개 및 맡은 일

| 이름 | 담당 도메인 |
| --- | --- |
| [정상진](https://github.com/jsj3473) | 위치·실시간 통신(SSE), 인증(세션), AWS 인프라 |
| [백홍빈](https://github.com/githings) | 고객 서비스, 결제·정산, 웹앱 |
| [유승종](https://github.com/bigbell999) | 위치·실시간 통신, AWS 인프라 |
| [박민서](https://github.com/minseo6753) | 배차 동시성, 라이더 서비스, AWS 인프라 |
| [주민석](https://github.com/emes-g) | 배차 동시성, 라이더 서비스, AWS 인프라 |

<br>

## 🤝 협업 기록

- 📋 [GitHub Project](https://github.com/orgs/softeerbootcamp-8th/projects/7)
- 💬 [GitHub Discussions](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions)

> 회의록·데일리 스크럼·KPT 회고 등 상세 기록 정리 예정

<br>

## 🏗️ 인프라 아키텍처

![architecture_img](./imgs/architecture.png)

<br>

## 🛠️ 기술 스택

| 분류 | 기술 스택 |
| --- | --- |
| 프론트엔드 | ![React](https://img.shields.io/badge/React-61DAFB?style=for-the-badge&logo=react&logoColor=black) ![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white) ![Vite](https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white) ![TanStack Query](https://img.shields.io/badge/TanStack_Query-FF4154?style=for-the-badge&logo=reactquery&logoColor=white) ![TanStack Router](https://img.shields.io/badge/TanStack_Router-0EA5E9?style=for-the-badge&logo=tanstack&logoColor=white) ![Tailwind CSS](https://img.shields.io/badge/Tailwind_CSS-06B6D4?style=for-the-badge&logo=tailwindcss&logoColor=white) ![shadcn/ui](https://img.shields.io/badge/shadcn%2Fui-000000?style=for-the-badge&logo=shadcnui&logoColor=white) ![axios](https://img.shields.io/badge/axios-5A29E4?style=for-the-badge&logo=axios&logoColor=white) ![Orval](https://img.shields.io/badge/Orval-FF6B6B?style=for-the-badge) |
| 백엔드 | ![Java](https://img.shields.io/badge/Java%2021-007396?style=for-the-badge&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white) ![JPA](https://img.shields.io/badge/JPA-59666C?style=for-the-badge&logo=hibernate&logoColor=white) ![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=for-the-badge&logo=flyway&logoColor=white) ![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white) ![Redis](https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white) ![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=for-the-badge&logo=junit5&logoColor=white) |
| 인프라 | ![AWS EC2](https://img.shields.io/badge/AWS%20EC2-FF9900?style=for-the-badge&logo=amazonec2&logoColor=white) ![AWS S3](https://img.shields.io/badge/AWS%20S3-569A31?style=for-the-badge&logo=amazons3&logoColor=white) ![AWS CloudFront](https://img.shields.io/badge/CloudFront-8C4FFF?style=for-the-badge&logo=amazonaws&logoColor=white) ![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white) ![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white) |
| 협업 | ![Figma](https://img.shields.io/badge/Figma-F24E1E?style=for-the-badge&logo=figma&logoColor=white) ![Slack](https://img.shields.io/badge/Slack-4A154B?style=for-the-badge&logo=slack&logoColor=white) ![GitHub](https://img.shields.io/badge/GitHub-181717?style=for-the-badge&logo=github&logoColor=white) |

<br>

## 📝 안내

본 프로젝트는 Softeer Bootcamp 8기 교육 과정에서 제작되었습니다.
