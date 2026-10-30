package com.ptit.lanexam.server.service;

import java.time.LocalDateTime;

public record ExamResultRow(Long roundId, String studentCode, String fullName, String examTitle,
                            Double score, Integer correct, int total, LocalDateTime submittedAt, Long classId, String variantCode, Status status) {
    public enum Status {
        NOT_STARTED("Chưa vào bài"), DOING("Đang làm"), GRADING("Đang chấm"),
        SUBMITTED("Đã nộp"), EXPIRED("Hết giờ — đã chấm"), ABSENT("Vắng thi");
        private final String label;
        Status(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public ExamResultRow(Long roundId, String studentCode, String fullName, String examTitle,
                         double score, int correct, int total, LocalDateTime submittedAt, Long classId, String variantCode) {
        this(roundId, studentCode, fullName, examTitle, score, correct, total, submittedAt, classId, variantCode, Status.SUBMITTED);
    }
    public ExamResultRow(Long roundId, String studentCode, String fullName, String examTitle,
                        double score, int correct, int total, LocalDateTime submittedAt, Long classId) {
        this(roundId, studentCode, fullName, examTitle, score, correct, total, submittedAt, classId, null);
    }
    public ExamResultRow(Long roundId, String studentCode, String fullName, String examTitle,
                         double score, int correct, int total, LocalDateTime submittedAt) {
        this(roundId, studentCode, fullName, examTitle, score, correct, total, submittedAt, null, null);
    }
}
