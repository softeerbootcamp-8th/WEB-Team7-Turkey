package com.turkey.quick.common.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * /v3/api-docs 의 operationId 계약을 검증한다.
 *
 * 프론트는 이 스펙으로 Orval React Query 훅을 생성하고, 훅 이름은 operationId 에서 온다(#194).
 * springdoc 은 동명 컨트롤러 메서드가 둘 이상이면 나중 것에 `_1` 접미사를 붙여 이름을 만드는데,
 * 그 배정 순서는 컨트롤러 스캔 순서에 달려 있다. 즉 새 컨트롤러가 기존 메서드명(login/signup/session)을
 * 재사용하면 `useRiderLogin` 같은 훅 이름이 조용히 `useLogin` / `useLogin1` 로 되돌아가고,
 * 어떤 액터를 가리키는지도 알 수 없게 된다. 그 회귀를 프론트가 아니라 여기서 잡는다.
 *
 * 스펙만 읽으므로 Redis 를 쓰는 엔드포인트를 호출하지 않는다(테스트 대체 빈이 필요 없다).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class OpenApiOperationIdE2ETest extends IntegrationTestSupport {

    private static final String API_DOCS_ENDPOINT = "/v3/api-docs";

    @Autowired
    private TestRestTemplate rest;

    /** operationId -> "METHOD /path" (중복 검출을 위해 등장 순서를 보존한다) */
    private final Map<String, List<String>> operationIds = new LinkedHashMap<>();

    private final List<String> missingOperationIds = new ArrayList<>();

    @BeforeEach
    void 스펙을_읽어_operationId를_수집한다() {
        var response = rest.getForEntity(API_DOCS_ENDPOINT, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode paths = response.getBody().get("paths");
        assertThat(paths).as("스펙에 paths 가 있어야 한다").isNotNull();

        paths.properties().forEach(pathEntry ->
                pathEntry.getValue().properties().forEach(methodEntry -> {
                    String location = methodEntry.getKey().toUpperCase() + " " + pathEntry.getKey();
                    JsonNode operationId = methodEntry.getValue().get("operationId");
                    if (operationId == null || operationId.asText().isBlank()) {
                        missingOperationIds.add(location);
                        return;
                    }
                    operationIds.computeIfAbsent(operationId.asText(), key -> new ArrayList<>()).add(location);
                }));

        assertThat(operationIds).as("수집된 오퍼레이션이 있어야 한다").isNotEmpty();
    }

    @Test
    void 모든_오퍼레이션에_operationId가_있다() {
        assertThat(missingOperationIds)
                .as("operationId 가 없으면 Orval 이 경로 기반으로 훅 이름을 지어낸다")
                .isEmpty();
    }

    @Test
    void operationId가_중복되지_않는다() {
        Map<String, List<String>> duplicated = new LinkedHashMap<>(operationIds);
        duplicated.values().removeIf(locations -> locations.size() == 1);

        assertThat(duplicated)
                .as("같은 operationId 를 쓰는 오퍼레이션이 있으면 훅 하나가 다른 하나를 덮어쓴다")
                .isEmpty();
    }

    @Test
    void operationId에_springdoc_중복해소_접미사가_붙지_않는다() {
        List<String> suffixed = operationIds.keySet().stream()
                .filter(id -> id.matches(".*_\\d+$"))
                .toList();

        assertThat(suffixed)
                .as("`_1` 접미사는 컨트롤러 메서드명이 겹쳤다는 신호다 — "
                        + "해당 컨트롤러에 @Operation(operationId = \"...\") 를 명시해 이름을 고정할 것")
                .isEmpty();
    }

    @Test
    void 고객_라이더_공통_행위의_operationId는_액터를_구분한다() {
        // login/signup/session 은 두 액터가 같은 이름을 쓰기 쉬운 지점이라 여기서 못 박아 둔다.
        assertThat(operationIds.keySet()).contains(
                "customerLogin", "customerLogout", "getCustomerSession", "customerSignup",
                "riderLogin", "getRiderSession", "riderSignup");

        assertThat(operationIds.get("customerLogin")).containsExactly("POST /api/customer/login");
        assertThat(operationIds.get("riderLogin")).containsExactly("POST /api/rider/login");
        assertThat(operationIds.get("getCustomerSession")).containsExactly("GET /api/customer/session");
        assertThat(operationIds.get("getRiderSession")).containsExactly("GET /api/rider/session");
    }
}
