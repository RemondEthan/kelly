package com.mordor.kelly.ui.chat;

import com.mordor.kelly.ui.theme.ThemeService;

import java.util.List;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Popup;

/**
 * 表情选择弹窗：点击 emoji 按钮时浮出一个表情网格。
 *
 * <h3>JavaFX Popup 机制</h3>
 * <p>{@link Popup} 是 JavaFX 的浮动窗口，与 {@link javafx.stage.Stage} 不同：
 * <ul>
 *   <li>无标题栏、无任务栏图标</li>
 *   <li>可在任意位置显示，不阻塞主窗口</li>
 *   <li>只能 {@code getContent().add(...)} 一次根节点</li>
 *   <li>{@code setAutoHide(true)} 点击弹窗外任意位置自动关闭</li>
 * </ul>
 *
 * <h3>事件处理细节</h3>
 * <p>autoHide 在 MOUSE_PRESSED 阶段触发，而按钮 onAction 在 RELEASED 阶段触发。
 * 为防止 autoHide 关闭后 onAction 又重新打开弹窗，用时间戳 {@link #lastAutoHideNanos}
 * 识别同一次点击（250ms 窗口）。</p>
 *
 * <h3>布局结构</h3>
 * <pre>
 *   Popup
 *   └── GridPane (6列网格)
 *       ├── Button[0,0] Button[1,0] ... Button[5,0]
 *       ├── Button[0,1] Button[1,1] ... Button[5,1]
 *       └── ...
 * </pre>
 */
public class EmojiPopover {

    /** 浮动弹窗，只能添加一个根节点 */
    private final Popup popup = new Popup();

    /** 表情网格（弹窗内容根节点，兼作色板锚点） */
    private GridPane grid;

    /** 表情插入的目标文本框（聊天输入框） */
    private final TextField target;
    private javafx.scene.control.IndexRange insertionSelection;
    private String insertionText;

    /**
     * 上次 autoHide 触发的时间戳（纳秒）。
     * autoHide 在 MOUSE_PRESSED 触发，onAction 在 RELEASED 触发；
     * 用时间戳差值判断是否为同一次点击。
     */
    private long lastAutoHideNanos;

    /**
     * 构造表情弹窗。
     *
     * @param target 表情要插入到的目标文本框
     */
    public EmojiPopover(TextField target) {
        this.target = target;
        grid = buildGrid();
        popup.getContent().add(grid);  // Popup 只能 add 一次根节点
        popup.setAutoHide(true);  // 点击弹窗外自动关闭
        popup.setOnAutoHide(e -> {
            lastAutoHideNanos = System.nanoTime();insertionSelection = null;insertionText = null;
        });
    }

    /**
     * 构造表情网格。
     *
     * <p>{@link GridPane} 是 JavaFX 的网格布局，按行列定位子节点。
     * 每个格子放一个表情按钮，列号 = i % 6，行号 = i / 6。</p>
     *
     * @return 填充好表情按钮的 GridPane
     */
    private GridPane buildGrid() {
        GridPane panel = new GridPane();
        panel.setHgap(2);   // 水平间距
        panel.setVgap(2);   // 垂直间距
        panel.setPadding(new Insets(6));
        // 弹窗自带独立 Scene：挂上聊天样式表并单独装载色板
        panel.getStyleClass().add("emoji-popup");
        panel.getStylesheets().add(
                EmojiPopover.class.getResource("chat.css").toExternalForm());
        ThemeService.apply(panel, new ThemeService().load());

        List<String> emojis = EmojiImages.getSupportedEmojis();
        for (int i = 0; i < emojis.size(); i++) {
            String emoji = emojis.get(i);
            Button b = new Button();
            b.setGraphic(EmojiImages.view(emoji, 18));  // 用 ImageView 显示 emoji 图片
            b.setStyle("-fx-background-color: transparent; " +
                    "-fx-cursor: hand; " +
                    "-fx-padding: 2 4 2 4;");
            // 点击表情：插入到文本框光标处 + 关闭弹窗
            b.setOnAction(e -> {
                popup.hide();
                insertAtCaret(emoji);
            });
            panel.add(b, i % 6, i / 6);  // col = i%6, row = i/6
        }
        return panel;
    }

    /**
     * 将 emoji 插入到文本框当前光标位置。
     *
     * <p>保留打开弹窗前的选区，使用 replaceText 插入以保留撤销记录；
     * 恢复焦点后折叠选区到 emoji 后，避免后续输入覆盖表情。</p>
     *
     * @param emoji 要插入的 emoji 字符串
     */
    void rememberSelection() {
        insertionSelection = target.getSelection();insertionText = target.getText();
    }

    void insertAtCaret(String emoji) {
        var range = insertionSelection != null && java.util.Objects.equals(insertionText, target.getText())
                ? insertionSelection : target.getSelection();
        insertionSelection = null;insertionText = null;
        int start = range.getStart();
        int end = range.getEnd();
        target.replaceText(start, end, emoji);
        int caret = start + emoji.length();
        String expected = target.getText();
        target.requestFocus();
        target.selectRange(caret, caret);
        // Native popup focus restoration may arrive after the button action (Windows/GTK).
        javafx.application.Platform.runLater(() -> {
            if (!expected.equals(target.getText())) return; // Never disturb subsequent typing.
            var selection = target.getSelection();
            if ((selection.getStart() == 0 && selection.getEnd() == expected.length())
                    || (selection.getStart() == start && selection.getEnd() == caret)) {
                target.selectRange(caret, caret);
            }
        });
    }

    /**
     * 在锚点节点附近显示/隐藏弹窗（toggle 行为）。
     *
     * @param anchor 触发按钮，弹窗显示在其上方
     */
    public void show(Node anchor) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        // 防止 autoHide 关闭后 onAction 又重新打开（250ms 内的点击忽略）
        if (System.nanoTime() - lastAutoHideNanos < 250_000_000L) {
            return;
        }
        if (insertionSelection == null || !java.util.Objects.equals(insertionText, target.getText())) rememberSelection();
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        if (b == null) return;
        Node content = popup.getContent().getFirst();
        // 每次显示都重新套一次主题，避免用户中途切换深浅色后弹窗仍是旧配色
        ThemeService.apply(grid, new ThemeService().load());
        content.applyCss();           // 强制计算布局
        double h = content.prefHeight(-1);
        if (h <= 0) h = 200;
        // 弹窗显示在按钮上方
        popup.show(anchor, b.getMinX(), b.getMinY() - h);
    }

    /**
     * 隐藏弹窗。
     */
    public void hide() {
        popup.hide();insertionSelection = null;insertionText = null;
    }
}