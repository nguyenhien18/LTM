package com.ptit.lanexam.common.dto;

import java.util.ArrayList;
import java.util.List;

public class QuestionDto {
    private Long id;
    private String content;
    private String type;
    private List<String> options = new ArrayList<>();

    public QuestionDto() {}
    public QuestionDto(Long id, String content, String type, List<String> options) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.options = options;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }
}
