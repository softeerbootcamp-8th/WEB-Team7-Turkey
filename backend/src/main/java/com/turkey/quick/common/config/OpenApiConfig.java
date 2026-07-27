package com.turkey.quick.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 문서 메타데이터 설정.
 *
 * <p>이 문서(/v3/api-docs)는 Swagger UI 열람용이자 프론트엔드 API 클라이언트의 입력이다.
 * frontend 의 Orval 이 이 스펙을 읽어 React Query 훅을 생성하므로,
 * 컨트롤러에 붙인 {@code @Tag}/{@code @Operation} 이 곧 프론트의 파일 구조와 훅 이름이 된다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI turkeyOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Turkey Quick Delivery API")
                .description("퀵배송 매칭 서비스 API. 인증은 쿠키 기반 서버 세션을 사용한다.")
                .version("v1"));
    }
}
