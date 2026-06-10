package com.roar.coupon.domain.coupon.service;

import com.roar.coupon.domain.coupon.entity.Coupon;
import com.roar.coupon.domain.coupon.entity.IssuedCoupon;
import com.roar.coupon.domain.coupon.repository.CouponRepository;
import com.roar.coupon.domain.coupon.repository.IssuedCouponRepository;
import com.roar.coupon.domain.user.entity.User;
import com.roar.coupon.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private static final long BASELINE_DELAY_MILLIS = 100L;
    private static final long LOCK_HOLD_MILLIS = 5L;
    private static final long LATCH_TIMEOUT_SECONDS = 30L;
    private static final String COUPON_ISSUE_COUNTER_KEY_PREFIX = "coupon:issue:count:";

    private static final int OVERSELLING_INITIAL_STOCK = 100;
    private static final int OVERSELLING_REQUEST_COUNT = 1000;

    private static final int DUPLICATE_ISSUE_SAFETY_STOCK = 1000;
    private static final int DUPLICATE_ISSUE_REQUEST_COUNT = 100;

    private static final int PESSIMISTIC_LOCK_STOCK = 100;
    private static final int PESSIMISTIC_LOCK_REQUEST_COUNT = 1000;

    private static final int OPTIMISTIC_LOCK_STOCK = 100;
    private static final int OPTIMISTIC_LOCK_REQUEST_COUNT = 1000;

    private static final int ATOMIC_UPDATE_STOCK = 100;
    private static final int ATOMIC_UPDATE_REQUEST_COUNT = 1000;

    private static final int REDIS_COUNTER_STOCK = 100;
    private static final int REDIS_COUNTER_REQUEST_COUNT = 1000;

    @Autowired
    private TestCouponIssueService testCouponIssueService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private IssuedCouponRepository issuedCouponRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        couponRepository.deleteAllInBatch();
        issuedCouponRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Disabled("낙관적 락(@Version) 적용 전 transaction-only baseline 실패 재현 테스트 (현재는 git history/docs로 보존)")
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
                BASELINE_DELAY_MILLIS,
                testCouponIssueService::issueTransactionOnlyWithDelay
        );

        // then
        Coupon savedCouponAfterIssue = couponRepository.findById(couponId)
                .orElseThrow();
        long issuedCouponCountByCoupon = issuedCouponRepository.countByCouponId(couponId);

        System.out.println("[Test 1: Transaction Only Can Oversell Coupon Stock]");
        System.out.println("successCount = " + concurrencyResult.successCount());
        System.out.println("failCount = " + concurrencyResult.failCount());
        System.out.println("issuedCouponCountByCoupon = " + issuedCouponCountByCoupon);
        System.out.println("finalIssuedQuantity = " + savedCouponAfterIssue.getIssuedQuantity());

        assertThat(concurrencyResult.totalCount()).isEqualTo(OVERSELLING_REQUEST_COUNT);
        assertThat(issuedCouponCountByCoupon).isGreaterThan(OVERSELLING_INITIAL_STOCK);
        assertThat(savedCouponAfterIssue.getIssuedQuantity()).isLessThanOrEqualTo(OVERSELLING_INITIAL_STOCK);
    }

    @Disabled("DB Unique Constraint 적용 전 baseline 실패 재현 테스트 (현재는 git history/docs로 보존)")
    @Test
    @DisplayName("@Transactional만 적용한 쿠폰 발급은 동일 사용자 동일 쿠폰의 중복 발급을 허용한다")
    void concurrentIssue_transactionOnly_allowsIssueDuplicateCouponToSameUser() throws InterruptedException {

        // given
        User savedUser = userRepository.save(
                User.create(
                        "duplicate@test.com",
                        "encoded-password",
                        "duplicate-user"
                )
        );

        Long couponId = couponRepository.save(
                new Coupon("중복 발급 테스트 쿠폰", DISCOUNT_AMOUNT, DUPLICATE_ISSUE_SAFETY_STOCK)
        ).getId();

        List<Long> duplicateRequestUserIds = createRepeatedUserIds(
                savedUser.getId(),
                DUPLICATE_ISSUE_REQUEST_COUNT
        );

        // when
        ConcurrencyResult concurrencyResult = runConcurrentIssueRequests(
                duplicateRequestUserIds,
                couponId,
                BASELINE_DELAY_MILLIS,
                testCouponIssueService::issueTransactionOnlyWithDelay
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

    @Test
    @DisplayName("DB Unique Constraint을 적용한 쿠폰 발급은 동시 사용자 동일 쿠폰의 중복 발급을 막는다")
    void concurrentIssue_dbUniqueConstraint_preventsDuplicateCouponIssueToSameUser()
        throws InterruptedException {

        // given
        User savedUser = userRepository.save(
                User.create(
                        "duplicate-solve@test.com",
                        "encoded-password",
                        "duplicate-solve-user"
                )
        );

        Long couponId = couponRepository.save(
                new Coupon("중복 쿠폰 발급 해결 테스트", DISCOUNT_AMOUNT, DUPLICATE_ISSUE_SAFETY_STOCK)
        ).getId();

        List<Long> duplicateRequestUserIds = createRepeatedUserIds(savedUser.getId(), DUPLICATE_ISSUE_REQUEST_COUNT);

        // when
        ConcurrencyResult concurrencyResult = runConcurrentIssueRequests(
                duplicateRequestUserIds,
                couponId,
                BASELINE_DELAY_MILLIS,
                testCouponIssueService::issueTransactionOnlyWithUniqueConstraintHandling
        );

        long issuedCouponCountByUserAndCoupon = issuedCouponRepository.countByUserIdAndCouponId(savedUser.getId(), couponId);

        // then
        System.out.println("[Test 3: DB Unique Constraint can Solve Issue Duplicate Coupon To Same User]");
        System.out.println("successCount = " + concurrencyResult.successCount());
        System.out.println("failCount = " + concurrencyResult.failCount());
        System.out.println("issuedCouponCountByUserAndCoupon = " + issuedCouponCountByUserAndCoupon);

        assertThat(concurrencyResult.totalCount()).isEqualTo(DUPLICATE_ISSUE_REQUEST_COUNT);
        assertThat(issuedCouponCountByUserAndCoupon).isEqualTo(1L);
    }

    @Test
    @DisplayName("비관적 락을 적용한 쿠폰 발급은 동시 요청에서도 재고 수량만큼만 발급한다")
    void concurrentIssue_pessimisticLock_preventsCouponStockOverselling()
            throws InterruptedException {

        // given
        List<User> savedUsers = userRepository.saveAll(createUsers(PESSIMISTIC_LOCK_REQUEST_COUNT));

        Coupon coupon = couponRepository.save(
                new Coupon("비관적 락 테스트 쿠폰",
                        DISCOUNT_AMOUNT,
                        PESSIMISTIC_LOCK_STOCK)
        );
        Long couponId = coupon.getId();

        List<Long> userIds = savedUsers.stream()
                .map(User::getId)
                .toList();

        // when
        ConcurrencyResult concurrencyResult = runConcurrentIssueRequests(
                userIds,
                couponId,
                LOCK_HOLD_MILLIS,
                testCouponIssueService::issueWithPessimisticLock
        );

        Coupon savedCouponAfterIssue = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다."));
        long issuedCouponCountByCoupon = issuedCouponRepository.countByCouponId(couponId);

        // then
        System.out.println("[Test 4: Pessimistic Lock Prevents Coupon Stock Overselling]");
        System.out.println("successCount = " + concurrencyResult.successCount());
        System.out.println("failCount = " + concurrencyResult.failCount());
        System.out.println("issuedCouponCountByCoupon = " + issuedCouponCountByCoupon);
        System.out.println("finalIssuedQuantity = " + savedCouponAfterIssue.getIssuedQuantity());

        assertThat(concurrencyResult.totalCount()).isEqualTo(PESSIMISTIC_LOCK_REQUEST_COUNT);
        assertThat(concurrencyResult.successCount()).isEqualTo(PESSIMISTIC_LOCK_STOCK);
        assertThat(concurrencyResult.failCount()).isEqualTo(PESSIMISTIC_LOCK_REQUEST_COUNT - PESSIMISTIC_LOCK_STOCK);
        assertThat(issuedCouponCountByCoupon).isEqualTo(PESSIMISTIC_LOCK_STOCK);
        assertThat(savedCouponAfterIssue.getIssuedQuantity()).isEqualTo(PESSIMISTIC_LOCK_STOCK);
        assertThat((long) savedCouponAfterIssue.getIssuedQuantity()).isEqualTo(issuedCouponCountByCoupon);
        assertThat(savedCouponAfterIssue.getIssuedQuantity()).isLessThanOrEqualTo(savedCouponAfterIssue.getTotalQuantity());
    }

    @Test
    @DisplayName("낙관적 락을 적용한 쿠폰 발급은 동시 요청에서 재고 초과 발급을 막는다")
    void concurrentIssue_optimisticLock_preventsCouponStockOverselling()
        throws InterruptedException {

        // given
        List<User> savedUsers = userRepository.saveAll(
                createUsers(OPTIMISTIC_LOCK_REQUEST_COUNT)
        );

        Coupon coupon = couponRepository.save(
                new Coupon("낙관적 락 테스트 쿠폰",
                        DISCOUNT_AMOUNT,
                        OPTIMISTIC_LOCK_STOCK)
        );
        Long couponId = coupon.getId();

        List<Long> userIds = savedUsers.stream()
                .map(User::getId)
                .toList();

        // when
        ConcurrencyResult concurrencyResult = runConcurrentIssueRequests(
                userIds,
                couponId,
                LOCK_HOLD_MILLIS,
                testCouponIssueService::issueWithOptimisticLock
        );

        Coupon savedCouponAfterIssue = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다."));
        long issuedCouponCountByCoupon = issuedCouponRepository.countByCouponId(couponId);

        // then
        System.out.println("[Test 5: Optimistic Lock Prevents Coupon Stock Overselling");
        System.out.println("successCount = " + concurrencyResult.successCount());
        System.out.println("failCount = " + concurrencyResult.failCount());
        System.out.println("issuedCouponQuantity = " + issuedCouponCountByCoupon);
        System.out.println("finalIssuedQuantity = " + savedCouponAfterIssue.getIssuedQuantity());

        assertThat(concurrencyResult.totalCount()).isEqualTo(OPTIMISTIC_LOCK_REQUEST_COUNT);
        assertThat(concurrencyResult.successCount()).isGreaterThan(0);
        assertThat(concurrencyResult.successCount()).isLessThanOrEqualTo(OPTIMISTIC_LOCK_STOCK);
        assertThat(concurrencyResult.failCount()).isEqualTo(OPTIMISTIC_LOCK_REQUEST_COUNT - OPTIMISTIC_LOCK_STOCK);

        assertThat(issuedCouponCountByCoupon).isEqualTo(concurrencyResult.successCount());
        assertThat(savedCouponAfterIssue.getIssuedQuantity()).isEqualTo(concurrencyResult.successCount());
        assertThat(savedCouponAfterIssue.getIssuedQuantity()).isLessThanOrEqualTo(OPTIMISTIC_LOCK_STOCK);
    }

    @Test
    @DisplayName("원자적 업데이트를 적용한 쿠폰 발급은 동시 요청에서 재고 초과 발급을 막는다")
    void concurrentIssue_atomicUpdate_preventsCouponStockOverselling()
        throws InterruptedException {

        // given
        List<User> savedUsers = userRepository.saveAll(
                createUsers(ATOMIC_UPDATE_REQUEST_COUNT)
        );

        Coupon coupon = couponRepository.save(
                new Coupon("원자적 업데이트 테스트 쿠폰",
                        DISCOUNT_AMOUNT,
                        ATOMIC_UPDATE_STOCK)
        );
        Long couponId = coupon.getId();

        List<Long> userIds = savedUsers.stream()
                .map(User::getId)
                .toList();

        // when
        ConcurrencyResult concurrencyResult = runConcurrentIssueRequests(
                userIds,
                couponId,
                LOCK_HOLD_MILLIS,
                testCouponIssueService::issueWithAtomicUpdate
        );

        Coupon savedCouponAfterIssue = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다."));

        long issuedCouponCountByCoupon = issuedCouponRepository.countByCouponId(couponId);

        // then
        System.out.println("[Test 6: Atomic Update Prevents Coupon Stock Overselling]");
        System.out.println("successCount = " + concurrencyResult.successCount());
        System.out.println("failCount = " + concurrencyResult.failCount());
        System.out.println("issuedCouponCountByCoupon = " + issuedCouponCountByCoupon);
        System.out.println("finalIssuedQuantity = " + savedCouponAfterIssue.getIssuedQuantity());

        assertThat(concurrencyResult.totalCount()).isEqualTo(ATOMIC_UPDATE_REQUEST_COUNT);
        assertThat(concurrencyResult.successCount()).isEqualTo(ATOMIC_UPDATE_STOCK);
        assertThat(concurrencyResult.failCount()).isEqualTo(ATOMIC_UPDATE_REQUEST_COUNT - ATOMIC_UPDATE_STOCK);

        assertThat(issuedCouponCountByCoupon).isEqualTo(ATOMIC_UPDATE_STOCK);
        assertThat(savedCouponAfterIssue.getIssuedQuantity()).isEqualTo(ATOMIC_UPDATE_STOCK);
        assertThat(issuedCouponCountByCoupon).isEqualTo(savedCouponAfterIssue.getIssuedQuantity());
        assertThat(savedCouponAfterIssue.getIssuedQuantity()).isLessThanOrEqualTo(savedCouponAfterIssue.getTotalQuantity());
    }

    @Test
    @DisplayName("Redis Counter를 적용한 쿠폰 발급은 동시 요청에서 재고 초과 발급을 막는다")
    void concurrentIssue_redisCounter_preventsCouponStockOverselling()
        throws InterruptedException {

        // given
        List<User> savedUsers = userRepository.saveAll(
                createUsers(REDIS_COUNTER_REQUEST_COUNT)
        );

        Coupon coupon = couponRepository.save(
                new Coupon(
                        "Redis Counter 테스트 쿠폰",
                        DISCOUNT_AMOUNT,
                        REDIS_COUNTER_STOCK
                )
        );
        Long couponId = coupon.getId();

        String redisKey = CouponRedisKeys.issueCounter(couponId);
        stringRedisTemplate.delete(redisKey);

        List<Long> userIds = savedUsers.stream()
                .map(User::getId)
                .toList();

        // when
        ConcurrencyResult concurrencyResult = runConcurrentIssueRequests(
                userIds,
                couponId,
                0L,
                testCouponIssueService::issueWithRedisCounterGate
        );

        Coupon savedCouponAfterIssue = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다."));
        long issuedCouponCountByCoupon = issuedCouponRepository.countByCouponId(couponId);
        String redisCounterValue = stringRedisTemplate.opsForValue().get(redisKey);

        // then
        System.out.println("[Test 7: Redis Counter Prevents Coupon Stock Overselling]");
        System.out.println("successCount = " + concurrencyResult.successCount());
        System.out.println("failCount = " + concurrencyResult.failCount());
        System.out.println("issuedCouponCountByCoupon = " + issuedCouponCountByCoupon);
        System.out.println("finalIssuedQuantity = " + savedCouponAfterIssue.getIssuedQuantity());

        assertThat(concurrencyResult.totalCount()).isEqualTo(REDIS_COUNTER_REQUEST_COUNT);
        assertThat(concurrencyResult.successCount()).isEqualTo(REDIS_COUNTER_STOCK);
        assertThat(concurrencyResult.failCount()).isEqualTo(REDIS_COUNTER_REQUEST_COUNT - REDIS_COUNTER_STOCK);

        assertThat(issuedCouponCountByCoupon).isEqualTo(REDIS_COUNTER_STOCK);
        assertThat(savedCouponAfterIssue.getIssuedQuantity()).isEqualTo(REDIS_COUNTER_STOCK);
        assertThat(redisCounterValue).isEqualTo(String.valueOf(REDIS_COUNTER_STOCK));
        assertThat(savedCouponAfterIssue.getIssuedQuantity()).isLessThanOrEqualTo(savedCouponAfterIssue.getTotalQuantity());
    }

    private ConcurrencyResult runConcurrentIssueRequests(
            List<Long> userIds,
            Long couponId,
            long delayMillis,
            CouponIssueAction issueStrategy
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

                    boolean started = startLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (!started) {
                        throw new IllegalStateException("시작 신호를 제한 시간내에 받지 못했습니다.");
                    }

                    issueStrategy.issue(userId, couponId, delayMillis);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failCount.incrementAndGet();
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

    @FunctionalInterface
    interface CouponIssueAction {
        void issue(Long userId, Long couponId, long delayMillis);
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
                IssuedCouponRepository issuedCouponRepository,
                StringRedisTemplate stringRedisTemplate
        ) {
            return new TestCouponIssueService(
                    couponRepository,
                    issuedCouponRepository,
                    stringRedisTemplate
            );
        }
    }

    static class TestCouponIssueService {

        private final CouponRepository couponRepository;
        private final IssuedCouponRepository issuedCouponRepository;
        private final StringRedisTemplate stringRedisTemplate;

        TestCouponIssueService(
                CouponRepository couponRepository,
                IssuedCouponRepository issuedCouponRepository,
                StringRedisTemplate stringRedisTemplate
        ) {
            this.couponRepository = couponRepository;
            this.issuedCouponRepository = issuedCouponRepository;
            this.stringRedisTemplate = stringRedisTemplate;
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

        @Transactional
        public void issueTransactionOnlyWithUniqueConstraintHandling(
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
            try {
                issuedCouponRepository.saveAndFlush(IssuedCoupon.issue(userId, couponId));
            } catch (DataIntegrityViolationException e) {
                throw new IllegalArgumentException("이미 발급받은 쿠폰입니다.", e);
            }
        }

        @Transactional
        public void issueWithPessimisticLock(
                Long userId,
                Long couponId,
                long holdMillis
        ) {
            Coupon coupon = couponRepository.findByIdWithPessimisticLock(couponId)
                    .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다."));

            if (issuedCouponRepository.existsByUserIdAndCouponId(userId, couponId)) {
                throw new IllegalArgumentException("이미 발급받은 쿠폰입니다.");
            }

            sleep(holdMillis);

            coupon.issue();
            issuedCouponRepository.save(IssuedCoupon.issue(userId, couponId));
        }

        @Transactional
        public void issueWithOptimisticLock(
                Long userId,
                Long couponId,
                long holdMillis
        ) {
            Coupon coupon = couponRepository.findById(couponId)
                    .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다."));

            if (issuedCouponRepository.existsByUserIdAndCouponId(userId, couponId)) {
                throw new IllegalArgumentException("이미 발급받은 쿠폰입니다.");
            }

            sleep(holdMillis);

            coupon.issue();
            issuedCouponRepository.save(IssuedCoupon.issue(userId, couponId));
        }

        @Transactional
        public void issueWithAtomicUpdate(
                Long userId,
                Long couponId,
                long holdMillis
        ) {
            if (issuedCouponRepository.existsByUserIdAndCouponId(userId, couponId)) {
                throw new IllegalArgumentException("이미 발급받은 쿠폰입니다.");
            }

            sleep(holdMillis);

            int updatedRows = couponRepository.increaseIssuedQuantityIfStockAvailable(couponId);

            if (updatedRows == 0) {
                throw new IllegalArgumentException("쿠폰 수량이 모두 소진되었습니다.");
            }

            issuedCouponRepository.save(IssuedCoupon.issue(userId, couponId));
        }

        @Transactional
        public void issueWithRedisCounterGate(
                Long userId,
                Long couponId,
                long holdMillis
        ) {
            String key = CouponRedisKeys.issueCounter(couponId);
            Long current = stringRedisTemplate.opsForValue().increment(key);

            if (current == null) {
                throw new IllegalStateException("Redis counter가 실패했습니다.");
            }

            if (current > REDIS_COUNTER_STOCK) {
                compensateRedisCounter(key);
                throw new IllegalArgumentException("쿠폰이 모두 소진됐습니다.");
            }

            if (holdMillis > 0) {
                sleep(holdMillis);
            }

            try {
                if (issuedCouponRepository.existsByUserIdAndCouponId(userId, couponId)) {
                    throw new IllegalArgumentException("이미 발급받은 쿠폰입니다.");
                }

                int updatedRows = couponRepository.increaseIssuedQuantityIfStockAvailable(couponId);
                if (updatedRows == 0) {
                    throw new IllegalArgumentException("쿠폰이 모두 소진됐습니다.");
                }

                issuedCouponRepository.save(IssuedCoupon.issue(userId, couponId));
            } catch (RuntimeException e) {
                compensateRedisCounter(key);
                throw e;
            }
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        private void compensateRedisCounter(String key) {
            stringRedisTemplate.opsForValue().decrement(key);
        }
    }

    private static final class CouponRedisKeys {
        static String issueCounter(Long couponId) {
            return COUPON_ISSUE_COUNTER_KEY_PREFIX + couponId;
        }

        private CouponRedisKeys() {}
    }
}
