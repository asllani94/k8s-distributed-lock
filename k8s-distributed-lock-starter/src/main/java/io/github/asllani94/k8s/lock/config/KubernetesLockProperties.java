package io.github.asllani94.k8s.lock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "k8s.distributed-lock")
public class KubernetesLockProperties {

    private boolean enabled = true;
    private String namespace = "default";
    private Lock lock = new Lock();

    public static class Lock {
        private int leaseDurationSeconds = 30;
        private int renewIntervalSeconds = 10;

        // getters and setters
        public int getLeaseDurationSeconds() {
            return leaseDurationSeconds;
        }

        public void setLeaseDurationSeconds(int leaseDurationSeconds) {
            this.leaseDurationSeconds = leaseDurationSeconds;
        }

        public int getRenewIntervalSeconds() {
            return renewIntervalSeconds;
        }

        public void setRenewIntervalSeconds(int renewIntervalSeconds) {
            this.renewIntervalSeconds = renewIntervalSeconds;
        }
    }

    // getters and setters
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Lock getLock() {
        return lock;
    }

    public void setLock(Lock lock) {
        this.lock = lock;
    }
}