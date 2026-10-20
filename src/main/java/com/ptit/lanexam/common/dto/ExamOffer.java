package com.ptit.lanexam.common.dto;

public record ExamOffer(Long examId, String title, int durationSeconds, boolean resumable) {}
