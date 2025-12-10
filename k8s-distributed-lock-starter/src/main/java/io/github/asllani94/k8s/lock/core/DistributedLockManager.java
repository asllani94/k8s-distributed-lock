package io.github.asllani94.k8s.lock.core;

import io.github.asllani94.k8s.lock.config.KubernetesLockProperties;
import io.kubernetes.client.openapi.ApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Manager for creating and managing distributed locks using Kubernetes Lease API.
 * Provides convenient methods to acquire locks and execute code within lock boundaries.
 */
@Service
public class DistributedLockManager {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLockManager.class);

    private final ApiClient apiClient;
    private final KubernetesLockProperties properties;
    private final Map<String, DistributedLock> locks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService leaseRenewalExecutor = Executors.newScheduledThreadPool(2);

    public DistributedLockManager(ApiClient apiClient, KubernetesLockProperties properties) {
        this.apiClient = apiClient;
        this.properties = properties;
    }

    /**
     * Creates or retrieves a distributed lock with the given name.
     *
     * @param lockName the name of the lock
     * @return the distributed lock instance
     */
    public DistributedLock getLock(String lockName) {
        return getLock(lockName, Duration.ofSeconds(properties.getLock().getLeaseDurationSeconds()));
    }

    /**
     * Creates or retrieves a distributed lock with the given name and lease duration.
     *
     * @param lockName the name of the lock
     * @param leaseDuration the duration of the lease
     * @return the distributed lock instance
     */
    public DistributedLock getLock(String lockName, Duration leaseDuration) {
        return locks.computeIfAbsent(lockName,
            name -> new KubernetesLeaseLock(apiClient, name, properties.getNamespace(), leaseDuration));
    }

    /**
     * Attempts to acquire a lock and execute the provided action within the lock.
     * The lock is automatically released after the action completes.
     *
     * @param lockName the name of the lock
     * @param action the action to execute while holding the lock
     * @return true if the lock was acquired and the action was executed, false otherwise
     */
    public boolean executeWithLock(String lockName, Runnable action) {
        DistributedLock lock = getLock(lockName);

        if (lock.tryLock()) {
            try {
                logger.info("Acquired lock: {}, executing action", lockName);
                action.run();
                return true;
            } finally {
                lock.unlock();
                logger.info("Released lock: {}", lockName);
            }
        } else {
            logger.debug("Failed to acquire lock: {}", lockName);
            return false;
        }
    }

    /**
     * Attempts to acquire a lock with a timeout and execute the provided action within the lock.
     * The lock is automatically released after the action completes.
     *
     * @param lockName the name of the lock
     * @param timeout the maximum time to wait for the lock
     * @param unit the time unit of the timeout argument
     * @param action the action to execute while holding the lock
     * @return true if the lock was acquired and the action was executed, false otherwise
     * @throws InterruptedException if the current thread is interrupted while acquiring the lock
     */
    public boolean executeWithLock(String lockName, long timeout, TimeUnit unit, Runnable action)
            throws InterruptedException {
        DistributedLock lock = getLock(lockName);

        if (lock.tryLock(timeout, unit)) {
            try {
                logger.info("Acquired lock: {}, executing action", lockName);
                action.run();
                return true;
            } finally {
                lock.unlock();
                logger.info("Released lock: {}", lockName);
            }
        } else {
            logger.debug("Failed to acquire lock: {} within timeout", lockName);
            return false;
        }
    }

    /**
     * Attempts to acquire a lock and execute the provided supplier within the lock.
     * The lock is automatically released after the supplier completes.
     *
     * @param lockName the name of the lock
     * @param supplier the supplier to execute while holding the lock
     * @param <T> the type of result
     * @return the result from the supplier if the lock was acquired, null otherwise
     */
    public <T> T executeWithLock(String lockName, Supplier<T> supplier) {
        DistributedLock lock = getLock(lockName);

        if (lock.tryLock()) {
            try {
                logger.info("Acquired lock: {}, executing supplier", lockName);
                return supplier.get();
            } finally {
                lock.unlock();
                logger.info("Released lock: {}", lockName);
            }
        } else {
            logger.debug("Failed to acquire lock: {}", lockName);
            return null;
        }
    }

    /**
     * Acquires a lock with automatic lease renewal.
     * The lock will be automatically renewed at regular intervals until explicitly released.
     *
     * @param lockName the name of the lock
     * @param timeout the maximum time to wait for the lock
     * @param unit the time unit of the timeout argument
     * @return true if the lock was acquired, false otherwise
     * @throws InterruptedException if the current thread is interrupted while acquiring the lock
     */
    public boolean acquireLockWithAutoRenewal(String lockName, long timeout, TimeUnit unit)
            throws InterruptedException {
        DistributedLock lock = getLock(lockName);

        if (lock.tryLock(timeout, unit)) {
            // Schedule automatic lease renewal
            long renewalInterval = properties.getLock().getRenewIntervalSeconds();
            leaseRenewalExecutor.scheduleAtFixedRate(
                () -> {
                    if (lock.isHeldByCurrentInstance()) {
                        if (!lock.renewLease()) {
                            logger.warn("Failed to renew lease for lock: {}", lockName);
                        }
                    }
                },
                renewalInterval,
                renewalInterval,
                TimeUnit.SECONDS
            );

            logger.info("Acquired lock: {} with automatic renewal", lockName);
            return true;
        }

        return false;
    }

    /**
     * Releases a lock.
     *
     * @param lockName the name of the lock to release
     */
    public void releaseLock(String lockName) {
        DistributedLock lock = getLock(lockName);
        if (lock != null) {
            lock.unlock();
        }
    }

    /**
     * Checks if a lock is held by the current instance.
     *
     * @param lockName the name of the lock
     * @return true if the lock is held by this instance, false otherwise
     */
    public boolean isLockHeld(String lockName) {
        DistributedLock lock = getLock(lockName);
        return lock != null && lock.isHeldByCurrentInstance();
    }

}
