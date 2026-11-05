package com.chatapp.notification.mapper;

import com.chatapp.notification.dto.response.NotificationResponse;
import com.chatapp.notification.dto.response.PreferencesResponse;
import com.chatapp.notification.entity.Notification;
import com.chatapp.notification.entity.NotificationPreference;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Entity to response mapping (MapStruct generates the code at build time). */
@Mapper
public interface NotificationMapper {

    @Mapping(target = "read", source = "read")
    NotificationResponse toResponse(Notification notification);

    PreferencesResponse toResponse(NotificationPreference preference);
}
