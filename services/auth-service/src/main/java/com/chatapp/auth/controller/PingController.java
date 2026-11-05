package com.chatapp.auth.controller;

import com.chatapp.auth.dto.response.ApiResponseDTO;
import com.chatapp.auth.dto.response.PingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint that proves the whole chain works:
 * browser -> gateway -> auth-service -> standard response format.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
public class PingController {

    @GetMapping("/ping")
    @Operation(summary = "Check that the auth service is reachable")
    public ApiResponseDTO<PingResponse> ping() {
        return ApiResponseDTO.ok("pong", new PingResponse("auth-service", "UP"));
    }
}
