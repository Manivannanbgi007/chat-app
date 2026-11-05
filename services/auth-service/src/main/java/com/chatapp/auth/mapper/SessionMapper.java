package com.chatapp.auth.mapper;

import com.chatapp.auth.dto.response.SessionResponse;
import com.chatapp.auth.entity.LoginSession;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SessionMapper {

    @Mapping(target = "current", source = "current")
    SessionResponse toResponse(LoginSession session, boolean current);
}
