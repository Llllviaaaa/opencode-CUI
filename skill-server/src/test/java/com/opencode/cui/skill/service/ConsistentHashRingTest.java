package com.opencode.cui.skill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for ConsistentHashRing — verifies routing correctness, stability, balance, and thread safety. */
class ConsistentHashRingTest {

    @Test
    @DisplayName("addNode_shouldMakeNodeRetrievable - node is reachable after being added")
    void addNode_shouldMakeNodeRetrievable() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150);
        ring.addNode("node-1", "session-A");

        String result = ring.getNode("any-key");

        assertNotNull(result);
        assertEquals("session-A", result);
    }

    @Test
    @DisplayName("removeNode_shouldOnlyAffectRemovedNodeKeys - most keys keep their mapping after node removal")
    void removeNode_shouldOnlyAffectRemovedNodeKeys() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150);
        ring.addNode("node-1", "session-A");
        ring.addNode("node-2", "session-B");
        ring.addNode("node-3", "session-C");

        // Record baseline mappings for 1000 keys
        int total = 1000;
        Map<String, String> baseline = new HashMap<>();
        for (int i = 0; i < total; i++) {
            String key = "key-" + i;
            baseline.put(key, ring.getNode(key));
        }

        // Count how many originally mapped to node-3
        long mappedToC = baseline.values().stream().filter("session-C"::equals).count();

        // Remove node-3
        ring.removeNode("node-3");
        assertEquals(2, ring.size());

        // Keys that were NOT on node-3 should keep their mapping
        int unchanged = 0;
        int remapped = 0;
        for (int i = 0; i < total; i++) {
            String key = "key-" + i;
            String before = baseline.get(key);
            String after = ring.getNode(key);
            if ("session-C".equals(before)) {
                // These must now map to one of the remaining two nodes
                assertTrue("session-A".equals(after) || "session-B".equals(after),
                        "Remapped key should go to a surviving node");
                remapped++;
            } else {
                if (before.equals(after)) unchanged++;
            }
        }

        // Roughly mappedToC keys should have been remapped
        assertEquals(mappedToC, remapped,
                "Exactly the keys that were on node-3 should have been remapped");
        // All others must remain stable
        assertEquals(total - mappedToC, unchanged,
                "Keys not on node-3 must stay on the same node");
    }

    @Test
    @DisplayName("getNode_withEmptyRing_shouldReturnNull - returns null when no nodes exist")
    void getNode_withEmptyRing_shouldReturnNull() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150);

        assertNull(ring.getNode("any-key"));
    }

    @Test
    @DisplayName("getNode_withSingleNode_shouldAlwaysReturnIt - single node captures all traffic")
    void getNode_withSingleNode_shouldAlwaysReturnIt() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150);
        ring.addNode("only-node", "session-X");

        for (int i = 0; i < 200; i++) {
            assertEquals("session-X", ring.getNode("key-" + i));
        }
    }

    @Test
    @DisplayName("distribution_shouldBeReasonablyBalanced - each of 3 nodes handles at least 20% of 1000 keys")
    void distribution_shouldBeReasonablyBalanced() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150);
        ring.addNode("node-1", "session-A");
        ring.addNode("node-2", "session-B");
        ring.addNode("node-3", "session-C");

        Map<String, Integer> counts = new HashMap<>();
        counts.put("session-A", 0);
        counts.put("session-B", 0);
        counts.put("session-C", 0);

        Random rng = new Random(42L);
        int total = 1000;
        for (int i = 0; i < total; i++) {
            String key = "user-" + rng.nextLong();
            String node = ring.getNode(key);
            counts.merge(node, 1, Integer::sum);
        }

        int minExpected = (int) (total * 0.20); // at least 20%
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            assertTrue(entry.getValue() >= minExpected,
                    "Node " + entry.getKey() + " got only " + entry.getValue() + " keys (expected >= " + minExpected + ")");
        }
    }

    @Test
    @DisplayName("concurrentAccess_shouldBeThreadSafe - no exceptions under concurrent add/remove/get")
    void concurrentAccess_shouldBeThreadSafe() throws InterruptedException {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150);
        ring.addNode("node-init", "session-init");

        int threadCount = 20;
        int opsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> error = new AtomicReference<>();

        List<Runnable> tasks = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            tasks.add(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        String nodeKey = "node-" + (threadId % 5);
                        String nodeVal = "session-" + (threadId % 5);
                        ring.addNode(nodeKey, nodeVal);
                        ring.getNode("key-" + i);
                        if (i % 10 == 0) {
                            ring.removeNode(nodeKey);
                        }
                    }
                } catch (Throwable ex) {
                    error.compareAndSet(null, ex);
                } finally {
                    latch.countDown();
                }
            });
        }

        tasks.forEach(executor::submit);
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Threads did not finish in time");
        executor.shutdown();

        assertDoesNotThrow(() -> {
            if (error.get() != null) throw new RuntimeException(error.get());
        }, "Concurrent access must not throw any exception");
    }
}
