package com.roar.coupon.auth.dto;

public record LoginResponse(
        String accessToken,
        String tokenType
) {
}
