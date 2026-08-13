# CORE 배송 상태 전이

## 한 줄 결론

**라이더 상태와 배송 상태를 분리**한다. 상태 변경은 요청 값으로 덮어쓰지 않고 **현재 상태 + 수행 행위**로 검증하며, 허용되지 않은 전이는 서버가 거부한다.
`WAITING → ASSIGNED → MOVING_TO_PICKUP → PICKED_UP → DELIVERING → COMPLETED`, 그리고 `CANCELED`. 배차 확정과 배송 완료는 각각 하나의 트랜잭션으로 묶인 원자적 전이다.

## 📐 결정 기록 (ADR)

- [ADR-003 라이더 상태와 배송 상태 분리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90003-%EB%9D%BC%EC%9D%B4%EB%8D%94-%EC%83%81%ED%83%9C%EC%99%80-%EB%B0%B0%EC%86%A1-%EC%83%81%ED%83%9C-%EB%B6%84%EB%A6%AC) — 두 상태를 분리한 결정
- [ADR-007 서버‐클라이언트 배송 상태 정합성 보장 방법](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90007-%EC%84%9C%EB%B2%84%E2%80%90%ED%81%B4%EB%9D%BC%EC%9D%B4%EC%96%B8%ED%8A%B8-%EB%B0%B0%EC%86%A1-%EC%83%81%ED%83%9C-%EC%A0%95%ED%95%A9%EC%84%B1-%EB%B3%B4%EC%9E%A5-%EB%B0%A9%EB%B2%95-%EA%B2%B0%EC%A0%95.) — 서버와 클라이언트 상태를 맞추는 방법

<br>

## 💬 디스커션

- [completed, canceled 상태 정합성 문제에 ack 프로토콜 도입 검토](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/432)
- [주문 취소 관련](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/402) — CANCELED 전이의 경계

<br>

## 🎫 이슈

- [#54 운행 상태 관리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/54) — 라이더 상태 축
- [#58 픽업지 이동 시작](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/58)
- [#59 물품 픽업 완료](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/59)
- [#65 배송 시작](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/65)
- [#61 배송 완료 인증 등록](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/61) — 인증과 완료 전이를 한 트랜잭션으로
- [#62 배송 완료 처리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/62)
- [#88 배송 상태 전이 이력 기록](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/88)
- [#89 배송 상태 전이 중복 요청 처리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/89)
- [#398 배송 상태 전이 SSE 실시간 전달 — 백엔드](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/398)
- [#399 배송 상태 전이 SSE 실시간 전달 — 프론트엔드](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/399)
- [#401 WAITING 구간 SSE 연결 허용](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/401)
- [#428 주문 상태 전이 발행이 커밋 이전에 발생할 수 있는 버그](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/428)
- [#387 배송 상태 타임라인 파생 중복 3곳 통합](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/387)
- [#86 진행 중 배송 조회 및 화면 복구](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/86) — 새로고침·재로그인 복구
- [#216 진행 배송 5단계 상태 연동](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/216)

<br>

## 📊 테스트

- [docs/02-domain-policy.md](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/blob/dev/docs/02-domain-policy.md) — 상태 전이 규칙 정본

<br>

## 📚 스터디

- [기술적 난제 정의와 역할 분담](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/%EA%B8%B0%EC%88%A0%EC%A0%81-%EB%82%9C%EC%A0%9C-%EC%A0%95%EC%9D%98%EC%99%80-%EC%97%AD%ED%95%A0-%EB%B6%84%EB%8B%B4.) — 이 주제가 왜 난제인지

<br>

---

_이 페이지는 링크 허브입니다. 내용은 링크된 원본이 정본이고, 여기에는 결론 한 줄만 둡니다._
_[README 사용자 흐름도](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/blob/dev/README.md#%EF%B8%8F-사용자-흐름도)의 노드에서 이 페이지로 들어옵니다._
