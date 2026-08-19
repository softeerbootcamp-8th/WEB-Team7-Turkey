package com.turkey.quick.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/** 메서드 단위의 제한적 재시도 등 Spring Framework 복원력 기능을 활성화한다. */
@Configuration
@EnableResilientMethods
public class ResilienceConfig {
}
