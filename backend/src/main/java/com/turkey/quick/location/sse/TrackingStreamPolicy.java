package com.turkey.quick.location.sse;

import java.time.Duration;
import java.time.Instant;

/**
 * 실시간 위치 구독 스트림의 수치 정책(#77, 사람 확인 2026-07-30).
 *
 * <p>{@code LocationAcceptancePolicy}·{@code LocationFilter} 와 같은 이유로 한 파일에 모았다 —
 * 이 값들은 {@code CLAUDE.md} 「확인이 필요한 항목」에 미결로 남아 있던 것이라, 흩어져 있으면
 * 무엇이 사람 확인을 받은 값인지 알 수 없게 된다.
 *
 * <p>값을 조정할 때 <b>깨지면 안 되는 순서</b>가 있다:
 * <pre>heartbeat 간격 &lt; CloudFront 오리진 응답 타임아웃 &lt; ALB idle timeout</pre>
 * CloudFront 는 스트리밍 응답에서 <b>패킷 사이 간격</b>에 타임아웃을 적용하므로, heartbeat 가
 * 그보다 느리면 조용한 구간에서 연결이 끊긴다. 그 실제 값은 배포 환경에서 실측해야 하고
 * (계획 Step 0) 아직 확인되지 않았다 — 지금 값은 CloudFront 기본값 30초를 전제로 한 것이다.
 */
public final class TrackingStreamPolicy {

    /**
     * 한 배송에 동시에 유지할 수 있는 구독 연결 수.
     *
     * <p>3인 이유: 탭 2개 + 재연결이 겹치는 순간의 중복 1. 1이면 새 탭이 옛 탭을 밀어내지 못하고
     * (옛 연결이 만료될 때까지 새 탭이 막힌다), 5면 느슨해서 상한의 의미가 없다.
     */
    public static final int MAX_CONNECTIONS_PER_ORDER = 3;

    /**
     * 이 시간 동안 갱신되지 않은 연결 기록은 죽은 것으로 보고 걷어낸다.
     *
     * <p>heartbeat 3회 누락 분량이다. <b>이 값이 연결 수 집계의 자기치유 장치다</b> — 인스턴스가
     * {@code kill -9} 로 죽거나 모바일 네트워크 전환으로 TCP 가 half-open 이 되면 정리 코드가
     * 돌지 않는데, 그때 남은 기록이 영구히 상한을 차지하면 <b>그 배송은 다시 구독할 수 없게 된다.</b>
     */
    public static final Duration STALE_AFTER = Duration.ofSeconds(45);

    /**
     * 연결 집계 키의 TTL. 위 stale 정리가 1차 방어이고 이것은 최종 청소다 — 그 배송에 아무도
     * 다시 접속하지 않으면 정리 코드를 돌릴 주체가 없으므로 키 자체가 만료돼야 한다.
     * 최신 위치 TTL(10분)과 같은 자릿수로 맞췄다.
     */
    public static final Duration CONNECTION_KEY_TTL = Duration.ofMinutes(10);

    private TrackingStreamPolicy() {
    }

    /**
     * 이 시각보다 오래된 기록은 죽은 연결로 본다.
     *
     * <p>시각을 Redis 의 {@code TIME} 이 아니라 <b>애플리케이션에서 넘기는</b> 이유가 두 가지다.
     * Lua 스크립트가 결정적(deterministic)이어야 복제·AOF 에 안전하고, 테스트가 대기 없이
     * stale 상황을 만들 수 있다({@code LocationAcceptancePolicy} 가 {@code now} 를 인자로 받는
     * 것과 같은 이유). 인스턴스 간 시계 오차는 이 임계값(45초)에 비해 무의미하다.
     */
    public static Instant staleCutoff(Instant now) {
        return now.minus(STALE_AFTER);
    }
}
