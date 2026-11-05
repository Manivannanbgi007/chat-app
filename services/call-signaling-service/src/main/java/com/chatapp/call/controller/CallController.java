package com.chatapp.call.controller;

import java.util.Map;
import java.util.UUID;

import com.chatapp.call.dto.request.SignalRequest;
import com.chatapp.call.dto.request.StartCallRequest;
import com.chatapp.call.dto.response.ApiResponseDTO;
import com.chatapp.call.dto.response.CallResponse;
import com.chatapp.call.dto.response.CallSessionResponse;
import com.chatapp.call.dto.response.PageResponse;
import com.chatapp.call.exception.FieldValidationException;
import com.chatapp.call.security.CurrentUser;
import com.chatapp.call.service.CallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One-to-one calls (API_DOCUMENTATION.md 7.3). The user is always the token's "sub" and the device the token's
 * "sid", never a value from the request.
 */
@RestController
@RequestMapping("/api/calls")
@Tag(name = "Calls")
public class CallController {

    private final CallService callService;

    public CallController(CallService callService) {
        this.callService = callService;
    }

    @PostMapping
    @Operation(summary = "Call someone (RINGING), or learn at once that they are busy (BUSY)")
    public ResponseEntity<ApiResponseDTO<CallSessionResponse>> start(@AuthenticationPrincipal Jwt jwt,
                                                                     @Valid @RequestBody StartCallRequest request) {
        CallSessionResponse session = callService.start(CurrentUser.id(jwt), CurrentUser.deviceId(jwt), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseDTO.ok("Call started", session));
    }

    @GetMapping("/active")
    @Operation(summary = "My call that is ringing or in progress (null when there is none)")
    public ApiResponseDTO<CallResponse> active(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Active call", callService.active(CurrentUser.id(jwt)));
    }

    @GetMapping("/history")
    @Operation(summary = "Every call I made or received, newest first")
    public ApiResponseDTO<PageResponse<CallResponse>> history(@AuthenticationPrincipal Jwt jwt,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return ApiResponseDTO.ok("Call history", callService.history(CurrentUser.id(jwt), page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One of my calls (404 when it does not exist or I am not part of it)")
    public ApiResponseDTO<CallResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Call", callService.get(CurrentUser.id(jwt), id));
    }

    @PostMapping("/{id}/accept")
    @Operation(summary = "Answer on this device (callee only)")
    public ApiResponseDTO<CallSessionResponse> accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Call accepted", callService.accept(CurrentUser.id(jwt), CurrentUser.deviceId(jwt), id));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Decline a ringing call (callee only)")
    public ApiResponseDTO<CallResponse> reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Call declined", callService.reject(CurrentUser.id(jwt), id));
    }

    @PostMapping("/{id}/end")
    @Operation(summary = "Hang up (cancel while ringing). Safe to repeat")
    public ApiResponseDTO<CallResponse> end(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Call ended", callService.end(CurrentUser.id(jwt), id));
    }

    @PostMapping("/{id}/signal")
    @Operation(summary = "Relay a WebRTC offer, answer or ICE candidates to the other call device")
    public ApiResponseDTO<Void> signal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @Valid @RequestBody SignalRequest request) {
        Map<String, String> problems = request.fieldErrors();
        if (!problems.isEmpty()) {
            throw new FieldValidationException(problems);
        }
        callService.signal(CurrentUser.id(jwt), CurrentUser.deviceId(jwt), id, request);
        return ApiResponseDTO.ok("Signal relayed", null);
    }

    @PostMapping("/{id}/ping")
    @Operation(summary = "My side of the call is still alive (every 10 s during the call)")
    public ApiResponseDTO<Void> ping(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        callService.ping(CurrentUser.id(jwt), CurrentUser.deviceId(jwt), id);
        return ApiResponseDTO.ok("Ping recorded", null);
    }
}
