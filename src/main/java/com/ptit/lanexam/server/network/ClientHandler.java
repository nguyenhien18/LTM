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

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final SessionRegistry registry;
    private final AuthService authService;
    private final ExamService examService;
    private final AssignmentService assignmentService;
    private final Supplier<Long> publishedExamId;

    private BufferedReader reader;
    private BufferedWriter writer;
    private volatile boolean closed;
    private String studentCode;

    public ClientHandler(Socket socket, SessionRegistry registry, AuthService authService, ExamService examService,
                         Supplier<Long> publishedExamId) {
        this(socket, registry, authService, examService, new AssignmentService(), publishedExamId);
    }

    public ClientHandler(Socket socket, SessionRegistry registry, AuthService authService, ExamService examService,
                         AssignmentService assignmentService, Supplier<Long> publishedExamId) {
        this.socket = socket;
        this.registry = registry;
        this.authService = authService;
        this.examService = examService;
        this.assignmentService = assignmentService;
        this.publishedExamId = publishedExamId;
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

    private void handle(NetworkMessage message) {
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
        studentCode = s.getStudentCode();
        String ip = socket.getInetAddress().getHostAddress();
        registry.register(new ClientSession(s.getStudentCode(), s.getFullName(), ip, this));
        send(JsonUtil.message(MessageType.LOGIN_RESULT,
                new LoginResponse(true, "Đăng nhập thành công", s.getFullName())));
        if (examService.getCurrentExamResult(studentCode).isPresent())
            registry.updateStatus(studentCode, ClientStatus.SUBMITTED);
        sendHistory();
        sendAvailability();
        sendAssignments();
    }

    private void handleJoin(JoinExamRequest request) {
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

    public boolean sendAvailability() {
        if (studentCode == null || closed || !registry.isCurrent(studentCode, this)) return false;
        Optional<ExamOffer> offer = examService.findOffer(studentCode, publishedExamId.get());
        send(JsonUtil.message(MessageType.EXAM_AVAILABLE, new ExamAvailability(offer.orElse(null))));
        return offer.isPresent();
    }

    private void handleSubmit(SubmitExamRequest request) {
        SubmitResult result = examService.submit(studentCode, request);
        registry.updateStatus(studentCode, ClientStatus.SUBMITTED);
        send(JsonUtil.message(MessageType.SUBMIT_RESULT, result));
        sendHistory();
        sendAvailability();
    }

    private void requireLogin(Runnable action) {
        if (studentCode == null || closed || !registry.isCurrent(studentCode, this)) {
            sendError("Bạn chưa đăng nhập");
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

    public void sendExam(ExamDto exam) {
        if (studentCode == null) return;
        registry.updateStatus(studentCode, ClientStatus.DOING_EXAM);
        send(JsonUtil.message(MessageType.EXAM_STARTED, exam));
    }

    private void sendError(String message) {
        send(JsonUtil.message(MessageType.ERROR, message));
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
