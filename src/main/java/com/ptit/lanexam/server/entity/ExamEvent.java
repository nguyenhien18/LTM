package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "exam_events", uniqueConstraints = @UniqueConstraint(name = "uk_exam_event_episode",
        columnNames = {"exam_id", "student_id", "event_key"}),
        indexes = @Index(name = "idx_exam_events_student", columnList = "exam_id,student_id"))
public class ExamEvent {
    public enum Kind { FOCUS_LOST, MANUAL_WARNING, REVIEWED, DISCONNECTED, RECONNECTED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "exam_id", nullable = false) private Exam exam;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "student_id", nullable = false) private Student student;
    @Column(name = "event_key", nullable = false, length = 64) private String eventKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private Kind kind;
    @Column(nullable = false) private Instant occurredAt;
    @Column(nullable = false, length = 1000) private String detail;
    private Instant reviewedAt;
    protected ExamEvent() {}
    public ExamEvent(Exam exam, Student student, String eventKey, Kind kind, Instant at, String detail) {
        this.exam = exam; this.student = student; this.eventKey = eventKey; this.kind = kind; occurredAt = at; this.detail = detail;
    }
    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public Kind getKind() { return kind; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void review(Instant now) { reviewedAt = now; }
}
