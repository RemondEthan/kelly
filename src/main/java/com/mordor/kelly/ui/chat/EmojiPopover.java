package com.mordor.kelly.ui.chat;

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

    /** 表情插入的目标文本框（聊天输入框） */
    private final TextField target;

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
        popup.getContent().add(buildGrid());  // Popup 只能 add 一次根节点
        popup.setAutoHide(true);  // 点击弹窗外自动关闭
        popup.setOnAutoHide(e -> lastAutoHideNanos = System.nanoTime());
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
        GridPane grid = new GridPane();
        grid.setHgap(2);   // 水平间距
        grid.setVgap(2);   // 垂直间距
        grid.setPadding(new Insets(6));
        // inline CSS：弹窗独立使用，不污染全局样式表
        grid.setStyle("-fx-background-color: white; " +
                "-fx-border-color: #E0E0E0; " +
                "-fx-border-radius: 4; " +
                "-fx-background-radius: 4;");

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
                insertAtCaret(emoji);
                popup.hide();
            });
            grid.add(b, i % 6, i / 6);  // col = i%6, row = i/6
        }
        return grid;
    }

    /**
     * 将 emoji 插入到文本框当前光标位置。
     *
     * <p>实现方式：将文本切成 [光标前] + emoji + [光标后] 三段重新拼接，
     * 然后将光标移到 emoji 之后，便于连续插入多个表情。</p>
     *
     * @param emoji 要插入的 emoji 字符串
     */
    private void insertAtCaret(String emoji) {
        String text = target.getText();
        int caret = target.getCaretPosition();  // 光标在文本中的字符索引
        String next = text.substring(0, caret) + emoji + text.substring(caret);
        target.setText(next);
        target.positionCaret(caret + emoji.length());  // 光标移到 emoji 后
        target.requestFocus();  // 把键盘焦点还给文本框
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
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        if (b == null) return;
        Node content = popup.getContent().getFirst();
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
        popup.hide();
    }
}