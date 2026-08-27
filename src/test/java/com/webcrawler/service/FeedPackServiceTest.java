package com.webcrawler.service;

import com.webcrawler.model.FeedPack;
import com.webcrawler.model.FeedPackMember;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedPackServiceTest {

    @Test
    void yamlLoaderReturnsEmptyWhenMissing() {
        assertTrue(FeedPackService.readClasspathYaml("does-not-exist.yaml").isEmpty());
    }

    @Test
    void yamlLoaderParsesPacksAndMembers() {
        List<FeedPack> parsed = FeedPackService.readClasspathYaml("test-feed-packs.yaml");
        assertEquals(2, parsed.size());

        FeedPack alpha = parsed.get(0);
        assertEquals("alpha", alpha.id());
        assertEquals("Alpha", alpha.name());
        assertEquals("Alpha pack", alpha.description());
        assertEquals(2, alpha.feeds().size());
        FeedPackMember a = alpha.feeds().get(0);
        assertEquals("https://a.example/rss", a.url());
        assertEquals("A", a.title());
        assertEquals(300, a.defaultPollIntervalSeconds());
        FeedPackMember b = alpha.feeds().get(1);
        assertEquals(60, b.defaultPollIntervalSeconds()); // per-feed override
    }

    @Test
    void yamlLoaderDefaultsMemberTitleToUrlWhenMissing() {
        List<FeedPack> parsed = FeedPackService.readClasspathYaml("test-feed-packs.yaml");
        FeedPack beta = parsed.get(1);
        assertEquals("beta", beta.id());
        assertNull(beta.description());
        assertEquals("https://c.example/rss", beta.feeds().get(0).title());
        // Default pack interval when no default_poll_interval_seconds is set: 900s.
        assertEquals(900, beta.feeds().get(0).defaultPollIntervalSeconds());
    }
}
