package com.roar.coupon.auth.service;

import com.roar.coupon.auth.dto.LoginRequest;
import com.roar.coupon.auth.dto.LoginResponse;
import com.roar.coupon.auth.jwt.JwtProvider;
import com.roar.coupon.domain.user.entity.User;
import com.roar.coupon.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    public LoginResponse login(LoginRequest request) {
        // 1. email로 User 조회
        User user = userRepository.findByEmail(request.email())
                // 2. 존재하지 않으면 예외
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않은 사용자입니다."));

        // 3. passwordEncoder.matches(rawPassword, encodedPassword)
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            // 4 .실패하면 예외
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        // 5. jwtProvider.generateToken(user.getId(), user.getEmail(), user.getRole())
        String token = jwtProvider.generateToken(
                user.getId(), user.getEmail(), user.getRole());

        // 6. LoginResponse 반환
        return new LoginResponse(token, "Bearer");
    }
}
