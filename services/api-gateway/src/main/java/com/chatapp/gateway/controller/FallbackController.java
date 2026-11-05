package com.chatapp.gateway.controller;

import com.chatapp.gateway.dto.ApiResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * Called by the circuit breaker when a downstream service is down, too slow, or its circuit is open.
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @RequestMapping("/fallback")
    public ResponseEntity<ApiResponseDTO<Void>> fallback(ServerWebExchange exchange) {
        // Say WHY (Phase 9: a load test showed 503s with nothing in the logs). The cause is the exception that
        // made the circuit breaker give up, for example a timeout, a refused connection or "circuit is open".
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        log.warn("Answering 503 for route {}: {}", route == null ? "unknown" : route.getId(),
                cause == null ? "no exception recorded" : cause.getClass().getSimpleName() + ": " + cause.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponseDTO.error("Service is temporarily unavailable. Please try again later.", null));
    }
}
