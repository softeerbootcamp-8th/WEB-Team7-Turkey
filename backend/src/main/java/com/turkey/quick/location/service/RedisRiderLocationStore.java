package com.turkey.quick.location.service;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 라이더 최신 위치의 Redis 구현.
 *
 * <p>{@code RedisSessionStore} 와 달리 Hash(HSET + EXPIRE)를 쓰지 않고, 네 필드를 한 문자열로
 * 합쳐 값과 TTL 을 한 명령으로 쓴다. 세션은 로그인당 1회라 HSET 과 EXPIRE 사이의 짧은 창을 감내할
 * 수 있지만, 위치는 라이더당 수 초에 한 번 갱신된다. 그 사이에 프로세스가 죽으면 <b>TTL 없는
 * 키</b>가 남고, 그 라이더가 다시 위치를 보내지 않으면 만료되지 않은 옛 좌표가 영구히 배차 후보에
 * 잡힌다. {@code SET ... EX} 는 값과 TTL 이 원자적으로 함께 걸려 그 창이 없다.
 *
 * <p>값 형식은 {@code measuredAt,latitude,longitude,accuracyMeters} 이고 정확도가 없으면 마지막
 * 조각을 비운다. 구분자로 쉼표를 쓰는 이유는 정규식 메타문자가 아니어서다 —
 * {@code String.split} 의 인자는 정규식이라 {@code |} 같은 문자를 쓰면 escape 를 빠뜨리는 순간
 * 문자 단위로 쪼개진다. 필드가 늘어나면 JSON 으로 바꾸는 것을 검토한다.
 *
 * <p><b>측정 시각이 맨 앞에 있고 고정 폭인 것은 Lua 를 위한 것이다</b>(#250). 조건부 갱신이
 * Redis 서버 안에서 시각을 비교해야 하는데, 고정 폭이면 Lua 가 앞 23글자를 잘라 사전순으로
 * 비교하기만 하면 되고 필드 분리조차 필요 없다. 이전 형식은 {@code LocalDateTime#toString} 이라
 * <b>길이가 가변이었다</b> — 초가 0이면 {@code :00} 을 생략하고 나노초 자릿수도 0·3·6·9 로
 * 달라져서, 사전순 비교가 시간순과 일치하는지가 미묘한 포맷 규칙에 달려 있었다.
 *
 * <p>epoch 밀리초 대신 ISO 문자열을 쓰는 이유: {@code LocalDateTime} 은 시간대가 없어 epoch 으로
 * 바꾸려면 시간대를 가정해야 하고(그 가정이 어긋나면 9시간 틀어진 값이 조용히 저장된다),
 * {@code redis-cli} 로 값을 봤을 때 사람이 읽을 수 없다. 고정 폭과 사전순 정합은 둘 다 만족한다.
 *
 * <p>{@code encode}/{@code decode} 를 static package-private 로 둔 이유: 인스턴스 상태를 쓰지
 * 않고, {@code StringRedisTemplate} 를 목킹하지 않고 인코딩 왕복만 단위 테스트하기 위해서다.
 * 이 클래스에서 실제로 깨지는 건 Redis 호출이 아니라 문자열 형식이다. 다만 Lua 스크립트의 실제
 * 원자성은 형식 테스트로 알 수 없어 실제 Redis 통합 테스트가 따로 있다
 * ({@code RedisRiderLocationStoreIntegrationTest}).
 */
@Component
public class RedisRiderLocationStore implements RiderLocationStore {

    private static final String KEY_FORMAT = "rider:location:%d";
    private static final String DELIMITER = ",";
    private static final int FIELD_COUNT = 4;

    /**
     * 측정 시각의 인코딩 형식. <b>항상 23자</b>여야 한다 — 아래 Lua 스크립트가 그 전제로 값의 앞을
     * 잘라 비교한다. {@code SSS} 로 밀리초를 고정하는 것은 {@code rider_location_history.measured_at}
     * 이 {@code DATETIME(3)} 인 것과도 일치한다.
     *
     * <p>{@code yyyy}(연대 기준 연도)가 아니라 {@code uuuu}(proleptic year)를 쓰는 이유는 연대
     * 표기 없이 연도만 다루기 때문이다. 서기 10000년 이후에는 폭이 깨지지만, TTL 10분짜리 값에
     * 대해 따질 문제가 아니다.
     */
    private static final DateTimeFormatter MEASURED_AT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");

    /**
     * 저장된 값보다 최신일 때만 쓰는 조건부 갱신. GET → 비교 → SET 이 Redis 서버 안에서 한 번에
     * 실행되므로, 인스턴스가 여러 대여도 그 사이에 다른 요청이 끼어들지 못한다.
     *
     * <p>저장된 값의 앞부분을 <b>정규식으로 검증한 뒤</b> 비교하는 이유: 손상된 값은 새 값으로
     * 덮어야 하는데({@link RiderLocationStore#saveIfNewer} 계약), 사전순 비교만 하면
     * {@code "garbage"} 같은 값이 어떤 시각보다도 크게 나와({@code 'g' > '2'}) 영원히 덮이지
     * 않는다. 패턴이 폭을 함께 정의하므로 {@code #storedAt} 으로 새 값을 자른다 — 23이라는 숫자를
     * Java 와 Lua 양쪽에 적어 두면 한쪽만 고쳐지는 날이 온다.
     *
     * <p>{@code TIME} 을 부르지 않는다. 이 스크립트는 저장된 값과 인자만 보고 결정하므로 결정적
     * (deterministic)이고, 복제·AOF 에 안전하다.
     */
    private static final RedisScript<Long> SAVE_IF_NEWER = RedisScript.of("""
            local stored = redis.call('GET', KEYS[1])
            if stored then
              local storedAt = string.match(stored, '^%d%d%d%d%-%d%d%-%d%dT%d%d:%d%d:%d%d%.%d%d%d')
              if storedAt and storedAt >= string.sub(ARGV[1], 1, #storedAt) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return 1
            """, Long.class);

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
    public boolean saveIfNewer(Long riderId, RiderLocationSnapshot location) {
        // StringRedisTemplate 이라 인자도 문자열이어야 한다 — 숫자를 그대로 넘기면 직렬화에서 터진다.
        Long written = redisTemplate.execute(SAVE_IF_NEWER, List.of(key(riderId)),
                encode(location), String.valueOf(LATEST_LOCATION_TTL.toSeconds()));
        return Long.valueOf(1L).equals(written);
    }

    @Override
    public Optional<RiderLocationSnapshot> find(Long riderId) {
        // 키가 없으면 get 이 null 을 준다. 연결 실패는 null 이 아니라 예외로 오므로 여기서
        // 삼키지 않는다 — Redis 장애를 "위치 없음"으로 위장하면 필터(#82)가 모든 좌표를
        // "최초 위치"로 보고 통과시킨다.
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(riderId)))
                .flatMap(RedisRiderLocationStore::decode);
    }

    @Override
    public boolean refreshTtl(Long riderId) {
        // EXPIRE 는 키가 없으면 0(false)을 돌려준다 — 값을 새로 만들지 않는다.
        //
        // 여기에는 조건부 갱신을 두지 않았다(#250 사람 확인). EXPIRE 는 값을 건드리지 않으므로
        // "오래된 좌표가 최신을 덮는" 문제 자체가 없고, 남는 것은 find 와 이 호출 사이에 TTL 이
        // 만료되는 틈뿐이다. 그 경우 false 를 받고 다음 갱신이 최초 좌표로 복구한다.
        return Boolean.TRUE.equals(redisTemplate.expire(key(riderId), LATEST_LOCATION_TTL));
    }

    private static String key(Long riderId) {
        return KEY_FORMAT.formatted(riderId);
    }

    static String encode(RiderLocationSnapshot location) {
        return String.join(DELIMITER,
                MEASURED_AT_FORMAT.format(location.measuredAt()),
                location.latitude().toString(),
                location.longitude().toString(),
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
                    new BigDecimal(parts[1]),
                    new BigDecimal(parts[2]),
                    LocalDateTime.parse(parts[0], MEASURED_AT_FORMAT),
                    parts[3].isEmpty() ? null : new BigDecimal(parts[3])
            ));
        } catch (NumberFormatException | DateTimeException e) {
            return Optional.empty();
        }
    }
}
