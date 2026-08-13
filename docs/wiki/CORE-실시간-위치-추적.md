# CORE 실시간 위치 추적

## 한 줄 결론

고객에게 라이더 위치를 **SSE**로 전달한다(Polling 아님, 부하테스트로 검증).
서버는 위치를 검증·필터링하지 않고 받는 즉시 중계한다. 다중 인스턴스를 위해 **Redis Pub/Sub 팬아웃**을 거치며,
위치 갱신 경로에서 `SseRelay`를 직접 부르면 다른 인스턴스에 연결된 고객이 받지 못한다.

📐 결정 기록: [ADR-010 위치 전달 방식(SSE) 부하테스트 검증](<https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR‐010:-위치-전달-방식(SSE)-부하테스트-검증>)

<br>

## 💬 디스커션

- [라이더 위치 정보 전달 방식 부하 테스트 계획](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/270) — SSE vs Polling 실험 설계
- [location 패키지 코드 단순화](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/286) — 서버측 위치 필터를 걷어낸 결정
- [completed, canceled 상태 정합성 문제에 ack 프로토콜 도입 검토](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/432)
- [부하테스트 자동화 skill 결과물](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/481)

<br>

## 🎫 이슈

- [#183 서버→클라이언트(고객) 연결 방식 실험](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/183)
- [#77 실시간 위치 구독 시작](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/77)
- [#78 라이더 위치 변경 이벤트 전송](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/78)
- [#79 SSE 재연결 및 최신 상태 복구](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/79)
- [#80 실시간 위치 구독 종료](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/80)
- [#81 라이더 현재 위치 갱신](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/81) — 전송 주기·임계값 계약
- [#82 위치 중복 및 이상 이동 필터링](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/82)
- [#289 로컬 SSE 연결 레지스트리 + 고객 구독 API (1/3)](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/289)
- [#290 라이더 위치 → 구독 고객에게 직접 전달 (2/3)](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/290)
- [#291 위치 추적 세션 인증·인가 결합 (3/3)](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/291)
- [#297 위치 추적 단순화](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/297)
- [#311 고객 위치 조회 Polling API](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/311) — 프론트 백업 경로
- [#317 최신 위치 Redis 저장 + SSE Pub/Sub 팬아웃 재도입](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/317) — 지금 구조의 뼈대
- [#359 고객 추적 SSE 스트림 heartbeat 도입](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/359)
- [#391 라이더 위치 전송 주기 단축](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/391)
- [#449 위치 데이터에 배송 상태 포함](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/449) — 중간 전이 유실 자연 복구
- [#450 배송 완료 시 SSE 연결 능동 종료](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/450) — 종료 전용 채널
- [#295 웹뷰 기반 앱의 백그라운드 위치 전송 검증](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/295)
- [#196 공용 훅 구현 — SSE 구독 · 위치 전송](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/196)
- [#209 실시간 배송 추적 화면 및 지도 연동](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/209)

<br>

## 📊 테스트

- [#259 라이더 위치 전달 3가지 방식 부하테스트 정리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/259)
- [#459 SSE·Polling arm k6 스크립트 작성 및 실행](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/459)
- [#373 실험용 데이터 seeding 스크립트](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/373)
- [#374 OOM 원인 분석을 위한 JVM 힙 덤프 옵션 추가](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/374)
- [ADR-011 GC 방식 비교 부하테스트](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR‐011:-GC-방식-비교-부하테스트-검증(SerialGC-vs-G1GC,-N=500)) — 이 부하 위에서 잰 것

<br>

## 📚 스터디

- [#329 부하테스트 실험 계획·변인·게이트 학습](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/329)

<br>

---

_이 페이지는 링크 허브입니다. 내용은 링크된 원본이 정본이고, 여기에는 결론 한 줄만 둡니다._
_[README 사용자 흐름도](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/blob/dev/README.md#%EF%B8%8F-사용자-흐름도)에서 이 페이지로 들어옵니다._
