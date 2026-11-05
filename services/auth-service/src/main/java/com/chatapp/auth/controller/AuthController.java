package com.chatapp.auth.controller;

import com.chatapp.auth.dto.request.ForgotPasswordRequest;
import com.chatapp.auth.dto.request.LoginRequest;
import com.chatapp.auth.dto.request.LogoutRequest;
import com.chatapp.auth.dto.request.RefreshRequest;
import com.chatapp.auth.dto.request.RegisterRequest;
import com.chatapp.auth.dto.request.ResendOtpRequest;
import com.chatapp.auth.dto.request.ResetPasswordRequest;
import com.chatapp.auth.dto.request.VerifyOtpRequest;
import com.chatapp.auth.dto.response.ApiResponseDTO;
import com.chatapp.auth.dto.response.AuthTokensResponse;
import com.chatapp.auth.dto.response.RegisterResponse;
import com.chatapp.auth.service.AuthenticationService;
import com.chatapp.auth.service.PasswordResetService;
import com.chatapp.auth.service.RegistrationService;
import com.chatapp.auth.util.ClientInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoints: register, verify, login, refresh, logout, password reset. Controllers stay thin. */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final AuthenticationService authenticationService;
    private final PasswordResetService passwordResetService;

    public AuthController(RegistrationService registrationService, AuthenticationService authenticationService,
                          PasswordResetService passwordResetService) {
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account and email a verification code")
    public ResponseEntity<ApiResponseDTO<RegisterResponse>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        RegisterResponse response = registrationService.register(request, ClientInfo.from(http));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDTO.ok("Account created. Please verify your email", response));
    }

    @PostMapping("/verify-otp")
    @Operation(summary = "Verify the email address with the 6-digit code")
    public ApiResponseDTO<Void> verifyOtp(@Valid @RequestBody VerifyOtpRequest request, HttpServletRequest http) {
        registrationService.verifyOtp(request, ClientInfo.from(http));
        return ApiResponseDTO.ok("Email verified", null);
    }

    @PostMapping("/resend-otp")
    @Operation(summary = "Send a new verification code")
    public ApiResponseDTO<Void> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        registrationService.resendOtp(request);
        return ApiResponseDTO.ok("If the account exists, a new code was sent", null);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with email or phone and password")
    public ApiResponseDTO<AuthTokensResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ApiResponseDTO.ok("Login successful", authenticationService.login(request, ClientInfo.from(http)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair (the old refresh token stops working)")
    public ApiResponseDTO<AuthTokensResponse> refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return ApiResponseDTO.ok("Token refreshed",
                authenticationService.refresh(request.refreshToken(), ClientInfo.from(http)));
    }

    @PostMapping("/logout")
    @Operation(summary = "End the device session that owns this refresh token")
    public ApiResponseDTO<Void> logout(@Valid @RequestBody LogoutRequest request, HttpServletRequest http) {
        authenticationService.logout(request.refreshToken(), ClientInfo.from(http));
        return ApiResponseDTO.ok("Logged out", null);
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Email a password reset link (always answers the same)")
    public ApiResponseDTO<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        passwordResetService.requestReset(request, ClientInfo.from(http));
        return ApiResponseDTO.ok("If the account exists, a reset link was sent", null);
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password with the link from the email")
    public ApiResponseDTO<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        passwordResetService.resetPassword(request, ClientInfo.from(http));
        return ApiResponseDTO.ok("Password changed. Please log in again", null);
    }
}
