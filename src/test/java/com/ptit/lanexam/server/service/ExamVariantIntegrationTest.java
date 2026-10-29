package com.ptit.lanexam.server.service;

import com.ptit.lanexam.common.*;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.server.entity.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class ExamVariantIntegrationTest {
    private EntityManagerFactory factory;
    private QuestionBankService bank;
    private ClassService classes;
    private ExamService exams;
    private Long teacherId, subjectId, classId, templateId;
    private final MutableClock clock = new MutableClock();
    private final Map<Integer, Integer> matrix = Map.of(1, 2, 2, 2, 3, 2, 4, 2, 5, 2, 6, 2);
    @BeforeEach void setup() {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", "jdbc:h2:mem:variants_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "", "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin(); Teacher teacher = new Teacher("gvvariants", "Giảng viên", "unused"); em.persist(teacher); em.flush(); teacherId = teacher.getId(); em.getTransaction().commit();
        }
        bank = new QuestionBankService(factory::createEntityManager, teacherId); subjectId = bank.createSubject("Lập trình mạng").id();
        for (int level = 1; level <= 6; level++) for (int n = 1; n <= 3; n++) bank.createQuestion(new BankQuestionDraft(subjectId, level,
                new ExamDraft.QuestionDraft("Câu mức " + level + " số " + n, QuestionType.MULTIPLE_CHOICE, "Đúng-" + level + "-" + n, "Sai B", "Sai C", "Sai D", "A")));
        classes = new ClassService(factory::createEntityManager, teacherId);
        classId = classes.create("LTM-01", "Nhóm 01", "HK1", subjectId).id();
        StringBuilder csv = new StringBuilder("STT,MSV,Họ và tên,Ngày sinh,Lớp\n");
        for (int i = 60; i >= 1; i--) csv.append(i * 10).append(",SV%03d,Sinh viên %d,07/11/2005,C1\n".formatted(i, i));
        classes.importRoster(classId, RosterCsv.parse(csv.toString()));
        exams = new ExamService(factory::createEntityManager, teacherId, clock);
        templateId = exams.createExamFromBank(new BankExamDraft("Kiểm tra nhanh", 1, subjectId, bank.drawQuestions(subjectId, matrix, Set.of()).stream().map(BankQuestionInfo::id).toList())).id();
    }
    @AfterEach void close() { factory.close(); }
    private ExamService reopened() { return new ExamService(factory::createEntityManager, teacherId, clock); }
    private static Map<Long, String> correct(ExamDto dto) {
        Map<Long, String> answers = new LinkedHashMap<>();
        dto.getQuestions().forEach(q -> answers.put(q.getId(), q.getOptions().stream().filter(o -> o.substring(3).startsWith("Đúng-")).findFirst().orElseThrow().substring(0, 1)));
        return answers;
    }
    private static String snapshot(ExamDto dto) { return JsonUtil.MAPPER.valueToTree(dto.getQuestions()).toString(); }

    @Test void drawsExactMatrixWithoutDuplicatesAndReplacesOnlyFromRemainingSameLevel() {
        var selection = bank.drawQuestions(subjectId, matrix, Set.of());
        assertEquals(12, selection.size()); assertEquals(12, selection.stream().map(BankQuestionInfo::id).distinct().count());
        for (int level = 1; level <= 6; level++) { int l = level; assertEquals(2, selection.stream().filter(q -> q.difficulty() == l).count()); }
        var replacement = bank.drawQuestions(subjectId, Map.of(1, 1), selection.stream().map(BankQuestionInfo::id).collect(Collectors.toSet()));
        assertEquals(1, replacement.getFirst().difficulty()); assertFalse(selection.stream().anyMatch(q -> q.id().equals(replacement.getFirst().id())));
        assertEquals(18, bank.listQuestions(subjectId, null).size()); assertEquals(1, exams.listExams().size());
    }
    @Test void rejectsShortagesInvalidMatrixAndForeignSubjectWithoutWrites() {
        assertThrows(IllegalArgumentException.class, () -> bank.drawQuestions(subjectId, Map.of(4, 4), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> bank.drawQuestions(subjectId, Map.of(1, -1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> bank.drawQuestions(subjectId, Map.of(7, 1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> bank.drawQuestions(subjectId, Map.of(1, 201), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> bank.drawQuestions(subjectId, Map.of(1, 0), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new QuestionBankService(factory::createEntityManager).drawQuestions(subjectId, matrix, Set.of()));
    }
    @Test void distributesFourDistinctSavedLayoutsEvenlyAcrossSixtyStudentsAndReopeningDoesNotShuffle() {
        var round = exams.activateExam(templateId, classId, null, 4);
        Map<String, Long> counts = new HashMap<>(); Map<String, String> layouts = new HashMap<>();
        Set<Long> ids = round.getQuestions().stream().map(QuestionDto::getId).collect(Collectors.toSet());
        for (int i = 1; i <= 60; i++) {
            String code = "SV%03d".formatted(i); var dto = exams.startAttempt(code, round.getId()).orElseThrow();
            String title = dto.getTitle(); counts.merge(title, 1L, Long::sum);
            String old = layouts.putIfAbsent(title, snapshot(dto)); if (old != null) assertEquals(old, snapshot(dto));
            assertEquals(ids, dto.getQuestions().stream().map(QuestionDto::getId).collect(Collectors.toSet()));
            assertEquals(snapshot(dto), snapshot(reopened().startAttempt(code, round.getId()).orElseThrow()));
            assertEquals(dto.getDeadlineEpochMillis(), round.getDeadlineEpochMillis());
        }
        assertEquals(4, counts.size()); assertTrue(counts.values().stream().allMatch(n -> n == 15));
        assertEquals(4, new HashSet<>(layouts.values()).size());
        assertEquals(round.getId(), reopened().activateExam(templateId, classId, null, 4).getId());
        assertThrows(IllegalStateException.class, () -> exams.activateExam(templateId, classId, null, 3));
    }
    @Test void gradesDisplayedChoicesCorrectlyForEveryVariantAndExportsCode() {
        var round = exams.activateExam(templateId, classId, null, 4); Set<String> tested = new HashSet<>();
        for (int i = 1; i <= 60 && tested.size() < 4; i++) {
            String code = "SV%03d".formatted(i); var dto = exams.startAttempt(code, round.getId()).orElseThrow();
            if (!tested.add(dto.getTitle())) continue;
            String network = JsonUtil.toJson(JsonUtil.message(MessageType.EXAM_STARTED, dto));
            assertFalse(network.contains("correctAnswer")); assertFalse(network.contains("assignments")); assertFalse(network.contains("variants"));
            var result = exams.submit(code, new SubmitExamRequest(round.getId(), correct(dto)));
            assertEquals(10.0, result.getScore());
            assertEquals(10.0, exams.submit(code, new SubmitExamRequest(round.getId(), Map.of())).getScore());
            assertTrue(exams.listStudentHistory(code).getFirst().examTitle().contains("Mã đề"));
        }
        assertEquals(4, tested.size());
        assertEquals(Set.of("101", "102", "103", "104"), exams.listResults().stream().map(ExamResultRow::variantCode).collect(Collectors.toSet()));
        assertTrue(ExamResultsCsv.format(exams.listResults()).contains("ma_de"));
        assertTrue(ExamResultsCsv.format(exams.listResults()).contains("\"101\""));
    }
    @Test void savedDisplayAnswersAndLayoutSurviveServiceRestartAndOfflineExpiry() {
        var round = exams.activateExam(templateId, classId, null, 4);
        var first = exams.startAttempt("SV001", round.getId()).orElseThrow();
        var answers = correct(first); exams.updateAnswers("SV001", new SubmitExamRequest(round.getId(), answers));
        var restarted = reopened(); var resume = restarted.getOpenAttempt("SV001").orElseThrow();
        assertEquals(first.getTitle(), resume.getTitle()); assertEquals(snapshot(first), snapshot(resume));
        assertEquals(answers, resume.getSavedAnswers()); assertEquals(first.getDeadlineEpochMillis(), resume.getDeadlineEpochMillis());
        clock.now = clock.now.plusSeconds(61); reopened().finalizeExpiredAttempts();
        assertEquals(10.0, reopened().listResults().getFirst().score()); assertNotNull(reopened().listResults().getFirst().variantCode());
    }
    @Test void sameStudentConcurrentJoinGetsOneAttemptAndSameVariant() throws Exception {
        var round = exams.activateExam(templateId, classId, null, 4);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<ExamDto>> joins = List.of(() -> reopened().startAttempt("SV001", round.getId()).orElseThrow(), () -> reopened().startAttempt("SV001", round.getId()).orElseThrow());
            var results = threads.invokeAll(joins); assertEquals(snapshot(results.get(0).get()), snapshot(results.get(1).get()));
            assertEquals(results.get(0).get().getTitle(), results.get(1).get().getTitle());
        }
        try (EntityManager em = factory.createEntityManager()) { assertEquals(1L, em.createQuery("select count(a) from ExamAttempt a", Long.class).getSingleResult()); }
    }
    @Test void newEnrollmentAfterPublicationCannotGetAnUnassignedVariantAndShiftBoundaryStillApplies() {
        var shifts = new ExamShiftService(factory::createEntityManager, teacherId, clock).split(classId, 30);
        var round = exams.activateExam(templateId, classId, shifts.getFirst().id(), 4);
        assertTrue(exams.findOffer("SV001", round.getId()).isPresent()); assertTrue(exams.findOffer("SV031", round.getId()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> exams.startAttempt("SV031", round.getId()));
        assertThrows(IllegalArgumentException.class, () -> exams.activateExam(templateId, classId, null, 0));
        assertThrows(IllegalArgumentException.class, () -> exams.activateExam(templateId, classId, null, 9));
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-29T08:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return now; }
    }

    @Test void fullClassPublicationDoesNotAssignCodesToLateImports() {
        var round = exams.activateExam(templateId, classId, null, 4);
        classes.importRoster(classId, RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n999,SV999,Sinh viên bổ sung,07/11/2005,C1"));
        assertTrue(exams.findOffer("SV999", round.getId()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> exams.startAttempt("SV999", round.getId()));
    }
    @Test void preservesOptionsThatReferToLettersWhileStillShufflingQuestionOrder() {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) ids.add(bank.createQuestion(new BankQuestionDraft(subjectId, 1,
                new ExamDraft.QuestionDraft("Câu tham chiếu " + i, QuestionType.MULTIPLE_CHOICE, "Nhận định thứ nhất", "Nhận định thứ hai", "Nhận định thứ ba", "Cả A và B", "D"))).id());
        Long template = exams.createExamFromBank(new BankExamDraft("Đề có tham chiếu", 1, subjectId, ids)).id();
        var round = exams.activateExam(template, classId, null, 4);
        Set<String> layouts = new HashSet<>();
        for (int i = 1; i <= 60; i++) {
            var dto = exams.startAttempt("SV%03d".formatted(i), round.getId()).orElseThrow(); layouts.add(snapshot(dto));
            for (var q : dto.getQuestions()) assertEquals(List.of("A. Nhận định thứ nhất", "B. Nhận định thứ hai", "C. Nhận định thứ ba", "D. Cả A và B"), q.getOptions());
        }
        assertEquals(4, layouts.size());
    }
    @Test void reportsWhenProtectedOptionsLeaveTooFewDistinctLayoutsAndRollsBackRound() {
        var q = bank.createQuestion(new BankQuestionDraft(subjectId, 1,
                new ExamDraft.QuestionDraft("Choose the answer", QuestionType.MULTIPLE_CHOICE, "One", "Two", "Three", "All of the above", "D")));
        Long template = exams.createExamFromBank(new BankExamDraft("Protected", 1, subjectId, List.of(q.id()))).id();
        assertThrows(IllegalArgumentException.class, () -> exams.activateExam(template, classId, null, 4));
        assertThrows(IllegalStateException.class, exams::getActiveExam);
    }
}
