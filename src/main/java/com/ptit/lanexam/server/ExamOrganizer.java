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
    private final Button split = new Button("Chia ca theo STT");
    private final Label shiftState = AppUi.label("", "muted");
    private final TableView<ClassService.RosterStudent> roster = new TableView<>();
    private final Label status = AppUi.label("Chọn lớp và đề, kiểm tra danh sách rồi bắt đầu ca thi.", "muted");
    private final Label count = AppUi.label("", "muted");
    private final Button prepare = new Button("Chuẩn bị ca thi");
    private final Button start = new Button("Bắt đầu thi");
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
    private boolean progressLoading;
    private int progressTicks;
    private final javafx.animation.Timeline deadlineWatcher = new javafx.animation.Timeline();
    private final ListChangeListener<ClientSessionInfo> listener = change -> refreshConnections();

    ExamOrganizer(ClassService classes, ExamService exams, ExamShiftService shifts, Supplier<ExamServer> server,
                  ObservableList<ClientSessionInfo> sessions, Consumer<ClientSessionInfo> warn,
                  Consumer<ClientSessionInfo> lock, Consumer<String> announce) {
        this.classes = classes; this.exams = exams; this.shifts = shifts; this.server = server; this.sessions = sessions; this.announce = announce;
        group.setId("exam-class"); exam.setId("exam-template"); group.setPromptText("Chọn lớp / nhóm thi"); exam.setPromptText("Chọn đề đã lưu");
        group.setMaxWidth(Double.MAX_VALUE); exam.setMaxWidth(Double.MAX_VALUE);
        exam.setConverter(new javafx.util.StringConverter<>() {
            public String toString(ExamSummary e) { return e == null ? "" : e.title() + " · " + e.questionCount() + " câu · " + e.durationMinutes() + " phút"; }
            public ExamSummary fromString(String s) { return null; }
        });
        group.valueProperty().addListener((o, old, value) -> {
            if (!changingOptions) { invalidatePreparation(); filterTemplates(); loadShifts(); }
        });
        exam.valueProperty().addListener((o, old, value) -> { if (!changingOptions) invalidatePreparation(); });
        shift.setId("exam-shift"); shift.setPromptText("Cả lớp · chưa chia ca"); shift.setMaxWidth(Double.MAX_VALUE);
        shift.setConverter(new javafx.util.StringConverter<>() {
            public String toString(ExamShiftService.ShiftInfo s) { return s == null ? "" : s.toString(); }
            public ExamShiftService.ShiftInfo fromString(String s) { return null; }
        });
        shift.valueProperty().addListener((o, old, value) -> { if (!changingOptions) { invalidatePreparation(); showShiftState(); } });
        capacity.setId("exam-shift-capacity"); capacity.setEditable(true); capacity.setPrefWidth(100);
        variantCount.setId("exam-variant-count"); variantCount.setPrefWidth(80);
        variantCount.valueProperty().addListener((o, old, value) -> { if (!changingOptions) invalidatePreparation(); });
        split.setId("exam-split"); split.setOnAction(e -> split());
        prepare.setId("exam-prepare"); prepare.setOnAction(e -> prepare());
        start.setId("exam-start"); start.getStyleClass().add("primary"); start.setOnAction(e -> start());
        Button refresh = new Button("Làm mới lớp / đề"); refresh.setId("exam-organizer-refresh"); refresh.setOnAction(e -> reload());
        HBox splitControls = AppUi.toolbar(new Label("Sinh viên / ca"), capacity, split);
        HBox shiftControls = AppUi.toolbar(shift, shiftState); HBox.setHgrow(shift, Priority.ALWAYS);
        VBox classControls = new VBox(10, new Label("1. Chọn lớp và ca thi"), group, splitControls, shiftControls);
        status.setWrapText(true); status.setMinWidth(0); status.setMaxWidth(Double.MAX_VALUE);
        VBox examControls = new VBox(10, new Label("2. Chọn đề kiểm tra"), exam,
                AppUi.toolbar(new Label("Số mã đề"), variantCount, AppUi.label("1: đề gốc · 2–8: đảo câu và lựa chọn", "muted")),
                AppUi.toolbar(prepare, start, AppUi.spacer(), refresh), status);
        classControls.setMinWidth(0); examControls.setMinWidth(0);
        classControls.setPrefWidth(480); examControls.setPrefWidth(480);
        HBox.setHgrow(classControls, Priority.ALWAYS); HBox.setHgrow(examControls, Priority.ALWAYS);
        VBox selection = AppUi.card(new HBox(22, classControls, examControls));
        roster.setId("exam-roster"); roster.setFixedCellSize(46); roster.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        var ordinalColumn = UiSupport.<ClassService.RosterStudent>column("STT", 60, r -> r.ordinal() == null ? "—" : r.ordinal().toString());
        ordinalColumn.setMinWidth(55); roster.getColumns().add(ordinalColumn);
        roster.getColumns().add(UiSupport.column("Mã sinh viên", 140, ClassService.RosterStudent::code));
        roster.getColumns().add(UiSupport.column("Họ và tên", 220, ClassService.RosterStudent::fullName));
        roster.getColumns().add(UiSupport.column("Lớp chính quy", 130, r -> r.regularClass() == null ? "" : r.regularClass()));
        roster.getColumns().add(UiSupport.column("Kết nối", 100, row -> session(row.code()) == null ? "Offline" : "Online"));
        roster.getColumns().add(UiSupport.column("Bài thi của ca này", 140,
                row -> progress == null ? "Chưa mở thi" : progress.state(row.code(), exams.currentTime())));
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
        roster.getColumns().add(actions); roster.setPlaceholder(new Label("Bấm Chuẩn bị ca thi để kiểm tra danh sách sinh viên."));
        VBox body = new VBox(14, selection, count, roster); VBox.setVgrow(roster, Priority.ALWAYS); setCenter(body);
        sessions.addListener(listener); reload();
        deadlineWatcher.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> {
            if (++progressTicks % 2 == 0) refreshProgress();
            if (deadlineMillis > 0 && exams.currentTime().toEpochMilli() >= deadlineMillis) {
                status.setText("Ca #" + runningRound + " đã hết giờ. Mở Kết quả để xem điểm."); deadlineMillis = 0;
                showShiftState();
            }
        }));
        deadlineWatcher.setCycleCount(javafx.animation.Timeline.INDEFINITE); deadlineWatcher.play();
    }
    private ClientSessionInfo session(String code) { return sessions.stream().filter(s -> s.studentCode().equals(code)).findFirst().orElse(null); }
    private void refreshConnections() {
        roster.refresh(); long online = roster.getItems().stream().filter(s -> session(s.code()) != null).count();
        count.setText(roster.getItems().isEmpty() && group.getValue() != null
                ? "Lớp có " + group.getValue().studentCount() + " sinh viên · Chuẩn bị ca để xem danh sách"
                : roster.getItems().size() + " sinh viên trong danh sách · " + online + " đã kết nối"); updateActions();
    }
    private void invalidatePreparation() { version++; progress = null; prepared = false; roster.getItems().clear(); refreshConnections(); status.setText("Chuẩn bị lại sau khi đổi lớp, ca hoặc đề."); updateActions(); }
    private void refreshProgress() {
        if (disposed || busy || progressLoading || roster.getItems().isEmpty() || group.getValue() == null) return;
        long request = version;
        Long classId = group.getValue().id(), shiftId = shift.getValue() == null ? null : shift.getValue().id();
        progressLoading = true;
        UiSupport.async("exam-roster-progress", () -> exams.roundProgress(classId, shiftId), result -> {
            progressLoading = false;
            if (disposed || request != version) return;
            progress = result.orElse(null); roster.refresh(); showShiftState();
        }, ex -> {
            progressLoading = false;
            if (!disposed && request == version) status.setText("Chưa cập nhật được trạng thái bài thi: " + ex.getMessage());
        });
    }
    private void filterTemplates() {
        ClassInfo selected = group.getValue();
        exam.getItems().setAll(templates.stream().filter(t -> selected != null && (t.subjectName() == null || t.subjectName().equals(selected.subjectName()))).toList());
        exam.getSelectionModel().selectFirst();
    }
    void reload() {
        if (disposed) return; long request = ++version; busy = true; updateActions();
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
        ClassInfo selected = group.getValue(); if (selected == null || exam.getValue() == null) return;
        var selectedShift = shift.getValue();
        long request = ++version; busy = true; updateActions();
        record Prepared(List<ClassService.RosterStudent> rows, java.util.Optional<ExamService.RoundProgress> progress) {}
        UiSupport.async("prepare-exam-roster", () -> new Prepared(selectedShift == null ? classes.roster(selected.id()) : shifts.roster(selected.id(), selectedShift.id()),
                exams.roundProgress(selected.id(), selectedShift == null ? null : selectedShift.id())), data -> {
            if (disposed || request != version) return;
            var rows = data.rows(); progress = data.progress().orElse(null);
            runningRound = progress == null ? null : progress.roundId();
            deadlineMillis = progress == null || !exams.currentTime().isBefore(progress.deadline()) ? 0 : progress.deadline().toEpochMilli();
            busy = false; roster.getItems().setAll(rows); prepared = !rows.isEmpty() && (selectedShift == null || selectedShift.roundId() == null); refreshConnections();
            status.setText(prepared ? "Sẵn sàng. Đồng hồ chỉ chạy sau khi bấm Bắt đầu thi."
                    : selectedShift != null && selectedShift.roundId() != null ? "Ca đã mở. Server tự khôi phục ca còn hạn khi khởi động; xem tiến độ trong bảng bên dưới."
                    : "Lớp chưa có sinh viên. Nhập CSV trong Lớp & sinh viên trước.");
        }, this::failed);
    }
    private void start() {
        if (!prepared || server.get() == null || !server.get().isRunning()) return;
        Long classId = group.getValue().id(), templateId = exam.getValue().id(); version++; progress = null; busy = true; updateActions();
        Long shiftId = shift.getValue() == null ? null : shift.getValue().id();
        record Started(ExamServer.Publication publication, List<ExamShiftService.ShiftInfo> shifts) {}
        int variants = variantCount.getValue();
        UiSupport.async("start-lan-exam", () -> new Started(server.get().openExam(templateId, classId, shiftId, variants), shifts.list(classId)), opened -> {
            if (disposed) return; busy = false; prepared = false; updateActions();
            var publication = opened.publication(); setShifts(opened.shifts(), shiftId);
            runningRound = publication.exam().getId(); deadlineMillis = publication.exam().getDeadlineEpochMillis();
            status.setText("Đang thi · Ca #" + publication.exam().getId() + " · Hạn nộp "
                    + UiSupport.time(java.time.Instant.ofEpochMilli(publication.exam().getDeadlineEpochMillis())));
            announce.accept(status.getText()); refreshConnections(); refreshProgress();
        }, this::failed);
    }
    private void updateActions() {
        group.setDisable(busy); exam.setDisable(busy); shift.setDisable(busy || shift.getItems().isEmpty()); capacity.setDisable(busy); variantCount.setDisable(busy);
        split.setDisable(busy || group.getValue() == null);
        prepare.setDisable(busy || group.getValue() == null || exam.getValue() == null);
        start.setDisable(busy || !prepared || server.get() == null || !server.get().isRunning());
    }
    private void showShiftState() {
        var selected = shift.getValue();
        shiftState.setText(selected == null ? "Thi cả lớp nếu chưa chia ca"
                : progress != null && java.util.Objects.equals(selected.roundId(), progress.roundId())
                ? (exams.currentTime().isBefore(progress.deadline()) ? "Đang thi" : "Đã kết thúc") : selected.state(exams.currentTime()));
        prepare.setText(selected != null && selected.roundId() != null ? "Xem danh sách ca" : "Chuẩn bị ca thi");
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
            busy = false; setShifts(items, oldShift); refreshConnections();
            status.setText(items.isEmpty() ? "Có thể thi cả lớp hoặc chia ca theo STT gốc trên CSV. Chuẩn bị chưa tính giờ."
                    : "Đã lưu " + items.size() + " ca. Chọn ca và đề, chuẩn bị danh sách rồi bắt đầu.");
        }, this::failed);
    }
    private void split() {
        if (group.getValue() == null) return;
        final int size;
        try {
            size = Integer.parseInt(capacity.getEditor().getText().strip());
            if (size < 1 || size > RosterCsv.MAX_ROWS) throw new NumberFormatException();
            capacity.getValueFactory().setValue(size);
        } catch (NumberFormatException ex) { status.setText("Số sinh viên mỗi ca từ 1–2.000."); return; }
        Long classId = group.getValue().id(); long request = ++version; prepared = false; busy = true; updateActions();
        UiSupport.async("split-exam-shifts", () -> shifts.split(classId, size), items -> {
            if (disposed || request != version) return;
            busy = false; roster.getItems().clear(); setShifts(items, null); refreshConnections();
            status.setText("Đã lưu " + items.size() + " ca theo STT CSV tăng dần, tối đa " + size + " sinh viên/ca. Chọn ca rồi bấm Chuẩn bị ca thi.");
        }, this::failed);
    }
    private void failed(Exception ex) { if (!disposed) { busy = false; status.setText(ex.getMessage()); updateActions(); } }
    void serverStateChanged() { updateActions(); }
    void dispose() { disposed = true; version++; deadlineWatcher.stop(); sessions.removeListener(listener); }
}
