package io.github.asllani94.k8s.lock.example;

import io.github.asllani94.k8s.lock.core.DistributedLock;
import io.github.asllani94.k8s.lock.core.DistributedLockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating how to use the distributed lock functionality.
 * This class shows various ways to acquire and use locks across multiple pod instances.
 */
@Component
public class DistributedLockExample implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLockExample.class);

    private final DistributedLockManager lockManager;

    public DistributedLockExample(DistributedLockManager lockManager) {
        this.lockManager = lockManager;
    }

    @Override
    public void run(String... args) {
        logger.info("Distributed Lock Example - demonstrating lock usage");

        // Example 1: Simple lock with executeWithLock
        example1SimpleExecution();

        // Example 2: Lock with timeout
        example2LockWithTimeout();

        // Example 3: Manual lock acquisition and release
        example3ManualLockManagement();

        // Example 4: Lock with return value
        example4LockWithReturnValue();
    }

    private void example1SimpleExecution() {
        logger.info("=== Example 1: Simple execution with lock ===");

        boolean executed = lockManager.executeWithLock("example-lock-1", () -> {
            logger.info("Executing critical section with lock");
            // Simulate some work
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            logger.info("Critical section completed");
        });

        if (executed) {
            logger.info("Lock was acquired and action was executed");
        } else {
            logger.info("Failed to acquire lock, action was not executed");
        }
    }

    private void example2LockWithTimeout() {
        logger.info("=== Example 2: Lock with timeout ===");

        try {
            boolean executed = lockManager.executeWithLock(
                "example-lock-2",
                5, TimeUnit.SECONDS,
                () -> {
                    logger.info("Executing with lock acquired within timeout");
                    // Simulate some work
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            );

            if (executed) {
                logger.info("Lock was acquired within timeout and action was executed");
            } else {
                logger.info("Failed to acquire lock within timeout");
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for lock", e);
            Thread.currentThread().interrupt();
        }
    }

    private void example3ManualLockManagement() {
        logger.info("=== Example 3: Manual lock management ===");

        DistributedLock lock = lockManager.getLock("example-lock-3");

        if (lock.tryLock()) {
            try {
                logger.info("Lock acquired manually, performing work");
                // Simulate some work
                Thread.sleep(1000);

                // Renew the lease if needed
                if (lock.renewLease()) {
                    logger.info("Lease renewed successfully");
                }

                logger.info("Work completed");
            } catch (InterruptedException e) {
                logger.error("Interrupted while holding lock", e);
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
                logger.info("Lock released");
            }
        } else {
            logger.info("Failed to acquire lock");
        }
    }

    private void example4LockWithReturnValue() {
        logger.info("=== Example 4: Lock with return value ===");

        String result = lockManager.executeWithLock("example-lock-4", () -> {
            logger.info("Computing result with lock");
            // Simulate computation
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Error";
            }
            return "Computed Result: " + System.currentTimeMillis();
        });

        if (result != null) {
            logger.info("Lock was acquired and result computed: {}", result);
        } else {
            logger.info("Failed to acquire lock, no result");
        }
    }

}
