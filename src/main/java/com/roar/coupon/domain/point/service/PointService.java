package com.roar.coupon.domain.point.service;

import com.roar.coupon.domain.point.dto.PointBalanceResponse;
import com.roar.coupon.domain.point.entity.Point;
import com.roar.coupon.domain.point.repoistory.PointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointService {

    private final PointRepository pointRepository;

    // 1. charge - 포인트 충전
    @Transactional
    public PointBalanceResponse charge(Long userId, long amount) {
        Point point = pointRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("포인트 정보를 찾을 수 없습니다."));

        point.charge(amount);

        return PointBalanceResponse.from(point);
    }

    // 2. deduct - 포인트 차감
    @Transactional
    public PointBalanceResponse deduct(Long userId, long amount) {
        Point point = pointRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("포인트 정보를 찾을 수 없습니다."));

        point.deduct(amount);

        return PointBalanceResponse.from(point);
    }

    // 3. getBalance - 잔액 조회
    public PointBalanceResponse getBalance(Long userId) {
        Point point = pointRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("포인트 정보를 찾을 수 없습니다."));

        return PointBalanceResponse.from(point);
    }
}
