package com.ptit.lanexam.server.service;

public record ExamSummary(Long id, String title, int durationMinutes, int questionCount, boolean active, String subjectName) {
    public ExamSummary(Long id, String title, int durationMinutes, int questionCount, boolean active) {
        this(id, title, durationMinutes, questionCount, active, null);
    }
}
