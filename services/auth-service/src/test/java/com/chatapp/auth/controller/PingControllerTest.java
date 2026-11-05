package com.chatapp.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chatapp.auth.config.CorrelationIdFilter;
import com.chatapp.auth.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Fast web-layer test (no database). Security rules are tested in SecurityRulesTest. */
@WebMvcTest(PingController.class)
@ActiveProfiles("test")
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class})
class PingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pingIsPublicAndReturnsStandardEnvelope() throws Exception {
        mockMvc.perform(get("/api/auth/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("pong"))
                .andExpect(jsonPath("$.data.service").value("auth-service"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void responseAlwaysCarriesACorrelationId() throws Exception {
        mockMvc.perform(get("/api/auth/ping"))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void existingCorrelationIdIsKept() throws Exception {
        mockMvc.perform(get("/api/auth/ping").header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
    }

    @Test
    void unsafeCorrelationIdIsReplaced() throws Exception {
        mockMvc.perform(get("/api/auth/ping").header("X-Correlation-Id", "bad id with spaces"))
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern("[A-Za-z0-9_-]{1,64}")));
    }
}
