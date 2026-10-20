package com.ptit.lanexam.common.dto;

import java.time.Instant;

public record AssignmentInfo(Long id, String title, String description, Instant createdAt, Instant dueAt,
                             String fileName, long fileSize, long submissionCount,
                             AssignmentSubmissionInfo mySubmission) {}
