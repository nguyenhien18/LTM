package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.AppUi;
import com.ptit.lanexam.common.ui.UiSupport;
import com.ptit.lanexam.server.service.BankQuestionInfo;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.function.Consumer;

final class QuestionDetailsDialog {
    private QuestionDetailsDialog() {}

    static void show(Window owner, BankQuestionInfo question) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Câu hỏi #" + question.id());
        dialog.setResizable(true);
        DialogPane pane = dialog.getDialogPane();
        pane.setId("question-details");
        AppUi.desktopStyle(pane);
        TextArea content = new TextArea(question.preview());
        content.setId("question-details-content");
        content.setEditable(false);
        content.setWrapText(true);
        content.setPrefRowCount(16);
        VBox body = new VBox(10, new Label(question.subjectName() + " · Mức " + question.difficulty()), content);
        VBox.setVgrow(content, Priority.ALWAYS);
        pane.setContent(body);
        pane.setPrefSize(700, 540);
        ButtonType close = new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().add(close);
        pane.lookupButton(close).setId("question-details-close");
        dialog.showAndWait();
    }

    static TableColumn<BankQuestionInfo, String> contentColumn(String title, int width,
                                                              Consumer<BankQuestionInfo> open) {
        var column = UiSupport.<BankQuestionInfo>column(title, width, q -> q.content().replaceAll("\\s+", " "));
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Hyperlink link = new Hyperlink();
            {
                link.setStyle("-fx-padding: 0;");
                link.setMaxWidth(Double.MAX_VALUE);
                link.setOnAction(event -> {
                    BankQuestionInfo question = getTableRow().getItem();
                    if (question != null) open.accept(question);
                });
            }
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(null);
                link.setText(empty ? "" : value);
                setGraphic(empty ? null : link);
            }
        });
        return column;
    }
}
