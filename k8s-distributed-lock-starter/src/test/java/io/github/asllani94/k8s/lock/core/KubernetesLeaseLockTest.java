package io.github.asllani94.k8s.lock.core;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoordinationV1Api;
import io.kubernetes.client.openapi.models.V1Lease;
import io.kubernetes.client.openapi.models.V1LeaseSpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KubernetesLeaseLockTest {

    @Mock
    private ApiClient apiClient;

    @Mock
    private CoordinationV1Api.APIreadNamespacedLeaseRequest readRequest;

    @Mock
    private CoordinationV1Api.APIcreateNamespacedLeaseRequest createRequest;

    @Mock
    private CoordinationV1Api.APIreplaceNamespacedLeaseRequest replaceRequest;

    @Mock
    private CoordinationV1Api.APIdeleteNamespacedLeaseRequest deleteRequest;

    private TestableKubernetesLeaseLock lock;

    private static final String LOCK_NAME = "test-lock";
    private static final String NAMESPACE = "default";
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    @BeforeEach
    void setUp() {
        lock = new TestableKubernetesLeaseLock(apiClient, LOCK_NAME, NAMESPACE, LEASE_DURATION);
    }

    @Test
    void testTryLock_WhenLeaseDoesNotExist_ShouldCreateAndAcquireLease() throws ApiException {
        // Arrange
        when(lock.getMockCoordinationApi().readNamespacedLease(LOCK_NAME, NAMESPACE))
                .thenReturn(readRequest);
        when(readRequest.execute()).thenThrow(new ApiException(404, "Not found"));

        when(lock.getMockCoordinationApi().createNamespacedLease(eq(NAMESPACE), any(V1Lease.class)))
                .thenReturn(createRequest);
        when(createRequest.execute()).thenReturn(createLease("instance-1"));

        // Act
        boolean acquired = lock.tryLock();

        // Assert
        assertTrue(acquired);
        assertTrue(lock.isHeldByCurrentInstance());
        verify(lock.getMockCoordinationApi()).createNamespacedLease(eq(NAMESPACE), any(V1Lease.class));
    }

    @Test
    void testTryLock_WhenLeaseExistsAndExpired_ShouldAcquireLease() throws ApiException {
        // Arrange
        V1Lease expiredLease = createExpiredLease("other-instance");
        when(lock.getMockCoordinationApi().readNamespacedLease(LOCK_NAME, NAMESPACE))
                .thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(expiredLease);

        when(lock.getMockCoordinationApi().replaceNamespacedLease(eq(LOCK_NAME), eq(NAMESPACE), any(V1Lease.class)))
                .thenReturn(replaceRequest);
        when(replaceRequest.execute()).thenReturn(createLease("instance-1"));

        // Act
        boolean acquired = lock.tryLock();

        // Assert
        assertTrue(acquired);
        assertTrue(lock.isHeldByCurrentInstance());
        verify(lock.getMockCoordinationApi()).replaceNamespacedLease(eq(LOCK_NAME), eq(NAMESPACE), any(V1Lease.class));
    }

    @Test
    void testTryLock_WhenLeaseExistsAndNotExpired_ShouldNotAcquireLease() throws ApiException {
        // Arrange
        V1Lease activeLease = createActiveLease("other-instance");
        when(lock.getMockCoordinationApi().readNamespacedLease(LOCK_NAME, NAMESPACE))
                .thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(activeLease);

        // Act
        boolean acquired = lock.tryLock();

        // Assert
        assertFalse(acquired);
        assertFalse(lock.isHeldByCurrentInstance());
        verify(lock.getMockCoordinationApi(), never()).createNamespacedLease(any(), any());
        verify(lock.getMockCoordinationApi(), never()).replaceNamespacedLease(any(), any(), any());
    }

    @Test
    void testTryLockWithTimeout_ShouldRetryUntilTimeout() throws ApiException, InterruptedException {
        // Arrange
        V1Lease activeLease = createActiveLease("other-instance");
        when(lock.getMockCoordinationApi().readNamespacedLease(LOCK_NAME, NAMESPACE))
                .thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(activeLease);

        // Act
        long startTime = System.currentTimeMillis();
        boolean acquired = lock.tryLock(500, TimeUnit.MILLISECONDS);
        long duration = System.currentTimeMillis() - startTime;

        // Assert
        assertFalse(acquired);
        assertTrue(duration >= 400, "Should wait at least 400ms"); // Allow some variance
        assertTrue(duration < 1000, "Should not wait much longer than timeout");
    }

    @Test
    void testGetLockName() {
        // Act & Assert
        assertEquals(LOCK_NAME, lock.getLockName());
    }

    @Test
    void testTryLock_WhenConflictOccursDuringCreate_ShouldReturnFalse() throws ApiException {
        // Arrange
        when(lock.getMockCoordinationApi().readNamespacedLease(LOCK_NAME, NAMESPACE))
                .thenReturn(readRequest);
        when(readRequest.execute()).thenThrow(new ApiException(404, "Not found"));

        when(lock.getMockCoordinationApi().createNamespacedLease(eq(NAMESPACE), any(V1Lease.class)))
                .thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new ApiException(409, "Conflict"));

        // Act
        boolean acquired = lock.tryLock();

        // Assert
        assertFalse(acquired);
        assertFalse(lock.isHeldByCurrentInstance());
    }

    @Test
    void testTryLock_WhenApiExceptionOccurs_ShouldReturnFalse() throws ApiException {
        // Arrange
        when(lock.getMockCoordinationApi().readNamespacedLease(LOCK_NAME, NAMESPACE))
                .thenReturn(readRequest);
        when(readRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        // Act
        boolean acquired = lock.tryLock();

        // Assert
        assertFalse(acquired);
        assertFalse(lock.isHeldByCurrentInstance());
    }

    // Helper methods

    private V1Lease createLease(String holderIdentity) {
        return new V1Lease()
                .metadata(new V1ObjectMeta()
                        .name(LOCK_NAME)
                        .namespace(NAMESPACE))
                .spec(new V1LeaseSpec()
                        .holderIdentity(holderIdentity)
                        .leaseDurationSeconds((int) LEASE_DURATION.getSeconds())
                        .acquireTime(OffsetDateTime.now())
                        .renewTime(OffsetDateTime.now()));
    }

    private V1Lease createActiveLease(String holderIdentity) {
        return new V1Lease()
                .metadata(new V1ObjectMeta()
                        .name(LOCK_NAME)
                        .namespace(NAMESPACE))
                .spec(new V1LeaseSpec()
                        .holderIdentity(holderIdentity)
                        .leaseDurationSeconds((int) LEASE_DURATION.getSeconds())
                        .acquireTime(OffsetDateTime.now())
                        .renewTime(OffsetDateTime.now())); // Just renewed, so it's active
    }

    private V1Lease createExpiredLease(String holderIdentity) {
        return new V1Lease()
                .metadata(new V1ObjectMeta()
                        .name(LOCK_NAME)
                        .namespace(NAMESPACE))
                .spec(new V1LeaseSpec()
                        .holderIdentity(holderIdentity)
                        .leaseDurationSeconds((int) LEASE_DURATION.getSeconds())
                        .acquireTime(OffsetDateTime.now().minusMinutes(5))
                        .renewTime(OffsetDateTime.now().minusMinutes(5))); // Expired 5 minutes ago
    }

    // Testable subclass that exposes the mock
    class TestableKubernetesLeaseLock extends KubernetesLeaseLock {
        private final CoordinationV1Api mockApi = mock(CoordinationV1Api.class);

        public TestableKubernetesLeaseLock(ApiClient apiClient, String lockName, String namespace, Duration leaseDuration) {
            super(apiClient, lockName, namespace, leaseDuration);
        }

        @Override
        protected CoordinationV1Api getCoordinationApi(ApiClient apiClient) {
            return mockApi;
        }

        @Override
        protected CoordinationV1Api getCoordinationApi() {
            return mockApi;
        }

        public CoordinationV1Api getMockCoordinationApi() {
            return mockApi;
        }
    }
}
