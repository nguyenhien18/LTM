package com.ptit.lanexam.server;

import com.ptit.lanexam.server.service.QuestionBankService;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import javafx.stage.Stage;
import javafx.stage.Window;

public final class QuestionBankManager {
    private QuestionBankManager() {}

    public static void show(Window owner, QuestionBankService service) {
        Stage window = new Stage();
        window.initOwner(owner);
        window.setTitle("Ngân hàng câu hỏi");
        QuestionBankBrowser browser = new QuestionBankBrowser(service);
        Label guide = new Label("Thêm nhiều câu hỏi theo môn và độ khó vào đây, sau đó chọn lại khi tạo đề 1, đề 2… Một câu dùng được cho nhiều đề.");
        guide.setWrapText(true);
        VBox content = new VBox(8, guide, browser);
        content.setPadding(new Insets(10));
        VBox.setVgrow(browser, Priority.ALWAYS);
        window.setScene(new Scene(content, 960, 640));
        window.setOnHidden(event -> browser.dispose());
        window.show();
    }
}
