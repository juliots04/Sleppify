package com.example.sleppify;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Applies a visible queue reorder without discarding the undisplayed tail or duplicate songs. */
final class PlaybackQueueOrder {
    private PlaybackQueueOrder() { }

    static <T> List<T> apply(List<T> queue, int currentIndex, List<T> visibleOrder) {
        if (currentIndex < 0 || currentIndex >= queue.size()) return new ArrayList<>(queue);
        T current = queue.get(currentIndex);
        List<T> remaining = new ArrayList<>();
        IdentityHashMap<T, Integer> available = new IdentityHashMap<>();
        for (int offset = 1; offset < queue.size(); offset++) {
            T item = queue.get((currentIndex + offset) % queue.size());
            remaining.add(item);
            available.put(item, available.getOrDefault(item, 0) + 1);
        }
        List<T> result = new ArrayList<>(queue.size());
        result.add(current);
        for (T item : visibleOrder) {
            int count = available.getOrDefault(item, 0);
            if (item != current && count > 0) {
                result.add(item);
                available.put(item, count - 1);
            }
        }
        for (T item : remaining) {
            int count = available.getOrDefault(item, 0);
            if (count > 0) {
                result.add(item);
                available.put(item, count - 1);
            }
        }
        return result;
    }
}
