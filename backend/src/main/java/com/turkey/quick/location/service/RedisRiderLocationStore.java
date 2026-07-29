package com.turkey.quick.location.service;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 라이더 최신 위치의 Redis 구현.
 *
 * <p>{@code RedisSessionStore} 와 달리 Hash(HSET + EXPIRE)를 쓰지 않고, 네 필드를 한 문자열로
 * 합쳐 {@code SET key value EX} 단일 명령으로 쓴다. 세션은 로그인당 1회라 HSET 과 EXPIRE 사이의
 * 짧은 창을 감내할 수 있지만, 위치는 라이더당 수 초에 한 번 갱신된다. 그 사이에 프로세스가
 * 죽으면 <b>TTL 없는 키</b>가 남고, 그 라이더가 다시 위치를 보내지 않으면 만료되지 않은 옛 좌표가
 * 영구히 배차 후보에 잡힌다. SET+EX 는 값과 TTL 이 원자적으로 함께 걸려 그 창이 없다.
 *
 * <p>값 형식은 {@code latitude,longitude,measuredAt,accuracyMeters} 이고 정확도가 없으면 마지막
 * 조각을 비운다. 구분자로 쉼표를 쓰는 이유는 정규식 메타문자가 아니어서다 —
 * {@code String.split} 의 인자는 정규식이라 {@code |} 같은 문자를 쓰면 escape 를 빠뜨리는 순간
 * 문자 단위로 쪼개진다. 필드가 늘어나면 JSON 으로 바꾸는 것을 검토한다.
 *
 * <p>{@code encode}/{@code decode} 를 static package-private 로 둔 이유: 인스턴스 상태를 쓰지
 * 않고, {@code StringRedisTemplate} 를 목킹하지 않고 인코딩 왕복만 단위 테스트하기 위해서다.
 * 이 클래스에서 실제로 깨지는 건 Redis 호출이 아니라 문자열 형식이다.
 */
@Component
public class RedisRiderLocationStore implements RiderLocationStore {

    private static final String KEY_FORMAT = "rider:location:%d";
    private static final String DELIMITER = ",";
    private static final int FIELD_COUNT = 4;

    /**
     * 최신 위치 유효 시간. 프론트가 정지 상태에서도 120초마다 강제 전송하므로(#196 확정) 정상
     * 운행 중에는 만료되지 않고, 연결이 끊긴 라이더는 배차 후보에서 자연히 빠진다.
     */
    private static final Duration LATEST_LOCATION_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public RedisRiderLocationStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(Long riderId, RiderLocationSnapshot location) {
        redisTemplate.opsForValue().set(key(riderId), encode(location), LATEST_LOCATION_TTL);
    }

    @Override
    public Optional<RiderLocationSnapshot> find(Long riderId) {
        // 키가 없으면 get 이 null 을 준다. 연결 실패는 null 이 아니라 예외로 오므로 여기서
        // 삼키지 않는다 — Redis 장애를 "위치 없음"으로 위장하면 필터(#82)가 모든 좌표를
        // "최초 위치"로 보고 통과시킨다.
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(riderId)))
                .flatMap(RedisRiderLocationStore::decode);
    }

    private static String key(Long riderId) {
        return KEY_FORMAT.formatted(riderId);
    }

    static String encode(RiderLocationSnapshot location) {
        return String.join(DELIMITER,
                location.latitude().toString(),
                location.longitude().toString(),
                location.measuredAt().toString(),
                location.accuracyMeters() == null ? "" : location.accuracyMeters().toString());
    }

    /** {@link #encode} 의 역함수. 형식이 어긋나면 예외가 아니라 빈 결과다. */
    static Optional<RiderLocationSnapshot> decode(String raw) {
        // limit -1: 정확도가 없으면 마지막 조각이 비는데, 기본값(0)은 그 빈 조각을 잘라내
        // 필드 수가 3개로 세어진다.
        String[] parts = raw.split(DELIMITER, -1);
        if (parts.length != FIELD_COUNT) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RiderLocationSnapshot(
                    new BigDecimal(parts[0]),
                    new BigDecimal(parts[1]),
                    LocalDateTime.parse(parts[2]),
                    parts[3].isEmpty() ? null : new BigDecimal(parts[3])
            ));
        } catch (NumberFormatException | DateTimeException e) {
            return Optional.empty();
        }
    }
}
