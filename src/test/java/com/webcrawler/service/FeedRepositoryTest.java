package com.webcrawler.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FeedRepositoryTest {

    @Test
    void enclosureContainsAllPresentFields() {
        Map<String, String> e = FeedRepository.enclosure("https://x/f.mp3", "audio/mpeg", 12345);
        assertEquals("https://x/f.mp3", e.get("url"));
        assertEquals("audio/mpeg", e.get("type"));
        assertEquals("12345", e.get("length"));
    }

    @Test
    void enclosureOmitsMissingNonLengthFields() {
        Map<String, String> e = FeedRepository.enclosure(null, null, 0);
        assertNull(e.get("url"));
        assertNull(e.get("type"));
        assertEquals("0", e.get("length"));
    }
}
