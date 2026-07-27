package com.turkey.quick.order.domain;

/**
 * 배송 완료 인증 방식.
 * PHOTO(사진), RECIPIENT_CONFIRMATION(수령인 확인), AUTH_CODE(인증코드) 중 하나다.
 * 실제 이미지 바이너리는 저장하지 않고, proof_value 에 참조값(URL/키/코드)만 보관한다.
 */
public enum ProofType {
    PHOTO,
    RECIPIENT_CONFIRMATION,
    AUTH_CODE
}
