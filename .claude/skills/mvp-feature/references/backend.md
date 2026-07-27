# 백엔드 구현 규칙

단계 3에서 읽는다. 이 저장소에 이미 존재하는 관례를 그대로 잇는 것이 목적이다.
새 패턴을 발명하기 전에 같은 계층의 기존 파일을 먼저 열어 보고 그 형태를 따른다.

## 패키지와 파일 위치

```
com.turkey.quick.{customer, rider, order, matching, location, payment, common}
  └─ controller / dto / service / repository / domain
```

- 도메인 엔터티·enum은 `{도메인}/domain/`에 둔다. (`order/OrderStatus.java`처럼 탑레벨에 있는 과거 파일이 있지만, 신규는 `domain/`을 따른다.)
- 어느 도메인에 넣을지 애매하면 **그 데이터를 소유한 애그리거트**를 기준으로 정한다.
  예: 요금 정책은 결제가 아니라 주문 요금 결정에 가까워 `order/domain`에 있다.
- 공통은 `common/` 아래 `config`, `exception`, `response`, `logging`, `auth`.

## 작성 순서

Repository → Service → DTO → Controller 순으로 만든다.
바깥에서 안으로 쓰면 아직 없는 시그니처를 추측하게 되고, 안에서 바깥으로 쓰면 그럴 일이 없다.

### Repository

`JpaRepository<Entity, Long>` 인터페이스. 데이터 접근 기술은 JPA로 확정되어 있다.

정합성이 걸린 조회는 여기서 결정된다. 조건부 UPDATE로 경쟁을 해소할 거라면
`@Modifying @Query`로 조건을 WHERE에 넣고, 반환된 갱신 행 수(0 또는 1)로 승패를 판정한다.
락을 쓸 거라면 `@Lock(LockModeType.PESSIMISTIC_WRITE)`. **어느 쪽을 쓸지는 단계 2에서 확인받은 대로** 한다
(`CLAUDE.md`의 「확인이 필요한 항목」에 아직 미결로 남아 있는 주제다).

### Service

- `@Service`, 생성자 주입(Lombok `@RequiredArgsConstructor`), 필드 주입 금지.
- 조회 전용은 `@Transactional(readOnly = true)`, 변경은 `@Transactional`.
- **트랜잭션 경계를 의식적으로 정한다.** `CLAUDE.md`가 하나의 트랜잭션으로 묶으라고 명시한 것들:
  - 배차 확정: 배송 `WAITING→ASSIGNED` + 라이더 `AVAILABLE→BUSY` + 배차 관계 생성
  - 배송 완료: 배송 `DELIVERING→COMPLETED` + 라이더 `BUSY→AVAILABLE` + 정산 내역 생성
- 상태 전이는 **요청 값으로 덮어쓰지 않는다.** 현재 상태 + 수행 행위로 검증하고,
  허용되지 않은 전이는 엔터티 안에서 예외로 거부한다. 서비스는 그 메서드를 호출할 뿐이다.
- 도메인 이벤트 로그는 서비스에서 남긴다(`docs/logging-guidelines.md` §4).
  식별자는 MDC에 기대지 말고 로그 파라미터로 직접 넘긴다. 컨트롤러에 요청 시작/종료 로그를 따로 쓰지 않는다
  — 그건 `RequestLoggingFilter`가 이미 한다.

### DTO

`{도메인}/dto/`에 **record**로 만든다. 요청과 응답을 나눈다.

- 요청: `DeliveryCreateRequest` — Bean Validation(`@NotNull`, `@Positive`, `@Size`)으로 형식 검증.
- 응답: `DeliveryCreateResponse` — 엔터티를 그대로 노출하지 말고 `static from(Entity)` 팩터리로 변환한다.
  엔터티를 응답에 실으면 지연 로딩과 내부 필드가 API 계약에 새어나간다.
- 요청 DTO에 상태 값(`status`)을 받지 않는다. 상태는 행위로 결정된다.

### Controller

`@RestController`, `@RequestMapping("/api/...")`. 반환은 항상 `ApiResponse<T>`로 감싼다
(`common/response/ApiResponse.java`의 `ok` / `fail`).

경로는 액터를 앞에 둔다: `/api/customer/...`, `/api/rider/...`. 공용은 `/api/...`.
동적 세그먼트는 프론트와 맞춰 `{deliveryId}`를 쓴다.

컨트롤러는 얇게 유지한다. 여기에 비즈니스 분기(상태 검사, 권한 판정)를 넣지 않는다.

## springdoc — 문서화가 곧 프론트 계약이다

이 저장소의 프론트 API 클라이언트는 `/v3/api-docs`에서 **자동 생성**된다.
즉 컨트롤러에 붙인 어노테이션이 그대로 프론트의 타입과 훅 이름이 된다.
어노테이션을 대충 달면 프론트에 `postApiCustomerDeliveries` 같은 이름과 `unknown` 타입이 생긴다.

컨트롤러 클래스에는 `@Tag`를, 메서드에는 `@Operation`을 반드시 단다.

