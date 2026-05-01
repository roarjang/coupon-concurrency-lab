package com.roar.coupon.domain.point.controller;

import com.roar.coupon.domain.point.dto.PointBalanceResponse;
import com.roar.coupon.domain.point.dto.PointChargeRequest;
import com.roar.coupon.domain.point.dto.PointDeductRequest;
import com.roar.coupon.domain.point.service.PointService;
import com.roar.coupon.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @PostMapping("/charge")
    public ResponseEntity<PointBalanceResponse> charge(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody PointChargeRequest request
    ) {
        PointBalanceResponse response = pointService.charge(
                userDetails.getUserId(),
                request.amount()
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/deduct")
    public ResponseEntity<PointBalanceResponse> deduct(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody PointDeductRequest request
    ) {
        PointBalanceResponse response = pointService.deduct(
                userDetails.getUserId(),
                request.amount()
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/balance")
    public ResponseEntity<PointBalanceResponse> getBalance(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        PointBalanceResponse response = pointService.getBalance(
                userDetails.getUserId()
        );

        return ResponseEntity.ok(response);
    }
}
