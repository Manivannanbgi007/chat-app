package com.chatapp.user.repository;

import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserProfile;

/** A user together with their profile (one row of the search query). */
public record UserWithProfile(User user, UserProfile profile) {
}
