package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "class_students", uniqueConstraints = @UniqueConstraint(columnNames = {"class_id", "student_id"}))
public class ClassStudent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_id", nullable = false)
    private CourseClass courseClass;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;
    @Column(name = "csv_ordinal")
    private Integer ordinal;
    public ClassStudent() {}
    public ClassStudent(CourseClass courseClass, Student student) { this.courseClass = courseClass; this.student = student; }
    public Student getStudent() { return student; }
    public Integer getOrdinal() { return ordinal; }
    public void setOrdinal(Integer ordinal) { this.ordinal = ordinal; }
}
