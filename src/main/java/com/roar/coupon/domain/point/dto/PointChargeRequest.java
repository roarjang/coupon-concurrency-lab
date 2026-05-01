package com.roar.coupon.domain.point.dto;

import jakarta.validation.constraints.Positive;

public record PointChargeRequest(
        @Positive(message = "충전 금액은 0보다 커야 합니다.")
        long amount
) {
}
