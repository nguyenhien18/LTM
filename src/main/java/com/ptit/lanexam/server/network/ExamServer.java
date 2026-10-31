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
import java.util.function.Consumer;

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
    private final Consumer<Throwable> failureListener;

    public ExamServer(SessionRegistry registry, AuthService authService, ExamService examService) {
        this(registry, authService, examService, failure -> {});
    }

    public ExamServer(SessionRegistry registry, AuthService authService, ExamService examService,
                      Consumer<Throwable> failureListener) {
        this.registry = registry;
        this.authService = authService;
        this.examService = examService;
        this.failureListener = failureListener;
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
        Throwable failure = null;
        try {
            while (running) {
                Socket socket = serverSocket.accept();
                try {
                    socket.setKeepAlive(true);
                    socket.setSoTimeout(20000);
                    dispatchClient(socket);
                } catch (IOException | RejectedExecutionException e) {
                    try { socket.close(); } catch (IOException ignored) {}
                    if (running) e.printStackTrace();
                } catch (RuntimeException | Error e) {
                    try { socket.close(); } catch (IOException ignored) {}
                    throw e;
                }
            }
        } catch (IOException | RuntimeException | Error e) {
            if (running) { failure = e; e.printStackTrace(); }
        } finally {
            // A bound port without an accept loop looks connected to Clients but
            // can never answer LOGIN. Release it and report the failed Server.
            if (running) {
                stop();
                if (failure != null) failureListener.accept(failure);
            }
        }
    }

    void dispatchClient(Socket socket) {
        ClientHandler handler = new ClientHandler(socket, registry, authService, examService,
                () -> publishedExamId, attemptGate, () -> running, requestSlots);
        connections.add(handler);
        try {
            clientPool.execute(() -> { try { handler.run(); } finally { connections.remove(handler); } });
        } catch (RuntimeException | Error e) { connections.remove(handler); throw e; }
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
        return openExam(templateId, classId, shiftId, variants, null);
    }
    public Publication openExam(Long templateId, Long classId, Long shiftId, int variants,
                                java.util.List<com.ptit.lanexam.server.service.ClassService.RosterStudent> preparedRoster) {
        if (variants < 1 || variants > 8) throw new IllegalArgumentException("Số mã đề từ 1 đến 8.");
        ExamDto exam;
        attemptGate.writeLock().lock();
        try {
            if (!running) throw new IllegalStateException("Server đã dừng. Không thể mở đề.");
            exam = preparedRoster != null ? examService.activateExam(templateId, classId, shiftId, variants, preparedRoster)
                    : variants > 1 ? examService.activateExam(templateId, classId, shiftId, variants)
                    : shiftId != null ? examService.activateExam(templateId, classId, shiftId)
                    : classId == null ? examService.activateExam(templateId) : examService.activateExam(templateId, classId);
            publishedExamId = exam.getId();
            publishedDeadline = exam.getDeadlineEpochMillis();
        } finally { attemptGate.writeLock().unlock(); }
        return new Publication(exam, notifyExamAvailability());
    }

    public void endExam(Long roundId) {
        attemptGate.writeLock().lock();
        try {
            if (!running) throw new IllegalStateException("Server đã dừng.");
            examService.endExam(roundId);
            if (java.util.Objects.equals(publishedExamId, roundId)) {
                publishedExamId = null;
                publishedDeadline = 0;
            }
        } finally { attemptGate.writeLock().unlock(); }
        // Timely frames already queued are drained before the expiry worker grades.
        examService.finalizeExpiredAttempts();
        for (ClientSession session : registry.allSessions()) session.getHandler().refreshExpiredAttempt();
        notifyExamAvailability();
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
        if (session.getExamId() != null) examService.events().reminder(session.getExamId(), studentCode, reason);
    }

    public void remindExam(Long roundId, String studentCode, String reason) {
        ClientSession session = registry.find(studentCode);
        if (!running || session == null || !java.util.Objects.equals(roundId, session.getExamId())
                || session.getStatus() != ClientStatus.DOING_EXAM)
            throw new IllegalStateException("Sinh viên không đang làm ca này hoặc đã offline.");
        if (reason == null || reason.isBlank() || reason.strip().length() > 1000)
            throw new IllegalArgumentException("Nhắc nhở từ 1–1.000 ký tự.");
        // Validate scope and the active attempt before sending; resolve only after the send is accepted.
        if (!examService.canDisqualify(roundId, studentCode)) throw new IllegalStateException("Bài thi đã kết thúc.");
        if (!session.getHandler().warnViolation(reason.strip())) throw new IllegalStateException("Sinh viên vừa mất kết nối.");
        examService.events().reminder(roundId, studentCode, reason);
    }

    public boolean isDoingExam(Long roundId, String code) {
        ClientSession session = registry.find(code);
        return running && session != null && session.getStatus() == ClientStatus.DOING_EXAM
                && java.util.Objects.equals(roundId, session.getExamId());
    }
    public boolean canDisqualify(Long roundId, String code) { return running && examService.canDisqualify(roundId, code); }

    public void disqualify(Long roundId, String code, String reason) {
        boolean changed;
        attemptGate.writeLock().lock();
        try {
            if (!running) throw new IllegalStateException("Server chưa chạy.");
            changed = examService.disqualify(roundId, code, reason);
        } finally { attemptGate.writeLock().unlock(); }
        if (!changed) return;
        ClientSession session = registry.find(code);
        if (session != null && java.util.Objects.equals(roundId, session.getExamId()))
            session.getHandler().notifyDisqualification(roundId, reason.strip());
        examService.finalizeExpiredAttempts();
        if (session != null && java.util.Objects.equals(roundId, session.getExamId())) {
            session.getHandler().refreshExpiredAttempt(); session.getHandler().sendAvailability();
        }
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
