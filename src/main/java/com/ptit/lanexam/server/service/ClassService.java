package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.*;
import jakarta.persistence.*;
import java.util.*;
import java.util.function.Supplier;

public final class ClassService {
    private final Supplier<EntityManager> managers;
    private final TeacherScope scope;
    public ClassService(Supplier<EntityManager> managers, Long teacherId) {
        this.managers = managers; scope = new TeacherScope(teacherId);
    }
    public record RosterStudent(String code, String fullName, java.time.LocalDate birthDate, String regularClass, Integer ordinal) {
        public RosterStudent(String code, String fullName, java.time.LocalDate birthDate) { this(code, fullName, birthDate, null, null); }
        public RosterStudent(String code, String fullName, java.time.LocalDate birthDate, String regularClass) { this(code, fullName, birthDate, regularClass, null); }
    }
    public enum ImportStatus {
        CREATE("Tạo sinh viên và thêm vào lớp"), ENROLL("Thêm sinh viên đã có vào lớp"),
        UPDATE("Cập nhật STT trong lớp"), EXISTS("Đã có trong lớp · bỏ qua");
        private final String label;
        ImportStatus(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public record ImportRow(RosterCsv.StudentRow student, ImportStatus status, RosterStudent storedProfile, boolean profileDiffers) {
        public RosterStudent profile() {
            return storedProfile != null ? storedProfile : new RosterStudent(student.code(), student.fullName(), student.birthDate(), student.regularClass(), student.ordinal());
        }
        public Integer ordinal() { return student.ordinal() != null ? student.ordinal() : storedProfile == null ? null : storedProfile.ordinal(); }
        public String actionLabel() { return status + (profileDiffers ? " · Giữ hồ sơ đã lưu (CSV khác)" : ""); }
    }
    public record ImportPreview(List<ImportRow> rows) {
        public ImportPreview { rows = List.copyOf(rows); }
        public long profileDifferences() { return rows.stream().filter(ImportRow::profileDiffers).count(); }
        public long count(ImportStatus status) { return rows.stream().filter(r -> r.status() == status).count(); }
        public String summary() { return count(ImportStatus.CREATE) + " sinh viên mới · " + count(ImportStatus.ENROLL)
                + " thêm vào lớp · " + count(ImportStatus.UPDATE) + " cập nhật STT · " + count(ImportStatus.EXISTS) + " đã có"
                + (profileDifferences() == 0 ? "" : " · " + profileDifferences() + " khác CSV — giữ hồ sơ đã lưu"); }
    }
    public ClassInfo create(String code, String name, String semester, Long subjectId) {
        code = required(code, 40, "Mã lớp").toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9][A-Z0-9._-]*")) throw new IllegalArgumentException("Mã lớp dùng chữ không dấu, số, . _ -");
        name = required(name, 160, "Tên lớp"); semester = required(semester, 80, "Học kỳ");
        try (EntityManager em = managers.get()) {
            try {
                em.getTransaction().begin();
                if (scope.teacherId() != null) em.find(Teacher.class, scope.teacherId(), LockModeType.PESSIMISTIC_WRITE);
                Subject subject = subjectId == null ? null : em.find(Subject.class, subjectId);
                scope.require(subject);
                if (em.createQuery("select count(c) from CourseClass c where " + scope.predicate("c") + " and c.code = :code", Long.class)
                        .setParameter("code", code).getSingleResult() > 0) throw new IllegalArgumentException("Mã lớp đã tồn tại. Dùng mã riêng cho từng học kỳ.");
                CourseClass c = new CourseClass(code, name, semester, subject);
                scope.assign(c); em.persist(c); em.flush();
                ClassInfo result = info(c, 0);
                em.getTransaction().commit(); return result;
            } catch (RuntimeException ex) { if (em.getTransaction().isActive()) em.getTransaction().rollback(); throw ex; }
        }
    }
    public List<ClassInfo> list() {
        try (EntityManager em = managers.get()) {
            return em.createQuery("select c, (select count(cs) from ClassStudent cs where cs.courseClass = c) "
                    + "from CourseClass c join fetch c.subject where " + scope.predicate("c") + " order by c.id desc", Object[].class)
                    .getResultList().stream().map(row -> info((CourseClass) row[0], (Long) row[1])).toList();
        }
    }
    public List<RosterStudent> roster(Long classId) {
        try (EntityManager em = managers.get()) {
            requireClass(em, classId, scope);
            return em.createQuery("select cs from ClassStudent cs join fetch cs.student where cs.courseClass.id = :id order by cs.ordinal asc nulls last, cs.student.studentCode", ClassStudent.class)
                    .setParameter("id", classId).getResultList().stream()
                    .map(cs -> rosterStudent(cs.getStudent(), cs.getOrdinal())).toList();
        }
    }
    static RosterStudent rosterStudent(Student s, Integer ordinal) {
        return new RosterStudent(s.getStudentCode(), s.getFullName(), s.getBirthDate(), s.getRegularClass(), ordinal);
    }
    public List<com.ptit.lanexam.common.dto.StudentClassInfo> listForStudent(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Bạn chưa đăng nhập.");
        try (EntityManager em = managers.get()) {
            return em.createQuery("select c from ClassStudent cs join cs.courseClass c join fetch c.subject "
                    + "where cs.student.studentCode = :code and " + scope.predicate("cs.courseClass") + " order by cs.courseClass.id desc", CourseClass.class)
                    .setParameter("code", code).getResultList().stream().map(c -> new com.ptit.lanexam.common.dto.StudentClassInfo(
                            c.getId(), c.getCode(), c.getName(), c.getSubject().getName(), c.getSemester())).toList();
        }
    }
    public ImportPreview preview(Long classId, List<RosterCsv.StudentRow> rows) {
        validate(rows);
        try (EntityManager em = managers.get()) {
            requireClass(em, classId, scope);
            return inspect(em, classId, rows);
        }
    }
    public ImportPreview importRoster(Long classId, List<RosterCsv.StudentRow> rows) {
        validate(rows);
        try (EntityManager em = managers.get()) {
            try {
                em.getTransaction().begin();
                CourseClass c = classId == null ? null : em.find(CourseClass.class, classId, LockModeType.PESSIMISTIC_WRITE);
                scope.require(c);
                ImportPreview preview = inspect(em, classId, rows);
                for (ImportRow row : preview.rows()) {
                    if (row.status() == ImportStatus.EXISTS) continue;
                    RosterCsv.StudentRow input = row.student();
                    Student student = em.createQuery("select s from Student s where upper(s.studentCode) = :code", Student.class)
                            .setParameter("code", input.code()).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                            .getResultStream().findFirst().orElse(null);
                    if (student == null) { student = new Student(input.code(), input.fullName(), input.birthDate(), input.regularClass()); em.persist(student); }
                    ClassStudent enrollment;
                    if (row.status() == ImportStatus.UPDATE) {
                        enrollment = em.createQuery("select cs from ClassStudent cs where cs.courseClass = :class and cs.student = :student", ClassStudent.class)
                                .setParameter("class", c).setParameter("student", student).getSingleResult();
                    } else { enrollment = new ClassStudent(c, student); em.persist(enrollment); }
                    if (input.ordinal() != null) enrollment.setOrdinal(input.ordinal());
                }
                em.getTransaction().commit(); return preview;
            } catch (RuntimeException ex) { if (em.getTransaction().isActive()) em.getTransaction().rollback(); throw ex; }
        }
    }
    private ImportPreview inspect(EntityManager em, Long classId, List<RosterCsv.StudentRow> rows) {
        Map<String, Student> students = new HashMap<>();
        em.createQuery("select s from Student s where upper(s.studentCode) in :codes", Student.class)
                .setParameter("codes", rows.stream().map(RosterCsv.StudentRow::code).toList())
                .getResultList().forEach(s -> students.put(s.getStudentCode().toUpperCase(Locale.ROOT), s));
        Map<String, ClassStudent> enrolled = new HashMap<>();
        em.createQuery("select cs from ClassStudent cs join fetch cs.student where cs.courseClass.id = :id", ClassStudent.class)
                .setParameter("id", classId).getResultList().forEach(cs -> enrolled.put(cs.getStudent().getStudentCode().toUpperCase(Locale.ROOT), cs));
        Map<String, Integer> finalOrdinals = new HashMap<>();
        enrolled.forEach((code, cs) -> finalOrdinals.put(code, cs.getOrdinal()));
        for (var row : rows) if (row.ordinal() != null) finalOrdinals.put(row.code(), row.ordinal());
        Set<Integer> used = new HashSet<>();
        for (Integer ordinal : finalOrdinals.values()) if (ordinal != null && !used.add(ordinal))
            throw new IllegalArgumentException("STT " + ordinal + " bị trùng trong danh sách lớp sau khi nhập. Sửa STT trên CSV trước khi nhập lại.");
        return new ImportPreview(rows.stream().map(row -> {
            Student s = students.get(row.code());
            if (s == null) return new ImportRow(row, ImportStatus.CREATE, null, false);
            RosterStudent stored = rosterStudent(s, enrolled.containsKey(row.code()) ? enrolled.get(row.code()).getOrdinal() : null);
            if (!enrolled.containsKey(row.code())) return new ImportRow(row, ImportStatus.ENROLL, stored, !same(s, row));
            boolean changedOrdinal = row.ordinal() != null && !Objects.equals(enrolled.get(row.code()).getOrdinal(), row.ordinal());
            return new ImportRow(row, changedOrdinal ? ImportStatus.UPDATE : ImportStatus.EXISTS, stored, !same(s, row));
        }).toList());
    }
    static CourseClass requireClass(EntityManager em, Long id, TeacherScope scope) {
        CourseClass c = id == null ? null : em.find(CourseClass.class, id);
        scope.require(c); return c;
    }
    static boolean eligible(EntityManager em, Long classId, String code, TeacherScope scope) {
        // Null classes only remain usable in the unowned legacy/demo service, never in a teacher workspace.
        if (classId == null) return scope.teacherId() == null;
        return em.createQuery("select count(cs) from ClassStudent cs where cs.courseClass.id = :id "
                        + "and cs.student.studentCode = :code and " + scope.predicate("cs.courseClass"), Long.class)
                .setParameter("id", classId).setParameter("code", code).getSingleResult() > 0;
    }
    static void requireEnrollment(EntityManager em, Long classId, String code, TeacherScope scope) {
        if (!eligible(em, classId, code, scope)) throw new IllegalArgumentException("Bạn không có tên trong lớp được giao bài kiểm tra này.");
    }
    private static boolean same(Student s, RosterCsv.StudentRow row) {
        return s.getBirthDate().equals(row.birthDate()) && normalized(s.getFullName()).equals(normalized(row.fullName()))
                && (row.regularClass() == null || s.getRegularClass() != null
                    && normalized(s.getRegularClass()).equals(normalized(row.regularClass())));
    }
    private static String normalized(String name) { return java.text.Normalizer.normalize(name.strip().replaceAll("\\s+", " "), java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    private static String required(String text, int max, String name) {
        if (text == null || text.isBlank() || text.strip().length() > max || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(name + " từ 1–" + max + " ký tự.");
        return text.strip();
    }
    private static void validate(List<RosterCsv.StudentRow> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > RosterCsv.MAX_ROWS) throw new IllegalArgumentException("Danh sách phải có 1–2.000 sinh viên.");
        Set<String> codes = new HashSet<>();
        for (var row : rows) {
            if (row == null || row.code() == null || !row.code().matches("[A-Z0-9][A-Z0-9._-]{0,29}") || !codes.add(row.code()))
                throw new IllegalArgumentException("Mã sinh viên trùng hoặc không hợp lệ.");
            required(row.fullName(), 120, "Họ tên");
            if (row.ordinal() != null && row.ordinal() <= 0) throw new IllegalArgumentException("STT phải là số nguyên dương.");
            if (row.regularClass() != null) required(row.regularClass(), 80, "Lớp chính quy");
            if (row.birthDate() == null || row.birthDate().isAfter(java.time.LocalDate.now()) || row.birthDate().getYear() < 1900)
                throw new IllegalArgumentException("Ngày sinh không hợp lệ.");
        }
    }
    private static ClassInfo info(CourseClass c, long count) { return new ClassInfo(c.getId(), c.getCode(), c.getName(), c.getSemester(), c.getSubject().getId(), c.getSubject().getName(), count); }
}
