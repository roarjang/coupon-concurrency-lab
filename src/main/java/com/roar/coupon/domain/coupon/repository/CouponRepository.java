package com.roar.coupon.domain.coupon.repository;

import com.roar.coupon.domain.coupon.entity.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :couponId")
    Optional<Coupon> findByIdWithPessimisticLock(@Param("couponId") Long couponId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Coupon c
            set c.issuedQuantity = c.issuedQuantity + 1,
                c.version = c.version + 1,
                c.updatedAt = CURRENT_TIMESTAMP
            where c.id = :couponId
            and c.issuedQuantity < c.totalQuantity
            """)
    int increaseIssuedQuantityIfStockAvailable(@Param("couponId") Long couponId);
}
