package com.ptit.lanexam.common.dto;

public record ExamOffer(Long examId, String title, int durationSeconds, boolean resumable, String subjectName,
                        long deadlineEpochMillis, long serverTimeEpochMillis, String className, Long classId) {
    public ExamOffer(Long examId, String title, int durationSeconds, boolean resumable, String subjectName,
                     long deadlineEpochMillis, long serverTimeEpochMillis, String className) {
        this(examId, title, durationSeconds, resumable, subjectName, deadlineEpochMillis, serverTimeEpochMillis, className, null);
    }
    public ExamOffer(Long examId, String title, int durationSeconds, boolean resumable, String subjectName,
                     long deadlineEpochMillis, long serverTimeEpochMillis) {
        this(examId, title, durationSeconds, resumable, subjectName, deadlineEpochMillis, serverTimeEpochMillis, null);
    }
    public ExamOffer(Long examId, String title, int durationSeconds, boolean resumable, String subjectName) {
        this(examId, title, durationSeconds, resumable, subjectName, 0, 0);
    }
    public ExamOffer(Long examId, String title, int durationSeconds, boolean resumable) {
        this(examId, title, durationSeconds, resumable, null);
    }
}
