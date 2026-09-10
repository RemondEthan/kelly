package com.mordor.kelly.ui.chat;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Popup;

/**
 * 表情选择弹窗：点击 emoji 按钮时浮出一个 6×6 表情网格。
 *
 * Popup：JavaFX 的浮动窗口（无标题栏、可在任意位置显示），
 * 与 Dialog 不同，Popup 不会阻塞主窗口。
 * setAutoHide(true) 让点击弹窗外任意位置时自动关闭。
 */
public class EmojiPopover {

    // Popup：浮动窗口，只能添加一个根节点（这里用 GridPane）
    private final Popup popup = new Popup();

    // 表情插入的目标文本框（聊天输入框）
    private final TextField target;

    // autoHide 发生在按钮 MOUSE_PRESSED，onAction 在 RELEASED；用时间戳识别同一次点击
    private long lastAutoHideNanos;

    public EmojiPopover(TextField target) {
        this.target = target;
        // 把表情网格塞进 Popup，Popup 只能 getContent().add(...) 一次根节点
        popup.getContent().add(buildGrid());
        // setAutoHide(true)：点击 Popup 外面任何位置都会关闭它
        popup.setAutoHide(true);
        popup.setOnAutoHide(e -> lastAutoHideNanos = System.nanoTime());
    }

    /**
     * 构造 6×6 的表情网格。
     *
     * GridPane：网格布局，按行列定位子节点。
     *   setHgap / setVgap：相邻格子之间的水平/垂直间距
     *   grid.add(node, col, row)：把节点放到第 col 列、第 row 行
     */
    private GridPane buildGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(2);
        grid.setVgap(2);
        grid.setPadding(new Insets(6));
        // 直接用 setStyle 写 inline CSS：弹窗只在这里用，避免污染全局样式表
        grid.setStyle("-fx-background-color: white; " +
                "-fx-border-color: #E0E0E0; " +
                "-fx-border-radius: 4; " +
                "-fx-background-radius: 4;");

        for (int i = 0; i < EmojiImages.CATALOG.length; i++) {
            String emoji = EmojiImages.CATALOG[i];
            Button b = new Button();
            b.setGraphic(EmojiImages.view(emoji, 18));
            b.setStyle("-fx-background-color: transparent; " +
                    "-fx-cursor: hand; " +
                    "-fx-padding: 2 4 2 4;");
            // 点击表情：插入到文本框光标处 + 关闭弹窗
            b.setOnAction(e -> {
                insertAtCaret(emoji);
                popup.hide();
            });
            // i % 6 = 列号，i / 6 = 行号（自动向下取整）
            grid.add(b, i % 6, i / 6);
        }
        return grid;
    }

    /**
     * 把 emoji 插入到文本框当前光标位置，而不是直接 append 到末尾。
     * 这样用户在文本中间时也能正确插入。
     */
    private void insertAtCaret(String emoji) {
        String text = target.getText();
        // getCaretPosition()：当前光标在文本中的字符索引（0 ~ text.length()）
        int caret = target.getCaretPosition();
        // 把文本切成 [光标前] + emoji + [光标后]，重新拼接
        String next = text.substring(0, caret) + emoji + text.substring(caret);
        target.setText(next);
        // 把光标移到刚插入的 emoji 之后，便于连续插入多个
        target.positionCaret(caret + emoji.length());
        target.requestFocus();  // 把焦点还给文本框
    }

    /**
     * 在指定节点附近显示弹窗。
     * 如果已经显示，再点一次则关闭（toggle 行为）。
     *
     * @param anchor 锚点节点（一般是触发按钮），弹窗会出现在它的位置附近
     */
    public void show(Node anchor) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        // 点表情按钮关弹层：autoHide 先关掉，随后 onAction 又会进到这里；忽略这次重开
        if (System.nanoTime() - lastAutoHideNanos < 250_000_000L) {
            return;
        }
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        if (b == null) return;
        Node content = popup.getContent().getFirst();
        content.applyCss();
        double h = content.prefHeight(-1);
        if (h <= 0) h = 200;
        popup.show(anchor, b.getMinX(), b.getMinY() - h);
    }

    public void hide() {
        popup.hide();
    }
}