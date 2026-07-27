package com.turkey.quick.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.rider.domain.RiderProfile;
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
    void 생성하면_주문과_라이더_인증정보를_보관한다() {
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
    void order는_null일수_없다() {
        assertThatThrownBy(() -> DeliveryProof.create(null, rider(),
                ProofType.PHOTO, "ref"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rider는_null일수_없다() {
        assertThatThrownBy(() -> DeliveryProof.create(order(), null,
                ProofType.PHOTO, "ref"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proofType은_null일수_없다() {
        assertThatThrownBy(() -> DeliveryProof.create(order(), rider(),
                null, "ref"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proofValue는_공백일수_없다() {
        assertThatThrownBy(() -> DeliveryProof.create(order(), rider(),
                ProofType.AUTH_CODE, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proofValue는_500자를_초과할수_없다() {
        assertThatThrownBy(() -> DeliveryProof.create(order(), rider(),
                ProofType.PHOTO, "a".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
