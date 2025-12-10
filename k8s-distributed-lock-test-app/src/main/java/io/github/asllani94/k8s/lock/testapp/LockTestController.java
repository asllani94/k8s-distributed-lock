package io.github.asllani94.k8s.lock.testapp;

import io.github.asllani94.k8s.lock.core.DistributedLockManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/locks")
public class LockTestController {

    @Autowired
    private DistributedLockManager lockManager;

    @PostMapping("/{lockName}/acquire")
    public Map<String, Object> acquireLock(@PathVariable String lockName) {
        Map<String, Object> response = new HashMap<>();

        boolean acquired = lockManager.executeWithLock(lockName, () -> {
            try {
                // Simulate some work
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        response.put("lockName", lockName);
        response.put("acquired", acquired);
        response.put("timestamp", System.currentTimeMillis());

        return response;
    }

    @PostMapping("/{lockName}/acquire-with-timeout")
    public Map<String, Object> acquireLockWithTimeout(
            @PathVariable String lockName,
            @RequestParam(defaultValue = "5") long timeout) throws InterruptedException {

        Map<String, Object> response = new HashMap<>();

        boolean acquired = lockManager.executeWithLock(lockName, timeout, TimeUnit.SECONDS, () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        response.put("lockName", lockName);
        response.put("acquired", acquired);
        response.put("timeout", timeout);
        response.put("timestamp", System.currentTimeMillis());

        return response;
    }

    @GetMapping("/{lockName}/status")
    public Map<String, Object> getLockStatus(@PathVariable String lockName) {
        Map<String, Object> response = new HashMap<>();

        boolean held = lockManager.isLockHeld(lockName);

        response.put("lockName", lockName);
        response.put("held", held);
        response.put("timestamp", System.currentTimeMillis());

        return response;
    }

    @PostMapping("/{lockName}/release")
    public Map<String, Object> releaseLock(@PathVariable String lockName) {
        Map<String, Object> response = new HashMap<>();

        lockManager.releaseLock(lockName);

        response.put("lockName", lockName);
        response.put("released", true);
        response.put("timestamp", System.currentTimeMillis());

        return response;
    }

    @GetMapping("/test/concurrent")
    public Map<String, Object> testConcurrentAccess() throws InterruptedException {
        Map<String, Object> response = new HashMap<>();
        String lockName = "concurrent-test-lock";

        AtomicInteger successCount = new AtomicInteger(0);
        int threadCount = 5;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Launch 5 threads that all try to acquire the lock at the same time
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    boolean acquired = lockManager.executeWithLock(lockName, () -> {
                        // Simulate work for 2 seconds
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    if (acquired) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete (with timeout)
        latch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        response.put("lockName", lockName);
        response.put("attempts", threadCount);
        response.put("successful", successCount.get());
        response.put("note", "Only 1 should succeed at a time in true distributed lock");

        return response;
    }

    @GetMapping("/test/acquire-release-flow")
    public Map<String, Object> testAcquireReleaseFlow() throws InterruptedException {
        Map<String, Object> response = new HashMap<>();
        String lockName = "acquire-release-test-lock";

        // Step 1: Manually acquire lock with auto-renewal
        boolean acquired = lockManager.acquireLockWithAutoRenewal(lockName, 5, TimeUnit.SECONDS);
        response.put("step1_acquired", acquired);

        if (acquired) {
            // Step 2: Verify lock is held
            boolean held = lockManager.isLockHeld(lockName);
            response.put("step2_held_after_acquire", held);

            // Step 3: Try to acquire same lock in another "thread" (should fail)
            boolean secondAcquire = lockManager.executeWithLock(lockName, () -> {
                // This should not execute
            });
            response.put("step3_second_acquire_failed", !secondAcquire);

            // Step 4: Release the lock
            lockManager.releaseLock(lockName);
            response.put("step4_released", true);

            // Step 5: Verify lock is no longer held
            boolean heldAfterRelease = lockManager.isLockHeld(lockName);
            response.put("step5_held_after_release", heldAfterRelease);

            // Step 6: Now try to acquire it again (should succeed)
            boolean reacquired = lockManager.executeWithLock(lockName, () -> {
                // This should execute now
            });
            response.put("step6_reacquired_after_release", reacquired);
        }

        response.put("test", "acquire-release-flow");
        response.put("success", acquired && !response.get("step5_held_after_release").equals(true));

        return response;
    }

    @GetMapping("/test/release-enables-next-thread")
    public Map<String, Object> testReleaseEnablesNextThread() throws InterruptedException {
        Map<String, Object> response = new HashMap<>();
        String lockName = "release-next-thread-lock";

        CountDownLatch thread1Acquired = new CountDownLatch(1);
        CountDownLatch thread1CanRelease = new CountDownLatch(1);
        CountDownLatch thread2Acquired = new CountDownLatch(1);

        AtomicInteger thread1Result = new AtomicInteger(0);
        AtomicInteger thread2Result = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Acquire lock, wait, then release
        executor.submit(() -> {
            try {
                boolean acquired = lockManager.executeWithLock(lockName, 5, TimeUnit.SECONDS, () -> {
                    thread1Result.set(1); // Mark as acquired
                    thread1Acquired.countDown();

                    // Wait for signal to release
                    try {
                        thread1CanRelease.await(10, TimeUnit.SECONDS);
                        Thread.sleep(500); // Hold lock a bit longer
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Thread 2: Wait for thread 1 to acquire, then try to acquire with timeout
        executor.submit(() -> {
            try {
                // Wait for thread 1 to acquire the lock
                thread1Acquired.await(5, TimeUnit.SECONDS);

                // Now try to acquire (should wait for thread 1 to release)
                boolean acquired = lockManager.executeWithLock(lockName, 10, TimeUnit.SECONDS, () -> {
                    thread2Result.set(1); // Mark as acquired
                    thread2Acquired.countDown();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Wait a bit, then signal thread 1 to release
        Thread.sleep(1000);
        thread1CanRelease.countDown();

        // Wait for thread 2 to acquire
        boolean thread2Succeeded = thread2Acquired.await(15, TimeUnit.SECONDS);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        response.put("lockName", lockName);
        response.put("thread1_acquired", thread1Result.get() == 1);
        response.put("thread2_acquired_after_thread1_released", thread2Succeeded && thread2Result.get() == 1);
        response.put("test", "release-enables-next-thread");
        response.put("success", thread2Succeeded);

        return response;
    }

    @GetMapping("/test/release-without-holding")
    public Map<String, Object> testReleaseWithoutHolding() {
        Map<String, Object> response = new HashMap<>();
        String lockName = "release-without-holding-lock";

        // Check initial state
        boolean initiallyHeld = lockManager.isLockHeld(lockName);
        response.put("initially_held", initiallyHeld);

        // Try to release a lock we don't hold (should handle gracefully)
        try {
            lockManager.releaseLock(lockName);
            response.put("release_succeeded", true);
            response.put("error", false);
        } catch (Exception e) {
            response.put("release_succeeded", false);
            response.put("error", true);
            response.put("error_message", e.getMessage());
        }

        // Verify still not held
        boolean stillNotHeld = lockManager.isLockHeld(lockName);
        response.put("still_not_held", !stillNotHeld);

        response.put("test", "release-without-holding");
        response.put("success", !initiallyHeld && !stillNotHeld);

        return response;
    }

    @GetMapping("/test/multiple-acquire-release-cycles")
    public Map<String, Object> testMultipleAcquireReleaseCycles() {
        Map<String, Object> response = new HashMap<>();
        String lockName = "multiple-cycles-lock";

        int cycles = 10;
        int successfulCycles = 0;
        List<Map<String, Object>> cycleResults = new ArrayList<>();

        for (int i = 0; i < cycles; i++) {
            Map<String, Object> cycleResult = new HashMap<>();
            int cycleNum = i + 1;

            // Acquire
            boolean acquired = lockManager.executeWithLock(lockName, () -> {
                try {
                    Thread.sleep(100); // Small work simulation
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            cycleResult.put("cycle", cycleNum);
            cycleResult.put("acquired", acquired);

            if (acquired) {
                // Verify released after executeWithLock completes
                boolean heldAfter = lockManager.isLockHeld(lockName);
                cycleResult.put("properly_released", !heldAfter);

                if (!heldAfter) {
                    successfulCycles++;
                }
            }

            cycleResults.add(cycleResult);
        }

        response.put("lockName", lockName);
        response.put("total_cycles", cycles);
        response.put("successful_cycles", successfulCycles);
        response.put("all_cycles_successful", successfulCycles == cycles);
        response.put("cycle_details", cycleResults);
        response.put("test", "multiple-acquire-release-cycles");

        return response;
    }
}