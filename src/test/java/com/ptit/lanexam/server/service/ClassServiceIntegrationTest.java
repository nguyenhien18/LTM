package com.ptit.lanexam.server.service;

import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.server.entity.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClassServiceIntegrationTest {
    private EntityManagerFactory factory;
    private ClassService classes, other;
    private Long teacherId, otherId, subjectId, classA, classB;
    private static final Instant NOW = Instant.parse("2026-10-28T08:00:00Z");
    private static final String CSV = "student_code,full_name,birth_date\nSV001,Nguyễn An,2003-05-12\nSV002,Trần Bình,2003-08-20";
    @BeforeEach void setup() {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", "jdbc:h2:mem:classes_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            Teacher a = new Teacher("gv01", "Một", "unused"), b = new Teacher("gv02", "Hai", "unused");
            em.persist(a); em.persist(b); em.flush(); teacherId = a.getId(); otherId = b.getId();
            Subject subject = new Subject("Mạng", "gv" + teacherId + ":mang"); subject.setOwnerTeacherId(teacherId); em.persist(subject); em.flush(); subjectId = subject.getId();
            em.getTransaction().commit();
        }
        classes = new ClassService(factory::createEntityManager, teacherId); other = new ClassService(factory::createEntityManager, otherId);
        classA = classes.create("LTM-A", "Nhóm A", "2026 HK1", subjectId).id();
        classB = classes.create("LTM-B", "Nhóm B", "2026 HK1", subjectId).id();
    }
    @AfterEach void close() { factory.close(); }
    @Test void editingSharedProfileChangesLoginAndAllClassesButPreservesIdentityAndOrdinals() {
        var rows = RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n7,SV001,Nguyễn An,12/05/2003,C1");
        classes.importRoster(classA, rows);
        classes.importRoster(classB, RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n19,SV001,Nguyễn An,12/05/2003,C1"));
        var old = classes.roster(classA).getFirst();
        var saved = classes.updateProfile(classA, old, "Nguyễn Văn An", LocalDate.of(2003, 5, 17), "C2");
        assertEquals("SV001", saved.code()); assertEquals(7, saved.ordinal());
        var second = classes.roster(classB).getFirst();
        assertEquals("Nguyễn Văn An", second.fullName()); assertEquals("C2", second.regularClass()); assertEquals(19, second.ordinal());
        var auth = new AuthService(factory::createEntityManager, teacherId);
        assertTrue(auth.authenticate("SV001", "2003-05-12").isEmpty());
        assertTrue(auth.authenticate("SV001", "2003-05-17").isPresent());
        try (var em = factory.createEntityManager()) { assertEquals(1L, em.createQuery("select count(s) from Student s", Long.class).getSingleResult()); }
    }
    @Test void profileEditingRejectsForeignClassOutsiderInvalidDataAndStaleForm() {
        classes.importRoster(classA, RosterCsv.parse(CSV));
        var original = classes.roster(classA).getFirst();
        assertThrows(IllegalArgumentException.class, () -> other.updateProfile(classA, original, "Sai quyền", original.birthDate(), null));
        assertThrows(IllegalArgumentException.class, () -> classes.updateProfile(classB, original, "Ngoài lớp", original.birthDate(), null));
        assertThrows(IllegalArgumentException.class, () -> classes.updateProfile(classA, original, " ", original.birthDate(), null));
        assertThrows(IllegalArgumentException.class, () -> classes.updateProfile(classA, original, "An", LocalDate.now().plusDays(1), null));
        assertEquals(original, classes.roster(classA).getFirst());
        classes.updateProfile(classA, original, "Tên đã sửa", original.birthDate(), "C1");
        assertThrows(IllegalStateException.class, () -> classes.updateProfile(classA, original, "Ghi đè", original.birthDate(), null));
        assertEquals("Tên đã sửa", classes.roster(classA).getFirst().fullName());
    }
    @Test void profileEditingWaitsForUnfinishedAttemptEvenInAnotherTeachersClass() {
        classes.importRoster(classA, RosterCsv.parse(CSV));
        var bank = new QuestionBankService(factory::createEntityManager, otherId);
        var subject = bank.createSubject("Môn khác");
        var group = other.create("OTHER", "Lớp khác", "HK1", subject.id());
        other.importRoster(group.id(), RosterCsv.parse(CSV));
        var exams = new ExamService(factory::createEntityManager, otherId);
        var template = exams.createExam(new ExamDraft("Đề thử", 1, List.of(new ExamDraft.QuestionDraft("Chọn A", QuestionType.MULTIPLE_CHOICE, "A", "B", "C", "D", "A"))));
        var round = exams.activateExam(template.id(), group.id());
        exams.startAttempt("SV001", round.getId()).orElseThrow();
        var original = classes.roster(classA).getFirst();
        assertThrows(IllegalStateException.class, () -> classes.updateProfile(classA, original, "Tên mới", original.birthDate(), "C2"));
        assertEquals(original, classes.roster(classA).getFirst());
        exams.submit("SV001", new SubmitExamRequest(round.getId(), Map.of()));
        assertEquals("Tên mới", classes.updateProfile(classA, original, "Tên mới", original.birthDate(), "C2").fullName());
        assertEquals("Nguyễn An", exams.rosterForRound(round.getId()).getFirst().fullName());
    }
    @Test void reusesIdentityAcrossClassesAndRepeatedImportIsIdempotent() {
        var rows = RosterCsv.parse(CSV);
        assertEquals(2, classes.preview(classA, rows).count(ClassService.ImportStatus.CREATE));
        assertTrue(classes.roster(classA).isEmpty(), "Preview must not write");
        classes.importRoster(classA, rows);
        assertEquals(2, classes.preview(classB, rows).count(ClassService.ImportStatus.ENROLL));
        classes.importRoster(classB, rows);
        assertEquals(2, classes.importRoster(classA, rows).count(ClassService.ImportStatus.EXISTS));
        assertEquals(2, classes.roster(classA).size()); assertEquals(2, classes.roster(classB).size());
        assertEquals(Set.of(classA, classB), classes.listForStudent("SV001").stream().map(com.ptit.lanexam.common.dto.StudentClassInfo::id).collect(java.util.stream.Collectors.toSet()));
        assertTrue(classes.listForStudent("NOT_ENROLLED").isEmpty());
        assertEquals(2, classes.list().size());
        try (EntityManager em = factory.createEntityManager()) {
            assertEquals(2L, em.createQuery("select count(s) from Student s", Long.class).getSingleResult());
            assertEquals(4L, em.createQuery("select count(s) from ClassStudent s", Long.class).getSingleResult());
        }
        Long anotherSubject = new QuestionBankService(factory::createEntityManager, otherId).createSubject("Cơ sở dữ liệu").id();
        Long anotherClass = other.create("CSDL-A", "Nhóm cơ sở dữ liệu", "HK1", anotherSubject).id();
        assertEquals(2, other.importRoster(anotherClass, rows).count(ClassService.ImportStatus.ENROLL));
        assertEquals(2, other.roster(anotherClass).size());
        try (EntityManager em = factory.createEntityManager()) {
            assertEquals(2L, em.createQuery("select count(s) from Student s", Long.class).getSingleResult());
        }
    }
    @Test void conflictingIdentityBlocksWholeImportAndNeverOverwritesStudent() {
        classes.importRoster(classA, RosterCsv.parse(CSV));
        var conflict = RosterCsv.parse("student_code,full_name,birth_date\nSV003,Lê Cường,2003-03-03\nSV001,Nguyễn An,2004-05-12");
        assertTrue(classes.preview(classB, conflict).hasConflicts());
        assertThrows(IllegalArgumentException.class, () -> classes.importRoster(classB, conflict));
        assertTrue(classes.roster(classB).isEmpty());
        try (EntityManager em = factory.createEntityManager()) {
            assertEquals(2L, em.createQuery("select count(s) from Student s", Long.class).getSingleResult());
        }
    }
    @Test void teacherCannotUseAnotherTeachersClassOrSubject() {
        assertTrue(other.list().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> other.roster(classA));
        assertThrows(IllegalArgumentException.class, () -> other.preview(classA, RosterCsv.parse(CSV)));
        assertThrows(IllegalArgumentException.class, () -> other.importRoster(classA, RosterCsv.parse(CSV)));
        assertThrows(IllegalArgumentException.class, () -> other.create("X", "X", "HK1", subjectId));
        assertThrows(IllegalArgumentException.class, () -> classes.create("ltm-a", "Trùng", "HK1", subjectId));
    }
    @Test void reusableTemplateProducesSeparateClassRoundsAndRejectsOutsiders() {
        var rows = RosterCsv.parse(CSV); classes.importRoster(classA, List.of(rows.getFirst())); classes.importRoster(classB, List.of(rows.getLast()));
        ExamService exams = new ExamService(factory::createEntityManager, teacherId, Clock.fixed(NOW, ZoneOffset.UTC));
        var template = exams.createExam(new ExamDraft("Đề dùng lại", 1, List.of(new ExamDraft.QuestionDraft("Chọn A", QuestionType.MULTIPLE_CHOICE, "1", "2", "3", "4", "A"))));
        assertThrows(IllegalArgumentException.class, () -> exams.activateExam(template.id()));
        var roundA = exams.activateExam(template.id(), classA);
        assertEquals("LTM-A · Nhóm A", exams.findOffer("SV001", roundA.getId()).orElseThrow().className());
        assertTrue(exams.findOffer("SV002", roundA.getId()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> exams.startAttempt("SV002", roundA.getId()));
        exams.startAttempt("SV001", roundA.getId());
        assertThrows(IllegalStateException.class, () -> exams.activateExam(template.id(), classB));
        exams.submit("SV001", new SubmitExamRequest(roundA.getId(), Map.of(roundA.getQuestions().getFirst().getId(), "A")));
        ExamService later = new ExamService(factory::createEntityManager, teacherId, Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC));
        var roundB = later.activateExam(template.id(), classB);
        assertNotEquals(roundA.getId(), roundB.getId()); assertEquals(1, later.listExams().size());
        assertTrue(later.findOffer("SV001", roundB.getId()).isEmpty()); assertTrue(later.findOffer("SV002", roundB.getId()).isPresent());
        assertEquals(10, later.listStudentHistory("SV001").getFirst().score());
        assertEquals(classA, later.listStudentHistory("SV001").getFirst().classId());
        assertEquals(1, later.listResults().stream().filter(r -> classA.equals(r.classId())).count());
        assertTrue(later.listResults().stream().noneMatch(r -> classB.equals(r.classId())));
        assertEquals(roundA.getId(), later.roundProgress(classA, null).orElseThrow().roundId());
        assertEquals(roundB.getId(), later.roundProgress(classB, null).orElseThrow().roundId());
    }


    @Test void openingExamChecksClassOwnerAndMatchingSubject() {
        QuestionBankService bank = new QuestionBankService(factory::createEntityManager, teacherId);
        Long question = bank.createQuestion(new BankQuestionDraft(subjectId, 1,
                new ExamDraft.QuestionDraft("Chọn A", QuestionType.MULTIPLE_CHOICE, "1", "2", "3", "4", "A"))).id();
        ExamService exams = new ExamService(factory::createEntityManager, teacherId, Clock.fixed(NOW, ZoneOffset.UTC));
        var template = exams.createExamFromBank(new BankExamDraft("Đề Mạng", 20, subjectId, List.of(question)));
        Long differentSubject = bank.createSubject("Cơ sở dữ liệu").id();
        Long wrongClass = classes.create("CSDL", "Nhóm CSDL", "HK1", differentSubject).id();
        assertThrows(IllegalArgumentException.class, () -> exams.activateExam(template.id(), wrongClass));
        Long foreignSubject = new QuestionBankService(factory::createEntityManager, otherId).createSubject("Mạng").id();
        Long foreignClass = other.create("LTM", "Lớp khác", "HK1", foreignSubject).id();
        assertThrows(IllegalArgumentException.class, () -> exams.activateExam(template.id(), foreignClass));
        assertThrows(IllegalStateException.class, () -> exams.activateExam(template.id(), classA));
        classes.importRoster(classA, RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n1,SV001,Sinh viên một,12/05/2003,C1"));
        assertNotNull(exams.activateExam(template.id(), classA));
    }

    @Test void schoolCsvPersistsRegularClassWithoutCreatingAnotherCourseClassAndSupportsLogin() {
        var rows = RosterCsv.parse(RosterCsv.sample());
        classes.importRoster(classA, rows); classes.importRoster(classB, rows);
        assertEquals(2, classes.list().size(), "Lớp chính quy không tự tạo thêm lớp học phần");
        assertEquals("D23CNPM05", classes.roster(classA).getFirst().regularClass());
        assertEquals("D23CNPM05", classes.roster(classB).getFirst().regularClass());
        var auth = new AuthService(factory::createEntityManager);
        assertTrue(auth.authenticate("B23DCCN011", "2005-11-07").isPresent());
        assertTrue(auth.authenticate("B23DCCN011", "2005-07-11").isEmpty());
        try (EntityManager em = factory.createEntityManager()) {
            assertEquals(2L, em.createQuery("select count(s) from Student s", Long.class).getSingleResult());
            assertEquals(4L, em.createQuery("select count(s) from ClassStudent s", Long.class).getSingleResult());
        }
    }
    @Test void fillsPreviouslyMissingRegularClassEvenForAlreadyEnrolledStudent() {
        classes.importRoster(classA, RosterCsv.parse(CSV));
        var rows = RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n1,SV001,Nguyễn An,12/05/2003,D23CNPM05");
        assertEquals(1, classes.preview(classA, rows).count(ClassService.ImportStatus.UPDATE));
        assertNull(classes.roster(classA).getFirst().regularClass(), "Xem trước không được ghi dữ liệu");
        classes.importRoster(classA, rows);
        assertEquals("D23CNPM05", classes.roster(classA).getFirst().regularClass());
        assertEquals(1, classes.importRoster(classA, rows).count(ClassService.ImportStatus.EXISTS));
        classes.importRoster(classB, RosterCsv.parse(CSV));
        assertEquals("D23CNPM05", classes.roster(classB).getFirst().regularClass(), "Mẫu cũ không xóa lớp chính quy đã có");
        assertEquals(2, classes.roster(classA).size());
    }
    @Test void conflictingRegularClassBlocksWholeImportAndKeepsExistingProfile() {
        classes.importRoster(classA, RosterCsv.parse(RosterCsv.sample()));
        var rows = RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n1,NEW01,Sinh viên mới,07/11/2005,D23A\n2,B23DCCN011,Bùi Thế Anh,07/11/2005,D23CNPM03");
        assertTrue(classes.preview(classB, rows).hasConflicts());
        assertThrows(IllegalArgumentException.class, () -> classes.importRoster(classB, rows));
        assertTrue(classes.roster(classB).isEmpty());
        assertEquals("D23CNPM05", classes.roster(classA).getFirst().regularClass());
        try (EntityManager em = factory.createEntityManager()) {
            assertEquals(2L, em.createQuery("select count(s) from Student s", Long.class).getSingleResult());
        }
    }

    @Test void restoresAllSixtyMissingOrdinalsFromOriginalCsvWithoutDuplicatingStudentsOrChangingOtherClass() throws Exception {
        var source = RosterCsv.read(java.nio.file.Path.of(java.util.Objects.requireNonNull(ClassServiceIntegrationTest.class.getResource("/fixtures/student-roster-60.csv")).toURI()));
        var oldRows = source.stream().map(r -> new RosterCsv.StudentRow(r.line(), r.code(), r.fullName(), r.birthDate(), r.regularClass())).toList();
        classes.importRoster(classA, oldRows); classes.importRoster(classB, oldRows);
        assertTrue(classes.roster(classA).stream().allMatch(r -> r.ordinal() == null));
        var preview = classes.preview(classA, source);
        assertEquals(60, preview.count(ClassService.ImportStatus.UPDATE));
        assertTrue(classes.roster(classA).stream().allMatch(r -> r.ordinal() == null), "Preview does not repair silently");
        classes.importRoster(classA, source);
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 60).boxed().toList(), classes.roster(classA).stream().map(ClassService.RosterStudent::ordinal).toList());
        assertTrue(classes.roster(classB).stream().allMatch(r -> r.ordinal() == null), "STT belongs to each class separately");
        assertEquals(60, classes.preview(classA, source).count(ClassService.ImportStatus.EXISTS));
        var shifts = new ExamShiftService(factory::createEntityManager, teacherId).split(classA, 30);
        assertEquals(List.of(30, 30), shifts.stream().map(ExamShiftService.ShiftInfo::studentCount).toList());
        try (EntityManager em = factory.createEntityManager()) {
            assertEquals(60L, em.createQuery("select count(s) from Student s", Long.class).getSingleResult());
            assertEquals(120L, em.createQuery("select count(cs) from ClassStudent cs", Long.class).getSingleResult());
        }
    }
}
