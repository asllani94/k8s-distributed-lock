package io.github.asllani94.k8s.lock.core;

import io.github.asllani94.k8s.lock.config.KubernetesLockProperties;
import io.kubernetes.client.openapi.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistributedLockManagerTest {

    @Mock
    private ApiClient apiClient;

    private KubernetesLockProperties properties;
    private DistributedLockManager lockManager;

    @BeforeEach
    void setUp() {
        properties = new KubernetesLockProperties();
        properties.setNamespace("test-namespace");

        KubernetesLockProperties.Lock lockConfig = new KubernetesLockProperties.Lock();
        lockConfig.setLeaseDurationSeconds(30);
        lockConfig.setRenewIntervalSeconds(10);
        properties.setLock(lockConfig);

        lockManager = new DistributedLockManager(apiClient, properties);
    }

    @Test
    void testGetLock_ShouldReturnSameLockInstanceForSameName() {
        // Act
        DistributedLock lock1 = lockManager.getLock("test-lock");
        DistributedLock lock2 = lockManager.getLock("test-lock");

        // Assert
        assertNotNull(lock1);
        assertNotNull(lock2);
        assertSame(lock1, lock2, "Should return the same lock instance for the same name");
    }

    @Test
    void testGetLock_ShouldReturnDifferentLockInstancesForDifferentNames() {
        // Act
        DistributedLock lock1 = lockManager.getLock("lock-1");
        DistributedLock lock2 = lockManager.getLock("lock-2");

        // Assert
        assertNotNull(lock1);
        assertNotNull(lock2);
        assertNotSame(lock1, lock2, "Should return different lock instances for different names");
    }

    @Test
    void testGetLock_WithCustomDuration() {
        // Act
        DistributedLock lock = lockManager.getLock("test-lock", Duration.ofSeconds(60));

        // Assert
        assertNotNull(lock);
        assertEquals("test-lock", lock.getLockName());
    }

    @Test
    void testExecuteWithLock_WhenLockAcquired_ShouldExecuteAction() {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);
        when(mockLock.tryLock()).thenReturn(true);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        AtomicBoolean actionExecuted = new AtomicBoolean(false);

        // Act
        boolean result = spyManager.executeWithLock("test-lock", () -> {
            actionExecuted.set(true);
        });

        // Assert
        assertTrue(result);
        assertTrue(actionExecuted.get());
        verify(mockLock).tryLock();
        verify(mockLock).unlock();
    }

    @Test
    void testExecuteWithLock_WhenLockNotAcquired_ShouldNotExecuteAction() {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);
        when(mockLock.tryLock()).thenReturn(false);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        AtomicBoolean actionExecuted = new AtomicBoolean(false);

        // Act
        boolean result = spyManager.executeWithLock("test-lock", () -> {
            actionExecuted.set(true);
        });

        // Assert
        assertFalse(result);
        assertFalse(actionExecuted.get());
        verify(mockLock).tryLock();
        verify(mockLock, never()).unlock();
    }

    @Test
    void testExecuteWithLock_ShouldUnlockEvenIfActionThrowsException() {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);
        when(mockLock.tryLock()).thenReturn(true);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        // Act & Assert
        assertThrows(RuntimeException.class, () -> {
            spyManager.executeWithLock("test-lock", () -> {
                throw new RuntimeException("Test exception");
            });
        });

        verify(mockLock).unlock();
    }

    @Test
    void testExecuteWithLockAndTimeout_WhenLockAcquired_ShouldExecuteAction() throws InterruptedException {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);
        when(mockLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        AtomicBoolean actionExecuted = new AtomicBoolean(false);

        // Act
        boolean result = spyManager.executeWithLock("test-lock", 5, TimeUnit.SECONDS, () -> {
            actionExecuted.set(true);
        });

        // Assert
        assertTrue(result);
        assertTrue(actionExecuted.get());
        verify(mockLock).tryLock(5, TimeUnit.SECONDS);
        verify(mockLock).unlock();
    }

    @Test
    void testExecuteWithLockAndTimeout_WhenLockNotAcquired_ShouldNotExecuteAction() throws InterruptedException {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);
        when(mockLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        AtomicBoolean actionExecuted = new AtomicBoolean(false);

        // Act
        boolean result = spyManager.executeWithLock("test-lock", 5, TimeUnit.SECONDS, () -> {
            actionExecuted.set(true);
        });

        // Assert
        assertFalse(result);
        assertFalse(actionExecuted.get());
        verify(mockLock).tryLock(5, TimeUnit.SECONDS);
        verify(mockLock, never()).unlock();
    }

    @Test
    void testExecuteWithLockAndSupplier_WhenLockAcquired_ShouldReturnResult() {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);
        when(mockLock.tryLock()).thenReturn(true);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        String expectedResult = "computed-result";

        // Act
        String result = spyManager.executeWithLock("test-lock", () -> expectedResult);

        // Assert
        assertEquals(expectedResult, result);
        verify(mockLock).tryLock();
        verify(mockLock).unlock();
    }

    @Test
    void testExecuteWithLockAndSupplier_WhenLockNotAcquired_ShouldReturnNull() {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);
        when(mockLock.tryLock()).thenReturn(false);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        // Act
        String result = spyManager.executeWithLock("test-lock", () -> "result");

        // Assert
        assertNull(result);
        verify(mockLock).tryLock();
        verify(mockLock, never()).unlock();
    }

    @Test
    void testAcquireLockWithAutoRenewal_WhenLockAcquired_ShouldReturnTrue() throws InterruptedException {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class, withSettings().lenient());
        when(mockLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        // Act
        boolean acquired = spyManager.acquireLockWithAutoRenewal("test-lock", 5, TimeUnit.SECONDS);

        // Assert
        assertTrue(acquired);
        verify(mockLock).tryLock(5, TimeUnit.SECONDS);
    }

    @Test
    void testAcquireLockWithAutoRenewal_WhenLockNotAcquired_ShouldReturnFalse() throws InterruptedException {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);
        when(mockLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        // Act
        boolean acquired = spyManager.acquireLockWithAutoRenewal("test-lock", 5, TimeUnit.SECONDS);

        // Assert
        assertFalse(acquired);
        verify(mockLock).tryLock(5, TimeUnit.SECONDS);
    }

    @Test
    void testReleaseLock_WhenLockExists_ShouldCallUnlock() {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        // Act
        spyManager.releaseLock("test-lock");

        // Assert
        verify(mockLock).unlock();
    }

    @Test
    void testReleaseLock_WhenLockDoesNotExist_ShouldNotThrow() {
        // Act & Assert - should not throw
        assertDoesNotThrow(() -> lockManager.releaseLock("non-existent-lock"));
    }

    @Test
    void testIsLockHeld_WhenLockHeld_ShouldReturnTrue() {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);
        when(mockLock.isHeldByCurrentInstance()).thenReturn(true);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        // Act
        boolean isHeld = spyManager.isLockHeld("test-lock");

        // Assert
        assertTrue(isHeld);
        verify(mockLock).isHeldByCurrentInstance();
    }

    @Test
    void testIsLockHeld_WhenLockNotHeld_ShouldReturnFalse() {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);
        when(mockLock.isHeldByCurrentInstance()).thenReturn(false);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        // Act
        boolean isHeld = spyManager.isLockHeld("test-lock");

        // Assert
        assertFalse(isHeld);
        verify(mockLock).isHeldByCurrentInstance();
    }

    @Test
    void testIsLockHeld_WhenLockDoesNotExist_ShouldReturnFalse() {
        // Act
        boolean isHeld = lockManager.isLockHeld("non-existent-lock");

        // Assert
        assertFalse(isHeld);
    }

    @Test
    void testConcurrentLockAcquisition_ShouldHandleMultipleThreads() throws InterruptedException {
        // Arrange
        DistributedLock mockLock = mock(DistributedLock.class);
        when(mockLock.tryLock()).thenReturn(true);

        DistributedLockManager spyManager = spy(lockManager);
        doReturn(mockLock).when(spyManager).getLock("test-lock");

        AtomicInteger executionCount = new AtomicInteger(0);
        int threadCount = 10;

        // Act
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                spyManager.executeWithLock("test-lock", () -> {
                    executionCount.incrementAndGet();
                });
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // Assert
        assertEquals(threadCount, executionCount.get());
        verify(mockLock, times(threadCount)).tryLock();
        verify(mockLock, times(threadCount)).unlock();
    }
}
