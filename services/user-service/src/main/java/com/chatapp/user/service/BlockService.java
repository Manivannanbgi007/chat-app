package com.chatapp.user.service;

import java.util.List;
import java.util.UUID;

import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.entity.BlockedUser;
import com.chatapp.user.exception.BadRequestException;
import com.chatapp.user.repository.BlockedUserRepository;
import com.chatapp.user.repository.UserWithProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlockService {

    private final BlockedUserRepository blockedUserRepository;
    private final UserDirectory userDirectory;
    private final ProfileVisibilityService visibilityService;

    public BlockService(BlockedUserRepository blockedUserRepository,
                        UserDirectory userDirectory,
                        ProfileVisibilityService visibilityService) {
        this.blockedUserRepository = blockedUserRepository;
        this.userDirectory = userDirectory;
        this.visibilityService = visibilityService;
    }

    /** Idempotent: blocking someone who is already blocked does nothing. */
    @Transactional
    public void block(UUID myId, UUID targetId) {
        if (myId.equals(targetId)) {
            throw new BadRequestException("You cannot block yourself");
        }
        userDirectory.require(targetId); // 404 if the user does not exist

        if (blockedUserRepository.existsByBlockerIdAndBlockedId(myId, targetId)) {
            return;
        }
        // If two requests block the same user at the same moment, the unique key rejects the
        // second one and GlobalExceptionHandler answers 409.
        blockedUserRepository.save(new BlockedUser(myId, targetId));
    }

    /** Idempotent: unblocking someone who is not blocked does nothing. */
    @Transactional
    public void unblock(UUID myId, UUID targetId) {
        blockedUserRepository.deleteByBlockerIdAndBlockedId(myId, targetId);
    }

    @Transactional(readOnly = true)
    public List<PublicProfileResponse> listBlocked(UUID myId) {
        List<UUID> blockedIds = blockedUserRepository.findByBlockerId(myId).stream()
                .map(BlockedUser::getBlockedId)
                .toList();
        List<UserWithProfile> users = userDirectory.findAll(blockedIds);
        return visibilityService.buildPublicProfiles(myId, users);
    }
}
