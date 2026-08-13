# CORE 배차 위치 검색

## 한 줄 결론

주변 검색을 **라이더가 아니라 주문(픽업지) 인덱싱**으로 뒤집었다.
콜 목록은 좌표를 선택 파라미터로 받아 bounding box 인덱스를 타고, 좌표가 없으면 반경 필터를 건너뛴다.
운임·거리 필터와 커서 비교는 후보가 수백 건이라는 전제 위에서 **자바 메모리**에서 수행한다.

📐 결정 기록: _디스커션 #338·#380이 정본. 별도 ADR 없음._

<br>

## 💬 디스커션

- [배차 위치 검색 방향 — GEO를 라이더가 아니라 주문에](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/338) — 방향을 뒤집은 결정
- [주문 위치 검색을 MySQL 쿼리로? Redis GEO search로? 실험 보고서](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/380) — 측정으로 고른 근거

<br>

## 🎫 이슈

- [#55 퀵 요청 목록 보기](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/55)
- [#60 퀵 요청 목록 필터 및 정렬](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/60)
- [#83 운행 상태 기반 GEO 배차 후보 반영](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/83)
- [#101 주변 라이더 검색 반경 단계적 확대](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/101) — 미구현 — 방향 전환으로 대체됨
- [#339 RiderGeo Repository를 OrderGeo Repository로 변경](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/339)
- [#342 riders:geo 라이더-측 사용처 제거](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/342)
- [#367 콜 목록 조회에 라이더 좌표 파라미터·인덱스 반영](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/367)
- [#496 콜 목록 화면이 자기 좌표를 검색 요청에 실어 보내지 않음](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/496)
- [#214 콜 목록 화면 API 연동](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/214)
- [#509 콜 목록 페이지네이션 프론트 연동](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/509) — 진행 중
- [#510 콜 목록 요금·배송거리 필터, 정렬 방향 프론트 연동](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/510) — 진행 중

<br>

## 📊 테스트

- [#362 DB 쿼리 + Bounding Box vs Redis GEO search 성능 테스트](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/362) — 디스커션 #380의 실측

<br>

## 📚 스터디

_아직 없음._

<br>

---

_이 페이지는 링크 허브입니다. 내용은 링크된 원본이 정본이고, 여기에는 결론 한 줄만 둡니다._
_[README 사용자 흐름도](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/blob/dev/README.md#%EF%B8%8F-사용자-흐름도)에서 이 페이지로 들어옵니다._
