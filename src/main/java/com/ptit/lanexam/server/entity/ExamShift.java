package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

/** A saved candidate list. Later CSV imports never change an existing shift. */
@Entity
@Table(name = "exam_shifts", indexes = @Index(columnList = "class_id"),
       uniqueConstraints = @UniqueConstraint(columnNames = {"batch_key", "shift_number"}))
public class ExamShift {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_id", nullable = false)
    private CourseClass courseClass;
    @Column(name = "batch_key", nullable = false, length = 36)
    private String batchKey;
    @Column(name = "shift_number", nullable = false)
    private int number;
    @ElementCollection
    @CollectionTable(name = "exam_shift_students", joinColumns = @JoinColumn(name = "shift_id"),
        uniqueConstraints = {@UniqueConstraint(columnNames = {"shift_id", "student_id"}),
                             @UniqueConstraint(columnNames = {"shift_id", "csv_ordinal"})})
    @OrderBy("ordinal ASC")
    private List<Candidate> candidates = new ArrayList<>();

    public ExamShift() {}
    public ExamShift(CourseClass courseClass, String batchKey, int number) {
        this.courseClass = courseClass; this.batchKey = batchKey; this.number = number;
    }
    public Long getId() { return id; }
    public CourseClass getCourseClass() { return courseClass; }
    public String getBatchKey() { return batchKey; }
    public int getNumber() { return number; }
    public List<Candidate> getCandidates() { return candidates; }

    @Embeddable
    public static class Candidate {
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "student_id", nullable = false)
        private Student student;
        @Column(name = "csv_ordinal", nullable = false)
        private int ordinal;
        public Candidate() {}
        public Candidate(Student student, int ordinal) { this.student = student; this.ordinal = ordinal; }
        public Student getStudent() { return student; }
        public int getOrdinal() { return ordinal; }
    }
}
