package com.webcrawler.storage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HybridStorageServiceTest {

    @Test
    void lookupHeaderIgnoreCaseMatchesExact() {
        Map<String, String> h = Map.of("Content-Type", "text/html");
        assertEquals("text/html",
                HybridStorageService.lookupHeaderIgnoreCase(h, "Content-Type", "def"));
    }

    @Test
    void lookupHeaderIgnoreCaseMatchesLowercase() {
        Map<String, String> h = Map.of("content-type", "text/plain");
        assertEquals("text/plain",
                HybridStorageService.lookupHeaderIgnoreCase(h, "Content-Type", "def"));
    }

    @Test
    void lookupHeaderIgnoreCaseMatchesMixed() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("CoNtEnT-tYpE", "application/xml");
        assertEquals("application/xml",
                HybridStorageService.lookupHeaderIgnoreCase(h, "Content-Type", "def"));
    }

    @Test
    void lookupHeaderIgnoreCaseFallsBackToDefault() {
        assertEquals("default",
                HybridStorageService.lookupHeaderIgnoreCase(Map.of(), "Content-Type", "default"));
        assertEquals("default",
                HybridStorageService.lookupHeaderIgnoreCase(null, "Content-Type", "default"));
    }

    @Test
    void nullDefaultReturnedForMissingHeader() {
        assertNull(HybridStorageService.lookupHeaderIgnoreCase(
                Map.of("X-Other", "value"), "Content-Type", null));
    }
}
