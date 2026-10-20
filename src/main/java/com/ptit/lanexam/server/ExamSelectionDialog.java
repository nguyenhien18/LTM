package com.ptit.lanexam.server;

import com.ptit.lanexam.server.service.ExamSummary;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

final class ExamSelectionDialog {
    private ExamSelectionDialog() {}

    static Optional<ExamSummary> show(Window owner, List<ExamSummary> exams) {
        Dialog<ExamSummary> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Chọn bài thi để mở");
        dialog.setHeaderText("Chọn đề đã lưu. Mở lại sau khi đợt cũ hoàn tất sẽ tạo đợt thi mới.");
        ButtonType openType = new ButtonType("Mở bài thi đã chọn", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(openType, ButtonType.CANCEL);

        TableView<ExamSummary> table = new TableView<>(FXCollections.observableArrayList(exams));
        table.getColumns().add(column("Mã đề", 75, item -> Long.toString(item.id())));
        table.getColumns().add(column("Tên bài thi", 335, ExamSummary::title));
        table.getColumns().add(column("Thời gian", 100, item -> item.durationMinutes() + " phút"));
        table.getColumns().add(column("Số câu", 75, item -> Integer.toString(item.questionCount())));
        table.getColumns().add(column("Trạng thái", 125,
                item -> item.active() ? "Đề hiện tại" : "Đã lưu"));
        table.setPlaceholder(new Label("Chưa có bài thi nào. Hãy tạo đề trước."));
        table.setPrefHeight(320);
        Button openButton = (Button) dialog.getDialogPane().lookupButton(openType);
        openButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        table.setRowFactory(view -> {
            TableRow<ExamSummary> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) openButton.fire();
            });
            return row;
        });
        VBox body = new VBox(10, new Label("Chọn một hàng trong danh sách bài thi MySQL:"), table);
        body.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().setPrefWidth(780);
        dialog.setResultConverter(button -> button == openType
                ? table.getSelectionModel().getSelectedItem() : null);
        return dialog.showAndWait();
    }

    private static TableColumn<ExamSummary, String> column(String title, int width,
            Function<ExamSummary, String> value) {
        TableColumn<ExamSummary, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }
}
