package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "submissions", uniqueConstraints =
        @UniqueConstraint(name = "uk_submission_student_exam", columnNames = {"student_id", "exam_id"}))
public class Submission {
    @Column(name = "variant_code", length = 8)
    private String variantCode;
    @Column(name = "automatic_submission")
    private Boolean automaticSubmission;
    public boolean isAutomaticSubmission() { return Boolean.TRUE.equals(automaticSubmission); }
    public void setAutomaticSubmission(boolean value) { automaticSubmission = value; }
    public String getVariantCode() { return variantCode; }
    public void setVariantCode(String value) { variantCode = value; }
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @Column(nullable = false)
    private double score;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @OneToMany(mappedBy = "submission", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StudentAnswer> answers = new ArrayList<>();

    public Submission() {}
    public Submission(Student student, Exam exam, double score, LocalDateTime submittedAt) {
        this.student = student;
        this.exam = exam;
        this.score = score;
        this.submittedAt = submittedAt;
    }

    public void addAnswer(StudentAnswer answer) {
        answer.setSubmission(this);
        answers.add(answer);
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public Exam getExam() { return exam; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public List<StudentAnswer> getAnswers() { return answers; }
}
