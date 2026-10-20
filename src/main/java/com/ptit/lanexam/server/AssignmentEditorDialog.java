package com.ptit.lanexam.server;

import com.ptit.lanexam.common.TransferLimits;
import com.ptit.lanexam.common.ui.AssignmentUi;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

public final class AssignmentEditorDialog {
    private AssignmentEditorDialog() {}
    public record Input(String title, String description, Instant dueAt, File attachment) {}

    public static Optional<Input> show(Window owner) {
        Dialog<Input> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Giao bài tập");
        ButtonType create = new ButtonType("Giao bài tập", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
        TextField title = new TextField();
        title.setPromptText("Tên bài tập (tối đa 200 ký tự)");
        TextArea description = new TextArea();
        description.setWrapText(true);
        description.setPrefRowCount(8);
        description.setPromptText("Yêu cầu bài tập, cách làm và định dạng bài cần nộp...");
        CheckBox useDeadline = new CheckBox("Đặt hạn nộp (giờ Việt Nam)");
        DatePicker date = new DatePicker(LocalDate.now(AssignmentUi.ZONE).plusDays(7));
        TextField time = new TextField("23:59");
        time.setPrefColumnCount(5);
        date.disableProperty().bind(useDeadline.selectedProperty().not());
        time.disableProperty().bind(useDeadline.selectedProperty().not());
        File[] attachment = {null};
        Label fileLabel = new Label("Chưa đính kèm tệp");
        fileLabel.setWrapText(true);
        Button choose = new Button("Đính kèm đề bài");
        Button remove = new Button("Bỏ tệp");
        choose.setOnAction(event -> {
            File chosen = AssignmentUi.chooseAttachment(owner);
            if (chosen != null) {
                attachment[0] = chosen;
                fileLabel.setText(AssignmentUi.fileLabel(chosen.getName(), chosen.length()));
            }
        });
        remove.setOnAction(event -> { attachment[0] = null; fileLabel.setText("Chưa đính kèm tệp"); });
        Label error = new Label();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #b00020;");
        VBox body = new VBox(10, new Label("Tên bài tập"), title, new Label("Nội dung yêu cầu"), description,
                useDeadline, new HBox(10, date, new Label("Giờ (HH:mm):"), time),
                new HBox(10, choose, remove), fileLabel,
                new Label("Tệp tối đa 5 MB. Học viên được nộp lại trước hạn; bản mới thay bản cũ."), error);
        body.setPadding(new Insets(14));
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().setPrefWidth(660);
        Instant[] deadline = {null};
        dialog.getDialogPane().lookupButton(create).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                if (title.getText().isBlank() || title.getText().trim().length() > 200)
                    throw new IllegalArgumentException("Nhập tên bài tập từ 1 đến 200 ký tự.");
                if (description.getText().length() > TransferLimits.MAX_TEXT_LENGTH)
                    throw new IllegalArgumentException("Nội dung tối đa 20.000 ký tự.");
                if (description.getText().isBlank() && attachment[0] == null)
                    throw new IllegalArgumentException("Nhập yêu cầu hoặc đính kèm đề bài.");
                deadline[0] = null;
                if (useDeadline.isSelected()) {
                    if (date.getValue() == null) throw new IllegalArgumentException("Chọn ngày hết hạn.");
                    try {
                        deadline[0] = date.getValue().atTime(LocalTime.parse(time.getText().trim()))
                                .atZone(AssignmentUi.ZONE).toInstant();
                    } catch (java.time.DateTimeException ex) {
                        throw new IllegalArgumentException("Nhập giờ đúng định dạng HH:mm, ví dụ 23:59.");
                    }
                    if (!deadline[0].isAfter(Instant.now())) throw new IllegalArgumentException("Hạn nộp phải ở tương lai.");
                }
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == create
                ? new Input(title.getText().trim(), description.getText().trim(), deadline[0], attachment[0]) : null);
        return dialog.showAndWait();
    }
}
