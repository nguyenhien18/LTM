package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.QuestionType;
import java.util.Locale;
import java.util.Set;

public final class QuestionRules {
    public static final int MAX_CONTENT_LENGTH = 10_000;
    private QuestionRules() {}

    public static void validate(ExamDraft.QuestionDraft question) {
        if (question == null || question.type() == null || question.content() == null || question.content().isBlank())
            throw new IllegalArgumentException("Mỗi câu hỏi cần nội dung và dạng câu hỏi.");
        if (question.type() != QuestionType.MULTIPLE_CHOICE)
            throw new IllegalArgumentException("Chỉ hỗ trợ câu trắc nghiệm có bốn lựa chọn A–D.");
        if (question.content().trim().length() > MAX_CONTENT_LENGTH)
            throw new IllegalArgumentException("Nội dung câu hỏi tối đa 10.000 ký tự.");
        if (question.correctAnswer() == null || question.correctAnswer().isBlank() || question.correctAnswer().trim().length() > 500)
            throw new IllegalArgumentException("Nhập đáp án đúng, tối đa 500 ký tự.");
        if (question.type() == QuestionType.MULTIPLE_CHOICE) {
            for (String option : new String[]{question.optionA(), question.optionB(), question.optionC(), question.optionD()})
                if (option == null || option.isBlank() || option.trim().length() > 500)
                    throw new IllegalArgumentException("Câu trắc nghiệm cần đủ bốn lựa chọn, mỗi lựa chọn tối đa 500 ký tự.");
            if (!Set.of("A", "B", "C", "D").contains(question.correctAnswer().trim().toUpperCase(Locale.ROOT)))
                throw new IllegalArgumentException("Đáp án trắc nghiệm phải là A, B, C hoặc D.");
        }
    }
}
