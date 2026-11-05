package com.chatapp.user.controller;

import java.util.List;
import java.util.UUID;

import com.chatapp.user.dto.request.AddContactRequest;
import com.chatapp.user.dto.response.ApiResponseDTO;
import com.chatapp.user.dto.response.ContactResponse;
import com.chatapp.user.security.CurrentUser;
import com.chatapp.user.service.ContactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contacts")
@Tag(name = "Contacts")
public class ContactController {

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a user to my contacts")
    public ApiResponseDTO<ContactResponse> add(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody AddContactRequest request) {
        return ApiResponseDTO.ok("Contact added", contactService.addContact(CurrentUser.id(jwt), request));
    }

    @GetMapping
    @Operation(summary = "My contacts, sorted by name")
    public ApiResponseDTO<List<ContactResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Contacts loaded", contactService.listContacts(CurrentUser.id(jwt)));
    }

    @DeleteMapping("/{contactId}")
    @Operation(summary = "Remove a contact")
    public ApiResponseDTO<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID contactId) {
        contactService.removeContact(CurrentUser.id(jwt), contactId);
        return ApiResponseDTO.ok("Contact removed", null);
    }
}
