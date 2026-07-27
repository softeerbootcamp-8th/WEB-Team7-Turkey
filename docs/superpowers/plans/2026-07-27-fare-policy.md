# FarePolicy / ItemTypeSurcharge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 요금 정책(`fare_policy`) 및 물품 종류별 할증(`item_type_surcharge`) 도메인 엔터티와 Flyway 마이그레이션을 추가하고, `active_policy_marker` VIRTUAL 생성 컬럼이 H2(MySQL 모드)에서 동작함을 실증한다.

**Architecture:** `FarePolicy`를 애그리거트 루트로 두고 `ItemTypeSurcharge`를 `@OneToMany(cascade = ALL, orphanRemoval = true)`로 소유한다. 상태 전이는 setter가 아닌 행위 메서드(`activate`/`deactivate`)로만 수행하고, 허용되지 않은 전이는 `IllegalStateException`으로 거부한다. 스키마는 Flyway가 만들고 JPA는 `validate`로 검증만 한다.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring Data JPA(Hibernate), Flyway, Lombok, JUnit 5 + AssertJ, MySQL 8.4(운영) / H2 MySQL 호환 모드(로컬)

## Global Constraints

- 패키지: 신규 클래스 4개 모두 `com.turkey.quick.order.domain`
- 엔터티에 setter를 두지 않는다. 상태 변경은 행위 메서드로만 수행한다.
- 타임스탬프는 `LocalDateTime.now(ZoneOffset.UTC)`로 `@PrePersist`에서 세팅한다. `fare_policy`/`item_type_surcharge`는 DDL에 `updated_at` 컬럼이 없으므로 `@PreUpdate`를 두지 않는다.
- Lombok은 `@Getter` + `@NoArgsConstructor(access = AccessLevel.PROTECTED)`만 사용한다(`@Setter`/`@Builder` 금지).
- JPA `@Version`은 사용하지 않는다(팀 정책상 전면 폐기).
- Flyway 마이그레이션 파일명은 `V{n}__{snake_case_설명}.sql`, 제약조건 명명은 `pk_`/`uk_`/`fk_`/`ck_` 접두사를 따른다.
- 마이그레이션 SQL의 테이블 옵션은 `ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci`로 고정하고 `COMMENT`를 단다.
- 작업 브랜치: `feature/142-fare-policy-entities` (이미 생성됨, 스펙 문서 커밋 완료)
- 모든 Gradle 명령은 `backend/` 디렉터리에서 실행한다.

## File Structure

| 파일 | 책임 |
|---|---|
| `backend/src/main/java/com/turkey/quick/order/domain/FarePolicyStatus.java` | 요금 정책 상태 enum (ACTIVE/INACTIVE) |
| `backend/src/main/java/com/turkey/quick/order/domain/ItemType.java` | 배송 물품 종류 enum. 2단계 `DeliveryOrder.item_type`에서도 재사용 |
| `backend/src/main/java/com/turkey/quick/order/domain/FarePolicy.java` | 요금 정책 애그리거트 루트. 상태 전이 + 할증 추가 |
| `backend/src/main/java/com/turkey/quick/order/domain/ItemTypeSurcharge.java` | 정책 버전별 물품 할증. 루트를 통해서만 생성 |
| `backend/src/main/resources/db/migration/V8__create_fare_policy.sql` | `fare_policy` 테이블 |
| `backend/src/main/resources/db/migration/V9__create_item_type_surcharge.sql` | `item_type_surcharge` 테이블 |
| `backend/src/test/java/com/turkey/quick/order/domain/FarePolicyTest.java` | 도메인 행위 단위 테스트 |

---

### Task 1: enum 2종 + FarePolicy 생성/검증

**Files:**
- Create: `backend/src/main/java/com/turkey/quick/order/domain/FarePolicyStatus.java`
- Create: `backend/src/main/java/com/turkey/quick/order/domain/ItemType.java`
- Create: `backend/src/main/java/com/turkey/quick/order/domain/FarePolicy.java`
- Test: `backend/src/test/java/com/turkey/quick/order/domain/FarePolicyTest.java`

