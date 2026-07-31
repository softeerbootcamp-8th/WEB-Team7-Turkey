package com.turkey.quick;

import com.turkey.quick.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * MemberRepository(첫 JPA 리포지토리) 이후로는 DB 자동설정을 제외한 기본 프로파일로는
 * 전체 컨텍스트가 뜨지 않는다(리포지토리 빈이 없어 DI 실패). integration 프로파일(Docker MySQL)로 띄운다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class QuickApplicationTests extends IntegrationTestSupport {

    @Test
    void contextLoads() {
    }
}
