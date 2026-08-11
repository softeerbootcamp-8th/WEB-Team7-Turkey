package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.rider.domain.RiderProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeliveryProofTest {

    private DeliveryOrder order() {
        return new DeliveryOrder();
    }

    private RiderProfile rider() {
        return RiderProfile.create(
                Member.create("rider01", "hash", "김라이더", "01011112222", MemberRole.RIDER));
    }

    @Test
    @DisplayName("생성하면 주문과 라이더 인증정보를 보관한다")
    void shouldStoreOrderRiderAndProofInformation() {
        DeliveryOrder order = order();
        RiderProfile rider = rider();

        DeliveryProof proof = DeliveryProof.create(order, rider,
                ProofType.PHOTO, "s3://turkey/proofs/1.jpg");

        assertThat(proof.getOrder()).isSameAs(order);
        assertThat(proof.getRider()).isSameAs(rider);
        assertThat(proof.getProofType()).isEqualTo(ProofType.PHOTO);
        assertThat(proof.getProofValue()).isEqualTo("s3://turkey/proofs/1.jpg");
    }

    @Test
    @DisplayName("order는 null일수 없다")
    void shouldRejectNullOrder() {
        assertThatThrownBy(() -> DeliveryProof.create(null, rider(),
                ProofType.PHOTO, "ref"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rider는 null일수 없다")
    void shouldRejectNullRider() {
        assertThatThrownBy(() -> DeliveryProof.create(order(), null,
                ProofType.PHOTO, "ref"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("proofType은 null일수 없다")
    void shouldRejectNullProofType() {
        assertThatThrownBy(() -> DeliveryProof.create(order(), rider(),
                null, "ref"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("proofValue는 공백일수 없다")
    void shouldRejectBlankProofValue() {
        assertThatThrownBy(() -> DeliveryProof.create(order(), rider(),
                ProofType.AUTH_CODE, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("proofValue는 500자를 초과할수 없다")
    void shouldRejectProofValueLongerThanFiveHundredCharacters() {
        assertThatThrownBy(() -> DeliveryProof.create(order(), rider(),
                ProofType.PHOTO, "a".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
