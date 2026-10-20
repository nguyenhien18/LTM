package com.ptit.lanexam.server.service;

import java.time.LocalDateTime;

public record ExamResultRow(Long roundId, String studentCode, String fullName, String examTitle,
                            double score, int correct, int total, LocalDateTime submittedAt) {}
