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

    @Test void completeRoundReportSeparatesAbsenceFromZeroAndGradesOfflineAttempts() {
        var round = exams.activateExam(templateId, classId, null, 1);
        assertEquals(60, exams.listRoundResults().size());
        assertTrue(exams.listRoundResults().stream().allMatch(r -> r.status() == ExamResultRow.Status.NOT_STARTED && r.score() == null));
        exams.startAttempt("SV001", round.getId()).orElseThrow();
        var offline = exams.startAttempt("SV002", round.getId()).orElseThrow();
        exams.updateAnswers("SV002", new SubmitExamRequest(round.getId(), correct(offline)));
        exams.submit("SV001", new SubmitExamRequest(round.getId(), Map.of()));
        classes.importRoster(classId, RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n999,SV999,Sinh viên bổ sung,07/11/2005,C1"));
        assertEquals(ExamResultRow.Status.DOING, exams.listRoundResults().stream().filter(r -> r.studentCode().equals("SV002")).findFirst().orElseThrow().status());
        clock.now = clock.now.plusSeconds(61);
        assertEquals(ExamResultRow.Status.GRADING, exams.listRoundResults().stream().filter(r -> r.studentCode().equals("SV002")).findFirst().orElseThrow().status());
        reopened().finalizeExpiredAttempts();
        var rows = reopened().listRoundResults();
        assertEquals(60, rows.size());
        assertTrue(rows.stream().noneMatch(r -> r.studentCode().equals("SV999")));
        assertEquals(58, rows.stream().filter(r -> r.status() == ExamResultRow.Status.ABSENT).count());
        var zero = rows.stream().filter(r -> r.studentCode().equals("SV001")).findFirst().orElseThrow();
        assertEquals(0.0, zero.score()); assertEquals(ExamResultRow.Status.SUBMITTED, zero.status());
        var automatic = rows.stream().filter(r -> r.studentCode().equals("SV002")).findFirst().orElseThrow();
        assertEquals(10.0, automatic.score()); assertEquals(ExamResultRow.Status.EXPIRED, automatic.status());
        assertTrue(rows.stream().filter(r -> r.status() == ExamResultRow.Status.ABSENT)
                .allMatch(r -> r.score() == null && r.correct() == null && r.submittedAt() == null));
        try (var em = factory.createEntityManager()) {
            assertEquals(2L, em.createQuery("select count(a) from ExamAttempt a", Long.class).getSingleResult());
            assertEquals(2L, em.createQuery("select count(s) from Submission s", Long.class).getSingleResult());
        }
    }
    @Test void attendanceReportKeepsShiftBoundariesVariantsAndTeacherScope() {
        var shift = new ExamShiftService(factory::createEntityManager, teacherId, clock).split(classId, 30).getFirst();
        var round = exams.activateExam(templateId, classId, shift.id(), 4);
        assertEquals(30, exams.listRoundResults().size());
        assertTrue(exams.listRoundResults().stream().allMatch(r -> r.variantCode() != null && r.classId().equals(classId) && r.roundId().equals(round.getId())));
        assertTrue(exams.listRoundResults().stream().noneMatch(r -> r.studentCode().equals("SV031")));
        assertTrue(new ExamService(factory::createEntityManager).listRoundResults().isEmpty());
        clock.now = clock.now.plusSeconds(61);
        assertTrue(reopened().listRoundResults().stream().allMatch(r -> r.status() == ExamResultRow.Status.ABSENT));
        assertTrue(exams.listResults().isEmpty());
    }

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
            var levels = dto.getQuestions().stream().map(q -> Integer.parseInt(q.getContent().split(" ")[2])).toList();
            assertEquals(List.of(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6), levels,
                    "Every code must preserve the same difficulty blocks and question counts");
            assertEquals(snapshot(dto), snapshot(reopened().startAttempt(code, round.getId()).orElseThrow()));
            assertEquals(dto.getDeadlineEpochMillis(), round.getDeadlineEpochMillis());
        }
        assertEquals(4, counts.size()); assertTrue(counts.values().stream().allMatch(n -> n == 15));
        var progress = reopened().roundProgress(classId, null).orElseThrow();
        assertEquals(60, progress.variantCodes().size());
        assertTrue(progress.variantCodes().values().stream().allMatch(code -> code.matches("10[1-4]")));
        for (int i = 1; i <= 60; i++) {
            String code = "SV%03d".formatted(i);
            assertTrue(reopened().getOpenAttempt(code).orElseThrow().getTitle().endsWith(progress.variantCode(code)));
        }
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
    @Test void singleCodeRoundFreezesRosterAndRestoresItWithoutChangingDeadlineOrAnswers() {
        var prepared = classes.roster(classId);
        var round = exams.activateExam(templateId, classId, null, 1, prepared);
        var codes = exams.roundProgress(classId, null).orElseThrow();
        assertEquals(60, codes.variantCodes().size());
        assertTrue(codes.variantCodes().values().stream().allMatch("Gốc"::equals));
        var first = exams.startAttempt("SV001", round.getId()).orElseThrow();
        exams.updateAnswers("SV001", new SubmitExamRequest(round.getId(), correct(first)));
        classes.importRoster(classId, RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n999,SV999,Sinh viên bổ sung,07/11/2005,C1"));
        var restored = reopened();
        assertEquals(61, classes.roster(classId).size());
        assertEquals(prepared, restored.rosterForRound(round.getId()));
        assertTrue(restored.findOffer("SV999", round.getId()).isEmpty());
        assertEquals("—", restored.roundProgress(classId, null).orElseThrow().variantCode("SV999"));
        assertThrows(IllegalArgumentException.class, () -> restored.startAttempt("SV999", round.getId()));
        assertEquals(round.getId(), restored.recoverPublishedExam().orElseThrow().getId());
        var resume = restored.startAttempt("SV001", round.getId()).orElseThrow();
        assertEquals(snapshot(first), snapshot(resume));
        assertEquals(correct(first), resume.getSavedAnswers());
        assertEquals(round.getDeadlineEpochMillis(), resume.getDeadlineEpochMillis());
        assertTrue(restored.findOffer("SV002", round.getId()).isPresent());
        clock.now = clock.now.plusSeconds(61); restored.finalizeExpiredAttempts();
        assertEquals("Vắng thi", restored.roundProgress(classId, null).orElseThrow().state("SV002", clock.instant()));
        assertEquals(10.0, restored.listResults().getFirst().score());
        var next = restored.activateExam(templateId, classId, null, 1, classes.roster(classId));
        assertTrue(restored.findOffer("SV999", next.getId()).isPresent());
    }
    @Test void changedPreparedRosterRejectsOpeningAtomicallyUntilPreparedAgain() {
        var prepared = classes.roster(classId);
        classes.importRoster(classId, RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n999,SV999,Sinh viên bổ sung,07/11/2005,C1"));
        assertThrows(IllegalStateException.class, () -> exams.activateExam(templateId, classId, null, 1, prepared));
        assertTrue(exams.recoverPublishedExam().isEmpty());
        try (var em = factory.createEntityManager()) {
            assertEquals(0L, em.createQuery("select count(e) from Exam e where e.templateExamId is not null", Long.class).getSingleResult());
        }
        var round = exams.activateExam(templateId, classId, null, 1, classes.roster(classId));
        assertEquals(61, exams.rosterForRound(round.getId()).size());
    }
    @Test void splitSingleCodeRoundUsesSavedShiftRosterAndKeepsOriginalOrdinals() {
        var shift = new ExamShiftService(factory::createEntityManager, teacherId, clock).split(classId, 30).getFirst();
        classes.importRoster(classId, RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n999,SV999,Sinh viên bổ sung,07/11/2005,C1"));
        var prepared = new ExamShiftService(factory::createEntityManager, teacherId, clock).roster(classId, shift.id());
        var round = exams.activateExam(templateId, classId, shift.id(), 1, prepared);
        assertEquals(prepared, reopened().rosterForRound(round.getId()));
        assertEquals(10, reopened().rosterForRound(round.getId()).getFirst().ordinal());
        assertTrue(reopened().findOffer("SV031", round.getId()).isEmpty());
        assertTrue(reopened().findOffer("SV999", round.getId()).isEmpty());
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
    @Test void protectedChoicesCannotBeShuffledAcrossDifficultyBlocksToMakeExtraCodes() {
        List<Long> ids = new ArrayList<>();
        for (int level : List.of(6, 2, 4)) ids.add(bank.createQuestion(new BankQuestionDraft(subjectId, level,
                new ExamDraft.QuestionDraft("Câu riêng mức " + level, QuestionType.MULTIPLE_CHOICE,
                        "One", "Two", "Three", "All of the above", "D"))).id());
        Long template = exams.createExamFromBank(new BankExamDraft("Protected levels", 1, subjectId, ids)).id();
        var error = assertThrows(IllegalArgumentException.class, () -> exams.activateExam(template, classId, null, 2));
        assertTrue(error.getMessage().contains("1 mã"));
        assertThrows(IllegalStateException.class, exams::getActiveExam);
        var single = exams.activateExam(template, classId, null, 1);
        assertEquals(List.of("Câu riêng mức 2", "Câu riêng mức 4", "Câu riêng mức 6"), single.getQuestions().stream().map(QuestionDto::getContent).toList());
    }
    @Test void bankEditsCannotChangeDifficultyBlocksOfAnExistingTemplateOrRound() {
        List<Long> selected = bank.drawQuestions(subjectId, matrix, Set.of()).stream().map(BankQuestionInfo::id).toList();
        List<Long> backwards = new ArrayList<>(selected); Collections.reverse(backwards);
        Long template = exams.createExamFromBank(new BankExamDraft("Snapshot levels", 1, subjectId, backwards)).id();
        var source = bank.listQuestions(subjectId, 1).stream().filter(q -> selected.contains(q.id())).findFirst().orElseThrow();
        bank.updateQuestion(source.id(), new BankQuestionDraft(subjectId, 6, new ExamDraft.QuestionDraft(
                source.content(), QuestionType.MULTIPLE_CHOICE, source.optionA(), source.optionB(), source.optionC(), source.optionD(), "A")));
        var round = exams.activateExam(template, classId, null, 8);
        List<Integer> expected = List.of(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6);
        try (var em = factory.createEntityManager()) {
            assertEquals(expected, em.find(Exam.class, round.getId()).getQuestions().stream().map(Question::getDifficulty).toList());
        }
        Map<String, String> saved = new HashMap<>();
        for (int index = 1; index <= 60 && saved.size() < 8; index++) {
            String code = "SV%03d".formatted(index); var attempt = exams.startAttempt(code, round.getId()).orElseThrow();
            if (saved.putIfAbsent(attempt.getTitle(), snapshot(attempt)) != null) continue;
            assertEquals(expected, attempt.getQuestions().stream().map(q -> Integer.parseInt(q.getContent().split(" ")[2])).toList());
            assertEquals(snapshot(attempt), snapshot(reopened().getOpenAttempt(code).orElseThrow()));
            assertEquals(10.0, exams.submit(code, new SubmitExamRequest(round.getId(), correct(attempt))).getScore());
        }
        assertEquals(8, saved.size());
    }
    @Test void legacyQuestionsWithoutDifficultyStayInASeparateFinalBlock() {
        var round = exams.activateExam(templateId, classId, null, 1);
        // Emulate an old template whose snapshot metadata was only partially available.
        clock.now = clock.now.plusSeconds(61);
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            var template = em.find(Exam.class, templateId);
            Long missing = template.getQuestions().getFirst().getId();
            em.createQuery("update Question q set q.difficulty = null where q.id = :id").setParameter("id", missing).executeUpdate();
            em.getTransaction().commit();
        }
        var next = exams.activateExam(templateId, classId, null, 4);
        String missingContent = round.getQuestions().getFirst().getContent();
        for (int index = 1; index <= 5; index++) {
            var dto = exams.startAttempt("SV%03d".formatted(index), next.getId()).orElseThrow();
            assertEquals(missingContent, dto.getQuestions().getLast().getContent());
            var known = dto.getQuestions().subList(0, dto.getQuestions().size() - 1).stream()
                    .map(q -> Integer.parseInt(q.getContent().split(" ")[2])).toList();
            assertEquals(known.stream().sorted().toList(), known);
        }
    }
    @Test void reportsWhenProtectedOptionsLeaveTooFewDistinctLayoutsAndRollsBackRound() {
        var q = bank.createQuestion(new BankQuestionDraft(subjectId, 1,
                new ExamDraft.QuestionDraft("Choose the answer", QuestionType.MULTIPLE_CHOICE, "One", "Two", "Three", "All of the above", "D")));
        Long template = exams.createExamFromBank(new BankExamDraft("Protected", 1, subjectId, List.of(q.id()))).id();
        assertThrows(IllegalArgumentException.class, () -> exams.activateExam(template, classId, null, 4));
        assertThrows(IllegalStateException.class, exams::getActiveExam);
    }
}
