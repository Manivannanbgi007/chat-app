package com.chatapp.call.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.call.dto.request.SignalRequest;
import com.chatapp.call.dto.request.StartCallRequest;
import com.chatapp.call.dto.response.CallSessionResponse;
import com.chatapp.call.exception.GlobalExceptionHandler;
import com.chatapp.call.security.ApiSecurityErrorHandler;
import com.chatapp.call.security.SecurityConfig;
import com.chatapp.call.service.CallService;
import com.chatapp.call.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** JSON in and out, validation, error format, and that the user and device always come from the token. */
@WebMvcTest(value = CallController.class, properties = {
        "security.jwt.secret=" + TestJwt.SECRET,
        "security.jwt.issuer=" + TestJwt.ISSUER})
@Import({SecurityConfig.class, ApiSecurityErrorHandler.class, GlobalExceptionHandler.class})
class CallControllerTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID MY_DEVICE = UUID.randomUUID();
    private static final UUID CALL = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JsonMapper jsonMapper;
    @MockitoBean
    private CallService callService;

    private ResultActions postAsMe(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .with(jwt().jwt(token -> token.subject(ME.toString()).claim("sid", MY_DEVICE.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private ResultActions signal(Object body) throws Exception {
        return postAsMe("/api/calls/" + CALL + "/signal", body);
    }

    // ---------------------------------------------------------------- start

    @Test
    void startingACallUsesTheUserAndDeviceOfTheTokenAndAnswers201() throws Exception {
        UUID callee = UUID.randomUUID();
        when(callService.start(eq(ME), eq(MY_DEVICE), any())).thenReturn(new CallSessionResponse(null, List.of()));

        postAsMe("/api/calls", Map.of("calleeId", callee, "type", "AUDIO"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        verify(callService).start(ME, MY_DEVICE, new StartCallRequest(callee, com.chatapp.call.enums.CallType.AUDIO));
    }

    @Test
    void aStartRequestNeedsACalleeAndAKnownType() throws Exception {
        postAsMe("/api/calls", Map.of("type", "VIDEO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.calleeId").exists());
        postAsMe("/api/calls", Map.of("calleeId", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.type").exists());
        postAsMe("/api/calls", Map.of("calleeId", UUID.randomUUID(), "type", "HOLOGRAM"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(callService);
    }

    @Test
    void withoutATokenEverythingIs401() throws Exception {
        mockMvc.perform(post("/api/calls").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post("/api/calls/" + CALL + "/accept")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutADeviceIsRefused() throws Exception {
        mockMvc.perform(post("/api/calls/" + CALL + "/accept").with(jwt().jwt(token -> token.subject(ME.toString()))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(callService);
    }

    // --------------------------------------------------------------- signal

    @Test
    void validSignalsAreHandedToTheService() throws Exception {
        signal(Map.of("type", "OFFER", "sdp", "v=0")).andExpect(status().isOk());
        signal(Map.of("type", "ANSWER", "sdp", "x".repeat(20000))).andExpect(status().isOk());
        List<Map<String, Object>> fifty = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fifty.add(Map.of("candidate", "candidate:" + i, "sdpMid", "0", "sdpMLineIndex", 0));
        }
        signal(Map.of("type", "ICE", "candidates", fifty)).andExpect(status().isOk());
        // an empty candidate ("end of candidates") with no mid / index is allowed
        signal(Map.of("type", "ICE", "candidates", List.of(Map.of("candidate", "")))).andExpect(status().isOk());

        verify(callService, times(4)).signal(eq(ME), eq(MY_DEVICE), eq(CALL), any(SignalRequest.class));
    }

    @Test
    void offerAndAnswerNeedSdpAndNoCandidates() throws Exception {
        signal(Map.of("type", "OFFER")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.sdp").exists());
        signal(Map.of("type", "ANSWER", "sdp", "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.sdp").exists());
        signal(Map.of("type", "OFFER", "sdp", "v=0", "candidates", List.of(Map.of("candidate", "c"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.candidates").exists());
        signal(Map.of("type", "OFFER", "sdp", "x".repeat(20001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.sdp").exists());
        verifyNoInteractions(callService);
    }

    @Test
    void iceNeedsOneToFiftyValidCandidatesAndNoSdp() throws Exception {
        signal(Map.of("type", "ICE")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.candidates").exists());
        signal(Map.of("type", "ICE", "candidates", List.of())).andExpect(status().isBadRequest());
        signal(Map.of("type", "ICE", "sdp", "v=0", "candidates", List.of(Map.of("candidate", "c"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.sdp").exists());

        List<Map<String, Object>> fiftyOne = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            fiftyOne.add(Map.of("candidate", "c" + i));
        }
        signal(Map.of("type", "ICE", "candidates", fiftyOne)).andExpect(status().isBadRequest());
        signal(Map.of("type", "ICE", "candidates", List.of(Map.of("candidate", "x".repeat(1025)))))
                .andExpect(status().isBadRequest());
        signal(Map.of("type", "ICE", "candidates", List.of(Map.of("candidate", "c", "sdpMLineIndex", 65))))
                .andExpect(status().isBadRequest());
        signal(Map.of("type", "ICE", "candidates", List.of(Map.of("candidate", "c", "sdpMid", "m".repeat(65)))))
                .andExpect(status().isBadRequest());
        signal(Map.of("type", "ICE", "candidates", List.of(Map.of("sdpMid", "0")))).andExpect(status().isBadRequest());
        signal(Map.of("type", "SHOUT", "sdp", "v=0")).andExpect(status().isBadRequest());
        signal(Map.of("sdp", "v=0")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.type").exists());
        verifyNoInteractions(callService);
    }

    @Test
    void aCallIdThatIsNotAUuidIs400() throws Exception {
        postAsMe("/api/calls/not-a-uuid/end", Map.of()).andExpect(status().isBadRequest());
    }
}
