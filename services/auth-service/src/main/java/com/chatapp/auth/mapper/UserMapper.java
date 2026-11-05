package com.chatapp.auth.mapper;

import com.chatapp.auth.dto.response.UserSummary;
import com.chatapp.auth.entity.AuthUser;
import com.chatapp.auth.kafka.UserRegisteredPayload;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserSummary toSummary(AuthUser user);

    @Mapping(target = "userId", source = "id")
    UserRegisteredPayload toUserRegisteredPayload(AuthUser user);
}
