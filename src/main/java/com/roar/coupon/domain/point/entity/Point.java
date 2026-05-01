package com.roar.coupon.domain.point.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "points",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_points_user_id", columnNames = "user_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Point {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private long balance;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Point(Long userId) {
        this.userId = userId;
        this.balance = 0L;
    }

    public void charge(long amount) {
        validatePositiveAmount(amount);
        this.balance += amount;
    }

    public void deduct(long amount) {
        validatePositiveAmount(amount);

        if (this.balance < amount) {
            throw new IllegalArgumentException("포인트 잔액이 금액보다 커야 합니다.");
        }

        this.balance -= amount;
    }

    private void validatePositiveAmount(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("포인트 금액은 0보다 커야 합니다.");
        }
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
