package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "subjects")
public class Subject extends TeacherOwned {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(name = "normalized_name", nullable = false, unique = true, length = 160)
    private String normalizedName;

    public Subject() {}
    public Subject(String name, String normalizedName) {
        this.name = name;
        this.normalizedName = normalizedName;
    }
    public Long getId() { return id; }
    public String getName() { return name; }
}
