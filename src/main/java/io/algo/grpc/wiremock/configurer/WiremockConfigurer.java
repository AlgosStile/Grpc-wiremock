package io.algo.grpc.wiremock.configurer;

public interface WiremockConfigurer {
    String[] configure(String... args);
}
