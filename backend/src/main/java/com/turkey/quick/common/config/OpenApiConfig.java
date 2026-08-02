package com.turkey.quick.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * OpenAPI 문서 메타데이터 설정.
 *
 * <p>이 문서(/v3/api-docs)는 Swagger UI 열람용이자 프론트엔드 API 클라이언트의 입력이다.
 * frontend 의 Orval 이 이 스펙을 읽어 React Query 훅을 생성하므로,
 * 컨트롤러에 붙인 {@code @Tag}/{@code @Operation} 이 곧 프론트의 파일 구조와 훅 이름이 된다.
 *
 * <p>{@code app.public-base-url}을 명시하는 이유: CloudFront가 커스텀 오리진(EC2)으로 요청을
 * 넘길 때 Host 헤더를 오리진 도메인으로 덮어써서, 이 값을 지정하지 않으면 springdoc이 자동
 * 추론하는 서버 URL이 EC2 도메인(그것도 http)으로 잡힌다. 그 상태로는 Swagger UI "Execute"가
 * CloudFront를 건너뛰고 EC2로 직접 요청을 보내 mixed-content/CORS 오류로 막힌다. 로컬
 * 프로파일에서는 이 값을 비워(application-local.yml) springdoc이 요청 기준으로 자동 추론하게
 * 둔다(localhost는 이 문제가 없다).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI turkeyOpenAPI(@Value("${app.public-base-url:}") String publicBaseUrl) {
        OpenAPI openAPI = new OpenAPI().info(new Info()
                .title("Turkey Quick Delivery API")
                .description("퀵배송 매칭 서비스 API. 인증은 쿠키 기반 서버 세션을 사용한다.")
                .version("v1"));

        if (StringUtils.hasText(publicBaseUrl)) {
            openAPI.addServersItem(new Server().url(publicBaseUrl));
        }
        return openAPI;
    }
}
