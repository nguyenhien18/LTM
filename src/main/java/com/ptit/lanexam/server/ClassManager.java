package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.*;
import com.ptit.lanexam.server.network.ClientSessionInfo;
import com.ptit.lanexam.server.service.*;
import javafx.collections.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;

/** Local teacher workspace. Roster files never leave the LAN server. */
public final class ClassManager extends BorderPane {
    private final Window owner;
    private final ClassService service;
    private final QuestionBankService bank;
    private final ObservableList<ClientSessionInfo> sessions;
    private final Runnable changed;
    private final ComboBox<ClassInfo> classes = new ComboBox<>();
    private final TableView<ClassService.RosterStudent> roster = new TableView<>();
    private final Label message = AppUi.label("Tạo lớp rồi nhập danh sách sinh viên bằng CSV UTF-8.", "muted");
    private final Label detail = AppUi.label("Chưa chọn lớp", "section-title");
    private final Button importCsv = new Button("Nhập danh sách CSV");
    private final Label ordinalNotice = AppUi.label("", "muted");
    private final Button restoreOrdinals = new Button("Bổ sung STT từ CSV");
    private final HBox ordinalHelp = AppUi.toolbar(ordinalNotice, AppUi.spacer(), restoreOrdinals);
    private final ListChangeListener<ClientSessionInfo> sessionListener = change -> roster.refresh();
    private boolean disposed;
    private long rosterVersion;

