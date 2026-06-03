package com.roar.coupon.domain.point.repoistory;

import com.roar.coupon.domain.point.entity.Point;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PointRepository extends JpaRepository<Point, Long> {

    Optional<Point> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Point p where p.userId = :userId")
    Optional<Point> findByUserIdForUpdate(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Point p
            set p.balance = p.balance - :amount,
                p.version = p.version + 1
            where p.userId = :userId
            and p.balance >= :amount
            """)
    int deductIfEnoughBalance(
            @Param("userId") Long userId,
            @Param("amount") long amount
    );
}
