package com.chatapp.auth.service;

import java.util.Locale;
import java.util.Optional;

import com.chatapp.auth.entity.AuthUser;
import com.chatapp.auth.repository.AuthUserRepository;
import org.springframework.stereotype.Service;

/** Finds a user by "identifier": an email address (contains @) or a phone number. */
@Service
public class UserLookupService {

    private final AuthUserRepository repository;

    public UserLookupService(AuthUserRepository repository) {
        this.repository = repository;
    }

    public Optional<AuthUser> findByIdentifier(String identifier) {
        String value = identifier.trim();
        if (value.contains("@")) {
            return repository.findByEmail(value.toLowerCase(Locale.ROOT));
        }
        return repository.findByPhone(value);
    }
}
