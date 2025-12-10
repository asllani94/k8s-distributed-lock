# Kubernetes Distributed Lock - Spring Boot Starter
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-brightgreen.svg)](https://spring.io/projects/spring-boot)

A Spring Boot 3.5.x starter for distributed locking using Kubernetes Lease API. Enables coordination between multiple pod instances in a Kubernetes cluster without external dependencies like Redis or Zookeeper.

## Features

- **Auto-configured Kubernetes Client**: Automatically configures connection using in-cluster service account or local kubeconfig
- **Distributed Locking**: Thread-safe distributed lock implementation using Kubernetes Lease API
- **Leader Election Support**: Built-in support for leader election patterns
- **Spring Boot Integration**: Seamless integration with Spring Boot ecosystem
- **Zero External Dependencies**: Uses native Kubernetes resources (no Redis, Zookeeper, etc.)
- **Fault Tolerant**: Automatic lock expiration if holder crashes
- **Local Development**: Works with local kubeconfig for development

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.github.asllani94</groupId>
    <artifactId>k8s-distributed-lock-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### Configuration

**application.yml:**
```yaml
k8s:
  distributed-lock:
    enabled: true
    namespace: default
    lock:
      lease-duration-seconds: 30
      renew-interval-seconds: 10
```

**application.properties:**
```properties
k8s.distributed-lock.enabled=true
k8s.distributed-lock.namespace=default
k8s.distributed-lock.lock.lease-duration-seconds=30
k8s.distributed-lock.lock.renew-interval-seconds=10
```

## Usage Examples

### 1. Simple Lock with Automatic Release

The most common pattern - lock is automatically released after execution:

```java
@Service
public class OrderProcessingService {

    @Autowired
    private DistributedLockManager lockManager;

    public void processOrder(String orderId) {
        // Execute with automatic lock/unlock
        lockManager.executeWithLock("order-" + orderId, () -> {
            // Only one pod executes this at a time
            updateOrderInDatabase(orderId);
            notifyCustomer(orderId);
        });
        // Lock is automatically released here
    }
}
```

### 2. Lock with Timeout

Wait for lock availability with a timeout:

```java
@Service
public class ReportGenerator {

    @Autowired
    private DistributedLockManager lockManager;

    public boolean generateReport(String reportId) throws InterruptedException {
        return lockManager.executeWithLock(
            "report-" + reportId,
            30, TimeUnit.SECONDS,  // Wait up to 30 seconds
            () -> {
                generateAndSaveReport(reportId);
            }
        );
    }
}
```

### 3. Lock with Return Value

Execute critical section and return a result:

```java
@Service
public class InventoryService {

    @Autowired
    private DistributedLockManager lockManager;

    public Integer reserveInventory(String productId, int quantity) {
        return lockManager.executeWithLock("inventory-" + productId, () -> {
            int available = getAvailableInventory(productId);
            if (available >= quantity) {
                decrementInventory(productId, quantity);
                return quantity;
            }
            return 0;
        });
    }
}
```

### 4. Manual Lock Management with Auto-Renewal

For long-running tasks that need automatic lease renewal:

```java
@Service
public class BatchProcessor {

    @Autowired
    private DistributedLockManager lockManager;

    public void processBatch() throws InterruptedException {
        // Acquire lock with automatic lease renewal
        if (lockManager.acquireLockWithAutoRenewal("batch-processing", 5, TimeUnit.SECONDS)) {
            try {
                // Long-running task - lease will be automatically renewed
                for (Item item : getLargeDataset()) {
                    processItem(item);
                    Thread.sleep(100);
                }
            } finally {
                // Always release when done
                lockManager.releaseLock("batch-processing");
            }
        } else {
            logger.info("Another instance is processing the batch");
        }
    }
}
```

### 5. Direct Lock Access

For advanced use cases requiring fine-grained control:

```java
@Service
public class CustomLockService {

    @Autowired
    private DistributedLockManager lockManager;

    public void customLockLogic() {
        DistributedLock lock = lockManager.getLock("my-custom-lock");

        if (lock.tryLock()) {
            try {
                doWork();

                // Manually renew lease if needed
                if (needMoreTime()) {
                    lock.renewLease();
                    doMoreWork();
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
```

### 6. Scheduled Tasks with Locking

Ensure only one instance executes scheduled tasks:

```java
@Component
public class ScheduledTaskService {

    @Autowired
    private DistributedLockManager lockManager;

    @Scheduled(fixedRate = 60000)
    public void performScheduledTask() {
        lockManager.executeWithLock("scheduled-task", () -> {
            logger.info("Executing scheduled task (only on one pod)");
            processScheduledWork();
        });
    }
}
```

### 7. Leader Election Pattern

Implement leader election for high-availability:

```java
@Component
public class LeaderElectionService {

    @Autowired
    private DistributedLockManager lockManager;

    private volatile boolean isLeader = false;

    @PostConstruct
    public void startLeaderElection() {
        new Thread(() -> {
            while (true) {
                try {
                    boolean acquired = lockManager.acquireLockWithAutoRenewal(
                        "leader-election", 10, TimeUnit.SECONDS
                    );

                    if (acquired && !isLeader) {
                        isLeader = true;
                        onBecomeLeader();
                    } else if (!acquired && isLeader) {
                        isLeader = false;
                        onLoseLeadership();
                    }

                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }

    private void onBecomeLeader() {
        logger.info("I am now the leader!");
        // Start leader-only tasks
    }

    private void onLoseLeadership() {
        logger.info("I am no longer the leader");
        // Stop leader-only tasks
    }

    public boolean isLeader() {
        return isLeader;
    }
}
```

## Kubernetes Setup

### Required RBAC Permissions

Create a ServiceAccount with permissions to manage Leases:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: distributed-lock-sa
  namespace: default
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: distributed-lock-role
  namespace: default
rules:
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get", "list", "create", "update", "delete", "patch", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: distributed-lock-binding
  namespace: default
subjects:
  - kind: ServiceAccount
    name: distributed-lock-sa
    namespace: default
roleRef:
  kind: Role
  name: distributed-lock-role
  apiGroup: rbac.authorization.k8s.io
```

### Deployment Example

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-spring-app
  namespace: default
spec:
  replicas: 3  # Multiple instances will coordinate via locks
  selector:
    matchLabels:
      app: my-spring-app
  template:
    metadata:
      labels:
        app: my-spring-app
    spec:
      serviceAccountName: distributed-lock-sa
      containers:
        - name: app
          image: my-spring-app:latest
          ports:
            - containerPort: 8080
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "production"
          resources:
            limits:
              memory: "512Mi"
              cpu: "500m"
            requests:
              memory: "256Mi"
              cpu: "250m"
```

## How It Works

### Distributed Locking Mechanism

The distributed lock uses Kubernetes Lease resources from the \`coordination.k8s.io\` API:

1. **Lock Acquisition**:
   - Attempts to create a Lease resource with the pod's identity as holder
   - If lease exists and is held by another pod, acquisition fails
   - If lease doesn't exist or has expired, acquisition succeeds

2. **Lock Holding**:
   - The lease is periodically renewed (based on \`renewIntervalSeconds\`)
   - Each renewal extends the lease duration (based on \`leaseDurationSeconds\`)
   - Holder identity is stored in the lease metadata

3. **Lock Expiration**:
   - If a pod crashes or stops renewing, the lease expires
   - Other pods can then acquire the expired lease
   - Automatic fault tolerance without manual cleanup

4. **Lock Release**:
   - Explicitly deletes the lease resource
   - Makes lock immediately available to other pods

### Benefits

- ✅ **No External Dependencies**: Uses native Kubernetes resources
- ✅ **Fault Tolerant**: Automatic recovery if lock holder crashes
- ✅ **Distributed**: Works across pods in a cluster
- ✅ **Fair**: First-come-first-served acquisition
- ✅ **Observable**: Inspect locks with \`kubectl get leases\`
- ✅ **Scalable**: Kubernetes API server handles coordination

### In-Cluster vs Local Development

**In Cluster (Production):**
- Uses service account token from \`/var/run/secrets/kubernetes.io/serviceaccount/token\`
- Automatically detects cluster environment
- No configuration needed

**Local Development:**
- Falls back to \`~/.kube/config\`
- Works with \`kubectl\` configured cluster
- Same API, different authentication

## Monitoring and Debugging

### View Active Locks

```bash
# List all leases in namespace
kubectl get leases -n default

# Describe specific lease
kubectl describe lease order-12345 -n default

# Watch lease changes in real-time
kubectl get leases -n default -w

# Get lease details as YAML
kubectl get lease order-12345 -n default -o yaml
```

### Example Lease Resource

```yaml
apiVersion: coordination.k8s.io/v1
kind: Lease
metadata:
  name: order-12345
  namespace: default
spec:
  holderIdentity: my-spring-app-7d9f8b5c4-x7k2m
  leaseDurationSeconds: 30
  acquireTime: "2025-12-10T22:15:30.123456Z"
  renewTime: "2025-12-10T22:15:40.234567Z"
  leaseTransitions: 1
```

### Logging

Enable debug logging to see lock operations:

```yaml
logging:
  level:
    io.github.asllani94.k8s.lock: DEBUG
    io.kubernetes.client: INFO
```

## Configuration Reference

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| \`k8s.distributed-lock.enabled\` | boolean | \`true\` | Enable/disable the starter |
| \`k8s.distributed-lock.namespace\` | string | \`default\` | Kubernetes namespace for locks |
| \`k8s.distributed-lock.lock.lease-duration-seconds\` | int | \`30\` | How long a lock is valid without renewal |
| \`k8s.distributed-lock.lock.renew-interval-seconds\` | int | \`10\` | How often to renew the lock |

**Recommendations:**
- \`leaseDurationSeconds\` should be 2-3x \`renewIntervalSeconds\`
- Shorter durations = faster recovery but more API calls
- Longer durations = fewer API calls but slower recovery

## API Reference

### DistributedLockManager

```java
// Get or create a lock
DistributedLock getLock(String lockName);
DistributedLock getLock(String lockName, Duration leaseDuration);

// Execute with automatic lock management
boolean executeWithLock(String lockName, Runnable action);
boolean executeWithLock(String lockName, long timeout, TimeUnit unit, Runnable action);
<T> T executeWithLock(String lockName, Supplier<T> supplier);

// Manual lock management
boolean acquireLockWithAutoRenewal(String lockName, long timeout, TimeUnit unit);
void releaseLock(String lockName);
boolean isLockHeld(String lockName);
```

### DistributedLock

```java
// Acquire lock
boolean tryLock();
boolean tryLock(long timeout, TimeUnit unit);

// Release lock
void unlock();

// Check status
boolean isHeldByCurrentInstance();

// Manual renewal
boolean renewLease();

// Get metadata
String getLockName();
```

## Best Practices

1. **Always use try-finally when manually managing locks:**
   ```java
   if (lock.tryLock()) {
       try {
           // critical section
       } finally {
           lock.unlock();
       }
   }
   ```

2. **Use meaningful lock names:**
   ```java
   // Good
   lockManager.getLock("order-process-" + orderId);

   // Bad
   lockManager.getLock("lock1");
   ```

3. **Keep critical sections short:**
   ```java
   // Good - lock only what needs synchronization
   prepareData();
   lockManager.executeWithLock("update", () -> updateDatabase());
   sendNotification();

   // Bad - holding lock unnecessarily
   lockManager.executeWithLock("all", () -> {
       prepareData();
       updateDatabase();
       sendNotification();
   });
   ```

4. **Handle lock acquisition failures gracefully:**
   ```java
   boolean acquired = lockManager.executeWithLock("task", () -> doWork());
   if (!acquired) {
       logger.info("Task already being processed by another instance");
       // Don't retry immediately, or use timeout
   }
   ```

5. **Use timeouts for non-critical operations:**
   ```java
   boolean acquired = lockManager.executeWithLock(
       "report", 5, TimeUnit.SECONDS, () -> generateReport()
   );
   if (!acquired) {
       // Schedule for later or notify user
   }
   ```

## Troubleshooting

### Lock Not Acquired

**Problem:** \`executeWithLock\` returns \`false\`

**Solutions:**
- Check if another pod holds the lock: \`kubectl get lease <lock-name> -o yaml\`
- Verify RBAC permissions are correct
- Check if lease has expired but not cleaned up
- Use timeout version to wait for lock availability

### Locks Not Released

**Problem:** Locks remain after pod termination

**Solutions:**
- Leases auto-expire after \`leaseDurationSeconds\`
- Manually delete: \`kubectl delete lease <lock-name>\`
- Ensure proper exception handling and \`finally\` blocks

### Permission Denied

**Problem:** \`Forbidden: User "system:serviceaccount..." cannot create resource "leases"\`

**Solutions:**
- Verify ServiceAccount is created
- Check Role has correct permissions
- Verify RoleBinding links SA to Role
- Check namespace matches deployment

## Performance Considerations

- Each lock operation makes 1-2 Kubernetes API calls
- Renewal happens in background thread
- API server handles thousands of leases easily
- Consider lock name cardinality (avoid too many unique locks)

## Comparison with Alternatives

| Feature | K8s Leases | Redis | Zookeeper | Database |
|---------|------------|-------|-----------|----------|
| External Dependency | ❌ | ✅ | ✅ | ✅ |
| Setup Complexity | Low | Medium | High | Low |
| Kubernetes Native | ✅ | ❌ | ❌ | ❌ |
| Auto Cleanup | ✅ | ⚠️ | ✅ | ❌ |
| Observability | \`kubectl\` | Redis CLI | ZK CLI | SQL |
| Performance | Good | Excellent | Good | Fair |

## Version Compatibility

- Spring Boot: 3.5.x
- Kubernetes Java Client: 25.0.0
- Java: 17+
- Kubernetes: 1.20+

## Contributing

Contributions are welcome! Please submit issues and pull requests on GitHub.

## License

[Apache License 2.0](LICENSE)

## Links

- [GitHub Repository](https://github.com/asllani94/k8s-distributed-lock)
- [Kubernetes Lease API Documentation](https://kubernetes.io/docs/concepts/architecture/leases/)
- [Kubernetes Java Client](https://github.com/kubernetes-client/java)

## Author

Arnold Asllani ([@asllani94](https://github.com/asllani94))
