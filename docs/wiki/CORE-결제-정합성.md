# CORE 결제 정합성

## 한 줄 결론

MVP 결제는 **포인트 기반**이다(실 PG 미연동). 주문 생성과 포인트 차감은 **하나의 트랜잭션**이며
`payForOrder`는 `Propagation.MANDATORY` — 단독 호출로 "주문 없이 포인트만 빠지는" 경로를 막는다.
취소는 곧 환급이고, 잠금 순서는 `point_charge` → `point_wallet`로 고정한다.

📐 결정 기록: _CORE 페이지가 정본. 별도 ADR 없음._

<br>

## 💬 디스커션

- [주문 취소 관련](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/402) — 취소=환급으로 합친 근거

<br>

## 🎫 이슈

- [#31 포인트 잔액 조회](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/31)
- [#32 포인트 충전 준비](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/32)
- [#33 포인트 충전 모의 승인](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/33) — 멱등성 · PG 파사드 구조
- [#34 포인트 충전 모의 실패 처리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/34) — 취소가 승인을 이기는 경쟁 창
- [#35 포인트 이용 내역 조회](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/35)
- [#36 포인트 결제 가능 여부 확인](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/36)
- [#40 배송요금 포인트 차감](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/40) — 포인트 부족은 402
- [#38 취소 주문 포인트 환급](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/38)
- [#47 배송 주문 취소](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/47) — 배차 전에만 · 시간 제약 없는 범용 취소
- [#91 포인트 거래 원장 기록](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/91)
- [#92 포인트 잔액 조건부 갱신](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/92)
- [#98 포인트 거래 상세 조회](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/98)
- [#405 동시 취소 요청의 멱등성 보장](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/405)
- [#97 포인트 충전 요청 상태 조회](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/97) — 진행 중
- [#211 포인트 충전 화면 API 연동](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/211)
- [#206 배송 상세 조회 및 취소 화면 API 연동](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/206)

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
