package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.Teacher;
import com.ptit.lanexam.server.persistence.JpaUtil;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public class TeacherAccountService {
    private final Supplier<EntityManager> managers;
    public TeacherAccountService() { this(JpaUtil::em); }
    public TeacherAccountService(Supplier<EntityManager> managers) { this.managers = managers; }

    public TeacherIdentity create(String username, String fullName, String password) {
        String login = normalize(username);
        if (!login.matches("[a-z0-9][a-z0-9._-]{2,49}"))
            throw new IllegalArgumentException("Tên đăng nhập gồm 3–50 chữ không dấu, số, dấu chấm, gạch dưới hoặc gạch ngang.");
        if (fullName == null || fullName.isBlank() || fullName.strip().length() > 120)
            throw new IllegalArgumentException("Nhập họ tên, tối đa 120 ký tự.");
        String hash = TeacherPassword.hash(password);
        try (EntityManager em = managers.get()) {
            try {
                em.getTransaction().begin();
                if (!em.createQuery("select t.id from Teacher t where t.username = :login", Long.class)
                        .setParameter("login", login).getResultList().isEmpty()) throw new IllegalArgumentException("Tên đăng nhập đã tồn tại.");
                Teacher teacher = new Teacher(login, fullName.strip(), hash);
                em.persist(teacher); em.flush();
                // A new teacher starts with an empty workspace. Existing unowned data stays intact.
                TeacherIdentity result = identity(teacher);
                em.getTransaction().commit(); return result;
            } catch (RuntimeException ex) { if (em.getTransaction().isActive()) em.getTransaction().rollback(); throw ex; }
        }
    }

    public Optional<TeacherIdentity> authenticate(String username, String password) {
        if (username == null || username.length() > 50) return Optional.empty();
        try (EntityManager em = managers.get()) {
            Optional<Teacher> found = em.createQuery("select t from Teacher t where t.username = :login", Teacher.class)
                    .setParameter("login", normalize(username)).getResultStream().findFirst();
            if (found.isEmpty() || !TeacherPassword.matches(password, found.get().getPasswordHash())) return Optional.empty();
            Teacher teacher = found.get();
            List<Long> runningOwners = em.createQuery("select distinct e.ownerTeacherId from Exam e "
                            + "where e.active = true and e.deadlineAt > :now", Long.class)
                    .setParameter("now", Instant.now()).getResultList();
            if (runningOwners.stream().anyMatch(owner -> owner != null && !Objects.equals(owner, teacher.getId())))
                throw new IllegalStateException("Ca thi của giáo viên khác đang diễn ra. Hãy chờ hết giờ và chấm xong trước khi đổi giáo viên.");
            List<Long> activeOwners = em.createQuery("select distinct a.exam.ownerTeacherId from ExamAttempt a "
                            + "where a.submittedAt is null and a.deadlineAt > :now", Long.class)
                    .setParameter("now", Instant.now()).getResultList();
            if (activeOwners.stream().anyMatch(owner -> owner != null && !Objects.equals(owner, teacher.getId())))
                throw new IllegalStateException("Còn sinh viên đang làm bài của giáo viên khác. Hãy đăng nhập lại giáo viên phụ trách hoặc chờ hết giờ.");
            return Optional.of(identity(teacher));
        }
    }

    public void changePassword(Long id, String currentPassword, String newPassword) {
        TeacherPassword.validate(newPassword);
        try (EntityManager em = managers.get()) {
            try {
                em.getTransaction().begin();
                Teacher teacher = id == null ? null : em.find(Teacher.class, id, LockModeType.PESSIMISTIC_WRITE);
                if (teacher == null || !TeacherPassword.matches(currentPassword, teacher.getPasswordHash()))
                    throw new IllegalArgumentException("Mật khẩu hiện tại không đúng.");
                teacher.setPasswordHash(TeacherPassword.hash(newPassword));
                em.getTransaction().commit();
            } catch (RuntimeException ex) { if (em.getTransaction().isActive()) em.getTransaction().rollback(); throw ex; }
        }
    }
    private static String normalize(String username) { return username == null ? "" : username.strip().toLowerCase(Locale.ROOT); }
    private static TeacherIdentity identity(Teacher teacher) { return new TeacherIdentity(teacher.getId(), teacher.getUsername(), teacher.getFullName()); }
}
