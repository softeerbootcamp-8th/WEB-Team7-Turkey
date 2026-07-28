package com.turkey.quick.order.service;

import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FarePolicyStatus;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.ItemTypeSurcharge;
import com.turkey.quick.order.dto.FareBreakdownResponse;
import com.turkey.quick.order.dto.FareQuoteRequest;
import com.turkey.quick.order.dto.FareQuoteResponse;
import com.turkey.quick.order.repository.FarePolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@RequiredArgsConstructor
@Service
public class DeliveryService {

    final double EARTH_RADIUS = 6371.0088;
    final int ESTIMATE_MINUTES_CONSTANT = 600;

    private static final BigDecimal METERS_PER_KM = BigDecimal.valueOf(1000);

    private final FarePolicyRepository farePolicyRepository;

    /**
     * 요금 견적. 활성 정책(fare_policy)과 좌표만으로 계산하며 주문·스냅샷은 만들지 않는다.
     * ItemTypeSurcharge 는 지연 로딩이라 정책 조회와 같은 트랜잭션 안에서 접근해야 한다.
     */
    @Transactional(readOnly = true)
    public FareQuoteResponse quoteFare(FareQuoteRequest request) {
        FarePolicy activePolicy = farePolicyRepository.findByStatus(FarePolicyStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("활성화된 요금 정책이 없습니다."));

        BigDecimal distanceKm = distance(
                request.pickupAddress().latitude(), request.pickupAddress().longitude(),
                request.destinationAddress().latitude(), request.destinationAddress().longitude());
        int distanceMeters = toMeters(distanceKm);

        if (distanceMeters > activePolicy.getMaxDeliveryDistanceMeters()) {
            throw new IllegalArgumentException(
                    "최대 배송 거리를 초과했습니다. distanceMeters=" + distanceMeters
                            + ", maxDeliveryDistanceMeters=" + activePolicy.getMaxDeliveryDistanceMeters());
        }

        FareBreakdownResponse fare = calculateFare(activePolicy, request.itemType(), distanceMeters);

        int estimateMinutes = estimateMinutes(distanceMeters);
        return new FareQuoteResponse(fare, estimateMinutes);
    }

    public int estimateMinutes(int distanceMeters) {
        return (int) Math.ceil((double) distanceMeters / ESTIMATE_MINUTES_CONSTANT);
    }

    /**
     * base + distance + surcharge 를 계산한다. total 은 항상 세 값의 합으로 파생시켜
     * {@link com.turkey.quick.order.domain.OrderFareSnapshot} 의 ck_order_fare_total 불변식과
     * 어긋나지 않게 한다.
     *
     * <p>거리 운임은 distanceUnitMeters 로 나눈 몫을 올림해 단위 수를 구한다(부분 구간도 한 단위로
     * 청구) — 택시 미터기와 같은 방식이다. 해당 물품 종류에 등록된 할증이 없으면 0으로 취급한다
     * (모든 ItemType 이 할증을 가질 필요는 없다).
     */
    private FareBreakdownResponse calculateFare(FarePolicy policy, ItemType itemType, int distanceMeters) {
        long baseFare = policy.getBaseFare();
        long distanceUnits = Math.ceilDiv(distanceMeters, policy.getDistanceUnitMeters());
        long distanceFare = distanceUnits * policy.getDistanceUnitFare();
        long itemSurcharge = policy.getSurcharges().stream()
                .filter(surcharge -> surcharge.getItemType() == itemType)
                .findFirst()
                .map(ItemTypeSurcharge::getSurchargeAmount)
                .orElse(0L);
        long totalFare = baseFare + distanceFare + itemSurcharge;

        return new FareBreakdownResponse(
                policy.getPolicyVersion(), distanceMeters, baseFare, distanceFare, itemSurcharge, totalFare);
    }

    /**
     * km(BigDecimal) 을 정수 미터로 반올림한다. Address.normalize 와 동일하게 HALF_UP 을 쓴다
     * (이 저장소의 좌표·거리 반올림 관례).
     */
    private int toMeters(BigDecimal distanceKm) {
        return distanceKm.multiply(METERS_PER_KM).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }


