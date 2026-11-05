package com.chatapp.presence.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.chatapp.presence.client.UserClient;
import com.chatapp.presence.dto.response.PresenceResponse;
import com.chatapp.presence.exception.DependencyUnavailableException;
import com.chatapp.presence.repository.PresenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

/** The presence rules with Redis and user-service mocked. */
@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    @Mock
    private PresenceRepository repository;
    @Mock
    private UserClient userClient;

    @InjectMocks
    private PresenceService service;

    private final UUID viewer = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @Test
    void youSeeYourselfWithoutAskingUserService() {
        Instant last = Instant.parse("2026-11-04T10:00:00Z");
        when(repository.isOnline(viewer)).thenReturn(true);
        when(repository.lastActive(viewer)).thenReturn(last);

        PresenceResponse response = service.presenceOf(viewer, viewer);

        assertThat(response).isEqualTo(new PresenceResponse(viewer, true, true, last));
        verifyNoInteractions(userClient);
    }

    @Test
    void aHiddenTargetIsNotEvenReadFromRedis() {
        when(userClient.canSeePresence(viewer, target)).thenReturn(false);

        PresenceResponse response = service.presenceOf(viewer, target);

        assertThat(response).isEqualTo(PresenceResponse.hidden(target));
        assertThat(response.online()).isNull();
        assertThat(response.lastSeenAt()).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void aVisibleTargetShowsOnlineAndLastSeen() {
        Instant last = Instant.parse("2026-11-04T10:00:00Z");
        when(userClient.canSeePresence(viewer, target)).thenReturn(true);
        when(repository.isOnline(target)).thenReturn(false);
        when(repository.lastActive(target)).thenReturn(last);

        assertThat(service.presenceOf(viewer, target)).isEqualTo(new PresenceResponse(target, true, false, last));
    }

    @Test
    void aNeverSeenTargetIsOfflineWithoutLastSeen() {
        when(userClient.canSeePresence(viewer, target)).thenReturn(true);
        when(repository.isOnline(target)).thenReturn(false);
        when(repository.lastActive(target)).thenReturn(null);

        PresenceResponse response = service.presenceOf(viewer, target);

        assertThat(response.visible()).isTrue();
        assertThat(response.online()).isFalse();
        assertThat(response.lastSeenAt()).isNull();
    }

    @Test
    void whenUserServiceCannotAnswerNothingIsShown() {
        when(userClient.canSeePresence(viewer, target)).thenThrow(new DependencyUnavailableException("down"));

        assertThatThrownBy(() -> service.presenceOf(viewer, target)).isInstanceOf(DependencyUnavailableException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void redisProblemsBecomeServiceUnavailable() {
        UUID device = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("boom")).when(repository).heartbeat(viewer, device);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("boom")).when(repository).markOffline(viewer, device);
        when(repository.onlineAmong(List.of(viewer))).thenThrow(new RedisConnectionFailureException("boom"));
        when(userClient.canSeePresence(viewer, target)).thenReturn(true);
        when(repository.isOnline(target)).thenThrow(new RedisConnectionFailureException("boom"));

        assertThatThrownBy(() -> service.heartbeat(viewer, device)).isInstanceOf(DependencyUnavailableException.class);
        assertThatThrownBy(() -> service.goOffline(viewer, device)).isInstanceOf(DependencyUnavailableException.class);
        assertThatThrownBy(() -> service.onlineAmong(List.of(viewer))).isInstanceOf(DependencyUnavailableException.class);
        assertThatThrownBy(() -> service.presenceOf(viewer, target)).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void heartbeatAndOfflineGoToTheRepositoryWithTheTokenIds() {
        UUID device = UUID.randomUUID();

        service.heartbeat(viewer, device);
        service.goOffline(viewer, device);

        verify(repository).heartbeat(viewer, device);
        verify(repository).markOffline(viewer, device);
    }

    @Test
    void theOnlineLookupReturnsTheOnlineIds() {
        when(repository.onlineAmong(List.of(viewer, target))).thenReturn(Set.of(target));

        assertThat(service.onlineAmong(List.of(viewer, target))).containsExactly(target);
    }
}
