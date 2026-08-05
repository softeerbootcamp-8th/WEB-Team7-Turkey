package com.turkey.quick.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 를 켠다. 이 저장소의 첫 스케줄러는 배차 대기 시간 초과 자동 취소(#42)의
 * 백그라운드 스캐너다.
 *
 * <p>Spring Batch 는 쓰지 않는다(CLAUDE.md 금지 사항) — {@code @Scheduled} 로 충분한 규모다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
