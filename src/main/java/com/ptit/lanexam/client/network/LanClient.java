package com.ptit.lanexam.client.network;

import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.NetworkMessage;
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
                messageListener.accept(JsonUtil.fromJson(line));
            }
            if (isCurrent(connected)) disconnectListener.accept("Server đã đóng kết nối");
        } catch (Exception e) {
            if (isCurrent(connected)) disconnectListener.accept(e.getMessage());
        } finally {
            if (isCurrent(connected)) close();
        }
    }

    private synchronized boolean isCurrent(Socket connected) {
        return !closed && socket == connected;
    }

    public synchronized void send(NetworkMessage message) {
        if (closed || writer == null) throw new IllegalStateException("Chưa kết nối Server");
        try {
            writer.write(JsonUtil.toJson(message));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Không gửi được dữ liệu đến Server", e);
        }
    }

    public synchronized void close() {
        closed = true;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
        reader = null;
        writer = null;
    }
}
