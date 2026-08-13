# CORE 쿠키-세션 인증방식

## 한 줄 결론

**Spring Security 없이** 쿠키 기반 서버 세션을 직접 구현한다. 세션은 Redis에 저장하고 쿠키에는 세션 식별자만 담는다.
TTL 2시간, 인증을 통과한 요청마다 슬라이딩 갱신. 보호할 API는 인터셉터 `addPathPatterns`에 **직접 등록**해야 걸린다 — 등록을 빠뜨리면 인증 없이 열린 채 배포된다.

## 📐 결정 기록 (ADR)

- [ADR-001 세션 기반 인증](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90001-%EC%84%B8%EC%85%98-%EA%B8%B0%EB%B0%98-%EC%9D%B8%EC%A6%9D) — 세션을 고른 결정
- [ADR-002 Redis 사용](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90002-Redis-%EC%82%AC%EC%9A%A9) — 세션 저장소로 Redis
- [ADR-008 CloudFront 통합 배포와 쿠키 SameSite 정책](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90008-%E2%80%94-CloudFront-%EA%B8%B0%EB%B0%98-%ED%94%84%EB%A1%A0%ED%8A%B8%E2%80%90API-%ED%86%B5%ED%95%A9-%EB%B0%B0%ED%8F%AC%EC%99%80-%EC%BF%A0%ED%82%A4-SameSite-%EC%A0%95%EC%B1%85) — 단일 오리진 전제 위의 SameSite=Lax

<br>

## 💬 디스커션

- [세션 기반 인증 vs 토큰 기반 인증](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/11) — 토큰 대신 세션을 고른 이유
- [디스커션 #243 — 라이더 세션 만료 처리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/243) — 이슈 #52 논의

<br>

## 🎫 이슈

- [#26 고객 로그인](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/26)
- [#27 고객 로그인 상태 확인](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/27) — 인터셉터 경로 등록 규칙이 여기서 정해졌다
- [#28 고객 로그아웃](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/28)
- [#29 세션 만료 처리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/29)
- [#49 라이더 로그인](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/49)
- [#50 라이더 로그인 상태 확인](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/50)
- [#51 라이더 로그아웃](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/51)
- [#52 라이더 세션 만료 처리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/52)
- [#195 라우트 인증 가드 및 401 공용 처리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/195) — 프론트 가드가 1차, 서버가 2차
- [#288 역할별 세션 병렬 조회 시 유효한 세션 쿠키가 삭제되는 문제](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/288) — 역할 무관 세션 API 부재로 생긴 버그
- [#439 라이더 BUSY 세션 만료 시 추적 멈춤·배송 진행 불가](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/439) — 슬라이딩 갱신을 도입한 계기
- [#511 세션 Redis 저장 구조를 Hash → String(JSON)으로 변경](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/511) — HSET+EXPIRE 원자성 창 제거 · 진행 중

<br>

## 📊 테스트

_아직 없음._

<br>

## 📚 스터디

- [#258 쿠키-세션 인증 흐름 학습](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/258)

<br>

---

_이 페이지는 링크 허브입니다. 내용은 링크된 원본이 정본이고, 여기에는 결론 한 줄만 둡니다._
_[README 사용자 흐름도](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/blob/dev/README.md#%EF%B8%8F-사용자-흐름도)의 노드에서 이 페이지로 들어옵니다._
