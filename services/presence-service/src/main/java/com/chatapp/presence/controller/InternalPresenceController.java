package com.chatapp.presence.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.chatapp.presence.dto.response.ApiResponseDTO;
import com.chatapp.presence.dto.response.OnlineUsersResponse;
import com.chatapp.presence.exception.BadRequestException;
import com.chatapp.presence.service.PresenceService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Only other services call this (header X-Internal-Token, see SecurityConfig). The gateway never routes /internal/**.
 * It ignores privacy on purpose: it is for server-side decisions (for example "do not push to a user who is online").
 */
@Hidden
@RestController
@RequestMapping("/internal/presence")
public class InternalPresenceController {

    static final int MAX_IDS = 200;

    private final PresenceService presenceService;

    public InternalPresenceController(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    /** GET /internal/presence/online?userIds=a,b,c  (1 to 200 ids) */
    @GetMapping("/online")
    public ApiResponseDTO<OnlineUsersResponse> online(@RequestParam String userIds) {
        List<UUID> ids = parse(userIds);
        return ApiResponseDTO.ok("Online users", new OnlineUsersResponse(presenceService.onlineAmong(ids)));
    }

    private List<UUID> parse(String userIds) {
        String[] parts = userIds.split(",", -1);
        if (parts.length > MAX_IDS) {
            throw new BadRequestException("At most " + MAX_IDS + " user ids per request", "VALIDATION");
        }
        List<UUID> ids = new ArrayList<>();
        for (String part : parts) {
            try {
                ids.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("userIds must be a comma separated list of UUIDs", "VALIDATION");
            }
        }
        return ids;
    }
}
