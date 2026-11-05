package com.chatapp.gateway.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How the gateway finds the address of a service.
 *
 * Docker gives a container a NEW address when it is recreated (a deployment, a rebuild), and its DNS says the old
 * address is valid for 10 minutes. The gateway used to keep calling the old address and answered 503 "connection
 * refused" for every request to that service until it was restarted (found in Phase 12).
 *
 * The fix has two parts. This bean makes the gateway's HTTP client use the plain Java name lookup (not Netty's own DNS
 * client, which keeps the 10 minutes). The Dockerfile then sets the Java cache time to a few seconds
 * (-Dsun.net.inetaddr.ttl=5), so a redeployed service is found again quickly.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClientCustomizer javaNameLookup() {
        return httpClient -> httpClient.resolver(DefaultAddressResolverGroup.INSTANCE);
    }
}
