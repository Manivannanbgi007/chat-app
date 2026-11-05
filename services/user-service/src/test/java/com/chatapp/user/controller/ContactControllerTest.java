package com.chatapp.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.user.TestJwt;
import com.chatapp.user.dto.request.AddContactRequest;
import com.chatapp.user.dto.response.ContactResponse;
import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.exception.ConflictException;
import com.chatapp.user.exception.GlobalExceptionHandler;
import com.chatapp.user.exception.ResourceNotFoundException;
import com.chatapp.user.security.ApiSecurityErrorHandler;
import com.chatapp.user.security.SecurityConfig;
import com.chatapp.user.service.ContactService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// The secret is set here so the test never depends on a local infra/.env file.
@WebMvcTest(value = ContactController.class,
        properties = {"security.jwt.secret=" + TestJwt.SECRET, "security.jwt.issuer=" + TestJwt.ISSUER})
@Import({SecurityConfig.class, ApiSecurityErrorHandler.class, GlobalExceptionHandler.class})
class ContactControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContactService contactService;

    private final UUID me = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();

    @Test
    void everyContactEndpointNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/contacts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/contacts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactUserId\":\"" + friend + "\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/contacts/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void addRequiresContactUserId() throws Exception {
        mockMvc.perform(post("/api/contacts").with(jwtOf(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Buddy\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.contactUserId").exists());
    }

    @Test
    void addRejectsTooLongNickname() throws Exception {
        mockMvc.perform(post("/api/contacts").with(jwtOf(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactUserId\":\"" + friend + "\",\"nickname\":\"" + "n".repeat(51) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.nickname").exists());
    }

    @Test
    void addReturns201AndUsesTheCallerAsOwner() throws Exception {
        when(contactService.addContact(eq(me), any())).thenReturn(contactResponse());

        mockMvc.perform(post("/api/contacts").with(jwtOf(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        // an "ownerId" in the body must be ignored
                        .content("{\"contactUserId\":\"" + friend + "\",\"nickname\":\"Buddy\","
                                + "\"ownerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nickname").value("Buddy"));

        verify(contactService).addContact(me, new AddContactRequest(friend, "Buddy"));
    }

    @Test
    void duplicateContactShowsTheErrorCode() throws Exception {
        when(contactService.addContact(eq(me), any()))
                .thenThrow(new ConflictException("This user is already in your contacts", "ALREADY_CONTACT"));

        mockMvc.perform(post("/api/contacts").with(jwtOf(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactUserId\":\"" + friend + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("ALREADY_CONTACT"));
    }

    @Test
    void listReturnsTheContacts() throws Exception {
        when(contactService.listContacts(me)).thenReturn(List.of(contactResponse()));

        mockMvc.perform(get("/api/contacts").with(jwtOf(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].user.username").value("pal"));
    }

    @Test
    void deletePassesCallerAndContactId() throws Exception {
        UUID contactId = UUID.randomUUID();

        mockMvc.perform(delete("/api/contacts/" + contactId).with(jwtOf(me)))
                .andExpect(status().isOk());

        verify(contactService).removeContact(me, contactId);
    }

    @Test
    void deletingSomeoneElsesContactIs404() throws Exception {
        UUID contactId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Contact not found"))
                .when(contactService).removeContact(me, contactId);

        mockMvc.perform(delete("/api/contacts/" + contactId).with(jwtOf(me)))
                .andExpect(status().isNotFound());
    }

    private static RequestPostProcessor jwtOf(UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()));
    }

    private ContactResponse contactResponse() {
        return new ContactResponse(UUID.randomUUID(), friend, "Buddy", Instant.now(),
                new PublicProfileResponse(friend, "pal", "Pal", null, null));
    }
}
