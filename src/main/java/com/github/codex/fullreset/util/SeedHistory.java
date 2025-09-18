package com.github.codex.fullreset.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Simple fixed-size seed history for storing recent seeds used/generated per base world.
 */
public class SeedHistory {
    private final int capacity;
    private final Deque<Long> history = new ArrayDeque<>();

    public SeedHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(long seed) {
        // avoid duplicates consecutive
        if (!history.isEmpty() && history.peekFirst() == seed) return;
        history.addFirst(seed);
        while (history.size() > capacity) history.removeLast();
    }

    public synchronized List<Long> list() {
        return history.stream().collect(Collectors.toList());
    }

    public synchronized Long latest() { return history.peekFirst(); }
}
