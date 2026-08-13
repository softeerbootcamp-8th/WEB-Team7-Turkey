# CORE 콜받기

## 한 줄 결론

**콜 받기 = 수락 = 배차**는 같은 행위다. 동시성은 **조건부 UPDATE(CAS)**로 처리하고 주문→라이더 순서를 고정한다.
배송요청당 최대 1명, 라이더당 동시 진행 배송 최대 1건, 경쟁에서 진 수락은 부분 성공 없이 명확히 실패한다.
배차 초과 자동 취소는 폴링 스캐너(1분)와 지연 만료의 하이브리드다.

📐 결정 기록: [ADR-006 배차 동시성 처리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR‐006-배차-동시성-처리)

<br>

## 💬 디스커션

- [배차 동시성 처리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/84) — 대안 비교가 여기 있다
- [배차 수락 시 데드락 발생: 원인과 해결 과정](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/468) — 그 뒤에 터진 문제
- [배차 수락 데드락 해소 테스트 해설](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/467)

<br>

## 🎫 이슈

- [#56 배달 확정하기](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/56) — 배차 수락 API
- [#41 주변 라이더 탐색 및 콜 발송](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/41)
- [#42 배차 대기 시간 초과 자동 취소](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/42)
- [#57 퀵 요청 상세사항 보기](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/57)
- [#85 신규 배차 요청 반영](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/85)
- [#446 배차 수락 경로 커넥션 풀 교착](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/446) — REQUIRES_NEW를 tx 안에서 호출
- [#463 createDelivery→expireIfStale 커넥션 풀 교착](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/463) — #446 형제 버그
- [#444 배차 대기 자동취소 화면 정합 — 클라이언트 타이머 + SSE 보완](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/444)
- [#215 콜 상세 및 수락 화면 API 연동](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/215)
- [#418 라이더 콜 넘기기(skip) 동작 구현](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/418) — 진행 중

<br>

## 📊 테스트

- [배차 동시성 테스트 결과](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/491)
- [#493 main 배포 전 백엔드 통합 테스트 추가](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/493)

<br>

## 📚 스터디

_아직 없음._

<br>

---

_이 페이지는 링크 허브입니다. 내용은 링크된 원본이 정본이고, 여기에는 결론 한 줄만 둡니다._
_[README 사용자 흐름도](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/blob/dev/README.md#%EF%B8%8F-사용자-흐름도)에서 이 페이지로 들어옵니다._
