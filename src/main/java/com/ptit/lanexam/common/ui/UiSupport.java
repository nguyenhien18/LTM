package com.ptit.lanexam.common.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableCell;
import javafx.scene.control.Tooltip;
import javafx.stage.Window;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

public final class UiSupport {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZONE);

    private UiSupport() {}

    public static String time(Instant instant) { return instant == null ? "Không giới hạn" : FORMAT.format(instant); }
    public static String fileLabel(String name, long size) {
        return name == null ? "Không có tệp" : name + " (" + String.format(java.util.Locale.ROOT, "%.1f KB", size / 1024.0) + ")";
    }
    public static <T> TableColumn<T, String> column(String name, int width, Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(name);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                Tooltip tooltip = empty || item == null || item.isBlank() ? null : new Tooltip(item);
                if (tooltip != null) { tooltip.setWrapText(true); tooltip.setMaxWidth(460); }
                setTooltip(tooltip);
            }
        });
        return column;
    }
    public static <T> void async(String name, Callable<T> operation, Consumer<T> success, Consumer<Exception> failure) {
        Thread thread = new Thread(() -> {
            try {
                T result = operation.call();
                Platform.runLater(() -> success.accept(result));
            } catch (Exception ex) {
                Platform.runLater(() -> failure.accept(ex));
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }
    public static void error(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        AppUi.style(alert.getDialogPane());
        alert.initOwner(owner);
        alert.setHeaderText("Không thể thực hiện");
        alert.setContentText(message == null ? "Có lỗi xử lý dữ liệu." : message);
        alert.showAndWait();
    }
}
