package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "exams")
public class Exam extends TeacherOwned {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(nullable = false)
    private boolean active;

    // Null for a reusable exam template; set for a published round copied from that template.
    @Column(name = "template_exam_id")
    private Long templateExamId;

    @OneToMany(mappedBy = "exam", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<Question> questions = new ArrayList<>();

    public Exam() {}
    public Exam(String title, int durationMinutes, boolean active) {
        this.title = title;
        this.durationMinutes = durationMinutes;
        this.active = active;
    }

    public void addQuestion(Question question) {
        question.setExam(this);
        questions.add(question);
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public Subject getSubject() { return subject; }
    public void setSubject(Subject subject) { this.subject = subject; }
    public String getSubjectName() { return subject == null ? null : subject.getName(); }
    public void setTitle(String title) { this.title = title; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Long getTemplateExamId() { return templateExamId; }
    public void setTemplateExamId(Long templateExamId) { this.templateExamId = templateExamId; }
    public List<Question> getQuestions() { return questions; }
}
