package com.chatapp.status.service;

import java.util.UUID;

import com.chatapp.status.enums.PrivacyMode;

/**
 * THE rule "may this viewer see this owner's statuses?", in one small pure function (easy to test, no database).
 * It is applied when someone LOOKS (not stored per status), so removing a contact, blocking someone or changing the
 * privacy mode takes effect at once, also for statuses posted before the change.
 */
public final class VisibilityRules {

    private VisibilityRules() {
    }

    /**
     * @param viewerIsContact  the viewer is in the OWNER's contact list
     * @param blocked          the owner blocked the viewer or the viewer blocked the owner
     * @param mode             the owner's status privacy mode (CONTACTS when they never chose one)
     * @param viewerIsListed   the viewer is on the owner's list (the excepted / chosen people of the mode)
     */
    public static boolean maySee(UUID viewerId, UUID ownerId, boolean viewerIsContact, boolean blocked,
                                 PrivacyMode mode, boolean viewerIsListed) {
        if (viewerId.equals(ownerId)) {
            return true;                      // I always see my own statuses
        }
        if (blocked || !viewerIsContact) {
            return false;                     // never with a block, and only ever for my contacts
        }
        return switch (mode == null ? PrivacyMode.CONTACTS : mode) {
            case CONTACTS -> true;
            case CONTACTS_EXCEPT -> !viewerIsListed;
            case ONLY_SHARE_WITH -> viewerIsListed;
        };
    }
}
