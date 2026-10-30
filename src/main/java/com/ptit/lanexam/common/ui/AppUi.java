package com.ptit.lanexam.common.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Shared presentation helpers for the teacher and student applications. */
public final class AppUi {
    private static final String STYLESHEET = AppUi.class.getResource("/styles/app.css").toExternalForm();
    private static final String DESKTOP_STYLESHEET = AppUi.class.getResource("/styles/desktop.css").toExternalForm();

    private AppUi() {}

    public static Scene scene(Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(STYLESHEET);
        return scene;
    }

    public static void style(Parent root) {
        if (!root.getStylesheets().contains(STYLESHEET)) root.getStylesheets().add(STYLESHEET);
    }

    public static Scene desktopScene(Parent root, double width, double height) {
        desktopStyle(root);
        return scene(root, width, height);
    }

    public static void desktopStyle(Parent root) {
        style(root);
        if (!root.getStyleClass().contains("desktop-ui")) root.getStyleClass().add("desktop-ui");
        if (!root.getStylesheets().contains(DESKTOP_STYLESHEET)) root.getStylesheets().add(DESKTOP_STYLESHEET);
    }

    public static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    public static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    public static HBox toolbar(Node... children) {
        HBox bar = new HBox(10, children);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar");
        return bar;
    }

    public static VBox heading(String title, String description) {
        return new VBox(5, label(title, "page-title"), label(description, "muted"));
    }

    public static VBox card(Node... children) {
        VBox card = new VBox(12, children);
        card.getStyleClass().add("card");
        return card;
    }
}
