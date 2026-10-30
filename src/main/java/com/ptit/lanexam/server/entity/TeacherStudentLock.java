package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;

/** A teacher's restriction must not block the student's other teachers. */
@Entity
@Table(name = "teacher_student_locks", uniqueConstraints = @UniqueConstraint(columnNames = {"teacher_id", "student_id"}))
public class TeacherStudentLock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private Teacher teacher;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;
    @Column(nullable = false, length = 500)
    private String reason;
    public TeacherStudentLock() {}
    public TeacherStudentLock(Teacher teacher, Student student, String reason) {
        this.teacher = teacher; this.student = student; this.reason = reason;
    }
    public Student getStudent() { return student; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
