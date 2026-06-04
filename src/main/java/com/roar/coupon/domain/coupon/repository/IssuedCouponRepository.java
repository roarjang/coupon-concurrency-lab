package com.roar.coupon.domain.coupon.repository;

import com.roar.coupon.domain.coupon.entity.IssuedCoupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssuedCouponRepository extends JpaRepository<IssuedCoupon, Long> {

    boolean existsByUserIdAndCouponId(Long userId, Long couponId);

    long countByCouponId(Long couponId);

    long countByUserIdAndCouponId(Long userId, Long couponId);
}