    public ClassManager(Window owner, ClassService service, QuestionBankService bank,
                        ObservableList<ClientSessionInfo> sessions, Runnable changed) {
        this.owner = owner; this.service = service; this.bank = bank; this.sessions = sessions; this.changed = changed;
        setId("class-manager");
        classes.setId("class-selector"); classes.setPromptText("Chọn lớp học phần"); classes.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(classes, Priority.ALWAYS);
        classes.valueProperty().addListener((o, a, b) -> loadRoster());
        Button create = new Button("Tạo lớp"); create.setId("class-create"); create.getStyleClass().add("primary");
        create.setOnAction(e -> run("load-class-subjects", bank::listSubjects, this::createClass));
        importCsv.setId("class-import"); importCsv.setOnAction(e -> chooseCsv());
        ordinalNotice.setId("class-ordinal-notice"); ordinalNotice.setMinWidth(0); ordinalNotice.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(ordinalNotice, Priority.ALWAYS);
        restoreOrdinals.setId("class-restore-ordinals"); restoreOrdinals.setOnAction(e -> chooseCsv());
        restoreOrdinals.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        ordinalHelp.setVisible(false); ordinalHelp.setManaged(false);
        Button sample = new Button("Tải CSV mẫu"); sample.setOnAction(e -> saveSample());
        Button refresh = new Button("Làm mới"); refresh.setId("class-refresh"); refresh.setOnAction(e -> reload(null));
        roster.setId("class-roster");
        roster.getColumns().add(UiSupport.column("STT", 60, s -> s.ordinal() == null ? "—" : s.ordinal().toString()));
        roster.getColumns().add(UiSupport.column("Mã sinh viên", 170, ClassService.RosterStudent::code));
        var names = UiSupport.<ClassService.RosterStudent>column("Họ và tên · bấm để sửa", 260, ClassService.RosterStudent::fullName);
        names.setCellFactory(column -> new TableCell<>() {
            private final Hyperlink link = new Hyperlink();
            { link.setStyle("-fx-padding: 0;"); link.setOnAction(e -> {
                var row = getTableRow().getItem();
                if (row != null && classes.getValue() != null) editProfile(classes.getValue(), row);
            }); }
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty); setText(null);
                link.setText(empty ? "" : value); setGraphic(empty ? null : link);
            }
        });
        roster.getColumns().add(names);
        roster.getColumns().add(UiSupport.column("Ngày sinh", 120, s -> RosterCsv.displayDate(s.birthDate())));
        roster.getColumns().add(UiSupport.column("Lớp chính quy", 140, s -> s.regularClass() == null ? "" : s.regularClass()));
        roster.getColumns().add(UiSupport.column("Kết nối hiện tại", 190, s -> sessions.stream()
                .filter(session -> session.studentCode().equals(s.code())).findFirst()
                .map(session -> switch (session.status()) {
                    case "DOING_EXAM" -> "Đang thi trắc nghiệm";
                    case "SUBMITTED" -> "Đã nộp trắc nghiệm";
                    default -> "Đang kết nối";
                }).orElse("Chưa kết nối")));
        roster.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        roster.setPlaceholder(new Label("Lớp chưa có sinh viên. Chọn Nhập danh sách CSV để thêm."));
        VBox students = new VBox(12, AppUi.toolbar(AppUi.spacer(), sample, importCsv), ordinalHelp, roster, message);
        VBox.setVgrow(roster, Priority.ALWAYS);
        VBox content = new VBox(14, AppUi.toolbar(classes, create, refresh), detail, students);
        VBox.setVgrow(students, Priority.ALWAYS); setCenter(content);
        sessions.addListener(sessionListener); reload(null);
    }
    public void dispose() { disposed = true; rosterVersion++; sessions.removeListener(sessionListener); }
    private void editProfile(ClassInfo group, ClassService.RosterStudent student) {
        Dialog<Boolean> dialog = new Dialog<>(); dialog.initOwner(owner); AppUi.style(dialog.getDialogPane());
        dialog.setTitle("Sửa hồ sơ sinh viên"); dialog.getDialogPane().setId("student-profile-editor");
        TextField code = new TextField(student.code()); code.setEditable(false); code.setId("student-profile-code");
        TextField name = new TextField(student.fullName()); name.setId("student-profile-name");
        DatePicker birth = new DatePicker(student.birthDate()); birth.setId("student-profile-birth");
        TextField regular = new TextField(student.regularClass()); regular.setId("student-profile-regular-class");
        Label original = new Label("Thông tin hiện tại: " + student.fullName() + " · " + RosterCsv.displayDate(student.birthDate())
                + " · " + (student.regularClass() == null ? "Chưa có lớp chính quy" : student.regularClass())); original.setWrapText(true);
        Label note = new Label("Hồ sơ dùng chung: thay đổi sẽ cập nhật ở mọi lớp học phần của sinh viên, kể cả lớp của giảng viên khác. Ngày sinh mới dùng để đăng nhập. STT của từng lớp và danh sách ca đã chốt giữ nguyên."); note.setWrapText(true);
        CheckBox confirm = new CheckBox("Tôi xác nhận cập nhật hồ sơ dùng chung"); confirm.setId("student-profile-confirm");
        Label error = new Label(); error.setId("student-profile-error"); error.setWrapText(true); error.getStyleClass().add("error-text");
        dialog.getDialogPane().setContent(new VBox(10, original, new Label("Mã sinh viên"), code,
                new Label("Họ và tên"), name, new Label("Ngày sinh"), birth, new Label("Lớp chính quy"), regular, note, confirm, error));
        dialog.getDialogPane().setPrefWidth(580);
        ButtonType save = new ButtonType("Lưu thay đổi", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Hủy");
        var saveButton = dialog.getDialogPane().lookupButton(save); saveButton.setId("student-profile-save");
        saveButton.disableProperty().bind(confirm.selectedProperty().not());
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            final java.time.LocalDate date;
            try { birth.commitValue(); date = birth.getValue(); }
            catch (RuntimeException ex) { error.setText("Ngày sinh không hợp lệ."); return; }
            String fullName = name.getText(), regularClass = regular.getText();
            dialog.getDialogPane().setDisable(true);
            UiSupport.async("update-student-profile", () -> service.updateProfile(group.id(), student, fullName, date, regularClass), saved -> {
                dialog.setResult(true); dialog.close();
                if (!disposed) { loadRoster(); changed.run(); }
            }, ex -> { dialog.getDialogPane().setDisable(false); error.setText(ex.getMessage()); });
        });
        dialog.showAndWait();
    }
    private void reload(Long selectedId) {
        Long id = selectedId != null ? selectedId : classes.getValue() == null ? null : classes.getValue().id();
        run("load-classes", service::list, items -> {
            classes.getItems().setAll(items);
            classes.setValue(items.stream().filter(c -> c.id().equals(id)).findFirst().orElse(items.isEmpty() ? null : items.getFirst()));
            loadRoster();
        });
    }
    private void loadRoster() {
        ClassInfo c = classes.getValue(); long version = ++rosterVersion;
        ordinalHelp.setVisible(false); ordinalHelp.setManaged(false);
        importCsv.setDisable(c == null); roster.getItems().clear();
        if (c == null) { detail.setText("Chưa có lớp học phần"); return; }
        detail.setText(c.subjectName() + " · " + c.semester());
        UiSupport.async("load-class-roster", () -> service.roster(c.id()), rows -> {
            if (disposed || version != rosterVersion) return;
            roster.getItems().setAll(rows); message.setText(rows.size() + " sinh viên. Trạng thái là kết nối hiện tại, chưa dùng để kết luận vắng học.");
            long missing = rows.stream().filter(s -> s.ordinal() == null).count();
            ordinalHelp.setVisible(missing > 0); ordinalHelp.setManaged(missing > 0);
            ordinalNotice.setText(missing == 0 ? "" : missing + " sinh viên chưa có STT (hiện —). Nhập lại CSV gốc có cột STT để cập nhật vào danh sách hiện có và chia ca.");
        }, ex -> { if (!disposed && version == rosterVersion) message.setText(ex.getMessage()); });
    }
    private void createClass(List<SubjectInfo> subjects) {
        Dialog<Boolean> dialog = new Dialog<>(); dialog.initOwner(owner); AppUi.style(dialog.getDialogPane());
        dialog.setTitle("Tạo lớp học phần"); dialog.getDialogPane().setId("class-editor");
        TextField code = new TextField(); code.setId("class-code"); code.setPromptText("VD: LTM-01-2026HK1");
        TextField name = new TextField(); name.setId("class-name"); name.setPromptText("VD: Lập trình mạng · Nhóm 01");
        TextField semester = new TextField(); semester.setId("class-semester"); semester.setPromptText("VD: 2026–2027 / Học kỳ 1");
        ComboBox<SubjectInfo> subject = new ComboBox<>(FXCollections.observableArrayList(subjects));
        subject.setId("class-subject"); subject.setMaxWidth(Double.MAX_VALUE); subject.setPromptText("Chọn môn học");
        if (!subjects.isEmpty()) subject.getSelectionModel().selectFirst();
        Button addSubject = new Button("Thêm môn học");
        Label error = new Label(); error.setWrapText(true); error.getStyleClass().add("error-text");
        addSubject.setOnAction(e -> {
            TextInputDialog input = new TextInputDialog(); input.initOwner(dialog.getDialogPane().getScene().getWindow());
            AppUi.style(input.getDialogPane()); input.setTitle("Thêm môn học"); input.setHeaderText("Tên môn dùng chung với ngân hàng câu hỏi.");
            input.showAndWait().filter(s -> !s.isBlank()).ifPresent(value -> {
                addSubject.setDisable(true);
                UiSupport.async("create-class-subject", () -> bank.createSubject(value), saved -> {
                    addSubject.setDisable(false); subject.getItems().add(saved); subject.setValue(saved);
                }, ex -> { addSubject.setDisable(false); error.setText(ex.getMessage()); });
            });
        });
        VBox body = new VBox(10, new Label("Mã lớp (duy nhất trong tài khoản giảng viên)"), code,
                new Label("Tên lớp / nhóm học phần"), name, new Label("Học kỳ"), semester,
                new Label("Môn học"), AppUi.toolbar(subject, addSubject), error);
        dialog.getDialogPane().setContent(body); dialog.getDialogPane().setPrefWidth(570);
        ButtonType save = new ButtonType("Tạo lớp", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Hủy");
        dialog.getDialogPane().lookupButton(save).setId("class-save");
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            if (subject.getValue() == null) { error.setText("Chọn hoặc thêm môn học trước."); return; }
            String classCode = code.getText(), className = name.getText(), term = semester.getText();
            Long subjectId = subject.getValue().id();
            dialog.getDialogPane().setDisable(true);
            UiSupport.async("create-course-class", () -> service.create(classCode, className, term, subjectId), saved -> {
                dialog.setResult(true); dialog.close(); if (!disposed) { reload(saved.id()); changed.run(); }
            }, ex -> { dialog.getDialogPane().setDisable(false); error.setText(ex.getMessage()); });
        });
        dialog.showAndWait();
    }
    private void chooseCsv() {
        ClassInfo c = classes.getValue(); if (c == null) return;
        FileChooser chooser = new FileChooser(); chooser.setTitle("Nhập danh sách · " + c.code());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV UTF-8 (*.csv)", "*.csv"));
        var file = chooser.showOpenDialog(owner); if (file == null) return;
        run("preview-class-import", () -> service.preview(c.id(), RosterCsv.read(file.toPath())), preview -> showPreview(c, preview));
    }
    // Public so an integration test can exercise the real preview and confirmation without an OS file dialog.
    public void showPreview(ClassInfo c, ClassService.ImportPreview preview) {
        Dialog<Boolean> dialog = new Dialog<>(); dialog.initOwner(owner); dialog.setTitle("Xem trước nhập danh sách · " + c.code());
        AppUi.style(dialog.getDialogPane()); dialog.getDialogPane().setId("class-import-preview");
        TableView<ClassService.ImportRow> table = new TableView<>(FXCollections.observableArrayList(preview.rows()));
        table.getColumns().add(UiSupport.column("STT", 60, r -> r.student().ordinal() == null ? "—" : r.student().ordinal().toString()));
        table.getColumns().add(UiSupport.column("Mã sinh viên", 130, r -> r.student().code()));
        table.getColumns().add(UiSupport.column("Họ và tên", 190, r -> r.student().fullName()));
        table.getColumns().add(UiSupport.column("Ngày sinh", 110, r -> RosterCsv.displayDate(r.student().birthDate())));
        table.getColumns().add(UiSupport.column("Lớp chính quy", 130, r -> r.student().regularClass() == null ? "" : r.student().regularClass()));
        table.getColumns().add(UiSupport.column("Xử lý", 260, r -> r.status().toString()));
        table.setPrefHeight(300); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Label status = new Label(preview.summary()); status.setWrapText(true);
        Label note = new Label("STT được lấy đúng từ CSV theo từng mã sinh viên trong lớp, không tự đánh lại theo thứ tự bảng. Dòng Cập nhật STT / lớp chính quy bổ sung dữ liệu vào sinh viên đã có; STT trống trên CSV giữ số đã lưu. Lớp trong CSV là lớp chính quy. Danh sách ca đã lưu giữ nguyên."); note.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(12, status, table, note)); dialog.getDialogPane().setPrefWidth(950);
        ButtonType save = new ButtonType("Xác nhận nhập", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Hủy");
        var confirm = dialog.getDialogPane().lookupButton(save); confirm.setId("class-import-confirm");
        confirm.setDisable(preview.hasConflicts() || preview.count(ClassService.ImportStatus.EXISTS) == preview.rows().size());
        confirm.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume(); dialog.getDialogPane().setDisable(true);
            UiSupport.async("commit-class-import", () -> service.importRoster(c.id(), preview.rows().stream().map(ClassService.ImportRow::student).toList()), result -> {
                dialog.setResult(true); dialog.close();
                if (!disposed) { reload(c.id()); changed.run(); message.setText("Đã nhập: " + result.summary()); }
            }, ex -> { dialog.getDialogPane().setDisable(false); status.setText(ex.getMessage()); });
        });
        dialog.showAndWait();
    }
    private void saveSample() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Lưu CSV mẫu"); chooser.setInitialFileName("danh-sach-sinh-vien.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV UTF-8", "*.csv"));
        var file = chooser.showSaveDialog(owner); if (file == null) return;
        run("save-roster-sample", () -> { Files.writeString(file.toPath(), RosterCsv.sample(), StandardCharsets.UTF_8); return true; },
                done -> message.setText("Đã lưu CSV mẫu. Thay dữ liệu mẫu bằng danh sách thực tế trước khi nhập."));
    }
    private <T> void run(String name, Callable<T> action, java.util.function.Consumer<T> done) {
        setDisable(true);
        UiSupport.async(name, action, result -> { if (!disposed) { setDisable(false); done.accept(result); } },
                ex -> { if (!disposed) { setDisable(false); message.setText(ex.getMessage()); } });
    }
}
