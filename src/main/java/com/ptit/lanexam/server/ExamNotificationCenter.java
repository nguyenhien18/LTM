package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.*;
import com.ptit.lanexam.server.network.ExamServer;
import com.ptit.lanexam.server.service.ExamEventService;
import javafx.animation.*;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.Duration;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;

/** Persistent queue plus a non-modal, coalesced toast. */
final class ExamNotificationCenter {
    private final Window owner;
    private final ExamEventService events;
    private final Supplier<ExamServer> server;
    private final Button badge = new Button("Thông báo · 0");
    private final VBox toast = new VBox(8);
    private final Label toastText = new Label(), status = new Label();
    private final TableView<ExamEventService.Notice> table = new TableView<>();
    private final CheckBox pendingOnly = new CheckBox("Chưa xử lý");
    private final Set<String> announced = new HashSet<>();
    private final Timeline polling = new Timeline();
    private final PauseTransition hideToast = new PauseTransition(Duration.seconds(8));
    private List<ExamEventService.Notice> notices = List.of();
    private Stage window;
    private boolean loading, disposed;

    ExamNotificationCenter(Window owner, ExamEventService events, Supplier<ExamServer> server) {
        this.owner = owner; this.events = events; this.server = server;
        badge.setId("teacher-notifications"); badge.setOnAction(e -> open());
        toast.setId("teacher-notification-toast"); toastText.setId("teacher-notification-text"); toastText.setWrapText(true);
        Button view = new Button("Xem danh sách"), dismiss = new Button("Đóng");
        view.setOnAction(e -> { hide(); open(); }); dismiss.setOnAction(e -> hide());
        toast.getChildren().addAll(toastText, AppUi.toolbar(view, AppUi.spacer(), dismiss));
        toast.setPadding(new Insets(16)); toast.setMaxWidth(410); toast.setMaxHeight(Region.USE_PREF_SIZE);
        toast.setStyle("-fx-background-color: white; -fx-border-color: #999; -fx-border-width: 1;"); hide();
        StackPane.setAlignment(toast, Pos.TOP_RIGHT); StackPane.setMargin(toast, new Insets(65, 20, 0, 0));
        hideToast.setOnFinished(e -> hide());
        polling.getKeyFrames().add(new KeyFrame(Duration.seconds(2), e -> refresh()));
        polling.setCycleCount(Timeline.INDEFINITE); polling.play(); refresh();
    }
    Button badge() { return badge; }
    VBox toast() { return toast; }
    private void hide() { toast.setVisible(false); toast.setManaged(false); hideToast.stop(); }
    private void refresh() {
        if (disposed || loading) return;
        loading = true;
        UiSupport.async("teacher-notification-poll", events::notices, rows -> {
            loading = false; if (disposed) return;
            notices = rows; long pending = rows.stream().filter(ExamEventService.Notice::pending).count();
            badge.setText("Thông báo · " + pending);
            badge.setStyle(pending > 0 ? "-fx-text-fill: #a63222; -fx-font-weight: bold;" : "");
            var fresh = rows.stream().filter(ExamEventService.Notice::pending).filter(n -> announced.add(n.token())).toList();
            if (!fresh.isEmpty()) {
                var first = fresh.getFirst();
                toastText.setText(fresh.size() == 1 ? first.studentCode() + " — " + first.fullName() + "\n"
                        + first.classLabel() + " · " + first.roundLabel() + "\nĐã rời cửa sổ thi " + first.count() + " lần. Cần kiểm tra."
                        : fresh.size() + " sinh viên cần kiểm tra.\nBấm Xem danh sách để xem lớp, ca và nhật ký.");
                toast.setVisible(true); toast.setManaged(true); hideToast.playFromStart();
            }
            if (pending == 0) hide();
            filter(); status.setText(pending + " cảnh báo chưa xử lý");
        }, ex -> { loading = false; if (!disposed) status.setText("Chưa tải được thông báo: " + ex.getMessage()); });
    }
    private void filter() {
        var selected = table.getSelectionModel().getSelectedItem();
        table.setItems(FXCollections.observableArrayList(notices.stream().filter(n -> !pendingOnly.isSelected() || n.pending()).toList()));
        if (selected != null) table.getSelectionModel().select(table.getItems().stream()
                .filter(n -> n.roundId().equals(selected.roundId()) && n.studentCode().equals(selected.studentCode())).findFirst().orElse(null));
    }
    private void details() {
        var selected = table.getSelectionModel().getSelectedItem(); if (selected == null) return;
        ExamWarningJournal.show(window, events, server.get(), selected.roundId(), selected.studentCode(), selected.fullName());
        refresh();
    }
    private void open() {
        if (window != null) { window.show(); window.toFront(); refresh(); return; }
        window = new Stage(); window.initOwner(owner); window.setTitle("Thông báo phòng thi");
        table.setId("teacher-notification-list"); table.setPlaceholder(new Label("Không có cảnh báo phù hợp."));
        table.getColumns().add(UiSupport.column("Sinh viên", 250, n -> n.studentCode() + " — " + n.fullName()));
        table.getColumns().add(UiSupport.column("Lớp / Ca", 300, n -> n.classLabel() + " · " + n.roundLabel()));
        table.getColumns().add(UiSupport.column("Số lần", 65, n -> Long.toString(n.count())));
        var format = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM").withZone(ZoneId.systemDefault());
        table.getColumns().add(UiSupport.column("Cập nhật", 130, n -> format.format(n.updatedAt())));
        table.getColumns().add(UiSupport.column("Trạng thái", 110, ExamEventService.Notice::state));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) details(); });
        Button details = new Button("Xem chi tiết"), refresh = new Button("Làm mới"); details.setId("teacher-notification-open");
        details.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull()); details.setOnAction(e -> details()); refresh.setOnAction(e -> refresh());
        pendingOnly.setSelected(true); pendingOnly.selectedProperty().addListener((o, a, b) -> filter());
        VBox body = new VBox(12, AppUi.toolbar(pendingOnly, AppUi.spacer(), details, refresh), table, status);
        body.setId("teacher-notification-center"); body.setPadding(new Insets(18)); VBox.setVgrow(table, Priority.ALWAYS);
        window.setScene(AppUi.desktopScene(body, 1000, 500)); window.show(); filter(); refresh();
    }
    void dispose() { disposed = true; polling.stop(); hide(); if (window != null) window.close(); }
}
