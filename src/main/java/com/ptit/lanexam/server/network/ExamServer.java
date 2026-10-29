package com.ptit.lanexam.server.network;

import com.ptit.lanexam.common.dto.ExamDto;
import com.ptit.lanexam.server.service.AuthService;
import com.ptit.lanexam.server.service.ExamService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ExamServer {
    private final SessionRegistry registry;
    private final AuthService authService;
    private final ExamService examService;
    // A handler lives as long as its socket. Start each one immediately instead of
    // queueing it behind connections that may remain open for the whole exam.
    private final ExecutorService clientPool = Executors.newVirtualThreadPerTaskExecutor();
    // Reserve capacity in Hibernate's default 20-connection pool for expiry and
    // teacher operations. Only requests wait for a slot, never idle connections.
    private final Semaphore requestSlots = new Semaphore(16, true);
    private final ScheduledExecutorService expiryWorker = Executors.newSingleThreadScheduledExecutor();
    private final Set<ClientHandler> connections = ConcurrentHashMap.newKeySet();
    private final ReadWriteLock attemptGate = new ReentrantReadWriteLock(true);

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile Long publishedExamId;
    private volatile long publishedDeadline;

    public ExamServer(SessionRegistry registry, AuthService authService, ExamService examService) {
        this.registry = registry;
        this.authService = authService;
        this.examService = examService;
    }

    public synchronized void start(int port) throws IOException {
        if (running) return;
        // Restore before accepting logins; fail startup if the database cannot be read.
        var recovered = examService.recoverPublishedExam();
        serverSocket = new ServerSocket(port);
        publishedExamId = recovered.map(ExamDto::getId).orElse(null);
        publishedDeadline = recovered.map(ExamDto::getDeadlineEpochMillis).orElse(0L);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "lan-exam-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        expiryWorker.scheduleWithFixedDelay(() -> {
            try {
                examService.finalizeExpiredAttempts();
                for (ClientSession session : registry.allSessions()) session.getHandler().refreshExpiredAttempt();
                clearExpiredOffer();
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
                    ClientHandler handler = new ClientHandler(socket, registry, authService, examService,
                            () -> publishedExamId, attemptGate, () -> running, requestSlots);
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
        attemptGate.writeLock().lock();
        try {
            if (!running) throw new IllegalStateException("Server đã dừng.");
            publishedExamId = exam.getId();
            publishedDeadline = exam.getDeadlineEpochMillis();
        } finally { attemptGate.writeLock().unlock(); }
        return notifyExamAvailability();
    }

    public record Publication(ExamDto exam, int notifiedStudents) {}

    public Publication openExam(Long templateId) {
        return openExam(templateId, null);
    }

    public Publication openExam(Long templateId, Long classId) {
        return openExam(templateId, classId, null);
    }

    public Publication openExam(Long templateId, Long classId, Long shiftId) {
        return openExam(templateId, classId, shiftId, 1);
    }
    public Publication openExam(Long templateId, Long classId, Long shiftId, int variants) {
        if (variants < 1 || variants > 8) throw new IllegalArgumentException("Số mã đề từ 1 đến 8.");
        ExamDto exam;
        attemptGate.writeLock().lock();
        try {
            if (!running) throw new IllegalStateException("Server đã dừng. Không thể mở đề.");
            exam = variants > 1 ? examService.activateExam(templateId, classId, shiftId, variants)
                    : shiftId != null ? examService.activateExam(templateId, classId, shiftId)
                    : classId == null ? examService.activateExam(templateId) : examService.activateExam(templateId, classId);
            publishedExamId = exam.getId();
            publishedDeadline = exam.getDeadlineEpochMillis();
        } finally { attemptGate.writeLock().unlock(); }
        return new Publication(exam, notifyExamAvailability());
    }

    private void clearExpiredOffer() {
        boolean expired = false;
        attemptGate.writeLock().lock();
        try {
            if (publishedExamId != null && publishedDeadline > 0
                    && examService.currentTime().toEpochMilli() >= publishedDeadline) {
                publishedExamId = null;
                expired = true;
            }
        } finally { attemptGate.writeLock().unlock(); }
        if (expired) notifyExamAvailability();
    }

    private int notifyExamAvailability() {
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

    public void refreshEligibility() {
        notifyExamAvailability();
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
            examService.ensureCanStop();
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

}
