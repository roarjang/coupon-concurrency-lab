package com.roar.coupon.domain.coupon.service;

import com.roar.coupon.domain.coupon.entity.Coupon;
import com.roar.coupon.domain.coupon.entity.IssuedCoupon;
import com.roar.coupon.domain.coupon.repository.CouponRepository;
import com.roar.coupon.domain.coupon.repository.IssuedCouponRepository;
import com.roar.coupon.domain.user.entity.User;
import com.roar.coupon.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
class CouponIssueConcurrencyTest {

    private static final long DISCOUNT_AMOUNT = 500L;
    private static final long ARTIFICIAL_DELAY_MILLIS = 100L;
    private static final long LATCH_TIMEOUT_SECONDS = 30L;

    private static final int OVERSELLING_INITIAL_STOCK = 100;
    private static final int OVERSELLING_REQUEST_COUNT = 1000;

    private static final int DUPLICATE_ISSUE_STOCK = 1000;
    private static final int DUPLICATE_ISSUE_REQUEST_COUNT = 100;

    @Autowired
    private TestCouponIssueService testCouponIssueService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private IssuedCouponRepository issuedCouponRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        couponRepository.deleteAllInBatch();
        issuedCouponRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("@Transactional만 적용한 쿠폰 발급은 동시 요청에서 재고 초과 발급을 허용한다")
    void concurrentIssue_transactionOnly_canOversellCouponStock() throws InterruptedException {

        // given
        List<User> savedUsers = userRepository.saveAll(createUsers(OVERSELLING_REQUEST_COUNT));

        Coupon savedCoupon = couponRepository.save(
                new Coupon("선착순 쿠폰", DISCOUNT_AMOUNT, OVERSELLING_INITIAL_STOCK)
        );
        Long couponId = savedCoupon.getId();

        List<Long> userIds = savedUsers.stream()
                .map(User::getId)
                .toList();

        // when
        ConcurrencyResult concurrencyResult = runConcurrentIssueRequests(
                userIds,
                couponId,
                ARTIFICIAL_DELAY_MILLIS
        );

        // then
        Coupon result = couponRepository.findById(couponId)
                .orElseThrow();
        long issuedCouponCount = issuedCouponRepository.countByCouponId(couponId);

        System.out.println("[Test 1: Transaction Only Can Oversell Coupon Stock]");
        System.out.println("successCount = " + concurrencyResult.successCount());
        System.out.println("failCount = " + concurrencyResult.failCount());
        System.out.println("issuedCouponCount = " + issuedCouponCount);
        System.out.println("finalIssuedQuantity = " + result.getIssuedQuantity());

        assertThat(concurrencyResult.totalCount()).isEqualTo(OVERSELLING_REQUEST_COUNT);
        assertThat(issuedCouponCount).isGreaterThan((long) OVERSELLING_INITIAL_STOCK);
        assertThat(result.getIssuedQuantity()).isLessThanOrEqualTo(OVERSELLING_INITIAL_STOCK);
    }

    @Test
    @DisplayName("@Transactional만 적용한 쿠폰 발급은 동일 사용자 중복 발급을 허용한다")
    void concurrentIssue_transactionOnly_canIssueDuplicateCouponToSameUser() throws InterruptedException {

        // given
        User savedUser = userRepository.save(
                User.create(
                        "duplicate@test.com",
                        "encoded-password",
                        "duplicate-user"
                )
        );

        Coupon savedCoupon = couponRepository.save(
                new Coupon("중복 발급 테스트 쿠폰", DISCOUNT_AMOUNT, DUPLICATE_ISSUE_STOCK)
        );
        Long couponId = savedCoupon.getId();

        List<Long> duplicateRequestUserIds = createRepeatedUserIds(
                savedUser.getId(),
                DUPLICATE_ISSUE_REQUEST_COUNT
        );

        // when
        ConcurrencyResult concurrencyResult = runConcurrentIssueRequests(
                duplicateRequestUserIds,
                couponId,
                ARTIFICIAL_DELAY_MILLIS
        );

        // then
        long issuedCouponCountByUserAndCoupon = issuedCouponRepository.countByUserIdAndCouponId(
                savedUser.getId(),
                couponId
        );

        System.out.println("[Test 2: Transaction Only Can Issue Duplicate Coupon To Same User]");
        System.out.println("successCount = " + concurrencyResult.successCount());
        System.out.println("failCount = " + concurrencyResult.failCount());
        System.out.println("issuedCouponCountByUserAndCoupon = " + issuedCouponCountByUserAndCoupon);

        assertThat(concurrencyResult.totalCount()).isEqualTo(DUPLICATE_ISSUE_REQUEST_COUNT);
        assertThat(issuedCouponCountByUserAndCoupon).isGreaterThan(1L);
    }

    private ConcurrencyResult runConcurrentIssueRequests(
            List<Long> userIds,
            Long couponId,
            long delayMillis
    ) throws InterruptedException {
        int requestCount = userIds.size();

        ExecutorService executorService = Executors.newFixedThreadPool(requestCount);

        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(requestCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (Long userId : userIds) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    testCouponIssueService.issueTransactionOnlyWithDelay(
                            userId,
                            couponId,
                            delayMillis
                    );

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertThat(readyLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        startLatch.countDown();

        assertThat(doneLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        executorService.shutdown();

        return new ConcurrencyResult(
                successCount.get(),
                failCount.get()
        );
    }

    private List<User> createUsers(int count) {
        List<User> users = new ArrayList<>();

        for (int i = 1; i <= count; i++) {
            users.add(
                    User.create(
                            "user-" + i + "@test.com",
                            "encoded-password",
                            "user-" + i
                    )
            );
        }

        return users;
    }

    private List<Long> createRepeatedUserIds(Long userId, int count) {
        List<Long> userIds = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            userIds.add(userId);
        }

        return userIds;
    }

    private record ConcurrencyResult(
            int successCount,
            int failCount
    ) {
        int totalCount() {
            return successCount + failCount;
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        TestCouponIssueService testCouponIssueService(
                CouponRepository couponRepository,
                IssuedCouponRepository issuedCouponRepository
        ) {
            return new TestCouponIssueService(
                    couponRepository,
                    issuedCouponRepository
            );
        }
    }

    static class TestCouponIssueService {

        private final CouponRepository couponRepository;
        private final IssuedCouponRepository issuedCouponRepository;

        TestCouponIssueService(
                CouponRepository couponRepository,
                IssuedCouponRepository issuedCouponRepository
        ) {
            this.couponRepository = couponRepository;
            this.issuedCouponRepository = issuedCouponRepository;
        }

        @Transactional
        public void issueTransactionOnlyWithDelay(
                Long userId,
                Long couponId,
                long delayMillis
        ) {
            Coupon coupon = couponRepository.findById(couponId)
                    .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다."));

            if (issuedCouponRepository.existsByUserIdAndCouponId(userId, couponId)) {
                throw new IllegalArgumentException("이미 발급받은 쿠폰입니다.");
            }

            sleep(delayMillis);

            coupon.issue();

            issuedCouponRepository.save(IssuedCoupon.issue(userId, couponId));
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}