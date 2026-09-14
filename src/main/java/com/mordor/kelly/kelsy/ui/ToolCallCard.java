/**
 * 工具调用卡片组件。
 *
 * <p>在 AI 助手气泡中显示工具调用信息。当 AI 调用外部工具（如读取文件、
 * 搜索知识库）时，此组件显示工具名称，并提供"打开"链接跳转到相关文件。
 *
 * <p>布局结构：
 * <pre>
 *   HBox
 *   ├── SelectableTextFlow ("调用：工具名称")
 *   └── Hyperlink ("打开")  ← 仅当有关联文件时显示
 * </pre>
 *
 * <p>使用场景：
 * AI 调用 read_file 工具读取 "knowledge/meetings/xxx.md" 时，
 * 卡片显示 "调用：read_file" 和"打开"链接，点击可跳转到该文件。
 *
 * @see MessageBlock.Kind#TOOL
 */
package com.mordor.kelly.kelsy.ui;

import com.mordor.kelly.ui.chat.SelectableTextFlow;

import javafx.scene.control.Hyperlink;
import javafx.scene.layout.HBox;

import java.util.function.Consumer;

/** 助手气泡里的工具调用条，可点「打开」跳到知识库文件。 */
public final class ToolCallCard extends HBox {

    /**
     * 创建工具调用卡片。
     *
     * @param name     工具名称（如 "read_file"、"memory_search"）
     * @param openPath 关联的文件路径（为空时不显示"打开"链接）
     * @param onOpen   打开链接的回调函数
     */
    public ToolCallCard(String name, String openPath, Consumer<String> onOpen) {
        getStyleClass().add("tool-card");
        setSpacing(8);
        SelectableTextFlow label = SelectableTextFlow.forText("调用：" + (name == null ? "" : name));
        getChildren().add(label);
        // 仅当有关联文件时显示"打开"链接
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
