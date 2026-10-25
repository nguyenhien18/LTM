package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.AssignmentUi;
import com.ptit.lanexam.server.network.ClientSessionInfo;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;

import java.util.function.Consumer;

public final class StudentSessionTable extends TableView<ClientSessionInfo> {
    public StudentSessionTable(ObservableList<ClientSessionInfo> rows,
                               Consumer<ClientSessionInfo> warn, Consumer<ClientSessionInfo> lock) {
        super(rows);
        setId("online-students");
        getColumns().add(AssignmentUi.column("Mã sinh viên", 145, ClientSessionInfo::studentCode));
        getColumns().add(AssignmentUi.column("Họ tên", 190, ClientSessionInfo::fullName));
        getColumns().add(AssignmentUi.column("IP", 130, ClientSessionInfo::ip));
        getColumns().add(AssignmentUi.column("Trạng thái phiên", 145, ClientSessionInfo::status));
        getColumns().add(AssignmentUi.column("Đề đang làm / đã nộp", 220,
                row -> row.examId() == null ? "—" : "#" + row.examId() + " — " + row.examTitle()));
        TableColumn<ClientSessionInfo, ClientSessionInfo> actions = new TableColumn<>("Cảnh báo / Tài khoản");
        actions.setPrefWidth(260);
        actions.setSortable(false);
        actions.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        actions.setCellFactory(column -> new TableCell<>() {
            private final Button warning = new Button("Cảnh báo vi phạm");
            private final Button locking = new Button("Khóa tài khoản");
            private final HBox buttons = new HBox(6, warning, locking);
            {
                warning.setId("student-warn");
                locking.setId("student-lock");
                warning.setOnAction(event -> { if (getItem() != null) warn.accept(getItem()); });
                locking.setOnAction(event -> { if (getItem() != null) lock.accept(getItem()); });
            }
            @Override protected void updateItem(ClientSessionInfo row, boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                setGraphic(empty || row == null ? null : buttons);
            }
        });
        getColumns().add(actions);
        Label empty = new Label("Chưa có sinh viên online.\nTài khoản đã khóa vẫn có trong danh sách Tài khoản bị khóa / Mở khóa ở phía trên.");
        empty.setWrapText(true);
        empty.setMaxWidth(500);
        empty.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        setPlaceholder(empty);
        setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }
}
