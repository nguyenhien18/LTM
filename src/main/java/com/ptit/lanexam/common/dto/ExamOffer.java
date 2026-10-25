package com.ptit.lanexam.common.dto;

public record ExamOffer(Long examId, String title, int durationSeconds, boolean resumable, String subjectName) {
    public ExamOffer(Long examId, String title, int durationSeconds, boolean resumable) {
        this(examId, title, durationSeconds, resumable, null);
    }
}
