package com.ptit.lanexam.server.network;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class SessionRegistry {
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private Consumer<List<ClientSessionInfo>> listener = ignored -> {};

    public void setListener(Consumer<List<ClientSessionInfo>> listener) {
        this.listener = listener == null ? ignored -> {} : listener;
    }

    public void register(ClientSession session) {
        ClientSession previous = sessions.put(session.getStudentCode(), session);
        if (previous != null && previous != session) previous.getHandler().closeQuietly();
        notifyListener();
    }

    public void remove(String studentCode, ClientHandler handler) {
        sessions.computeIfPresent(studentCode, (key, session) -> session.getHandler() == handler ? null : session);
        notifyListener();
    }

    public void updateStatus(String studentCode, ClientStatus status) {
        ClientSession session = sessions.get(studentCode);
        if (session != null) {
            session.setStatus(status);
            notifyListener();
        }
    }

    public boolean isCurrent(String studentCode, ClientHandler handler) {
        ClientSession session = sessions.get(studentCode);
        return session != null && session.getHandler() == handler;
    }

    public List<ClientSession> allSessions() {
        return new ArrayList<>(sessions.values());
    }

    private void notifyListener() {
        List<ClientSessionInfo> snapshot = sessions.values().stream()
                .map(ClientSession::info)
                .sorted((a, b) -> a.studentCode().compareToIgnoreCase(b.studentCode()))
                .toList();
        listener.accept(snapshot);
    }
}
