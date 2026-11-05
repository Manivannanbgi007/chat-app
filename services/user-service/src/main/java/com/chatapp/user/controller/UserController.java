package com.chatapp.user.controller;

import java.util.List;
import java.util.UUID;

import com.chatapp.user.dto.request.PrivacyRequest;
import com.chatapp.user.dto.request.UpdateProfileRequest;
import com.chatapp.user.dto.response.ApiResponseDTO;
import com.chatapp.user.dto.response.MyProfileResponse;
import com.chatapp.user.dto.response.PrivacyResponse;
import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.security.CurrentUser;
import com.chatapp.user.service.BlockService;
import com.chatapp.user.service.PrivacyService;
import com.chatapp.user.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Profile, search, blocking and privacy. The caller is always taken from the token (CurrentUser). */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users")
public class UserController {

    private final ProfileService profileService;
    private final BlockService blockService;
    private final PrivacyService privacyService;

    public UserController(ProfileService profileService, BlockService blockService, PrivacyService privacyService) {
        this.profileService = profileService;
        this.blockService = blockService;
        this.privacyService = privacyService;
    }

    @GetMapping("/me")
    @Operation(summary = "My profile")
    public ApiResponseDTO<MyProfileResponse> getMe(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Profile loaded", profileService.getMyProfile(CurrentUser.id(jwt)));
    }

    @PutMapping("/me")
    @Operation(summary = "Update my display name and about text")
    public ApiResponseDTO<MyProfileResponse> updateMe(@AuthenticationPrincipal Jwt jwt,
                                                      @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponseDTO.ok("Profile updated", profileService.updateMyProfile(CurrentUser.id(jwt), request));
    }

    @GetMapping("/search")
    @Operation(summary = "Search users by username, name or phone (2-50 characters, max 20 results)")
    public ApiResponseDTO<List<PublicProfileResponse>> search(@AuthenticationPrincipal Jwt jwt,
                                                              @RequestParam("q") String q) {
        return ApiResponseDTO.ok("Search finished", profileService.search(CurrentUser.id(jwt), q));
    }

    @GetMapping("/blocked")
    @Operation(summary = "Users I have blocked")
    public ApiResponseDTO<List<PublicProfileResponse>> blocked(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Blocked users loaded", blockService.listBlocked(CurrentUser.id(jwt)));
    }

    @GetMapping("/me/privacy")
    @Operation(summary = "My privacy settings")
    public ApiResponseDTO<PrivacyResponse> getPrivacy(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Privacy settings loaded", privacyService.getPrivacy(CurrentUser.id(jwt)));
    }

    @PutMapping("/me/privacy")
    @Operation(summary = "Change my privacy settings")
    public ApiResponseDTO<PrivacyResponse> updatePrivacy(@AuthenticationPrincipal Jwt jwt,
                                                         @Valid @RequestBody PrivacyRequest request) {
        return ApiResponseDTO.ok("Privacy settings updated",
                privacyService.updatePrivacy(CurrentUser.id(jwt), request));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Another user's profile (filtered by their privacy settings)")
    public ApiResponseDTO<PublicProfileResponse> getUser(@AuthenticationPrincipal Jwt jwt,
                                                         @PathVariable UUID userId) {
        return ApiResponseDTO.ok("Profile loaded", profileService.getPublicProfile(CurrentUser.id(jwt), userId));
    }

    @PostMapping("/{userId}/block")
    @Operation(summary = "Block a user")
    public ApiResponseDTO<Void> block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        blockService.block(CurrentUser.id(jwt), userId);
        return ApiResponseDTO.ok("User blocked", null);
    }

    @DeleteMapping("/{userId}/block")
    @Operation(summary = "Unblock a user")
    public ApiResponseDTO<Void> unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        blockService.unblock(CurrentUser.id(jwt), userId);
        return ApiResponseDTO.ok("User unblocked", null);
    }
}
