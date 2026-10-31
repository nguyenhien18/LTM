package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "students", uniqueConstraints = @UniqueConstraint(columnNames = "student_code"))
public class Student {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_code", nullable = false, length = 30)
    private String studentCode;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "regular_class", length = 80)
    private String regularClass;
    public String getRegularClass() { return regularClass; }

    @Column(name = "account_locked", nullable = false, columnDefinition = "boolean default false")
    private boolean accountLocked;

    @Column(name = "lock_reason", length = 500)
    private String lockReason;

    public Student() {}
    public Student(String studentCode, String fullName, LocalDate birthDate) {
        this.studentCode = studentCode;
        this.fullName = fullName;
        this.birthDate = birthDate;
    }
    public Student(String studentCode, String fullName, LocalDate birthDate, String regularClass) {
        this(studentCode, fullName, birthDate); this.regularClass = regularClass;
    }

    public Long getId() { return id; }
    public String getStudentCode() { return studentCode; }
    public String getFullName() { return fullName; }
    public LocalDate getBirthDate() { return birthDate; }
    public boolean isAccountLocked() { return accountLocked; }
    public String getLockReason() { return lockReason; }
    public void setAccountLock(boolean locked, String reason) {
        this.accountLocked = locked;
        this.lockReason = locked ? reason : null;
    }
}