```java
@Tag(name = "customer-delivery", description = "고객 배송요청")
@RestController
@RequestMapping("/api/customer/deliveries")
@RequiredArgsConstructor
public class CustomerDeliveryController {

    private final DeliveryOrderService deliveryOrderService;

    @Operation(summary = "배송요청 생성", description = "고객이 새 배송요청을 등록한다. 진행 중 요청이 있으면 거부된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "진행 중 배송요청이 이미 있음")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DeliveryCreateResponse> create(
            @Valid @RequestBody DeliveryCreateRequest request) {
        return ApiResponse.ok(DeliveryCreateResponse.from(deliveryOrderService.create(request)));
    }
}
```

주의할 점:

- `@Tag`의 `name`이 Orval의 **파일 분리 단위**다 (`mode: 'tags-split'`).
  백엔드 패키지 구조와 대응되도록 `customer-delivery`, `rider-matching`처럼 케밥케이스로 일관되게 붙인다.
  같은 도메인 컨트롤러가 여러 개면 같은 태그명을 공유한다.
- 프로젝트의 `com.turkey.quick.common.response.ApiResponse`와 springdoc의
  `io.swagger.v3.oas.annotations.responses.ApiResponse`는 **이름이 겹친다.**
  둘 다 쓰는 파일에서는 위 예시처럼 swagger 쪽을 FQCN으로 쓰거나, `@ApiResponses` 자체를 생략한다.
  (에러 코드가 자명하면 `@Operation`만으로 충분하다. 겹침을 피하려고 프로젝트 응답 타입을 바꾸지는 않는다.)
- DTO record 필드에는 `@Schema(description = "...", example = "...")`를 단다.
  프론트에서 이 설명이 JSDoc으로 따라간다.
- 문서가 바뀌었으면 프론트를 다시 생성해야 한다 → 단계 4.

## 예외 처리

도메인 규칙 위반은 도메인/서비스에서 예외를 던지고, HTTP 변환은 한곳에서 한다.

`common/exception/`에 전역 핸들러가 **아직 없다.** 이번 이슈에서 처음 필요해졌다면 만들되,
이슈 범위를 넘는 대규모 예외 체계를 설계하지 말고 이번에 필요한 것만 추가한다.
이미 있으면 그 구조에 케이스만 더한다.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(ApiResponse.fail(message));
    }
}
```

기존 엔터티들이 검증 실패에 `IllegalArgumentException`을 쓰고 있으니 그 관례를 잇는다.
상태 전이 거부(409)처럼 400과 구분해야 하는 케이스가 생기면 그때 전용 예외를 도입하고,
**그 판단은 단계 6 문서에 남긴다.**

예외를 서비스에서 로그로 남기고 핸들러에서 또 남기지 않는다(중복 로깅 금지, `docs/logging-guidelines.md` §6).

## Flyway 마이그레이션

스키마 변경은 마이그레이션 파일로만 한다. 콘솔에서 직접 고치지 않는다.

- 위치: `backend/src/main/resources/db/migration/`
- **파일명은 디렉터리에 이미 있는 형식을 따른다.** 현재는 순번 방식(`V17__create_point_transaction.sql`)이므로
  다음 번호를 쓴다. (`docs/flyway-ground-rules-short.md`는 타임스탬프 방식을 제안하지만 저장소 실물은 순번이다.
  이 불일치는 문서 담당자에게 올릴 사항이지, 이 작업에서 임의로 바꿀 사항이 아니다.)
- 순번 방식이라 **동시에 열린 다른 브랜치와 번호가 충돌할 수 있다.** 작성 전에 `git fetch origin main` 후
  원격 브랜치들의 최신 번호를 확인하고, 충돌하면 자기 번호를 뒤로 민다.
- **이미 머지된 파일은 절대 수정하지 않는다.** 되돌릴 일이 있어도 새 파일을 추가한다.
- 파일 하나 = 논리적 변경 하나. 테이블 생성에 필요한 제약·인덱스는 같은 파일에 포함해도 된다.
- JPA는 `ddl-auto: validate`다. 엔터티와 스키마가 어긋나면 **앱이 뜨지 않는다.**
  마이그레이션과 엔터티 매핑을 반드시 같이 맞춘다.
- 로컬은 H2(MySQL 모드)로 검증한다. H2 2.3.x는 생성 컬럼의 `VIRTUAL` 키워드를 파싱하지 못하므로
  `GENERATED ALWAYS AS (...)`까지만 쓴다(MySQL 기본값이 VIRTUAL이라 결과가 같다).
  이 저장소의 기존 마이그레이션이 이미 그 방식이다.

## 인증

Spring Security를 쓰지 않는다. 쿠키 기반 서버 세션(Redis 저장)을 필터/인터셉터로 직접 처리한다.
`common/auth/`는 아직 비어 있다. 인증이 필요한 엔드포인트인데 세션 배선이 없다면
**추측해서 만들지 말고** 단계 2에서 사람에게 확인한다 — 인가 방식은 이슈 하나가 임의로 정할 사안이 아니다.

## 검증

```bash
cd backend && ./gradlew build          # 컴파일 + 테스트
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'
curl -s localhost:8080/v3/api-docs | head -40    # 문서가 나오는지
```
