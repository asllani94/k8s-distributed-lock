package io.github.asllani94.k8s.lock.core;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoordinationV1Api;
import io.kubernetes.client.openapi.models.V1Lease;
import io.kubernetes.client.openapi.models.V1LeaseSpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Distributed lock implementation using Kubernetes Lease API.
 * This provides a distributed locking mechanism across multiple pods using Kubernetes leases.
 */
public class KubernetesLeaseLock implements DistributedLock {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesLeaseLock.class);

    private final CoordinationV1Api coordinationApi;
    private final String lockName;
    private final String namespace;
    private final String identity;
    private final Duration leaseDuration;
    private volatile boolean isHeld = false;

    public KubernetesLeaseLock(ApiClient apiClient, String lockName, String namespace, Duration leaseDuration) {
        this(apiClient, lockName, namespace, leaseDuration, null);
    }

    /**
     * Protected constructor that allows specifying a custom identity.
     * This is primarily useful for testing scenarios where you want to simulate
     * multiple pods/instances with different identities.
     *
     * @param apiClient the Kubernetes API client
     * @param lockName the name of the lock
     * @param namespace the Kubernetes namespace
     * @param leaseDuration the duration of the lease
     * @param customIdentity the custom identity to use (if null, generates identity automatically)
     */
    protected KubernetesLeaseLock(ApiClient apiClient, String lockName, String namespace,
                                   Duration leaseDuration, String customIdentity) {
        this.coordinationApi = getCoordinationApi(apiClient);
        this.lockName = lockName;
        this.namespace = namespace;
        this.leaseDuration = leaseDuration;
        this.identity = (customIdentity != null && !customIdentity.isEmpty())
                ? customIdentity
                : getIdentity();
    }

    /**
     * Creates the CoordinationV1Api instance.
     * This method can be overridden in tests to provide a mock.
     */
    protected CoordinationV1Api getCoordinationApi(ApiClient apiClient) {
        return new CoordinationV1Api(apiClient);
    }

    /**
     * Returns the CoordinationV1Api instance.
     * This method can be overridden in tests to provide a mock.
     */
    protected CoordinationV1Api getCoordinationApi() {
        return coordinationApi;
    }

    @Override
    public boolean tryLock() {
        try {
            V1Lease existingLease = getLease();

            if (existingLease == null) {
                // Lease doesn't exist, create it
                return createLease();
            } else {
                // Lease exists, try to acquire it
                return acquireLease(existingLease);
            }
        } catch (ApiException e) {
            logger.error("Failed to acquire lock {}: {}", lockName, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        long retryInterval = 100; // 100ms retry interval

        while (System.currentTimeMillis() < deadline) {
            if (tryLock()) {
                return true;
            }
            Thread.sleep(retryInterval);
        }

        return false;
    }

    @Override
    public void unlock() {
        if (!isHeld) {
            logger.warn("Attempted to unlock {} but lock is not held by this instance", lockName);
            return;
        }

        try {
            V1Lease lease = getLease();
            if (lease != null && isOwnedByCurrentInstance(lease)) {
                getCoordinationApi().deleteNamespacedLease(lockName, namespace).execute();
                isHeld = false;
                logger.info("Successfully released lock: {}", lockName);
            }
        } catch (ApiException e) {
            logger.error("Failed to release lock {}: {}", lockName, e.getMessage());
        }
    }

    @Override
    public boolean isHeldByCurrentInstance() {
        return isHeld;
    }

    @Override
    public String getLockName() {
        return lockName;
    }

    @Override
    public boolean renewLease() {
        if (!isHeld) {
            return false;
        }

        try {
            V1Lease lease = getLease();
            if (lease != null && isOwnedByCurrentInstance(lease)) {
                Objects.requireNonNull(lease.getSpec()).setRenewTime(OffsetDateTime.now());
                getCoordinationApi().replaceNamespacedLease(lockName, namespace, lease).execute();
                logger.debug("Successfully renewed lease for lock: {}", lockName);
                return true;
            }
        } catch (ApiException e) {
            logger.error("Failed to renew lease for lock {}: {}", lockName, e.getMessage());
        }

        return false;
    }

    private V1Lease getLease() throws ApiException {
        try {
            return getCoordinationApi().readNamespacedLease(lockName, namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return null; // Lease doesn't exist
            }
            throw e;
        }
    }

    private boolean createLease() throws ApiException {
        V1Lease lease = new V1Lease()
                .metadata(new V1ObjectMeta()
                        .name(lockName)
                        .namespace(namespace))
                .spec(new V1LeaseSpec()
                        .holderIdentity(identity)
                        .leaseDurationSeconds((int) leaseDuration.getSeconds())
                        .acquireTime(OffsetDateTime.now())
                        .renewTime(OffsetDateTime.now()));

        try {
            getCoordinationApi().createNamespacedLease(namespace, lease).execute();
            isHeld = true;
            logger.info("Successfully created and acquired lock: {}", lockName);
            return true;
        } catch (ApiException e) {
            if (e.getCode() == 409) {
                // Lease was created by another instance in the meantime
                logger.debug("Lock {} already exists, created by another instance", lockName);
                return false;
            }
            throw e;
        }
    }

    private boolean acquireLease(V1Lease lease) throws ApiException {
        if (isOwnedByCurrentInstance(lease)) {
            // We already own this lease
            isHeld = true;
            return true;
        }

        // Check if the lease has expired
        if (isLeaseExpired(lease)) {
            // Try to take over the expired lease
            V1LeaseSpec spec = lease.getSpec();
            assert spec != null;
            spec.setHolderIdentity(identity);
            spec.setAcquireTime(OffsetDateTime.now());
            spec.setRenewTime(OffsetDateTime.now());

            try {
                getCoordinationApi().replaceNamespacedLease(lockName, namespace, lease).execute();
                isHeld = true;
                logger.info("Successfully acquired expired lock: {}", lockName);
                return true;
            } catch (ApiException e) {
                if (e.getCode() == 409) {
                    // Conflict - another instance updated the lease
                    logger.debug("Failed to acquire lock {}, another instance updated it", lockName);
                    return false;
                }
                throw e;
            }
        }

        return false;
    }

    private boolean isOwnedByCurrentInstance(V1Lease lease) {
        String holder = Objects.requireNonNull(lease.getSpec()).getHolderIdentity();
        return identity.equals(holder);
    }

    private boolean isLeaseExpired(V1Lease lease) {
        V1LeaseSpec spec = lease.getSpec();
        assert spec != null;
        if (spec.getRenewTime() == null) {
            return true;
        }

        OffsetDateTime renewTime = spec.getRenewTime();
        Integer leaseDurationSeconds = spec.getLeaseDurationSeconds();

        if (leaseDurationSeconds == null) {
            leaseDurationSeconds = (int) this.leaseDuration.getSeconds();
        }

        OffsetDateTime expirationTime = renewTime.plusSeconds(leaseDurationSeconds);
        return OffsetDateTime.now().isAfter(expirationTime);
    }

    private String getIdentity() {
        String podName = System.getenv("HOSTNAME");
        if (podName != null && !podName.isEmpty()) {
            return podName;
        }

        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-" + System.currentTimeMillis();
        }
    }

}
