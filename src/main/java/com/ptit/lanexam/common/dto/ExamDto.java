package com.ptit.lanexam.common.dto;

import java.util.ArrayList;
import java.util.List;

public class ExamDto {
    private Long id;
    private String title;
    private String subjectName;
    private int durationSeconds;
    private long deadlineEpochMillis;
    private long serverTimeEpochMillis;
    private List<QuestionDto> questions = new ArrayList<>();

    public ExamDto() {}
    public ExamDto(Long id, String title, int durationSeconds, List<QuestionDto> questions) {
        this.id = id;
        this.title = title;
        this.durationSeconds = durationSeconds;
        this.questions = questions;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
    public long getDeadlineEpochMillis() { return deadlineEpochMillis; }
    public void setDeadlineEpochMillis(long deadlineEpochMillis) { this.deadlineEpochMillis = deadlineEpochMillis; }
    public long getServerTimeEpochMillis() { return serverTimeEpochMillis; }
    public void setServerTimeEpochMillis(long serverTimeEpochMillis) { this.serverTimeEpochMillis = serverTimeEpochMillis; }
    public List<QuestionDto> getQuestions() { return questions; }
    public void setQuestions(List<QuestionDto> questions) { this.questions = questions; }
}
