package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.QuestionType;
import java.util.List;

public record ExamDraft(String title, int durationMinutes, List<QuestionDraft> questions) {
    public record QuestionDraft(String content, QuestionType type, String optionA, String optionB,
                                String optionC, String optionD, String correctAnswer) {}
}
