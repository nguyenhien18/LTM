package com.ptit.lanexam.server.service;

import com.ptit.lanexam.common.dto.ExamWarningStatus;
import com.ptit.lanexam.common.dto.ExamFocusChange;
import com.ptit.lanexam.server.entity.*;
import com.ptit.lanexam.server.entity.ExamEvent.Kind;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Observations are reviewed by the teacher; they never change marks or account locks. */
public class ExamEventService {
    private final Supplier<EntityManager> managers;
    private final TeacherScope scope;
    private final Clock clock;
    public record Summary(long count, boolean needsReview, long interruptions) {
        public String label() { return count + " lần" + (needsReview ? " · Cần kiểm tra" : ""); }
    }
    public record Entry(Long id, Instant at, Kind kind, String detail, boolean reviewed) {}
    public record Notice(Long roundId, String studentCode, String fullName, String classLabel, String roundLabel,
                         long count, long unreviewed, Instant updatedAt, Long firstUnreviewedId) {
        public boolean pending() { return unreviewed > 0; }
        public String token() { return roundId + ":" + studentCode + ":" + firstUnreviewedId; }
        public String state() { return pending() ? "Chưa xử lý" : "Đã xử lý"; }
    }
    public ExamEventService(Supplier<EntityManager> managers, Long teacherId, Clock clock) {
        this.managers = managers; scope = new TeacherScope(teacherId); this.clock = clock;
    }
    private Exam round(EntityManager em, Long id) {
        if (id == null) throw new IllegalArgumentException("Chưa chọn ca thi.");
        Exam round = em.find(Exam.class, id); scope.require(round);
        if (round.getTemplateExamId() == null) throw new IllegalArgumentException("Chọn lượt thi đã mở.");
        return round;
    }
    private ExamAttempt attempt(EntityManager em, Long roundId, String code) {
        return em.createQuery("select a from ExamAttempt a where a.exam.id = :round and a.student.studentCode = :code", ExamAttempt.class)
                .setParameter("round", roundId).setParameter("code", code).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst().orElseThrow(() -> new IllegalArgumentException("Sinh viên chưa vào ca thi này."));
    }
    public ExamWarningStatus focusReturned(Long roundId, String code, String episodeId, Instant left, Instant returned) {
        return focusObserved(roundId, code, episodeId, left, returned, true);
    }
    public ExamWarningStatus focusStillAway(Long roundId, String code, String episodeId, Instant left, Instant observed) {
        return focusObserved(roundId, code, episodeId, left, observed, false);
    }
    private ExamWarningStatus focusObserved(Long roundId, String code, String episodeId, Instant left, Instant returned, boolean complete) {
        UUID.fromString(episodeId);
        long millis = Duration.between(left, returned).toMillis();
        if (millis < ExamFocusChange.AWAY_THRESHOLD_MILLIS) return status(roundId, code, "");
        boolean[] inserted = {false};
        write(em -> {
            Exam round = round(em, roundId);
            ExamAttempt attempt = attempt(em, roundId, code);
            if (!attempt.isOpenAt(returned) || !round.isActive() || !returned.isBefore(round.getDeadlineAt())) return;
            var duplicate = em.createQuery("select e from ExamEvent e where e.exam.id = :round and e.student.studentCode = :code and e.eventKey = :key", ExamEvent.class)
                    .setParameter("round", roundId).setParameter("code", code).setParameter("key", episodeId).getResultStream().findFirst();
            String detail = "Rời cửa sổ thi " + (complete ? "" : "ít nhất ") + (millis / 1000) + " giây.";
            if (duplicate.isEmpty()) {
                em.persist(new ExamEvent(round, attempt.getStudent(), episodeId, Kind.FOCUS_LOST, returned,
                        detail));
                inserted[0] = true;
            } else if (complete) duplicate.get().setDetail(detail);
        });
        var state = status(roundId, code, "");
        if (!inserted[0]) return state;
        String message = state.needsReview() ? "Bạn đã rời cửa sổ thi " + state.count() + " lần. Giảng viên sẽ kiểm tra nhật ký."
                : "Bạn vừa rời cửa sổ thi. Vui lòng tập trung làm bài. Lần " + state.count() + ".";
        return new ExamWarningStatus(roundId, state.count(), state.needsReview(), message);
    }
    public void connection(Long roundId, String code, boolean connected) {
        write(em -> {
            Exam round = round(em, roundId); ExamAttempt attempt = attempt(em, roundId, code);
            if (!attempt.isOpenAt(clock.instant())) return;
            if (connected && em.createQuery("select count(e) from ExamEvent e where e.exam = :round and e.student = :student and e.kind = :kind", Long.class)
                    .setParameter("round", round).setParameter("student", attempt.getStudent()).setParameter("kind", Kind.DISCONNECTED).getSingleResult() == 0) return;
            em.persist(new ExamEvent(round, attempt.getStudent(), UUID.randomUUID().toString(), connected ? Kind.RECONNECTED : Kind.DISCONNECTED,
                    clock.instant(), connected ? "Đăng nhập lại sau gián đoạn kết nối; không tính vi phạm." : "Kết nối Client kết thúc hoặc bị gián đoạn; không tính vi phạm."));
        });
    }
    public void reminder(Long roundId, String code, String text) {
        if (text == null || text.isBlank() || text.strip().length() > 1000) throw new IllegalArgumentException("Nhắc nhở từ 1–1.000 ký tự.");
        write(em -> {
            Exam round = round(em, roundId); ExamAttempt attempt = attempt(em, roundId, code);
            em.persist(new ExamEvent(round, attempt.getStudent(), UUID.randomUUID().toString(), Kind.MANUAL_WARNING, clock.instant(), text.strip()));
            markReviewed(em, roundId, code);
        });
    }
    public void review(Long roundId, String code) {
        write(em -> {
            Exam round = round(em, roundId); ExamAttempt attempt = attempt(em, roundId, code);
            if (!markReviewed(em, roundId, code)) return;
            em.persist(new ExamEvent(round, attempt.getStudent(), UUID.randomUUID().toString(), Kind.REVIEWED, clock.instant(), "Giảng viên đã kiểm tra các cảnh báo trước thời điểm này."));
        });
    }
    private boolean markReviewed(EntityManager em, Long roundId, String code) {
        boolean changed = false;
        for (var event : entries(em, roundId, code)) if (event.getKind() == Kind.FOCUS_LOST && event.getReviewedAt() == null) {
            event.review(clock.instant()); changed = true;
        }
        return changed;
    }
    void recordDisqualification(EntityManager em, ExamAttempt attempt, String reason) {
        markReviewed(em, attempt.getExam().getId(), attempt.getStudent().getStudentCode());
        em.persist(new ExamEvent(attempt.getExam(), attempt.getStudent(), UUID.randomUUID().toString(), Kind.DISQUALIFIED, clock.instant(), reason));
    }
    /** One aggregate per student/round. Opening a journal is not a review action. */
    public List<Notice> notices() {
        try (var em = managers.get()) {
            var events = em.createQuery("select e from ExamEvent e join fetch e.student join fetch e.exam x "
                            + "left join fetch x.courseClass left join fetch x.shift where e.kind = :kind and "
                            + scope.predicate("x") + " order by e.id", ExamEvent.class)
                    .setParameter("kind", Kind.FOCUS_LOST).getResultList();
            record Key(Long round, String code) {}
            Map<Key, List<ExamEvent>> groups = new LinkedHashMap<>();
            for (var event : events) groups.computeIfAbsent(new Key(event.getExam().getId(), event.getStudent().getStudentCode()), ignored -> new ArrayList<>()).add(event);
            List<Notice> notices = new ArrayList<>();
            for (var entry : groups.entrySet()) {
                var rows = entry.getValue();
                var first = rows.getFirst(); var round = first.getExam();
                var unreviewed = rows.stream().filter(e -> e.getReviewedAt() == null).toList();
                notices.add(new Notice(round.getId(), first.getStudent().getStudentCode(), first.getStudent().getFullName(),
                        round.getCourseClass() == null ? "Lượt thi cũ" : round.getCourseClass().displayName(),
                        round.getShift() == null ? "Lượt #" + round.getId() : "Ca " + round.getShift().getNumber() + " · #" + round.getId(),
                        rows.size(), unreviewed.size(), rows.getLast().getOccurredAt(), unreviewed.isEmpty() ? null : unreviewed.getFirst().getId()));
            }
            notices.sort(Comparator.comparing(Notice::pending).reversed().thenComparing(Notice::updatedAt, Comparator.reverseOrder()));
            return List.copyOf(notices);
        }
    }
    public List<Entry> list(Long roundId, String code) {
        try (var em = managers.get()) {
            round(em, roundId);
            return entries(em, roundId, code).stream().map(e -> new Entry(e.getId(), e.getOccurredAt(), e.getKind(), e.getDetail(), e.getReviewedAt() != null)).toList();
        }
    }
    private List<ExamEvent> entries(EntityManager em, Long roundId, String code) {
        return em.createQuery("select e from ExamEvent e where e.exam.id = :round and e.student.studentCode = :code order by e.id", ExamEvent.class)
                .setParameter("round", roundId).setParameter("code", code).getResultList();
    }
    public Map<String, Summary> summaries(Long roundId) {
        try (var em = managers.get()) {
            round(em, roundId);
            var rows = em.createQuery("select e from ExamEvent e join fetch e.student where e.exam.id = :round order by e.id", ExamEvent.class)
                    .setParameter("round", roundId).getResultList();
            Map<String, long[]> grouped = new HashMap<>();
            for (var e : rows) {
                long[] counts = grouped.computeIfAbsent(e.getStudent().getStudentCode(), ignored -> new long[3]);
                if (e.getKind() == Kind.FOCUS_LOST) { counts[0]++; if (e.getReviewedAt() == null) counts[1]++; }
                if (e.getKind() == Kind.DISCONNECTED) counts[2]++;
            }
            Map<String, Summary> result = new HashMap<>();
            grouped.forEach((code, counts) -> result.put(code, new Summary(counts[0], counts[1] > 0, counts[2])));
            return Map.copyOf(result);
        }
    }
    public ExamWarningStatus status(Long roundId, String code, String message) {
        Summary summary = summaries(roundId).getOrDefault(code, new Summary(0, false, 0));
        return new ExamWarningStatus(roundId, summary.count(), summary.needsReview(), message);
    }
    private void write(Consumer<EntityManager> action) {
        try (var em = managers.get()) {
            var tx = em.getTransaction();
            try { tx.begin(); action.accept(em); tx.commit(); }
            catch (RuntimeException ex) { if (tx.isActive()) tx.rollback(); throw ex; }
        }
    }
}
