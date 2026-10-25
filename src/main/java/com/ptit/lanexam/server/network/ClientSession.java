package com.ptit.lanexam.server.network;

import com.ptit.lanexam.common.dto.ExamDto;
import java.util.Objects;

public class ClientSession {
    private final String studentCode;
    private final String fullName;
    private final String ip;
    private final ClientHandler handler;
    private record Progress(ClientStatus status, Long examId, String title) {}
    private volatile Progress progress = new Progress(ClientStatus.ONLINE, null, null);

    public ClientSession(String studentCode, String fullName, String ip, ClientHandler handler) {
        this.studentCode = studentCode;
        this.fullName = fullName;
        this.ip = ip;
        this.handler = handler;
    }

    public String getStudentCode() { return studentCode; }
    public String getFullName() { return fullName; }
    public String getIp() { return ip; }
    public ClientHandler getHandler() { return handler; }
    public ClientStatus getStatus() { return progress.status(); }
    public Long getExamId() { return progress.examId(); }
    public void startExam(ExamDto exam) {
        progress = new Progress(ClientStatus.DOING_EXAM, exam.getId(), exam.getTitle());
    }
    public void completeExam(Long examId) {
        Progress current = progress;
        if (Objects.equals(current.examId(), examId))
            progress = new Progress(ClientStatus.SUBMITTED, current.examId(), current.title());
    }
    public void readyForExam(Long examId) {
        Progress current = progress;
        if (current.status() != ClientStatus.DOING_EXAM && !Objects.equals(current.examId(), examId))
            progress = new Progress(ClientStatus.ONLINE, null, null);
    }

    public ClientSessionInfo info() {
        Progress current = progress;
        return new ClientSessionInfo(studentCode, fullName, ip, current.status().name(), current.examId(), current.title());
    }
}
