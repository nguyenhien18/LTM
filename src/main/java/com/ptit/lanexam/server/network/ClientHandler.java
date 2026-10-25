package com.ptit.lanexam.server.network;

import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.MessageType;
import com.ptit.lanexam.common.NetworkMessage;
import com.ptit.lanexam.common.TransferLimits;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.server.entity.Student;
import com.ptit.lanexam.server.service.AuthService;
import com.ptit.lanexam.server.service.AssignmentService;
import com.ptit.lanexam.server.service.ExamService;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final SessionRegistry registry;
    private final AuthService authService;
    private final ExamService examService;
    private final AssignmentService assignmentService;
    private final Supplier<Long> publishedExamId;
    private final ReadWriteLock attemptGate;
    private final BooleanSupplier acceptsAttempts;

    private BufferedReader reader;
    private BufferedWriter writer;
    private volatile boolean closed;
    private volatile String studentCode;

    public ClientHandler(Socket socket, SessionRegistry registry, AuthService authService, ExamService examService,
                         Supplier<Long> publishedExamId) {
        this(socket, registry, authService, examService, new AssignmentService(), publishedExamId);
    }

    public ClientHandler(Socket socket, SessionRegistry registry, AuthService authService, ExamService examService,
                         AssignmentService assignmentService, Supplier<Long> publishedExamId) {
        this(socket, registry, authService, examService, assignmentService, publishedExamId, new ReentrantReadWriteLock(), () -> true);
    }

    public ClientHandler(Socket socket, SessionRegistry registry, AuthService authService, ExamService examService,
                         AssignmentService assignmentService, Supplier<Long> publishedExamId,
                         ReadWriteLock attemptGate, BooleanSupplier acceptsAttempts) {
        this.socket = socket;
        this.registry = registry;
        this.authService = authService;
        this.examService = examService;
        this.assignmentService = assignmentService;
        this.publishedExamId = publishedExamId;
        this.attemptGate = attemptGate;
        this.acceptsAttempts = acceptsAttempts;
    }

    @Override
    public void run() {
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String line;
            while (!closed && (line = TransferLimits.readLine(reader)) != null) {
                handle(JsonUtil.fromJson(line));
            }
        } catch (Exception e) {
            if (!closed) System.err.println("Client " + socket.getRemoteSocketAddress() + " disconnected: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private synchronized void handle(NetworkMessage message) {
        try {
            switch (message.getType()) {
                case LOGIN -> handleLogin(JsonUtil.payload(message, LoginRequest.class));
                case JOIN_EXAM -> requireLogin(() -> handleJoin(JsonUtil.payload(message, JoinExamRequest.class)));
                case HISTORY_REQUEST -> requireLogin(() -> {
                    sendHistory();
                    sendAvailability();
                });
                case SUBMIT_EXAM -> requireLogin(() -> handleSubmit(JsonUtil.payload(message, SubmitExamRequest.class)));
                case SAVE_DRAFT -> requireLogin(() -> examService.saveDraft(studentCode,
                        JsonUtil.payload(message, SubmitExamRequest.class)));
                case ASSIGNMENTS_REQUEST -> requireLogin(this::sendAssignments);
                case ASSIGNMENT_SUBMIT -> requireLogin(() -> {
                    var result = assignmentService.submit(studentCode,
                            JsonUtil.payload(message, AssignmentMessages.SubmitRequest.class));
                    send(JsonUtil.message(MessageType.ASSIGNMENT_SUBMITTED, result));
                    sendAssignments();
                });
                case ASSIGNMENT_FILE_REQUEST -> requireLogin(() -> send(JsonUtil.message(MessageType.ASSIGNMENT_FILE_RESULT,
                        assignmentService.downloadForStudent(studentCode,
                                JsonUtil.payload(message, AssignmentMessages.FileRequest.class)))));
                case PING -> requireLogin(() -> send(JsonUtil.message(MessageType.PONG, null)));
                default -> sendError("Message không được hỗ trợ: " + message.getType());
            }
        } catch (Exception e) {
            MessageType errorType = message.getType() != null && message.getType().name().startsWith("ASSIGNMENT")
                    ? MessageType.ASSIGNMENT_ERROR : MessageType.ERROR;
            send(JsonUtil.message(errorType, e.getMessage() == null ? "Có lỗi xử lý yêu cầu" : e.getMessage()));
        }
    }

    private void sendAssignments() {
        send(JsonUtil.message(MessageType.ASSIGNMENTS_RESULT,
                new AssignmentMessages.ListResult(assignmentService.listForStudent(studentCode))));
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
                    new LoginResponse(false, "Sai mã sinh viên hoặc ngày sinh", null)));
            return;
        }

        Student s = student.get();
        if (s.isAccountLocked()) {
            send(JsonUtil.message(MessageType.LOGIN_RESULT, new LoginResponse(false,
                    "Tài khoản đã bị khóa. Lý do: " + s.getLockReason() + ". Liên hệ giảng viên để mở khóa.", null)));
            return;
        }
        studentCode = s.getStudentCode();
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
        sendAssignments();
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

    private void handleSubmit(SubmitExamRequest request) {
        ClientSession current = registry.find(studentCode);
        if (current != null && current.getStatus() == ClientStatus.DOING_EXAM
                && (request == null || !java.util.Objects.equals(current.getExamId(), request.getExamId())))
            throw new IllegalArgumentException("Yêu cầu nộp bài không thuộc đề đang làm trong phiên này.");
        SubmitResult result = examService.submit(studentCode, request);
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

    public synchronized void send(NetworkMessage message) {
        if (closed || writer == null) return;
        try {
            writer.write(JsonUtil.toJson(message));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            closeQuietly();
        }
    }

    public synchronized void sendExam(ExamDto exam) {
        if (studentCode == null) return;
        registry.updateExam(studentCode, this, session -> session.startExam(exam));
        send(JsonUtil.message(MessageType.EXAM_STARTED, exam));
    }

    private void sendError(String message) {
        send(JsonUtil.message(MessageType.ERROR, message));
    }

    public synchronized boolean warnViolation(String reason) {
        if (closed || studentCode == null || !registry.isCurrent(studentCode, this)) return false;
        send(JsonUtil.message(MessageType.VIOLATION_WARNING, reason));
        return !closed;
    }

    public synchronized void disconnectLocked(String reason) {
        send(JsonUtil.message(MessageType.ACCOUNT_LOCKED, reason));
        closeQuietly();
        if (studentCode != null) registry.remove(studentCode, this);
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
        if (studentCode != null) registry.remove(studentCode, this);
        closeQuietly();
    }

    public void closeQuietly() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
