package com.ptit.lanexam.server.service;

import java.time.LocalDateTime;

public record ExamResultRow(Long roundId, String studentCode, String fullName, String examTitle,
                            double score, int correct, int total, LocalDateTime submittedAt, Long classId, String variantCode) {
    public ExamResultRow(Long roundId, String studentCode, String fullName, String examTitle,
                        double score, int correct, int total, LocalDateTime submittedAt, Long classId) {
        this(roundId, studentCode, fullName, examTitle, score, correct, total, submittedAt, classId, null);
    }
    public ExamResultRow(Long roundId, String studentCode, String fullName, String examTitle,
                         double score, int correct, int total, LocalDateTime submittedAt) {
        this(roundId, studentCode, fullName, examTitle, score, correct, total, submittedAt, null, null);
    }
}
