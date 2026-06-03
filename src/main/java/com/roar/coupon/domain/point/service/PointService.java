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

    // 4. deductWithPessimisticLock - 포인트 차감 (비관적 락 적용)
    @Transactional
    public PointBalanceResponse deductWithPessimisticLock(Long userId, long amount) {
        Point point = pointRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("포인트 정보를 찾을 수 없습니다."));

        point.deduct(amount);

        return PointBalanceResponse.from(point);
    }

    // 5. deductWithOptimisticLock - 포인트 차감 (낙관적 락 적용)
    @Transactional
    public PointBalanceResponse deductWithOptimisticLock(Long userId, long amount) {
        Point point = pointRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("포인트 정보를 찾을 수 없습니다."));

        point.deduct(amount);

        return PointBalanceResponse.from(point);
    }

    // 6. deductWithAtomicUpdate - 포인트 차감 (조건부 쿼리 적용)
    @Transactional
    public PointBalanceResponse deductWithAtomicUpdate(Long userId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("포인트 금액은 0보다 커야 합니다.");
        }

        int updatedRows = pointRepository.deductIfEnoughBalance(userId, amount);

        if (updatedRows == 0) {
            throw new IllegalArgumentException("포인트 잔액이 금액보다 커야 합니다.");
        }

        Point point = pointRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("포인트 정보를 찾을 수 없습니다."));

        return PointBalanceResponse.from(point);
    }
}
