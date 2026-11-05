package com.chatapp.conversation.mapper;

import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.dto.response.GroupInfoResponse;
import com.chatapp.conversation.dto.response.InternalConversationResponse;
import com.chatapp.conversation.dto.response.MemberPeriodResponse;
import com.chatapp.conversation.dto.response.MembershipResponse;
import com.chatapp.conversation.dto.response.PreferencesResponse;
import com.chatapp.conversation.dto.response.PublicProfileResponse;
import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationParticipant;
import com.chatapp.conversation.entity.GroupSettings;
import com.chatapp.conversation.entity.MembershipPeriod;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Entity to response mapping (MapStruct generates the code at build time). */
@Mapper
public interface ConversationMapper {

    @Mapping(target = "id", source = "conversation.id")
    @Mapping(target = "type", source = "conversation.type")
    @Mapping(target = "createdAt", source = "conversation.createdAt")
    @Mapping(target = "lastMessageAt", source = "conversation.lastMessageAt")
    @Mapping(target = "participants", source = "participants")
    @Mapping(target = "otherUser", source = "otherUser")
    @Mapping(target = "group", source = "groupInfo")
    @Mapping(target = "membership", source = "myMembership")
    @Mapping(target = "preferences", source = "myPreferences")
    ConversationResponse toResponse(Conversation conversation,
                                    List<PublicProfileResponse> participants,
                                    PublicProfileResponse otherUser,
                                    GroupInfoResponse groupInfo,
                                    MembershipResponse myMembership,
                                    PreferencesResponse myPreferences);

    /** My own membership: role, active, joinedAt, leftAt. */
    MembershipResponse toMembership(ConversationParticipant participant);

    /** My own settings for the chat. Archived / pinned are stored as times; the API only says yes or no. */
    @Mapping(target = "archived", expression = "java(participant.getArchivedAt() != null)")
    @Mapping(target = "pinned", expression = "java(participant.getPinnedAt() != null)")
    PreferencesResponse toPreferences(ConversationParticipant participant);

    @Mapping(target = "name", source = "settings.name")
    @Mapping(target = "description", source = "settings.description")
    @Mapping(target = "imageUrl", source = "settings.imageUrl")
    @Mapping(target = "onlyAdminsCanSend", source = "settings.onlyAdminsCanSend")
    @Mapping(target = "onlyAdminsCanEdit", source = "settings.onlyAdminsCanEdit")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "adminIds", source = "adminIds")
    GroupInfoResponse toGroupInfo(GroupSettings settings, UUID createdBy, List<UUID> adminIds);

    @Mapping(target = "id", source = "conversation.id")
    @Mapping(target = "type", source = "conversation.type")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "participantIds", source = "participantIds")
    @Mapping(target = "adminIds", source = "adminIds")
    @Mapping(target = "onlyAdminsCanSend", source = "onlyAdminsCanSend")
    @Mapping(target = "members", source = "members")
    InternalConversationResponse toInternal(Conversation conversation, String name, List<UUID> participantIds,
                                            List<UUID> adminIds, boolean onlyAdminsCanSend,
                                            List<MemberPeriodResponse> members);

    MemberPeriodResponse toPeriod(MembershipPeriod period);

    List<MemberPeriodResponse> toPeriods(List<MembershipPeriod> periods);
}
