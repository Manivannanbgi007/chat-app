package com.chatapp.user.integration;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.chatapp.user.TestJwt;
import com.chatapp.user.dto.request.AddContactRequest;
import com.chatapp.user.dto.request.PrivacyRequest;
import com.chatapp.user.enums.Visibility;
import com.chatapp.user.service.BlockService;
import com.chatapp.user.service.ContactService;
import com.chatapp.user.service.PrivacyService;
import com.chatapp.user.service.UserRegistrationService;
import com.chatapp.user.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The internal API on a real MySQL, called with the shared token like another service would. */
class InternalApiTest extends AbstractIntegrationTest {

    private static final String TOKEN = "X-Internal-Token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRegistrationService registrationService;
    @Autowired
    private BlockService blockService;
    @Autowired
    private ContactService contactService;
    @Autowired
    private PrivacyService privacyService;

    // ---- blocks ----

    @Test
    void blockCheckSeesABlockInBothDirections() throws Exception {
        UUID alice = newUser("blk");
        UUID bob = newUser("blk");
        UUID carol = newUser("blk");

        blocks(alice, bob).andExpect(jsonPath("$.data.blocked").value(false));

        blockService.block(alice, bob);   // alice blocks bob

        blocks(alice, bob).andExpect(jsonPath("$.data.blocked").value(true));   // blocker first
        blocks(bob, alice).andExpect(jsonPath("$.data.blocked").value(true));   // blocked user first
        blocks(alice, carol).andExpect(jsonPath("$.data.blocked").value(false));
        blocks(carol, bob).andExpect(jsonPath("$.data.blocked").value(false));

        blockService.unblock(alice, bob);
        blocks(alice, bob).andExpect(jsonPath("$.data.blocked").value(false));
    }

    @Test
    void blockCheckOfAUserWithThemselvesIsFalse() throws Exception {
        UUID alice = newUser("self");
        blocks(alice, alice).andExpect(jsonPath("$.data.blocked").value(false));
    }

    // ---- privacy ----

