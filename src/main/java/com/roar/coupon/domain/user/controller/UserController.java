package com.roar.coupon.domain.user.controller;

import com.roar.coupon.domain.user.dto.SignupRequest;
import com.roar.coupon.domain.user.dto.UserResponse;
import com.roar.coupon.domain.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(
            @Valid @RequestBody SignupRequest request
    ) {
        UserResponse response = userService.signup(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<String> me(
            Authentication authentication
    ) {
        return ResponseEntity.ok("authenticated user id: " + authentication.getName());
    }
}
