package com.ptit.lanexam.client.network;

import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.NetworkMessage;
import com.ptit.lanexam.common.MessageType;
import com.ptit.lanexam.common.TransferLimits;

import java.io.*;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class LanClient {
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private Thread readThread;
    private volatile boolean closed;

    private Consumer<NetworkMessage> messageListener = ignored -> {};
    private Consumer<String> disconnectListener = ignored -> {};

    public void setMessageListener(Consumer<NetworkMessage> listener) {
        this.messageListener = listener == null ? ignored -> {} : listener;
    }

    public void setDisconnectListener(Consumer<String> listener) {
        this.disconnectListener = listener == null ? ignored -> {} : listener;
    }

    public synchronized void connect(String host, int port) throws IOException {
        close();
        Socket connected = new Socket();
        try {
            connected.connect(new InetSocketAddress(host, port), 5000);
        } catch (IOException e) {
            connected.close();
            throw e;
        }
        connected.setSoTimeout(20000);
        socket = connected;
        socket.setKeepAlive(true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        closed = false;

        BufferedReader connectedReader = reader;
        readThread = new Thread(() -> readLoop(connected, connectedReader), "lan-client-reader");
        readThread.setDaemon(true);
        readThread.start();
    }

    private void readLoop(Socket connected, BufferedReader connectedReader) {
        try {
            String line;
            while (isCurrent(connected) && (line = TransferLimits.readLine(connectedReader)) != null) {
                NetworkMessage message = JsonUtil.fromJson(line);
                messageListener.accept(message);
                if (message.getType() == MessageType.ACCOUNT_LOCKED) {
                    closeIfCurrent(connected);
                    return; // The explicit lock notification replaces a generic disconnect popup.
                }
            }
            if (isCurrent(connected)) disconnectListener.accept("Server đã đóng kết nối");
        } catch (Exception e) {
            if (isCurrent(connected)) disconnectListener.accept(e.getMessage());
        } finally {
            closeIfCurrent(connected);
        }
    }

    private synchronized boolean isCurrent(Socket connected) {
        return !closed && socket == connected;
    }

    public void send(NetworkMessage message) {
        BufferedWriter connectedWriter;
        Socket connected;
        synchronized (this) {
            if (closed || writer == null) throw new IllegalStateException("Chưa kết nối Server");
            connectedWriter = writer;
            connected = socket;
        }
        // Serialize complete frames per connection, without holding the state
        // monitor: close() must be able to close the socket and abort a stalled flush.
        synchronized (connectedWriter) {
            synchronized (this) {
                if (closed || socket != connected || writer != connectedWriter)
                    throw new IllegalStateException("Chưa kết nối Server");
            }
            try {
                connectedWriter.write(JsonUtil.toJson(message));
                connectedWriter.newLine();
                connectedWriter.flush();
            } catch (IOException e) {
                throw new IllegalStateException("Không gửi được dữ liệu đến Server", e);
            }
        }
    }

    private synchronized void closeIfCurrent(Socket connected) {
        if (socket == connected) close();
    }

    public synchronized void close() {
        closed = true;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
        reader = null;
        writer = null;
    }
}
