package io.github.asllani94.k8s.lock.core;

import io.github.asllani94.k8s.lock.config.KubernetesLockProperties;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoordinationV1Api;
import io.kubernetes.client.openapi.models.V1Lease;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for DistributedLockManager that run against a real Kubernetes cluster.
 * Requires:
 * - A running Kubernetes cluster (local or remote)
 * - Proper RBAC permissions for lease management
 * - kubectl configured with valid context
 */
@SpringBootTest
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DistributedLockManagerIT {

    @Autowired
    private DistributedLockManager lockManager;

    @Autowired
    private ApiClient apiClient;

    @Autowired
    private KubernetesLockProperties properties;

    private static final String TEST_NAMESPACE = "default";
    private String testLockName;

    @BeforeEach
    void setUp() {
        // Generate unique lock name for each test to avoid conflicts
        testLockName = "test-lock-" + System.currentTimeMillis();
    }

    @AfterEach
    void tearDown() {
        // Cleanup: Release any locks that might still be held
        try {
            lockManager.releaseLock(testLockName);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    @Test
    @Order(1)
    @DisplayName("Should acquire and release distributed lock in Kubernetes")
    void testAcquireAndReleaseLock() throws Exception {
        // Acquire lock
        DistributedLock lock = lockManager.getLock(testLockName);
        boolean acquired = lock.tryLock(5, TimeUnit.SECONDS);
        assertTrue(acquired, "Should successfully acquire lock");

        // Verify lease exists in Kubernetes
        CoordinationV1Api api = new CoordinationV1Api(apiClient);
        V1Lease lease = api.readNamespacedLease(testLockName, TEST_NAMESPACE).execute();
        assertNotNull(lease, "Lease should exist in Kubernetes");
        assertNotNull(lease.getSpec(), "Lease spec should not be null");
        assertNotNull(lease.getSpec().getHolderIdentity(), "Lease should have holder identity");

        // Release lock
        lockManager.releaseLock(testLockName);

        // Verify lease is deleted
        assertThrows(Exception.class, () -> {
            api.readNamespacedLease(testLockName, TEST_NAMESPACE).execute();
        }, "Lease should be deleted after release");
    }

    @Test
    @Order(2)
    @DisplayName("Should prevent concurrent lock acquisition")
    void testConcurrentLockAcquisition() throws Exception {
        // First lock acquisition should succeed (simulating Pod 1)
        DistributedLock lock1 = lockManager.getLock(testLockName);
        boolean firstAcquired = lock1.tryLock(5, TimeUnit.SECONDS);
        assertTrue(firstAcquired, "First lock acquisition should succeed");

        // Second lock acquisition should fail (simulating Pod 2 trying to acquire same lock)
        // Create a separate lock instance with a DIFFERENT identity to simulate a different pod
        DistributedLock lock2 = new TestKubernetesLeaseLock(
            apiClient,
            testLockName,
            TEST_NAMESPACE,
            Duration.ofSeconds(properties.getLock().getLeaseDurationSeconds()),
            "pod-2-identity-" + System.currentTimeMillis() // Different identity from pod 1
        );
        boolean secondAcquired = lock2.tryLock(1, TimeUnit.SECONDS);
        assertFalse(secondAcquired, "Second lock acquisition should fail while first lock is held");

        // Release first lock
        lockManager.releaseLock(testLockName);

        // Now third acquisition should succeed (Pod 2 can now get the lock)
        boolean thirdAcquired = lock2.tryLock(5, TimeUnit.SECONDS);
        assertTrue(thirdAcquired, "Lock acquisition should succeed after previous lock is released");

        // Cleanup
        lock2.unlock();
    }

    /**
     * Test helper class to create a KubernetesLeaseLock with a custom identity.
     * This simulates a different pod/instance trying to acquire the same lock.
     */
    private static class TestKubernetesLeaseLock extends KubernetesLeaseLock {
        public TestKubernetesLeaseLock(ApiClient apiClient, String lockName, String namespace,
                                       Duration leaseDuration, String customIdentity) {
            super(apiClient, lockName, namespace, leaseDuration, customIdentity);
        }
    }

    @Test
    @Order(3)
    @DisplayName("Should execute code with automatic lock management")
    void testExecuteWithLock() throws Exception {
        final boolean[] executed = {false};

        // Execute with lock - should succeed
        lockManager.executeWithLock(testLockName, () -> {
            executed[0] = true;
            // Simulate some work
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(executed[0], "Code should be executed with lock");

        // Verify lock is automatically released after execution
        DistributedLock lock = lockManager.getLock(testLockName);
        boolean canAcquire = lock.tryLock(2, TimeUnit.SECONDS);
        assertTrue(canAcquire, "Lock should be automatically released after executeWithLock completes");

        // Cleanup
        lockManager.releaseLock(testLockName);
    }

    @Test
    @Order(4)
    @DisplayName("Should handle lock with return value")
    void testExecuteWithLockAndReturnValue() {
        String result = lockManager.executeWithLock(testLockName, () -> {
            // Simulate some computation
            return "test-result-" + System.currentTimeMillis();
        });

        assertNotNull(result, "Should return result from locked execution");
        assertTrue(result.startsWith("test-result-"), "Should return expected result format");
    }

    @Test
    @Order(5)
    @DisplayName("Should acquire lock with auto-renewal")
    void testAcquireLockWithAutoRenewal() throws Exception {
        // Acquire lock with auto-renewal
        boolean acquired = lockManager.acquireLockWithAutoRenewal(testLockName, 5, TimeUnit.SECONDS);
        assertTrue(acquired, "Should acquire lock with auto-renewal");

        // Wait longer than lease duration to ensure renewal happens
        Thread.sleep(20000); // Wait 20 seconds (longer than lease duration)

        // Verify lock is still held (lease was renewed)
        CoordinationV1Api api = new CoordinationV1Api(apiClient);
        V1Lease lease = api.readNamespacedLease(testLockName, TEST_NAMESPACE).execute();
        assertNotNull(lease, "Lease should still exist after auto-renewal period");

        // Cleanup
        lockManager.releaseLock(testLockName);
    }

    @Test
    @Order(6)
    @DisplayName("Should get lock instance and perform manual operations")
    void testGetLockAndManualOperations() {
        DistributedLock lock = lockManager.getLock(testLockName);
        assertNotNull(lock, "Should get lock instance");

        // Try to acquire lock
        boolean acquired = lock.tryLock();
        assertTrue(acquired, "Should acquire lock");

        // Verify lock is held
        assertTrue(lock.isHeldByCurrentInstance(), "Lock should be held by current instance");

        // Unlock
        lock.unlock();

        // Verify lock is released
        assertFalse(lock.isHeldByCurrentInstance(), "Lock should be released after unlock");
    }
}
