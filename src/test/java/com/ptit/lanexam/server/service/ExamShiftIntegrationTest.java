package com.ptit.lanexam.server.service;

import com.ptit.lanexam.common.dto.SubmitExamRequest;
import com.ptit.lanexam.server.entity.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class ExamShiftIntegrationTest {
    private EntityManagerFactory factory;
    private ClassService classes;
    private ExamShiftService shifts;
    private ExamService exams;
    private Long teacherId, otherId, classId, secondClass, templateId;
    private final MutableClock clock = new MutableClock();

    @BeforeEach void setup() {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:shift_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            Teacher a = new Teacher("gv01", "Một", "unused"), b = new Teacher("gv02", "Hai", "unused");
            em.persist(a); em.persist(b); em.flush(); teacherId = a.getId(); otherId = b.getId();
            Subject subject = new Subject("Mạng", "mang"); subject.setOwnerTeacherId(teacherId); em.persist(subject);
            Exam exam = new Exam("Kiểm tra", 1, false); exam.setOwnerTeacherId(teacherId); exam.setSubject(subject);
            exam.addQuestion(new Question("Giao thức tin cậy?", QuestionType.MULTIPLE_CHOICE, "TCP", "UDP", "IP", "ICMP", "A"));
            em.persist(exam); em.flush(); templateId = exam.getId();
            classes = new ClassService(factory::createEntityManager, teacherId);
            em.getTransaction().commit();
            classId = classes.create("LTM-A", "Nhóm A", "HK1", subject.getId()).id();
            secondClass = classes.create("LTM-B", "Nhóm B", "HK1", subject.getId()).id();
        }
        shifts = new ExamShiftService(factory::createEntityManager, teacherId, clock);
        exams = new ExamService(factory::createEntityManager, teacherId, clock);
    }
    @AfterEach void close() { factory.close(); }

    private static String csv(int... ordinals) {
        StringBuilder text = new StringBuilder("STT,MSV,Họ và tên,Ngày sinh,Lớp\n");
        for (int n : ordinals) text.append(n).append(",SV%03d,Sinh viên %d,07/11/2005,D23CNPM01\n".formatted(n, n));
        return text.toString();
    }
    private void importCsv(String text) { classes.importRoster(classId, RosterCsv.parse(text)); }
    private List<Integer> ordinals(Long id) { return classes.roster(id).stream().map(ClassService.RosterStudent::ordinal).toList(); }

    @Test void sixtyShuffledRowsSplitThirtyThirtyAndSurviveServiceReload() {
        int[] reversed = IntStream.rangeClosed(1, 60).map(n -> 61 - n).toArray();
        importCsv(csv(reversed));
        var saved = shifts.split(classId, 30);
        assertEquals(List.of(30, 30), saved.stream().map(ExamShiftService.ShiftInfo::studentCount).toList());
        var reopened = new ExamShiftService(factory::createEntityManager, teacherId, clock);
        assertEquals(saved, reopened.list(classId));
        assertEquals(IntStream.rangeClosed(1, 30).boxed().toList(), reopened.roster(classId, saved.getFirst().id()).stream().map(ClassService.RosterStudent::ordinal).toList());
        assertEquals(IntStream.rangeClosed(31, 60).boxed().toList(), reopened.roster(classId, saved.getLast().id()).stream().map(ClassService.RosterStudent::ordinal).toList());
        var all = saved.stream().flatMap(s -> reopened.roster(classId, s.id()).stream()).toList();
        assertEquals(60, all.stream().map(ClassService.RosterStudent::code).distinct().count());
    }
    @Test void exactCsvOrdinalsOverrideFileOrderAndStudentCodeOrderAndAllowGaps() {
        importCsv("STT,MSV,Họ và tên,Ngày sinh,Lớp\n90,AAA,An,07/11/2005,C1\n10,ZZZ,Bình,07/11/2005,C1\n40,BBB,Cường,07/11/2005,C1");
        assertEquals(List.of(10, 40, 90), ordinals(classId));
        var split = shifts.split(classId, 2);
        assertEquals(List.of("ZZZ", "BBB"), shifts.roster(classId, split.getFirst().id()).stream().map(ClassService.RosterStudent::code).toList());
        assertEquals(List.of(90), shifts.roster(classId, split.getLast().id()).stream().map(ClassService.RosterStudent::ordinal).toList());
        assertEquals(1, split.getLast().studentCount());
    }
    @Test void reimportUpdatesOrSwapsOrdinalsOnlyWithinThatCourseClass() {
        importCsv(csv(1, 2)); classes.importRoster(secondClass, RosterCsv.parse(csv(1, 2)));
        var changed = RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n2,SV001,Sinh viên 1,07/11/2005,D23CNPM01\n1,SV002,Sinh viên 2,07/11/2005,D23CNPM01");
        assertEquals(2, classes.preview(classId, changed).count(ClassService.ImportStatus.UPDATE));
        classes.importRoster(classId, changed);
        assertEquals(List.of("SV002", "SV001"), classes.roster(classId).stream().map(ClassService.RosterStudent::code).toList());
        assertEquals(List.of("SV001", "SV002"), classes.roster(secondClass).stream().map(ClassService.RosterStudent::code).toList());
        assertEquals(2, classes.importRoster(classId, changed).count(ClassService.ImportStatus.EXISTS));
    }
    @Test void duplicateSttIncludingExistingRowsRejectsWholeImport() {
        importCsv(csv(1));
        String duplicate = "STT,MSV,Họ và tên,Ngày sinh,Lớp\n1,NEW,Sinh viên mới,07/11/2005,C1\n2,NEW2,Sinh viên hai,07/11/2005,C1";
        assertThrows(IllegalArgumentException.class, () -> classes.preview(classId, RosterCsv.parse(duplicate)));
        assertThrows(IllegalArgumentException.class, () -> importCsv(duplicate));
        assertEquals(1, classes.roster(classId).size());
        assertEquals(List.of(1), ordinals(classId));
        try (EntityManager em = factory.createEntityManager()) {
            assertEquals(0L, em.createQuery("select count(s) from Student s where s.studentCode like 'NEW%'", Long.class).getSingleResult());
        }
    }
    @Test void legacyOrBlankSttRequiresReimportBeforeSplittingNeverRenumbersAutomatically() {
        importCsv("student_code,full_name,birth_date\nSV001,Sinh viên 1,2005-11-07");
        assertNull(classes.roster(classId).getFirst().ordinal());
        assertThrows(IllegalArgumentException.class, () -> shifts.split(classId, 30));
        importCsv(csv(1)); assertEquals(1, shifts.split(classId, 30).size());
    }
    @Test void shiftsStayFrozenWhenClassCsvIsChangedAndCanBeRebuiltBeforeStarting() {
        importCsv(csv(1, 2)); var first = shifts.split(classId, 1);
        importCsv("STT,MSV,Họ và tên,Ngày sinh,Lớp\n90,SV001,Sinh viên 1,07/11/2005,D23CNPM01");
        importCsv(csv(3));
        assertEquals(List.of(1), shifts.roster(classId, first.getFirst().id()).stream().map(ClassService.RosterStudent::ordinal).toList());
        assertEquals(2, shifts.list(classId).size());
        var rebuilt = shifts.split(classId, 2);
        assertEquals(List.of(2, 3), shifts.roster(classId, rebuilt.getFirst().id()).stream().map(ClassService.RosterStudent::ordinal).toList());
        assertThrows(IllegalArgumentException.class, () -> shifts.roster(classId, first.getFirst().id()));
    }
    @Test void enforcesShiftMembershipInBothOffersAndDirectJoinAndKeepsSeparateScores() {
        importCsv(csv(1, 2)); var split = shifts.split(classId, 1);
        var first = exams.activateExam(templateId, classId, split.getFirst().id());
        assertTrue(first.getTitle().contains("Ca 1"));
        assertEquals(first.getId(), exams.activateExam(templateId, classId, split.getFirst().id()).getId());
        assertTrue(exams.findOffer("SV001", first.getId()).isPresent());
        assertTrue(exams.findOffer("SV002", first.getId()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> exams.startAttempt("SV002", first.getId()));
        assertThrows(IllegalArgumentException.class, () -> exams.activateExam(templateId, classId));
        assertThrows(IllegalStateException.class, () -> exams.activateExam(templateId, classId, split.getLast().id()));
        exams.startAttempt("SV001", first.getId()).orElseThrow();
        exams.submit("SV001", new SubmitExamRequest(first.getId(), Map.of(first.getQuestions().getFirst().getId(), "A")));
        clock.advance();
        var second = exams.activateExam(templateId, classId, split.getLast().id());
        assertNotEquals(first.getId(), second.getId());
        assertTrue(exams.findOffer("SV001", second.getId()).isEmpty());
        exams.startAttempt("SV002", second.getId()).orElseThrow();
        exams.submit("SV002", new SubmitExamRequest(second.getId(), Map.of(second.getQuestions().getFirst().getId(), "B")));
        assertEquals(2, exams.listResults().size());
        assertEquals(Set.of(0.0, 10.0), exams.listResults().stream().map(ExamResultRow::score).collect(Collectors.toSet()));
        assertTrue(exams.listResults().stream().anyMatch(r -> r.examTitle().contains("Ca 1")));
        assertTrue(exams.listResults().stream().anyMatch(r -> r.examTitle().contains("Ca 2")));
    }
    @Test void cannotReplaceStartedPlanOrReuseFinishedShiftButCanCreateNextExamBatch() {
        importCsv(csv(1, 2)); var split = shifts.split(classId, 1);
        exams.activateExam(templateId, classId, split.getFirst().id());
        assertThrows(IllegalStateException.class, () -> shifts.split(classId, 2));
        clock.advance();
        assertThrows(IllegalStateException.class, () -> shifts.split(classId, 2), "Ca 2 still pending");
        assertThrows(IllegalStateException.class, () -> exams.activateExam(templateId, classId, split.getFirst().id()));
        exams.activateExam(templateId, classId, split.getLast().id()); clock.advance();
        var next = shifts.split(classId, 2);
        assertEquals(1, next.size()); assertNull(next.getFirst().roundId());
        assertEquals(1, shifts.roster(classId, split.getFirst().id()).getFirst().ordinal(), "Historical candidates kept");
        assertThrows(IllegalArgumentException.class, () -> exams.activateExam(templateId, classId, split.getFirst().id()));
    }
    @Test void validatesOwnershipClassAndCapacity() {
        importCsv(csv(1, 2)); var split = shifts.split(classId, 30);
        var foreign = new ExamShiftService(factory::createEntityManager, otherId, clock);
        assertThrows(IllegalArgumentException.class, () -> foreign.list(classId));
        assertThrows(IllegalArgumentException.class, () -> foreign.split(classId, 30));
        assertThrows(IllegalArgumentException.class, () -> foreign.roster(classId, split.getFirst().id()));
        assertThrows(IllegalArgumentException.class, () -> shifts.roster(secondClass, split.getFirst().id()));
        assertThrows(IllegalArgumentException.class, () -> exams.activateExam(templateId, secondClass, split.getFirst().id()));
        assertThrows(IllegalArgumentException.class, () -> shifts.split(classId, 0));
        assertThrows(IllegalArgumentException.class, () -> shifts.split(classId, 2001));
        assertThrows(IllegalArgumentException.class, () -> shifts.split(secondClass, 30));
    }
    @Test void startupRestoresSplitRoundForExistingAndNotYetStartedStudentsOverTcp() throws Exception {
        importCsv(csv(1, 2, 3));
        var split = shifts.split(classId, 2);
        var round = exams.activateExam(templateId, classId, split.getFirst().id(), 4);
        var before = exams.startAttempt("SV001", round.getId()).orElseThrow();
        exams.updateAnswers("SV001", new SubmitExamRequest(round.getId(), Map.of(before.getQuestions().getFirst().getId(), "B")));
        var reopened = new ExamService(factory::createEntityManager, teacherId, clock);
        var server = new com.ptit.lanexam.server.network.ExamServer(new com.ptit.lanexam.server.network.SessionRegistry(),
                new AuthService(factory::createEntityManager), reopened);
        try {
            server.start(0);
            for (String code : List.of("SV001", "SV002", "SV003")) {
                var client = new com.ptit.lanexam.client.network.LanClient();
                var inbox = new java.util.concurrent.LinkedBlockingQueue<com.ptit.lanexam.common.NetworkMessage>();
                client.setMessageListener(inbox::add);
                try {
                    client.connect("127.0.0.1", server.getPort());
                    client.send(com.ptit.lanexam.common.JsonUtil.message(com.ptit.lanexam.common.MessageType.LOGIN,
                            new com.ptit.lanexam.common.dto.LoginRequest(code, "2005-11-07")));
                    var offer = com.ptit.lanexam.common.JsonUtil.payload(receive(inbox, com.ptit.lanexam.common.MessageType.EXAM_AVAILABLE),
                            com.ptit.lanexam.common.dto.ExamAvailability.class).exam();
                    if (code.equals("SV003")) { assertNull(offer); continue; }
                    assertNotNull(offer); assertEquals(round.getId(), offer.examId());
                    assertEquals(code.equals("SV001"), offer.resumable());
                    client.send(com.ptit.lanexam.common.JsonUtil.message(com.ptit.lanexam.common.MessageType.JOIN_EXAM,
                            new com.ptit.lanexam.common.dto.JoinExamRequest(round.getId())));
                    var actual = com.ptit.lanexam.common.JsonUtil.payload(receive(inbox, com.ptit.lanexam.common.MessageType.EXAM_STARTED),
                            com.ptit.lanexam.common.dto.ExamDto.class);
                    assertEquals(round.getDeadlineEpochMillis(), actual.getDeadlineEpochMillis());
                    if (code.equals("SV001")) {
                        assertEquals(before.getTitle(), actual.getTitle());
                        assertEquals(before.getQuestions().getFirst().getOptions(), actual.getQuestions().getFirst().getOptions());
                        assertEquals("B", actual.getSavedAnswers().get(before.getQuestions().getFirst().getId()));
                    } else assertTrue(actual.getSavedAnswers().isEmpty());
                } finally { client.close(); }
            }
            assertTrue(new ExamService(factory::createEntityManager, otherId, clock).recoverPublishedExam().isEmpty());
            clock.advance(); assertTrue(reopened.recoverPublishedExam().isEmpty());
            assertTrue(reopened.findOffer("SV002", null).isEmpty());
            try (var em = factory.createEntityManager()) {
                assertEquals(1L, em.createQuery("select count(e) from Exam e where e.templateExamId is not null", Long.class).getSingleResult());
            }
        } finally { server.stop(); }
    }

    private static com.ptit.lanexam.common.NetworkMessage receive(
            java.util.concurrent.BlockingQueue<com.ptit.lanexam.common.NetworkMessage> inbox,
            com.ptit.lanexam.common.MessageType type) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            var message = inbox.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (message != null && message.getType() == type) return message;
        }
        throw new AssertionError("Missing " + type);
    }

    @Test void progressUsesPersistedRoundAndDistinguishesUnstartedAbsentAndGrading() {
        importCsv(csv(1, 2, 3)); classes.importRoster(secondClass, RosterCsv.parse(csv(1, 2, 3)));
        var split = shifts.split(classId, 2);
        assertTrue(exams.roundProgress(classId, split.getFirst().id()).isEmpty());
        var round = exams.activateExam(templateId, classId, split.getFirst().id());
        exams.startAttempt("SV001", round.getId());
        assertEquals("Đang làm", exams.roundProgress(classId, split.getFirst().id()).orElseThrow().state("SV001", clock.instant()));
        assertEquals("Chưa vào bài", exams.roundProgress(classId, split.getFirst().id()).orElseThrow().state("SV002", clock.instant()));
        exams.submit("SV001", new SubmitExamRequest(round.getId(), Map.of()));
        var reopened = new ExamService(factory::createEntityManager, teacherId, clock);
        assertEquals("Đã nộp", reopened.roundProgress(classId, split.getFirst().id()).orElseThrow().state("SV001", clock.instant()));
        assertTrue(reopened.roundProgress(classId, split.getLast().id()).isEmpty());
        assertTrue(reopened.roundProgress(secondClass, null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> reopened.roundProgress(secondClass, split.getFirst().id()));
        assertThrows(IllegalArgumentException.class, () -> new ExamService(factory::createEntityManager, otherId, clock).roundProgress(classId, null));
        exams.startAttempt("SV002", round.getId()); clock.advance();
        var expired = reopened.roundProgress(classId, split.getFirst().id()).orElseThrow();
        assertEquals("Đã nộp", expired.state("SV001", clock.instant()));
        assertEquals("Đang chấm", expired.state("SV002", clock.instant()));
        assertEquals("Vắng thi", expired.state("NEVER_JOINED", clock.instant()));
        reopened.finalizeExpiredAttempts();
        assertEquals("Đã nộp", reopened.roundProgress(classId, split.getFirst().id()).orElseThrow().state("SV002", clock.instant()));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-29T08:00:00Z");
        void advance() { now = now.plusSeconds(61); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
