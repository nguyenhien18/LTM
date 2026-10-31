package com.ptit.lanexam.client;

import com.ptit.lanexam.common.*;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.server.ExamEditorDialog;
import com.ptit.lanexam.server.QuestionEditorDialog;
import com.ptit.lanexam.server.LockedAccountManager;
import com.ptit.lanexam.server.ServerApp;
import com.ptit.lanexam.server.ClassManager;
import com.ptit.lanexam.server.service.ClassService;
import com.ptit.lanexam.server.service.RosterCsv;
import com.ptit.lanexam.server.network.*;
import com.ptit.lanexam.server.entity.Student;
import com.ptit.lanexam.server.entity.QuestionType;
import com.ptit.lanexam.server.service.BankExamDraft;
import com.ptit.lanexam.server.service.BankQuestionDraft;
import com.ptit.lanexam.server.service.BankQuestionInfo;
import com.ptit.lanexam.server.service.QuestionBankService;
import com.ptit.lanexam.server.service.SubjectInfo;
import com.ptit.lanexam.server.service.AuthService;
import com.ptit.lanexam.server.service.ExamService;
import com.ptit.lanexam.server.service.LockedAccountInfo;
import com.ptit.lanexam.server.service.TeacherAccountService;
import com.ptit.lanexam.server.service.ExamDraft;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import javafx.collections.FXCollections;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "lanexam.uiTests", matches = "true")
class ExamWorkflowUiTest {
    private static final Instant TIME = Instant.parse("2026-10-20T08:00:00Z");

    @Test
    void clientRetriesTypedLoginAfterServerErrorAndWrongDateUsingImportedRoster() throws Exception {
        try (var factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", "jdbc:h2:mem:typed_login_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "", "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var teacher = new TeacherAccountService(factory::createEntityManager).create("gv_login", "Giảng viên", "Matkhau123!");
            var bank = new QuestionBankService(factory::createEntityManager, teacher.id());
            var subject = bank.createSubject("Lập trình mạng");
            var classes = new ClassService(factory::createEntityManager, teacher.id());
            var group = classes.create("LTM-LOGIN", "Lớp đăng nhập", "HK1", subject.id());
            classes.importRoster(group.id(), RosterCsv.read(Path.of("src/test/resources/fixtures/student-roster-60.csv")));
            AtomicInteger logins = new AtomicInteger();
            var auth = new AuthService(factory::createEntityManager, teacher.id()) {
                @Override public Optional<Student> authenticate(String code, String date) {
                    if (logins.incrementAndGet() == 1) throw new IllegalStateException("Lỗi kiểm tra tài khoản thử nghiệm");
                    return super.authenticate(code, date);
                }
            };
            var registry = new SessionRegistry();
            var server = new ExamServer(registry, auth, new ExamService(factory::createEntityManager, teacher.id()));
            server.start(0);
            ClientApp app = new ClientApp();
            Stage stage = fx(() -> { var s = new Stage(); app.start(s); return s; });
            try {
                fx(() -> {
                    ((TextField) stage.getScene().lookup("#login-port")).setText(Integer.toString(server.getPort()));
                    ((TextField) stage.getScene().lookup("#login-code")).setText("B23DCCN221");
                    ((DatePicker) stage.getScene().lookup("#login-birth-date")).getEditor().setText("25/05/2004");
                    return null;
                });
                for (int attempt = 0; attempt < 2; attempt++) {
                    fx(() -> { ((Button) stage.getScene().lookup("#login-connect")).fire(); return null; });
                    awaitFx(() -> notice("#client-alert") != null);
                    final int index = attempt;
                    fx(() -> {
                        var pane = notice("#client-alert");
                        assertEquals("Đăng nhập thất bại", pane.getHeaderText());
                        assertTrue(pane.getContentText().contains(index == 0 ? "Lỗi kiểm tra" : "MSV/ngày sinh"));
                        assertFalse(stage.getScene().lookup("#login-connect").isDisabled());
                        assertEquals("B23DCCN221", ((TextField) stage.getScene().lookup("#login-code")).getText());
                        assertEquals("25/05/2004", ((DatePicker) stage.getScene().lookup("#login-birth-date")).getEditor().getText());
                        ((Button) pane.lookupButton(ButtonType.OK)).fire(); return null;
                    });
                    awaitFx(() -> notice("#client-alert") == null);
                }
                fx(() -> {
                    ((DatePicker) stage.getScene().lookup("#login-birth-date")).getEditor().setText("25/05/2005");
                    ((Button) stage.getScene().lookup("#login-connect")).fire(); return null;
                });
                awaitFx(() -> stage.getScene().lookup("#join-exam") != null);
                assertNotNull(registry.find("B23DCCN221"));
            } finally {
                fx(() -> { var pane = notice("#client-alert"); if (pane != null) pane.getScene().getWindow().hide(); app.stop(); stage.close(); return null; });
                server.stop();
            }
        }
    }

    @Test
    void loginRejectsInvalidOrClearedTypedDateInsteadOfUsingPreviousValue() throws Exception {
        ClientApp app = new ClientApp();
        Stage stage = fx(() -> { var s = new Stage(); app.start(s); return s; });
        try {
            fx(() -> {
                ((TextField) stage.getScene().lookup("#login-code")).setText("ST001");
                ((TextField) stage.getScene().lookup("#login-port")).setText("0");
                var date = (DatePicker) stage.getScene().lookup("#login-birth-date");
                assertEquals(LocalDate.of(2005, 5, 25), date.getConverter().fromString("25/05/2005"));
                assertEquals(LocalDate.of(2004, 2, 29), date.getConverter().fromString("29/02/2004"));
                assertEquals("25/05/2005", date.getConverter().toString(LocalDate.of(2005, 5, 25)));
                return null;
            });
            for (String input : List.of("31/02/2005", "29/02/2005", "05-25-2005", "")) {
                fx(() -> {
                    var date = (DatePicker) stage.getScene().lookup("#login-birth-date");
                    date.setValue(LocalDate.of(2003, 5, 12));
                    date.getEditor().setText(input);
                    Platform.runLater(() -> ((Button) stage.getScene().lookup("#login-connect")).fire());
                    return null;
                });
                awaitFx(() -> notice("#client-alert") != null);
                fx(() -> {
                    var pane = notice("#client-alert");
                    assertEquals(input.isEmpty() ? "Thiếu dữ liệu" : "Ngày sinh không hợp lệ", pane.getHeaderText());
                    assertFalse(stage.getScene().lookup("#login-connect").isDisabled());
                    ((Button) pane.lookupButton(ButtonType.OK)).fire();
                    return null;
                });
                awaitFx(() -> notice("#client-alert") == null);
            }
        } finally {
            fx(() -> {
                var pane = notice("#client-alert"); if (pane != null) pane.getScene().getWindow().hide();
                app.stop(); stage.close(); return null;
            });
        }
    }

    @Test void templateWorkspaceCreatesFiltersAndReloadsOnlyExamTemplates() throws Exception {
        try (var factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:template_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var bank = new QuestionBankService(factory::createEntityManager);
            var subject = bank.createSubject("Mạng");
            bank.createQuestion(new BankQuestionDraft(subject.id(), 1,
                    new ExamDraft.QuestionDraft("Giao thức?", QuestionType.MULTIPLE_CHOICE, "TCP", "UDP", "IP", "ICMP", "A")));
            var exams = new ExamService(factory::createEntityManager);
            com.ptit.lanexam.server.ExamTemplateManager[] view = new com.ptit.lanexam.server.ExamTemplateManager[1];
            var announce = new java.util.concurrent.atomic.AtomicReference<String>();
            Stage stage = fx(() -> {
                var s = new Stage(); view[0] = new com.ptit.lanexam.server.ExamTemplateManager(s, exams, bank, announce::set);
                s.setScene(com.ptit.lanexam.common.ui.AppUi.scene(view[0], 1100, 700)); s.show(); return s;
            });
            try {
                awaitFx(() -> !stage.getScene().lookup("#exam-template-create").isDisabled());
                fx(() -> {
                    assertNull(stage.getScene().lookup("#assessment-filter"));
                    assertNull(stage.getScene().lookup("#exam-publish"));
                    Platform.runLater(() -> ((Button) stage.getScene().lookup("#exam-template-create")).fire()); return null;
                });
                awaitFx(() -> notice("#exam-editor") != null
                        && ((TableView<?>) notice("#exam-editor").lookup("#bank-questions")).getItems().size() == 1);
                fx(() -> {
                    var pane = notice("#exam-editor");
                    ((TextField) pane.lookup("#exam-title")).setText("Đề mạng mới");
                    ((TableView<?>) pane.lookup("#bank-questions")).getSelectionModel().selectFirst();
                    ((Button) pane.lookup("#exam-add-selected")).fire(); ((Button) pane.lookup("#exam-save")).fire(); return null;
                });
                awaitFx(() -> ((TableView<?>) stage.getScene().lookup("#teacher-exam-templates")).getItems().size() == 1);
                assertEquals("Đề mạng mới", exams.listExams().getFirst().title()); assertNotNull(announce.get());
                fx(() -> { ((TextField) stage.getScene().lookup("#exam-template-search")).setText("không tồn tại"); return null; });
                assertEquals(0, fx(() -> ((TableView<?>) stage.getScene().lookup("#teacher-exam-templates")).getItems().size()));
                fx(() -> {
                    ((TextField) stage.getScene().lookup("#exam-template-search")).clear();
                    ((Button) stage.getScene().lookup("#exam-templates-refresh")).fire(); return null;
                });
                awaitFx(() -> !stage.getScene().lookup("#exam-templates-refresh").isDisabled());
                fx(() -> { snapshot(stage.getScene().getRoot(), "exam-template-workspace"); return null; });
            } finally {
                fx(() -> {
                    if (notice("#exam-editor") != null) notice("#exam-editor").getScene().getWindow().hide();
                    view[0].dispose(); stage.close(); return null;
                });
            }
        }
    }

