package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TrackingChannel")
class TrackingChannelTest {

    @Nested
    @DisplayName("채널명")
    class Naming {

        @Test
        @DisplayName("배송 식별자로 채널명을 만든다")
        void buildsChannelName() {
            // 채널명이 곧 스키마다. 접두사가 바뀌면 롤링 배포 중 구·신 버전이 서로 다른 채널을
            // 써서 그 구간의 이벤트가 전달되지 않으므로, 형식 자체를 못 박아 둔다.
            assertThat(TrackingChannel.of(1024L)).isEqualTo("tracking:order:1024");
        }

        @Test
        @DisplayName("만든 채널명에서 배송 식별자를 되꺼낸다")
        void roundTripsDeliveryId() {
            assertThat(TrackingChannel.deliveryIdOf(TrackingChannel.of(1024L))).contains(1024L);
        }

        @Test
        @DisplayName("구독 패턴은 모든 배송 채널에 걸린다")
        void patternMatchesEveryDeliveryChannel() {
            // 이 값이 어긋나면 구독은 성공하지만 어떤 이벤트도 도착하지 않는다 — 조용한 실패라
            // 형식을 고정해 둔다.
            assertThat(TrackingChannel.pattern()).isEqualTo("tracking:order:*");
            assertThat(TrackingChannel.of(7L)).startsWith(TrackingChannel.pattern().replace("*", ""));
        }
    }

    @Nested
    @DisplayName("잘못된 채널명")
    class Malformed {

        @Test
        @DisplayName("다른 접두사는 빈 결과다")
        void returnsEmptyForOtherPrefix() {
            // 예외를 던지면 리스너 스레드에서 올라가 컨테이너가 삼키고, 그 메시지의 나머지
            // 수신자까지 잃는다.
            assertThat(TrackingChannel.deliveryIdOf("rider:location:1024")).isEmpty();
        }

        @Test
        @DisplayName("숫자가 아닌 뒷부분은 빈 결과다")
        void returnsEmptyForNonNumericSuffix() {
            assertThat(TrackingChannel.deliveryIdOf("tracking:order:abc")).isEmpty();
        }

        @Test
        @DisplayName("뒷부분이 비어도 빈 결과다")
        void returnsEmptyForMissingSuffix() {
            assertThat(TrackingChannel.deliveryIdOf("tracking:order:")).isEmpty();
        }

        @Test
        @DisplayName("null 이어도 예외가 아니라 빈 결과다")
        void returnsEmptyForNull() {
            assertThat(TrackingChannel.deliveryIdOf(null)).isEmpty();
        }
    }
}