    /**
     * 두 좌표 간의 지구 표면 기준 직선(구면) 거리를 계산합니다.
     *
     * <p><strong>사용주의 사항</strong></p>
     * <ul>
     *   <li><strong>하버사인 공식(Haversine Formula) 적용:</strong> 평면상의 단순 피타고라스 직선거리는
     *       지구가 구체라는 공간적 특성을 반영하지 못해 실제 거리와 오차가 심하므로 구면 기하학을 고려한 공식을 적용했습니다.</li>
     *   <li><strong>내부 double 변환 사유:</strong> 자바 표준 {@link java.lang.Math} 라이브러리의 삼각함수
     *       메서드들이 {@code double} 타입만을 지원하기 때문에 내부 연산 시 일시적으로 형변환을 수행.</li>
     *   <li><strong>오차 정밀도 검토:</strong> 본 시스템은 단순 직선거리 기반의 대략적인 운임 측정을 목적으로 하므로,
     *       부동소수점({@code double}) 변환 과정에서 발생하는 미세한 연산 오차는 비즈니스 레이어에서 크리티컬하지 않다고 생각</li>
     * </ul>
     *
     * @param latitude1  출발지 위도 (BigDecimal)
     * @param longitude1 출발지 경도 (BigDecimal)
     * @param latitude2  도착지 위도 (BigDecimal)
     * @param longitude2 도착지 경도 (BigDecimal)
     * @return 하버사인 공식을 통해 산정된 최종 직선거리 (단위: km, 소수점 3자리 반올림)
     */
    public BigDecimal distance(BigDecimal latitude1, BigDecimal longitude1, BigDecimal latitude2, BigDecimal longitude2) {
        if (latitude1 == null || longitude1 == null || latitude2 == null || longitude2 == null) {
            throw new IllegalArgumentException("좌표 값은 필수여야 합니다.");
        }

        double lat1Rad = Math.toRadians(latitude1.doubleValue());
        double lon1Rad = Math.toRadians(longitude1.doubleValue());
        double lat2Rad = Math.toRadians(latitude2.doubleValue());
        double lon2Rad = Math.toRadians(longitude2.doubleValue());

        double deltaLat = lat2Rad - lat1Rad;
        double deltaLon = lon2Rad - lon1Rad;

        // 하버사인 연산
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                    Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                    Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        double distanceKm = EARTH_RADIUS * c;

        return BigDecimal.valueOf(distanceKm).setScale(8, RoundingMode.HALF_UP);
    }


    @Deprecated
    public BigDecimal pureStraightDistance(BigDecimal latitude1, BigDecimal longitude1, BigDecimal latitude2, BigDecimal longitude2) {
        if (latitude1 == null || longitude1 == null || latitude2 == null || longitude2 == null) {
            throw new IllegalArgumentException("좌표 값은 필수여야 합니다.");
        }

        // *** 대한민국 위도(약 37도) 기준 1도당 실제 거리 상수 km 단위
        // 위도 1도 = 약 111km, 경도 1도 = 약 88.5km (위도에 따라 가변적이나 평면 가정 선언)
        final double KM_PER_LATITUDE = 111.0;
        final double KM_PER_LONGITUDE = 88.5;

        double lat1 = latitude1.doubleValue();
        double lon1 = longitude1.doubleValue();
        double lat2 = latitude2.doubleValue();
        double lon2 = longitude2.doubleValue();

        // 2. 위·경도 차이를 km 단위 거리로 환산
        double x = (lon2 - lon1) * KM_PER_LONGITUDE;
        double y = (lat2 - lat1) * KM_PER_LATITUDE;

        // 3. 피타고라스 공식
        double distanceKm = Math.sqrt((x * x) + (y * y));

        return BigDecimal.valueOf(distanceKm).setScale(8, RoundingMode.HALF_UP);
    }
}
