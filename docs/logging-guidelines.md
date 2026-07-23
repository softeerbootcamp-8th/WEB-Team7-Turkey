# 로깅 공통 규칙

## 1. 목적

Turkey 프로젝트에서는 배차 동시성, 배송 상태 전이, 포인트·정산 정합성, Redis 위치 처리, SSE 연결 문제를 추적할 수 있도록 로그를 남긴다.

공통 요청 로그는 로그 담당자가 Filter와 MDC로 자동 처리하며, 각 기능 담당자는 서비스 코드에서 중요한 비즈니스 이벤트만 명시적으로 기록한다. 성능 측정이 필요한 일부 메서드에 한해 선택적으로 AOP를 사용한다.

## 2. 로깅 구조

```text
필수
├─ Filter + MDC        → 공통 HTTP 요청 완료 로그
└─ Service              → 도메인 이벤트 직접 로그

선택
└─ AOP                  → 성능 측정이 필요한 메서드에만 적용
```

- **Filter + MDC**: 요청 단위 공통 로그를 담당한다. Controller 진입 전에 요청 식별자를 발급하고, 요청 처리가 끝나는 시점에 결과를 한 줄로 기록한다.
- **Service**: 비즈니스적으로 의미 있는 이벤트(주문 생성, 배차 성공/실패, 상태 전이, 포인트 차감, SSE 송신 등)를 발생 시점에 직접 기록한다. 이벤트 로그에 필요한 식별자(`orderId`, `riderId`, `emitterId` 등)는 MDC에 의존하지 않고 로그 파라미터로 명시적으로 전달한다.
- **AOP**: 필수 요소가 아니다. 서비스 메서드 실행 시간처럼 별도 계측이 필요한 경우에만 선택적으로 적용하고, 예외를 로그로 남기는 용도로는 사용하지 않는다(6장 중복 로깅 금지와 충돌).

## 3. 공통 요청 로그 (Filter + MDC)

로그 담당자가 Filter로 구현한다.

- 요청 진입 시 요청 식별자(`requestId`)를 발급하여 MDC에 저장한다.
- 가능하면 인증된 회원 식별자(`memberId`)도 MDC에 함께 저장한다.
- 요청 종료 시 API 처리 시간, 성공/실패 여부를 포함한 완료 로그를 한 줄 남긴다.
- 응답 헤더에 `requestId`를 반환한다.
- 요청 처리가 끝나면(정상/예외 무관) `finally` 블록에서 MDC를 반드시 정리한다.

팀원은 Controller마다 요청 시작·종료 로그를 별도로 작성하지 않는다.

### MDC 사용 범위와 한계

MDC는 하나의 요청을 처리하는 스레드 안에서만 유효하다. 다음과 같이 스레드가 전환되는 지점에서는 MDC 값이 이어지지 않으므로, 해당 로그에는 `orderId`, `riderId`, `emitterId` 등 필요한 식별자를 서비스 코드에서 파라미터로 직접 넘겨 기록한다.

- 라이더 위치 갱신 요청과, 그 위치를 SSE로 구독 고객에게 전달하는 시점은 서로 다른 요청/스레드다.
- SSE 연결은 응답이 즉시 끝나지 않고 장시간 유지되므로, 연결 생성 시점의 MDC와 이후 이벤트 전송 시점의 MDC는 같은 컨텍스트가 아니다.
- 요청 처리 중간에 생성되는 식별자(예: 주문 생성 중 발급되는 `orderId`)는 요청 시작 시점의 MDC에는 없으므로, 생성된 시점부터 로그 파라미터로 추가한다.

즉, MDC(`requestId`, `memberId`)는 같은 HTTP 요청 내부의 로그를 엮는 용도로만 사용하고, 요청 경계를 넘는 도메인 흐름(SSE 송신, 비동기 처리 등)은 서비스 계층에서 식별자를 직접 로그에 남겨 추적한다.

## 4. 팀원이 작성할 로그 (Service)

각 기능 담당자는 서비스 코드에서 중요한 처리 결과를 직접 기록한다.

