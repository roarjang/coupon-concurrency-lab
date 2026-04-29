package com.roar.coupon.domain.user.dto;

import com.roar.coupon.domain.user.entity.User;
import com.roar.coupon.domain.user.entity.UserRole;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String email,
        String username,
        UserRole role,
        LocalDateTime createAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}
