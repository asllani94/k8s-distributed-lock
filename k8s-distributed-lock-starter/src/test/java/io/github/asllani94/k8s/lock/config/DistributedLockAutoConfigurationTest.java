package io.github.asllani94.k8s.lock.config;

;
import io.github.asllani94.k8s.lock.autoconfigure.DistributedLockAutoConfiguration;
import io.github.asllani94.k8s.lock.core.DistributedLockManager;
import io.kubernetes.client.openapi.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedLockAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DistributedLockAutoConfiguration.class));

    @Test
    void autoConfigurationShouldProvideDistributedLockManager() {
        this.contextRunner
                .withPropertyValues("k8s.distributed-lock.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(DistributedLockManager.class);
                    assertThat(context).hasSingleBean(KubernetesLockProperties.class);
                });
    }

    @Test
    void autoConfigurationShouldNotLoadWhenDisabled() {
        this.contextRunner
                .withPropertyValues("k8s.distributed-lock.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DistributedLockManager.class);
                });
    }

    @Test
    void autoConfigurationShouldRespectCustomApiClient() {
        this.contextRunner
                .withPropertyValues("k8s.distributed-lock.enabled=true")
                .withBean(ApiClient.class, () -> new ApiClient())
                .run(context -> {
                    assertThat(context).hasSingleBean(ApiClient.class);
                    assertThat(context).hasSingleBean(DistributedLockManager.class);
                });
    }
}