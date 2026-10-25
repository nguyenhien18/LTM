package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.AssignmentUi;
import com.ptit.lanexam.server.service.AuthService;
import com.ptit.lanexam.server.service.LockedAccountInfo;
import javafx.geometry.Insets;
import javafx.scene.Scene;
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
        TableView<LockedAccountInfo> table = new TableView<>();
        table.setId("locked-accounts");
        table.getColumns().add(AssignmentUi.column("Mã sinh viên", 150, LockedAccountInfo::studentCode));
        table.getColumns().add(AssignmentUi.column("Họ tên", 200, LockedAccountInfo::fullName));
        table.getColumns().add(AssignmentUi.column("Lý do khóa", 380, LockedAccountInfo::reason));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Không có tài khoản bị khóa."));
        Button unlock = new Button("Mở khóa tài khoản đã chọn");
        unlock.setId("account-unlock");
        unlock.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        Button refresh = new Button("Làm mới");
        Label message = new Label();
        HBox actions = new HBox(10, unlock, refresh);
        Runnable load = () -> {
            actions.setDisable(true);
            message.setText("Đang tải...");
            AssignmentUi.async("load-locked-accounts", auth::listLockedAccounts, accounts -> {
                table.getItems().setAll(accounts);
                message.setText(accounts.size() + " tài khoản bị khóa. Mở khóa cho phép sinh viên đăng nhập lại.");
                actions.setDisable(false);
            }, ex -> { actions.setDisable(false); message.setText("Không tải được: " + ex.getMessage()); });
        };
        refresh.setOnAction(event -> load.run());
        unlock.setOnAction(event -> {
            LockedAccountInfo account = table.getSelectionModel().getSelectedItem();
            if (account == null) return;
            actions.setDisable(true);
            AssignmentUi.async("unlock-student", () -> {
                auth.setAccountLocked(account.studentCode(), false, null);
                return account;
            }, unlocked -> load.run(), ex -> {
                actions.setDisable(false);
                AssignmentUi.error(window, ex.getMessage());
            });
        });
        VBox root = new VBox(10, table, message, actions);
        root.setPadding(new Insets(14));
        VBox.setVgrow(table, Priority.ALWAYS);
        window.setScene(new Scene(root, 800, 460));
        window.show();
        load.run();
    }
}
