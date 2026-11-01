package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.*;
import com.ptit.lanexam.server.network.*;
import com.ptit.lanexam.server.service.*;
import javafx.collections.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.List;
import java.util.function.*;

/** Prepare locally first; the common deadline starts only when Start is pressed. */
final class ExamOrganizer extends BorderPane {
    private final ComboBox<ClassInfo> group = new ComboBox<>();
    private final ComboBox<ExamSummary> exam = new ComboBox<>();
    private final ComboBox<ExamShiftService.ShiftInfo> shift = new ComboBox<>();
    private final Spinner<Integer> capacity = new Spinner<>(1, RosterCsv.MAX_ROWS, 30);
    private final Spinner<Integer> variantCount = new Spinner<>(1, 8, 4);
    private final Button split = new Button("Chia ca…");
    private final TableView<ClassService.RosterStudent> roster = new TableView<>();
    private final Label status = AppUi.label("Chọn lớp để xem danh sách.", "muted");
    private final Label count = AppUi.label("", "muted");
    private final Button start = new Button("Bắt đầu thi");
    private final Button end = new Button("Dừng bài thi");
    private final ClassService classes;
    private final ExamService exams;
    private final ExamShiftService shifts;
    private final Supplier<ExamServer> server;
    private final ObservableList<ClientSessionInfo> sessions;
    private final Consumer<String> announce;
    private List<ExamSummary> templates = List.of();
    private boolean prepared, busy, disposed, changingOptions;
    private long version;
    private long deadlineMillis;
    private Long runningRound;
    private ExamService.RoundProgress progress;
    private java.util.Map<String, ExamEventService.Summary> warnings = java.util.Map.of();
    private boolean progressLoading;
    private int progressTicks;
    private final javafx.animation.Timeline deadlineWatcher = new javafx.animation.Timeline();
    private final ListChangeListener<ClientSessionInfo> listener = change -> refreshConnections();

