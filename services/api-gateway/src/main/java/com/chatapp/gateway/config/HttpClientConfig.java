package com.chatapp.gateway.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How the gateway remembers the address of a service.
 *
 * Docker gives a container a NEW address when it is recreated (a deployment, a rebuild). The gateway's HTTP client
 * remembers the address a name had for as long as the DNS answer says, and Docker's DNS says 10 minutes: after a
 * deployment the gateway kept calling the old address and answered 503 "connection refused" for every request to that
 * service (found in Phase 12 when auth-service was rebuilt: every login failed until the gateway was restarted).
 * Here the remembered address is dropped after a few seconds, so a redeployed service is found again quickly.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClientCustomizer shortDnsCache(@Value("${chatapp.gateway.dns-cache-seconds:5}") long cacheSeconds) {
        Duration ttl = Duration.ofSeconds(cacheSeconds);
        return httpClient -> httpClient.resolver(spec -> spec
                .cacheMaxTimeToLive(ttl)
                .cacheNegativeTimeToLive(Duration.ofSeconds(1)));
    }
}
