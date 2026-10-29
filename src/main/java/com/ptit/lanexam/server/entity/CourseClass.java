package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;

/** A course group in one semester, independent of the physical computer lab. */
@Entity
@Table(name = "course_classes", uniqueConstraints = @UniqueConstraint(columnNames = {"owner_teacher_id", "class_code"}))
public class CourseClass extends TeacherOwned {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "class_code", nullable = false, length = 40)
    private String code;
    @Column(nullable = false, length = 160)
    private String name;
    @Column(nullable = false, length = 80)
    private String semester;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;

    public CourseClass() {}
    public CourseClass(String code, String name, String semester, Subject subject) {
        this.code = code; this.name = name; this.semester = semester; this.subject = subject;
    }
    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getSemester() { return semester; }
    public Subject getSubject() { return subject; }
    public String displayName() { return code + " · " + name; }
}