주요 대상:

- 주문 생성 및 취소
- 배차 성공 및 경쟁 실패
- 배송 상태 변경
- 포인트 차감 및 환불
- 배송 완료 및 정산 생성
- 위치 정보 검증 실패
- SSE 연결, 종료 및 전송 실패

이벤트 로그에 필요한 식별자는 MDC가 아니라 로그 파라미터로 직접 전달한다. 특히 `emitterId`처럼 SSE 연결 단위로만 의미 있는 값, 또는 요청 도중 생성되는 `orderId` 같은 값은 서비스 로직에서 해당 이벤트가 발생하는 지점에 명시한다.

예시:

```java
log.info(
    "event=ASSIGNMENT_SUCCEEDED orderId={} riderId={}",
    orderId,
    riderId
);
```

```java
log.warn(
    "event=ASSIGNMENT_REJECTED orderId={} riderId={} reason={}",
    orderId,
    riderId,
    "ORDER_ALREADY_ASSIGNED"
);
```

```java
log.warn(
    "event=SSE_SEND_FAILED orderId={} emitterId={} reason={}",
    orderId,
    emitterId,
    "CLIENT_DISCONNECTED"
);
```

## 5. 선택적 성능 로그 (AOP)

성능 측정이 필요하다고 판단되는 메서드에 한해 AOP를 선택적으로 적용한다.

- 대상은 각 기능 담당자가 필요에 따라 정한다. 전체 서비스 계층에 일괄 적용하지 않는다.
- 실행 시간 측정 등 계측 목적으로만 사용하며, 예외 처리나 비즈니스 이벤트 로그의 대체 수단으로 사용하지 않는다.
- 예외 로그는 AOP가 아닌 전역 예외 처리기에서 남기는 것을 기본으로 하여 6장의 중복 로깅 금지 원칙을 지킨다.

## 6. 로그 작성 형식

로그는 자유로운 문장보다 다음 형식을 사용한다.

```text
event=EVENT_NAME key=value key=value
```

이벤트 이름은 대문자 스네이크 케이스로 작성한다.

```text
ORDER_CREATED
ORDER_CANCELED
ASSIGNMENT_SUCCEEDED
ASSIGNMENT_REJECTED
ORDER_STATUS_CHANGED
ORDER_TRANSITION_REJECTED
POINT_DEDUCTED
POINT_REFUNDED
SETTLEMENT_CREATED
LOCATION_REJECTED
SSE_CONNECTED
SSE_DISCONNECTED
SSE_SEND_FAILED
```

주문과 관련된 로그에는 가능한 경우 `orderId`를 포함한다. 필요에 따라 `memberId`, `customerId`, `riderId`, `transactionId`, `settlementId`, `emitterId`를 함께 기록한다. 이 식별자들은 MDC가 아니라 각 로그 호출 시점에 서비스 코드에서 직접 전달한다.

## 7. 로그 레벨

- `INFO`: 정상적으로 완료된 주요 비즈니스 이벤트
- `WARN`: 예상 가능한 실패, 중복 요청, 잘못된 상태 전이
- `ERROR`: DB·Redis 장애, 트랜잭션 실패, 예상하지 못한 예외
- `DEBUG`: 개발 중 확인이 필요한 상세 정보

다른 라이더가 먼저 배차를 확정한 경우는 시스템 장애가 아니므로 `ERROR`가 아닌 `WARN`으로 기록한다.

## 8. 기록 금지 정보

다음 정보는 로그에 남기지 않는다.

- 비밀번호 및 비밀번호 해시
- 세션 ID 전체
- 쿠키 전체
- 인증번호
- 전화번호 전체
- 계좌번호 전체
- 요청·응답 객체 전체
- 라이더 위치 좌표의 반복적인 `INFO` 로그

민감정보가 필요한 경우 일부만 마스킹한다. 또한 동일한 예외를 Controller, Service, 전역 예외 처리기 등 여러 계층에서 중복 기록하지 않는다.
