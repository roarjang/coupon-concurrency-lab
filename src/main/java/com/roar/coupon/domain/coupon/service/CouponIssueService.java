package com.roar.coupon.domain.coupon.service;

import com.roar.coupon.domain.coupon.entity.Coupon;
import com.roar.coupon.domain.coupon.entity.IssuedCoupon;
import com.roar.coupon.domain.coupon.repository.CouponRepository;
import com.roar.coupon.domain.coupon.repository.IssuedCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponIssueService {

    private final CouponRepository couponRepository;
    private final IssuedCouponRepository issuedCouponRepository;

    @Transactional
    public void issueTransactionOnly(Long userId, Long couponId) {

        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다."));

        if (issuedCouponRepository.existsByUserIdAndCouponId(userId, couponId)) {
            throw new IllegalArgumentException("이미 발급받은 쿠폰입니다.");
        }

        coupon.issue();

        issuedCouponRepository.save(IssuedCoupon.issue(userId, couponId));
    }
}
