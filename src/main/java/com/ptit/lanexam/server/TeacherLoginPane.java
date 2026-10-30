package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.UiSupport;
import com.ptit.lanexam.common.ui.AppUi;
import com.ptit.lanexam.server.service.TeacherAccountService;
import com.ptit.lanexam.server.service.TeacherIdentity;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import java.util.function.Consumer;

public final class TeacherLoginPane extends VBox {
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label message = new Label();
    private boolean disposed;

    public TeacherLoginPane(TeacherAccountService accounts, Consumer<TeacherIdentity> onLogin) {
        super(12);
        AppUi.desktopStyle(this);
        setMaxWidth(420);
        setMaxHeight(Region.USE_PREF_SIZE);
        getStyleClass().add("login-card");
        Label title = new Label("Đăng nhập giáo viên");
        title.getStyleClass().add("page-title");
        Label guide = AppUi.label("Tổ chức trắc nghiệm và thực hành trong phòng máy.", "muted");
        guide.setWrapText(true);
        username.setId("teacher-login-username"); username.setPromptText("Tên đăng nhập");
        password.setId("teacher-login-password"); password.setPromptText("Mật khẩu");
        Button login = new Button("Đăng nhập"); login.setId("teacher-login-submit"); login.setDefaultButton(true);
        Button create = new Button("Tạo tài khoản giáo viên"); create.setId("teacher-login-create");
        login.getStyleClass().add("primary");
        login.setMaxWidth(Double.MAX_VALUE);
        create.setMaxWidth(Double.MAX_VALUE);
        message.setId("teacher-login-message"); message.setWrapText(true);
        message.getStyleClass().add("muted");
        login.setOnAction(event -> {
            if (username.getText().isBlank() || password.getText().isEmpty()) { message.setText("Nhập tên đăng nhập và mật khẩu."); return; }
            String loginName = username.getText(); String secret = password.getText();
            setDisable(true); message.setText("Đang đăng nhập...");
            UiSupport.async("teacher-login", () -> accounts.authenticate(loginName, secret), teacher -> {
                if (disposed) return;
                setDisable(false); password.clear();
                if (teacher.isPresent()) onLogin.accept(teacher.get()); else message.setText("Tên đăng nhập hoặc mật khẩu không đúng.");
            }, ex -> { if (!disposed) { setDisable(false); password.clear(); message.setText(ex.getMessage()); } });
        });
        create.setOnAction(event -> TeacherAccountDialogs.register(getScene().getWindow()).ifPresent(input -> {
            setDisable(true); message.setText("Đang tạo tài khoản...");
            UiSupport.async("create-teacher", () -> accounts.create(input.username(), input.fullName(), input.password()), created -> {
                if (disposed) return;
                setDisable(false); username.setText(created.username()); password.clear();
                message.setText("Đã tạo tài khoản " + created.username() + ". Nhập mật khẩu để đăng nhập."); password.requestFocus();
            }, ex -> { if (!disposed) { setDisable(false); message.setText(ex.getMessage()); } });
        }));
        getChildren().addAll(title,
                new Label("Tên đăng nhập"), username, new Label("Mật khẩu"), password, login, create, message);
    }
    public void dispose() { disposed = true; password.clear(); }
}
