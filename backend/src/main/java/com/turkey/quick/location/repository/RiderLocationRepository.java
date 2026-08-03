package com.turkey.quick.location.repository;

import com.turkey.quick.location.dto.LocationPayload;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * 라이더 최신 위치의 Redis 저장소(#317).
 *
 * <p>{@code RedisSessionStore} 와 달리 Hash(HSET + EXPIRE)를 쓰지 않고, 네 필드를 한 문자열로
 * 합쳐 값과 TTL 을 한 명령으로 쓴다. 세션은 로그인당 1회라 HSET 과 EXPIRE 사이의 짧은 창을 감내할
 * 수 있지만, 위치는 라이더당 수 초에 한 번 갱신된다. 그 사이에 프로세스가 죽으면 <b>TTL 없는
 * 키</b>가 남고, 그 라이더가 다시 위치를 보내지 않으면 옛 좌표가 영구히 남는다.
 * {@code SET ... EX} 는 값과 TTL 이 원자적으로 함께 걸려 그 창이 없다.
 *
 * <p><b>지금 이 값을 읽는 코드는 없다</b>(#317 범위). 소비자 후보는 #311 폴링 arm 과 SSE
 * {@code init} 스냅샷이고, 그 이슈에서 {@code find}/{@code decode} 를 추가한다 — 호출자 없는
 * getter 를 미리 두지 않았다. 그때까지 형식 계약을 지키는 것은 {@link #encode} 단위 테스트와
 * 실제 Redis 통합 테스트다.
 *
 * <p>{@code RiderGeoRepository} 와 같은 패키지·같은 관례다(인터페이스 없이 구현체 하나,
 * 자동설정 {@code StringRedisTemplate} 주입). 인메모리 대체를 두지 않은 이유: 이 클래스에서 검증할
 * 가치가 있는 것은 Lua 의 원자성인데 그건 인메모리 구현으로 재현할 수 없다.
 */
@Repository
public class RiderLocationRepository {

    private static final String KEY_FORMAT = "rider:location:%d";
    private static final String DELIMITER = ",";

    /**
     * 측정 시각의 인코딩 형식. <b>항상 23자</b>여야 한다 — 아래 Lua 스크립트가 그 전제로 값의 앞을
     * 잘라 비교한다. {@code SSS} 로 밀리초를 고정하는 것은 {@code rider_location_history.measured_at}
     * 이 {@code DATETIME(3)} 인 것과도 일치한다.
     *
     * <p>{@code yyyy}(연대 기준 연도)가 아니라 {@code uuuu}(proleptic year)를 쓰는 이유는 연대
     * 표기 없이 연도만 다루기 때문이다. 서기 10000년 이후에는 폭이 깨지지만, TTL 10분짜리 값에
     * 대해 따질 문제가 아니다.
     *
     * <p>{@code Instant} 는 시간대가 없어 포맷터에 UTC 를 못 박는다. 사전순 비교가 시간순과 일치해야
     * 하므로 <b>저장 시각의 시간대는 반드시 고정</b>돼야 한다 — 인스턴스마다 기본 시간대가 다르면
     * 같은 순간이 다른 문자열로 저장돼 비교가 무의미해진다.
     */
    private static final DateTimeFormatter MEASURED_AT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    /**
     * 저장된 값보다 최신일 때만 쓰는 조건부 갱신. GET → 비교 → SET 이 Redis 서버 안에서 한 번에
     * 실행되므로, 인스턴스가 여러 대여도 그 사이에 다른 요청이 끼어들지 못한다.
     *
     * <p>저장된 값의 앞부분을 <b>정규식으로 검증한 뒤</b> 비교하는 이유: 손상된 값은 새 값으로
     * 덮어야 하는데, 사전순 비교만 하면 {@code "garbage"} 같은 값이 어떤 시각보다도 크게 나와
     * ({@code 'g' > '2'}) 영원히 덮이지 않는다. 패턴이 폭을 함께 정의하므로 {@code #storedAt} 으로
     * 새 값을 자른다 — 23이라는 숫자를 Java 와 Lua 양쪽에 적어 두면 한쪽만 고쳐지는 날이 온다.
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
     * 최신 위치 유효 시간. 프론트가 정지 상태에서도 120초마다 강제 전송하므로(#81 확정) 정상
     * 운행 중에는 만료되지 않고, 연결이 끊긴 라이더의 값은 자연히 사라진다.
     */
    private static final Duration LATEST_LOCATION_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public RiderLocationRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 저장된 값보다 측정 시각이 <b>최신일 때만</b> 최신 위치를 저장하고 TTL 을 다시 건다.
     *
     * <p>무조건 덮어쓰는 연산을 두지 않은 이유: 수평 확장에서 같은 라이더의 요청 둘이 다른
     * 인스턴스에서 처리될 때 <b>오래된 좌표가 최신을 덮는다.</b> 그건 조용히 일어나고(응답은 200)
     * 다음 이벤트가 복구하므로 테스트로도 잘 드러나지 않는다. 애초에 부를 수 없게 두는 편이 낫다.
     *
     * <p>지켜지는 의미론: 저장된 값이 없으면(만료 포함) 무조건 쓴다 / 측정 시각이 <b>같으면</b>
     * 쓰지 않는다(재전송이라 값은 같은데 쓰기만 늘어난다) / 저장된 값이 손상돼 시각을 읽을 수 없으면
     * 새 값으로 덮는다.
     *
     * @return 실제로 저장했는지. false 면 저장된 값이 이 위치보다 최신이거나 같다는 뜻이다
     */
    public boolean saveIfNewer(Long riderId, LocationPayload location) {
        // StringRedisTemplate 이라 인자도 문자열이어야 한다 — 숫자를 그대로 넘기면 직렬화에서 터진다.
        Long written = redisTemplate.execute(SAVE_IF_NEWER, List.of(key(riderId)),
                encode(location), String.valueOf(LATEST_LOCATION_TTL.toSeconds()));
        return Long.valueOf(1L).equals(written);
    }

    private static String key(Long riderId) {
        return KEY_FORMAT.formatted(riderId);
    }

    /**
     * 값 형식은 {@code measuredAt,latitude,longitude,accuracyMeters} 이고 정확도가 없으면 마지막
     * 조각을 비운다. 구분자로 쉼표를 쓰는 이유는 정규식 메타문자가 아니어서다 —
     * {@code String.split} 의 인자는 정규식이라 {@code |} 같은 문자를 쓰면 escape 를 빠뜨리는 순간
     * 문자 단위로 쪼개진다.
     *
     * <p><b>측정 시각이 맨 앞에 있고 고정 폭인 것은 Lua 를 위한 것이다.</b> 조건부 갱신이 Redis
     * 서버 안에서 시각을 비교해야 하는데, 고정 폭이면 Lua 가 앞 23글자를 잘라 사전순으로 비교하기만
     * 하면 되고 필드 분리조차 필요 없다.
     *
     * <p>{@code static} package-private 인 이유: 인스턴스 상태를 쓰지 않고,
     * {@code StringRedisTemplate} 를 목킹하지 않고 형식만 단위 테스트하기 위해서다. 이 클래스에서
     * 실제로 깨지는 건 Redis 호출이 아니라 문자열 형식이다.
     *
     * <p>ponytail: 저장은 쉼표 구분 문자열, 팬아웃({@code TrackingPublisher})은 JSON 으로
     * <b>형식이 둘</b>이다. Lua 가 고정폭 접두어를 잘라 비교하려면 필요한 대가다 — 필드가 늘어나면
     * 저장 쪽도 JSON + Lua {@code cjson} 으로 합치는 것을 검토한다.
     */
    static String encode(LocationPayload location) {
        return String.join(DELIMITER,
                MEASURED_AT_FORMAT.format(location.measuredAt()),
                location.latitude().toString(),
                location.longitude().toString(),
                location.accuracyMeters() == null ? "" : location.accuracyMeters().toString());
    }
}
