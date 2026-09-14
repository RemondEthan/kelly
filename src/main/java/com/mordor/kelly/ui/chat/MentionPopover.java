package com.mordor.kelly.ui.chat;

import com.mordor.kelly.model.RoomMember;
import com.mordor.kelly.ui.AvatarView;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * @提及选择弹窗：在输入框上方显示匹配的成员列表。
 *
 * <h3>交互方式</h3>
 * <ul>
 *   <li>键盘 ↑/↓ 切换选中项</li>
 *   <li>回车确认选择</li>
 *   <li>ESC 关闭弹窗</li>
 *   <li>鼠标悬停高亮，点击选择</li>
 * </ul>
 *
 * <h3>布局结构</h3>
 * <pre>
 *   Popup
 *   └── ScrollPane (scroller)
 *       └── VBox (list)
 *           ├── HBox (mention-row): AvatarView + Label (成员名)
 *           ├── HBox (mention-row-active): 当前选中项（高亮样式）
 *           └── ...
 * </pre>
 *
 * <p>最多显示 {@value #MAX_VISIBLE} 行，超出部分通过 ScrollPane 滚动。</p>
 */
public final class MentionPopover {

    /** 最多可见行数 */
    private static final int MAX_VISIBLE = 6;
    /** 每行高度（px） */
    private static final double ROW_H = 40;

    /** 浮动弹窗 */
    private final Popup popup = new Popup();
    /** 成员列表容器 */
    private final VBox list = new VBox();
    /** 带滚动条的列表容器 */
    private final ScrollPane scroller = new ScrollPane(list);
    /** 头像查询函数 */
    private final BiFunction<RoomMember, String, Image> avatarOf;
    /** 选择成员的回调 */
    private final Consumer<RoomMember> onPick;

    /** 当前候选成员列表 */
    private final List<RoomMember> items = new ArrayList<>();
    /** 当前高亮的成员索引 */
    private int active;

    /**
     * 构造 @提及弹窗。
     *
     * @param avatarOf 头像查询函数：(成员, 用户名) → 头像图片
     * @param onPick   选择成员时的回调
     */
    public MentionPopover(BiFunction<RoomMember, String, Image> avatarOf,
                          Consumer<RoomMember> onPick) {
        this.avatarOf = avatarOf;
        this.onPick = onPick;
        list.getStyleClass().add("mention-popup");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);  // 隐藏水平滚动条
        scroller.setMaxHeight(MAX_VISIBLE * ROW_H);
        scroller.setPrefWidth(220);
        String css = MentionPopover.class.getResource("chat.css").toExternalForm();
        scroller.getStylesheets().add(css);
        popup.getContent().add(scroller);
        popup.setAutoHide(true);  // 点击外部自动关闭
    }

    /**
     * 弹窗是否正在显示。
     */
    public boolean isShowing() {
        return popup.isShowing();
    }

    /**
     * 隐藏弹窗并清空候选列表。
     */
    public void hide() {
        popup.hide();
        items.clear();
    }

    /**
     * 在锚点节点上方显示候选列表。
     *
     * @param anchor     触发 @ 输入的文本框
     * @param candidates 匹配的成员列表
     */
    public void show(Node anchor, List<RoomMember> candidates) {
        items.clear();
        if (candidates == null || candidates.isEmpty()) {
            hide();
            return;
        }
        items.addAll(candidates);
        active = 0;  // 默认选中第一个
        rebuild();
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        if (b == null) {
            return;
        }
        scroller.applyCss();
        scroller.autosize();
        double h = Math.min(items.size(), MAX_VISIBLE) * ROW_H + 8;
        // 弹窗显示在文本框上方
        popup.show(anchor, b.getMinX(), b.getMinY() - h);
    }

    /**
     * 处理键盘事件：↑/↓ 选择、回车确认、ESC 关闭。
     *
     * @param e 键盘事件
     * @return true 表示事件已消费
     */
    public boolean handleKey(KeyEvent e) {
        if (!popup.isShowing() || items.isEmpty()) {
            return false;
        }
        if (e.getCode() == KeyCode.UP) {
            active = (active - 1 + items.size()) % items.size();  // 循环向上
            rebuild();
            e.consume();
            return true;
        }
        if (e.getCode() == KeyCode.DOWN) {
            active = (active + 1) % items.size();  // 循环向下
            rebuild();
            e.consume();
            return true;
        }
        if (e.getCode() == KeyCode.ENTER) {
            pick(items.get(active));  // 确认选择
            e.consume();
            return true;
        }
        if (e.getCode() == KeyCode.ESCAPE) {
            hide();
            e.consume();
            return true;
        }
        return false;
    }

    /**
     * 重建成员列表 UI。
     *
     * <p>遍历候选成员，为每个成员创建一行（头像 + 名字），
     * 当前高亮项添加 "mention-row-active" 样式类。</p>
     */
    private void rebuild() {
        list.getChildren().clear();
        for (int i = 0; i < items.size(); i++) {
            RoomMember m = items.get(i);
            String name = m.username() == null || m.username().isBlank() ? "?" : m.username();
            Image photo = avatarOf.apply(m, name);

            HBox row = new HBox(8);
            row.getStyleClass().add("mention-row");
            if (i == active) {
                row.getStyleClass().add("mention-row-active");  // 高亮当前选中项
            }

            // 头像 + 名字
            row.getChildren().add(new AvatarView(name, photo, false, 28));
            Label label = new Label(name);
            label.getStyleClass().add("mention-name");
            row.getChildren().add(label);

            // 鼠标悬停高亮
            final int index = i;
            row.setOnMouseEntered(ev -> {
                active = index;
                rebuild();
            });
            // 鼠标点击选择
            row.addEventHandler(MouseEvent.MOUSE_CLICKED, ev -> pick(m));
            list.getChildren().add(row);
        }
    }

    /**
     * 选择成员并关闭弹窗。
     */
    private void pick(RoomMember member) {
        hide();
        onPick.accept(member);
    }
}
