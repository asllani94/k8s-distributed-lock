package io.github.asllani94.k8s.lock.core;

import java.util.concurrent.TimeUnit;

/**
 * Interface for distributed locking mechanism.
 * Provides methods to acquire and release locks across multiple instances.
 */
public interface DistributedLock {

    /**
     * Attempts to acquire the lock immediately.
     * Returns true if the lock was acquired, false otherwise.
     *
     * @return true if lock was acquired, false otherwise
     */
    boolean tryLock();

    /**
     * Attempts to acquire the lock within the given waiting time.
     *
     * @param timeout the maximum time to wait for the lock
     * @param unit the time unit of the timeout argument
     * @return true if the lock was acquired, false if the waiting time elapsed before the lock was acquired
     * @throws InterruptedException if the current thread is interrupted while acquiring the lock
     */
    boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Releases the lock.
     * Should only be called by the holder of the lock.
     */
    void unlock();

    /**
     * Returns true if the lock is currently held by this instance.
     *
     * @return true if this instance holds the lock
     */
    boolean isHeldByCurrentInstance();

    /**
     * Returns the name of this lock.
     *
     * @return the lock name
     */
    String getLockName();

    /**
     * Renews the lock lease to extend the lock duration.
     * Should be called periodically by the lock holder to maintain the lock.
     *
     * @return true if the lease was renewed successfully
     */
    boolean renewLease();

}
