package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;
import java.time.Instant;
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

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    // Null for a reusable exam template; set for a published round copied from that template.
    @Column(name = "template_exam_id")
    private Long templateExamId;

    @Lob @Column(name = "variants_json", columnDefinition = "MEDIUMTEXT")
    private String variantsJson;
    public String getVariantsJson() { return variantsJson; }
    public void setVariantsJson(String value) { variantsJson = value; }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id")
    private CourseClass courseClass;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", unique = true)
    private ExamShift shift;

    public ExamShift getShift() { return shift; }
    public void setShift(ExamShift shift) { this.shift = shift; }

    public CourseClass getCourseClass() { return courseClass; }
    public void setCourseClass(CourseClass value) { courseClass = value; }

    @OneToMany(mappedBy = "exam", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<Question> questions = new ArrayList<>();

    public Exam() {}
    public Exam(String title, int durationMinutes, boolean active) {
        this.title = title;
        this.durationMinutes = durationMinutes;
        this.active = active;
        if (active) openAt(Instant.now());
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
    public Instant getOpenedAt() { return openedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public void openAt(Instant now) {
        // Match the millisecond precision sent over TCP so the database and
        // the Client agree on the exact boundary of the common deadline.
        openedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        deadlineAt = openedAt.plusSeconds(Math.multiplyExact((long) durationMinutes, 60));
    }
    public void setActive(boolean active) { this.active = active; }
    public Long getTemplateExamId() { return templateExamId; }
    public void setTemplateExamId(Long templateExamId) { this.templateExamId = templateExamId; }
    public List<Question> getQuestions() { return questions; }
}
