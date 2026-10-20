package com.ptit.lanexam.common.ui;

import com.ptit.lanexam.common.TransferLimits;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.TableColumn;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

public final class AssignmentUi {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZONE);

    private AssignmentUi() {}

    public static String time(Instant instant) { return instant == null ? "Không giới hạn" : FORMAT.format(instant); }
    public static String fileLabel(String name, long size) {
        return name == null ? "Không có tệp" : name + " (" + String.format(java.util.Locale.ROOT, "%.1f KB", size / 1024.0) + ")";
    }
    public static <T> TableColumn<T, String> column(String name, int width, Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(name);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
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
        alert.initOwner(owner);
        alert.setHeaderText("Không thể thực hiện");
        alert.setContentText(message == null ? "Có lỗi xử lý dữ liệu." : message);
        alert.showAndWait();
    }
    public static File chooseAttachment(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp đính kèm (tối đa 5 MB)");
        File file = chooser.showOpenDialog(owner);
        if (file != null && (file.length() == 0 || file.length() > TransferLimits.MAX_ATTACHMENT_BYTES)) {
            error(owner, "Tệp phải có dữ liệu và không vượt quá 5 MB.");
            return null;
        }
        return file;
    }
    public static File chooseDestination(Window owner, String name) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Lưu tệp");
        chooser.setInitialFileName(name);
        return chooser.showSaveDialog(owner);
    }
    public static byte[] readAttachment(File file) throws IOException {
        if (file == null) return null;
        try (var input = Files.newInputStream(file.toPath())) {
            byte[] data = input.readNBytes(TransferLimits.MAX_ATTACHMENT_BYTES + 1);
            TransferLimits.validateAttachment(file.getName(), data);
            return data;
        }
    }
    public static void save(Window owner, File destination, byte[] data, Consumer<String> saved) {
        async("save-attachment", () -> {
            Files.write(destination.toPath(), data);
            return destination.getAbsolutePath();
        }, saved, ex -> error(owner, ex.getMessage()));
    }
}
