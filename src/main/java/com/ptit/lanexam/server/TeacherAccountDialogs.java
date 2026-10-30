package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.AppUi;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import java.util.Optional;

public final class TeacherAccountDialogs {
    public record Registration(String username, String fullName, String password) {}
    public record PasswordChange(String currentPassword, String newPassword) {}
    private TeacherAccountDialogs() {}

    public static Optional<Registration> register(Window owner) {
        Dialog<Registration> dialog = registration(owner);
        return dialog.showAndWait();
    }
    public static Dialog<Registration> registration(Window owner) {
        Dialog<Registration> dialog = new Dialog<>();
        AppUi.desktopStyle(dialog.getDialogPane());
        dialog.initOwner(owner);
        dialog.setTitle("Tạo tài khoản giáo viên");
        dialog.getDialogPane().setId("teacher-register-dialog");
        dialog.setHeaderText("Tạo tài khoản để quản lý câu hỏi, đề thi và kết quả riêng.");
        TextField username = new TextField(); username.setId("teacher-register-username");
        TextField fullName = new TextField(); fullName.setId("teacher-register-name");
        PasswordField password = new PasswordField(); password.setId("teacher-register-password");
        PasswordField confirmation = new PasswordField(); confirmation.setId("teacher-register-confirm");
        Label error = errorLabel();
        VBox body = new VBox(8, new Label("Tên đăng nhập (3–50 ký tự không dấu)"), username,
                new Label("Họ tên giáo viên"), fullName, new Label("Mật khẩu (8–128 ký tự)"), password,
                new Label("Nhập lại mật khẩu"), confirmation, error);
        body.setPadding(new Insets(14));
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().setPrefWidth(540);
        ButtonType save = new ButtonType("Tạo tài khoản", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).setId("teacher-register-save");
        dialog.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION, event -> {
            String message = !username.getText().strip().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{2,49}") ? "Tên đăng nhập không hợp lệ."
                    : fullName.getText().isBlank() || fullName.getText().strip().length() > 120 ? "Nhập họ tên, tối đa 120 ký tự."
                    : passwordError(password.getText(), confirmation.getText());
            if (message != null) { error.setText(message); event.consume(); }
        });
        dialog.setResultConverter(button -> button == save ? new Registration(username.getText(), fullName.getText(), password.getText()) : null);
        return dialog;
    }

    public static Optional<PasswordChange> changePassword(Window owner) {
        Dialog<PasswordChange> dialog = new Dialog<>();
        AppUi.desktopStyle(dialog.getDialogPane());
        dialog.initOwner(owner); dialog.setTitle("Đổi mật khẩu giáo viên");
        PasswordField current = new PasswordField();
        PasswordField password = new PasswordField();
        PasswordField confirmation = new PasswordField();
        Label error = errorLabel();
        VBox body = new VBox(8, new Label("Mật khẩu hiện tại"), current, new Label("Mật khẩu mới (8–128 ký tự)"), password,
                new Label("Nhập lại mật khẩu mới"), confirmation, error);
        body.setPadding(new Insets(14));
        dialog.getDialogPane().setContent(body); dialog.getDialogPane().setPrefWidth(480);
        ButtonType save = new ButtonType("Đổi mật khẩu", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION, event -> {
            String message = current.getText().isEmpty() ? "Nhập mật khẩu hiện tại." : passwordError(password.getText(), confirmation.getText());
            if (message != null) { error.setText(message); event.consume(); }
        });
        dialog.setResultConverter(button -> button == save ? new PasswordChange(current.getText(), password.getText()) : null);
        return dialog.showAndWait();
    }
    private static Label errorLabel() {
        Label error = new Label(); error.setWrapText(true); error.setStyle("-fx-text-fill: #b00020;"); return error;
    }
    private static String passwordError(String password, String confirmation) {
        return password.length() < 8 || password.length() > 128 ? "Mật khẩu phải có từ 8 đến 128 ký tự."
                : !password.equals(confirmation) ? "Hai mật khẩu không khớp." : null;
    }
}
