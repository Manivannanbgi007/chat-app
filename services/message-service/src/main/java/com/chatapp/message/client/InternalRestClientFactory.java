package com.chatapp.message.client;

import java.net.http.HttpClient;
import java.time.Duration;

import com.chatapp.message.config.AppProperties;
import com.chatapp.message.config.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds the HTTP clients for calls to other services: short timeouts, the shared internal token,
 * and the correlation id of the current request so one request can be followed through all logs.
 */
@Component
public class InternalRestClientFactory {

    private final AppProperties properties;

    public InternalRestClientFactory(AppProperties properties) {
        this.properties = properties;
    }

    public RestClient create(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.services().connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.services().readTimeoutMs()));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Token", properties.internal().token())
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
