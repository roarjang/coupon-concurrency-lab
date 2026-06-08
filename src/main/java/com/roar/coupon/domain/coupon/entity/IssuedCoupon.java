package com.roar.coupon.domain.coupon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "issued_coupons",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_issued_coupon_user_coupon",
                        columnNames = {"user_id", "coupon_id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssuedCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IssuedCouponStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    private LocalDateTime usedAt;

    public static IssuedCoupon issue(Long userId, Long couponId) {
        IssuedCoupon issuedCoupon = new IssuedCoupon();
        issuedCoupon.userId = userId;
        issuedCoupon.couponId = couponId;
        issuedCoupon.status = IssuedCouponStatus.ISSUED;
        issuedCoupon.issuedAt = LocalDateTime.now();

        return issuedCoupon;
    }
}
