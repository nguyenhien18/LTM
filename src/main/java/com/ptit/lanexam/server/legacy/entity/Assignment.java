package com.ptit.lanexam.server.legacy.entity;

import com.ptit.lanexam.server.entity.*;

import jakarta.persistence.*;
import java.time.Instant;

/** Mapping retained only to read existing historical data; no practical API is exposed. */
@Entity
@Table(name = "assignments")
public class Assignment extends TeacherOwned {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String description;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "due_at")
    private Instant dueAt;
    @Column(name = "file_name")
    private String fileName;
    @Column(name = "file_size", nullable = false)
    private long fileSize;
    @Lob @Column(name = "file_data", columnDefinition = "LONGBLOB")
    private byte[] fileData;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id")
    private CourseClass courseClass;

    public CourseClass getCourseClass() { return courseClass; }
    public void setCourseClass(CourseClass value) { courseClass = value; }

    public Assignment() {}
    public Assignment(String title, String description, Instant createdAt, Instant dueAt,
                      String fileName, byte[] fileData) {
        this.title = title;
        this.description = description;
        this.createdAt = createdAt;
        this.dueAt = dueAt;
        this.fileName = fileName;
        this.fileData = fileData;
        this.fileSize = fileData == null ? 0 : fileData.length;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDueAt() { return dueAt; }
    public void closeAt(Instant time) {
        // MySQL timestamps use microseconds. Truncate so DB rounding cannot move
        // the closing boundary into the future and reject an immediate grading action.
        time = time.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (dueAt == null || dueAt.isAfter(time)) dueAt = time;
    }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public byte[] getFileData() { return fileData; }
}
