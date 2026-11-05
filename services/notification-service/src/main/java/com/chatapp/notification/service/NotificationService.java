package com.chatapp.notification.service;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.notification.dto.response.NotificationResponse;
import com.chatapp.notification.dto.response.PageResponse;
import com.chatapp.notification.dto.response.UnreadCountResponse;
import com.chatapp.notification.dto.response.UpdatedCountResponse;
import com.chatapp.notification.entity.Notification;
import com.chatapp.notification.exception.BadRequestException;
import com.chatapp.notification.exception.ResourceNotFoundException;
import com.chatapp.notification.mapper.NotificationMapper;
import com.chatapp.notification.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What the user does with their own notifications through the REST API. Everything is scoped to the caller. */
@Service
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 50;

    private final NotificationRepository repository;
    private final NotificationMapper mapper;

    public NotificationService(NotificationRepository repository, NotificationMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Newest activity first (a chat that just got another message moves to the top). */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UUID userId, int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or more", "VALIDATION");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE, "VALIDATION");
        }
        Page<Notification> result = repository.findByUserId(userId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))));
        return new PageResponse<>(result.getContent().stream().map(mapper::toResponse).toList(),
                page, size, result.getTotalElements(), result.hasNext());
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(UUID userId) {
        return new UnreadCountResponse(repository.countByUserIdAndReadFalse(userId));
    }

    /** 404 when the notification does not exist OR belongs to somebody else (the same answer for both). */
    @Transactional
    public NotificationResponse markRead(UUID userId, UUID notificationId) {
        Notification notification = repository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (!notification.isRead()) {
            notification.markRead(Instant.now());
        }
        return mapper.toResponse(notification);
    }

    @Transactional
    public UpdatedCountResponse markAllRead(UUID userId) {
        return new UpdatedCountResponse(repository.markAllRead(userId, Instant.now()));
    }
}
