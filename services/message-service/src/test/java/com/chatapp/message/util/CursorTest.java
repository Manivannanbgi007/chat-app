package com.chatapp.message.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.message.exception.BadRequestException;
import org.junit.jupiter.api.Test;

class CursorTest {

    @Test
    void encodeThenDecodeGivesTheSameCursorWithMicrosecondPrecision() {
        Cursor cursor = new Cursor(Instant.parse("2026-11-04T10:15:30.123456Z"), UUID.randomUUID());

        Cursor decoded = Cursor.decode(cursor.encode());

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void encodedCursorIsUrlSafe() {
        String encoded = new Cursor(Instant.now(), UUID.randomUUID()).encode();
        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void garbageIsRejectedAsABadRequest() {
        for (String bad : new String[]{"", "%%%", "bm90LWEtY3Vyc29y", "MTIzXw"}) {
            assertThatThrownBy(() -> Cursor.decode(bad)).isInstanceOf(BadRequestException.class);
        }
    }
}
