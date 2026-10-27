package com.ptit.lanexam.server.network;

import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.NetworkMessage;
import com.ptit.lanexam.common.TransferLimits;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** An ordered, bounded outbox whose blocked writes cannot stall other clients. */
final class ClientWriter implements AutoCloseable {
    private record Frame(String json, boolean closeAfter) {}
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("lan-exam-write-timeouts").factory());
    private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(64);
    private final AtomicLong queuedChars = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BufferedWriter output;
    private final Runnable disconnect;
    private final long timeoutMillis;
    private final Thread worker;
    private boolean finishing;

    ClientWriter(OutputStream output, Runnable disconnect) {
        this(output, disconnect, Duration.ofSeconds(10));
    }

    ClientWriter(OutputStream output, Runnable disconnect, Duration timeout) {
        this.output = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.disconnect = disconnect;
        timeoutMillis = timeout.toMillis();
        worker = Thread.ofVirtual().name("lan-exam-client-writer").start(this::writeLoop);
    }

    synchronized boolean send(NetworkMessage message, boolean closeAfter) {
        if (closed.get() || finishing) return false;
        String json = JsonUtil.toJson(message);
        if (queuedChars.addAndGet(json.length()) > 2L * TransferLimits.MAX_MESSAGE_CHARS
                || !queue.offer(new Frame(json, closeAfter))) {
            fail();
            return false;
        }
        finishing = closeAfter;
        return true;
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                Frame frame = queue.take();
                queuedChars.addAndGet(-frame.json().length());
                AtomicBoolean writing = new AtomicBoolean(true);
                ScheduledFuture<?> timeout = TIMEOUTS.schedule(() -> {
                    if (writing.compareAndSet(true, false)) fail();
                }, timeoutMillis, TimeUnit.MILLISECONDS);
                try {
                    output.write(frame.json());
                    output.newLine();
                    output.flush();
                } finally {
                    writing.set(false);
                    timeout.cancel(false);
                }
                if (frame.closeAfter()) { fail(); return; }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            fail();
        }
    }

    private void fail() {
        close();
        disconnect.run(); // Closing the socket unblocks a native write in progress.
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            queue.clear();
            worker.interrupt();
        }
    }
}
