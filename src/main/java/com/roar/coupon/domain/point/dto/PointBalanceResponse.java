package com.roar.coupon.domain.point.dto;

import com.roar.coupon.domain.point.entity.Point;

public record PointBalanceResponse(
        Long userId,
        long balance
) {

    public static PointBalanceResponse from(Point point) {
        return new PointBalanceResponse(
                point.getUserId(),
                point.getBalance()
        );
    }
}
