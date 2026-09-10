package com.mordor.kelly.kelsy.ui;

import com.mordor.kelly.ui.chat.SelectableTextFlow;

import javafx.scene.control.Hyperlink;
import javafx.scene.layout.HBox;

import java.util.function.Consumer;

/** 助手气泡里的工具调用条，可点「打开」跳到知识库文件。 */
public final class ToolCallCard extends HBox {

    public ToolCallCard(String name, String openPath, Consumer<String> onOpen) {
        getStyleClass().add("tool-card");
        setSpacing(8);
        SelectableTextFlow label = SelectableTextFlow.forText("调用：" + (name == null ? "" : name));
        getChildren().add(label);
        if (openPath != null && !openPath.isBlank()) {
            Hyperlink open = new Hyperlink("打开");
            open.setOnAction(e -> {
                if (onOpen != null) {
                    onOpen.accept(openPath);
                }
            });
            getChildren().add(open);
        }
    }
}
