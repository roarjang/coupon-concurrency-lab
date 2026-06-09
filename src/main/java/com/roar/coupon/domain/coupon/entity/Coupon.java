package com.roar.coupon.domain.coupon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long discountAmount;

    @Column(nullable = false)
    private int totalQuantity;

    @Column(nullable = false)
    private int issuedQuantity;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Coupon(String couponName, long discountAmount, int totalQuantity) {
        this.name = couponName;
        this.discountAmount = discountAmount;
        this.totalQuantity = totalQuantity;
    }

    public void issue() {
        if (this.issuedQuantity >= this.totalQuantity) {
            throw new IllegalArgumentException("쿠폰 수량이 모두 소진되었습니다.");
        }

        this.issuedQuantity++;
    }

    @PrePersist
    public void beforePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void beforeUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
