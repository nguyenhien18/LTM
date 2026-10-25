package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;
import java.time.Instant;

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
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public byte[] getFileData() { return fileData; }
}
