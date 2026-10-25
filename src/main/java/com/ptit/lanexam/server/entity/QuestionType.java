package com.ptit.lanexam.server.entity;

public enum QuestionType {
    MULTIPLE_CHOICE("Trắc nghiệm"),
    // Retained to read previously saved exams/results; creation and publication only accept MULTIPLE_CHOICE.
    TEXT("Điền đáp án");

    private final String label;
    QuestionType(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
