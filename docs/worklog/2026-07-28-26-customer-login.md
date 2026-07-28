# 고객 로그인 작업 기록

- 이슈: [#26](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/26)
- 브랜치: `feature/26-customer-login`
- 범위: backend
- 작성일: 2026-07-28

## 무엇을 만들었나

로그인 ID·비밀번호로 고객을 인증하고, 서버 세션(Redis)을 생성해 HttpOnly 쿠키로 발급하는
`POST /api/customer/login`을 만들었다. 이 저장소에서 세션·쿠키를 다루는 첫 기능이라
`common/auth` 패키지(그동안 빈 자리로 예약만 돼 있던)에 `SessionStore`/`SessionCookie`를
새로 추가했다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| POST | `/api/customer/login` | 고객 로그인, 세션 쿠키 발급 | 401(아이디 없음·라이더 계정·비밀번호 불일치·비활성 계정 — 전부 동일 메시지), 400(필수값 누락) |

### 화면

해당 없음(백엔드 API만). 프론트 로그인 화면 연동은 이번 범위 밖.

### 스키마 변경

해당 없음. 세션은 MySQL이 아니라 Redis에만 저장한다(`docs/03-erd.md` 5절에 이미 확정된
`session:{sessionId}` 포맷을 그대로 구현).

## 사람이 고른 선택

### 1. 세션 TTL

- **물었던 것**: ERD엔 세션 값에 `expiresAt`이 있다는 것만 정해져 있고, 실제 TTL 수치는 문서·코드
  어디에도 없었다.
- **선택지**:
  - (A) 2시간 고정 — 배송 요청처럼 한 번 쓰고 끝나는 세션에 적당한 길이
  - (B) 30분 고정 — 보안 우선, 대신 재로그인이 잦음
  - (C) 24시간 고정 — 편의 우선
- **고른 것**: (A)
- **근거**: "2시간 고정 (추천)"을 그대로 선택.
- **영향**: `CustomerLoginService.SESSION_TTL = Duration.ofHours(2)`로 하드코딩(다른 TTL 상수들
  — `PhoneVerificationService.CODE_TTL` 등 — 과 같은 방식). 슬라이딩 갱신(활동 중 TTL 연장) 여부는
  세션 검증 기능(#27)에서 결정한다 — 로그인 시점엔 최초 TTL만 정하면 된다.

### 2. 쿠키 SameSite/Secure 속성과 프론트·API 배포 구조

- **물었던 것**: CLAUDE.md에 "쿠키 보안 속성(Secure/SameSite/...)"과 "프론트 Origin과 API Origin
  분리 시 CORS·쿠키 설정"이 둘 다 미결로 남아 있었다. 프론트(S3+CloudFront)와 API(EC2)가 커스텀
  도메인 없이 배포되면 등록 도메인이 서로 달라(`*.cloudfront.net` vs EC2 기본 주소) 브라우저가
  크로스사이트로 취급해 `SameSite=Lax`로는 로그인 쿠키가 axios 요청에 실리지 않는다. 이 경우
  `SameSite=None`+`Secure`가 필요한데, `Secure`는 HTTPS 전제라 EC2도 별도로 HTTPS를 갖춰야 한다.
- **선택지**:
  - (A) 프론트와 API를 CloudFront 배포 하나로 묶는다 — 기본 동작(default behavior)은 S3(정적 파일),
    `/api/*` 같은 경로 behavior를 추가해 EC2를 origin으로 연결. 브라우저 기준 완전히 같은 origin이
    되므로 `SameSite=Lax` + `Secure`(CloudFront가 기본 도메인에 무료로 HTTPS 종단)로 충분하고,
    EC2 자체는 HTTPS를 안 갖춰도 된다(CloudFront→EC2 구간은 origin protocol policy를 HTTP로 설정).
    비용 추가 없음.
  - (B) API를 위한 CloudFront 배포를 따로 만들거나 EC2를 그대로 노출 — 크로스사이트가 되므로
    `SameSite=None`+`Secure` 필요 → EC2도 자체 HTTPS(인증서) 필요, 커스텀 도메인 없인 사실상
    무료로 하기 어려움.
  - (C) 커스텀 도메인을 사서 프론트/API를 서브도메인으로 분리(`app.xxx.com`/`api.xxx.com`) —
    같은 site라 `SameSite=Lax`로 충분하지만 도메인 구매·Route53·ACM 인증서 발급 등 인프라 작업이
    늘어남.
- **고른 것**: (A)
- **근거**: "우리조는 돈은 웬만하면 안쓰는기조임." — 팀이 비용 지출을 최소화하려 함. (A)는 도메인
  구매도, EC2 HTTPS 인증서도 필요 없이 CloudFront의 무료 기본 도메인 HTTPS만으로 문제가 해소된다.
- **영향**: 쿠키는 `SameSite=Lax` + `Secure`(프로파일별: local=false, 그 외 기본값=true)로
  구현했다(`SessionCookie.java`). **이 결정은 CloudFront 배포에 `/api/*` 경로 behavior를 추가해
  프론트·API를 같은 origin으로 묶는 인프라 작업이 실제로 이뤄진다는 전제에 의존한다** — 아직 코드가
  아니라 AWS 콘솔 설정이라 이번 세션에서 직접 하지 못했다(아래 「새로 생긴 미결 사항」). 만약 나중에
  API가 별도 CloudFront 배포나 EC2 직접 노출로 배포되면(크로스사이트) `SameSite=None`으로 다시
  바꿔야 한다.
- **탈락한 대안 참고**: EC2 보안그룹은 이미 8080/80/443이 `0.0.0.0/0`으로 열려 있어(스크린샷으로
  확인) CloudFront→EC2 origin 트래픽을 위한 별도 보안그룹 변경은 필요 없었다. 나중에 "CloudFront
  우회 직접 접근"을 막고 싶으면 소스를 AWS 관리형 프리픽스 리스트(`com.amazonaws.global.cloudfront.origin-facing`)로
  좁히는 걸 고려할 수 있다(선택 사항, 이번엔 안 함).

## 스스로 판단한 것

- **실패 사유를 구분하지 않고 전부 401 + 동일 메시지**: 이슈 예외 처리·비고에 "동일한 로그인 실패
  응답을 반환한다", "계정 존재 여부를 구체적으로 노출하지 않는다"고 명시돼 있어, 아이디 없음/역할
  불일치/비밀번호 틀림/비활성 계정을 전부 `BusinessException(UNAUTHORIZED, "아이디 또는
  비밀번호가 일치하지 않습니다.")`로 통일했다. 처리 흐름의 순서(②조회 ③역할 ④비밀번호 ⑤상태)는
  그대로 따랐지만 응답은 구분되지 않는다.
- **`SessionStore`가 `MemberRole` 대신 `String role`을 받음**: `common/auth`는 고객·라이더 공용
  인증 인프라라 특정 도메인 enum(`MemberRole`)에 의존하지 않게 했다. 서비스 계층에서
  `member.getRole().name()`으로 변환해 넘긴다.
- **세션 값 저장에 Redis Hash 사용**: ERD가 세션 값을 `memberId, role, expiresAt` 세 필드로
  정의해서, 문자열 하나에 구분자로 우겨넣기보다 Redis Hash(`HSET`)로 필드별로 저장하는 게 더
  자연스럽다고 판단했다. `HSET` 이후 `EXPIRE`를 별도로 호출하는데(원자적 단일 명령 아님), 세션
  생성 경로라 그 사이 극히 짧은 창은 감내 가능하다고 봤다.
- **세션 ID 생성 방식**: `PhoneVerificationService.generateToken()`과 동일하게 `SecureRandom` +
  24바이트 + Base64 URL-safe 인코딩을 그대로 재사용했다(이미 검증된 패턴).
- **쿠키 이름·발급 규칙을 `common/auth/SessionCookie`로 공용화**: 이름(`SESSION_ID`)과
  `HttpOnly`/`SameSite`/`Path` 속성을 한 곳에 모아 뒀다 — #27(세션 확인)이 쿠키를 읽을 때, #28(로그아웃)이
  같은 속성으로 쿠키를 만료시킬 때 재사용해야 값이 어긋나지 않는다.
- **`Secure` 값은 `@Value("${session.cookie.secure:true}")`로 기본값 내장**: `application.yml`에
  `session.cookie.secure: true`, `application-local.yml`에 `false`로 오버라이드했다. 테스트
  리소스(`src/test/resources`)는 `application.yml`을 완전히 대체하는 별도 파일이라 이 프로퍼티가
  없는데, `@Value`의 인라인 기본값(`:true`)이 있어 테스트 yml을 건드리지 않고도 정상 동작한다.

## 일부러 하지 않은 것

- **세션 검증(매 요청마다 쿠키 확인) 필터**: `common/auth`에 인증 필터를 만드는 건 #27(로그인 상태
  확인)의 범위다. 이번엔 `SessionStore`에 "생성"만 만들었다 — `VerificationCodeStore`가
  이슈마다 메서드를 증분한 것과 같은 방식.
- **슬라이딩 세션 갱신**: TTL을 활동에 따라 연장할지는 #27에서 세션을 실제로 읽는 코드가 생길 때
  결정한다.
- **CloudFront `/api/*` behavior 추가**: AWS 콘솔 설정이라 이 세션에서 직접 할 수 없었다. 인프라
  담당자가 별도로 처리해야 한다 — 「새로 생긴 미결 사항」 참고.
- **정지(SUSPENDED) 계정 시나리오의 실제 테스트**: `Member` 도메인에 `withdraw()`는 있지만 계정을
  정지시키는 메서드가 아직 없어(관리자 기능 미구현), `SUSPENDED` 상태를 정상적으로 만들 방법이
  없다. `!member.isActive()` 분기 하나로 `WITHDRAWN`/`SUSPENDED`를 모두 처리하므로, `WITHDRAWN`
  테스트로 같은 코드 경로를 검증했다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `customer/service/CustomerLoginServiceTest.java` | 정상 로그인(세션 생성, 회원정보 반환), 세션에 역할·회원ID 저장, 존재하지 않는 아이디, 라이더 계정, 비밀번호 불일치, 탈퇴 계정, 실패 사유 무관 동일 메시지 |
| E2E | `customer/controller/CustomerLoginE2ETest.java` | 실제 HTTP로 200+세션 쿠키(HttpOnly·SameSite=Lax) 발급, 세션 저장소에 역할 반영, 존재하지 않는 아이디 401, 비밀번호 불일치 401, 라이더 계정 401, 필수값 누락 400 |

실행 결과:

```text
cd backend && ./gradlew test
BUILD SUCCESSFUL
145 tests completed, 0 failed, 0 errors (전체 스위트, #20/#21/#24/#25 포함)
```

### 검증하지 못한 것

- CloudFront `/api/*` 경로 배선이 실제로 됐을 때 쿠키가 정말 same-origin으로 도달하는지는 배포
  환경에서만 확인 가능 — 로컬 단위/E2E 테스트로는 검증 범위 밖이다.
- 로컬 Docker(MySQL+Redis) 실기동 확인 — 이전 이슈들과 같은 이유로 스킵.

## 새로 생긴 미결 사항

- CloudFront 배포에 `/api/*` (및 SSE 경로) behavior를 추가해 EC2를 origin으로 연결하는 인프라
  작업이 아직 안 됨. 이게 안 되면(즉 프론트·API가 실제로 다른 site로 배포되면) 쿠키가
  `SameSite=Lax`로 동작하지 않으므로 로그인 자체가 깨진다 — 배포 전 반드시 확인 필요.
  SSE 경로는 CloudFront 캐싱을 꺼야(CachingDisabled) 스트리밍이 유지된다는 점도 함께 챙겨야 한다.
- 세션 슬라이딩 갱신 정책(#27에서 결정 예정).
- 계정 정지(SUSPENDED) 기능 자체가 아직 없음(관리자 기능 미구현) — 로그인 코드는 이미 대응하지만
  실제로 계정을 정지시킬 방법이 없다.
