package com.turkey.quick.payment.service;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Aspect
@Component
@Profile("local")
public class PaymentDelayAspect {

    // MockPaymentGateway 클래스 내부의 모든 public 메서드를 타겟으로 지정
    @Before("execution(public * com.turkey.quick.payment.service.MockPaymentGateway.*(..))")
    public void delay() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(10, 2000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}