package com.webcrawler.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedPollerTest {

    @Test
    void sha256IsStableAndPrefixed() {
        String a = FeedPoller.sha256("https://x.example/1|2026-08-27T00:00:00Z");
        String b = FeedPoller.sha256("https://x.example/1|2026-08-27T00:00:00Z");
        String c = FeedPoller.sha256("https://x.example/2|2026-08-27T00:00:00Z");
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertTrue(a.startsWith("sha256:"));
        assertEquals(7 + 64, a.length());
    }

    @Test
    void sha256HandlesEmpty() {
        String hash = FeedPoller.sha256("");
        assertTrue(hash.startsWith("sha256:"));
        assertEquals(7 + 64, hash.length());
    }

    @Test
    void stripControlCharsPreservesWhitespaceAndText() {
        assertEquals("hello\tworld\nlinefeed\rok",
                FeedPoller.stripControlChars("hello\tworld\nlinefeed\rok"));
    }

    @Test
    void stripControlCharsRemovesNonWhitespaceControls() {
        // \u0000 (null), \u0001, \u000b (vtab), \u000c (form feed), \u001f, \u007f (DEL)
        assertEquals("bookvolume", FeedPoller.stripControlChars("book\u000cvolume"));
        assertEquals("aB",         FeedPoller.stripControlChars("a\u000bB"));
        assertEquals("clean",      FeedPoller.stripControlChars("cle\u0000an"));
        assertEquals("delete",     FeedPoller.stripControlChars("del\u007fete"));
    }

    @Test
    void stripControlCharsPassesNullThrough() {
        assertNull(FeedPoller.stripControlChars(null));
    }
}
