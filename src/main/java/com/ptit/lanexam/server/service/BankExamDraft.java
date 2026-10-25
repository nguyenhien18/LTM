package com.ptit.lanexam.server.service;

import java.util.List;

public record BankExamDraft(String title, int durationMinutes, Long subjectId, List<Long> questionIds) {}
