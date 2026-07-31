package com.turkey.quick.order.domain;

/**
 * 배송 상태 전이를 수행한 주체의 종류.
 * CUSTOMER/RIDER 는 실제 회원이 수행한 전이(actor_member_id 필수), SYSTEM 은 자동 처리로
 * 회원 주체가 없는 전이(actor_member_id NULL)다. 이 쌍 조건은 DB ck_order_status_actor 가 강제한다.
 */
public enum ActorType {
    CUSTOMER,
    RIDER,
    SYSTEM
}
