package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.QuestionType;

public record BankQuestionInfo(Long id, Long subjectId, String subjectName, int difficulty,
                               String content, QuestionType type, String optionA, String optionB,
                               String optionC, String optionD, String correctAnswer) {
    public ExamDraft.QuestionDraft questionDraft() {
        return new ExamDraft.QuestionDraft(content, type, optionA, optionB, optionC, optionD, correctAnswer);
    }
    public String preview() {
        String options = type == QuestionType.MULTIPLE_CHOICE
                ? "\nA. " + optionA + "\nB. " + optionB + "\nC. " + optionC + "\nD. " + optionD : "";
        return content + options + "\nĐáp án: " + correctAnswer;
    }
}
