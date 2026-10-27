package com.ptit.lanexam.server.network;

import com.ptit.lanexam.common.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientWriterTest {
    @Test void blockedReceiverDoesNotDelayOtherClientsAndIsDisconnectedOnTimeout() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1), disconnected = new CountDownLatch(1);
        OutputStream blocked = new OutputStream() {
            @Override public void write(int value) throws IOException {
                writing.countDown();
                try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
            }
        };
        ByteArrayOutputStream healthy = new ByteArrayOutputStream();
        try (ClientWriter slow = new ClientWriter(blocked, () -> { release.countDown(); disconnected.countDown(); }, Duration.ofMillis(500));
             ClientWriter fast = new ClientWriter(healthy, () -> {}, Duration.ofSeconds(2))) {
            assertTrue(slow.send(JsonUtil.message(MessageType.PONG, "slow"), false));
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            assertTrue(fast.send(JsonUtil.message(MessageType.PONG, "healthy"), true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (healthy.size() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(healthy.toString(java.nio.charset.StandardCharsets.UTF_8).contains("healthy"));
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
            assertFalse(slow.send(JsonUtil.message(MessageType.PONG, "late"), false));
        } finally { release.countDown(); }
    }

    @Test void finalLockMessageIsFlushedAfterEarlierMessagesBeforeDisconnect() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CountDownLatch disconnected = new CountDownLatch(1);
        try (ClientWriter writer = new ClientWriter(output, disconnected::countDown)) {
            writer.send(JsonUtil.message(MessageType.PONG, "first"), false);
            writer.send(JsonUtil.message(MessageType.ACCOUNT_LOCKED, "locked"), true);
            assertFalse(writer.send(JsonUtil.message(MessageType.PONG, "must-not-send"), false));
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
            String[] lines = output.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toArray(String[]::new);
            assertEquals(2, lines.length);
            assertEquals(MessageType.PONG, JsonUtil.fromJson(lines[0]).getType());
            assertEquals(MessageType.ACCOUNT_LOCKED, JsonUtil.fromJson(lines[1]).getType());
        }
    }
}
