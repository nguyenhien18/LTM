package com.ptit.lanexam.server.legacy.entity;

import com.ptit.lanexam.server.entity.*;

import jakarta.persistence.*;
import java.time.Instant;

/** Mapping retained only to read existing historical data; no practical API is exposed. */
@Entity
@Table(name = "assignment_submissions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"assignment_id", "student_id"}))
public class AssignmentSubmission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false)
    private Assignment assignment;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content = "";
    @Column(name = "file_name")
    private String fileName;
    @Column(name = "file_size", nullable = false)
    private long fileSize;
    @Lob @Column(name = "file_data", columnDefinition = "LONGBLOB")
    private byte[] fileData;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
    @Column(name = "score")
    private Double score;
    @Column(name = "feedback", length = 5000)
    private String feedback;
    @Column(name = "graded_at")
    private Instant gradedAt;

    public AssignmentSubmission() {}
    public AssignmentSubmission(Assignment assignment, Student student) {
        this.assignment = assignment;
        this.student = student;
    }

    public void update(String content, Instant time) {
        this.content = content;
        this.submittedAt = time;
        this.score = null;
        this.feedback = null;
        this.gradedAt = null;
    }
    public void setFile(String name, byte[] data) {
        this.fileName = name;
        this.fileData = data;
        this.fileSize = data == null ? 0 : data.length;
    }

    public void grade(double score, String feedback, Instant at) {
        this.score = score;
        this.feedback = feedback;
        this.gradedAt = at;
    }

    public Long getId() { return id; }
    public Assignment getAssignment() { return assignment; }
    public Student getStudent() { return student; }
    public String getContent() { return content; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public byte[] getFileData() { return fileData; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Double getScore() { return score; }
    public String getFeedback() { return feedback; }
    public Instant getGradedAt() { return gradedAt; }
}
