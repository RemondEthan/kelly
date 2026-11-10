package com.mordor.kelly.ui.chat;

import com.mordor.kelly.ui.theme.AppTheme;
import com.mordor.kelly.ui.theme.ThemeService;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;

class ProbeTextFillTest {

    @Test
    void probe() throws Exception {
        FxTestSupport.startToolkit();
        var t = new FutureTask<Void>(() -> {
            String[] classes = {"bubble-system", "bubble-self", "bubble-peer", "thinking-body",
                    "todo-reminder", "todo-reminder-item", "todo-reminder-due", "md-view", "md-code"};
            for (String cls : classes) {
                StackPane root = new StackPane();
                root.getStylesheets().add(
                        ChatPane.class.getResource("chat.css").toExternalForm());
                ThemeService.apply(root, AppTheme.DARK);
                SelectableTextFlow flow = SelectableTextFlow.forText("已与 alice 连接");
                flow.getStyleClass().add(cls);
                root.getChildren().add(flow);
                new Scene(root, 400, 200);
                root.applyCss();
                root.layout();
                for (javafx.scene.Node n : flow.getChildren()) {
                    if (n instanceof Text tx) {
                        Color c = (Color) tx.getFill();
                        System.out.println("DARK " + cls + " fill=" + c);
                    }
                }
            }
            return null;
        });
        javafx.application.Platform.runLater(t);
        t.get();
    }
}
