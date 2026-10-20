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

    @Lob
    @Column(name = "draft_answers", columnDefinition = "LONGTEXT")
    private String draftAnswers = "{}";

    protected ExamAttempt() {}

    public ExamAttempt(Student student, Exam exam, Instant startedAt) {
        this.student = student;
        this.exam = exam;
        this.startedAt = startedAt;
        this.deadlineAt = startedAt.plusSeconds(Math.multiplyExact((long) exam.getDurationMinutes(), 60));
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public Exam getExam() { return exam; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public String getDraftAnswers() { return draftAnswers; }
    public void setDraftAnswers(String draftAnswers) { this.draftAnswers = draftAnswers; }
    public void markSubmitted(Instant submittedAt) { this.submittedAt = submittedAt; }
    public boolean isOpenAt(Instant now) { return submittedAt == null && now.isBefore(deadlineAt); }
}
