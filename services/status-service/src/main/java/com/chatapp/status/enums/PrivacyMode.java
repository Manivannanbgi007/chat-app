package com.chatapp.status.enums;

/**
 * Who may see my statuses (always only people in MY contact list, never someone blocked either way):
 *  - CONTACTS:         all my contacts (the default)
 *  - CONTACTS_EXCEPT:  my contacts except the chosen people
 *  - ONLY_SHARE_WITH:  only the chosen people (and only those who are also my contacts)
 */
public enum PrivacyMode {
    CONTACTS,
    CONTACTS_EXCEPT,
    ONLY_SHARE_WITH
}