**Interfaces:**
- Consumes: 없음(첫 태스크)
- Produces:
  - `enum FarePolicyStatus { ACTIVE, INACTIVE }`
  - `enum ItemType { DOCUMENT, SMALL_PARCEL, MEDIUM_PARCEL, LARGE_PARCEL, FOOD }`
  - `static FarePolicy FarePolicy.create(String policyVersion, long baseFare, int distanceUnitMeters, long distanceUnitFare, int maxDeliveryDistanceMeters, LocalDateTime effectiveFrom)`
  - getters: `getId()`, `getPolicyVersion()`, `getBaseFare()`, `getDistanceUnitMeters()`, `getDistanceUnitFare()`, `getMaxDeliveryDistanceMeters()`, `getStatus()`, `getEffectiveFrom()`, `getEffectiveTo()`, `getCreatedAt()`, `getSurcharges()`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/turkey/quick/order/domain/FarePolicyTest.java`:

```java
package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class FarePolicyTest {

    private FarePolicy policy() {
        return FarePolicy.create("v1", 3_000L, 1_000, 500L, 10_000, LocalDateTime.now());
    }

    @Test
    void 생성하면_INACTIVE_상태이고_적용종료시각과_할증은_비어있다() {
        FarePolicy policy = policy();

        assertThat(policy.getStatus()).isEqualTo(FarePolicyStatus.INACTIVE);
        assertThat(policy.getEffectiveTo()).isNull();
        assertThat(policy.getSurcharges()).isEmpty();
    }

    @Test
    void 기본요금은_양수여야_한다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 0L, 1_000, 500L, 10_000, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거리단위는_양수여야_한다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 0, 500L, 10_000, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거리단가는_양수여야_한다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 1_000, 0L, 10_000, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최대배송거리는_양수여야_한다() {
        assertThatThrownBy(() ->
                FarePolicy.create("v1", 3_000L, 1_000, 500L, 0, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.turkey.quick.order.domain.FarePolicyTest'` (from `backend/`)
Expected: 컴파일 실패 — `cannot find symbol: class FarePolicy`

- [ ] **Step 3: Write the enums**

Create `backend/src/main/java/com/turkey/quick/order/domain/FarePolicyStatus.java`:

```java
package com.turkey.quick.order.domain;

/** 요금 정책 적용 상태. 동시에 ACTIVE 일 수 있는 정책은 최대 1개다. */
public enum FarePolicyStatus {
    ACTIVE,
    INACTIVE
}
```

Create `backend/src/main/java/com/turkey/quick/order/domain/ItemType.java`:

```java
package com.turkey.quick.order.domain;

/** 배송 물품 종류. 요금 할증 기준이자 주문의 물품 구분값이다(크기·무게·수량 기준은 쓰지 않는다). */
public enum ItemType {
    DOCUMENT,
    SMALL_PARCEL,
    MEDIUM_PARCEL,
    LARGE_PARCEL,
    FOOD
}
```

- [ ] **Step 4: Write minimal FarePolicy implementation**

Create `backend/src/main/java/com/turkey/quick/order/domain/FarePolicy.java`:

```java
package com.turkey.quick.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 거리 단위당 요금을 직접 저장하는 요금 정책. 거리 구간별 규칙을 별도 행으로 관리하지 않는다.
 *
 * 정책은 policy_version 단위로 관리되며, 동시에 활성(ACTIVE)일 수 있는 정책은 최대 1개다 —
 * DB 의 active_policy_marker(VIRTUAL) + uk_fare_policy_active UNIQUE 가 이를 강제한다.
 * 이 엔터티는 전이 자체의 유효성만 검증하고, 기존 활성 정책을 먼저 비활성화하는 오케스트레이션은
 * 서비스 계층 책임이다.
 *
 * item_type_surcharge 는 특정 정책 버전 없이는 존재 의미가 없는 하위 구성요소이므로,
 * 이 루트의 addSurcharge() 를 통해서만 생성된다.
 */
@Entity
@Table(name = "fare_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FarePolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fare_policy_id")
    private Long id;

    @Column(name = "policy_version", nullable = false, length = 30, updatable = false)
    private String policyVersion;

    @Column(name = "base_fare", nullable = false, updatable = false)
    private long baseFare;

    @Column(name = "distance_unit_meters", nullable = false, updatable = false)
    private int distanceUnitMeters;

    @Column(name = "distance_unit_fare", nullable = false, updatable = false)
    private long distanceUnitFare;

    @Column(name = "max_delivery_distance_meters", nullable = false, updatable = false)
    private int maxDeliveryDistanceMeters;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FarePolicyStatus status;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "farePolicy", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<ItemTypeSurcharge> surcharges = new ArrayList<>();

    private FarePolicy(String policyVersion, long baseFare, int distanceUnitMeters,
                       long distanceUnitFare, int maxDeliveryDistanceMeters,
                       LocalDateTime effectiveFrom) {
        if (baseFare <= 0) {
            throw new IllegalArgumentException("기본요금은 양수여야 합니다. baseFare=" + baseFare);
        }
        if (distanceUnitMeters <= 0) {
            throw new IllegalArgumentException(
                    "거리 단위는 양수여야 합니다. distanceUnitMeters=" + distanceUnitMeters);
        }
        if (distanceUnitFare <= 0) {
            throw new IllegalArgumentException(
                    "거리 단가는 양수여야 합니다. distanceUnitFare=" + distanceUnitFare);
        }
        if (maxDeliveryDistanceMeters <= 0) {
            throw new IllegalArgumentException(
                    "최대 배송 거리는 양수여야 합니다. maxDeliveryDistanceMeters="
                            + maxDeliveryDistanceMeters);
        }
        this.policyVersion = policyVersion;
        this.baseFare = baseFare;
        this.distanceUnitMeters = distanceUnitMeters;
        this.distanceUnitFare = distanceUnitFare;
        this.maxDeliveryDistanceMeters = maxDeliveryDistanceMeters;
        this.effectiveFrom = effectiveFrom;
        this.status = FarePolicyStatus.INACTIVE;
    }

    /** 새 요금 정책 생성. 항상 INACTIVE 로 시작하고 activate() 로 명시적으로 활성화한다. */
    public static FarePolicy create(String policyVersion, long baseFare, int distanceUnitMeters,
                                    long distanceUnitFare, int maxDeliveryDistanceMeters,
                                    LocalDateTime effectiveFrom) {
        return new FarePolicy(policyVersion, baseFare, distanceUnitMeters, distanceUnitFare,
                maxDeliveryDistanceMeters, effectiveFrom);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
```

주의: 이 단계에서 `ItemTypeSurcharge` 클래스가 아직 없으므로 컴파일이 실패한다. Task 2에서 생성한다. 컴파일을 통과시키기 위해 이 Task에서 `ItemTypeSurcharge`를 함께 만들 것 — Step 5로 이어진다.

- [ ] **Step 5: Write ItemTypeSurcharge (컴파일 통과용 최소 구현)**

Create `backend/src/main/java/com/turkey/quick/order/domain/ItemTypeSurcharge.java`:

```java
package com.turkey.quick.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 요금 정책 버전별 물품 종류 할증.
 * FarePolicy 없이는 존재 의미가 없는 애그리거트 하위 구성요소이므로 생성 경로를
 * package-private 으로 제한하고, FarePolicy.addSurcharge() 를 통해서만 만들어진다.
 */
@Entity
@Table(name = "item_type_surcharge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemTypeSurcharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_type_surcharge_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fare_policy_id", updatable = false)
    private FarePolicy farePolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 30, updatable = false)
    private ItemType itemType;

    @Column(name = "surcharge_amount", nullable = false, updatable = false)
    private long surchargeAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private ItemTypeSurcharge(FarePolicy farePolicy, ItemType itemType, long surchargeAmount) {
        this.farePolicy = farePolicy;
        this.itemType = itemType;
        this.surchargeAmount = surchargeAmount;
    }

    static ItemTypeSurcharge create(FarePolicy farePolicy, ItemType itemType,
                                    long surchargeAmount) {
        return new ItemTypeSurcharge(farePolicy, itemType, surchargeAmount);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'com.turkey.quick.order.domain.FarePolicyTest'` (from `backend/`)
Expected: PASS (5 tests)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/turkey/quick/order/domain/ backend/src/test/java/com/turkey/quick/order/domain/FarePolicyTest.java
git commit -m "feat: 요금 정책 엔터티 및 물품 종류 enum 추가 (#142)"
```

---

### Task 2: 상태 전이 (activate / deactivate)

**Files:**
- Modify: `backend/src/main/java/com/turkey/quick/order/domain/FarePolicy.java`
- Test: `backend/src/test/java/com/turkey/quick/order/domain/FarePolicyTest.java`

**Interfaces:**
- Consumes: Task 1의 `FarePolicy.create(...)`, `FarePolicyStatus`
- Produces:
  - `void FarePolicy.activate()` — `INACTIVE → ACTIVE`
  - `void FarePolicy.deactivate()` — `ACTIVE → INACTIVE`, `effectiveTo`를 현재 UTC 시각으로 세팅

- [ ] **Step 1: Write the failing tests**

`FarePolicyTest`에 아래 테스트 4개를 추가한다(기존 테스트 아래에 붙인다):

```java
    @Test
    void activate하면_ACTIVE로_전이한다() {
        FarePolicy policy = policy();

        policy.activate();

        assertThat(policy.getStatus()).isEqualTo(FarePolicyStatus.ACTIVE);
    }

    @Test
    void 이미_ACTIVE인_정책은_다시_activate할수_없다() {
        FarePolicy policy = policy();
        policy.activate();

        assertThatThrownBy(policy::activate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deactivate하면_INACTIVE로_전이하고_적용종료시각이_기록된다() {
        FarePolicy policy = policy();
        policy.activate();

        policy.deactivate();

        assertThat(policy.getStatus()).isEqualTo(FarePolicyStatus.INACTIVE);
        assertThat(policy.getEffectiveTo()).isNotNull();
    }

    @Test
    void INACTIVE_상태는_deactivate할수_없다() {
        FarePolicy policy = policy();

        assertThatThrownBy(policy::deactivate).isInstanceOf(IllegalStateException.class);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.turkey.quick.order.domain.FarePolicyTest'` (from `backend/`)
Expected: 컴파일 실패 — `cannot find symbol: method activate()`

- [ ] **Step 3: Implement the transitions**

`FarePolicy.java`의 `create(...)` 메서드 아래, `onCreate()` 위에 다음을 추가한다:

```java
    /** 정책 활성화: INACTIVE → ACTIVE. */
    public void activate() {
        requireStatus(FarePolicyStatus.INACTIVE, "활성화");
        this.status = FarePolicyStatus.ACTIVE;
    }

    /** 정책 비활성화: ACTIVE → INACTIVE. 적용 종료 시각을 남긴다. */
    public void deactivate() {
        requireStatus(FarePolicyStatus.ACTIVE, "비활성화");
        this.status = FarePolicyStatus.INACTIVE;
        this.effectiveTo = LocalDateTime.now(ZoneOffset.UTC);
    }

    private void requireStatus(FarePolicyStatus required, String action) {
        if (this.status != required) {
            throw new IllegalStateException(
                    action + " 불가: 현재 상태 " + status + " (요구 상태 " + required + ")");
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.turkey.quick.order.domain.FarePolicyTest'` (from `backend/`)
Expected: PASS (9 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/turkey/quick/order/domain/FarePolicy.java backend/src/test/java/com/turkey/quick/order/domain/FarePolicyTest.java
git commit -m "feat: 요금 정책 활성화/비활성화 상태 전이 (#142)"
```

---

### Task 3: 물품 종류별 할증 추가 (addSurcharge)

**Files:**
- Modify: `backend/src/main/java/com/turkey/quick/order/domain/FarePolicy.java`
- Test: `backend/src/test/java/com/turkey/quick/order/domain/FarePolicyTest.java`

**Interfaces:**
- Consumes: Task 1의 `ItemTypeSurcharge.create(FarePolicy, ItemType, long)` (package-private), `ItemType`
- Produces: `void FarePolicy.addSurcharge(ItemType itemType, long surchargeAmount)`

- [ ] **Step 1: Write the failing tests**

`FarePolicyTest`에 아래 테스트 3개를 추가한다:

```java
    @Test
    void 물품종류_할증을_추가할수_있다() {
        FarePolicy policy = policy();

        policy.addSurcharge(ItemType.FOOD, 1_000L);

        assertThat(policy.getSurcharges()).hasSize(1);
        assertThat(policy.getSurcharges().get(0).getItemType()).isEqualTo(ItemType.FOOD);
        assertThat(policy.getSurcharges().get(0).getSurchargeAmount()).isEqualTo(1_000L);
        assertThat(policy.getSurcharges().get(0).getFarePolicy()).isSameAs(policy);
    }

    @Test
    void 같은_물품종류를_중복_추가할수_없다() {
        FarePolicy policy = policy();
        policy.addSurcharge(ItemType.FOOD, 1_000L);

        assertThatThrownBy(() -> policy.addSurcharge(ItemType.FOOD, 500L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 할증금액은_음수일수_없다() {
        FarePolicy policy = policy();

        assertThatThrownBy(() -> policy.addSurcharge(ItemType.FOOD, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.turkey.quick.order.domain.FarePolicyTest'` (from `backend/`)
Expected: 컴파일 실패 — `cannot find symbol: method addSurcharge(ItemType,long)`

- [ ] **Step 3: Implement addSurcharge**

`FarePolicy.java`의 `create(...)` 메서드 아래, `activate()` 위에 다음을 추가한다:

```java
    /**
     * 물품 종류별 할증 추가. 같은 itemType 은 중복 등록할 수 없다 —
     * DB uk_item_surcharge_policy_type 과 동일한 조합을 앱에서도 먼저 막아
     * 제약 위반 예외 대신 명확한 도메인 예외로 실패시킨다.
     */
    public void addSurcharge(ItemType itemType, long surchargeAmount) {
        if (surchargeAmount < 0) {
            throw new IllegalArgumentException(
                    "할증 금액은 음수일 수 없습니다. surchargeAmount=" + surchargeAmount);
        }
        boolean duplicate = this.surcharges.stream()
                .anyMatch(surcharge -> surcharge.getItemType() == itemType);
        if (duplicate) {
            throw new IllegalStateException(
                    "이미 등록된 물품 종류입니다: " + itemType
                            + " (policyVersion=" + policyVersion + ")");
        }
        this.surcharges.add(ItemTypeSurcharge.create(this, itemType, surchargeAmount));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.turkey.quick.order.domain.FarePolicyTest'` (from `backend/`)
Expected: PASS (12 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/turkey/quick/order/domain/FarePolicy.java backend/src/test/java/com/turkey/quick/order/domain/FarePolicyTest.java
git commit -m "feat: 요금 정책 물품 종류별 할증 추가 (#142)"
```

---

### Task 4: Flyway 마이그레이션 + H2 validate 실증

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__create_fare_policy.sql`
- Create: `backend/src/main/resources/db/migration/V9__create_item_type_surcharge.sql`

**Interfaces:**
- Consumes: Task 1~3의 엔터티 매핑(컬럼명/타입이 여기 DDL과 일치해야 `validate`가 통과한다)
- Produces: `fare_policy`, `item_type_surcharge` 테이블. 다음 단계(#143 `DeliveryOrder`)는 V10부터 번호를 잇는다.

- [ ] **Step 1: Write V8 migration**

Create `backend/src/main/resources/db/migration/V8__create_fare_policy.sql`:

```sql
-- 거리 단위당 요금을 직접 저장하는 요금 정책. 거리 구간별 규칙을 별도 행으로 관리하지 않는다.
-- active_policy_marker 는 status='ACTIVE' 일 때만 1, 그 외에는 NULL 인 생성 컬럼이다.
-- MySQL UNIQUE 는 NULL 을 다건 허용하므로, 이 컬럼의 UNIQUE 가 곧
-- "활성 요금 정책은 최대 1건" 제약이 된다.

CREATE TABLE fare_policy (
    fare_policy_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    policy_version VARCHAR(30) NOT NULL,
    base_fare BIGINT UNSIGNED NOT NULL,
    distance_unit_meters INT UNSIGNED NOT NULL DEFAULT 1000,
    distance_unit_fare BIGINT UNSIGNED NOT NULL,
    max_delivery_distance_meters INT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    effective_from DATETIME(3) NOT NULL,
    effective_to DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    active_policy_marker TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END
        ) VIRTUAL,

    CONSTRAINT pk_fare_policy PRIMARY KEY (fare_policy_id),
    CONSTRAINT uk_fare_policy_version UNIQUE (policy_version),
    CONSTRAINT uk_fare_policy_active UNIQUE (active_policy_marker),
    CONSTRAINT ck_fare_policy_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_fare_policy_base_fare
        CHECK (base_fare > 0),
    CONSTRAINT ck_fare_policy_distance_unit
        CHECK (distance_unit_meters > 0),
    CONSTRAINT ck_fare_policy_distance_fare
        CHECK (distance_unit_fare > 0),
    CONSTRAINT ck_fare_policy_max_distance
        CHECK (max_delivery_distance_meters > 0),
    CONSTRAINT ck_fare_policy_effective_period
        CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '기본요금 및 거리 단가 정책';
```

- [ ] **Step 2: Write V9 migration**

Create `backend/src/main/resources/db/migration/V9__create_item_type_surcharge.sql`:

```sql
-- 요금 정책 버전별 물품 종류 할증. 물품 크기·무게·수량 기준은 사용하지 않는다.
-- (fare_policy_id, item_type) 유니크로 정책 버전당 물품 종류별 할증을 1건으로 제한한다.

CREATE TABLE item_type_surcharge (
    item_type_surcharge_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    fare_policy_id BIGINT UNSIGNED NOT NULL,
    item_type VARCHAR(30) NOT NULL,
    surcharge_amount BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_item_type_surcharge PRIMARY KEY (item_type_surcharge_id),
    CONSTRAINT uk_item_surcharge_policy_type
        UNIQUE (fare_policy_id, item_type),
    CONSTRAINT fk_item_surcharge_policy
        FOREIGN KEY (fare_policy_id) REFERENCES fare_policy (fare_policy_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '요금 정책별 물품 종류 할증';
```

- [ ] **Step 3: 기존 로컬 H2 데이터 파일 삭제**

`backend/data/`에 이전 실행에서 만든 H2 파일이 남아 있으면 Flyway 이력이 섞인다. 삭제 후 새로 만든다(이 디렉터리는 `.gitignore`에 등록되어 있어 커밋 대상이 아니다).

Run (from `backend/`): `rm -rf data`

- [ ] **Step 4: 로컬 프로파일로 앱을 기동해 Flyway + Hibernate validate 통과 실증**

Run (from `backend/`): `./gradlew bootRun --args='--spring.profiles.active=local'`

Expected 로그:
- `Migrating schema "PUBLIC" to version "8 - create fare policy"`
- `Migrating schema "PUBLIC" to version "9 - create item type surcharge"`
- `Started QuickApplication in ...` (Hibernate `ddl-auto: validate` 가 통과했다는 뜻 — 매핑 불일치가 있으면 `SchemaManagementException` 으로 기동이 실패한다)

기동 확인 후 Ctrl+C 로 종료한다.

**만약 `active_policy_marker` 생성 컬럼에서 실패한다면** (H2 MySQL 호환 모드가 `GENERATED ALWAYS AS ... VIRTUAL` 문법이나 생성 컬럼의 UNIQUE 를 지원하지 않는 경우):
- 마이그레이션 SQL 을 우회 수정하지 말 것. MySQL 이 정본이고 이 컬럼은 활성 정책 1건 제약의 핵심이다.
- 실패한 정확한 예외 메시지와 H2 버전을 이슈 #142 에 코멘트로 남기고, 2단계(#143 `DeliveryOrder`, VIRTUAL 컬럼 2개 사용) 착수 전에 팀과 로컬 검증 방식을 합의한다.

- [ ] **Step 5: 전체 테스트 실행**

Run (from `backend/`): `./gradlew test`
Expected: 기존 테스트 포함 전부 PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V8__create_fare_policy.sql backend/src/main/resources/db/migration/V9__create_item_type_surcharge.sql
git commit -m "feat: 요금 정책·물품 할증 테이블 마이그레이션 추가 (#142)"
```

---

### Task 5: PR 생성

**Files:** 없음(코드 변경 없음)

- [ ] **Step 1: 브랜치 푸시**

```bash
git push -u origin feature/142-fare-policy-entities
```

- [ ] **Step 2: PR 생성**

PR 본문에는 팀 규칙에 따라 변경 내용·선택 이유·테스트 결과·집중 리뷰 영역을 포함하고, 본문에 `Closes #142`를 넣는다. 집중 리뷰 영역으로는 최소한 다음을 명시한다:
- `active_policy_marker` VIRTUAL 컬럼 + UNIQUE 로 "활성 정책 1건"을 강제하는 방식이 적절한지
- `FarePolicy`를 애그리거트 루트로 두고 `ItemTypeSurcharge` 생성을 package-private 으로 제한한 설계

---

## 다음 단계 예고

이 계획이 끝나면 [#143](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/143) `DeliveryOrder`가 이어진다. 마이그레이션 번호는 V10부터 시작하고, 이 계획의 Task 4에서 검증한 VIRTUAL 생성 컬럼 결과를 전제로 `active_customer_id`/`active_rider_id`를 도입한다.
