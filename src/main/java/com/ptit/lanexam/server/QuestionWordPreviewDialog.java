package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.*;
import com.ptit.lanexam.server.service.*;
import javafx.collections.FXCollections;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

/** Preview only: closing or cancelling this dialog never writes to the bank. */
public final class QuestionWordPreviewDialog {
    private QuestionWordPreviewDialog() {}
    public static boolean show(Window owner, SubjectInfo subject, QuestionBankService.WordPreview preview) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Nhập câu hỏi từ Word"); dialog.setHeaderText("Môn học: " + subject.name()); dialog.setResizable(true);
        DialogPane pane = dialog.getDialogPane(); pane.setId("bank-word-preview"); AppUi.desktopStyle(pane);
        ButtonType confirm = new ButtonType("Nhập " + preview.count(QuestionBankService.WordStatus.NEW) + " câu mới", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        pane.lookupButton(ButtonType.CANCEL).setId("bank-word-cancel");
        Button commit = (Button) pane.lookupButton(confirm); commit.setId("bank-word-confirm"); commit.getStyleClass().add("primary");
        commit.setDisable(preview.hasErrors() || preview.count(QuestionBankService.WordStatus.NEW) == 0);
        TableView<QuestionBankService.WordRow> table = new TableView<>(FXCollections.observableArrayList(preview.rows()));
        table.setId("bank-word-rows"); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(UiSupport.column("Vị trí trong Word", 160, r -> r.item().label()));
        table.getColumns().add(UiSupport.column("Mức", 55, r -> Integer.toString(r.item().difficulty())));
        table.getColumns().add(UiSupport.column("Nội dung", 440, r -> r.item().question().content().replaceAll("\\s+", " ")));
        table.getColumns().add(UiSupport.column("Đáp án", 75, r -> r.item().question().correctAnswer() == null ? "—" : r.item().question().correctAnswer()));
        table.getColumns().add(UiSupport.column("Kiểm tra", 190, r -> r.status().toString()));
        TextArea detail = new TextArea(); detail.setId("bank-word-detail"); detail.setEditable(false); detail.setWrapText(true); detail.setPrefRowCount(9); detail.setMinHeight(160);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, row) -> {
            if (row == null) { detail.clear(); return; }
            var q = row.item().question();
            detail.setText(row.item().label() + " · Mức " + row.item().difficulty() + "\n" + q.content()
                    + "\nA. " + q.optionA() + "\nB. " + q.optionB() + "\nC. " + q.optionC() + "\nD. " + q.optionD()
                    + "\nĐáp án: " + (q.correctAnswer() == null ? "chưa có" : q.correctAnswer()) + "\n\n" + row.detail());
        });
        Label summary = AppUi.label(preview.summary(), "muted");
        Label instruction = AppUi.label(preview.hasErrors()
                ? "Tệp còn lỗi. Chọn dòng Cần sửa để xem chi tiết, sửa trong Word rồi nhập lại. Chưa lưu câu nào."
                : "Chọn từng dòng để kiểm tra đầy đủ A–D. Câu trùng hoàn toàn trong cùng môn và mức sẽ được bỏ qua.", "muted");
        VBox content = new VBox(10, summary, instruction);
        if (!preview.errors().isEmpty()) {
            TextArea errors = new TextArea(String.join("\n", preview.errors())); errors.setId("bank-word-errors");
            errors.setEditable(false); errors.setWrapText(true); errors.setPrefRowCount(3); content.getChildren().add(errors);
        }
        content.getChildren().addAll(table, detail); VBox.setVgrow(table, Priority.ALWAYS);
        pane.setContent(content); pane.setPrefSize(1020, 640);
        var firstInvalid = preview.rows().stream().filter(r -> r.status() == QuestionBankService.WordStatus.INVALID).findFirst();
        if (firstInvalid.isPresent()) table.getSelectionModel().select(firstInvalid.get()); else table.getSelectionModel().selectFirst();
        return dialog.showAndWait().filter(confirm::equals).isPresent();
    }
}