    ExamOrganizer(ClassService classes, ExamService exams, ExamShiftService shifts, Supplier<ExamServer> server,
                  ObservableList<ClientSessionInfo> sessions, Consumer<ClientSessionInfo> warn,
                  Consumer<ClientSessionInfo> lock, Consumer<String> announce) {
        AppUi.desktopStyle(this);
        this.classes = classes; this.exams = exams; this.shifts = shifts; this.server = server; this.sessions = sessions; this.announce = announce;
        group.setId("exam-class"); exam.setId("exam-template"); group.setPromptText("Chọn lớp / nhóm thi"); exam.setPromptText("Chọn đề đã lưu");
        exam.getStyleClass().add("round-setting"); variantCount.getStyleClass().add("round-setting");
        group.setMaxWidth(Double.MAX_VALUE); exam.setMaxWidth(Double.MAX_VALUE);
        exam.setConverter(new javafx.util.StringConverter<>() {
            public String toString(ExamSummary e) { return e == null ? "" : e.title() + " · " + e.questionCount() + " câu · " + e.durationMinutes() + " phút"; }
            public ExamSummary fromString(String s) { return null; }
        });
        group.valueProperty().addListener((o, old, value) -> {
            if (!changingOptions) {
                invalidatePreparation(); changingOptions = true; filterTemplates(); changingOptions = false; loadShifts();
            }
        });
        exam.valueProperty().addListener((o, old, value) -> { if (!changingOptions) { invalidatePreparation(); prepare(); } });
        shift.setId("exam-shift"); shift.setPromptText("Cả lớp · chưa chia ca"); shift.setMaxWidth(Double.MAX_VALUE);
        shift.setConverter(new javafx.util.StringConverter<>() {
            public String toString(ExamShiftService.ShiftInfo s) { return s == null ? "" : s.toString(); }
            public ExamShiftService.ShiftInfo fromString(String s) { return null; }
        });
        shift.valueProperty().addListener((o, old, value) -> { if (!changingOptions) { invalidatePreparation(); prepare(); } });
        capacity.setId("exam-shift-capacity"); capacity.setEditable(true); capacity.setPrefWidth(100);
        variantCount.setId("exam-variant-count"); variantCount.setPrefWidth(80);
        split.setId("exam-split"); split.setOnAction(e -> split());
        start.setId("exam-start"); start.getStyleClass().add("primary"); start.setOnAction(e -> start());
        end.setId("exam-end"); end.getStyleClass().add("danger"); end.setOnAction(e -> end());
        Button refresh = new Button("Làm mới"); refresh.setId("exam-organizer-refresh"); refresh.setOnAction(e -> reload());
        HBox shiftControls = AppUi.toolbar(shift, split); HBox.setHgrow(shift, Priority.ALWAYS);
        split.setMinWidth(Region.USE_PREF_SIZE);
        GridPane fields = new GridPane(); fields.setHgap(24); fields.setVgap(12);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(50); column.setHgrow(Priority.ALWAYS);
            fields.getColumnConstraints().add(column);
        }
        fields.add(new VBox(6, new Label("Lớp học phần"), group), 0, 0);
        fields.add(new VBox(6, new Label("Ca thi"), shiftControls), 1, 0);
        fields.add(new VBox(6, new Label("Đề thi"), exam), 0, 1);
        fields.add(new VBox(6, new Label("Số mã đề"), variantCount), 1, 1);
        group.setMinWidth(0); exam.setMinWidth(0); shift.setMinWidth(0);
        status.setWrapText(true); status.setMinWidth(0); status.setMaxWidth(Double.MAX_VALUE);
        status.setId("exam-round-status"); HBox.setHgrow(status, Priority.ALWAYS);
        VBox selection = AppUi.card(fields, AppUi.toolbar(status, AppUi.spacer(), refresh, start, end));
        roster.setId("exam-roster"); roster.setFixedCellSize(36); roster.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        var ordinalColumn = UiSupport.<ClassService.RosterStudent>column("STT", 60, r -> r.ordinal() == null ? "—" : r.ordinal().toString());
        ordinalColumn.setMinWidth(55); roster.getColumns().add(ordinalColumn);
        roster.getColumns().add(UiSupport.column("Mã sinh viên", 140, ClassService.RosterStudent::code));
        roster.getColumns().add(UiSupport.column("Họ và tên", 220, ClassService.RosterStudent::fullName));
        roster.getColumns().add(UiSupport.column("Mã đề", 75, row -> progress == null ? "—" : progress.variantCode(row.code())));
        roster.getColumns().add(UiSupport.column("Lớp chính quy", 130, r -> r.regularClass() == null ? "" : r.regularClass()));
        roster.getColumns().add(UiSupport.column("Kết nối", 100, row -> session(row.code()) == null ? "Offline" : "Online"));
        roster.getColumns().add(UiSupport.column("Bài thi của ca này", 140,
                row -> progress == null ? "Chưa mở thi" : progress.state(row.code(), exams.currentTime())));
        var warningColumn = UiSupport.<ClassService.RosterStudent>column("Cảnh báo", 165,
                row -> warnings.getOrDefault(row.code(), new ExamEventService.Summary(0, false, 0)).label());
        warningColumn.setCellFactory(column -> new TableCell<>() {
            final Hyperlink link = new Hyperlink();
            { link.setStyle("-fx-padding: 0;"); link.setOnAction(e -> {
                var student = getTableRow().getItem();
                if (student != null && runningRound != null)
                    ExamWarningJournal.show(getScene().getWindow(), exams.events(), server.get(), runningRound, student.code(), student.fullName());
            }); }
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty); setText(null); link.setText(empty ? "" : value);
                link.setDisable(runningRound == null); setGraphic(empty ? null : link);
                link.setStyle("-fx-padding: 0;" + (value != null && value.contains("Cần kiểm tra") ? "-fx-text-fill: #b13b28; -fx-font-weight: bold;" : ""));
            }
        });
        roster.getColumns().add(warningColumn);
        roster.getColumns().add(UiSupport.column("IP", 140, row -> session(row.code()) == null ? "" : session(row.code()).ip()));
        TableColumn<ClassService.RosterStudent, Void> actions = new TableColumn<>("Xử lý"); actions.setPrefWidth(180);
        actions.setCellFactory(column -> new TableCell<>() {
            final Button warning = new Button("Cảnh báo"), locking = new Button("Khóa");
            final HBox buttons = new HBox(6, warning, locking);
            {
                warning.getStyleClass().add("small"); locking.getStyleClass().add("small");
                setStyle("-fx-padding: 4 8;");
                warning.setOnAction(e -> act(warn)); locking.setOnAction(e -> act(lock));
            }
            private void act(Consumer<ClientSessionInfo> action) {
                var row = getTableRow().getItem(); if (row != null && session(row.code()) != null) action.accept(session(row.code()));
            }
            @Override protected void updateItem(Void value, boolean empty) {
                super.updateItem(value, empty); setGraphic(empty ? null : buttons);
                var row = getTableRow() == null ? null : getTableRow().getItem(); buttons.setDisable(row == null || session(row.code()) == null);
            }
        });
        roster.getColumns().add(actions); roster.setPlaceholder(new Label("Chưa có sinh viên trong danh sách."));
        VBox body = new VBox(14, selection, count, roster); VBox.setVgrow(roster, Priority.ALWAYS); setCenter(body);
        sessions.addListener(listener); reload();
        deadlineWatcher.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> {
            if (++progressTicks % 2 == 0) refreshProgress();
            if (deadlineMillis > 0 && exams.currentTime().toEpochMilli() >= deadlineMillis) {
                deadlineMillis = 0;
                showShiftState();
                updateActions();
            }
        }));
        deadlineWatcher.setCycleCount(javafx.animation.Timeline.INDEFINITE); deadlineWatcher.play();
    }
    private ClientSessionInfo session(String code) { return sessions.stream().filter(s -> s.studentCode().equals(code)).findFirst().orElse(null); }
    private void refreshConnections() {
        roster.refresh(); long online = roster.getItems().stream().filter(s -> session(s.code()) != null).count();
        count.setText(roster.getItems().size() + " sinh viên · " + online + " đang kết nối"); updateActions();
    }
    private void invalidatePreparation() { version++; progress = null; warnings = java.util.Map.of(); runningRound = null; deadlineMillis = 0; prepared = false; roster.getItems().clear(); refreshConnections(); updateActions(); }
    private void refreshProgress() {
        if (disposed || busy || progressLoading || group.getValue() == null) return;
        long request = version;
        Long classId = group.getValue().id(), shiftId = shift.getValue() == null ? null : shift.getValue().id();
        boolean checkingPreparation = progress == null;
        List<ClassService.RosterStudent> expected = List.copyOf(roster.getItems());
        progressLoading = true;
        record Checked(java.util.Optional<ExamService.RoundProgress> progress, List<ClassService.RosterStudent> rows,
                       java.util.Map<String, ExamEventService.Summary> warnings) {}
        UiSupport.async("exam-roster-progress", () -> {
            var progress = exams.roundProgress(classId, shiftId);
            return new Checked(progress, checkingPreparation ? shiftId == null ? classes.roster(classId) : shifts.roster(classId, shiftId) : null,
                    progress.isEmpty() ? java.util.Map.of() : exams.events().summaries(progress.get().roundId()));
        }, result -> {
            progressLoading = false;
            if (disposed || request != version) return;
            if (checkingPreparation && !new java.util.HashSet<>(expected).equals(new java.util.HashSet<>(result.rows()))) {
                invalidatePreparation(); prepare(); return;
            }
            if (checkingPreparation && result.progress().filter(saved -> shiftId != null || exams.currentTime().isBefore(saved.deadline())).isPresent()) {
                invalidatePreparation(); prepare(); return;
            }
            progress = checkingPreparation ? null : result.progress().orElse(null);
            warnings = progress == null ? java.util.Map.of() : result.warnings();
            runningRound = progress == null ? null : progress.roundId();
            deadlineMillis = progress == null || !exams.currentTime().isBefore(progress.deadline())
                    ? 0 : progress.deadline().toEpochMilli();
            if (progress != null) showRoundConfiguration();
            roster.refresh(); showShiftState(); updateActions();
        }, ex -> {
            progressLoading = false;
            if (!disposed && request == version) status.setText("Chưa cập nhật được trạng thái bài thi: " + ex.getMessage());
        });
    }
    private void filterTemplates() {
        ClassInfo selected = group.getValue();
        Long oldExam = exam.getValue() == null ? null : exam.getValue().id();
        exam.getItems().setAll(templates.stream().filter(t -> selected != null && (t.subjectName() == null || t.subjectName().equals(selected.subjectName()))).toList());
        exam.setValue(exam.getItems().stream().filter(t -> t.id().equals(oldExam)).findFirst().orElse(exam.getItems().isEmpty() ? null : exam.getItems().getFirst()));
    }
    private void showRoundConfiguration() {
        changingOptions = true;
        try {
            if (progress == null) filterTemplates();
            else {
                ExamSummary saved = progress.template();
                if (!saved.equals(exam.getValue()) || exam.getItems().size() != 1) {
                    exam.getItems().setAll(saved); exam.setValue(saved);
                }
                variantCount.getValueFactory().setValue(progress.variantCount());
            }
        } finally { changingOptions = false; }
    }
    void reload() {
        if (disposed) return; long request = ++version; busy = true; runningRound = null; warnings = java.util.Map.of(); deadlineMillis = 0; updateActions();
        Long oldClass = group.getValue() == null ? null : group.getValue().id();
        record Options(List<ClassInfo> groups, List<ExamSummary> templates) {}
        UiSupport.async("exam-organizer-options", () -> new Options(classes.list(), exams.listExams()), options -> {
            if (disposed || request != version) return;
            busy = false; progress = null; prepared = false; templates = options.templates(); changingOptions = true;
            group.getItems().setAll(options.groups());
            group.setValue(options.groups().stream().filter(g -> g.id().equals(oldClass)).findFirst().orElse(options.groups().isEmpty() ? null : options.groups().getFirst()));
            filterTemplates(); changingOptions = false; roster.getItems().clear(); loadShifts();
        }, this::failed);
    }
    private void prepare() {
        ClassInfo selected = group.getValue(); if (selected == null) { status.setText("Chưa có lớp học phần."); return; }
        var selectedShift = shift.getValue();
        long request = ++version; prepared = false; busy = true; status.setText("Đang tải danh sách…"); updateActions();
        record Prepared(List<ClassService.RosterStudent> rows, java.util.Optional<ExamService.RoundProgress> progress) {}
        UiSupport.async("prepare-exam-roster", () -> {
            var savedProgress = exams.roundProgress(selected.id(), selectedShift == null ? null : selectedShift.id());
            boolean opened = savedProgress.isPresent() && (selectedShift != null || exams.currentTime().isBefore(savedProgress.get().deadline()));
            return new Prepared(opened ? exams.rosterForRound(savedProgress.get().roundId())
                    : selectedShift == null ? classes.roster(selected.id()) : shifts.roster(selected.id(), selectedShift.id()), savedProgress);
        }, data -> {
            if (disposed || request != version) return;
            var rows = data.rows();
            progress = data.progress().filter(saved -> selectedShift != null || exams.currentTime().isBefore(saved.deadline())).orElse(null);
            runningRound = progress == null ? null : progress.roundId();
            deadlineMillis = progress == null || !exams.currentTime().isBefore(progress.deadline()) ? 0 : progress.deadline().toEpochMilli();
            boolean opened = selectedShift != null && selectedShift.roundId() != null
                    || progress != null && exams.currentTime().isBefore(progress.deadline());
            showRoundConfiguration();
            busy = false; roster.getItems().setAll(rows); prepared = !rows.isEmpty() && !opened; refreshConnections();
            showShiftState();
            refreshProgress();
        }, this::failed);
    }
    private void start() {
        if (!prepared || exam.getValue() == null || server.get() == null || !server.get().isRunning()) return;
        Long classId = group.getValue().id(), templateId = exam.getValue().id(); version++; progress = null; busy = true; updateActions();
        Long shiftId = shift.getValue() == null ? null : shift.getValue().id();
        record Started(ExamServer.Publication publication, List<ExamShiftService.ShiftInfo> shifts) {}
        int variants = variantCount.getValue();
        var preparedRoster = List.copyOf(roster.getItems());
        // A rejected configuration leaves this roster available for another
        // start, which still validates it inside the opening transaction.
        UiSupport.async("start-lan-exam", () -> new Started(server.get().openExam(templateId, classId, shiftId, variants, preparedRoster), shifts.list(classId)), opened -> {
            if (disposed) return; busy = false; prepared = false; updateActions();
            var publication = opened.publication(); setShifts(opened.shifts(), shiftId);
            runningRound = publication.exam().getId(); deadlineMillis = publication.exam().getDeadlineEpochMillis();
            showShiftState();
            announce.accept(status.getText()); refreshConnections(); refreshProgress();
        }, this::failed);
    }
    private void updateActions() {
        boolean opened = runningRound != null || shift.getValue() != null && shift.getValue().roundId() != null;
        group.setDisable(busy); exam.setDisable(busy || opened); shift.setDisable(busy || shift.getItems().isEmpty()); capacity.setDisable(busy);
        split.setDisable(busy || group.getValue() == null);
        start.setDisable(busy || !prepared || exam.getValue() == null || server.get() == null || !server.get().isRunning());
        boolean running = runningRound != null && deadlineMillis > exams.currentTime().toEpochMilli();
        end.setVisible(running); end.setManaged(running);
        start.setVisible(!running); start.setManaged(!running);
        variantCount.setDisable(busy || opened);
        end.setDisable(busy || runningRound == null || deadlineMillis <= exams.currentTime().toEpochMilli()
                || server.get() == null || !server.get().isRunning());
    }
    private void end() {
        if (end.isDisabled()) return;
        Long roundId = runningRound;
        ExamServer currentServer = server.get();
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(getScene().getWindow()); AppUi.desktopStyle(confirmation.getDialogPane());
        confirmation.getDialogPane().setId("exam-end-confirmation");
        confirmation.setTitle("Dừng bài thi"); confirmation.setHeaderText("Kết thúc ca #" + roundId + " ngay bây giờ?");
        confirmation.setContentText("Thu và chấm các đáp án Server đã nhận. Sinh viên chưa vào bài sẽ được ghi vắng. Không thể tiếp tục ca này sau khi dừng.");
        ((Button) confirmation.getDialogPane().lookupButton(ButtonType.OK)).setText("Dừng và chấm bài");
        ((Button) confirmation.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Hủy");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        version++; busy = true; prepared = false; updateActions();
        UiSupport.async("end-lan-exam", () -> { currentServer.endExam(roundId); return true; }, done -> {
            if (disposed) return;
            busy = false; deadlineMillis = 0; updateActions();
            showShiftState();
            announce.accept("Đã dừng ca #" + roundId + ". Xem điểm tại Kết quả."); refreshProgress();
        }, this::failed);
    }
    private void showShiftState() {
        var selected = shift.getValue();
        if (runningRound != null && deadlineMillis > exams.currentTime().toEpochMilli())
            status.setText("Đang thi · Hạn nộp " + UiSupport.time(java.time.Instant.ofEpochMilli(deadlineMillis)));
        else if (runningRound != null || selected != null && selected.roundId() != null) status.setText("Đã kết thúc");
        else if (group.getValue() == null) status.setText("Chưa có lớp học phần.");
        else if (roster.getItems().isEmpty()) status.setText("Chưa có sinh viên.");
        else if (exam.getValue() == null) status.setText("Chưa có đề thi cùng môn.");
        else status.setText("Chưa bắt đầu");
    }
    private void setShifts(List<ExamShiftService.ShiftInfo> items, Long selectedId) {
        changingOptions = true;
        shift.getItems().setAll(items);
        shift.setValue(items.stream().filter(s -> s.id().equals(selectedId)).findFirst().orElse(items.isEmpty() ? null : items.getFirst()));
        changingOptions = false; showShiftState(); updateActions();
    }
    private void loadShifts() {
        ClassInfo selected = group.getValue();
        if (selected == null) { busy = false; setShifts(List.of(), null); refreshConnections(); return; }
        Long oldShift = shift.getValue() == null ? null : shift.getValue().id();
        long request = ++version; busy = true; prepared = false; updateActions();
        UiSupport.async("load-exam-shifts", () -> shifts.list(selected.id()), items -> {
            if (disposed || request != version) return;
            busy = false; setShifts(items, oldShift); prepare();
        }, this::failed);
    }
    private void split() {
        if (busy || group.getValue() == null) return;
        capacity.getEditor().setText(Integer.toString(capacity.getValue()));
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Chia ca thi"); AppUi.desktopStyle(dialog.getDialogPane());
        dialog.getDialogPane().setId("exam-split-dialog");
        Label error = AppUi.label("", "error"); error.setId("exam-split-error");
        VBox form = new VBox(12, new Label(group.getValue().toString()), AppUi.toolbar(new Label("Sinh viên mỗi ca"), capacity), error);
        form.setPrefWidth(480); dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button confirm = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        confirm.setId("exam-split-confirm"); confirm.setText("Chia theo STT");
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Hủy");
        confirm.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            final int size;
            try {
                size = Integer.parseInt(capacity.getEditor().getText().strip());
                if (size < 1 || size > RosterCsv.MAX_ROWS) throw new NumberFormatException();
                capacity.getValueFactory().setValue(size);
            } catch (NumberFormatException ex) { error.setText("Nhập số sinh viên từ 1 đến 2.000."); return; }
            confirm.setDisable(true); dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);
            dialog.setOnCloseRequest(e -> { if (busy) e.consume(); });
            split(size, dialog, error);
        });
        dialog.showAndWait();
    }
    private void split(int size, Dialog<ButtonType> dialog, Label error) {
        Long classId = group.getValue().id(); long request = ++version; prepared = false; busy = true; updateActions();
        UiSupport.async("split-exam-shifts", () -> shifts.split(classId, size), items -> {
            if (disposed || request != version) return;
            busy = false; dialog.setResult(ButtonType.OK); dialog.close();
            invalidatePreparation(); setShifts(items, null); prepare();
        }, ex -> {
            if (disposed || request != version) return;
            busy = false; prepared = progress == null && !roster.getItems().isEmpty(); error.setText(ex.getMessage());
            dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(false);
            dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(false);
            updateActions();
        });
    }
    private void failed(Exception ex) { if (!disposed) { busy = false; status.setText(ex.getMessage()); updateActions(); } }
    void serverStateChanged() { updateActions(); }
    void dispose() { disposed = true; version++; deadlineWatcher.stop(); sessions.removeListener(listener); }
}
