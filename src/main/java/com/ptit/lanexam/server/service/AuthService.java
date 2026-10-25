package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.Student;
import com.ptit.lanexam.server.persistence.JpaUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.Locale;
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
                    .setParameter("code", studentCode.trim().toUpperCase(Locale.ROOT))
                    .setParameter("birthDate", birthDate)
                    .getResultStream()
                    .findFirst();
        } finally {
            em.close();
        }
    }

    public boolean isAccountLocked(String studentCode) {
        EntityManager em = entityManagers.get();
        try {
            return em.createQuery("select s.accountLocked from Student s where s.studentCode = :code", Boolean.class)
                    .setParameter("code", studentCode).getResultStream().findFirst().orElse(true);
        } finally { em.close(); }
    }

    public void setAccountLocked(String studentCode, boolean locked, String reason) {
        if (studentCode == null || studentCode.isBlank()) throw new IllegalArgumentException("Chọn tài khoản sinh viên.");
        if (locked && (reason == null || reason.isBlank() || reason.strip().length() > 500))
            throw new IllegalArgumentException("Lý do khóa phải có từ 1 đến 500 ký tự.");
        EntityManager em = entityManagers.get();
        try {
            em.getTransaction().begin();
            Student student = em.createQuery("select s from Student s where s.studentCode = :code", Student.class)
                    .setParameter("code", studentCode.strip().toUpperCase(Locale.ROOT))
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản sinh viên."));
            student.setAccountLock(locked, locked ? reason.strip() : null);
            em.getTransaction().commit();
        } catch (RuntimeException ex) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw ex;
        } finally { em.close(); }
    }

    public List<LockedAccountInfo> listLockedAccounts() {
        EntityManager em = entityManagers.get();
        try {
            return em.createQuery("select s from Student s where s.accountLocked = true order by s.studentCode", Student.class)
                    .getResultList().stream().map(s -> new LockedAccountInfo(s.getStudentCode(), s.getFullName(), s.getLockReason())).toList();
        } finally { em.close(); }
    }
}
