package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Persisted, class-specific exam rosters ordered by the original CSV ordinals. */
public final class ExamShiftService {
    private final Supplier<EntityManager> managers;
    private final TeacherScope scope;
    private final Clock clock;

    public ExamShiftService(Supplier<EntityManager> managers, Long teacherId) { this(managers, teacherId, Clock.systemUTC()); }
    public ExamShiftService(Supplier<EntityManager> managers, Long teacherId, Clock clock) {
        this.managers = managers; this.scope = new TeacherScope(teacherId); this.clock = clock;
    }
    public record ShiftInfo(Long id, int number, int studentCount, int firstOrdinal, int lastOrdinal,
                            Long roundId, Instant deadlineAt) {
        public String state(Instant now) { return roundId == null ? "Chưa thi" : deadlineAt != null && now.isBefore(deadlineAt) ? "Đang thi" : "Đã kết thúc"; }
        @Override public String toString() { return "Ca " + number + " · " + studentCount + " SV · STT " + firstOrdinal + "–" + lastOrdinal; }
    }
    public List<ShiftInfo> list(Long classId) {
        try (EntityManager em = managers.get()) {
            ClassService.requireClass(em, classId, scope);
            return info(em, latest(em, classId));
        }
    }
    public List<ClassService.RosterStudent> roster(Long classId, Long shiftId) {
        try (EntityManager em = managers.get()) {
            ExamShift shift = require(em, classId, shiftId, scope);
            return shift.getCandidates().stream().map(c -> ClassService.rosterStudent(c.getStudent(), c.getOrdinal())).toList();
        }
    }
    public List<ShiftInfo> split(Long classId, int capacity) {
        if (capacity < 1 || capacity > RosterCsv.MAX_ROWS) throw new IllegalArgumentException("Số sinh viên mỗi ca từ 1–2.000.");
        try (EntityManager em = managers.get()) {
            try {
                em.getTransaction().begin();
                // Match activation's lock order so two Server windows cannot replace a starting shift.
                if (scope.teacherId() != null) em.find(Teacher.class, scope.teacherId(), LockModeType.PESSIMISTIC_WRITE);
                CourseClass group = classId == null ? null : em.find(CourseClass.class, classId, LockModeType.PESSIMISTIC_WRITE);
                scope.require(group);
                long live = em.createQuery("select count(e) from Exam e where e.courseClass = :class and e.active = true and e.deadlineAt > :now", Long.class)
                        .setParameter("class", group).setParameter("now", clock.instant()).getSingleResult();
                if (live > 0) throw new IllegalStateException("Lớp đang có ca thi. Chờ kết thúc trước khi chia lại.");
                List<ExamShift> previous = latest(em, classId);
                List<ShiftInfo> oldInfo = info(em, previous);
                boolean started = oldInfo.stream().anyMatch(s -> s.roundId() != null);
                if (started && oldInfo.stream().anyMatch(s -> s.roundId() == null))
                    throw new IllegalStateException("Đợt thi đã bắt đầu và còn ca chưa thi. Hoàn tất các ca trước khi tạo đợt mới.");
                List<ClassStudent> students = em.createQuery("select cs from ClassStudent cs join fetch cs.student where cs.courseClass = :class order by cs.ordinal", ClassStudent.class)
                        .setParameter("class", group).getResultList();
                if (students.isEmpty()) throw new IllegalArgumentException("Lớp chưa có sinh viên. Nhập CSV trước khi chia ca.");
                Set<Integer> ordinals = new HashSet<>();
                for (var student : students) {
                    Integer ordinal = student.getOrdinal();
                    if (ordinal == null || ordinal <= 0 || !ordinals.add(ordinal))
                        throw new IllegalArgumentException("Chia ca cần STT nguyên dương, không trùng cho mọi sinh viên. Nhập lại CSV có cột STT; hệ thống không tự đánh lại số.");
                }
                if (!started) previous.forEach(em::remove);
                String batch = UUID.randomUUID().toString();
                List<ExamShift> created = new ArrayList<>();
                for (int from = 0; from < students.size(); from += capacity) {
                    ExamShift shift = new ExamShift(group, batch, created.size() + 1);
                    for (var enrollment : students.subList(from, Math.min(from + capacity, students.size())))
                        shift.getCandidates().add(new ExamShift.Candidate(enrollment.getStudent(), enrollment.getOrdinal()));
                    em.persist(shift); created.add(shift);
                }
                em.flush(); List<ShiftInfo> result = info(em, created);
                em.getTransaction().commit(); return result;
            } catch (RuntimeException ex) { if (em.getTransaction().isActive()) em.getTransaction().rollback(); throw ex; }
        }
    }
    static List<ExamShift> latest(EntityManager em, Long classId) {
        ExamShift last = em.createQuery("select s from ExamShift s where s.courseClass.id = :id order by s.id desc", ExamShift.class)
                .setParameter("id", classId).setMaxResults(1).getResultStream().findFirst().orElse(null);
        return last == null ? List.of() : em.createQuery("select s from ExamShift s where s.courseClass.id = :id and s.batchKey = :batch order by s.number", ExamShift.class)
                .setParameter("id", classId).setParameter("batch", last.getBatchKey()).getResultList();
    }
    static ExamShift require(EntityManager em, Long classId, Long shiftId, TeacherScope scope) {
        ClassService.requireClass(em, classId, scope);
        ExamShift shift = shiftId == null ? null : em.find(ExamShift.class, shiftId);
        if (shift == null || !Objects.equals(classId, shift.getCourseClass().getId()))
            throw new IllegalArgumentException("Ca thi không thuộc lớp đã chọn.");
        return shift;
    }
    static boolean eligible(EntityManager em, Exam exam, String code) {
        if (exam.getShift() == null) return true;
        return em.createQuery("select count(s) from ExamShift s join s.candidates c where s.id = :id and c.student.studentCode = :code", Long.class)
                .setParameter("id", exam.getShift().getId()).setParameter("code", code).getSingleResult() > 0;
    }
    private List<ShiftInfo> info(EntityManager em, List<ExamShift> shifts) {
        if (shifts.isEmpty()) return List.of();
        Map<Long, Exam> rounds = new HashMap<>();
        em.createQuery("select e from Exam e where e.shift in :shifts", Exam.class).setParameter("shifts", shifts)
                .getResultList().forEach(e -> rounds.put(e.getShift().getId(), e));
        return shifts.stream().map(s -> {
            var candidates = s.getCandidates(); Exam round = rounds.get(s.getId());
            return new ShiftInfo(s.getId(), s.getNumber(), candidates.size(), candidates.getFirst().getOrdinal(), candidates.getLast().getOrdinal(),
                    round == null ? null : round.getId(), round == null ? null : round.getDeadlineAt());
        }).toList();
    }
}
