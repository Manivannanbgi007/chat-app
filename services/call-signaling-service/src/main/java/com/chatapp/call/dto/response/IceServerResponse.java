package com.chatapp.call.dto.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One entry of RTCPeerConnection's "iceServers" list. A STUN server has only urls; the TURN server also needs a
 * username and a password (credential), which are left out of the JSON when they are null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IceServerResponse(List<String> urls, String username, String credential) {
}
