package com.roar.coupon.domain.point.service;

import com.roar.coupon.domain.point.entity.Point;
import com.roar.coupon.domain.point.repoistory.PointRepository;
import com.roar.coupon.domain.user.entity.User;
import com.roar.coupon.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
class PointServiceConcurrencyTest {

    @Autowired
    private TestPointService testPointService;

    @Autowired
    private PointService pointService;

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        pointRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("@Transactional만 적용한 포인트 차감은 동시 요청에서 잔액 정합성을 보장하지 못한다")
    void concurrentDeduct_transactionOnly_inconsistentBalance() throws InterruptedException {

        // given
        User user = User.create(
                "concurrency@test.com",
                "encoded-password",
                "concurrency-user"
        );

        User savedUser = userRepository.save(user);

        Point point = new Point(savedUser.getId());
        pointRepository.save(point);

        testPointService.charge(savedUser.getId(), 10000);

        int threadCount = 15;
        long deductAmount = 1000L;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    testPointService.deductWithDelay(savedUser.getId(), deductAmount, 100);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();

        executorService.shutdown();

        // then
        Point result = pointRepository.findByUserId(savedUser.getId())
                .orElseThrow();

        long expectedBalanceBySuccessCount = 10_000L - successCount.get() * deductAmount;

        System.out.println("[concurrentDeduct_transactionOnly_inconsistentBalance]");
        System.out.println("successCount = " + successCount.get());
        System.out.println("failCount = " + failCount.get());
        System.out.println("expectedBalanceBySuccessCount = " + expectedBalanceBySuccessCount);
        System.out.println("actualBalance = " + result.getBalance());

        assertThat(successCount.get() + failCount.get()).isEqualTo(threadCount);
        assertThat(result.getBalance()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("비관적 락을 적용한 포인트 차감은 동시 요청에서도 잔액 정합성을 보장한다")
    void concurrentDeduct_pessimisticLock_consistentBalance() throws InterruptedException {

        // given
        User user = User.create(
                "pessimistic-lock@test.com",
                "encoded-password",
                "pessimistic-lock-user"
        );

        User savedUser = userRepository.save(user);

        Point point = new Point(savedUser.getId());
        pointRepository.save(point);

        testPointService.charge(savedUser.getId(), 10000);

        int threadCount = 15;
        long deductAmount = 1000L;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    pointService.deductWithPessimisticLock(savedUser.getId(), deductAmount);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();

        executorService.shutdown();

        // then
        Point result = pointRepository.findByUserId(savedUser.getId())
                .orElseThrow();

        System.out.println("[concurrentDeduct_pessimisticLock_consistentBalance]");
        System.out.println("successCount = " + successCount.get());
        System.out.println("failCount = " + failCount.get());
        System.out.println("actualBalance = " + result.getBalance());

        assertThat(successCount.get()).isEqualTo(10);
        assertThat(failCount.get()).isEqualTo(5);
        assertThat(result.getBalance()).isEqualTo(0L);
    }

    @Test
    @DisplayName("낙관적 락을 적용한 포인트 차감은 동시 수정 충돌을 감지한다")
    void concurrentDeduct_optimisticLock_detectsConflict() throws InterruptedException {

        // given
        User user = User.create(
                "optimistic-lock@test.com",
                "encoded-password",
                "optimistic-lock-user"
        );

        User savedUser = userRepository.save(user);

        Point point = new Point(savedUser.getId());
        pointRepository.save(point);

        testPointService.charge(savedUser.getId(), 10000);

        int threadCount = 15;
        long deductAmount = 1000L;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        AtomicInteger optimisticLockFailCount = new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    pointService.deductWithOptimisticLock(savedUser.getId(), deductAmount);
                    successCount.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    optimisticLockFailCount.incrementAndGet();
                    failCount.incrementAndGet();
                    System.out.println("optimisticLockException = " + e.getClass().getName());
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.out.println("unexpectedException = " + e.getClass().getName());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();

        executorService.shutdown();

        // then
        Point result = pointRepository.findByUserId(savedUser.getId())
                .orElseThrow();

        long expectedBalanceBySuccessCount = 10_000L - successCount.get() * deductAmount;

        System.out.println("[concurrentDeduct_optimisticLock_detectsConflict]");
        System.out.println("successCount = " + successCount.get());
        System.out.println("failCount = " + failCount.get());
        System.out.println("expectedBalanceBySuccessCount = " + expectedBalanceBySuccessCount);
        System.out.println("actualBalance = " + result.getBalance());

        assertThat(successCount.get()).isLessThan(threadCount);
        assertThat(failCount.get()).isGreaterThan(0);
        assertThat(optimisticLockFailCount.get()).isGreaterThan(0);
        assertThat(failCount.get()).isEqualTo(optimisticLockFailCount.get());
        assertThat(result.getBalance()).isEqualTo(expectedBalanceBySuccessCount);
        assertThat(result.getBalance()).isGreaterThanOrEqualTo(0L);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        TestPointService testPointService(PointRepository pointRepository) {
            return new TestPointService(pointRepository);
        }
    }

    static class TestPointService {

        private final PointRepository pointRepository;

        TestPointService(PointRepository pointRepository) {
            this.pointRepository = pointRepository;
        }

        @Transactional
        public void charge(Long userId, long amount) {
            Point point = pointRepository.findByUserId(userId)
                    .orElseThrow();

            point.charge(amount);
        }

        @Transactional
        public void deductWithDelay(Long userId, long amount, long delayMillis) {
            Point point = pointRepository.findByUserId(userId)
                    .orElseThrow();

            sleep(delayMillis);

            point.deduct(amount);
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
