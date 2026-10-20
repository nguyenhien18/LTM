package com.ptit.lanexam.common.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class SubmitExamRequest {
    private Long examId;
    private Map<Long, String> answers = new LinkedHashMap<>();

    public SubmitExamRequest() {}
    public SubmitExamRequest(Long examId, Map<Long, String> answers) {
        this.examId = examId;
        this.answers = answers;
    }

    public Long getExamId() { return examId; }
    public void setExamId(Long examId) { this.examId = examId; }
    public Map<Long, String> getAnswers() { return answers; }
    public void setAnswers(Map<Long, String> answers) { this.answers = answers; }
}
