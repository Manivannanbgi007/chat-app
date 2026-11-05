package com.chatapp.call.client;

import java.net.http.HttpClient;
import java.time.Duration;

import com.chatapp.call.config.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds the HTTP clients for calls to other services: short timeouts (2 s connect, 3 s read), the shared internal
 * token, and the correlation id of the current work so one request can be followed through all logs.
 */
@Component
public class InternalRestClientFactory {

    private final String internalToken;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public InternalRestClientFactory(@Value("${chatapp.internal.token}") String internalToken,
                                     @Value("${chatapp.services.connect-timeout-ms}") int connectTimeoutMs,
                                     @Value("${chatapp.services.read-timeout-ms}") int readTimeoutMs) {
        this.internalToken = internalToken;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public RestClient create(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Token", internalToken)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
