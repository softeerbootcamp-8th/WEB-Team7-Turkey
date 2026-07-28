package com.turkey.quick.order.service;

import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FarePolicyStatus;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.dto.AddressRequest;
import com.turkey.quick.order.dto.FareQuoteRequest;
import com.turkey.quick.order.dto.FareQuoteResponse;
import com.turkey.quick.order.repository.FarePolicyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("배송 운임 및 시간 계산 서비스")
class DeliveryServiceTest {

    @InjectMocks
    private DeliveryService deliveryService;

    @Mock
    private FarePolicyRepository farePolicyRepository;

    //  픽스 데이터를 상수로 분리
    private static final AddressRequest YANGJAE_STATION = new AddressRequest(
            "양재역", "상세", "54299",
            new BigDecimal("37.4909375"), new BigDecimal("127.0334375"));

    private static final AddressRequest SINSA_STATION = new AddressRequest(
            "신사역", "상세", "54299",
            new BigDecimal("37.5141299"), new BigDecimal("127.0291812"));

    private static final AddressRequest JEJU_AIRPORT = new AddressRequest(
            "제주국제공항", "제주 공항로 2", "63115",
            new BigDecimal("33.510413"), new BigDecimal("126.491353"));

    @Nested
    @DisplayName("거리 계산 알고리즘 검증")
    class DistanceAlgorithmTest {

        @Test
        @DisplayName("하버사인 공식은 평면 피타고라스 공식보다 실측 지도 거리에 더 가까워야 한다")
        void haversineShouldBeMoreAccurateThanPythagoras() {
            // given
            BigDecimal naverRealDistance = new BigDecimal("451.0"); // 네이버 지도 실측 451.0

            // when
            BigDecimal haversine = deliveryService.distance(
                    YANGJAE_STATION.latitude(), YANGJAE_STATION.longitude(),
                    JEJU_AIRPORT.latitude(), JEJU_AIRPORT.longitude()
            );
            BigDecimal pythagoras = deliveryService.pureStraightDistance(
                    YANGJAE_STATION.latitude(), YANGJAE_STATION.longitude(),
                    JEJU_AIRPORT.latitude(), JEJU_AIRPORT.longitude()
            );

            // then
            BigDecimal haversineDiff = haversine.subtract(naverRealDistance).abs();
            BigDecimal pythagorasDiff = pythagoras.subtract(naverRealDistance).abs();

            assertThat(haversineDiff)
                    .as("하버사인 오차가 피타고라스 오차보다 작아야 함")
                    .isLessThan(pythagorasDiff);
        }
    }

    @Nested
    @DisplayName("예상 운임 및 소요시간 견적")
    class QuoteFareTest {

        @Test
        @DisplayName("활성화된 운임 정책(기본 1.5km 1만원, 100m당 140원)에 따라 정확한 요금을 산정한다")
        void shouldCalculateCorrectFareUsingActivePolicy() {
            // given
            FarePolicy activePolicy = createMockFarePolicy();
            given(farePolicyRepository.findByStatus(FarePolicyStatus.ACTIVE))
                    .willReturn(Optional.of(activePolicy));

            FareQuoteRequest request = new FareQuoteRequest(ItemType.DOCUMENT, YANGJAE_STATION, SINSA_STATION);

            // when
            FareQuoteResponse response = deliveryService.quoteFare(request);

            // then
            assertThat(response.estimatedMinutes()).isPositive();
            assertThat(response.fare().totalFare())
                    .as("기본요금 및 거리 요금 합산 검증")
                    .isEqualTo(5010L);
        }
    }

    private FarePolicy createMockFarePolicy() {
        return FarePolicy.create(
                "1.0",
                1500L,        // 기본 거리 (1.5km)
                100,          // 추가 거리 단위 (100m)
                130L,         // 단위당 추가 요금 (130원)
                30000,        // 제한 무게
                LocalDateTime.now()
        );
    }
}
