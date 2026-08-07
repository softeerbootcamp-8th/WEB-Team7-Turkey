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
 * 이슈 #420 의 완료 조건 "OSRM 미기동 상태에서도 애플리케이션이 정상 기동·동작"을 고정한다.
 *
 * <p>테스트 설정의 {@code routing.base-url} 은 아무도 듣지 않는 주소다
 * ({@code src/test/resources/application.yml}). 즉 이 클래스는 <b>OSRM 이 없는 상태 그 자체</b>를
 * 재현한다 — 컨텍스트가 뜨고, 주입된 클라이언트를 불러도 예외가 나오지 않아야 한다.
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
    @DisplayName("OSRM 이 떠 있지 않아도 컨텍스트가 뜨고 호출은 빈 값을 돌려준다")
    void shouldStartAndDegradeGracefullyWithoutOsrm() {
        Coordinate seoulCityHall =
                new Coordinate(new BigDecimal("37.5665000"), new BigDecimal("126.9779000"));
        Coordinate gangnamStation =
                new Coordinate(new BigDecimal("37.4979000"), new BigDecimal("127.0276000"));

        assertThat(routingClient).isInstanceOf(OsrmRoutingClient.class);
        assertThat(routingClient.findRoute(seoulCityHall, gangnamStation)).isEmpty();
    }
}
