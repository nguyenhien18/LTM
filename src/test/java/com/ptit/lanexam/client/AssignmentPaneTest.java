package com.ptit.lanexam.client;

import com.ptit.lanexam.common.*;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.common.dto.AssignmentMessages.SubmitRequest;
import com.ptit.lanexam.server.AssignmentManager;
import com.ptit.lanexam.server.service.AssignmentService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "lanexam.uiTests", matches = "true")
class AssignmentPaneTest {
    private static final Instant TIME = Instant.parse("2026-10-20T08:00:00Z");

    @BeforeAll
    static void initializeToolkit() throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.complete(null);
        });
        ready.get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void shutdownToolkit() { Platform.exit(); }

    @Test
    void incomingUpdatesPreserveStudentDraftAndResubmissionKeepsAttachedFile() throws Exception {
        BlockingQueue<NetworkMessage> outgoing = new LinkedBlockingQueue<>();
        AssignmentPane pane = fx(() -> {
            AssignmentPane view = new AssignmentPane(null, outgoing::add);
            Scene scene = new Scene(view, 900, 600);
            view.setItems(List.of(task(submission("Bài cũ"))));
            view.applyCss();
            view.layout();
            TextArea answer = (TextArea) scene.lookup("#assignment-answer");
            answer.setText("Bài mới chưa gửi");
            view.setItems(List.of(task(submission("Bài cũ"))));
            assertEquals("Bài mới chưa gửi", answer.getText(), "Làm mới danh sách không được xóa nội dung đang soạn");
            ((Button) scene.lookup("#assignment-submit")).fire();
            return view;
        });
        NetworkMessage sent = outgoing.poll(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals(MessageType.ASSIGNMENT_SUBMIT, sent.getType());
        SubmitRequest request = JsonUtil.payload(sent, SubmitRequest.class);
        assertEquals(1L, request.assignmentId());
        assertEquals("Bài mới chưa gửi", request.content());
        assertTrue(request.keepExistingFile());
        assertNull(request.fileData());
        fx(() -> {
            pane.submitted(submission("Bài mới chưa gửi"));
            pane.setItems(List.of(task(submission("Bài mới chưa gửi"))));
            assertEquals("Bài mới chưa gửi", ((TextArea) pane.lookup("#assignment-answer")).getText());
            assertFalse(((Button) pane.lookup("#assignment-submit")).isDisabled());
            pane.dispose();
            return null;
        });
    }

    @Test
    void studentAndTeacherViewsRenderAndTeacherCanReadSelectedSubmission() throws Exception {
        fx(() -> {
            AssignmentPane pane = new AssignmentPane(null, ignored -> {});
            new Scene(pane, 920, 620);
            pane.applyCss();
            pane.layout();
            assertTrue(((Button) pane.lookup("#assignment-submit")).isDisabled());
            pane.setItems(List.of(task(submission("Em đã hoàn thành chương trình Socket.\nMã nguồn nằm trong source.zip."))));
            snapshot(pane, "assignment-student");
            pane.dispose();
            return null;
        });
        fx(() -> {
            AssignmentService sample = new AssignmentService() {
                @Override public List<AssignmentInfo> listForTeacher() { return List.of(task(null)); }
                @Override public List<AssignmentSubmissionInfo> listSubmissions(Long id) {
                    return List.of(submission("Em đã hoàn thành chương trình Socket.\nMã nguồn nằm trong source.zip."));
                }
            };
            new AssignmentManager(null, sample, () -> {});
            return null;
        });
        Stage teacher = null;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            teacher = fx(() -> Window.getWindows().stream().filter(w -> w instanceof Stage s
                    && "Bài tập - Giảng viên".equals(s.getTitle())).map(w -> (Stage) w).findFirst().orElse(null));
            Stage current = teacher;
            if (current != null && fx(() -> !((TableView<?>) current.getScene().lookup("#teacher-submissions")).getItems().isEmpty()))
                break;
            Thread.sleep(25);
        }
        assertNotNull(teacher);
        Stage finalTeacher = teacher;
        fx(() -> {
            TableView<?> table = (TableView<?>) finalTeacher.getScene().lookup("#teacher-submissions");
            assertEquals(1, table.getItems().size());
            table.getSelectionModel().selectFirst();
            TextArea answer = (TextArea) finalTeacher.getScene().lookup("#teacher-submission-content");
            assertTrue(answer.getText().contains("hoàn thành"));
            snapshot(finalTeacher.getScene().getRoot(), "assignment-teacher");
            finalTeacher.close();
            return null;
        });
    }

    private static AssignmentInfo task(AssignmentSubmissionInfo mine) {
        return new AssignmentInfo(1L, "Bài tập 1: Lập trình TCP Socket",
                "Xây dựng ứng dụng chat trong mạng LAN.\nNộp mã nguồn và báo cáo dưới dạng một tệp ZIP.",
                TIME, null, "de-bai.pdf", 32000, 1, mine);
    }

    private static AssignmentSubmissionInfo submission(String content) {
        return new AssignmentSubmissionInfo(9L, "B21DCCN001", "Nguyễn Văn An", content, "source.zip", 64000, TIME);
    }

    private static <T> T fx(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(action.get()); }
            catch (Throwable ex) { result.completeExceptionally(ex); }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static void snapshot(javafx.scene.Parent root, String name) {
        try {
            root.applyCss();
            root.layout();
            WritableImage image = root.snapshot(null, null);
            BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++)
                for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            Path directory = Path.of("target", "assignment-preview");
            Files.createDirectories(directory);
            ImageIO.write(png, "png", directory.resolve(name + ".png").toFile());
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
}
