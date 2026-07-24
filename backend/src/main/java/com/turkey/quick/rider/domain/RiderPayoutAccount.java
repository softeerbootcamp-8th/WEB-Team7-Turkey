package com.turkey.quick.rider.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 라이더 활성 출금 계좌.
 * rider_profile 과 PK(rider_id = member_id)를 공유한다 — 라이더당 활성 계좌 1개.
 * (RiderProfile 이 Member 를 @MapsId 로 확장하듯, 이 엔터티는 RiderProfile 을 확장한다.)
 *
 * 보안: 계좌번호 원문은 저장하지 않는다. 암호화된 바이트(ciphertext)만 보관하고, 화면 표시는
 * masked_account_number 만 사용한다. 암호화/복호화는 키·Cipher 가 필요한 인프라 관심사라
 * 서비스 계층에서 수행하고, 이 엔터티는 이미 암호화된 바이트를 받기만 한다.
 */
@Entity
@Table(name = "rider_payout_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiderPayoutAccount {

    @Id
    @Column(name = "rider_id")
    private Long riderId;

    /** @MapsId: rider 연관의 PK 를 위 riderId(=이 테이블 PK)로 복사 → RiderProfile 과 공유 PK. LAZY. */
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rider_id")
    private RiderProfile rider;

    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode;

    /** 계좌번호 암호문(VARBINARY). 이미 암호화된 바이트만 저장한다(원문 미저장). */
    @Column(name = "account_number_ciphertext", nullable = false, length = 512)
    private byte[] accountNumberCiphertext;

    @Column(name = "masked_account_number", nullable = false, length = 50)
    private String maskedAccountNumber;

    @Column(name = "account_holder_name", nullable = false, length = 50)
    private String accountHolderName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private RiderPayoutAccount(RiderProfile rider, String bankCode, byte[] accountNumberCiphertext,
                              String maskedAccountNumber, String accountHolderName) {
        this.rider = rider;
        this.bankCode = bankCode;
        this.accountNumberCiphertext = accountNumberCiphertext;
        this.maskedAccountNumber = maskedAccountNumber;
        this.accountHolderName = accountHolderName;
    }

    /** 출금 계좌 최초 등록. ciphertext 는 서비스 계층에서 암호화한 바이트를 넘겨받는다. */
    public static RiderPayoutAccount register(RiderProfile rider, String bankCode,
                                              byte[] accountNumberCiphertext,
                                              String maskedAccountNumber,
                                              String accountHolderName) {
        return new RiderPayoutAccount(rider, bankCode, accountNumberCiphertext,
                maskedAccountNumber, accountHolderName);
    }

    /** 계좌 변경. 라이더당 활성 계좌는 1개라 새 행이 아니라 in-place 로 교체한다. */
    public void changeAccount(String bankCode, byte[] accountNumberCiphertext,
                              String maskedAccountNumber, String accountHolderName) {
        this.bankCode = bankCode;
        this.accountNumberCiphertext = accountNumberCiphertext;
        this.maskedAccountNumber = maskedAccountNumber;
        this.accountHolderName = accountHolderName;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
