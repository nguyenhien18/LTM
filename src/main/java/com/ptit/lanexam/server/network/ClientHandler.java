package com.ptit.lanexam.server.network;

import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.MessageType;
import com.ptit.lanexam.common.NetworkMessage;
import com.ptit.lanexam.common.TransferLimits;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.server.entity.Student;
import com.ptit.lanexam.server.service.AuthService;
import com.ptit.lanexam.server.service.ExamService;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.Semaphore;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final SessionRegistry registry;
    private final AuthService authService;
    private final ExamService examService;
    private final Supplier<Long> publishedExamId;
    private final ReadWriteLock attemptGate;
    private final BooleanSupplier acceptsAttempts;
    private final Semaphore requestSlots;

    private BufferedReader reader;
    private volatile ClientWriter writer;
    private volatile boolean closed;
    private volatile boolean closing;
    private volatile String studentCode;
    private record Incoming(NetworkMessage message, ExamService.RequestReceipt receipt, int characters) {}
    private final BlockingQueue<Incoming> incoming = new ArrayBlockingQueue<>(64);
    private final AtomicLong incomingCharacters = new AtomicLong();
    private volatile boolean inputFinished;
    private volatile Thread processor;
    private String focusEpisode;
    private Long focusExam;
    private Instant focusLeftAt;
    private boolean focusMonitoring;

    public ClientHandler(Socket socket, SessionRegistry registry, AuthService authService, ExamService examService,
                         Supplier<Long> publishedExamId, ReadWriteLock attemptGate,
                         BooleanSupplier acceptsAttempts, Semaphore requestSlots) {
        this.socket = socket;
        this.registry = registry;
        this.authService = authService;
        this.examService = examService;
        this.publishedExamId = publishedExamId;
        this.attemptGate = attemptGate;
        this.acceptsAttempts = acceptsAttempts;
        this.requestSlots = requestSlots;
    }

    @Override
    public void run() {
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new ClientWriter(socket.getOutputStream(), this::closeTransport);
            processor = Thread.ofVirtual().name("lan-exam-client-requests").start(this::processRequests);

            String line;
            while (!closed && (line = TransferLimits.readLine(reader)) != null) {
                ExamService.RequestReceipt receipt = examService.receiveRequest();
                boolean queued = false;
                try {
                    NetworkMessage message = JsonUtil.fromJson(line);
                    if (message.getType() == MessageType.ANSWER_UPDATE || message.getType() == MessageType.SUBMIT_EXAM) {
                        SubmitExamRequest answers = JsonUtil.payload(message, SubmitExamRequest.class);
                        receipt.bind(studentCode, answers == null ? null : answers.getExamId());
                    } else receipt.close();
                    if (incomingCharacters.addAndGet(line.length()) > 2L * TransferLimits.MAX_MESSAGE_CHARS
                            || !incoming.offer(new Incoming(message, receipt, line.length())))
                        throw new IOException("Quá nhiều yêu cầu đang chờ xử lý.");
                    queued = true;
                } finally { if (!queued) receipt.close(); }
            }
        } catch (Exception e) {
            if (!closed) System.err.println("Client " + socket.getRemoteSocketAddress() + " disconnected: " + e.getMessage());
        } finally {
            inputFinished = true;
            // An EOF must not discard complete answers already received. Drain
            // the bounded queue before removing the student's connection.
            Thread worker = processor;
            if (worker != null) {
                try { worker.join(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); closeQuietly(); }
            }
            Incoming abandoned;
            while ((abandoned = incoming.poll()) != null) abandoned.receipt().close();
            cleanup();
        }
    }

    private void processRequests() {
        try {
            while (!closed && (!inputFinished || !incoming.isEmpty())) {
                Incoming request = incoming.poll(100, TimeUnit.MILLISECONDS);
                if (request == null) continue;
                incomingCharacters.addAndGet(-request.characters());
                try (ExamService.RequestReceipt receipt = request.receipt()) {
                    requestSlots.acquire();
                    try {
                        if (!closed && !closing) {
                            if (request.message().getType() == MessageType.ANSWER_UPDATE
                                    || request.message().getType() == MessageType.SUBMIT_EXAM) {
                                SubmitExamRequest answers = JsonUtil.payload(request.message(), SubmitExamRequest.class);
                                receipt.bind(studentCode, answers == null ? null : answers.getExamId());
                            }
                            handle(request.message(), receipt.receivedAt());
                        }
                    } finally { requestSlots.release(); }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            closeQuietly();
        }
    }

    private synchronized void handle(NetworkMessage message, Instant receivedAt) {
        try {
            switch (message.getType()) {
                case LOGIN -> handleLogin(JsonUtil.payload(message, LoginRequest.class));
                case JOIN_EXAM -> requireLogin(() -> handleJoin(JsonUtil.payload(message, JoinExamRequest.class)));
                case HISTORY_REQUEST -> requireLogin(() -> {
                    sendHistory();
                    sendAvailability();
                });
                case SUBMIT_EXAM -> requireLogin(() -> handleSubmit(JsonUtil.payload(message, SubmitExamRequest.class), receivedAt));
                case SAVE_DRAFT -> sendError("Client cũ không còn được hỗ trợ. Hãy dùng LANExamClient.exe phiên bản mới.");
                case ANSWER_UPDATE -> requireLogin(() -> examService.updateAnswers(studentCode,
                        JsonUtil.payload(message, SubmitExamRequest.class), receivedAt));
                case PING -> requireLogin(() -> send(JsonUtil.message(MessageType.PONG, null)));
                case EXAM_FOCUS_CHANGED -> requireLogin(() -> handleFocus(JsonUtil.payload(message, ExamFocusChange.class), receivedAt));
                default -> sendError("Message không được hỗ trợ: " + message.getType());
            }
        } catch (Exception e) {
            send(JsonUtil.message(MessageType.ERROR, e.getMessage() == null ? "Có lỗi xử lý yêu cầu" : e.getMessage()));
        }
    }


    private void handleLogin(LoginRequest request) {
        if (!acceptsAttempts.getAsBoolean()) { sendError("Server đã dừng."); closeQuietly(); return; }
        if (studentCode != null) {
            sendError("Kết nối này đã đăng nhập.");
            return;
        }
        Optional<Student> student = request == null ? Optional.empty()
                : authService.authenticate(request.getStudentCode(), request.getBirthDate());
        if (student.isEmpty()) {
            send(JsonUtil.message(MessageType.LOGIN_RESULT,
                    new LoginResponse(false, "MSV/ngày sinh không khớp hoặc sinh viên chưa thuộc lớp của giảng viên đang mở Server. Kiểm tra hồ sơ tại Lớp & sinh viên.", null)));
            return;
        }

        Student s = student.get();
        if (authService.isAccountLocked(s.getStudentCode())) {
            send(JsonUtil.message(MessageType.LOGIN_RESULT, new LoginResponse(false,
                    "Tài khoản đã bị khóa bởi giảng viên này. Lý do: " + authService.accountLockReason(s.getStudentCode()).orElse("Liên hệ giảng viên") + ".", null)));
            return;
        }
        studentCode = s.getStudentCode();
        focusMonitoring = request.isFocusMonitoring();
        String ip = socket.getInetAddress().getHostAddress();
        registry.register(new ClientSession(s.getStudentCode(), s.getFullName(), ip, this));
        if (authService.isAccountLocked(studentCode)) {
            disconnectLocked("Tài khoản đã bị giảng viên khóa. Liên hệ giảng viên để mở khóa.");
            return;
        }
        send(JsonUtil.message(MessageType.LOGIN_RESULT,
                new LoginResponse(true, "Đăng nhập thành công", s.getFullName())));
        // Login describes this connection; past submissions belong in history.
        sendHistory();
        sendAvailability();
        try {
            examService.getOpenAttempt(studentCode).ifPresent(attempt -> examService.events().connection(attempt.getId(), studentCode, true));
        } catch (RuntimeException ex) { System.err.println("Cannot log reconnect: " + ex.getMessage()); }
    }

    private void handleJoin(JoinExamRequest request) {
        attemptGate.readLock().lock();
        try {
            if (!acceptsAttempts.getAsBoolean()) throw new IllegalStateException("Server đã dừng.");
            joinExam(request);
        } finally { attemptGate.readLock().unlock(); }
    }

    private void joinExam(JoinExamRequest request) {
        if (request == null || request.examId() == null) throw new IllegalArgumentException("Thiếu mã đề thi.");
        ExamOffer offer = examService.findOffer(studentCode, publishedExamId.get())
                .orElseThrow(() -> new IllegalStateException("Chưa có bài thi nào đang mở cho bạn."));
        if (!offer.examId().equals(request.examId()))
            throw new IllegalStateException("Bài thi này không còn mở. Hãy làm mới trang chính.");
        Optional<ExamDto> exam = examService.startAttempt(studentCode, offer.examId());
        if (exam.isEmpty()) {
            sendHistory();
            sendAvailability();
            throw new IllegalStateException("Bạn đã hoàn thành bài thi này.");
        }
        sendExam(exam.get());
    }

    private void sendHistory() {
        send(JsonUtil.message(MessageType.HISTORY_RESULT,
                new ExamHistoryResponse(examService.listStudentHistory(studentCode))));
    }

    public synchronized boolean sendAvailability() {
        if (studentCode == null || closed || !registry.isCurrent(studentCode, this)) return false;
        Optional<ExamOffer> offer = examService.findOffer(studentCode, publishedExamId.get());
        if (offer.isPresent() && !offer.get().resumable())
            registry.updateExam(studentCode, this, session -> session.readyForExam(offer.get().examId()));
        send(JsonUtil.message(MessageType.EXAM_AVAILABLE, new ExamAvailability(offer.orElse(null))));
        return offer.isPresent();
    }

    private void handleSubmit(SubmitExamRequest request, Instant receivedAt) {
        ClientSession current = registry.find(studentCode);
        if (current != null && current.getStatus() == ClientStatus.DOING_EXAM
                && (request == null || !java.util.Objects.equals(current.getExamId(), request.getExamId())))
            throw new IllegalArgumentException("Yêu cầu nộp bài không thuộc đề đang làm trong phiên này.");
        SubmitResult result = examService.submit(studentCode, request, receivedAt);
        registry.updateExam(studentCode, this, session -> session.completeExam(request.getExamId()));
        send(JsonUtil.message(MessageType.SUBMIT_RESULT, result));
        sendHistory();
        sendAvailability();
    }

    private void requireLogin(Runnable action) {
        if (studentCode == null || closed || !registry.isCurrent(studentCode, this)) {
            sendError("Bạn chưa đăng nhập");
            return;
        }
        if (authService.isAccountLocked(studentCode)) {
            disconnectLocked("Tài khoản đã bị giảng viên khóa. Liên hệ giảng viên để mở khóa.");
            return;
        }
        action.run();
    }

    public void send(NetworkMessage message) {
        if (closed || writer == null) return;
        writer.send(message, false);
    }

    public synchronized void sendExam(ExamDto exam) {
        if (studentCode == null) return;
        registry.updateExam(studentCode, this, session -> session.startExam(exam));
        send(JsonUtil.message(MessageType.EXAM_STARTED, exam));
        focusEpisode = null; focusExam = null; focusLeftAt = null;
        if (focusMonitoring) send(JsonUtil.message(MessageType.EXAM_WARNING_STATUS, examService.events().status(exam.getId(), studentCode, "")));
    }

    private void handleFocus(ExamFocusChange report, Instant receivedAt) {
        focusMonitoring = true;
        ClientSession session = registry.find(studentCode);
        if (report == null || report.examId() == null || report.episodeId() == null || report.episodeId().length() > 36)
            throw new IllegalArgumentException("Tín hiệu cửa sổ không hợp lệ.");
        java.util.UUID.fromString(report.episodeId());
        if (session == null || session.getStatus() != ClientStatus.DOING_EXAM
                || !java.util.Objects.equals(session.getExamId(), report.examId())) return;
        if (!report.focused()) {
            if (focusEpisode == null) { focusEpisode = report.episodeId(); focusExam = report.examId(); focusLeftAt = receivedAt; }
            else if (report.episodeId().equals(focusEpisode) && report.examId().equals(focusExam)) {
                var status = examService.events().focusStillAway(report.examId(), studentCode, report.episodeId(), focusLeftAt, receivedAt);
                send(JsonUtil.message(MessageType.EXAM_WARNING_STATUS, status));
            }
        } else if (report.episodeId().equals(focusEpisode) && report.examId().equals(focusExam)) {
            Instant left = focusLeftAt;
            focusEpisode = null; focusExam = null; focusLeftAt = null;
            var status = examService.events().focusReturned(report.examId(), studentCode, report.episodeId(), left, receivedAt);
            send(JsonUtil.message(MessageType.EXAM_WARNING_STATUS, status));
        }
    }

    private void sendError(String message) {
        send(JsonUtil.message(MessageType.ERROR, message));
    }

    public synchronized boolean warnViolation(String reason) {
        if (closed || studentCode == null || !registry.isCurrent(studentCode, this)) return false;
        return writer != null && writer.send(JsonUtil.message(MessageType.VIOLATION_WARNING, reason), false);
    }

    public synchronized void disconnectLocked(String reason) {
        closing = true;
        if (studentCode != null) registry.remove(studentCode, this);
        if (writer == null || !writer.send(JsonUtil.message(MessageType.ACCOUNT_LOCKED, reason), true)) closeQuietly();
    }

    public synchronized void refreshExpiredAttempt() {
        if (closed || studentCode == null || !registry.isCurrent(studentCode, this)) return;
        ClientSession session = registry.find(studentCode);
        if (session == null || session.getStatus() != ClientStatus.DOING_EXAM) return;
        Long examId = session.getExamId();
        examService.getExamResult(studentCode, examId).ifPresent(result -> {
            registry.updateExam(studentCode, this, current -> current.completeExam(examId));
            send(JsonUtil.message(MessageType.SUBMIT_RESULT, result));
            sendHistory();
            sendAvailability();
        });
    }

    private void cleanup() {
        ClientSession current = studentCode == null ? null : registry.find(studentCode);
        if (current != null && current.getHandler() == this && current.getStatus() == ClientStatus.DOING_EXAM && acceptsAttempts.getAsBoolean()) {
            try { examService.events().connection(current.getExamId(), studentCode, false); }
            catch (RuntimeException ex) { System.err.println("Cannot log disconnect: " + ex.getMessage()); }
        }
        if (studentCode != null) registry.remove(studentCode, this);
        closeQuietly();
    }

    public void closeQuietly() {
        closed = true;
        closeTransport();
        Thread worker = processor;
        if (worker != null) worker.interrupt();
    }

    private void closeTransport() {
        try { socket.close(); } catch (IOException ignored) {}
        ClientWriter currentWriter = writer;
        if (currentWriter != null) currentWriter.close();
    }
}
