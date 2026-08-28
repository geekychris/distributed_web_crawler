package com.webcrawler.service;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecordsServiceTest {

    @Test
    void kafkaCursorParsedFromPartitionOffsetForm() {
        assertEquals(42L, RecordsService.parseKafkaCursor("kafka:0:42"));
    }

    @Test
    void kafkaCursorParsedFromShortForm() {
        assertEquals(100L, RecordsService.parseKafkaCursor("kafka:100"));
    }

    @Test
    void kafkaCursorMinusOneOnMissingOrBadInput() {
        assertEquals(-1L, RecordsService.parseKafkaCursor(null));
        assertEquals(-1L, RecordsService.parseKafkaCursor(""));
        assertEquals(-1L, RecordsService.parseKafkaCursor("cassandra:0:42"));
        assertEquals(-1L, RecordsService.parseKafkaCursor("kafka:not-a-number"));
        assertEquals(-1L, RecordsService.parseKafkaCursor("something"));
    }

    @Test
    void cassandraCursorNullForEmpty() {
        assertNull(RecordsService.parseCassandraCursor(null));
        assertNull(RecordsService.parseCassandraCursor(""));
    }

    @Test
    void cassandraCursorNullForWrongPrefix() {
        assertNull(RecordsService.parseCassandraCursor("kafka:0:0"));
    }

    @Test
    void cassandraCursorDecodesBase64() {
        byte[] state = {1, 2, 3, 4, 5};
        String cursor = "cassandra:" + Base64.getEncoder().encodeToString(state);
        ByteBuffer bb = RecordsService.parseCassandraCursor(cursor);
        byte[] decoded = new byte[bb.remaining()];
        bb.get(decoded);
        assertEquals(5, decoded.length);
        assertEquals(1, decoded[0]);
        assertEquals(5, decoded[4]);
    }

    @Test
    void cassandraCursorNullOnBadBase64() {
        assertNull(RecordsService.parseCassandraCursor("cassandra:!!!not-base64!!!"));
    }
}
