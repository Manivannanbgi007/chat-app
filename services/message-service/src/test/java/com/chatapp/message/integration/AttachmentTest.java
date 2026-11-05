package com.chatapp.message.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.message.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Media messages: the two-step send (upload first, then send with attachmentId) and the signed download links. */
class AttachmentTest extends AbstractIntegrationTest {

    @AfterEach
    void reset() {
        PEERS.mediaServiceDown = false;
        PEERS.unblockAll();
    }

    private UUID uploadedImage(Device owner) {
        return PEERS.addMedia(owner.userId(), "IMAGE", "holiday.png", "image/png", 2048, 640, 480, null, true);
    }

    private MvcResult sendMedia(Device from, UUID conversationId, String type, UUID attachmentId, String caption, String key)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("conversationId", conversationId);
        body.put("type", type);
        body.put("idempotencyKey", key);
        if (attachmentId != null) {
            body.put("attachmentId", attachmentId);
        }
        if (caption != null) {
            body.put("content", caption);
        }
        return post("/api/messages", body, from);
    }

    private MvcResult link(Device who, String messageId, String variant) throws Exception {
        return get("/api/messages/" + messageId + "/attachment/url" + (variant == null ? "" : "?variant=" + variant), who);
    }

    // ------------------------------------------------------------------- sending

    @Test
    void imageMessageWithCaptionIsSavedWithItsAttachment() throws Exception {
        Chat chat = newChat();
        UUID media = uploadedImage(chat.a());

        MvcResult result = sendMedia(chat.a(), chat.id(), "IMAGE", media, "Look at this", newKey());

        assertStatus(result, 201);
        assertThat(field(result, "$.data.type")).isEqualTo("IMAGE");
        assertThat(field(result, "$.data.content")).isEqualTo("Look at this");
        assertThat(field(result, "$.data.attachment.mediaId")).isEqualTo(media.toString());
        assertThat(field(result, "$.data.attachment.kind")).isEqualTo("IMAGE");
        assertThat(field(result, "$.data.attachment.fileName")).isEqualTo("holiday.png");
        assertThat(field(result, "$.data.attachment.mimeType")).isEqualTo("image/png");
        assertThat(field(result, "$.data.attachment.width")).isEqualTo("640");
        assertThat(field(result, "$.data.attachment.hasThumbnail")).isEqualTo("true");
        assertThat(field(result, "$.data.status")).isEqualTo("SENT");
    }

    @Test
    void captionIsOptional() throws Exception {
        Chat chat = newChat();
        UUID media = PEERS.addMedia(chat.a().userId(), "VOICE", "voice-note.webm", "audio/webm", 4000, null, null, 7, false);

        MvcResult withoutCaption = sendMedia(chat.a(), chat.id(), "VOICE", media, null, newKey());

        assertStatus(withoutCaption, 201);
        assertThat(field(withoutCaption, "$.data.content")).isEqualTo("");
        assertThat(field(withoutCaption, "$.data.attachment.durationSeconds")).isEqualTo("7");
        assertThat(field(withoutCaption, "$.data.attachment.width")).isNull();
    }

    @Test
    void everyMediaTypeCanBeSent() throws Exception {
        Chat chat = newChat();
        for (String kind : List.of("IMAGE", "VIDEO", "AUDIO", "VOICE", "DOCUMENT")) {
            UUID media = PEERS.addMedia(chat.a().userId(), kind, "file", "application/octet-stream", 10, null, null, null, false);
            assertStatus(sendMedia(chat.a(), chat.id(), kind, media, "", newKey()), 201);
        }
    }

    @Test
    void historyAndSummariesCarryTheAttachmentMetadata() throws Exception {
        Chat chat = newChat();
        UUID media = PEERS.addMedia(chat.a().userId(), "DOCUMENT", "report.pdf", "application/pdf", 90_000, null, null, null, false);
        sendMedia(chat.a(), chat.id(), "DOCUMENT", media, "Please read", newKey());

        MvcResult history = get("/api/conversations/" + chat.id() + "/messages", chat.b());
        assertThat(field(history, "$.data.items[0].attachment.fileName")).isEqualTo("report.pdf");
        assertThat(field(history, "$.data.items[0].attachment.sizeBytes")).isEqualTo("90000");

        MvcResult summaries = get("/api/messages/summaries", chat.b());
        List<String> names = read(summaries, "$.data[?(@.conversationId=='" + chat.id() + "')].lastMessage.attachment.fileName");
        assertThat(names).containsExactly("report.pdf");
    }

    @Test
    void sameKeyReturnsTheSameMediaMessage() throws Exception {
        Chat chat = newChat();
        UUID media = uploadedImage(chat.a());
        String key = newKey();

        MvcResult first = sendMedia(chat.a(), chat.id(), "IMAGE", media, "once", key);
        MvcResult retry = sendMedia(chat.a(), chat.id(), "IMAGE", media, "once", key);

        assertStatus(first, 201);
        assertStatus(retry, 200);
        assertThat(field(retry, "$.data.id")).isEqualTo(field(first, "$.data.id"));
        assertThat(field(retry, "$.data.attachment.mediaId")).isEqualTo(media.toString());
    }

    @Test
    void aFileCannotBeUsedByTwoMessages() throws Exception {
        Chat chat = newChat();
        UUID media = uploadedImage(chat.a());
        assertStatus(sendMedia(chat.a(), chat.id(), "IMAGE", media, "first", newKey()), 201);

        MvcResult second = sendMedia(chat.a(), chat.id(), "IMAGE", media, "second", newKey());

        assertStatus(second, 409);
        assertThat(field(second, "$.data.errorCode")).isEqualTo("ATTACHMENT_ALREADY_USED");
    }

    @Test
    void anotherUsersFileIsRefusedWithTheSameAnswerAsAnUnknownFile() throws Exception {
        Chat chat = newChat();
        UUID bobsFile = uploadedImage(chat.b());

        MvcResult stolen = sendMedia(chat.a(), chat.id(), "IMAGE", bobsFile, "mine now", newKey());
        MvcResult unknown = sendMedia(chat.a(), chat.id(), "IMAGE", UUID.randomUUID(), "ghost", newKey());

        assertStatus(stolen, 404);
        assertStatus(unknown, 404);
        assertThat(field(stolen, "$.data.errorCode")).isEqualTo("ATTACHMENT_NOT_FOUND");
        assertThat(field(stolen, "$.message")).isEqualTo(field(unknown, "$.message"));
    }

    @Test
    void messageTypeMustMatchTheFileKind() throws Exception {
        Chat chat = newChat();
        UUID media = uploadedImage(chat.a());

        MvcResult result = sendMedia(chat.a(), chat.id(), "VIDEO", media, "", newKey());

        assertStatus(result, 400);
        assertThat(field(result, "$.data.errorCode")).isEqualTo("ATTACHMENT_TYPE_MISMATCH");
    }

    @Test
    void typeAndAttachmentMustFitTogether() throws Exception {
        Chat chat = newChat();
        UUID media = uploadedImage(chat.a());

        // media type without attachment
        MvcResult noAttachment = sendMedia(chat.a(), chat.id(), "IMAGE", null, "caption only", newKey());
        assertStatus(noAttachment, 400);
        assertThat(field(noAttachment, "$.data.errorCode")).isEqualTo("VALIDATION");

        // text with an attachment
        MvcResult textWithFile = sendMedia(chat.a(), chat.id(), "TEXT", media, "hello", newKey());
        assertStatus(textWithFile, 400);

        // text still needs text
        assertStatus(sendMedia(chat.a(), chat.id(), "TEXT", null, "  ", newKey()), 400);
        assertStatus(sendMedia(chat.a(), chat.id(), "TEXT", null, null, newKey()), 400);

        // types that cannot be sent
        for (String type : List.of("SYSTEM", "LOCATION", "CONTACT")) {
            MvcResult result = sendMedia(chat.a(), chat.id(), type, media, "x", newKey());
            assertStatus(result, 400);
            assertThat(field(result, "$.data.errorCode")).isEqualTo("UNSUPPORTED_TYPE");
        }

        // caption too long
        assertStatus(sendMedia(chat.a(), chat.id(), "IMAGE", media, "x".repeat(4001), newKey()), 400);
    }

    @Test
    void mediaServiceDownGives503AndSavesNothing() throws Exception {
        Chat chat = newChat();
        UUID media = uploadedImage(chat.a());
        PEERS.mediaServiceDown = true;
        String key = newKey();

        MvcResult result = sendMedia(chat.a(), chat.id(), "IMAGE", media, "hi", key);

        assertStatus(result, 503);
        assertThat(field(result, "$.data.errorCode")).isEqualTo("DEPENDENCY_UNAVAILABLE");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE idempotency_key = ?", Integer.class, key);
        assertThat(rows).isZero();
    }

    @Test
    void theUsualChatRulesStillApplyToMediaMessages() throws Exception {
        Chat chat = newChat();
        Device stranger = Device.newUser();
        UUID strangersFile = uploadedImage(stranger);
        assertStatus(sendMedia(stranger, chat.id(), "IMAGE", strangersFile, "let me in", newKey()), 404);

        PEERS.block(chat.b().userId(), chat.a().userId());
        UUID media = uploadedImage(chat.a());
        MvcResult blocked = sendMedia(chat.a(), chat.id(), "IMAGE", media, "blocked?", newKey());
        assertStatus(blocked, 403);
        assertThat(field(blocked, "$.data.errorCode")).isEqualTo("MESSAGING_NOT_ALLOWED");
    }

    @Test
    void mediaInGroupsFollowsTheGroupRules() throws Exception {
        Group group = newGroup(2);
        PEERS.setOnlyAdminsCanSend(group.id(), true);
        UUID memberFile = uploadedImage(group.member(0));

        MvcResult refused = sendMedia(group.member(0), group.id(), "IMAGE", memberFile, "", newKey());
        assertStatus(refused, 403);
        assertThat(field(refused, "$.data.errorCode")).isEqualTo("ONLY_ADMINS_CAN_SEND");

        UUID adminFile = uploadedImage(group.admin());
        assertStatus(sendMedia(group.admin(), group.id(), "IMAGE", adminFile, "announcement", newKey()), 201);
    }

    // ------------------------------------------------------------ download links

    @Test
    void recipientAndSenderGetSignedLinksForTheFile() throws Exception {
        Chat chat = newChat();
        UUID media = uploadedImage(chat.a());
        String messageId = field(sendMedia(chat.a(), chat.id(), "IMAGE", media, "", newKey()), "$.data.id");

        MvcResult asBob = link(chat.b(), messageId, null);
        assertStatus(asBob, 200);
        assertThat(field(asBob, "$.data.url")).startsWith("http://storage.test/" + media + "/original");
        assertThat(field(asBob, "$.data.expiresAt")).isNotBlank();

        assertStatus(link(chat.a(), messageId, "original"), 200);
        MvcResult thumbnail = link(chat.b(), messageId, "thumbnail");
        assertStatus(thumbnail, 200);
        assertThat(field(thumbnail, "$.data.url")).contains("/thumbnail");
        assertThat(PEERS.downloadLinkRequests).contains(chat.b().userId() + ":original", chat.b().userId() + ":thumbnail");
    }

    @Test
    void strangersCannotGetLinks() throws Exception {
        Chat chat = newChat();
        UUID media = uploadedImage(chat.a());
        String messageId = field(sendMedia(chat.a(), chat.id(), "IMAGE", media, "", newKey()), "$.data.id");

        assertStatus(link(Device.newUser(), messageId, null), 404);
        assertStatus(link(null, messageId, null), 401);
        assertStatus(link(chat.b(), UUID.randomUUID().toString(), null), 404);
    }

    @Test
    void thereIsNoLinkForTextMessagesOrMissingThumbnails() throws Exception {
        Chat chat = newChat();
        UUID text = sendOk(chat.a(), chat.id(), "just text");
        assertStatus(link(chat.b(), text.toString(), null), 404);

        UUID video = PEERS.addMedia(chat.a().userId(), "VIDEO", "clip.mp4", "video/mp4", 5000, null, null, 12, false);
        String videoMessage = field(sendMedia(chat.a(), chat.id(), "VIDEO", video, "", newKey()), "$.data.id");
        assertStatus(link(chat.b(), videoMessage, "original"), 200);
        assertStatus(link(chat.b(), videoMessage, "thumbnail"), 404);        // videos have no thumbnail
        assertStatus(link(chat.b(), videoMessage, "huge"), 400);             // unknown variant
    }

    @Test
    void membersRemovedFromAGroupCannotGetLinksToNewerFiles() throws Exception {
        Group group = newGroup(2);
        Device stays = group.member(0);
        Device removed = group.member(1);

        UUID before = uploadedImage(group.admin());
        String sentBefore = field(sendMedia(group.admin(), group.id(), "IMAGE", before, "before", newKey()), "$.data.id");
        assertStatus(link(removed, sentBefore, null), 200);

        PEERS.removeMember(group.id(), removed.userId());
        UUID after = uploadedImage(group.admin());
        String sentAfter = field(sendMedia(group.admin(), group.id(), "IMAGE", after, "after", newKey()), "$.data.id");

        assertStatus(link(stays, sentAfter, null), 200);
        assertStatus(link(removed, sentAfter, null), 404);          // created after the removal
        assertStatus(link(removed, sentBefore, null), 200);         // old messages stay visible to a former member
    }

    @Test
    void mediaServiceDownGives503ForLinks() throws Exception {
        Chat chat = newChat();
        UUID media = uploadedImage(chat.a());
        String messageId = field(sendMedia(chat.a(), chat.id(), "IMAGE", media, "", newKey()), "$.data.id");
        PEERS.mediaServiceDown = true;

        assertStatus(link(chat.b(), messageId, null), 503);
    }
}
