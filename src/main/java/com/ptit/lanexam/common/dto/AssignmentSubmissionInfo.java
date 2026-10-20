package com.ptit.lanexam.common.dto;

import java.time.Instant;

public record AssignmentSubmissionInfo(Long id, String studentCode, String studentName,
                                       String content, String fileName, long fileSize, Instant submittedAt) {}
