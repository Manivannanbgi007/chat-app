package com.chatapp.message.websocket;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.chatapp.message.dto.request.DeliveredRequest;
import com.chatapp.message.dto.request.ReadRequest;
import com.chatapp.message.dto.request.SendMessageRequest;
import com.chatapp.message.dto.request.TypingRequest;
import com.chatapp.message.dto.response.MessageDto;
import com.chatapp.message.dto.response.SendResult;
import com.chatapp.message.exception.ApiException;
import com.chatapp.message.exception.BadRequestException;
import com.chatapp.message.exception.TooManyRequestsException;
import com.chatapp.message.repository.ConversationMemberRepository;
import com.chatapp.message.service.ConversationAccessService;
import com.chatapp.message.service.MessageService;
import com.chatapp.message.service.ReceiptService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * Messages sent by the browser to /app/chat.*. These are thin: they check the input, call the same
 * services as the REST API, and answer. The sender is ALWAYS the authenticated principal; the payloads
 * have no "sender" field at all.
 */
@Controller
public class ChatStompController {

    private static final Logger log = LoggerFactory.getLogger(ChatStompController.class);

    public record AckData(String idempotencyKey, MessageDto message) {
    }

    public record ErrorData(String code, String message, String idempotencyKey) {
    }

    private final MessageService messageService;
    private final ReceiptService receiptService;
    private final ConversationAccessService accessService;
    private final ConversationMemberRepository memberRepository;
    private final RealtimePublisher realtimePublisher;
    private final SlidingWindowLimiter limiter;
    private final Validator validator;

    public ChatStompController(MessageService messageService, ReceiptService receiptService,
                               ConversationAccessService accessService, ConversationMemberRepository memberRepository,
                               RealtimePublisher realtimePublisher, SlidingWindowLimiter limiter, Validator validator) {
        this.messageService = messageService;
        this.receiptService = receiptService;
        this.accessService = accessService;
        this.memberRepository = memberRepository;
        this.realtimePublisher = realtimePublisher;
        this.limiter = limiter;
        this.validator = validator;
    }

    /** Same as POST /api/messages. The answer goes only to the sending connection. */
    @MessageMapping("chat.send")
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public RealtimeEvent<AckData> send(@Payload SendMessageRequest request, Principal principal,
                                       SimpMessageHeaderAccessor headers) {
        String idempotencyKey = request == null ? null : request.idempotencyKey();
        try {
            validate(request);
            // at most 30 messages per 10 seconds per connection
            if (!limiter.allow(headers.getSessionId() + ":send", 30, 10_000)) {
                throw new TooManyRequestsException("You are sending too fast", "RATE_LIMITED");
            }
            SendResult result = messageService.send(userId(principal), request);
            return RealtimeEvent.of("MESSAGE_ACK", request.conversationId(), new AckData(idempotencyKey, result.message()));
        } catch (ApiException e) {
            throw new StompSendException(idempotencyKey, e);      // so the error event can name the message
        }
    }

    /**
     * "I am typing" for the other members. Too frequent events are dropped without an error.
     * Only current members may type; the event goes to /user/queue/typing of every current member (the typist's
     * own devices too, clients ignore their own id). A removed group member is no longer in that list.
     */
    @MessageMapping("chat.typing")
    public void typing(@Payload TypingRequest request, Principal principal, SimpMessageHeaderAccessor headers) {
        validate(request);
        if (!limiter.allow(headers.getSessionId() + ":typing", 1, 1_000)) {
            return;
        }
        UUID userId = userId(principal);
        boolean knownMember = memberRepository
                .existsByConversationIdAndUserIdAndLeftAtIsNull(request.conversationId(), userId);
        if (!knownMember) {
            // e.g. a brand new conversation our copy does not know yet: ask the source of truth (also updates our copy)
            accessService.requireMember(request.conversationId(), userId);
        }
        RealtimeEvent<Map<String, Object>> event = RealtimeEvent.of("TYPING", request.conversationId(), Map.of(
                "conversationId", request.conversationId(),
                "userId", userId,
                "typing", request.typing()));
        realtimePublisher.toUsers(memberRepository.findCurrentMemberIds(request.conversationId()), "/queue/typing", event);
    }

    /** Same as POST /api/messages/delivered. */
    @MessageMapping("chat.delivered")
    public void delivered(@Payload DeliveredRequest request, Principal principal, SimpMessageHeaderAccessor headers) {
        validate(request);
        receiptService.markDelivered(userId(principal), deviceId(headers), request.messageIds());
    }

    /** Same as POST /api/conversations/{id}/read. */
    @MessageMapping("chat.read")
    public void read(@Payload ReadRequest request, Principal principal, SimpMessageHeaderAccessor headers) {
        validate(request);
        if (request.conversationId() == null) {
            throw new BadRequestException("conversationId is required", "VALIDATION");
        }
        receiptService.markRead(userId(principal), deviceId(headers), request.conversationId(), request.upToMessageId());
    }

    /** Any problem while handling a frame becomes an ERROR event for the sender (their own connection only). */
    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public RealtimeEvent<ErrorData> handleError(Exception ex) {
        if (ex instanceof StompSendException sendFailure) {
            ApiException failure = sendFailure.getFailure();
            return RealtimeEvent.of("ERROR", null,
                    new ErrorData(codeOf(failure), failure.getMessage(), sendFailure.getIdempotencyKey()));
        }
        if (ex instanceof ApiException apiException) {
            return RealtimeEvent.of("ERROR", null, new ErrorData(codeOf(apiException), apiException.getMessage(), null));
        }
        log.warn("Unexpected error while handling a WebSocket frame", ex);
        return RealtimeEvent.of("ERROR", null, new ErrorData("INTERNAL_ERROR", "Something went wrong", null));
    }

    private String codeOf(ApiException exception) {
        if (exception.getErrorCode() != null) {
            return exception.getErrorCode();
        }
        return switch (exception.getStatus()) {
            case NOT_FOUND -> "NOT_FOUND";
            case BAD_REQUEST -> "VALIDATION";
            case TOO_MANY_REQUESTS -> "RATE_LIMITED";
            default -> "ERROR";
        };
    }

    private void validate(Object payload) {
        if (payload == null) {
            throw new BadRequestException("The frame has no body", "VALIDATION");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .reduce((a, b) -> a + "; " + b).orElse("invalid");
            throw new BadRequestException("Validation failed: " + details, "VALIDATION");
        }
    }

    private UUID userId(Principal principal) {
        return UUID.fromString(principal.getName());
    }

    private UUID deviceId(SimpMessageHeaderAccessor headers) {
        Map<String, Object> attributes = headers.getSessionAttributes();
        return UUID.fromString(String.valueOf(attributes.get(StompAuthChannelInterceptor.ATTR_SESSION_ID)));
    }
}
