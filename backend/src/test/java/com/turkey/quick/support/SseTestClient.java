package com.turkey.quick.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 테스트에서 SSE 스트림을 읽는 클라이언트.
 *
 * <p><b>{@code TestRestTemplate} 을 쓸 수 없다.</b> {@code RestTemplate} 은 응답 본문을 끝까지
 * 버퍼링하므로 스트림이 닫힐 때까지(emitter 타임아웃 5분) 블록되고, 그 안에서 "2초 안에 첫
 * 이벤트가 왔는가"를 단언할 방법이 없다. 실수하면 테스트가 멈춘 것처럼 보인다.
 * {@code WebTestClient} 도 쓸 수 없다 — {@code spring-webflux} 가 의존성에 없다.
 *
 * <p>그래서 JDK 21 {@code HttpClient} + {@code BodyHandlers.ofLines()} 를 쓴다(새 의존성 없음).
 * 이 조합은 <b>헤더가 도착한 시점에</b> 응답을 돌려주고 본문은 지연 스트림으로 준다. 그 스트림을
 * 별 스레드에서 소비해 큐에 담고, 테스트는 타임아웃을 걸어 꺼낸다.
 *
 * <p>쿠키를 헤더로 직접 싣는다 — JDK 클라이언트는 기본 쿠키 핸들러가 없다.
 * {@code Accept: text/event-stream} 도 명시한다. 브라우저 {@code EventSource} 가 그 헤더만
 * 보내는데, 그 조건에서 오류 응답이 406 으로 바뀌는 함정이 있어 테스트가 같은 조건을 재현해야 한다.
 *
 * <p><b>반드시 닫아야 한다.</b> 닫지 않으면 드레인 스레드가 남아 Gradle 이 끝나지 않을 수 있다.
 */
public final class SseTestClient implements AutoCloseable {

    /** 헤더 도착을 기다리는 상한. 스트림 본문에는 적용되지 않는다. */
    private static final Duration HEADER_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final ExecutorService drainer =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sse-test-drainer");
                thread.setDaemon(true);
                return thread;
            });
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> consumed = new ArrayList<>();
    private final int statusCode;
    private final Stream<String> body;

    private SseTestClient(HttpClient client, HttpResponse<Stream<String>> response) {
        this.client = client;
        this.statusCode = response.statusCode();
        this.body = response.body();
        drainer.submit(() -> {
            try {
                body.forEach(lines::add);
            } catch (RuntimeException ignored) {
                // 스트림을 닫으면 소비 중 예외가 날 수 있다. 테스트 종료 경로라 무시한다.
            }
        });
    }

    /**
     * @param cookie {@code null} 이면 쿠키를 싣지 않는다 — 인터셉터 등록 누락을 잡는 401 케이스가
     *               그 형태다
     */
    public static SseTestClient get(String url, String cookie) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "text/event-stream")
                .GET();
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        try {
            // sendAsync + get(타임아웃): 요청 자체에 timeout 을 걸면 본문(수 분짜리 스트림)까지
            // 그 시간에 묶일 수 있어, 헤더 대기만 따로 제한한다.
            HttpResponse<Stream<String>> response = client
                    .sendAsync(request.build(), HttpResponse.BodyHandlers.ofLines())
                    .get(HEADER_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return new SseTestClient(client, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SSE 연결이 중단됐습니다: " + url, e);
        } catch (Exception e) {
            throw new IllegalStateException("SSE 연결에 실패했습니다: " + url, e);
        }
    }

    public int statusCode() {
        return statusCode;
    }

    /**
     * 지정한 이름의 이벤트가 오면 그 {@code data:} 본문을 돌려준다.
     *
     * <p>스프링은 {@code event:<name>} 다음 줄에 {@code data:<본문>} 을 쓴다(공백 없음). SSE 스펙은
     * 콜론 뒤 공백을 허용하므로 잘라낸 뒤 trim 한다.
     */
    public String awaitEvent(String eventName, Duration timeout) {
        String[] data = {null};
        boolean[] matched = {false};
        awaitLine(line -> {
            if (line.startsWith("event:")) {
                matched[0] = line.substring("event:".length()).trim().equals(eventName);
                return false;
            }
            if (matched[0] && line.startsWith("data:")) {
                data[0] = line.substring("data:".length()).trim();
                return true;
            }
            return false;
        }, timeout, "event=" + eventName);
        return data[0];
    }

    /**
     * 이름 없는 이벤트({@code data:} 만 있는 프레임)의 본문을 돌려준다.
     *
     * <p>위치 중계는 이벤트 이름을 붙이지 않는다 — 브라우저 {@code EventSource} 의 기본
     * {@code message} 이벤트로 도착해야 프론트 {@code useTrackingStream.onmessage} 가 받는다.
     * 그래서 {@link #awaitEvent} 로는 위치 프레임을 기다릴 수 없다.
     */
    public String awaitData(Duration timeout) {
        String[] data = {null};
        awaitLine(line -> {
            if (line.startsWith("data:")) {
                data[0] = line.substring("data:".length()).trim();
                return true;
            }
            return false;
        }, timeout, "data 프레임");
        return data[0];
    }

    /** 조건에 맞는 줄이 올 때까지 기다린다. heartbeat 주석({@code :hb}) 확인에도 쓴다. */
    public void awaitLine(Predicate<String> matcher, Duration timeout, String description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("SSE 스트림에서 %s 를 %s 안에 받지 못했습니다. 받은 줄=%s"
                        .formatted(description, timeout, consumed));
            }
            String line;
            try {
                line = lines.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("SSE 대기가 중단됐습니다: " + description, e);
            }
            if (line == null) {
                continue;
            }
            consumed.add(line);
            if (matcher.test(line)) {
                return;
            }
        }
    }

    /** 지금까지 받은 줄 전부. 실패 진단과 "오지 않아야 하는 것"을 단언할 때 쓴다. */
    public List<String> receivedLines() {
        return List.copyOf(consumed);
    }

    @Override
    public void close() {
        body.close();
        drainer.shutdownNow();
        client.close();
    }
}
