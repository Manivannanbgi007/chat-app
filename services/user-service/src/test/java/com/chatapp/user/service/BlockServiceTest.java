package com.chatapp.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.chatapp.user.entity.BlockedUser;
import com.chatapp.user.exception.BadRequestException;
import com.chatapp.user.exception.ResourceNotFoundException;
import com.chatapp.user.repository.BlockedUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockServiceTest {

    @Mock
    private BlockedUserRepository blockedUserRepository;
    @Mock
    private UserDirectory userDirectory;
    @Mock
    private ProfileVisibilityService visibilityService;

    @InjectMocks
    private BlockService service;

    private final UUID me = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @Test
    void cannotBlockYourself() {
        assertThatThrownBy(() -> service.block(me, me)).isInstanceOf(BadRequestException.class);
        verify(blockedUserRepository, never()).save(any());
    }

    @Test
    void cannotBlockAnUnknownUser() {
        when(userDirectory.require(target)).thenThrow(new ResourceNotFoundException("User not found"));

        assertThatThrownBy(() -> service.block(me, target)).isInstanceOf(ResourceNotFoundException.class);
        verify(blockedUserRepository, never()).save(any());
    }

    @Test
    void blockingTwiceDoesNothingTheSecondTime() {
        when(blockedUserRepository.existsByBlockerIdAndBlockedId(me, target)).thenReturn(true);

        service.block(me, target);

        verify(blockedUserRepository, never()).save(any());
    }

    @Test
    void blockSavesTheBlock() {
        service.block(me, target);

        ArgumentCaptor<BlockedUser> saved = ArgumentCaptor.forClass(BlockedUser.class);
        verify(blockedUserRepository).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getBlockerId()).isEqualTo(me);
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getBlockedId()).isEqualTo(target);
    }

    @Test
    void unblockDeletesOnlyMyOwnBlock() {
        service.unblock(me, target);

        verify(blockedUserRepository).deleteByBlockerIdAndBlockedId(me, target);
    }
}
