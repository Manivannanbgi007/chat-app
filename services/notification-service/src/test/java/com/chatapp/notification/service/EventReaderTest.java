package com.chatapp.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.chatapp.notification.kafka.EventReader;
import com.chatapp.notification.kafka.InvalidEventException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EventReaderTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID id = UUID.randomUUID();

    private JsonNode json(String text) {
        return EventReader.parse(mapper, text);
    }

    @Test
    void parseRejectsEmptyBrokenAndNonObjectEvents() {
        assertThatThrownBy(() -> json(null)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> json("   ")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> json("{not json")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> json("[1,2]")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> json("\"text\"")).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void textReadsStringsAndTreatsMissingAndNullAsNull() {
        JsonNode node = json("{\"a\":\"x\",\"b\":null}");
        assertThat(EventReader.text(node, "a")).isEqualTo("x");
        assertThat(EventReader.text(node, "b")).isNull();
        assertThat(EventReader.text(node, "c")).isNull();
        assertThat(EventReader.text(null, "a")).isNull();
    }

    @Test
    void requireUuidAcceptsAUuidAndRejectsEverythingElse() {
        assertThat(EventReader.requireUuid(json("{\"id\":\"" + id + "\"}"), "id")).isEqualTo(id);
        assertThatThrownBy(() -> EventReader.requireUuid(json("{}"), "id")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> EventReader.requireUuid(json("{\"id\":\"\"}"), "id")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> EventReader.requireUuid(json("{\"id\":null}"), "id")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> EventReader.requireUuid(json("{\"id\":\"nope\"}"), "id")).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void optionalUuidMayBeMissing() {
        assertThat(EventReader.optionalUuid(json("{}"), "id")).isNull();
        assertThat(EventReader.optionalUuid(json("{\"id\":null}"), "id")).isNull();
        assertThat(EventReader.optionalUuid(json("{\"id\":\"" + id + "\"}"), "id")).isEqualTo(id);
        assertThatThrownBy(() -> EventReader.optionalUuid(json("{\"id\":\"nope\"}"), "id")).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void requireObjectNeedsAnObject() {
        assertThat(EventReader.requireObject(json("{\"p\":{\"a\":1}}"), "p").isObject()).isTrue();
        assertThatThrownBy(() -> EventReader.requireObject(json("{}"), "p")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> EventReader.requireObject(json("{\"p\":\"x\"}"), "p")).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void uuidListReadsAnArrayOfUuids() {
        UUID second = UUID.randomUUID();
        assertThat(EventReader.uuidList(json("{\"l\":[\"" + id + "\",\"" + second + "\"]}"), "l")).containsExactly(id, second);
        assertThat(EventReader.uuidList(json("{\"l\":[]}"), "l")).isEmpty();
        assertThatThrownBy(() -> EventReader.uuidList(json("{}"), "l")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> EventReader.uuidList(json("{\"l\":\"x\"}"), "l")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> EventReader.uuidList(json("{\"l\":[\"x\"]}"), "l")).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void userIdsOfMembersReadsTheUserIdOfEachObject() {
        List<UUID> ids = EventReader.userIdsOfMembers(json("{\"m\":[{\"userId\":\"" + id + "\",\"role\":\"MEMBER\"}]}"), "m");
        assertThat(ids).containsExactly(id);
        assertThatThrownBy(() -> EventReader.userIdsOfMembers(json("{\"m\":[{\"role\":\"MEMBER\"}]}"), "m"))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> EventReader.userIdsOfMembers(json("{}"), "m")).isInstanceOf(InvalidEventException.class);
    }
}
