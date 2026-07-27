# 공통 요청 로깅 필터(Filter + MDC) 설계

- 관련 이슈: [#153](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/153)
- 정본 문서: [`docs/logging-guidelines.md`](../../logging-guidelines.md) (Wiki "로깅 룰"과 동일)

## 배경 및 목적

`docs/logging-guidelines.md`(로깅 공통 규칙)는 요청 단위 공통 로그를 `Filter + MDC`가
전담하고, 각 기능 담당자는 서비스 코드에서 도메인 이벤트만 직접 남기도록 구조를 나눈다.
이 문서는 그중 `Filter + MDC` 축, 즉 모든 요청에 공통으로 붙는 요청 완료 로그와
`requestId` 발급/전파를 다룬다.

범위는 **요청 단위 공통 로그(Filter + MDC)까지**다. 서비스 계층의 도메인 이벤트 로그
(`ORDER_CREATED`, `ASSIGNMENT_SUCCEEDED` 등)와 AOP 기반 성능 로그는 이 이슈 범위 밖이며,
각 기능 담당자가 해당 기능 구현 시 직접 추가한다.

### memberId를 이번 범위에서 제외하는 이유

ADR에는 "가능하면 인증된 회원 식별자(`memberId`)도 MDC에 저장"이라고 되어 있으나,
인증/세션 기능(`common/auth`)이 아직 구현되지 않아 이 시점에는 회원 식별자를 얻을 방법이
없다. 따라서 이번 필터는 `requestId`만 다루고, `common/auth` 필터가 나중에 붙을 때
같은 MDC 키 상수를 재사용해 `memberId`를 추가할 수 있도록 확장 포인트만 남긴다
(아래 "확장 포인트" 참고).

## 패키지 위치

- `common/logging/RequestLoggingFilter.java` — 필터 로직 본체(`OncePerRequestFilter` 상속)
- `common/logging/RequestId.java` — MDC 키 상수 모음(`REQUEST_ID`, `MEMBER_ID`)
- `common/config/RequestLoggingFilterConfig.java` — `FilterRegistrationBean`으로 필터 등록

`common/logging`은 신규 서브패키지다. 기존 `common` 하위는 `config`/`exception`/`response`
(+ 아직 비어 있는 `auth`)만 있었는데, 로깅 전담 클래스가 늘어날 여지(추후 AOP 로깅 등)를
고려해 `config`에 필터 로직까지 몰아넣지 않고 분리한다. 필터 자체는 `common/logging`,
등록(Bean 배선)은 기존 관례대로 `common/config`가 담당한다.

`RequestLoggingFilterConfig`는 `FilterRegistrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE)`로
필터 체인의 맨 앞에 오도록 고정한다. 나중에 `common/auth` 인증 필터가 추가되어도 항상 이
필터보다 뒤에서 실행되므로(= 로깅 필터가 인증 필터를 감싸므로), 인증 실패로 401이 나는
요청까지 포함해 모든 요청이 빠짐없이 완료 로그에 남는다.

## RequestId 상수

```java
public final class RequestId {
    public static final String REQUEST_ID = "requestId";
    public static final String MEMBER_ID = "memberId";

    private RequestId() {}
}
```

`common/auth` 필터가 나중에 `MDC.put(RequestId.MEMBER_ID, ...)`를 호출할 때 이 상수를
그대로 재사용한다. 이 시점엔 `MEMBER_ID`를 쓰는 코드가 없어도, 상수 자체는 지금 정의해
두 필터 간의 암묵적 계약(같은 키 이름)을 명시적으로 남긴다.

## RequestLoggingFilter 동작

1. **요청 진입**: `UUID.randomUUID().toString()`으로 `requestId` 발급 →
   `MDC.put(RequestId.REQUEST_ID, requestId)`
2. **응답 헤더**: `X-Request-Id` 헤더에 `requestId`를 세팅(클라이언트가 문의 시 이 값으로
   서버 로그를 역추적할 수 있도록)
3. **요청 처리**: `chain.doFilter(request, response)`를 `try` 블록 안에서 호출
4. **완료 로그**: `try`가 끝나는 시점(정상 반환이든 예외든) 처리 시간과 응답 상태 코드를
   포함한 한 줄 로그를 남긴다 —

   ```
   event=REQUEST_COMPLETED method={} uri={} status={} durationMs={}
   ```

   - 로그 레벨은 INFO 고정. 4xx/5xx 판단이나 예외 상세는 이 필터의 책임이 아니다
     (ADR 6장 "동일 예외를 여러 계층에서 중복 기록하지 않는다"에 따라 예외 자체의 로깅은
     전역 예외 처리기 책임으로 남긴다).
   - 예외가 `chain.doFilter`를 뚫고 올라오면 상태 코드는 `response.getStatus()`로 읽은
     현재 값을 그대로 기록하고, 예외는 다시 던져 전역 예외 처리기가 처리하게 한다.
5. **MDC 정리**: `finally` 블록에서 `MDC.clear()`를 호출해 정상/예외 무관하게 스레드풀
   재사용 시 이전 요청의 MDC 값이 새어 들어가지 않도록 한다.

## 확장 포인트 (auth 필터 도입 시)

- `common/auth`의 인증 필터가 `RequestLoggingFilter`보다 **뒤에**(요청 흐름상 필터 체인에서
  로깅 필터가 먼저 실행되도록) 등록되면, 인증 필터 안에서 `MDC.put(RequestId.MEMBER_ID, ...)`
  한 줄만 추가하면 된다.
- `RequestLoggingFilter`의 완료 로그 포맷에 `memberId`를 반영할지(예:
  `event=REQUEST_COMPLETED method={} uri={} status={} durationMs={} memberId={}`)는 그때
  가서 결정한다 — 지금 필드를 미리 만들어두지 않는다(YAGNI).

## 테스트 계획

`RequestLoggingFilterTest` (`MockMvc` 또는 `MockHttpServletRequest`/`MockHttpServletResponse` +
`MockFilterChain` 단위 테스트):

- 응답 헤더에 `X-Request-Id`가 존재하고 값이 비어있지 않다
- 같은 필터를 두 번 다른 요청에 태우면 `requestId` 값이 서로 다르다
- 필터 체인 중간에서 예외가 발생해도(`FilterChain`이 예외를 던지는 상황을 목으로 재현)
  필터 실행이 끝난 뒤 `MDC.get(RequestId.REQUEST_ID)`가 `null`이다(정리 보장)
- 정상 처리 시에도 필터 실행이 끝난 뒤 MDC가 비어 있다

## 완료 조건

- [ ] `RequestId` 상수 클래스 작성
- [ ] `RequestLoggingFilter` 구현(`requestId` 발급, 응답 헤더, 완료 로그, MDC 정리)
- [ ] `RequestLoggingFilterConfig`로 필터 등록
- [ ] `RequestLoggingFilterTest` TDD로 작성 및 통과
- [ ] CI 테스트 통과
- [ ] 코드 리뷰 반영