    @BeforeAll
    static void initializeToolkit() throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.complete(null);
        });
        ready.get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void shutdownToolkit() { Platform.exit(); }

    @Test
    void recoveredSplitRosterKeepsSubmissionStatusAcrossReconnectAndClassChanges() throws Exception {
        try (var factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:progress_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var teacher = new TeacherAccountService(factory::createEntityManager).create("gvprogress", "Giảng viên", "Matkhau123!");
            var bank = new QuestionBankService(factory::createEntityManager, teacher.id());
            var subject = bank.createSubject("Mạng");
            var classes = new ClassService(factory::createEntityManager, teacher.id());
            var a = classes.create("A", "Lớp A", "HK1", subject.id());
            var b = classes.create("B", "Lớp B", "HK1", subject.id());
            var csv = RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n1,ST001,An,12/05/2003,C1\n2,ST002,Bình,12/05/2003,C1\n3,ST003,Cường,12/05/2003,C1");
            classes.importRoster(a.id(), csv); classes.importRoster(b.id(), csv);
            var exams = new ExamService(factory::createEntityManager, teacher.id());
            var q = bank.createQuestion(new BankQuestionDraft(subject.id(), 1, new ExamDraft.QuestionDraft(
                    "Giao thức?", QuestionType.MULTIPLE_CHOICE, "TCP", "UDP", "IP", "ICMP", "A")));
            var template = exams.createExamFromBank(new BankExamDraft("Đề", 20, subject.id(), List.of(q.id())));
            var shifts = new com.ptit.lanexam.server.service.ExamShiftService(factory::createEntityManager, teacher.id());
            var round = exams.activateExam(template.id(), a.id(), shifts.split(a.id(), 3).getFirst().id(), 4);
            exams.startAttempt("ST001", round.getId()); exams.startAttempt("ST002", round.getId());
            exams.submit("ST001", new SubmitExamRequest(round.getId(), Map.of()));
            var app = new ServerApp(factory::createEntityManager, () -> {}, 0);
            Stage stage = fx(() -> { var s = new Stage(); app.start(s); return s; });
            var peer = new com.ptit.lanexam.client.network.LanClient();
            try {
                awaitFx(() -> stage.getScene().lookup("#teacher-login-submit") != null);
                loginTeacherInUi(stage, "gvprogress");
                assertTrue(app.getListeningPort() > 0);
                peer.connect("127.0.0.1", app.getListeningPort());
                peer.send(JsonUtil.message(MessageType.LOGIN, new LoginRequest("ST001", "2003-05-12")));
                fx(() -> { ((Button) stage.getScene().lookup("#nav-room")).fire(); return null; });
                selectProgressClass(stage, a.id());
                awaitFx(() -> progressCell(stage, 0, "Kết nối").equals("Online") && progressCell(stage, 0, "Bài thi của ca này").equals("Đã nộp"));
                assertEquals("Offline", fx(() -> progressCell(stage, 1, "Kết nối")));
                assertEquals("Đang làm", fx(() -> progressCell(stage, 1, "Bài thi của ca này")));
                assertEquals("Chưa vào bài", fx(() -> progressCell(stage, 2, "Bài thi của ca này")));
                var codes = exams.roundProgress(a.id(), shifts.list(a.id()).getFirst().id()).orElseThrow();
                for (int index = 0; index < 3; index++) {
                    int row = index; String code = "ST%03d".formatted(index + 1);
                    assertEquals(codes.variantCode(code), fx(() -> progressCell(stage, row, "Mã đề")));
                    assertTrue(codes.variantCode(code).matches("10[1-4]"));
                }
                for (int index = 0; index < 3; index++) exams.events().focusReturned(round.getId(), "ST002", UUID.randomUUID().toString(),
                        Instant.now().minusSeconds(4), Instant.now());
                awaitFx(() -> progressCell(stage, 1, "Cảnh báo").contains("3 lần") && progressCell(stage, 1, "Cảnh báo").contains("Cần kiểm tra"));
                fx(() -> { ((Button) stage.getScene().lookup("#nav-bank")).fire(); return null; });
                awaitFx(() -> ((Button) stage.getScene().lookup("#teacher-notifications")).getText().endsWith("1")
                        && stage.getScene().lookup("#teacher-notification-toast").isVisible());
                fx(() -> {
                    assertTrue(((Label) stage.getScene().lookup("#teacher-notification-text")).getText().contains("ST002"));
                    ((Button) stage.getScene().lookup("#teacher-notifications")).fire(); return null;
                });
                awaitFx(() -> notificationCenter() != null && ((TableView<?>) notificationCenter().lookup("#teacher-notification-list")).getItems().size() == 1);
                fx(() -> {
                    var table = (TableView<?>) notificationCenter().lookup("#teacher-notification-list");
                    table.getSelectionModel().selectFirst(); snapshot(notificationCenter(), "teacher-notification-center"); return null;
                });
                Platform.runLater(() -> ((Button) notificationCenter().lookup("#teacher-notification-open")).fire());
                awaitFx(() -> notice("#exam-warning-journal") != null
                        && ((TableView<?>) notice("#exam-warning-journal").lookup("#warning-journal-events")).getItems().size() == 3);
                fx(() -> {
                    assertTrue(notice("#exam-warning-journal").lookup("#warning-journal-remind").isDisabled(), "Offline students cannot receive a reminder");
                    assertFalse(notice("#exam-warning-journal").lookup("#warning-journal-disqualify").isDisabled(), "An offline active attempt can be disqualified");
                    snapshot(notice("#exam-warning-journal"), "exam-warning-journal");
                    ((Button) notice("#exam-warning-journal").lookup("#warning-journal-review")).fire(); return null;
                });
                awaitFx(() -> !((Label) notice("#exam-warning-journal").lookup("#warning-journal-state")).getText().contains("Cần kiểm tra"));
                fx(() -> { ((Button) notice("#exam-warning-journal").lookupButton(ButtonType.CLOSE)).fire(); return null; });
                awaitFx(() -> ((TableView<?>) notificationCenter().lookup("#teacher-notification-list")).getItems().isEmpty()
                        && ((Button) stage.getScene().lookup("#teacher-notifications")).getText().endsWith("0"));
                fx(() -> {
                    ((Stage) notificationCenter().getScene().getWindow()).close();
                    ((Button) stage.getScene().lookup("#nav-room")).fire(); return null;
                });
                awaitFx(() -> !progressCell(stage, 1, "Cảnh báo").contains("Cần kiểm tra"));
                selectProgressClass(stage, b.id());
                assertEquals("Chưa mở thi", fx(() -> progressCell(stage, 0, "Bài thi của ca này")));
                assertEquals("—", fx(() -> progressCell(stage, 0, "Mã đề")));
                assertEquals("0 lần", fx(() -> progressCell(stage, 1, "Cảnh báo")));
                selectProgressClass(stage, a.id());
                assertEquals("Đã nộp", fx(() -> progressCell(stage, 0, "Bài thi của ca này")));
                assertEquals(codes.variantCode("ST001"), fx(() -> progressCell(stage, 0, "Mã đề")));
                peer.close();
                awaitFx(() -> progressCell(stage, 0, "Kết nối").equals("Offline"));
                assertEquals("Đã nộp", fx(() -> progressCell(stage, 0, "Bài thi của ca này")));
                try (var em = factory.createEntityManager()) {
                    em.getTransaction().begin();
                    em.createQuery("update Exam e set e.deadlineAt = :past where e.id = :id")
                            .setParameter("past", Instant.now().minusSeconds(1)).setParameter("id", round.getId()).executeUpdate();
                    em.createQuery("update ExamAttempt a set a.deadlineAt = :past where a.exam.id = :id")
                            .setParameter("past", Instant.now().minusSeconds(1)).setParameter("id", round.getId()).executeUpdate();
                    em.getTransaction().commit();
                }
                awaitFx(() -> progressCell(stage, 1, "Bài thi của ca này").equals("Đã nộp")
                        && progressCell(stage, 2, "Bài thi của ca này").equals("Vắng thi"));
                assertEquals(codes.variantCode("ST003"), fx(() -> progressCell(stage, 2, "Mã đề")));
                fx(() -> { snapshot(stage.getScene().getRoot(), "recovered-round-progress.png"); return null; });
            } finally { peer.close(); fx(() -> { app.stop(); stage.close(); return null; }); }
        }
    }

    @Test void groupedNotificationsAndConfirmedDisqualificationEndTheClientAndPreserveTheReason() throws Exception {
        try (var factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", "jdbc:h2:mem:discipline_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "", "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var teacher = new TeacherAccountService(factory::createEntityManager).create("gvdiscipline", "Giảng viên", "Matkhau123!");
            var bank = new QuestionBankService(factory::createEntityManager, teacher.id()); var subject = bank.createSubject("LTM");
            var classes = new ClassService(factory::createEntityManager, teacher.id()); var group = classes.create("A", "Nhóm A", "HK1", subject.id());
            classes.importRoster(group.id(), RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n1,ST001,An,12/05/2003,C1\n2,ST002,Bình,12/05/2003,C1"));
            var exams = new ExamService(factory::createEntityManager, teacher.id());
            var question = bank.createQuestion(new BankQuestionDraft(subject.id(), 1, new ExamDraft.QuestionDraft("Chọn TCP", QuestionType.MULTIPLE_CHOICE, "TCP", "UDP", "IP", "ICMP", "A")));
            var template = exams.createExamFromBank(new BankExamDraft("Đề", 20, subject.id(), List.of(question.id())));
            var round = exams.activateExam(template.id(), group.id());
            for (String code : List.of("ST001", "ST002")) {
                exams.startAttempt(code, round.getId());
                for (int i = 0; i < 3; i++) exams.events().focusReturned(round.getId(), code, UUID.randomUUID().toString(), Instant.now().minusSeconds(4), Instant.now());
            }
            var app = new ServerApp(factory::createEntityManager, () -> {}, 0); var client = new ClientApp();
            Stage teacherStage = fx(() -> { var stage = new Stage(); app.start(stage); return stage; }); Stage[] student = new Stage[1];
            try {
                awaitFx(() -> teacherStage.getScene().lookup("#teacher-login-submit") != null); loginTeacherInUi(teacherStage, "gvdiscipline");
                awaitFx(() -> ((Button) teacherStage.getScene().lookup("#teacher-notifications")).getText().endsWith("2"));
                fx(() -> { assertTrue(((Label) teacherStage.getScene().lookup("#teacher-notification-text")).getText().contains("2 sinh viên")); return null; });
                student[0] = fx(() -> {
                    var stage = new Stage(); client.start(stage);
                    ((TextField) stage.getScene().lookup("#login-port")).setText(Integer.toString(app.getListeningPort()));
                    ((TextField) stage.getScene().lookup("#login-code")).setText("ST001");
                    ((DatePicker) stage.getScene().lookup("#login-birth-date")).setValue(LocalDate.of(2003, 5, 12));
                    ((Button) stage.getScene().lookup("#login-connect")).fire(); return stage;
                });
                awaitFx(() -> student[0].getScene().lookup("#join-exam") instanceof Button button && !button.isDisabled());
                fx(() -> { ((Button) student[0].getScene().lookup("#join-exam")).fire(); return null; });
                awaitFx(() -> student[0].getScene().lookup("#exam-submit") != null);
                fx(() -> { ((Button) teacherStage.getScene().lookup("#exam-prepare")).fire(); return null; });
                awaitFx(() -> teacherStage.getScene().lookup("#exam-roster") instanceof TableView<?> table && table.getItems().size() == 2);
                Platform.runLater(() -> {
                    var table = (TableView<?>) teacherStage.getScene().lookup("#exam-roster"); table.applyCss(); table.layout();
                    table.lookupAll(".table-row-cell").stream().map(node -> (TableRow<?>) node).filter(row -> row.getIndex() == 0 && !row.isEmpty())
                            .findFirst().orElseThrow().lookupAll(".hyperlink").stream().map(node -> (Hyperlink) node).findFirst().orElseThrow().fire();
                });
                awaitFx(() -> notice("#exam-warning-journal") != null && !notice("#exam-warning-journal").lookup("#warning-journal-disqualify").isDisabled());
                requestUiDisqualification("Sử dụng tài liệu");
                fx(() -> { ((Button) notice("#exam-disqualification-confirmation").lookupButton(ButtonType.CANCEL)).fire(); return null; });
                assertTrue(exams.getExamResult("ST001", round.getId()).isEmpty());
                requestUiDisqualification("Sử dụng tài liệu");
                fx(() -> { ((Button) notice("#exam-disqualification-confirmation").lookupButton(ButtonType.OK)).fire(); return null; });
                awaitFx(() -> student[0].getScene().getRoot().lookupAll(".label").stream().anyMatch(node -> node instanceof Label label && label.getText().equals("Vi phạm quy chế")));
                assertEquals(0.0, exams.getExamResult("ST001", round.getId()).orElseThrow().getScore());
                fx(() -> {
                    assertNull(student[0].getScene().lookup("#exam-submit")); snapshot(student[0].getScene().getRoot(), "student-disqualified-result");
                    ((Button) notice("#exam-warning-journal").lookupButton(ButtonType.CLOSE)).fire(); return null;
                });
                awaitFx(() -> ((Button) teacherStage.getScene().lookup("#teacher-notifications")).getText().endsWith("1"));
            } finally { fx(() -> { client.stop(); if (student[0] != null) student[0].close(); app.stop(); teacherStage.close(); return null; }); }
        }
    }
    private static void requestUiDisqualification(String reason) throws Exception {
        Platform.runLater(() -> ((Button) notice("#exam-warning-journal").lookup("#warning-journal-disqualify")).fire());
        awaitFx(() -> notice("#exam-disqualification-reason") != null);
        fx(() -> { ((TextField) notice("#exam-disqualification-reason").lookup(".text-field")).setText(reason);
            ((Button) notice("#exam-disqualification-reason").lookupButton(ButtonType.OK)).fire(); return null; });
        awaitFx(() -> notice("#exam-disqualification-confirmation") != null);
    }

    @Test void focusMonitorIgnoresOwnedDialogsAndStopsAfterTheAttempt() throws Exception {
        var reports = new java.util.concurrent.LinkedBlockingQueue<ExamFocusChange>();
        ExamFocusMonitor[] monitor = new ExamFocusMonitor[1];
        Stage[] windows = new Stage[3];
        fx(() -> {
            windows[0] = new Stage(); windows[0].setScene(new Scene(new Label("Exam"), 400, 200)); windows[0].show(); windows[0].requestFocus();
            monitor[0] = new ExamFocusMonitor(windows[0], reports::add); return null;
        });
        try {
            awaitFx(() -> windows[0].isFocused());
            fx(() -> { monitor[0].start(10L); return null; });
            fx(() -> {
                windows[1] = new Stage(); windows[1].initOwner(windows[0]); windows[1].setScene(new Scene(new Label("Owned dialog"), 200, 100));
                windows[1].show(); windows[1].requestFocus(); return null;
            });
            awaitFx(() -> windows[1].isFocused());
            fx(() -> null);
            assertTrue(reports.isEmpty(), "An owned dialog must count as focus inside the exam application");
            fx(() -> {
                windows[1].close(); windows[0].requestFocus();
                windows[2] = new Stage(); windows[2].setScene(new Scene(new Label("Other app"), 200, 100)); windows[2].show(); windows[2].requestFocus(); return null;
            });
            var left = reports.poll(3, TimeUnit.SECONDS); assertNotNull(left); assertFalse(left.focused());
            var stillAway = reports.poll(5, TimeUnit.SECONDS); assertNotNull(stillAway); assertFalse(stillAway.focused());
            assertEquals(left.episodeId(), stillAway.episodeId(), "Threshold confirmation belongs to the same episode");
            fx(() -> { windows[2].close(); windows[0].requestFocus(); return null; });
            var back = reports.poll(3, TimeUnit.SECONDS); assertNotNull(back); assertTrue(back.focused()); assertEquals(left.episodeId(), back.episodeId());
            fx(() -> { monitor[0].stop(); windows[0].setIconified(true); return null; });
            fx(() -> null); assertTrue(reports.isEmpty());
        } finally {
            fx(() -> { monitor[0].close(); for (Stage window : windows) if (window != null) window.close(); return null; });
        }
    }

    private static void selectProgressClass(Stage stage, Long id) throws Exception {
        awaitFx(() -> stage.getScene().lookup("#exam-class") instanceof ComboBox<?> box && !box.getItems().isEmpty() && !box.isDisabled());
        fx(() -> {
            var box = (ComboBox<com.ptit.lanexam.server.service.ClassInfo>) stage.getScene().lookup("#exam-class");
            box.setValue(box.getItems().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow()); return null;
        });
        awaitFx(() -> !stage.getScene().lookup("#exam-prepare").isDisabled());
        fx(() -> { ((Button) stage.getScene().lookup("#exam-prepare")).fire(); return null; });
        awaitFx(() -> ((TableView<?>) stage.getScene().lookup("#exam-roster")).getItems().size() == 3);
    }

    private static String progressCell(Stage stage, int index, String column) {
        var table = (TableView<?>) stage.getScene().lookup("#exam-roster");
        if (table == null || table.getItems().size() <= index) return "";
        return String.valueOf(table.getColumns().stream().filter(c -> c.getText().equals(column)).findFirst().orElseThrow().getCellObservableValue(index).getValue());
    }

    @Test
    void rosterIsReadOnlyAndSixtyExistingStudentsWithOneCsvDifferenceCanJoinAnotherClass() throws Exception {
        try (var factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:profile_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var bank = new QuestionBankService(factory::createEntityManager);
            var classes = new ClassService(factory::createEntityManager, null);
            var group = classes.create("PROFILE", "Nhóm thử", "HK1", bank.createSubject("Mạng").id());
            var source = RosterCsv.read(Path.of("src/test/resources/fixtures/student-roster-60.csv"));
            classes.importRoster(group.id(), source);
            var next = classes.create("NEXT", "Lớp mới", "HK1", bank.listSubjects().getFirst().id());
            var incoming = new java.util.ArrayList<>(source);
            var first = source.getFirst();
            incoming.set(0, new RosterCsv.StudentRow(first.line(), first.code(), "Tên trên CSV đã khác", first.birthDate().plusDays(1), "KHAC", first.ordinal()));
            var preview = classes.preview(next.id(), incoming);
            assertEquals(60, preview.count(ClassService.ImportStatus.ENROLL)); assertEquals(1, preview.profileDifferences());
            ClassManager[] view = new ClassManager[1];
            Stage stage = fx(() -> {
                var s = new Stage(); view[0] = new ClassManager(s, classes, bank, FXCollections.observableArrayList(), () -> {});
                s.setScene(com.ptit.lanexam.common.ui.AppUi.scene(view[0], 1100, 680)); s.show(); return s;
            });
            try {
                awaitFx(() -> stage.getScene().lookup("#class-selector") instanceof ComboBox<?> box && box.getItems().size() == 2);
                fx(() -> {
                    ((ComboBox<com.ptit.lanexam.server.service.ClassInfo>) stage.getScene().lookup("#class-selector")).setValue(group); return null;
                });
                awaitFx(() -> ((TableView<?>) stage.getScene().lookup("#class-roster")).getItems().size() == 60);
                fx(() -> {
                    stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                    var roster = (TableView<?>) stage.getScene().lookup("#class-roster");
                    assertFalse(roster.isEditable()); assertTrue(roster.lookupAll(".hyperlink").isEmpty());
                    assertNull(notice("#student-profile-editor"));
                    ((ComboBox<com.ptit.lanexam.server.service.ClassInfo>) stage.getScene().lookup("#class-selector")).setValue(next); return null;
                });
                Platform.runLater(() -> view[0].showPreview(next, preview));
                awaitFx(() -> notice("#class-import-preview") != null);
                fx(() -> {
                    var pane = notice("#class-import-preview"); var table = (TableView<?>) pane.lookup(".table-view");
                    assertEquals(first.fullName(), table.getColumns().get(2).getCellData(0));
                    assertEquals(RosterCsv.displayDate(first.birthDate()), table.getColumns().get(3).getCellData(0));
                    assertEquals(first.regularClass(), table.getColumns().get(4).getCellData(0));
                    assertTrue(table.getColumns().get(5).getCellData(0).toString().contains("Giữ hồ sơ"));
                    assertFalse(pane.lookup("#class-import-confirm").isDisabled());
                    snapshot(pane, "csv-reuse-existing-profile");
                    ((Button) pane.lookup("#class-import-confirm")).fire(); return null;
                });
                awaitFx(() -> notice("#class-import-preview") == null && ((TableView<?>) stage.getScene().lookup("#class-roster")).getItems().size() == 60);
                assertEquals(classes.roster(group.id()), classes.roster(next.id()));
                var auth = new AuthService(factory::createEntityManager);
                assertTrue(auth.authenticate(first.code(), first.birthDate().toString()).isPresent());
                assertTrue(auth.authenticate(first.code(), first.birthDate().plusDays(1).toString()).isEmpty());
                try (var em = factory.createEntityManager()) { assertEquals(60L, em.createQuery("select count(s) from Student s", Long.class).getSingleResult()); }
            } finally { fx(() -> { view[0].dispose(); stage.close(); return null; }); }
        }
    }

    @Test
    void schoolCsvPreviewShowsFiveColumnsAndCommitsRegularClass() throws Exception {
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:school_csv_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var bank = new QuestionBankService(factory::createEntityManager);
            var classes = new ClassService(factory::createEntityManager, null);
            var group = classes.create("LTM-01", "Nhóm Lập trình mạng", "2026 HK1", bank.createSubject("Lập trình mạng").id());
            var preview = classes.preview(group.id(), RosterCsv.parse(RosterCsv.sample()));
            ClassManager[] view = new ClassManager[1];
            Stage stage = fx(() -> {
                var s = new Stage(); view[0] = new ClassManager(s, classes, bank, FXCollections.observableArrayList(), () -> {});
                s.setScene(com.ptit.lanexam.common.ui.AppUi.scene(view[0], 1100, 680)); s.show(); return s;
            });
            try {
                awaitFx(() -> !view[0].isDisabled());
                fx(() -> { Platform.runLater(() -> view[0].showPreview(group, preview)); return null; });
                awaitFx(() -> notice("#class-import-preview") != null);
                fx(() -> {
                    var pane = notice("#class-import-preview");
                    var table = (TableView<?>) pane.lookup(".table-view");
                    assertEquals(6, table.getColumns().size());
                    assertEquals("1", table.getColumns().get(0).getCellData(0));
                    assertEquals("B23DCCN011", table.getColumns().get(1).getCellData(0));
                    assertEquals("07/11/2005", table.getColumns().get(3).getCellData(0));
                    assertEquals("D23CNPM05", table.getColumns().get(4).getCellData(0));
                    snapshot(pane, "school-csv-five-column-preview");
                    ((Button) pane.lookup("#class-import-confirm")).fire(); return null;
                });
                awaitFx(() -> notice("#class-import-preview") == null && stage.getScene().lookup("#class-roster") instanceof TableView<?> table && table.getItems().size() == 2);
                fx(() -> {
                    var table = (TableView<?>) stage.getScene().lookup("#class-roster");
                    assertEquals("07/11/2005", table.getColumns().get(3).getCellData(0));
                    assertEquals("D23CNPM05", table.getColumns().get(4).getCellData(0));
                    snapshot(stage.getScene().getRoot(), "school-csv-roster"); return null;
                });
            } finally { fx(() -> { view[0].dispose(); stage.close(); return null; }); }
        }
    }

    @Test
    void clickingBankQuestionOpensEditorAndRefreshesSavedContent() throws Exception {
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", "jdbc:h2:mem:question_click_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "", "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var bank = new QuestionBankService(factory::createEntityManager);
            var subject = bank.createSubject("Lập trình mạng");
            var original = bank.createQuestion(new BankQuestionDraft(subject.id(), 1,
                    new ExamDraft.QuestionDraft("Giao thức nào tin cậy?", QuestionType.MULTIPLE_CHOICE, "TCP", "UDP", "IP", "ICMP", "A")));
            var view = fx(() -> new com.ptit.lanexam.server.QuestionBankBrowser(bank));
            Stage stage = fx(() -> {
                var s = new Stage(); s.setScene(com.ptit.lanexam.common.ui.AppUi.scene(view, 1100, 680)); s.show(); return s;
            });
            try {
                awaitFx(() -> !stage.getScene().getRoot().lookupAll(".hyperlink").isEmpty());
                fx(() -> {
                    assertNull(view.lookup("#bank-question-preview"), "Không hiển thị ô xem trước dưới bảng quản lý.");
                    var link = (Hyperlink) view.lookupAll(".hyperlink").stream()
                            .filter(node -> node instanceof Hyperlink h && h.getText().equals(original.content())).findFirst().orElseThrow();
                    Platform.runLater(link::fire); return null;
                });
                awaitFx(() -> notice("#question-editor") != null);
                fx(() -> {
                    var pane = notice("#question-editor");
                    assertEquals(original.content(), ((TextArea) pane.lookup("#question-content")).getText());
                    ((TextArea) pane.lookup("#question-content")).setText("Câu hỏi đã chỉnh sửa");
                    snapshot(pane, "bank-question-click-editor");
                    ((Button) pane.lookup("#question-save")).fire(); return null;
                });
                awaitFx(() -> view.lookup("#bank-questions") instanceof TableView<?> table
                        && table.getItems().size() == 1
                        && ((BankQuestionInfo) table.getItems().getFirst()).content().equals("Câu hỏi đã chỉnh sửa"));
                var saved = bank.listQuestions(subject.id(), null).getFirst();
                assertEquals(original.id(), saved.id());
                assertEquals("Câu hỏi đã chỉnh sửa", saved.content());
                fx(() -> { snapshot(view, "bank-without-inline-preview"); return null; });
            } finally {
                fx(() -> {
                    var pane = notice("#question-editor"); if (pane != null) pane.getScene().getWindow().hide();
                    view.dispose(); stage.close(); return null;
                });
            }
        }
    }

    @Test
    void wordImportPreviewsAllLevelsCancelsSafelyCommitsAndPreventsDuplicateReimport() throws Exception {
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", "jdbc:h2:mem:word_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "", "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var bank = new QuestionBankService(factory::createEntityManager); var subject = bank.createSubject("Lập trình mạng");
            com.ptit.lanexam.server.QuestionBankBrowser[] view = new com.ptit.lanexam.server.QuestionBankBrowser[1];
            Stage stage = fx(() -> {
                var s = new Stage(); view[0] = new com.ptit.lanexam.server.QuestionBankBrowser(bank);
                s.setScene(com.ptit.lanexam.common.ui.AppUi.scene(view[0], 1100, 680)); s.show(); return s;
            });
            Path sample = Path.of("src/main/resources/templates/question-bank-template.docx");
            try {
                awaitFx(() -> !stage.getScene().lookup("#bank-import-word").isDisabled());
                fx(() -> { view[0].importWordFile(sample); return null; });
                awaitFx(() -> notice("#bank-word-preview") != null);
                fx(() -> {
                    var pane = notice("#bank-word-preview"); var rows = (TableView<?>) pane.lookup("#bank-word-rows");
                    assertEquals(6, rows.getItems().size()); assertEquals("1", rows.getColumns().get(1).getCellData(0));
                    assertEquals("6", rows.getColumns().get(1).getCellData(5));
                    assertFalse(pane.lookup("#bank-word-confirm").isDisabled());
                    String detail = ((TextArea) pane.lookup("#bank-word-detail")).getText();
                    assertTrue(detail.contains("A. UDP")); assertTrue(detail.contains("B. TCP")); assertTrue(detail.contains("Đáp án: B"));
                    snapshot(pane, "word-question-import-preview");
                    ((Button) pane.lookup("#bank-word-cancel")).fire(); return null;
                });
                assertTrue(bank.listQuestions(subject.id(), null).isEmpty());
                awaitFx(() -> !view[0].isDisabled());
                fx(() -> { view[0].importWordFile(sample); return null; });
                awaitFx(() -> notice("#bank-word-preview") != null);
                fx(() -> { ((Button) notice("#bank-word-preview").lookup("#bank-word-confirm")).fire(); return null; });
                awaitFx(() -> stage.getScene().lookup("#bank-questions") instanceof TableView<?> table && table.getItems().size() == 6 && !view[0].isDisabled());
                assertEquals(6, bank.listQuestions(subject.id(), null).size());
                fx(() -> { view[0].importWordFile(sample); return null; });
                awaitFx(() -> notice("#bank-word-preview") != null);
                fx(() -> {
                    var pane = notice("#bank-word-preview"); assertTrue(pane.lookup("#bank-word-confirm").isDisabled());
                    ((Button) pane.lookup("#bank-word-cancel")).fire(); return null;
                });
            } finally { fx(() -> { view[0].dispose(); stage.close(); return null; }); }
        }
    }

    @Test
    void oldRosterShowsMissingSttAndReimportRepairsSixtyExistingRows() throws Exception {
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", "jdbc:h2:mem:ordinal_repair_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "", "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var bank = new QuestionBankService(factory::createEntityManager); var classes = new ClassService(factory::createEntityManager, null);
            var group = classes.create("LTM-01", "Nhóm 01", "HK1", bank.createSubject("Lập trình mạng").id());
            var source = RosterCsv.read(Path.of(java.util.Objects.requireNonNull(ExamWorkflowUiTest.class.getResource("/fixtures/student-roster-60.csv")).toURI()));
            classes.importRoster(group.id(), source.stream().map(r -> new RosterCsv.StudentRow(r.line(), r.code(), r.fullName(), r.birthDate(), r.regularClass())).toList());
            ClassManager[] view = new ClassManager[1];
            Stage stage = fx(() -> {
                var s = new Stage(); view[0] = new ClassManager(s, classes, bank, FXCollections.observableArrayList(), () -> {});
                s.setScene(com.ptit.lanexam.common.ui.AppUi.scene(view[0], 1100, 680)); s.show(); return s;
            });
            try {
                awaitFx(() -> stage.getScene().lookup("#class-roster") instanceof TableView<?> table && table.getItems().size() == 60);
                fx(() -> {
                    var table = (TableView<?>) stage.getScene().lookup("#class-roster"); assertEquals("—", table.getColumns().getFirst().getCellData(0));
                    assertTrue(stage.getScene().lookup("#class-restore-ordinals").isVisible());
                    assertTrue(((Label) stage.getScene().lookup("#class-ordinal-notice")).getText().contains("60 sinh viên chưa có STT"));
                    snapshot(stage.getScene().getRoot(), "roster-missing-stt-help"); return null;
                });
                var preview = classes.preview(group.id(), source); assertEquals(60, preview.count(ClassService.ImportStatus.UPDATE));
                fx(() -> { Platform.runLater(() -> view[0].showPreview(group, preview)); return null; });
                awaitFx(() -> notice("#class-import-preview") != null);
                fx(() -> { ((Button) notice("#class-import-preview").lookup("#class-import-confirm")).fire(); return null; });
                awaitFx(() -> notice("#class-import-preview") == null && stage.getScene().lookup("#class-roster") instanceof TableView<?> table
                        && table.getItems().size() == 60 && "1".equals(table.getColumns().getFirst().getCellData(0)));
                fx(() -> {
                    var table = (TableView<?>) stage.getScene().lookup("#class-roster");
                    assertEquals("60", table.getColumns().getFirst().getCellData(59));
                    assertFalse(stage.getScene().lookup("#class-restore-ordinals").getParent().isVisible());
                    assertFalse(stage.getScene().lookup("#class-restore-ordinals").getParent().isManaged());
                    snapshot(stage.getScene().getRoot(), "roster-stt-restored-from-csv"); return null;
                });
                assertEquals(60, classes.roster(group.id()).size());
            } finally { fx(() -> { view[0].dispose(); stage.close(); return null; }); }
        }
    }

    @Test
    void wordPreviewHighlightsInvalidQuestionAndDisablesConfirm() throws Exception {
        var parsed = com.ptit.lanexam.server.service.QuestionWordImport.parseText("MỨC 2\nCâu 4: Câu sai mẫu\nA. A\nB. B\nD. D\nĐáp án: E");
        var item = parsed.items().getFirst();
        var preview = new QuestionBankService.WordPreview(List.of(new QuestionBankService.WordRow(item, QuestionBankService.WordStatus.INVALID, String.join(" ", item.errors()))), List.of());
        Stage owner = fx(() -> { var s = new Stage(); s.setScene(new Scene(new javafx.scene.layout.VBox(), 500, 300)); s.show(); return s; });
        try {
            fx(() -> { Platform.runLater(() -> com.ptit.lanexam.server.QuestionWordPreviewDialog.show(owner, new SubjectInfo(1L, "Lập trình mạng", 0), preview)); return null; });
            awaitFx(() -> notice("#bank-word-preview") != null);
            fx(() -> {
                var pane = notice("#bank-word-preview"); assertTrue(pane.lookup("#bank-word-confirm").isDisabled());
                String detail = ((TextArea) pane.lookup("#bank-word-detail")).getText();
                assertTrue(detail.contains("Thiếu lựa chọn C")); assertTrue(detail.contains("đúng một chữ"));
                snapshot(pane, "word-question-import-errors");
                ((Button) pane.lookup("#bank-word-cancel")).fire(); return null;
            });
        } finally { fx(() -> { owner.close(); return null; }); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void organizerPreparesBeforeStartingAndStudentAutomaticallyReceivesOwnExamAndResumes() throws Exception {
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:mcq_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var teacher = new TeacherAccountService(factory::createEntityManager).create("gvnav", "Nguyễn Thị Lan", "Matkhau123!");
            var bank = new QuestionBankService(factory::createEntityManager, teacher.id());
            var subject = bank.createSubject("Lập trình mạng");
            var classes = new ClassService(factory::createEntityManager, teacher.id());
            var a = classes.create("LTM-A", "Nhóm 01", "2026 HK1", subject.id());
            var b = classes.create("LTM-B", "Nhóm 02", "2026 HK1", subject.id());
            classes.importRoster(a.id(), RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n90,ST001,Nguyễn Văn An,12/05/2003,C1\n10,ST003,Lê Cường,12/05/2003,C1"));
            classes.importRoster(b.id(), RosterCsv.parse("student_code,full_name,birth_date\nST002,Trần Bình,2003-05-12"));
            var exams = new ExamService(factory::createEntityManager, teacher.id());
            var question = bank.createQuestion(new BankQuestionDraft(subject.id(), 1,
                    new ExamDraft.QuestionDraft("Giao thức truyền tin cậy?", QuestionType.MULTIPLE_CHOICE, "TCP", "UDP", "IP", "ICMP", "A")));
            exams.createExamFromBank(new BankExamDraft("Kiểm tra mạng máy tính", 20, subject.id(), List.of(question.id())));
            ServerApp app = new ServerApp(factory::createEntityManager, () -> {}, 0);
            Stage teacherStage = fx(() -> { var s = new Stage(); app.start(s); return s; });
            ClientApp first = new ClientApp(), resumed = new ClientApp();
            Stage[] students = new Stage[2];
            try {
                awaitFx(() -> teacherStage.getScene().lookup("#teacher-login-submit") != null);
                loginTeacherInUi(teacherStage, "gvnav");
                fx(() -> {
                    assertEquals(4, teacherStage.getScene().getRoot().lookupAll(".nav-button").size());
                    assertNull(teacherStage.getScene().lookup("#nav-assessments"));
                    assertNull(teacherStage.getScene().lookup("#server-start"));
                    assertNull(teacherStage.getScene().lookup("#server-stop")); return null;
                });
                assertTrue(app.getListeningPort() > 0);
                awaitFx(() -> teacherStage.getScene().lookup("#exam-class") instanceof ComboBox<?> box && box.getItems().size() == 2);
                fx(() -> {
                    ComboBox<com.ptit.lanexam.server.service.ClassInfo> group = (ComboBox<com.ptit.lanexam.server.service.ClassInfo>) teacherStage.getScene().lookup("#exam-class");
                    group.setValue(group.getItems().stream().filter(g -> g.id().equals(a.id())).findFirst().orElseThrow());
                    return null;
                });
                awaitFx(() -> !teacherStage.getScene().lookup("#exam-split").isDisabled());
                fx(() -> {
                    ((Spinner<Integer>) teacherStage.getScene().lookup("#exam-shift-capacity")).getValueFactory().setValue(1);
                    ((Button) teacherStage.getScene().lookup("#exam-split")).fire(); return null;
                });
                awaitFx(() -> teacherStage.getScene().lookup("#exam-shift") instanceof ComboBox<?> box && box.getItems().size() == 2
                        && !teacherStage.getScene().lookup("#exam-prepare").isDisabled());
                fx(() -> {
                    ((ComboBox<?>) teacherStage.getScene().lookup("#exam-shift")).getSelectionModel().selectLast();
                    ((Button) teacherStage.getScene().lookup("#exam-prepare")).fire(); return null;
                });
                awaitFx(() -> teacherStage.getScene().lookup("#exam-roster") instanceof TableView<?> table && table.getItems().size() == 1
                        && !teacherStage.getScene().lookup("#exam-start").isDisabled());
                assertTrue(exams.findOffer("ST001", null).isEmpty());
                assertThrows(IllegalStateException.class, exams::getActiveExam);
                fx(() -> { snapshot(teacherStage.getScene().getRoot(), "mcq-teacher-prepared"); return null; });
                students[0] = fx(() -> {
                    var s = new Stage(); first.start(s);
                    ((TextField) s.getScene().lookup("#login-port")).setText(Integer.toString(app.getListeningPort()));
                    ((TextField) s.getScene().lookup("#login-code")).setText("ST001");
                    DatePicker typedDate = (DatePicker) s.getScene().lookup("#login-birth-date");
                    typedDate.getEditor().setText("12/05/2003");
                    assertNull(typedDate.getValue(), "Tái hiện gõ ngày nhưng DatePicker chưa commit giá trị.");
                    ((Button) s.getScene().lookup("#login-connect")).fire(); return s;
                });
                awaitFx(() -> students[0].getScene().lookup("#join-exam") != null);
                fx(() -> {
                    assertTrue(students[0].getScene().lookup("#join-exam").isDisabled());
                    assertNull(students[0].getScene().lookup("#student-class-selector"));
                    assertNull(students[0].getScene().lookup("#student-nav-classes"));
                    snapshot(students[0].getScene().getRoot(), "mcq-student-waiting");
                    ((Button) teacherStage.getScene().lookup("#exam-start")).fire(); return null;
                });
                awaitFx(() -> !students[0].getScene().lookup("#join-exam").isDisabled());
                Long round = exams.getActiveExam().getId();
                assertTrue(exams.findOffer("ST002", round).isEmpty());
                assertTrue(exams.findOffer("ST003", round).isEmpty(), "Same class but another shift cannot receive the exam");
                assertThrows(IllegalArgumentException.class, () -> exams.startAttempt("ST003", round));
                fx(() -> { ((Button) students[0].getScene().lookup("#join-exam")).fire(); return null; });
                awaitFx(() -> students[0].getScene().lookup("#exam-submit") != null);
                fx(() -> {
                    ((RadioButton) students[0].getScene().lookup("#question-card-1").lookupAll(".radio-button").stream()
                            .filter(n -> ((RadioButton) n).getText().contains("TCP")).findFirst().orElseThrow()).fire();
                    snapshot(students[0].getScene().getRoot(), "mcq-student-taking-exam"); return null;
                });
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (System.nanoTime() < end && exams.getOpenAttempt("ST001").orElseThrow().getSavedAnswers().isEmpty()) Thread.sleep(25);
                assertFalse(exams.getOpenAttempt("ST001").orElseThrow().getSavedAnswers().isEmpty());
                long deadline = exams.getOpenAttempt("ST001").orElseThrow().getDeadlineEpochMillis();
                fx(() -> { first.stop(); students[0].close(); return null; });
                students[1] = fx(() -> {
                    var s = new Stage(); resumed.start(s);
                    ((TextField) s.getScene().lookup("#login-port")).setText(Integer.toString(app.getListeningPort()));
                    ((TextField) s.getScene().lookup("#login-code")).setText("ST001");
                    ((DatePicker) s.getScene().lookup("#login-birth-date")).setValue(LocalDate.of(2003, 5, 12));
                    ((Button) s.getScene().lookup("#login-connect")).fire(); return s;
                });
                awaitFx(() -> students[1].getScene().lookup("#join-exam") instanceof Button button && !button.isDisabled());
                fx(() -> {
                    assertEquals("Tiếp tục làm bài", ((Button) students[1].getScene().lookup("#join-exam")).getText());
                    ((Button) students[1].getScene().lookup("#join-exam")).fire(); return null;
                });
                awaitFx(() -> students[1].getScene().lookup("#exam-submit") != null);
                fx(() -> {
                    assertTrue(((RadioButton) students[1].getScene().lookup("#question-card-1").lookupAll(".radio-button").stream()
                            .filter(n -> ((RadioButton) n).getText().contains("TCP")).findFirst().orElseThrow()).isSelected());
                    assertEquals("1 / 1 đã trả lời", ((Label) students[1].getScene().lookup("#exam-progress")).getText());
                    return null;
                });
                assertEquals(deadline, exams.getActiveExam().getDeadlineEpochMillis());
                awaitFx(() -> !teacherStage.getScene().lookup("#exam-end").isDisabled());
                Platform.runLater(() -> ((Button) teacherStage.getScene().lookup("#exam-end")).fire());
                awaitFx(() -> notice("#exam-end-confirmation") != null);
                fx(() -> { ((Button) notice("#exam-end-confirmation").lookupButton(ButtonType.CANCEL)).fire(); return null; });
                assertEquals(deadline, exams.getActiveExam().getDeadlineEpochMillis(), "Cancel must not end the round");
                Platform.runLater(() -> ((Button) teacherStage.getScene().lookup("#exam-end")).fire());
                awaitFx(() -> notice("#exam-end-confirmation") != null);
                fx(() -> { ((Button) notice("#exam-end-confirmation").lookupButton(ButtonType.OK)).fire(); return null; });
                awaitFx(() -> students[1].getScene().getRoot().lookupAll(".label").stream().anyMatch(n -> n instanceof Label label && label.getText().startsWith("Điểm:")));
                assertTrue(app.getListeningPort() > 0);
                assertTrue(exams.getActiveExam().getDeadlineEpochMillis() < deadline);
                fx(() -> { ((Button) teacherStage.getScene().lookup("#nav-results")).fire(); return null; });
                awaitFx(() -> teacherStage.getScene().lookup("#teacher-results") instanceof TableView<?> table && table.getItems().size() == 1);
                fx(() -> {
                    assertNull(teacherStage.getScene().lookup("#teacher-practical-results"));
                    var resultTable = (TableView<?>) teacherStage.getScene().lookup("#teacher-results");
                    assertEquals("10.00", resultTable.getColumns().stream().filter(c -> c.getText().equals("Điểm")).findFirst().orElseThrow().getCellData(0));
                    assertTrue(resultTable.getColumns().stream().filter(c -> c.getText().equals("Mã đề")).findFirst().orElseThrow().getCellData(0).toString().matches("10[1-4]"));
                    snapshot(teacherStage.getScene().getRoot(), "mcq-teacher-results");
                    assertFalse(teacherStage.getScene().lookup("#results-export").isDisabled()); return null;
                });
                assertEquals(1, exams.listResults().size());
            } finally {
                fx(() -> { first.stop(); resumed.stop(); for (Stage s : students) if (s != null) s.close(); app.stop(); teacherStage.close(); return null; });
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullClassPreparationExpiresOnRosterChangeAndOpenedUiUsesFrozenRoster() throws Exception {
        try (var factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:frozen_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var teacher = new TeacherAccountService(factory::createEntityManager).create("gvfrozen", "Giảng viên", "Matkhau123!");
            var bank = new QuestionBankService(factory::createEntityManager, teacher.id());
            var subject = bank.createSubject("Mạng");
            var classes = new ClassService(factory::createEntityManager, teacher.id());
            var group = classes.create("FROZEN", "Nhóm thi", "HK1", subject.id());
            classes.importRoster(group.id(), RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n1,FR001,Sinh viên một,12/05/2003,C1"));
            var question = bank.createQuestion(new BankQuestionDraft(subject.id(), 1,
                    new ExamDraft.QuestionDraft("Giao thức?", QuestionType.MULTIPLE_CHOICE, "TCP", "UDP", "IP", "ICMP", "A")));
            var exams = new ExamService(factory::createEntityManager, teacher.id());
            exams.createExamFromBank(new BankExamDraft("Đề thử", 1, subject.id(), List.of(question.id())));
            var app = new ServerApp(factory::createEntityManager, () -> {}, 0);
            Stage stage = fx(() -> { var s = new Stage(); app.start(s); return s; });
            try {
                awaitFx(() -> stage.getScene().lookup("#teacher-login-submit") != null);
                loginTeacherInUi(stage, "gvfrozen");
                awaitFx(() -> !stage.getScene().lookup("#exam-prepare").isDisabled());
                fx(() -> {
                    ((Spinner<Integer>) stage.getScene().lookup("#exam-variant-count")).getValueFactory().setValue(1);
                    ((Button) stage.getScene().lookup("#exam-prepare")).fire(); return null;
                });
                awaitFx(() -> !stage.getScene().lookup("#exam-start").isDisabled());
                classes.importRoster(group.id(), RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n2,FR002,Sinh viên hai,12/05/2003,C1"));
                awaitFx(() -> stage.getScene().lookup("#exam-start").isDisabled()
                        && ((TableView<?>) stage.getScene().lookup("#exam-roster")).getItems().isEmpty());
                fx(() -> { ((Button) stage.getScene().lookup("#exam-prepare")).fire(); return null; });
                awaitFx(() -> !stage.getScene().lookup("#exam-start").isDisabled()
                        && ((TableView<?>) stage.getScene().lookup("#exam-roster")).getItems().size() == 2);
                fx(() -> { ((Button) stage.getScene().lookup("#exam-start")).fire(); return null; });
                awaitFx(() -> stage.getScene().lookup("#exam-start").isDisabled()
                        && !stage.getScene().lookup("#exam-prepare").isDisabled());
                classes.importRoster(group.id(), RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n3,FR003,Sinh viên ba,12/05/2003,C1"));
                fx(() -> { ((Button) stage.getScene().lookup("#exam-prepare")).fire(); return null; });
                awaitFx(() -> !stage.getScene().lookup("#exam-prepare").isDisabled()
                        && ((TableView<?>) stage.getScene().lookup("#exam-roster")).getItems().size() == 2);
                fx(() -> { assertTrue(stage.getScene().lookup("#exam-start").isDisabled()); return null; });
                assertTrue(exams.findOffer("FR003", exams.getActiveExam().getId()).isEmpty());
                awaitFx(() -> progressCell(stage, 0, "Mã đề").equals("Gốc"));
                fx(() -> { ((Button) stage.getScene().lookup("#nav-results")).fire(); return null; });
                awaitFx(() -> stage.getScene().lookup("#teacher-results") instanceof TableView<?> table && table.getItems().size() == 2
                        && !stage.getScene().lookup("#results-refresh").isDisabled());
                fx(() -> {
                    var table = (TableView<?>) stage.getScene().lookup("#teacher-results");
                    assertEquals("Chưa vào bài", table.getColumns().stream().filter(c -> c.getText().equals("Trạng thái")).findFirst().orElseThrow().getCellData(0));
                    assertEquals("", table.getColumns().stream().filter(c -> c.getText().equals("Điểm")).findFirst().orElseThrow().getCellData(0));
                    return null;
                });
                expireUiRound(factory);
                fx(() -> { ((Button) stage.getScene().lookup("#results-refresh")).fire(); return null; });
                awaitFx(() -> !stage.getScene().lookup("#results-refresh").isDisabled()
                        && "Vắng thi".equals(((TableView<?>) stage.getScene().lookup("#teacher-results")).getColumns().stream()
                        .filter(c -> c.getText().equals("Trạng thái")).findFirst().orElseThrow().getCellData(0)));
                fx(() -> {
                    var table = (TableView<?>) stage.getScene().lookup("#teacher-results");
                    assertEquals(2, table.getItems().size());
                    assertEquals("", table.getColumns().stream().filter(c -> c.getText().equals("Nộp lúc")).findFirst().orElseThrow().getCellData(0));
                    assertFalse(stage.getScene().lookup("#results-export").isDisabled()); return null;
                });
                fx(() -> {
                    ((Button) stage.getScene().lookup("#nav-room")).fire();
                    ((Button) stage.getScene().lookup("#exam-prepare")).fire(); return null;
                });
                awaitFx(() -> !stage.getScene().lookup("#exam-start").isDisabled()
                        && ((TableView<?>) stage.getScene().lookup("#exam-roster")).getItems().size() == 3);
                assertEquals("—", fx(() -> progressCell(stage, 0, "Mã đề")), "New preparation must not show the previous round's code");
            } finally { fx(() -> { app.stop(); stage.close(); return null; }); }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void organizerSplitsSixtyCandidatesByCsvSttAndReloadsSavedShifts() throws Exception {
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:shift_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var teacher = new TeacherAccountService(factory::createEntityManager).create("gvshift", "Nguyễn Thị Lan", "Matkhau123!");
            var bank = new QuestionBankService(factory::createEntityManager, teacher.id());
            var subject = bank.createSubject("Lập trình mạng");
            var classes = new ClassService(factory::createEntityManager, teacher.id());
            var group = classes.create("LTM-01", "Lập trình mạng · Nhóm 01", "2026 HK1", subject.id());
            classes.importRoster(group.id(), RosterCsv.read(java.nio.file.Path.of(java.util.Objects.requireNonNull(ExamWorkflowUiTest.class.getResource("/fixtures/student-roster-60.csv")).toURI())));
            var question = bank.createQuestion(new BankQuestionDraft(subject.id(), 1,
                    new ExamDraft.QuestionDraft("Giao thức truyền tin cậy?", QuestionType.MULTIPLE_CHOICE, "TCP", "UDP", "IP", "ICMP", "A")));
            new ExamService(factory::createEntityManager, teacher.id()).createExamFromBank(
                    new BankExamDraft("Kiểm tra mạng máy tính", 20, subject.id(), List.of(question.id())));
            ServerApp app = new ServerApp(factory::createEntityManager, () -> {}, 0);
            Stage stage = fx(() -> { var s = new Stage(); app.start(s); return s; });
            try {
                awaitFx(() -> stage.getScene().lookup("#teacher-login-submit") != null);
                loginTeacherInUi(stage, "gvshift");
                awaitFx(() -> stage.getScene().lookup("#exam-split") != null && !stage.getScene().lookup("#exam-split").isDisabled());
                fx(() -> {
                    assertEquals(30, ((Spinner<Integer>) stage.getScene().lookup("#exam-shift-capacity")).getValue());
                    ((Button) stage.getScene().lookup("#exam-split")).fire(); return null;
                });
                awaitFx(() -> stage.getScene().lookup("#exam-shift") instanceof ComboBox<?> box && box.getItems().size() == 2
                        && !stage.getScene().lookup("#exam-prepare").isDisabled());
                fx(() -> { ((Button) stage.getScene().lookup("#exam-prepare")).fire(); return null; });
                awaitFx(() -> stage.getScene().lookup("#exam-roster") instanceof TableView<?> table && table.getItems().size() == 30);
                Long saved = fx(() -> {
                    TableView<ClassService.RosterStudent> table = (TableView<ClassService.RosterStudent>) stage.getScene().lookup("#exam-roster");
                    assertEquals(1, table.getItems().getFirst().ordinal()); assertEquals(30, table.getItems().getLast().ordinal());
                    assertFalse(stage.getScene().lookup("#exam-start").isDisabled(), "Server starts automatically after teacher login");
                    var selection = (ComboBox<com.ptit.lanexam.server.service.ExamShiftService.ShiftInfo>) stage.getScene().lookup("#exam-shift");
                    Long id = selection.getValue().id();
                    snapshot(stage.getScene().getRoot(), "mcq-teacher-split-shifts");
                    ((Button) stage.getScene().lookup("#exam-organizer-refresh")).fire(); return id;
                });
                awaitFx(() -> !stage.getScene().lookup("#exam-prepare").isDisabled());
                fx(() -> {
                    var selection = (ComboBox<com.ptit.lanexam.server.service.ExamShiftService.ShiftInfo>) stage.getScene().lookup("#exam-shift");
                    assertEquals(saved, selection.getValue().id());
                    selection.getSelectionModel().selectLast();
                    ((Button) stage.getScene().lookup("#exam-prepare")).fire(); return null;
                });
                awaitFx(() -> stage.getScene().lookup("#exam-roster") instanceof TableView<?> table && table.getItems().size() == 30);
                fx(() -> {
                    var table = (TableView<ClassService.RosterStudent>) stage.getScene().lookup("#exam-roster");
                    assertEquals(31, table.getItems().getFirst().ordinal()); assertEquals(60, table.getItems().getLast().ordinal());
                    return null;
                });
            } finally { fx(() -> { app.stop(); stage.hide(); return null; }); }
        }
    }
    @Test
    void automaticDeadlineSubmissionClosesAnOpenUnansweredConfirmationWithoutSubmittingTwice() throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        ExamDto round = new ExamDto(101L, "Đề kiểm tra hết giờ", 3, List.of(
                new QuestionDto(1L, "Chọn một đáp án", "MULTIPLE_CHOICE", List.of("A. Một", "B. Hai", "C. Ba", "D. Bốn"))));
        AuthService auth = new AuthService() {
            @Override public Optional<Student> authenticate(String code, String birthDate) {
                return Optional.of(new Student("ST001", "Nguyễn Văn An", LocalDate.of(2003, 5, 12)));
            }
            @Override public boolean isAccountLocked(String code) { return false; }
        };
        ExamService exams = new UiExamStub() {
            @Override public Optional<ExamDto> recoverPublishedExam() { return Optional.empty(); }

            @Override public void finalizeExpiredAttempts() {}
            @Override public List<ExamHistoryItem> listStudentHistory(String code) { return List.of(); }
            @Override public Optional<ExamOffer> findOffer(String code, Long id) { return Optional.of(new ExamOffer(101L, round.getTitle(), 60, false)); }
            @Override public Optional<ExamDto> startAttempt(String code, Long id) { return Optional.of(round); }
            @Override public Optional<SubmitResult> getExamResult(String code, Long id) { return Optional.empty(); }
            @Override public SubmitResult submit(String code, SubmitExamRequest request, Instant receivedAt) {
                submitted.incrementAndGet();
                return new SubmitResult(0, 0, 1, "Đã nộp khi hết giờ");
            }
        };
        ExamServer server = new ExamServer(new SessionRegistry(), auth, exams);
        server.start(0);
        ClientApp client = new ClientApp();
        Stage stage = fx(() -> {
            Stage created = new Stage(); client.start(created);
            ((DatePicker) created.getScene().lookup("#login-birth-date")).setValue(LocalDate.of(2003, 5, 12));
            ((TextField) created.getScene().lookup("#login-code")).setText("ST001");
            ((TextField) created.getScene().lookup("#login-port")).setText(Integer.toString(server.getPort()));
            ((Button) created.getScene().lookup("#login-connect")).fire(); return created;
        });
        try {
            awaitFx(() -> stage.getScene().lookup("#join-exam") instanceof Button join && !join.isDisabled());
            fx(() -> { ((Button) stage.getScene().lookup("#join-exam")).fire(); return null; });
            awaitFx(() -> stage.getScene().lookup("#exam-submit") != null);
            fx(() -> { Platform.runLater(() -> ((Button) stage.getScene().lookup("#exam-submit")).fire()); return null; });
            awaitFx(() -> notice("#exam-submit-confirmation") != null);
            awaitFx(() -> stage.getScene().getRoot().lookupAll(".label").stream()
                    .anyMatch(node -> node instanceof Label label && label.getText().startsWith("Điểm:")));
            assertEquals(1, submitted.get());
            assertNull(fx(() -> notice("#exam-submit-confirmation")));
        } finally {
            fx(() -> { client.stop(); stage.close(); return null; }); server.stop();
        }
    }


    @Test
    @SuppressWarnings("unchecked")
    void questionEditorValidatesDifficultyAndOnlyOffersMultipleChoice() throws Exception {
        SubjectInfo subject = new SubjectInfo(1L, "Lập trình mạng", 0);
        fx(() -> {
            Dialog<BankQuestionDraft> dialog = QuestionEditorDialog.create(null, List.of(subject), subject, null);
            dialog.show();
            var pane = dialog.getDialogPane();
            ((TextArea) pane.lookup("#question-content")).setText("Giao thức nào cung cấp truyền dữ liệu tin cậy?");
            ((TextField) pane.lookup("#question-option-a")).setText("UDP");
            ((TextField) pane.lookup("#question-option-b")).setText("IP");
            ((TextField) pane.lookup("#question-option-c")).setText("TCP");
            ((TextField) pane.lookup("#question-option-d")).setText("ICMP");
            ((ComboBox<String>) pane.lookup("#question-choice-answer")).setValue("C");
            Spinner<Integer> level = (Spinner<Integer>) pane.lookup("#question-difficulty");
            level.getEditor().setText("7");
            ((Button) pane.lookup("#question-save")).fire();
            assertTrue(dialog.isShowing(), "Độ khó ngoài 1–6 không được lưu.");
            level.getEditor().setText("6");
            snapshot(pane, "question-editor");
            ((Button) pane.lookup("#question-save")).fire();
            assertEquals(6, dialog.getResult().difficulty());
            assertEquals("C", dialog.getResult().question().correctAnswer());
            assertEquals(QuestionType.MULTIPLE_CHOICE, dialog.getResult().question().type());
            assertNull(pane.lookup("#question-type"));
            assertNull(pane.lookup("#question-text-answer"));
            return null;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void composingExamKeepsSelectionsAcrossFiltersButClearsThemOnSubjectChange() throws Exception {
        List<SubjectInfo> subjects = List.of(new SubjectInfo(1L, "Lập trình mạng", 3), new SubjectInfo(2L, "Cơ sở dữ liệu", 1));
        List<BankQuestionInfo> questions = List.of(
                new BankQuestionInfo(101L, 1L, "Lập trình mạng", 1, "Giao thức nào truyền dữ liệu tin cậy?", QuestionType.MULTIPLE_CHOICE,
                        "TCP", "UDP", "IP", "ICMP", "A"),
                new BankQuestionInfo(102L, 1L, "Lập trình mạng", 6, "Kỹ thuật nào tránh xử lý đồng thời gây mất dữ liệu?", QuestionType.MULTIPLE_CHOICE,
                        "Đóng socket", "Đồng bộ hóa", "Tăng timeout", "Bỏ kiểm tra", "B"),
                new BankQuestionInfo(103L, 1L, "Lập trình mạng", 3, "Lớp Java dùng để kết nối TCP?", QuestionType.MULTIPLE_CHOICE,
                        "Socket", "Scanner", "Thread", "String", "A"),
                new BankQuestionInfo(201L, 2L, "Cơ sở dữ liệu", 2, "Ngôn ngữ truy vấn cơ sở dữ liệu?", QuestionType.MULTIPLE_CHOICE,
                        "Java", "SQL", "C", "Python", "B"));
        QuestionBankService sample = new QuestionBankService() {
            @Override public List<SubjectInfo> listSubjects() { return subjects; }
            @Override public List<BankQuestionInfo> listQuestions(Long subject, Integer level) {
                return questions.stream().filter(q -> q.subjectId().equals(subject))
                        .filter(q -> level == null || q.difficulty() == level).toList();
            }
        };
        Dialog<BankExamDraft> dialog = fx(() -> {
            var created = ExamEditorDialog.create(null, sample);
            created.show();
            return created;
        });
        awaitFx(() -> bankTable(dialog).getItems().size() == 3 && bankReady(dialog));
        assertQuestionDetailsFromTable(bankTable(dialog));
        fx(() -> {
            assertTrue(dialog.getDialogPane().lookupAll(".text-area").isEmpty(), "Không còn hai ô xem trước trong cửa sổ tạo đề.");
            return null;
        });
        fx(() -> {
            bankTable(dialog).getSelectionModel().selectIndices(0, 1);
            ((Button) dialog.getDialogPane().lookup("#exam-add-selected")).fire();
            ((Button) dialog.getDialogPane().lookup("#exam-add-selected")).fire();
            assertEquals(2, selectedTable(dialog).getItems().size(), "Không thêm trùng câu hỏi.");
            ((ComboBox<Integer>) dialog.getDialogPane().lookup("#bank-difficulty")).setValue(6);
            return null;
        });
        assertQuestionDetailsFromTable(selectedTable(dialog));
        awaitFx(() -> bankTable(dialog).getItems().size() == 1 && bankReady(dialog));
        fx(() -> {
            assertEquals(102L, bankTable(dialog).getItems().getFirst().id());
            assertEquals(2, selectedTable(dialog).getItems().size(), "Lọc không được làm mất câu đã chọn.");
            assertNull(dialog.getDialogPane().lookup("#bank-type"));
            ((ComboBox<Integer>) dialog.getDialogPane().lookup("#bank-difficulty")).setValue(0);
            return null;
        });
        awaitFx(() -> bankTable(dialog).getItems().size() == 3 && bankReady(dialog));
        fx(() -> {
            bankTable(dialog).getSelectionModel().clearAndSelect(2);
            ((Button) dialog.getDialogPane().lookup("#exam-add-selected")).fire();
            assertEquals(3, selectedTable(dialog).getItems().size());
            selectedTable(dialog).getSelectionModel().clearAndSelect(1);
            ((Button) dialog.getDialogPane().lookup("#exam-move-up")).fire();
            assertEquals(List.of(1, 3, 6), selectedTable(dialog).getItems().stream().map(BankQuestionInfo::difficulty).toList());
            assertEquals(101L, selectedTable(dialog).getItems().getFirst().id(), "Không chuyển câu sang mức độ khó khác.");
            ((TextField) dialog.getDialogPane().lookup("#exam-title")).setText("Kiểm tra Lập trình mạng");
            snapshot(dialog.getDialogPane(), "exam-from-question-bank");
            ((ComboBox<SubjectInfo>) dialog.getDialogPane().lookup("#bank-subject")).setValue(subjects.get(1));
            assertTrue(selectedTable(dialog).getItems().isEmpty(), "Đổi môn phải bỏ câu của môn trước.");
            return null;
        });
        awaitFx(() -> bankTable(dialog).getItems().size() == 1 && bankReady(dialog));
        fx(() -> {
            bankTable(dialog).getSelectionModel().selectFirst();
            ((Button) dialog.getDialogPane().lookup("#exam-add-selected")).fire();
            ((TextField) dialog.getDialogPane().lookup("#exam-title")).setText("Kiểm tra CSDL");
            ((Button) dialog.getDialogPane().lookup("#exam-save")).fire();
            assertEquals(2L, dialog.getResult().subjectId());
            assertEquals(List.of(201L), dialog.getResult().questionIds());
            return null;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void quickExamDrawsMatrixReplacesSameLevelAndKeepsSelectionWhenBankIsShort() throws Exception {
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", "jdbc:h2:mem:quick_exam_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "", "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var bank = new QuestionBankService(factory::createEntityManager); var subject = bank.createSubject("Lập trình mạng");
            for (int level = 1; level <= 6; level++) for (int n = 1; n <= 3; n++) bank.createQuestion(new BankQuestionDraft(subject.id(), level,
                    new ExamDraft.QuestionDraft("Câu hỏi mức " + level + " số " + n, QuestionType.MULTIPLE_CHOICE, "TCP", "UDP", "IP", "ICMP", "A")));
            Dialog<BankExamDraft> dialog = fx(() -> { var d = ExamEditorDialog.create(null, bank); d.show(); return d; });
            try {
                awaitFx(() -> bankTable(dialog).getItems().size() == 18 && bankReady(dialog));
                fx(() -> {
                    ((TextField) dialog.getDialogPane().lookup("#exam-title")).setText("Kiểm tra tạo đề nhanh");
                    ((Spinner<Integer>) dialog.getDialogPane().lookup("#exam-level-1-count")).getValueFactory().setValue(2);
                    ((Spinner<Integer>) dialog.getDialogPane().lookup("#exam-level-2-count")).getValueFactory().setValue(1);
                    ((Button) dialog.getDialogPane().lookup("#exam-random-draw")).fire(); return null;
                });
                awaitFx(() -> selectedTable(dialog).getItems().size() == 3 && !dialog.getDialogPane().getContent().isDisabled());
                Long before = fx(() -> {
                    var rows = selectedTable(dialog).getItems(); assertEquals(2, rows.stream().filter(q -> q.difficulty() == 1).count());
                    assertEquals(1, rows.stream().filter(q -> q.difficulty() == 2).count());
                    selectedTable(dialog).getSelectionModel().selectFirst(); Long id = rows.getFirst().id();
                    ((Button) dialog.getDialogPane().lookup("#exam-random-replace")).fire(); return id;
                });
                awaitFx(() -> !selectedTable(dialog).getItems().getFirst().id().equals(before) && !dialog.getDialogPane().getContent().isDisabled());
                List<Long> chosen = fx(() -> {
                    assertEquals(1, selectedTable(dialog).getItems().getFirst().difficulty());
                    snapshot(dialog.getDialogPane(), "quick-exam-matrix-preview");
                    var ids = selectedTable(dialog).getItems().stream().map(BankQuestionInfo::id).toList(); assertEquals(3, ids.stream().distinct().count());
                    ((Spinner<Integer>) dialog.getDialogPane().lookup("#exam-level-1-count")).getValueFactory().setValue(99);
                    ((Button) dialog.getDialogPane().lookup("#exam-random-draw")).fire(); return ids;
                });
                awaitFx(() -> !dialog.getDialogPane().getContent().isDisabled() && ((Label) dialog.getDialogPane().lookup("#exam-editor-error")).getText().contains("Mức 1"));
                fx(() -> {
                    assertEquals(chosen, selectedTable(dialog).getItems().stream().map(BankQuestionInfo::id).toList());
                    ((Button) dialog.getDialogPane().lookup("#exam-save")).fire(); return null;
                });
                var result = new ExamService(factory::createEntityManager).createExamFromBank(dialog.getResult());
                assertEquals(3, result.questionCount());
            } finally { fx(() -> { dialog.close(); return null; }); }
        }
    }


    @Test
    @SuppressWarnings("unchecked")
    void clientShowsTeacherWarningAndReturnsToLoginWhenLockedAndTeacherCanUnlock() throws Exception {
        AtomicBoolean locked = new AtomicBoolean();
        Student student = new Student("ST001", "Nguyễn Văn An", LocalDate.of(2003, 5, 12));
        AuthService auth = new AuthService() {
            @Override public Optional<Student> authenticate(String code, String birthDate) { return Optional.of(student); }
            @Override public boolean isAccountLocked(String code) { return locked.get(); }
            @Override public void setAccountLocked(String code, boolean value, String reason) { locked.set(value); }
            @Override public List<LockedAccountInfo> listLockedAccounts() {
                return locked.get() ? List.of(new LockedAccountInfo("ST001", "Nguyễn Văn An", "Vi phạm quy chế")) : List.of();
            }
        };
        ExamDto exam = new ExamDto(101L, "Đề kiểm tra mạng", 120, List.of(
                new QuestionDto(1L, "Giao thức tin cậy?", "MULTIPLE_CHOICE", List.of("A. TCP", "B. UDP", "C. IP", "D. ICMP"))));
        ExamService exams = new UiExamStub() {
            @Override public Optional<ExamDto> recoverPublishedExam() { return Optional.empty(); }

            @Override public void finalizeExpiredAttempts() {}
            @Override public Optional<SubmitResult> getExamResult(String code, Long id) { return Optional.empty(); }
            @Override public List<ExamHistoryItem> listStudentHistory(String code) { return List.of(); }
            @Override public Optional<ExamOffer> findOffer(String code, Long id) { return Optional.of(new ExamOffer(101L, exam.getTitle(), 120, false)); }
            @Override public Optional<ExamDto> startAttempt(String code, Long id) { return Optional.of(exam); }
            @Override public void updateAnswers(String code, SubmitExamRequest request, Instant receivedAt) {}
        };
        SessionRegistry registry = new SessionRegistry();
        ExamServer server = new ExamServer(registry, auth, exams);
        server.start(0);
        ClientApp app = new ClientApp();
        Stage clientStage = fx(() -> {
            Stage created = new Stage();
            app.start(created);
            ((DatePicker) created.getScene().lookup("#login-birth-date")).setValue(LocalDate.of(2003, 5, 12));
            ((TextField) created.getScene().lookup("#login-code")).setText("ST001");
            ((TextField) created.getScene().lookup("#login-port")).setText(Integer.toString(server.getPort()));
            ((Button) created.getScene().lookup("#login-connect")).fire();
            return created;
        });
        try {
            awaitFx(() -> clientStage.getScene().lookup("#join-exam") instanceof Button join && !join.isDisabled());
            fx(() -> { ((Button) clientStage.getScene().lookup("#join-exam")).fire(); return null; });
            awaitFx(() -> clientStage.getScene().lookup("#exam-submit") != null);
            fx(() -> { ((RadioButton) clientStage.getScene().getRoot().lookupAll(".radio-button").iterator().next()).fire(); return null; });
            server.warnViolation("ST001", "Không trao đổi bài với bạn bên cạnh.");
            awaitFx(() -> notice("#violation-warning-notice") != null);
            fx(() -> {
                DialogPane pane = notice("#violation-warning-notice");
                assertEquals("Không trao đổi bài với bạn bên cạnh.", pane.getContentText());
                assertNotNull(clientStage.getScene().lookup("#exam-timer"));
                assertTrue(clientStage.getScene().getRoot().lookupAll(".radio-button").stream().anyMatch(node -> ((RadioButton) node).isSelected()));
                snapshot(pane, "client-violation-warning");
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
                return null;
            });
            server.lockAccount("ST001", "Vi phạm quy chế");
            awaitFx(() -> notice("#account-locked-notice") != null && clientStage.getScene().lookup("#login-connect") != null);
            fx(() -> {
                assertNull(clientStage.getScene().lookup("#exam-submit"));
                assertTrue(notice("#account-locked-notice").getContentText().contains("Vi phạm quy chế"));
                snapshot(notice("#account-locked-notice"), "client-account-locked");
                assertTrue(Window.getWindows().stream().noneMatch(window -> window instanceof Stage stage && "Mất kết nối".equals(stage.getTitle())));
                ((Button) notice("#account-locked-notice").lookupButton(ButtonType.OK)).fire();
                LockedAccountManager.show(clientStage, auth);
                return null;
            });
            awaitFx(() -> lockedAccountStage() != null && !((TableView<?>) lockedAccountStage().getScene().lookup("#locked-accounts")).getItems().isEmpty());
            fx(() -> {
                Stage manager = lockedAccountStage();
                ((TableView<LockedAccountInfo>) manager.getScene().lookup("#locked-accounts")).getSelectionModel().selectFirst();
                ((Button) manager.getScene().lookup("#account-unlock")).fire();
                return null;
            });
            awaitFx(() -> !locked.get() && ((TableView<?>) lockedAccountStage().getScene().lookup("#locked-accounts")).getItems().isEmpty());
            fx(() -> { lockedAccountStage().close(); return null; });
        } finally {
            fx(() -> { app.stop(); clientStage.close(); if (lockedAccountStage() != null) lockedAccountStage().close(); return null; });
            server.stop();
        }
    }

    @Test
    void teachersRegisterLoginSeeTheirNameAndSwitchOnlyAfterTheExamEnds() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:teacher_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin(); em.persist(new Student("ST001", "Nguyễn An", LocalDate.of(2003, 5, 12))); em.getTransaction().commit();
        }
        ServerApp app = new ServerApp(factory::createEntityManager, () -> {}, 0);
        Stage stage = fx(() -> { Stage created = new Stage(); app.start(created); return created; });
        try {
            awaitFx(() -> stage.getScene().lookup("#teacher-login-submit") != null);
            fx(() -> { snapshot(stage.getScene().getRoot(), "teacher-login"); return null; });
            createTeacherInUi(stage, "gv01", "Nguyễn Thị Lan");
            fx(() -> {
                ((TextField) stage.getScene().lookup("#teacher-login-username")).setText("gv01");
                ((PasswordField) stage.getScene().lookup("#teacher-login-password")).setText("SaiMatKhau");
                ((Button) stage.getScene().lookup("#teacher-login-submit")).fire(); return null;
            });
            awaitFx(() -> ((Label) stage.getScene().lookup("#teacher-login-message")).getText().contains("không đúng"));
            loginTeacherInUi(stage, "gv01");
            fx(() -> {
                assertTrue(((Label) stage.getScene().lookup("#active-teacher")).getText().contains("Nguyễn Thị Lan (gv01)"));
                assertNull(stage.getScene().lookup("#server-start"));
                assertNull(stage.getScene().lookup("#server-stop"));
                assertTrue(app.getListeningPort() > 0);
                snapshot(stage.getScene().getRoot(), "teacher-server-workspace");
                return null;
            });
            var teacher = new TeacherAccountService(factory::createEntityManager).authenticate("gv01", "Matkhau123!").orElseThrow();
            ExamService exams = new ExamService(factory::createEntityManager, teacher.id());
            var template = exams.createExam(new ExamDraft("Đề đang làm", 20, List.of(new ExamDraft.QuestionDraft(
                    "Chọn A", QuestionType.MULTIPLE_CHOICE, "Một", "Hai", "Ba", "Bốn", "A"))));
            var round = exams.activateExam(template.id(), enrolledUiClass(factory, teacher.id()));
            exams.startAttempt("ST001", round.getId());
            fx(() -> { ((Button) stage.getScene().lookup("#teacher-logout")).fire(); return null; });
            awaitFx(() -> notice("#server-error-notice") != null);
            fx(() -> {
                assertTrue(notice("#server-error-notice").getContentText().contains("Ca thi đang diễn ra"));
                assertNotNull(stage.getScene().lookup("#active-teacher"));
                snapshot(notice("#server-error-notice"), "teacher-switch-blocked");
                ((Button) notice("#server-error-notice").lookupButton(ButtonType.OK)).fire(); return null;
            });
            exams.submit("ST001", new SubmitExamRequest(round.getId(), Map.of()));
            expireUiRound(factory);
            fx(() -> { ((Button) stage.getScene().lookup("#teacher-logout")).fire(); return null; });
            awaitFx(() -> stage.getScene().lookup("#teacher-login-submit") != null);
            createTeacherInUi(stage, "gv02", "Trần Văn Minh");
            loginTeacherInUi(stage, "gv02");
            fx(() -> {
                assertTrue(((Label) stage.getScene().lookup("#active-teacher")).getText().contains("Trần Văn Minh (gv02)"));
                ((Button) stage.getScene().lookup("#teacher-logout")).fire(); return null;
            });
            awaitFx(() -> stage.getScene().lookup("#teacher-login-submit") != null);
            loginTeacherInUi(stage, "gv01");
            fx(() -> {
                assertTrue(((Label) stage.getScene().lookup("#active-teacher")).getText().contains("Nguyễn Thị Lan (gv01)")); return null;
            });
        } finally {
            fx(() -> {
                Window.getWindows().stream().filter(w -> w instanceof Stage child && child.getOwner() == stage)
                        .map(w -> (Stage) w).toList().forEach(Stage::close);
                app.stop(); stage.close(); return null;
            });
            factory.close();
        }
    }

    @Test
    void closingServerWindowIsBlockedByOfflineAttemptAndAllowedAfterDeadline() throws Exception {
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:close_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var teacher = new TeacherAccountService(factory::createEntityManager).create("gvclose", "Giáo viên", "Matkhau123!");
            ExamService exams = new ExamService(factory::createEntityManager, teacher.id());
            try (var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new Student("ST001", "Học sinh offline", LocalDate.of(2003, 5, 12)));
                em.getTransaction().commit();
            }
            var template = exams.createExam(new ExamDraft("Đề đang thi", 20, List.of(new ExamDraft.QuestionDraft(
                    "Chọn A", QuestionType.MULTIPLE_CHOICE, "Một", "Hai", "Ba", "Bốn", "A"))));
            var round = exams.activateExam(template.id(), enrolledUiClass(factory, teacher.id()));
            exams.startAttempt("ST001", round.getId());
            ServerApp app = new ServerApp(factory::createEntityManager, () -> {}, 0);
            Stage stage = fx(() -> { Stage created = new Stage(); app.start(created); return created; });
            try {
                awaitFx(() -> stage.getScene().lookup("#teacher-login-submit") != null);
                loginTeacherInUi(stage, "gvclose");
                fx(() -> { stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)); return null; });
                awaitFx(() -> notice("#server-error-notice") != null);
                fx(() -> {
                    assertTrue(stage.isShowing());
                    assertTrue(notice("#server-error-notice").getContentText().contains("Ca thi đang diễn ra"));
                    ((Button) notice("#server-error-notice").lookupButton(ButtonType.OK)).fire();
                    return null;
                });
                try (var em = factory.createEntityManager()) {
                    em.getTransaction().begin();
                    em.createQuery("update ExamAttempt a set a.deadlineAt = :past")
                            .setParameter("past", Instant.now().minusSeconds(1)).executeUpdate();
                    em.createQuery("update Exam e set e.deadlineAt = :past where e.active = true")
                            .setParameter("past", Instant.now().minusSeconds(1)).executeUpdate();
                    em.getTransaction().commit();
                }
                fx(() -> { stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)); return null; });
                awaitFx(() -> !stage.isShowing());
                assertTrue(exams.getExamResult("ST001", round.getId()).isPresent(), "Phải chấm bài hết giờ trước khi đóng.");
            } finally {
                fx(() -> {
                    if (notice("#server-error-notice") != null)
                        ((Button) notice("#server-error-notice").lookupButton(ButtonType.OK)).fire();
                    app.stop(); stage.close(); return null;
                });
            }
        }
    }

    @Test
    void reconnectingRestoresOnlyOwnAnswersAndExpiryGradesOnlineAndOfflineStudents() throws Exception {
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:restart_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            try (var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new Student("ST001", "Student A", LocalDate.of(2003, 5, 12)));
                em.persist(new Student("ST002", "Student B", LocalDate.of(2003, 5, 12)));
                em.getTransaction().commit();
            }
            BlockingQueue<String> updates = new LinkedBlockingQueue<>();
            ExamService exams = new ExamService(factory::createEntityManager) {
                @Override public void updateAnswers(String code, SubmitExamRequest request, Instant receivedAt) {
                    super.updateAnswers(code, request, receivedAt);
                    updates.add(code);
                }
            };
            var template = exams.createExam(new ExamDraft("Đề chung", 20, List.of(new ExamDraft.QuestionDraft(
                    "Chọn A", QuestionType.MULTIPLE_CHOICE, "Một", "Hai", "Ba", "Bốn", "A"))));
            var round = exams.activateExam(template.id());
            SessionRegistry registry = new SessionRegistry();
            ExamServer server = new ExamServer(registry, new AuthService(factory::createEntityManager), exams);
            server.start(0);
            server.publishExam(round);
            ClientApp app = new ClientApp();
            Stage stage = fx(() -> { Stage created = new Stage(); app.start(created); return created; });
            try {
                for (String code : List.of("ST001", "ST001", "ST002")) {
                    fx(() -> {
                        ((DatePicker) stage.getScene().lookup("#login-birth-date")).setValue(LocalDate.of(2003, 5, 12));
                        ((TextField) stage.getScene().lookup("#login-code")).setText(code);
                        ((TextField) stage.getScene().lookup("#login-port")).setText(Integer.toString(server.getPort()));
                        ((Button) stage.getScene().lookup("#login-connect")).fire(); return null;
                    });
                    awaitFx(() -> stage.getScene().lookup("#join-exam") instanceof Button join && !join.isDisabled());
                    boolean restored = exams.getOpenAttempt(code).map(a -> !a.getSavedAnswers().isEmpty()).orElse(false);
                    fx(() -> { ((Button) stage.getScene().lookup("#join-exam")).fire(); return null; });
                    awaitFx(() -> stage.getScene().lookup("#exam-submit") != null);
                    fx(() -> {
                        var choices = stage.getScene().getRoot().lookupAll(".radio-button");
                        assertEquals(restored ? 1 : 0, choices.stream().filter(node -> ((RadioButton) node).isSelected()).count(), "Chỉ khôi phục đáp án của chính sinh viên: " + code);
                        if (!restored) choices.stream().map(node -> (RadioButton) node).filter(button -> button.getText().startsWith("A."))
                                .findFirst().orElseThrow().fire();
                        return null;
                    });
                    if (!restored) assertEquals(code, updates.poll(5, TimeUnit.SECONDS));
                    if (code.equals("ST002")) break;
                    registry.find(code).getHandler().closeQuietly();
                    awaitFx(() -> notice(".dialog-pane") != null);
                    fx(() -> { ((Button) notice(".dialog-pane").lookupButton(ButtonType.OK)).fire(); return null; });
                    awaitFx(() -> stage.getScene().lookup("#login-connect") != null);
                }
                try (var em = factory.createEntityManager()) {
                    em.getTransaction().begin();
                    em.createQuery("update ExamAttempt a set a.deadlineAt = :past")
                            .setParameter("past", Instant.now().minusSeconds(1)).executeUpdate();
                    em.getTransaction().commit();
                }
                awaitFx(() -> stage.getScene().getRoot().lookupAll(".label").stream()
                        .anyMatch(node -> node instanceof Label label && label.getText().startsWith("Điểm:")));
                assertEquals(10.0, exams.getExamResult("ST001", round.getId()).orElseThrow().getScore());
                assertEquals(10.0, exams.getExamResult("ST002", round.getId()).orElseThrow().getScore());
            } finally {
                fx(() -> { app.stop(); stage.close(); return null; });
                server.stop();
            }
        }
    }

    @Test
    void waitingDashboardCountsDownAndDisablesJoiningWhenRoundExpires() throws Exception {
        long deadline = System.currentTimeMillis() + 4000;
        AtomicInteger joins = new AtomicInteger();
        ExamDto round = new ExamDto(99L, "Đề sắp hết giờ", 4, List.of());
        round.setDeadlineEpochMillis(deadline);
        ExamService exams = new UiExamStub() {
            @Override public Optional<ExamDto> recoverPublishedExam() { return Optional.empty(); }

            @Override public void finalizeExpiredAttempts() {}
            @Override public List<ExamHistoryItem> listStudentHistory(String code) { return List.of(); }
            @Override public Optional<ExamOffer> findOffer(String code, Long id) {
                long now = System.currentTimeMillis();
                return now >= deadline ? Optional.empty() : Optional.of(new ExamOffer(99L, round.getTitle(),
                        (int) ((deadline - now + 999) / 1000), false, null, deadline, now));
            }
            @Override public Optional<ExamDto> startAttempt(String code, Long id) { joins.incrementAndGet(); return Optional.of(round); }
        };
        AuthService auth = new AuthService() {
            @Override public Optional<Student> authenticate(String code, String birthDate) {
                return Optional.of(new Student("ST001", "Student", LocalDate.of(2003, 5, 12)));
            }
            @Override public boolean isAccountLocked(String code) { return false; }
        };
        ExamServer server = new ExamServer(new SessionRegistry(), auth, exams);
        server.start(0); server.publishExam(round);
        ClientApp app = new ClientApp();
        Stage stage = fx(() -> {
            Stage created = new Stage(); app.start(created);
            ((DatePicker) created.getScene().lookup("#login-birth-date")).setValue(LocalDate.of(2003, 5, 12));
            ((TextField) created.getScene().lookup("#login-code")).setText("ST001");
            ((TextField) created.getScene().lookup("#login-port")).setText(Integer.toString(server.getPort()));
            ((Button) created.getScene().lookup("#login-connect")).fire();
            return created;
        });
        try {
            awaitFx(() -> stage.getScene().lookup("#join-exam") instanceof Button join && !join.isDisabled());
            String initial = fx(() -> ((Label) stage.getScene().lookup("#exam-offer-label")).getText());
            awaitFx(() -> !initial.equals(((Label) stage.getScene().lookup("#exam-offer-label")).getText()));
            awaitFx(() -> ((Button) stage.getScene().lookup("#join-exam")).isDisabled());
            fx(() -> { ((Button) stage.getScene().lookup("#join-exam")).fire(); return null; });
            assertEquals(0, joins.get(), "Đề hết hạn không được gửi yêu cầu tham gia.");
        } finally {
            fx(() -> { app.stop(); stage.close(); return null; });
            server.stop();
        }
    }

    private static void createTeacherInUi(Stage stage, String username, String fullName) throws Exception {
        fx(() -> {
            Platform.runLater(() -> ((Button) stage.getScene().lookup("#teacher-login-create")).fire()); return null;
        });
        awaitFx(() -> notice("#teacher-register-dialog") != null);
        fx(() -> {
            DialogPane pane = notice("#teacher-register-dialog");
            ((TextField) pane.lookup("#teacher-register-username")).setText(username);
            ((TextField) pane.lookup("#teacher-register-name")).setText(fullName);
            ((PasswordField) pane.lookup("#teacher-register-password")).setText("Matkhau123!");
            ((PasswordField) pane.lookup("#teacher-register-confirm")).setText("KhongKhop");
            ((Button) pane.lookup("#teacher-register-save")).fire();
            assertNotNull(notice("#teacher-register-dialog"), "Mật khẩu xác nhận không khớp thì chưa được lưu.");
            ((PasswordField) pane.lookup("#teacher-register-confirm")).setText("Matkhau123!");
            ((Button) pane.lookup("#teacher-register-save")).fire(); return null;
        });
        awaitFx(() -> ((Label) stage.getScene().lookup("#teacher-login-message")).getText().contains("Đã tạo tài khoản"));
    }

    private static void expireUiRound(EntityManagerFactory factory) {
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.createQuery("update Exam e set e.deadlineAt = :past where e.active = true")
                    .setParameter("past", Instant.now().minusSeconds(1)).executeUpdate();
            em.getTransaction().commit();
        }
    }

    private static Long enrolledUiClass(EntityManagerFactory factory, Long teacherId) {
        QuestionBankService bank = new QuestionBankService(factory::createEntityManager, teacherId);
        Long subjectId = bank.listSubjects().stream().findFirst().map(SubjectInfo::id)
                .orElseGet(() -> bank.createSubject("Môn thử nghiệm").id());
        ClassService classes = new ClassService(factory::createEntityManager, teacherId);
        Long id = classes.create("TEST-HK1", "Lớp thử nghiệm", "HK1", subjectId).id();
        try (var em = factory.createEntityManager()) {
            classes.importRoster(id, em.createQuery("select s from Student s", Student.class).getResultList().stream()
                    .map(s -> new RosterCsv.StudentRow(1, s.getStudentCode(), s.getFullName(), s.getBirthDate())).toList());
        }
        return id;
    }

    private static void loginTeacherInUi(Stage stage, String username) throws Exception {
        fx(() -> {
            ((TextField) stage.getScene().lookup("#teacher-login-username")).setText(username);
            ((PasswordField) stage.getScene().lookup("#teacher-login-password")).setText("Matkhau123!");
            ((Button) stage.getScene().lookup("#teacher-login-submit")).fire(); return null;
        });
        awaitFx(() -> stage.getScene().lookup("#active-teacher") != null
                && !stage.getScene().lookup("#teacher-logout").isDisabled()
                && !((Label) stage.getScene().lookup("#server-address")).getText().isBlank());
        fx(() -> { ((Button) stage.getScene().lookup("#nav-room")).fire(); return null; });
    }


    private static javafx.scene.Parent notificationCenter() {
        return Window.getWindows().stream().map(Window::getScene).filter(java.util.Objects::nonNull)
                .map(scene -> scene.lookup("#teacher-notification-center")).filter(java.util.Objects::nonNull)
                .map(node -> (javafx.scene.Parent) node).findFirst().orElse(null);
    }
    private static DialogPane notice(String selector) {
        return Window.getWindows().stream().map(Window::getScene).filter(java.util.Objects::nonNull)
                .map(scene -> scene.lookup(selector)).filter(java.util.Objects::nonNull)
                .map(node -> (DialogPane) node).findFirst().orElse(null);
    }

    private static Stage lockedAccountStage() {
        return Window.getWindows().stream().filter(w -> w instanceof Stage stage && "Tài khoản bị khóa".equals(stage.getTitle()))
                .map(w -> (Stage) w).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static TableView<BankQuestionInfo> bankTable(Dialog<?> dialog) {
        return (TableView<BankQuestionInfo>) dialog.getDialogPane().lookup("#bank-questions");
    }
    @SuppressWarnings("unchecked")
    private static TableView<BankQuestionInfo> selectedTable(Dialog<?> dialog) {
        return (TableView<BankQuestionInfo>) dialog.getDialogPane().lookup("#exam-selected-questions");
    }
    private static void assertQuestionDetailsFromTable(TableView<BankQuestionInfo> table) throws Exception {
        BankQuestionInfo expected = fx(() -> table.getItems().getFirst());
        awaitFx(() -> table.lookupAll(".hyperlink").stream()
                .anyMatch(node -> node instanceof Hyperlink link && link.getText().equals(expected.content())));
        fx(() -> {
            var link = (Hyperlink) table.lookupAll(".hyperlink").stream()
                    .filter(node -> node instanceof Hyperlink h && h.getText().equals(expected.content())).findFirst().orElseThrow();
            Platform.runLater(link::fire); return null;
        });
        awaitFx(() -> notice("#question-details") != null);
        fx(() -> {
            var pane = notice("#question-details");
            var content = (TextArea) pane.lookup("#question-details-content");
            assertEquals(expected.preview(), content.getText());
            assertFalse(content.isEditable());
            snapshot(pane, "exam-question-details");
            ((Button) pane.lookup("#question-details-close")).fire();
            return null;
        });
    }

    private static boolean bankReady(Dialog<?> dialog) {
        return ((Label) dialog.getDialogPane().lookup("#bank-load-status")).getText().matches("\\d+ câu hỏi");
    }
    private static void awaitFx(Supplier<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            if (fx(condition)) return;
            Thread.sleep(25);
        }
        fail("Giao diện không cập nhật danh sách câu hỏi đúng hạn.");
    }


    private static <T> T fx(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(action.get()); }
            catch (Throwable ex) { result.completeExceptionally(ex); }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static class UiExamStub extends ExamService {
        private final com.ptit.lanexam.server.service.ExamEventService events = new com.ptit.lanexam.server.service.ExamEventService(
                () -> { throw new AssertionError("UI stub must not access a real database"); }, null, java.time.Clock.systemUTC()) {
            @Override public ExamWarningStatus status(Long round, String code, String message) { return new ExamWarningStatus(round, 0, false, message); }
            @Override public ExamWarningStatus focusReturned(Long round, String code, String episode, Instant left, Instant back) { return status(round, code, ""); }
            @Override public ExamWarningStatus focusStillAway(Long round, String code, String episode, Instant left, Instant back) { return status(round, code, ""); }
            @Override public void connection(Long round, String code, boolean connected) {}
            @Override public void reminder(Long round, String code, String text) {}
        };
        UiExamStub() { super(() -> { throw new AssertionError("UI stub must not access a real database"); }); }
        @Override public Optional<ExamDto> getOpenAttempt(String code) { return Optional.empty(); }
        @Override public com.ptit.lanexam.server.service.ExamEventService events() { return events; }
    }

    private static void snapshot(javafx.scene.Parent root, String name) {
        try {
            root.applyCss();
            root.layout();
            WritableImage image = root.snapshot(null, null);
            BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++)
                for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            Path directory = Path.of("target", "assignment-preview");
            Files.createDirectories(directory);
            ImageIO.write(png, "png", directory.resolve(name + ".png").toFile());
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
}
