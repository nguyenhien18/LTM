package com.ptit.lanexam.server.network;

import com.ptit.lanexam.common.dto.ExamDto;
import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.MessageType;
import com.ptit.lanexam.server.service.AssignmentService;
import com.ptit.lanexam.server.service.AuthService;
import com.ptit.lanexam.server.service.ExamService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

public class ExamServer {
    private final SessionRegistry registry;
    private final AuthService authService;
    private final ExamService examService;
    private final AssignmentService assignmentService;
    private final ExecutorService clientPool = new ThreadPoolExecutor(
            8, 64, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    private final ScheduledExecutorService expiryWorker = Executors.newSingleThreadScheduledExecutor();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile Long publishedExamId;

    public ExamServer(SessionRegistry registry, AuthService authService, ExamService examService) {
        this(registry, authService, examService, new AssignmentService());
    }

    public ExamServer(SessionRegistry registry, AuthService authService, ExamService examService,
                      AssignmentService assignmentService) {
        this.registry = registry;
        this.authService = authService;
        this.examService = examService;
        this.assignmentService = assignmentService;
    }

    public synchronized void start(int port) throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "lan-exam-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        expiryWorker.scheduleWithFixedDelay(() -> {
            try { examService.finalizeExpiredAttempts(); }
            catch (Exception e) { System.err.println("Exam expiry error: " + e.getMessage()); }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setKeepAlive(true);
                socket.setSoTimeout(20000);
                try {
                    clientPool.execute(new ClientHandler(socket, registry, authService, examService, assignmentService,
                            () -> publishedExamId));
                } catch (RejectedExecutionException e) {
                    socket.close();
                }
            } catch (IOException e) {
                if (running) System.err.println("Accept error: " + e.getMessage());
            }
        }
    }

    public int publishExam(ExamDto exam) {
        publishedExamId = exam.getId();
        int count = 0;
        for (ClientSession session : registry.allSessions()) {
            try {
                if (session.getHandler().sendAvailability()) count++;
            } catch (Exception e) {
                System.err.println("Cannot publish to " + session.getStudentCode() + ": " + e.getMessage());
            }
        }
        return count;
    }

    public synchronized void stop() {
        running = false;
        for (ClientSession session : registry.allSessions()) session.getHandler().closeQuietly();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        for (Runnable pending : clientPool.shutdownNow()) {
            if (pending instanceof ClientHandler handler) handler.closeQuietly();
        }
        expiryWorker.shutdownNow();
    }

    public boolean isRunning() { return running; }

    public int getPort() { return serverSocket == null ? -1 : serverSocket.getLocalPort(); }

    public void notifyAssignmentsChanged() {
        if (!running) return;
        for (ClientSession session : registry.allSessions())
            session.getHandler().send(JsonUtil.message(MessageType.ASSIGNMENTS_CHANGED, null));
    }
}
