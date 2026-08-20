package com.turkey.quick.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.turkey.quick.common.config.ResilienceConfig;
import com.turkey.quick.order.dto.DeliveryCreateRequest;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        ResilienceConfig.class,
        DeliveryOrderCreatorRetryTest.TestConfig.class
})
@DisplayName("DeliveryOrderCreator 데드락 재시도")
class DeliveryOrderCreatorRetryTest {

    @Autowired
    private DeliveryOrderCreator deliveryOrderCreator;

    @Autowired
    private DeliveryService deliveryService;

    @AfterEach
    void tearDown() {
        reset(deliveryService);
    }

    @Test
    @DisplayName("MySQL 데드락이면 실패한 호출 뒤 전체 생성을 다시 실행한다")
    void retriesAfterMySqlDeadlock() {
        CannotAcquireLockException deadlock = cannotAcquireLock(1213, "40001");
        given(deliveryService.createDelivery(any(), anyLong()))
                .willThrow(deadlock)
                .willReturn(null);

        deliveryOrderCreator.create(mock(DeliveryCreateRequest.class), 1L);

        verify(deliveryService, times(2)).createDelivery(any(), anyLong());
    }

    @Test
    @DisplayName("락 대기 타임아웃이면 다시 실행하지 않고 즉시 전파한다")
    void doesNotRetryLockWaitTimeout() {
        CannotAcquireLockException timeout = cannotAcquireLock(1205, "HY000");
        given(deliveryService.createDelivery(any(), anyLong())).willThrow(timeout);

        assertThatThrownBy(() -> deliveryOrderCreator.create(mock(DeliveryCreateRequest.class), 1L))
                .isSameAs(timeout);

        verify(deliveryService, times(1)).createDelivery(any(), anyLong());
    }

    private CannotAcquireLockException cannotAcquireLock(int errorCode, String sqlState) {
        SQLException sqlException = new SQLException("MySQL lock failure", sqlState, errorCode);
        return new CannotAcquireLockException("insert failed", sqlException);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        DeliveryService deliveryService() {
            return mock(DeliveryService.class);
        }

        @Bean
        DeliveryOrderCreator deliveryOrderCreator(DeliveryService deliveryService) {
            return new DeliveryOrderCreator(deliveryService);
        }
    }
}
