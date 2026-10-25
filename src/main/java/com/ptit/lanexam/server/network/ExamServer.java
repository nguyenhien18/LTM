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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ExamServer {
    private final SessionRegistry registry;
    private final AuthService authService;
    private final ExamService examService;
    private final AssignmentService assignmentService;
    private final ExecutorService clientPool = new ThreadPoolExecutor(
            8, 64, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    private final ScheduledExecutorService expiryWorker = Executors.newSingleThreadScheduledExecutor();
    private final Set<ClientHandler> connections = ConcurrentHashMap.newKeySet();
    private final ReadWriteLock attemptGate = new ReentrantReadWriteLock(true);

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
            try {
                examService.finalizeExpiredAttempts();
                for (ClientSession session : registry.allSessions()) session.getHandler().refreshExpiredAttempt();
            }
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
                    ClientHandler handler = new ClientHandler(socket, registry, authService, examService, assignmentService,
                            () -> publishedExamId, attemptGate, () -> running);
                    connections.add(handler);
                    try {
                        clientPool.execute(() -> { try { handler.run(); } finally { connections.remove(handler); } });
                    } catch (RejectedExecutionException ex) { connections.remove(handler); throw ex; }
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
        for (ClientHandler connection : connections) connection.closeQuietly();
        connections.clear();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        clientPool.shutdownNow();
        expiryWorker.shutdownNow();
    }

    public void stopIfIdle() {
        attemptGate.writeLock().lock();
        try {
            if (examService.hasOpenAttempts())
                throw new IllegalStateException("Còn sinh viên đang làm bài, kể cả sinh viên đã mất kết nối. Hãy chờ nộp hoặc hết giờ trước khi đổi giáo viên.");
            stop();
        } finally { attemptGate.writeLock().unlock(); }
    }

    public boolean isRunning() { return running; }

    public void warnViolation(String studentCode, String reason) {
        if (!running) throw new IllegalStateException("Server chưa chạy.");
        if (reason == null || reason.isBlank() || reason.strip().length() > 1000)
            throw new IllegalArgumentException("Nội dung cảnh báo phải có từ 1 đến 1.000 ký tự.");
        ClientSession session = registry.find(studentCode);
        if (session == null || !session.getHandler().warnViolation(reason.strip()))
            throw new IllegalStateException("Sinh viên không còn online.");
    }

    public void lockAccount(String studentCode, String reason) {
        if (!running) throw new IllegalStateException("Server chưa chạy.");
        authService.setAccountLocked(studentCode, true, reason);
        ClientSession session = registry.find(studentCode);
        if (session != null) session.getHandler().disconnectLocked(
                "Tài khoản đã bị khóa. Lý do: " + reason.strip() + ". Liên hệ giảng viên để mở khóa.");
    }

    public int getPort() { return serverSocket == null ? -1 : serverSocket.getLocalPort(); }

    public void notifyAssignmentsChanged() {
        if (!running) return;
        for (ClientSession session : registry.allSessions())
            session.getHandler().send(JsonUtil.message(MessageType.ASSIGNMENTS_CHANGED, null));
    }
}
