package com.chatapp.auth.service;

import java.util.Locale;
import java.util.Optional;

import com.chatapp.auth.entity.AuthUser;
import com.chatapp.auth.repository.AuthUserRepository;
import org.springframework.stereotype.Service;

/** Finds a user by "identifier": an email address (contains @), a phone number, or the username. */
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
        // Something that looks like a phone number is tried as a phone number first (a username may also be all digits).
        if (value.matches("[+]?[0-9 ()-]{6,}")) {
            Optional<AuthUser> byPhone = repository.findByPhone(value);
            if (byPhone.isPresent()) {
                return byPhone;
            }
        }
        // Usernames are stored in lower case (see RegistrationService).
        return repository.findByUsername(value.toLowerCase(Locale.ROOT));
    }
}
