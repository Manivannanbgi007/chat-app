package com.chatapp.user.mapper;

import com.chatapp.user.dto.request.PrivacyRequest;
import com.chatapp.user.dto.response.MyProfileResponse;
import com.chatapp.user.dto.response.PrivacyResponse;
import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserPrivacySettings;
import com.chatapp.user.entity.UserProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/** MapStruct writes the boring copy code for us at compile time (see target/generated-sources). */
@Mapper
public interface UserMapper {

    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "username", source = "user.username")
    @Mapping(target = "displayName", source = "profile.displayName")
    @Mapping(target = "email", source = "user.email")
    @Mapping(target = "phone", source = "user.phone")
    @Mapping(target = "profileImageUrl", source = "profile.profileImageUrl")
    @Mapping(target = "about", source = "profile.about")
    @Mapping(target = "accountStatus", source = "user.accountStatus")
    @Mapping(target = "createdAt", source = "user.createdAt")
    @Mapping(target = "updatedAt", source = "profile.updatedAt")
    MyProfileResponse toMyProfile(User user, UserProfile profile);

    PrivacyResponse toPrivacyResponse(UserPrivacySettings settings);

    /** Copies the request values into the existing entity (userId and updatedAt are not touched here). */
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updatePrivacy(PrivacyRequest request, @MappingTarget UserPrivacySettings settings);
}
