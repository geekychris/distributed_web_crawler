package com.webcrawler.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Small in-memory ring buffer of the last N crawl outcomes — success,
 * rejection, error. Purely observability; not durable. The UI polls it so a
 * user can see something happen when they submit a URL, instead of the
 * silent-drop UX we had before.
 */
@Service
public class ActivityFeed {
    public enum Kind { CRAWLED, REJECTED, ERROR }

    public record Event(Instant at, Kind kind, String url, String detail) {}

    private static final int CAPACITY = 200;
    private final Deque<Event> events = new ArrayDeque<>(CAPACITY);

    public synchronized void crawled(String url, int status, int linksFound, int linksFollowed) {
        push(new Event(Instant.now(), Kind.CRAWLED, url,
                "http " + status + " · " + linksFound + " found → " + linksFollowed + " to crawl"));
    }

    public synchronized void rejected(String url, String reason) {
        push(new Event(Instant.now(), Kind.REJECTED, url, reason));
    }

    public synchronized void error(String url, String message) {
        push(new Event(Instant.now(), Kind.ERROR, url, message));
    }

    public synchronized List<Event> recent(int limit) {
        List<Event> out = new ArrayList<>(Math.min(limit, events.size()));
        int i = 0;
        for (Event e : events) {
            if (i++ >= limit) break;
            out.add(e);
        }
        return out;
    }

    private void push(Event event) {
        events.addFirst(event);
        while (events.size() > CAPACITY) events.removeLast();
    }
}
