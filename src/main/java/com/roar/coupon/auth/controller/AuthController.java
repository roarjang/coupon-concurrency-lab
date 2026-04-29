package com.roar.coupon.auth.controller;

/*
 * 로그인 API 흐름
 * 1. 사용자가 email, password로 로그인 요청
 * 2. email로 User 조회
 * 3. 사용자가 없으면 예외
 * 4. passwordEncoder.matches(rawPassword, encodedPassword) 검증
 * 5. 비밀번호가 틀리면 예외
 * 6. JWT 생성
 * 7. accessToken 응답
 */

/*
 * 응답 예시
 * {
 *   "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
 *   "tokenType": "Bearer"
 * }
 */

import com.roar.coupon.auth.dto.LoginRequest;
import com.roar.coupon.auth.dto.LoginResponse;
import com.roar.coupon.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        LoginResponse response = authService.login(request);

        return ResponseEntity.ok(response);
    }
}
