package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.Student;
import com.ptit.lanexam.server.entity.Teacher;
import com.ptit.lanexam.server.entity.TeacherStudentLock;
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
    private final Long teacherId;

    public AuthService() { this(JpaUtil::em); }
    // Unscoped construction only serves the old, unowned data workflow.
    public AuthService(Supplier<EntityManager> entityManagers) { this(entityManagers, null); }
    public AuthService(Supplier<EntityManager> entityManagers, Long teacherId) {
        if (teacherId != null && teacherId <= 0) throw new IllegalArgumentException("Mã giảng viên không hợp lệ.");
        this.entityManagers = entityManagers; this.teacherId = teacherId;
    }

    private boolean manages(EntityManager em, String code) {
        return teacherId == null || em.createQuery("select count(cs) from ClassStudent cs where cs.student.studentCode = :code and cs.courseClass.ownerTeacherId = :teacher", Long.class)
                .setParameter("code", code).setParameter("teacher", teacherId).getSingleResult() > 0;
    }
    private Optional<TeacherStudentLock> teacherLock(EntityManager em, String code) {
        return em.createQuery("select l from TeacherStudentLock l where l.teacher.id = :teacher and l.student.studentCode = :code", TeacherStudentLock.class)
                .setParameter("teacher", teacherId).setParameter("code", code).getResultStream().findFirst();
    }
    public Optional<String> accountLockReason(String code) {
        try (EntityManager em = entityManagers.get()) {
            if (!manages(em, code)) return Optional.empty();
            if (teacherId != null) return teacherLock(em, code).map(TeacherStudentLock::getReason);
            return em.createQuery("select s.lockReason from Student s where s.studentCode = :code and s.accountLocked = true", String.class)
                    .setParameter("code", code).getResultStream().filter(java.util.Objects::nonNull).findFirst();
        }
    }

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
            var found = em.createQuery(
                            "select s from Student s where upper(s.studentCode) = :code and s.birthDate = :birthDate",
                            Student.class)
                    .setParameter("code", studentCode.trim().toUpperCase(Locale.ROOT))
                    .setParameter("birthDate", birthDate)
                    .getResultStream()
                    .findFirst();
            return found.filter(s -> manages(em, s.getStudentCode()));
        } finally {
            em.close();
        }
    }

    public boolean isAccountLocked(String studentCode) {
        EntityManager em = entityManagers.get();
        try {
            if (!manages(em, studentCode)) return true;
            if (teacherId != null) return teacherLock(em, studentCode).isPresent();
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
            if (!manages(em, student.getStudentCode()))
                throw new IllegalArgumentException("Bạn chỉ được khóa hoặc mở khóa sinh viên thuộc lớp học phần của mình.");
            if (teacherId == null) student.setAccountLock(locked, locked ? reason.strip() : null);
            else {
                var existing = teacherLock(em, student.getStudentCode()).orElse(null);
                if (!locked && existing != null) em.remove(existing);
                else if (locked && existing != null) existing.setReason(reason.strip());
                else if (locked) em.persist(new TeacherStudentLock(em.getReference(Teacher.class, teacherId), student, reason.strip()));
            }
            em.getTransaction().commit();
        } catch (RuntimeException ex) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw ex;
        } finally { em.close(); }
    }

    public List<LockedAccountInfo> listLockedAccounts() {
        EntityManager em = entityManagers.get();
        try {
            if (teacherId != null) return em.createQuery("select l from TeacherStudentLock l join fetch l.student s where l.teacher.id = :teacher "
                            + "and exists (select cs from ClassStudent cs where cs.student = s and cs.courseClass.ownerTeacherId = :teacher) order by s.studentCode", TeacherStudentLock.class)
                    .setParameter("teacher", teacherId).getResultList().stream()
                    .map(l -> new LockedAccountInfo(l.getStudent().getStudentCode(), l.getStudent().getFullName(), l.getReason())).toList();
            return em.createQuery("select s from Student s where s.accountLocked = true order by s.studentCode", Student.class)
                    .getResultList().stream().map(s -> new LockedAccountInfo(s.getStudentCode(), s.getFullName(), s.getLockReason())).toList();
        } finally { em.close(); }
    }
}
