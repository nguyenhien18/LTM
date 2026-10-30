package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.UiSupport;
import com.ptit.lanexam.common.ui.AppUi;
import com.ptit.lanexam.server.service.AuthService;
import com.ptit.lanexam.server.service.LockedAccountInfo;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;

public final class LockedAccountManager {
    private LockedAccountManager() {}

    public static void show(Window owner, AuthService auth) {
        Stage window = new Stage();
        window.initOwner(owner);
        window.setTitle("Tài khoản bị khóa");
        VBox root = content(window, auth);
        root.setPadding(new Insets(20));
        window.setScene(AppUi.desktopScene(root, 850, 500));
        window.show();
    }

    public static VBox content(Window owner, AuthService auth) {
        TableView<LockedAccountInfo> table = new TableView<>();
        table.setId("locked-accounts");
        table.getColumns().add(UiSupport.column("Mã sinh viên", 150, LockedAccountInfo::studentCode));
        table.getColumns().add(UiSupport.column("Họ tên", 200, LockedAccountInfo::fullName));
        table.getColumns().add(UiSupport.column("Lý do khóa", 380, LockedAccountInfo::reason));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Không có tài khoản bị khóa."));
        Button unlock = new Button("Mở khóa");
        unlock.getStyleClass().add("primary");
        unlock.setId("account-unlock");
        unlock.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        Button refresh = new Button("Làm mới");
        Label message = new Label();
        HBox actions = new HBox(10, unlock, refresh);
        Runnable load = () -> {
            actions.setDisable(true);
            message.setText("Đang tải...");
            UiSupport.async("load-locked-accounts", auth::listLockedAccounts, accounts -> {
                table.getItems().setAll(accounts);
                message.setText(accounts.size() + " sinh viên bị bạn khóa. Mở khóa cho phép đăng nhập lại vào Server của bạn.");
                actions.setDisable(false);
            }, ex -> { actions.setDisable(false); message.setText("Không tải được: " + ex.getMessage()); });
        };
        refresh.setOnAction(event -> load.run());
        unlock.setOnAction(event -> {
            LockedAccountInfo account = table.getSelectionModel().getSelectedItem();
            if (account == null) return;
            actions.setDisable(true);
            UiSupport.async("unlock-student", () -> {
                auth.setAccountLocked(account.studentCode(), false, null);
                return account;
            }, unlocked -> load.run(), ex -> {
                actions.setDisable(false);
                UiSupport.error(owner, ex.getMessage());
            });
        });
        message.getStyleClass().add("muted");
        message.setWrapText(true);
        VBox root = new VBox(14, actions, table, message);
        VBox.setVgrow(table, Priority.ALWAYS);
        load.run();
        return root;
    }
}
