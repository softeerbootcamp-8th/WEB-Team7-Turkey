# CORE 배송 상태 전이

## 한 줄 결론

**라이더 상태와 배송 상태를 분리**한다. 상태 변경은 요청 값으로 덮어쓰지 않고 **현재 상태 + 수행 행위**로 검증하며,
허용되지 않은 전이는 서버가 거부한다. 배차 확정·배송 완료는 각각 하나의 트랜잭션으로 묶인 원자적 전이다.
`WAITING → ASSIGNED → MOVING_TO_PICKUP → PICKED_UP → DELIVERING → COMPLETED`, 그리고 `CANCELED`.

📐 결정 기록: [ADR-003 라이더 상태와 배송 상태 분리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR‐003-라이더-상태와-배송-상태-분리)

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

_아직 없음._

<br>

## 📚 스터디

_아직 없음._

<br>

---

_이 페이지는 링크 허브입니다. 내용은 링크된 원본이 정본이고, 여기에는 결론 한 줄만 둡니다._
_[README 사용자 흐름도](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/blob/dev/README.md#%EF%B8%8F-사용자-흐름도)에서 이 페이지로 들어옵니다._
