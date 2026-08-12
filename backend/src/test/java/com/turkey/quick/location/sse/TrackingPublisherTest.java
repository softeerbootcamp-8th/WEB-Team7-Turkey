package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("TrackingPublisher")
class TrackingPublisherTest {

    private static final LocationPayload LOCATION = new LocationPayload(
            new BigDecimal("37.4979"), new BigDecimal("127.0276"),
            Instant.parse("2026-08-03T01:02:03.456Z"), new BigDecimal("12.5"));

    @Mock
    private StringRedisTemplate redisTemplate;

    /**
     * 스프링 부트가 구성하는 {@code ObjectMapper} 와 같게 맞춘다 —
     * {@code WRITE_DATES_AS_TIMESTAMPS} 가 켜져 있으면 {@code Instant} 가 <b>숫자</b>로 나가고,
     * 프론트 {@code parseLocationPing} 은 {@code measuredAt} 이 문자열이 아니면 프레임을 버린다.
     * 기본값으로 두면 이 테스트가 운영과 다른 형식을 검증하게 된다.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private TrackingPublisher publisher() {
        return new TrackingPublisher(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("배송 채널로 직렬화된 위치를 발행한다")
    void publishesSerializedLocationToDeliveryChannel() {
        publisher().publish(1024L, LOCATION);

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        then(redisTemplate).should().convertAndSend(channel.capture(), body.capture());

        assertThat(channel.getValue()).isEqualTo("tracking:order:1024");
        // 구독자는 이 문자열을 파싱하지 않고 SSE data: 로 그대로 흘린다. 그래서 프론트
        // parseLocationPing 이 요구하는 형식이 여기서 결정된다 — measuredAt 은 반드시 문자열이다.
        assertThat((String) body.getValue())
                .contains("\"type\":\"location\"")
                .contains("\"latitude\":37.4979")
                .contains("\"longitude\":127.0276")
                .contains("\"measuredAt\":\"2026-08-03T01:02:03.456Z\"")
                .contains("\"accuracyMeters\":12.5");
    }

    @Test
    @DisplayName("Redis 발행이 실패해도 예외를 밖으로 내지 않는다")
    void swallowsRedisFailure() {
        // 이 호출은 라이더 위치 갱신 POST 경로에 있다. 여기서 예외가 올라가면 Redis 장애가
        // 고객 추적을 넘어 배차 후보 갱신(#83)까지 같이 죽인다.
        willThrow(new RedisConnectionFailureException("down"))
                .given(redisTemplate).convertAndSend(anyString(), anyString());

        assertThatCode(() -> publisher().publish(1L, LOCATION)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 배송 채널로 직렬화된 상태 전이를 발행한다(#398)")
    void publishesSerializedStatusToDeliveryChannel() {
        publisher().publishStatus(1024L, OrderStatus.PICKED_UP, Instant.parse("2026-08-03T01:02:03.456Z"));

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        then(redisTemplate).should().convertAndSend(channel.capture(), body.capture());

        assertThat(channel.getValue()).isEqualTo("tracking:order:1024");
        assertThat((String) body.getValue())
                .contains("\"type\":\"status\"")
                .contains("\"status\":\"PICKED_UP\"")
                .contains("\"occurredAt\":\"2026-08-03T01:02:03.456Z\"");
    }

    @Test
    @DisplayName("상태 발행도 Redis 실패 시 예외를 밖으로 내지 않는다")
    void swallowsRedisFailureForStatus() {
        willThrow(new RedisConnectionFailureException("down"))
                .given(redisTemplate).convertAndSend(anyString(), anyString());

        assertThatCode(() -> publisher().publishStatus(1L, OrderStatus.COMPLETED, Instant.now()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("트랜잭션 안에서는 커밋 전까지 상태 발행을 미룬다 — 이른 재조회 경쟁 방지")
    void deferStatusPublishUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher().publishStatus(1024L, OrderStatus.COMPLETED,
                    Instant.parse("2026-08-03T01:02:03.456Z"));

            then(redisTemplate).should(never()).convertAndSend(anyString(), anyString());

            TransactionSynchronizationUtils.triggerAfterCommit();

            then(redisTemplate).should().convertAndSend(anyString(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("종료 신호는 데이터와 다른 채널로 발행한다 (#450)")
    void publishesCloseSignalToSeparateChannel() {
        publisher().publishClose(1024L);

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        then(redisTemplate).should().convertAndSend(channel.capture(), anyString());

        assertThat(channel.getValue()).isEqualTo("tracking:close:1024");
    }

    @Test
    @DisplayName("종료 신호도 커밋 전까지 미룬다 — 커밋 전에 닫으면 재연결이 옛 상태를 읽는다 (#450)")
    void defersCloseSignalUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher().publishClose(1024L);

            then(redisTemplate).should(never()).convertAndSend(anyString(), anyString());

            TransactionSynchronizationUtils.triggerAfterCommit();

            then(redisTemplate).should().convertAndSend(anyString(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("종료 신호 발행이 실패해도 예외를 밖으로 내지 않는다 (#450)")
    void swallowsRedisFailureForClose() {
        // 이 호출은 배송 완료 트랜잭션의 커밋 후 콜백에서 실행된다 — 여기서 예외가 올라가면
        // Redis 장애가 커밋 완료 처리에까지 번진다.
        willThrow(new RedisConnectionFailureException("down"))
                .given(redisTemplate).convertAndSend(anyString(), anyString());

        assertThatCode(() -> publisher().publishClose(1L)).doesNotThrowAnyException();
    }
}
