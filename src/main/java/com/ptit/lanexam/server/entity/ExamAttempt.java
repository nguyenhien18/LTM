package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "exam_attempts", uniqueConstraints =
        @UniqueConstraint(name = "uk_attempt_student_exam", columnNames = {"student_id", "exam_id"}))
public class ExamAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "variant_code", length = 8)
    private String variantCode;
    public String getVariantCode() { return variantCode; }
    public void setVariantCode(String value) { variantCode = value; }

    @Lob @Column(name = "saved_answers_json", columnDefinition = "TEXT")
    private String savedAnswersJson;
    public String getSavedAnswersJson() { return savedAnswersJson; }
    public void setSavedAnswersJson(String value) { savedAnswersJson = value; }

    protected ExamAttempt() {}

    public ExamAttempt(Student student, Exam exam, Instant startedAt) {
        this.student = student;
        this.exam = exam;
        this.startedAt = startedAt;
        this.deadlineAt = exam.getDeadlineAt() != null ? exam.getDeadlineAt()
                : startedAt.plusSeconds(Math.multiplyExact((long) exam.getDurationMinutes(), 60));
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public Exam getExam() { return exam; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void markSubmitted(Instant submittedAt) { this.submittedAt = submittedAt; }
    public boolean isOpenAt(Instant now) { return submittedAt == null && now.isBefore(deadlineAt); }
}
