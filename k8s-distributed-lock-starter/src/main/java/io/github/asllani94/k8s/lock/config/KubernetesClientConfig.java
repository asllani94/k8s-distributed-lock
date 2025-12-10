package io.github.asllani94.k8s.lock.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.credentials.AccessTokenAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@Configuration
public class KubernetesClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesClientConfig.class);
    private static final String SERVICE_ACCOUNT_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private static final String SERVICE_ACCOUNT_CA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";

    @Bean
    @ConditionalOnMissingBean
    public ApiClient kubernetesApiClient() throws IOException {
        ApiClient client;

        if (isRunningInCluster()) {
            logger.info("Detected in-cluster environment, using service account credentials");
            client = createInClusterClient();
        } else {
            logger.info("Using default kubeconfig for Kubernetes client");
            client = Config.defaultClient();
        }

        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);
        return client;
    }

    private boolean isRunningInCluster() {
        return Files.exists(Paths.get(SERVICE_ACCOUNT_TOKEN_PATH)) &&
               Files.exists(Paths.get(SERVICE_ACCOUNT_CA_PATH));
    }

    private ApiClient createInClusterClient() throws IOException {
        // Read service account token
        String token = new String(Files.readAllBytes(Paths.get(SERVICE_ACCOUNT_TOKEN_PATH)));

        // Get Kubernetes API server host and port from environment variables
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");

        if (host == null || port == null) {
            logger.warn("KUBERNETES_SERVICE_HOST or KUBERNETES_SERVICE_PORT not set, falling back to default client");
            return Config.defaultClient();
        }

        String basePath = "https://" + host + ":" + port;
        logger.info("Connecting to Kubernetes API at: {}", basePath);

        // Create client with service account token

        return new ClientBuilder()
                .setBasePath(basePath)
                .setVerifyingSsl(true)
                .setCertificateAuthority(Files.readAllBytes(Paths.get(SERVICE_ACCOUNT_CA_PATH)))
                .setAuthentication(new AccessTokenAuthentication(token))
                .build();
    }

}
