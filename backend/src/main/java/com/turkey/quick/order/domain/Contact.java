package com.turkey.quick.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문에 스냅샷으로 저장되는 연락처(보내는 사람 / 받는 사람).
 *
 * 회원 정보가 나중에 바뀌어도 과거 주문의 연락처는 주문 시점 값으로 남아야 하므로
 * member 를 참조하지 않고 값을 복사해 보관한다. 받는 사람은 회원이 아닐 수도 있다.
 *
 * 실제 컬럼명(sender_* / recipient_*)은 사용처에서 @AttributeOverride 로 지정한다 —
 * 이름·전화번호 두 문자열이 나란히 오는 인자를 타입으로 구분해, 보내는 사람과 받는 사람이
 * 뒤바뀐 채 컴파일되는 것을 막는다.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Contact {

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String phoneNumber;

    private Contact(String name, String phoneNumber) {
        requireText(name, "이름");
        requireText(phoneNumber, "전화번호");
        this.name = name;
        this.phoneNumber = phoneNumber;
    }

    public static Contact of(String name, String phoneNumber) {
        return new Contact(name, phoneNumber);
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "은 비어 있을 수 없습니다. " + name + "=" + value);
        }
    }
}
