package com.mordor.kelly.kelsy.ui.markdown;

import javafx.scene.layout.GridPane;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownViewTest {

    @Test
    void tableRendersAsGrid() {
        var view = new MarkdownView(
                MarkdownRenderer.parse("| 待办 | 截止 |\n| --- | --- |\n| 周报 | 周五 |\n"),
                path -> {
                });
        assertTrue(view.getChildren().stream().anyMatch(n -> n instanceof GridPane));
        GridPane grid = (GridPane) view.getChildren().stream()
                .filter(n -> n instanceof GridPane)
                .findFirst()
                .orElseThrow();
        assertTrue(grid.getStyleClass().contains("md-table"));
    }
}
