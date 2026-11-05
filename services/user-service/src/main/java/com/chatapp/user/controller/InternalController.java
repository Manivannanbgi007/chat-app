package com.chatapp.user.controller;

import java.util.List;
import java.util.UUID;

import com.chatapp.user.dto.request.GroupAddCheckRequest;
import com.chatapp.user.dto.request.PresenceVisibilityRequest;
import com.chatapp.user.dto.request.SummariesRequest;
import com.chatapp.user.dto.response.ApiResponseDTO;
import com.chatapp.user.dto.response.BlockCheckResponse;
import com.chatapp.user.dto.response.ContactOwnersResponse;
import com.chatapp.user.dto.response.ContactRelationResponse;
import com.chatapp.user.dto.response.VisibilityResponse;
import com.chatapp.user.dto.response.GroupAddCheckResponse;
import com.chatapp.user.dto.response.PrivacyResponse;
import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.service.GroupAddPolicyService;
import com.chatapp.user.service.PresenceVisibilityService;
import com.chatapp.user.service.InternalLookupService;
import com.chatapp.user.service.PrivacyService;
import com.chatapp.user.service.StatusAudienceService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for OTHER SERVICES only (never routed by the gateway, protected by X-Internal-Token).
 * Hidden from Swagger on purpose. Here the user ids come from the caller, because the caller is a
 * trusted service that already checked who the user is.
 */
@Hidden
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final InternalLookupService lookupService;
    private final PrivacyService privacyService;
    private final GroupAddPolicyService groupAddPolicyService;
    private final PresenceVisibilityService presenceVisibilityService;
    private final StatusAudienceService statusAudienceService;

    public InternalController(InternalLookupService lookupService, PrivacyService privacyService,
                              GroupAddPolicyService groupAddPolicyService,
                              PresenceVisibilityService presenceVisibilityService,
                              StatusAudienceService statusAudienceService) {
        this.lookupService = lookupService;
        this.privacyService = privacyService;
        this.groupAddPolicyService = groupAddPolicyService;
        this.presenceVisibilityService = presenceVisibilityService;
        this.statusAudienceService = statusAudienceService;
    }

    @GetMapping("/blocks")
    public ApiResponseDTO<BlockCheckResponse> blocks(@RequestParam UUID a, @RequestParam UUID b) {
        return ApiResponseDTO.ok("Block check done", new BlockCheckResponse(lookupService.isBlockedEitherWay(a, b)));
    }

    @GetMapping("/users/{id}/privacy")
    public ApiResponseDTO<PrivacyResponse> privacy(@PathVariable UUID id) {
        return ApiResponseDTO.ok("Privacy settings loaded", privacyService.getPrivacy(id));
    }

    /** Which of these users may the actor NOT add to a group? (blocks + each user's "groupAdd" privacy) */
    @PostMapping("/users/group-add-check")
    public ApiResponseDTO<GroupAddCheckResponse> groupAddCheck(@Valid @RequestBody GroupAddCheckRequest request) {
        return ApiResponseDTO.ok("Group add check done", new GroupAddCheckResponse(
                groupAddPolicyService.notAllowed(request.actorId(), request.userIds())));
    }

    /** May the viewer see the target's online status / last seen? (blocks, "lastSeen" privacy, contacts, reciprocity) */
    @PostMapping("/users/presence-visibility")
    public ApiResponseDTO<VisibilityResponse> presenceVisibility(@Valid @RequestBody PresenceVisibilityRequest request) {
        return ApiResponseDTO.ok("Presence visibility checked", new VisibilityResponse(
                presenceVisibilityService.canSee(request.viewerId(), request.targetId())));
    }

    /** Who saved this viewer as a contact (blocks either way removed)? status-service builds the status feed from it. */
    @GetMapping("/users/{viewerId}/contact-owners")
    public ApiResponseDTO<ContactOwnersResponse> contactOwners(@PathVariable UUID viewerId) {
        return ApiResponseDTO.ok("Contact owners loaded",
                new ContactOwnersResponse(statusAudienceService.contactOwners(viewerId)));
    }

    /** Is the viewer in the owner's contact list, and is there a block either way? (may the viewer see a status?) */
    @GetMapping("/contacts/relation")
    public ApiResponseDTO<ContactRelationResponse> contactRelation(@RequestParam UUID ownerId, @RequestParam UUID viewerId) {
        return ApiResponseDTO.ok("Contact relation checked", statusAudienceService.relation(ownerId, viewerId));
    }

    @PostMapping("/users/summaries")
    public ApiResponseDTO<List<PublicProfileResponse>> summaries(@Valid @RequestBody SummariesRequest request) {
        return ApiResponseDTO.ok("Summaries loaded", lookupService.summaries(request.viewerId(), request.userIds()));
    }
}
