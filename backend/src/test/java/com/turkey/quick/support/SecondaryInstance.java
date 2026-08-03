package com.turkey.quick.support;

import com.turkey.quick.QuickApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 같은 테스트 JVM 안에 <b>두 번째 애플리케이션 인스턴스</b>를 띄운다.
 *
 * <p><b>왜 필요한가</b>: 인스턴스가 1대면 Pub/Sub 팬아웃을 통째로 빼고 직접 전달하도록 구현해도
 * 모든 테스트가 통과한다. 즉 단일 인스턴스 검증은 이 기능의 핵심을 <b>하나도 증명하지 못한다.</b>
 * "인스턴스 A 에 SSE 연결 → 인스턴스 B 에 위치 POST → A 의 스트림에 도달"만이 팬아웃을 증명한다.
 *
 * <p>두 컨텍스트는 서로 다른 {@code BeanFactory} 를 가지므로 {@code SseRegistry} 가 컨텍스트당
 * 하나씩 생긴다 — 그래서 "인스턴스 로컬"이 자연히 재현된다. <b>레지스트리를 {@code static} 으로
 * 바꾸면 두 컨텍스트가 같은 맵을 공유해 테스트가 통과하면서 아무것도 증명하지 못하므로</b>,
 * 테스트가 두 레지스트리의 동일성과 B 가 비어 있음을 함께 단언한다.
 *
 * <p><b>놓치기 쉬운 함정</b>: {@code src/test/resources/application.yml} 이 DataSource·JPA·Flyway
 * 자동설정을 <b>제외</b>하고 있고, E2E 클래스들은 {@code properties = "spring.autoconfigure.exclude="}
 * 로 그것을 되돌린다. 여기서 띄우는 인스턴스도 <b>같은 테스트 클래스패스를 읽으므로</b> 그 빈 값을
 * 명시하지 않으면 DataSource 없이 떠서 리포지토리 주입이 깨진다.
 *
 * <p>이 방식의 한계도 분명하다 — <b>같은 JVM 이라 진짜 프로세스 분리가 아니다.</b> {@code static}
 * 상태나 JVM 전역 싱글턴으로 인한 거짓 통과는 위 단언으로 부분적으로만 막힌다. 롤링 배포 중
 * 구·신 버전 공존은 이 방식으로 재현할 수 없다.
 */
public final class SecondaryInstance implements AutoCloseable {

    private final ConfigurableApplicationContext context;
    private final int port;

    private SecondaryInstance(ConfigurableApplicationContext context, int port) {
        this.context = context;
        this.port = port;
    }

    public static SecondaryInstance start() {
        // 반드시 커맨드라인 인자(--key=value)로 넘긴다. SpringApplicationBuilder.properties() 는
        // defaultProperties 소스에 넣는데 그건 application.yml 보다 우선순위가 **낮아서** 아무것도
        // 덮지 못한다 — 실제로 그렇게 만들었다가 EntityManagerFactory 가 없어 기동이 깨졌다.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(QuickApplication.class)
                .run(
                        // 0 이면 임의 포트. RANDOM_PORT 를 두 번 쓰는 것이 아니라 직접 지정한다.
                        "--server.port=0",
                        "--spring.profiles.active=integration",
                        // 위 javadoc 의 함정 — 빈 값으로 테스트 클래스패스의 exclude 를 되돌린다.
                        "--spring.autoconfigure.exclude=",
                        // 첫 인스턴스가 이미 마이그레이션을 끝냈다. 다시 돌리면 no-op 이지만
                        // history 테이블 락을 잡을 이유가 없다.
                        "--spring.flyway.enabled=false",
                        // 스위트 전체에 캐시된 컨텍스트들과 MySQL max_connections 를 나눠 쓴다.
                        "--spring.datasource.hikari.maximum-pool-size=2",
                        // 관리 포트(8081)를 두 인스턴스가 함께 잡으려다 기동이 깨지는 것을 막는다.
                        "--management.server.port=0",
                        "--spring.jmx.enabled=false");
        Integer resolved = context.getEnvironment().getProperty("local.server.port", Integer.class);
        if (resolved == null) {
            context.close();
            throw new IllegalStateException("두 번째 인스턴스의 포트를 확인할 수 없습니다.");
        }
        return new SecondaryInstance(context, resolved);
    }

    public String url(String path) {
        return "http://localhost:%d%s".formatted(port, path);
    }

    /** 이 인스턴스의 빈을 꺼낸다. 레지스트리가 인스턴스별로 분리됐는지 단언할 때 쓴다. */
    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    /**
     * 반드시 닫아야 한다. 남기면 커넥션 풀과 스케줄러 스레드가 살아 있어 MySQL
     * {@code max_connections} 에 닿거나 Gradle 이 끝나지 않을 수 있다.
     */
    @Override
    public void close() {
        context.close();
    }
}
