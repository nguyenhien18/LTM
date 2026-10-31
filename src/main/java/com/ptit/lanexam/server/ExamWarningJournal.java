package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.*;
import com.ptit.lanexam.server.entity.ExamEvent.Kind;
import com.ptit.lanexam.server.network.ExamServer;
import com.ptit.lanexam.server.service.ExamEventService;
import javafx.animation.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;

final class ExamWarningJournal {
    private ExamWarningJournal() {}
    static void show(Window owner, ExamEventService events, ExamServer server, Long roundId, String code, String name) {
        Dialog<Void> dialog = new Dialog<>(); dialog.initOwner(owner);
        dialog.setTitle("Nhật ký ca #" + roundId + " · " + code); dialog.getDialogPane().setId("exam-warning-journal");
        AppUi.desktopStyle(dialog.getDialogPane());
        Label summary = new Label(name + " — " + code); summary.setWrapText(true);
        Label state = new Label(); state.setId("warning-journal-state"); state.setWrapText(true);
        TableView<ExamEventService.Entry> table = new TableView<>(); table.setId("warning-journal-events");
        var timestamp = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
        table.getColumns().add(UiSupport.column("Thời gian", 170, row -> timestamp.format(row.at())));
        table.getColumns().add(UiSupport.column("Sự kiện", 180, row -> label(row.kind())));
        var content = UiSupport.<ExamEventService.Entry>column("Nội dung", 470, ExamEventService.Entry::detail);
        content.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty); setText(empty ? null : value);
                setTooltip(empty || value == null ? null : new Tooltip(value));
            }
        });
        table.getColumns().add(content);
        table.getColumns().add(UiSupport.column("Đã kiểm tra", 100, row -> row.kind() == Kind.FOCUS_LOST ? row.reviewed() ? "Có" : "Chưa" : ""));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(340); table.setPlaceholder(new Label("Chưa có sự kiện trong ca này."));
        Button refresh = new Button("Làm mới"), remind = new Button("Gửi nhắc nhở"), review = new Button("Đánh dấu đã kiểm tra");
        refresh.setId("warning-journal-refresh"); remind.setId("warning-journal-remind"); review.setId("warning-journal-review");
        boolean[] loading = {false}, closed = {false};
        Runnable reload = () -> {
            if (closed[0] || loading[0]) return;
            loading[0] = true; refresh.setDisable(true);
            UiSupport.async("load-exam-warning-journal", () -> events.list(roundId, code), rows -> {
                loading[0] = false;
                if (closed[0]) return;
                refresh.setDisable(false); table.getItems().setAll(rows);
                long count = rows.stream().filter(row -> row.kind() == Kind.FOCUS_LOST).count();
                boolean pending = rows.stream().anyMatch(row -> row.kind() == Kind.FOCUS_LOST && !row.reviewed());
                state.setText(count + " lần rời cửa sổ" + (count >= 3 && pending ? " · Cần kiểm tra" : ""));
                review.setDisable(!pending);
                remind.setDisable(server == null || !server.isDoingExam(roundId, code));
            }, ex -> { loading[0] = false; if (!closed[0]) { refresh.setDisable(false); state.setText(ex.getMessage()); } });
        };
        refresh.setOnAction(e -> reload.run());
        review.setOnAction(e -> {
            review.setDisable(true);
            UiSupport.async("review-exam-warnings", () -> { events.review(roundId, code); return true; }, done -> reload.run(),
                    ex -> { if (!closed[0]) { review.setDisable(false); state.setText(ex.getMessage()); } });
        });
        remind.setOnAction(e -> {
            TextInputDialog input = new TextInputDialog("Vui lòng tập trung làm bài và không rời cửa sổ thi.");
            input.initOwner(dialog.getDialogPane().getScene().getWindow()); input.setTitle("Gửi nhắc nhở"); input.setHeaderText(name + " · " + code);
            AppUi.desktopStyle(input.getDialogPane());
            input.showAndWait().ifPresent(text -> {
                remind.setDisable(true);
                UiSupport.async("remind-exam-student", () -> { server.remindExam(roundId, code, text); return true; }, done -> reload.run(),
                        ex -> { if (!closed[0]) { remind.setDisable(false); state.setText(ex.getMessage()); } });
            });
        });
        dialog.getDialogPane().setContent(new VBox(12, summary, state, table, AppUi.toolbar(remind, review, AppUi.spacer(), refresh)));
        dialog.getDialogPane().setPrefWidth(1000); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE)).setText("Đóng");
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(2), e -> reload.run())); timer.setCycleCount(Timeline.INDEFINITE);
        dialog.setOnShown(e -> { reload.run(); timer.play(); });
        dialog.setOnHidden(e -> { closed[0] = true; timer.stop(); });
        dialog.showAndWait();
    }
    private static String label(Kind kind) {
        return switch (kind) {
            case FOCUS_LOST -> "Rời cửa sổ thi"; case MANUAL_WARNING -> "Giảng viên nhắc nhở";
            case REVIEWED -> "Đã kiểm tra"; case DISCONNECTED -> "Gián đoạn kết nối"; case RECONNECTED -> "Kết nối lại";
        };
    }
}
