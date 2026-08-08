package com.turkey.quick.common.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * "라우팅 API 에 닿지 않는 상태에서도 애플리케이션이 정상 기동·동작"을 고정한다(#420 완료 조건,
 * #431 에서 카카오 구현체로 이어받음).
 *
 * <p>테스트 설정의 {@code routing.base-url} 은 아무도 듣지 않는 주소다
 * ({@code src/test/resources/application.yml}). 즉 이 클래스는 <b>라우팅 서버가 없는 상태 그 자체</b>를
 * 재현하며, 동시에 <b>테스트가 실제 카카오 API 를 부르지 않는다는 것</b>도 그 설정이 보장한다
 * (호출당 과금, #431 확정사항).
 *
 * <p>같은 설정의 {@code routing.api-key} 에 가짜 키가 들어 있는 것도 의도다 — 키가 비면 클라이언트가
 * 호출 자체를 건너뛰어, 이 테스트가 연결 실패 경로를 지나가지 않고 통과해 버린다.
 *
 * <p>클라이언트가 기동 시점에 서버에 접속하려 들면(생성자에서 헬스체크를 하는 식) 여기서 바로
 * 컨텍스트 로딩이 깨진다. 그런 구현으로 바뀌는 것을 막는 것이 이 테스트의 목적이다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("라우팅 클라이언트 통합")
class RoutingClientIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RoutingClient routingClient;

    @Test
    @DisplayName("라우팅 서버에 닿지 않아도 컨텍스트가 뜨고 호출은 빈 값을 돌려준다")
    void shouldStartAndDegradeGracefullyWithoutRoutingServer() {
        Coordinate seoulCityHall =
                new Coordinate(new BigDecimal("37.5665000"), new BigDecimal("126.9779000"));
        Coordinate gangnamStation =
                new Coordinate(new BigDecimal("37.4979000"), new BigDecimal("127.0276000"));

        // 구현체를 못 박는 것은 #431 교체가 실제로 배선까지 반영됐는지 보기 위해서다.
        // 어댑터를 또 갈아 끼우면 이 줄이 먼저 깨져, 설정만 바꾸고 빈은 그대로인 상태를 막는다.
        assertThat(routingClient).isInstanceOf(KakaoMobilityRoutingClient.class);
        assertThat(routingClient.findRoute(seoulCityHall, gangnamStation)).isEmpty();
    }
}
