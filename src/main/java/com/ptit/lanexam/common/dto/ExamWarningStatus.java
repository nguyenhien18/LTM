package com.ptit.lanexam.common.dto;

public record ExamWarningStatus(Long examId, long count, boolean needsReview, String message) {}
