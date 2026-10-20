package com.ptit.lanexam.common.dto;

import java.time.LocalDateTime;

public record ExamHistoryItem(Long examId, String examTitle, double score,
                              int correctCount, int totalQuestions, LocalDateTime submittedAt) {}
