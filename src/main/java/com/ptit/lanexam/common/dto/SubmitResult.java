package com.ptit.lanexam.common.dto;

public class SubmitResult {
    private double score;
    private int correctCount;
    private int totalQuestions;
    private String message;

    public SubmitResult() {}
    public SubmitResult(double score, int correctCount, int totalQuestions, String message) {
        this.score = score;
        this.correctCount = correctCount;
        this.totalQuestions = totalQuestions;
        this.message = message;
    }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public int getCorrectCount() { return correctCount; }
    public void setCorrectCount(int correctCount) { this.correctCount = correctCount; }
    public int getTotalQuestions() { return totalQuestions; }
    public void setTotalQuestions(int totalQuestions) { this.totalQuestions = totalQuestions; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
