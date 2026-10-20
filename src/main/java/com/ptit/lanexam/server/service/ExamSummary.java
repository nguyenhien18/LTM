package com.ptit.lanexam.server.service;

public record ExamSummary(Long id, String title, int durationMinutes, int questionCount, boolean active) {}
