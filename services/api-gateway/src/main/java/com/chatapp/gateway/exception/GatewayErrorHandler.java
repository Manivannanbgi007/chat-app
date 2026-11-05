package com.chatapp.gateway.exception;

import com.chatapp.gateway.dto.ApiResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns errors raised by the gateway itself (no route, bad gateway, ...) into our standard
 * ApiResponseDTO JSON, so the frontend only has to understand one error format.
 */
@Component
@Order(-2) // run before Spring Boot's default error handler
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final JsonMapper jsonMapper;

    public GatewayErrorHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatusCode status = (ex instanceof ResponseStatusException rse)
                ? rse.getStatusCode()
                : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) {
            // Log the cause on the server; never send exception details to the client.
            log.error("Gateway error on {} {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), ex);
        }

        var body = ApiResponseDTO.error(messageFor(status), null);
        byte[] bytes = jsonMapper.writeValueAsBytes(body);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private String messageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Bad request";
            case 401 -> "Authentication is required or the token is invalid";
            case 403 -> "You are not allowed to do this";
            case 404 -> "Resource not found";
            case 405 -> "Method not allowed";
            case 429 -> "Too many requests";
            case 502 -> "Bad gateway";
            case 503 -> "Service is temporarily unavailable. Please try again later.";
            case 504 -> "The service took too long to respond";
            default -> status.is4xxClientError() ? "Request failed" : "Internal server error";
        };
    }
}