    @Test
    void privacyLookupReturnsTheCurrentSettings() throws Exception {
        UUID alice = newUser("prv");

        mockMvc.perform(get("/internal/users/" + alice + "/privacy").header(TOKEN, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readReceipts").value(true))
                .andExpect(jsonPath("$.data.lastSeen").value("EVERYONE"));

        privacyService.updatePrivacy(alice, new PrivacyRequest(
                Visibility.NOBODY, Visibility.CONTACTS, Visibility.CONTACTS, Visibility.NOBODY, false));

        mockMvc.perform(get("/internal/users/" + alice + "/privacy").header(TOKEN, TestJwt.INTERNAL_TOKEN))
                .andExpect(jsonPath("$.data.readReceipts").value(false))
                .andExpect(jsonPath("$.data.lastSeen").value("NOBODY"))
                .andExpect(jsonPath("$.data.groupAdd").value("NOBODY"));
    }

    @Test
    void privacyOfAnUnknownUserIs404() throws Exception {
        mockMvc.perform(get("/internal/users/" + UUID.randomUUID() + "/privacy").header(TOKEN, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isNotFound());
    }

    // ---- summaries ----

    @Test
    void summariesReturnPublicProfilesInRequestOrderAndIgnoreUnknownIds() throws Exception {
        UUID viewer = newUser("viewer");
        UUID bob = newUser("bob");
        UUID carol = newUser("carol");
        UUID unknown = UUID.randomUUID();

        summaries(viewer, carol, unknown, bob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(carol.toString()))
                .andExpect(jsonPath("$.data[1].id").value(bob.toString()))
                .andExpect(jsonPath("$.data[0].email").doesNotExist())
                .andExpect(jsonPath("$.data[0].phone").doesNotExist());
    }

    @Test
    void summariesApplyThePrivacyOfEachUserRelativeToTheViewer() throws Exception {
        UUID viewer = newUser("viewer");
        UUID friend = newUser("friend");        // has the viewer as a contact
        UUID stranger = newUser("stranger");    // does not
        for (UUID owner : new UUID[]{friend, stranger}) {
            jdbc.update("UPDATE user_profiles SET profile_image_url = ?, about = ? WHERE user_id = ?",
                    "https://example.com/" + owner + ".png", "about " + owner, owner.toString());
            // photo: contacts only, about: nobody
            privacyService.updatePrivacy(owner, new PrivacyRequest(
                    Visibility.EVERYONE, Visibility.CONTACTS, Visibility.NOBODY, Visibility.EVERYONE, true));
        }
        contactService.addContact(friend, new AddContactRequest(viewer, null));

        summaries(viewer, friend, stranger)
                .andExpect(status().isOk())
                // the friend added the viewer as a contact -> photo allowed, about is NOBODY -> hidden
                .andExpect(jsonPath("$.data[0].id").value(friend.toString()))
                .andExpect(jsonPath("$.data[0].profileImageUrl").value("https://example.com/" + friend + ".png"))
                .andExpect(jsonPath("$.data[0].about").doesNotExist())
                // the stranger did not -> photo hidden too
                .andExpect(jsonPath("$.data[1].id").value(stranger.toString()))
                .andExpect(jsonPath("$.data[1].profileImageUrl").doesNotExist())
                .andExpect(jsonPath("$.data[1].about").doesNotExist());
    }

    @Test
    void viewerSeesThemselvesWithoutPrivacyFiltering() throws Exception {
        UUID viewer = newUser("me");
        jdbc.update("UPDATE user_profiles SET about = ? WHERE user_id = ?", "my own about", viewer.toString());
        privacyService.updatePrivacy(viewer, new PrivacyRequest(
                Visibility.NOBODY, Visibility.NOBODY, Visibility.NOBODY, Visibility.NOBODY, true));

        summaries(viewer, viewer)
                .andExpect(jsonPath("$.data[0].about").value("my own about"));
    }

    @Test
    void usersWhoBlockedTheViewerAreOmitted() throws Exception {
        UUID viewer = newUser("viewer");
        UUID blocker = newUser("blocker");
        UUID other = newUser("other");
        blockService.block(blocker, viewer);   // blocker blocked the viewer

        summaries(viewer, blocker, other)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(other.toString()));

        // ...but the one the viewer blocked is still shown (the viewer knows about that block)
        UUID blockedByViewer = newUser("blockedbyviewer");
        blockService.block(viewer, blockedByViewer);
        summaries(viewer, blockedByViewer)
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void emptyListGivesEmptyAnswer() throws Exception {
        UUID viewer = newUser("viewer");
        summaries(viewer).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void duplicateIdsAreReturnedOnce() throws Exception {
        UUID viewer = newUser("viewer");
        UUID bob = newUser("bob");
        summaries(viewer, bob, bob, bob).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void moreThan100IdsAreRejected() throws Exception {
        UUID viewer = newUser("viewer");
        String ids = IntStream.range(0, 101).mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/internal/users/summaries").header(TOKEN, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + viewer + "\",\"userIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest());
    }

    // ---- group-add check ----

    @Test
    void groupAddCheckFollowsPrivacyContactsAndBlocks() throws Exception {
        UUID actor = newUser("actor");
        UUID open = newUser("open");                 // groupAdd EVERYONE (the default)
        UUID friend = newUser("friend");             // CONTACTS, has the actor as a contact
        UUID stranger = newUser("stranger");         // CONTACTS, does not
        UUID closed = newUser("closed");             // NOBODY, even though the actor is a contact
        UUID blockedActor = newUser("blockedactor"); // blocked the actor
        UUID blockedByActor = newUser("blockedby");  // the actor blocked them
        UUID unknown = UUID.randomUUID();

        setGroupAdd(friend, Visibility.CONTACTS);
        setGroupAdd(stranger, Visibility.CONTACTS);
        setGroupAdd(closed, Visibility.NOBODY);
        contactService.addContact(friend, new AddContactRequest(actor, null));
        contactService.addContact(closed, new AddContactRequest(actor, null));
        // the actor having THEM as a contact does not matter: only their own list counts
        contactService.addContact(actor, new AddContactRequest(stranger, null));
        blockService.block(blockedActor, actor);
        blockService.block(actor, blockedByActor);

        groupAddCheck(actor, open, friend, stranger, closed, blockedActor, blockedByActor, unknown, actor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notAllowed.length()").value(5))
                .andExpect(jsonPath("$.data.notAllowed[0]").value(stranger.toString()))
                .andExpect(jsonPath("$.data.notAllowed[1]").value(closed.toString()))
                .andExpect(jsonPath("$.data.notAllowed[2]").value(blockedActor.toString()))
                .andExpect(jsonPath("$.data.notAllowed[3]").value(blockedByActor.toString()))
                .andExpect(jsonPath("$.data.notAllowed[4]").value(unknown.toString()));
    }

    @Test
    void groupAddCheckAllowsEverybodyByDefault() throws Exception {
        UUID actor = newUser("actor");
        UUID bob = newUser("bob");
        UUID carol = newUser("carol");

        groupAddCheck(actor, bob, carol, bob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notAllowed.length()").value(0));
    }

    @Test
    void groupAddCheckNeedsTheInternalToken() throws Exception {
        UUID actor = newUser("actor");
        mockMvc.perform(post("/internal/users/group-add-check")
                        .header("Authorization", "Bearer " + TestJwt.token(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"" + actor + "\",\"userIds\":[\"" + actor + "\"]}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- security with the real application ----

    @Test
    void internalEndpointsRejectMissingTokenAndUserJwt() throws Exception {
        UUID alice = newUser("sec");

        mockMvc.perform(get("/internal/users/" + alice + "/privacy"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/users/" + alice + "/privacy")
                        .header("Authorization", "Bearer " + TestJwt.token(alice)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/users/" + alice + "/privacy").header(TOKEN, "wrong-wrong-wrong-wrong-wrong-12"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theInternalTokenDoesNotOpenTheNormalApi() throws Exception {
        mockMvc.perform(get("/api/users/me").header(TOKEN, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    // ---- presence visibility ----

    private void setLastSeen(UUID userId, Visibility lastSeen) {
        privacyService.updatePrivacy(userId, new PrivacyRequest(
                lastSeen, Visibility.EVERYONE, Visibility.EVERYONE, Visibility.EVERYONE, true));
    }

    private ResultActions presenceVisibility(UUID viewer, UUID target) throws Exception {
        return mockMvc.perform(post("/internal/users/presence-visibility").header(TOKEN, TestJwt.INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"viewerId\":\"" + viewer + "\",\"targetId\":\"" + target + "\"}"));
    }

    @Test
    void presenceIsVisibleByDefaultAndToTheUserThemselves() throws Exception {
        UUID viewer = newUser("pv");
        UUID target = newUser("pv");

        presenceVisibility(viewer, target).andExpect(status().isOk()).andExpect(jsonPath("$.data.visible").value(true));
        presenceVisibility(target, target).andExpect(jsonPath("$.data.visible").value(true));
    }

    @Test
    void presenceOfAnUnknownUserIsNotVisible() throws Exception {
        presenceVisibility(newUser("pv"), UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(false));
    }

    @Test
    void presenceIsHiddenByABlockInBothDirections() throws Exception {
        UUID alice = newUser("pvb");
        UUID bob = newUser("pvb");
        UUID carol = newUser("pvb");
        blockService.block(alice, bob);

        presenceVisibility(alice, bob).andExpect(jsonPath("$.data.visible").value(false));   // blocker looks at blocked
        presenceVisibility(bob, alice).andExpect(jsonPath("$.data.visible").value(false));   // blocked looks at blocker
        presenceVisibility(carol, bob).andExpect(jsonPath("$.data.visible").value(true));    // others are not affected

        blockService.unblock(alice, bob);
        presenceVisibility(alice, bob).andExpect(jsonPath("$.data.visible").value(true));
    }

    @Test
    void presenceFollowsTheLastSeenSettingOfTheTarget() throws Exception {
        UUID viewer = newUser("pvs");
        UUID friend = newUser("pvs");      // will have the viewer as a contact
        UUID stranger = newUser("pvs");    // will not
        contactService.addContact(friend, new AddContactRequest(viewer, null));

        // CONTACTS: only the user who saved the viewer shows their status
        setLastSeen(friend, Visibility.CONTACTS);
        setLastSeen(stranger, Visibility.CONTACTS);
        presenceVisibility(viewer, friend).andExpect(jsonPath("$.data.visible").value(true));
        presenceVisibility(viewer, stranger).andExpect(jsonPath("$.data.visible").value(false));

        // NOBODY hides it from everyone, even from a contact
        setLastSeen(friend, Visibility.NOBODY);
        presenceVisibility(viewer, friend).andExpect(jsonPath("$.data.visible").value(false));

        // the user always sees themselves
        presenceVisibility(friend, friend).andExpect(jsonPath("$.data.visible").value(true));
    }

    @Test
    void theDirectionOfTheContactMatters() throws Exception {
        UUID viewer = newUser("pvd");
        UUID target = newUser("pvd");
        setLastSeen(target, Visibility.CONTACTS);
        contactService.addContact(viewer, new AddContactRequest(target, null));   // the VIEWER saved the target only

        presenceVisibility(viewer, target).andExpect(jsonPath("$.data.visible").value(false));

        contactService.addContact(target, new AddContactRequest(viewer, null));   // now the target saved the viewer
        presenceVisibility(viewer, target).andExpect(jsonPath("$.data.visible").value(true));
    }

    @Test
    void whoeverHidesTheirOwnStatusCannotSeeOthers() throws Exception {
        UUID hider = newUser("pvr");
        UUID open = newUser("pvr");
        setLastSeen(hider, Visibility.NOBODY);

        presenceVisibility(hider, open).andExpect(jsonPath("$.data.visible").value(false));   // reciprocity
        presenceVisibility(open, hider).andExpect(jsonPath("$.data.visible").value(false));   // hidden from others
        presenceVisibility(hider, hider).andExpect(jsonPath("$.data.visible").value(true));   // own status always
    }

    @Test
    void presenceVisibilityNeedsTheInternalToken() throws Exception {
        UUID viewer = newUser("pvt");
        mockMvc.perform(post("/internal/users/presence-visibility").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + viewer + "\",\"targetId\":\"" + viewer + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- status audience (Phase 8) ----

    @Test
    void contactOwnersAreThePeopleWhoSavedMeNotThePeopleISaved() throws Exception {
        UUID viewer = newUser("sav");
        UUID savedMe = newUser("sav");
        UUID alsoSavedMe = newUser("sav");
        UUID iSavedThem = newUser("sav");
        UUID stranger = newUser("sav");
        contactService.addContact(savedMe, new AddContactRequest(viewer, null));
        contactService.addContact(alsoSavedMe, new AddContactRequest(viewer, "nick"));
        contactService.addContact(viewer, new AddContactRequest(iSavedThem, null));   // the wrong direction
        contactService.addContact(stranger, new AddContactRequest(savedMe, null));    // about somebody else

        contactOwners(viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerIds.length()").value(2))
                .andExpect(jsonPath("$.data.ownerIds", containsInAnyOrder(savedMe.toString(), alsoSavedMe.toString())));
    }

    @Test
    void aBlockInEitherDirectionRemovesTheOwner() throws Exception {
        UUID viewer = newUser("sab");
        UUID blockedViewer = newUser("sab");     // saved the viewer, then blocked them
        UUID blockedByViewer = newUser("sab");   // saved the viewer, but the viewer blocked them
        UUID friend = newUser("sab");
        contactService.addContact(blockedViewer, new AddContactRequest(viewer, null));
        contactService.addContact(blockedByViewer, new AddContactRequest(viewer, null));
        contactService.addContact(friend, new AddContactRequest(viewer, null));
        blockService.block(blockedViewer, viewer);
        blockService.block(viewer, blockedByViewer);

        contactOwners(viewer)
                .andExpect(jsonPath("$.data.ownerIds.length()").value(1))
                .andExpect(jsonPath("$.data.ownerIds[0]").value(friend.toString()));

        blockService.unblock(viewer, blockedByViewer);
        contactOwners(viewer).andExpect(jsonPath("$.data.ownerIds.length()").value(2));
    }

    @Test
    void nobodySavedMeGivesAnEmptyList() throws Exception {
        contactOwners(newUser("san")).andExpect(jsonPath("$.data.ownerIds.length()").value(0));
        contactOwners(UUID.randomUUID()).andExpect(jsonPath("$.data.ownerIds.length()").value(0));
    }

    @Test
    void relationReportsTheContactDirectionAndBlocks() throws Exception {
        UUID owner = newUser("rel");
        UUID viewer = newUser("rel");

        relation(owner, viewer).andExpect(jsonPath("$.data.contact").value(false))
                .andExpect(jsonPath("$.data.blocked").value(false));

        contactService.addContact(viewer, new AddContactRequest(owner, null));        // the viewer saved the owner
        relation(owner, viewer).andExpect(jsonPath("$.data.contact").value(false));   // ...does not count

        contactService.addContact(owner, new AddContactRequest(viewer, null));        // the owner saved the viewer
        relation(owner, viewer).andExpect(jsonPath("$.data.contact").value(true))
                .andExpect(jsonPath("$.data.blocked").value(false));

        blockService.block(viewer, owner);
        relation(owner, viewer).andExpect(jsonPath("$.data.contact").value(true))
                .andExpect(jsonPath("$.data.blocked").value(true));
        blockService.unblock(viewer, owner);
        blockService.block(owner, viewer);
        relation(owner, viewer).andExpect(jsonPath("$.data.blocked").value(true));

        relation(owner, owner).andExpect(jsonPath("$.data.contact").value(false))
                .andExpect(jsonPath("$.data.blocked").value(false));
    }

    @Test
    void statusAudienceEndpointsNeedTheInternalToken() throws Exception {
        UUID viewer = newUser("sat");
        mockMvc.perform(get("/internal/users/" + viewer + "/contact-owners")
                        .header("Authorization", "Bearer " + TestJwt.token(viewer)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/contacts/relation").param("ownerId", viewer.toString())
                        .param("viewerId", viewer.toString()))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private ResultActions contactOwners(UUID viewer) throws Exception {
        return mockMvc.perform(get("/internal/users/" + viewer + "/contact-owners").header(TOKEN, TestJwt.INTERNAL_TOKEN));
    }

    private ResultActions relation(UUID owner, UUID viewer) throws Exception {
        return mockMvc.perform(get("/internal/contacts/relation").param("ownerId", owner.toString())
                        .param("viewerId", viewer.toString()).header(TOKEN, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk());
    }


    private ResultActions blocks(UUID a, UUID b) throws Exception {
        return mockMvc.perform(get("/internal/blocks").param("a", a.toString()).param("b", b.toString())
                        .header(TOKEN, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk());
    }

    private ResultActions summaries(UUID viewer, UUID... ids) throws Exception {
        String idList = java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return mockMvc.perform(post("/internal/users/summaries").header(TOKEN, TestJwt.INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"viewerId\":\"" + viewer + "\",\"userIds\":[" + idList + "]}"));
    }

    private ResultActions groupAddCheck(UUID actor, UUID... ids) throws Exception {
        String idList = java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return mockMvc.perform(post("/internal/users/group-add-check").header(TOKEN, TestJwt.INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorId\":\"" + actor + "\",\"userIds\":[" + idList + "]}"));
    }

    private void setGroupAdd(UUID userId, Visibility groupAdd) {
        privacyService.updatePrivacy(userId, new PrivacyRequest(
                Visibility.EVERYONE, Visibility.EVERYONE, Visibility.EVERYONE, groupAdd, true));
    }

    private UUID newUser(String prefix) {
        UUID id = UUID.randomUUID();
        String username = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String phone = "+91" + String.format("%010d", Math.abs(id.getMostSignificantBits() % 10_000_000_000L));
        registrationService.createUser(UUID.randomUUID(), id, username, username + "@example.com", phone,
                username.toUpperCase());
        return id;
    }
}
