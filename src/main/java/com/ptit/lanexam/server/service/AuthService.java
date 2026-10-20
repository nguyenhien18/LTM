package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.Student;
import com.ptit.lanexam.server.persistence.JpaUtil;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Supplier;

public class AuthService {
    private final Supplier<EntityManager> entityManagers;

    public AuthService() { this(JpaUtil::em); }
    public AuthService(Supplier<EntityManager> entityManagers) { this.entityManagers = entityManagers; }

    public Optional<Student> authenticate(String studentCode, String birthDateText) {
        if (studentCode == null || birthDateText == null) return Optional.empty();

        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(birthDateText);
        } catch (Exception e) {
            return Optional.empty();
        }

        EntityManager em = entityManagers.get();
        try {
            return em.createQuery(
                            "select s from Student s where upper(s.studentCode) = :code and s.birthDate = :birthDate",
                            Student.class)
                    .setParameter("code", studentCode.trim().toUpperCase())
                    .setParameter("birthDate", birthDate)
                    .getResultStream()
                    .findFirst();
        } finally {
            em.close();
        }
    }
}
