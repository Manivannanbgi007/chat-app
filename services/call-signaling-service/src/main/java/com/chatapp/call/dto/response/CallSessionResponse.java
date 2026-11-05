package com.chatapp.call.dto.response;

import java.util.List;

/** The answer to "start a call" and "accept": the call plus the ICE servers this browser should use (may be empty). */
public record CallSessionResponse(CallResponse call, List<IceServerResponse> iceServers) {
}
