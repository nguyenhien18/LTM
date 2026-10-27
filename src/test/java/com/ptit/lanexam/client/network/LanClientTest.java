package com.ptit.lanexam.client.network;

import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.MessageType;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class LanClientTest {
    @Test
    void closingClientAbortsABlockedWriteWithoutWaitingForTheSender() throws Exception {
        LanClient client = new LanClient();
        CountDownLatch writing = new CountDownLatch(1), transportClosed = new CountDownLatch(1);
        OutputStream blocked = new OutputStream() {
            @Override public void write(int value) throws IOException {
                writing.countDown();
                try {
                    if (!transportClosed.await(5, TimeUnit.SECONDS)) throw new IOException("Test cleanup timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ex);
                }
                throw new IOException("Socket closed");
            }
        };
        Socket socket = new Socket() {
            @Override public void close() { transportClosed.countDown(); }
        };
        set(client, "socket", socket);
        set(client, "writer", new BufferedWriter(new OutputStreamWriter(blocked)));
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> sending = workers.submit(() -> client.send(JsonUtil.message(MessageType.PING, null)));
            try {
                assertTrue(writing.await(2, TimeUnit.SECONDS));
                Future<?> closing = workers.submit(client::close);
                closing.get(1, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> sending.get(1, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertThrows(IllegalStateException.class, () -> client.send(JsonUtil.message(MessageType.PING, null)));
                client.close(); // Closing twice remains safe.
            } finally { transportClosed.countDown(); }
        } finally { client.close(); }
    }

    private static void set(LanClient client, String name, Object value) throws Exception {
        var field = LanClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }
}
