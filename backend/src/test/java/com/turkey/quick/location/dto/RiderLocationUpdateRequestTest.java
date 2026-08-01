package com.turkey.quick.location.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RiderLocationUpdateRequest")
class RiderLocationUpdateRequestTest {

    private static final Instant MEASURED_AT = Instant.parse("2026-07-29T12:34:56.789Z");

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    private static RiderLocationUpdateRequest request(String latitude, String longitude, String accuracy) {
        return new RiderLocationUpdateRequest(
                latitude == null ? null : new BigDecimal(latitude),
                longitude == null ? null : new BigDecimal(longitude),
                MEASURED_AT,
                accuracy == null ? null : new BigDecimal(accuracy));
    }

    /** 위반이 걸린 필드 이름만 뽑는다. 메시지 문구가 아니라 "어느 필드가 걸렸는가"를 단언한다. */
    private static Set<String> violatedFields(RiderLocationUpdateRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Nested
    @DisplayName("400 으로 끊는 입력")
    class ClientErrors {

        @Test
        @DisplayName("정상 요청은 위반이 없다")
        void acceptsValidRequest() {
            assertThat(violatedFields(request("37.4979", "127.0276", "12.5"))).isEmpty();
        }

        @Test
        @DisplayName("좌표는 필수다")
        void requiresCoordinates() {
            assertThat(violatedFields(request(null, null, null)))
                    .containsExactlyInAnyOrder("latitude", "longitude");
        }

        @Test
        @DisplayName("측정 시각은 필수다")
        void requiresMeasuredAt() {
            var missing = new RiderLocationUpdateRequest(
                    new BigDecimal("37.4979"), new BigDecimal("127.0276"), null, null);

            assertThat(violatedFields(missing)).containsExactly("measuredAt");
        }

        @Test
        @DisplayName("위도가 범위를 벗어나면 위반이다")
        void rejectsLatitudeOutOfRange() {
            assertThat(violatedFields(request("90.1", "127.0276", null))).containsExactly("latitude");
            assertThat(violatedFields(request("-90.1", "127.0276", null))).containsExactly("latitude");
        }

        @Test
        @DisplayName("경도가 범위를 벗어나면 위반이다")
        void rejectsLongitudeOutOfRange() {
            assertThat(violatedFields(request("37.4979", "180.1", null))).containsExactly("longitude");
            assertThat(violatedFields(request("37.4979", "-180.1", null))).containsExactly("longitude");
        }

        @Test
        @DisplayName("좌표 범위의 경계값은 허용한다")
        void acceptsCoordinateBoundaries() {
            assertThat(violatedFields(request("90", "180", null))).isEmpty();
            assertThat(violatedFields(request("-90", "-180", null))).isEmpty();
        }

        @Test
        @DisplayName("정확도가 음수면 위반이다")
        void rejectsNegativeAccuracy() {
            // 물리적으로 불가능한 입력이라 상한(100m)과 달리 400 으로 끊는다.
            assertThat(violatedFields(request("37.4979", "127.0276", "-0.1")))
                    .containsExactly("accuracyMeters");
        }

        @Test
        @DisplayName("정확도 상한을 넘어도 Bean Validation 위반은 아니다")
        void doesNotRejectLowAccuracyHere() {
            // 상한 초과는 200 으로 수용하고 폐기해야 하므로 여기서 걸리면 안 된다.
            // 여기에 @DecimalMax 를 달면 GlobalExceptionHandler 를 타고 400 이 된다.
            assertThat(violatedFields(request("37.4979", "127.0276", "999"))).isEmpty();
        }

        @Test
        @DisplayName("정확도는 없어도 된다")
        void allowsMissingAccuracy() {
            assertThat(violatedFields(request("37.4979", "127.0276", null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("역직렬화")
    class Deserialization {

        private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        private String body(String measuredAt) {
            return """
                    {"latitude":37.4979,"longitude":127.0276,"measuredAt":"%s","accuracyMeters":12.5}"""
                    .formatted(measuredAt);
        }

        @Test
        @DisplayName("프론트가 보내는 toISOString 형식이 파싱된다")
        void parsesBrowserIsoString() throws Exception {
            RiderLocationUpdateRequest parsed =
                    objectMapper.readValue(body("2026-07-29T12:34:56.789Z"), RiderLocationUpdateRequest.class);

            assertThat(parsed.measuredAt()).isEqualTo(MEASURED_AT);
            assertThat(parsed.latitude()).isEqualByComparingTo("37.4979");
            assertThat(parsed.accuracyMeters()).isEqualByComparingTo("12.5");
        }

        @Test
        @DisplayName("오프셋이 붙은 형식도 같은 순간으로 정규화된다")
        void normalizesOffsetToSameInstant() throws Exception {
            // 서울(+09:00) 21:34:56 은 UTC 12:34:56 과 같은 순간이다.
            RiderLocationUpdateRequest parsed =
                    objectMapper.readValue(body("2026-07-29T21:34:56.789+09:00"), RiderLocationUpdateRequest.class);

            assertThat(parsed.measuredAt()).isEqualTo(MEASURED_AT);
        }

        @Test
        @DisplayName("시간대가 없는 시각은 거부한다")
        void rejectsZonelessTimestamp() {
            // measuredAt 을 Instant 로 둔 이유가 이것이다. LocalDateTime 이면 이 문자열을 조용히
            // UTC 로 간주하므로, 로컬 시각을 보낸 클라이언트의 값이 9시간 틀어진 채 오류 없이
            // 들어오고 이후 모든 위치가 STALE 로 판정된다. 여기서 끊는 편이 낫다.
            assertThatThrownBy(() ->
                    objectMapper.readValue(body("2026-07-29T12:34:56.789"), RiderLocationUpdateRequest.class))
                    .isInstanceOf(InvalidFormatException.class);
        }

        @Test
        @DisplayName("정확도를 생략한 본문도 파싱된다")
        void parsesBodyWithoutAccuracy() throws Exception {
            String json = """
                    {"latitude":37.4979,"longitude":127.0276,"measuredAt":"2026-07-29T12:34:56.789Z"}""";

            assertThat(objectMapper.readValue(json, RiderLocationUpdateRequest.class).accuracyMeters()).isNull();
        }
    }

    @Nested
    @DisplayName("스냅샷 변환")
    class SnapshotConversion {

        @Test
        @DisplayName("측정 시각을 UTC LocalDateTime 으로 바꾼다")
        void convertsMeasuredAtToUtcLocalDateTime() {
            // 저장 계층은 저장소 관례대로 UTC LocalDateTime 을 쓴다. 여기서 오프셋을 잃으면
            // 서버 타임존에 따라 9시간 틀어진 좌표가 저장된다.
            RiderLocationSnapshot snapshot = request("37.4979", "127.0276", "12.5").toSnapshot();

            assertThat(snapshot.measuredAt())
                    .isEqualTo(LocalDateTime.of(2026, 7, 29, 12, 34, 56, 789_000_000));
        }

        @Test
        @DisplayName("스냅샷이 좌표 자릿수를 정규화한다")
        void normalizesScaleViaSnapshot() {
            RiderLocationSnapshot snapshot = request("37.4979", "127.0276", "12.5").toSnapshot();

            assertThat(snapshot.latitude()).isEqualTo(new BigDecimal("37.4979000"));
            assertThat(snapshot.accuracyMeters()).isEqualTo(new BigDecimal("12.50"));
        }

        @Test
        @DisplayName("정확도가 없으면 스냅샷에도 없다")
        void keepsAccuracyAbsent() {
            assertThat(request("37.4979", "127.0276", null).toSnapshot().accuracyMeters()).isNull();
        }
    }
}
