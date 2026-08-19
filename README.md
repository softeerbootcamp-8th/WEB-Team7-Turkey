# Turkey

> 카카오 T 퀵의 사용자 흐름을 참고한 실시간 퀵배송 서비스

**Softeer Bootcamp 8기 Team 7 종합 프로젝트**

<br>

## 🚚 서비스 소개

Turkey는 물품을 빠르게 배송하려는 고객과 주변 라이더를 실시간으로 연결하는 웹 기반 퀵서비스 플랫폼입니다.

 **고객 배송요청 → 배차 → 실시간 위치 추적 → 배송 완료**로 이어지는 핵심 흐름을 제대로 구현하고, 그 과정에서 마주치는 기술적 도전에 집중하는 것을 목표로 삼았습니다.

- 🔗 [배포 링크](https://dw1nqa61d1no6.cloudfront.net/)

- 📱 [모바일 앱 설치 링크](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/actions/workflows/build-mobile-apk.yml) — 라이더의 실시간 위치 전송을 위해 앱 설치가 필요합니다.(최신 액션에서 아티팩트 다운로드)

- 📑 [API 문서 (Swagger)](https://dw1nqa61d1no6.cloudfront.net/swagger-ui/index.html#/)

<br>

## 🎬 시연 영상

<div align="center">
  <video src="https://github.com/user-attachments/assets/59b183db-5e93-4dbc-9864-fdf12caac17d" controls width="600"></video>
</div>

<br>

## 🗺️ 사용자 흐름도

| 표기 | 뜻 |
| --- | --- |
| 🟦 파란 노드 | 고객 · 웹 브라우저 |
| 🟨 노란 노드 | 라이더 · 안드로이드 앱 |
| 🔴 빨간 화살표 | 두 액터가 서버를 통해 서로를 움직이는 지점 |
| 🟠 주황 화살표 | 고객이 흐름에서 빠져나가는 분기(취소) |
| 굵은 테두리 | **클릭하면 그 주제의 CORE 위키로 이동** |

```mermaid
flowchart TB
    C1["🧑 ① 회원가입"] --> C2["🧑 ② 로그인"] --> C3["🧑 ③ 포인트 충전"] --> C4["🧑 ④ 배송요청 생성"] --> C5["🧑 ⑤ 실시간 위치 추적"]
    C4 -->|"배차 전(WAITING)에만 취소"| C8["🧑 ⑧ 취소 · 포인트 환급"]
    C5 --> C6["🧑 ⑥ 포인트 내역"]
    C5 --> C7["🧑 ⑦ 배송 내역"]

    R1["🛵 ① 회원가입"] --> R2["🛵 ② 로그인"] --> R3["🛵 ③ 콜 목록"] --> R4["🛵 ④ 콜 받기"] --> R5["🛵 ⑤ 진행 배송"] --> R6["🛵 ⑥ 완료 인증"]
    R6 --> R7["🛵 ⑦ 포인트 · 정산"]
    R6 --> R8["🛵 ⑧ 운행 기록"]

    C4 -->|"WAITING 주문 노출"| R3
    R4 -->|"배차 확정"| C5
    R5 -->|"위치 → SSE"| C5
    R6 -->|"COMPLETED · 정산"| C7

    click C2 href "https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/CORE-쿠키-세션-인증방식" "CORE 쿠키-세션 인증방식"
    click R2 href "https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/CORE-쿠키-세션-인증방식" "CORE 쿠키-세션 인증방식"
    click C3 href "https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/CORE-결제-정합성" "CORE 결제 정합성"
    click C8 href "https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/CORE-주문-취소" "CORE 주문 취소"
    click C5 href "https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/CORE-실시간-위치-추적" "CORE 실시간 위치 추적"
    click R3 href "https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/CORE-콜-목록-조회" "CORE 콜 목록 조회"
    click R4 href "https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/CORE-배차-수락" "CORE 배차 수락"
    click R5 href "https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/CORE-배송-상태-전이" "CORE 배송 상태 전이"

    classDef cus fill:#dbeafe,stroke:#3b82f6,color:#1e3a5f
    classDef rid fill:#fef3c7,stroke:#f59e0b,color:#5c4813
    classDef core stroke-width:3px
    class C1,C2,C3,C4,C5,C6,C7,C8 cus
    class R1,R2,R3,R4,R5,R6,R7,R8 rid
    class C2,C3,C5,C8,R2,R3,R4,R5 core
    linkStyle 14,15,16,17 stroke:#dc2626,stroke-width:3px
    linkStyle 4 stroke:#ea580c,stroke-width:3px
```
<br>

## 👥 팀원 소개 및 맡은 일

| 이름 | 담당 도메인 |
| --- | --- |
| [정상진](https://github.com/jsj3473) | 위치·실시간 통신, 인증 |
| [백홍빈](https://github.com/githings) | 고객 서비스, 결제·정산, 웹앱 |
| [유승종](https://github.com/bigbell999) | 위치·실시간 통신, AWS 인프라 |
| [박민서](https://github.com/minseo6753) | 배차 동시성, 라이더 서비스, AWS 인프라 |
| [주민석](https://github.com/emes-g) | 배차 동시성, 라이더 서비스, AWS 인프라 |

<br>


## 🏗️ 인프라 아키텍처

![architecture_img](./imgs/다이어그램.drawio.png)

<br>

## 🛠️ 기술 스택

| 분류 | 기술 스택 |
| --- | --- |
| 프론트엔드 | ![React](https://img.shields.io/badge/React-61DAFB?style=for-the-badge&logo=react&logoColor=black) ![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white) ![Vite](https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white) ![TanStack Query](https://img.shields.io/badge/TanStack_Query-FF4154?style=for-the-badge&logo=reactquery&logoColor=white) ![TanStack Router](https://img.shields.io/badge/TanStack_Router-0EA5E9?style=for-the-badge&logo=tanstack&logoColor=white) ![Tailwind CSS](https://img.shields.io/badge/Tailwind_CSS-06B6D4?style=for-the-badge&logo=tailwindcss&logoColor=white)   ![Orval](https://img.shields.io/badge/Orval-FF6B6B?style=for-the-badge) |
| 백엔드 | ![Java](https://img.shields.io/badge/Java%2021-007396?style=for-the-badge&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white) ![JPA](https://img.shields.io/badge/JPA-59666C?style=for-the-badge&logo=hibernate&logoColor=white) ![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=for-the-badge&logo=flyway&logoColor=white) ![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white) ![Redis](https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white) ![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=for-the-badge&logo=junit5&logoColor=white) |
| 인프라 | ![AWS EC2](https://img.shields.io/badge/AWS%20EC2-FF9900?style=for-the-badge&logo=amazonec2&logoColor=white) ![AWS S3](https://img.shields.io/badge/AWS%20S3-569A31?style=for-the-badge&logo=amazons3&logoColor=white) ![AWS CloudFront](https://img.shields.io/badge/CloudFront-8C4FFF?style=for-the-badge&logo=amazonaws&logoColor=white) ![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white) ![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white) |
| 협업 | ![Figma](https://img.shields.io/badge/Figma-F24E1E?style=for-the-badge&logo=figma&logoColor=white) ![Slack](https://img.shields.io/badge/Slack-4A154B?style=for-the-badge&logo=slack&logoColor=white) ![GitHub](https://img.shields.io/badge/GitHub-181717?style=for-the-badge&logo=github&logoColor=white) |

<br>
