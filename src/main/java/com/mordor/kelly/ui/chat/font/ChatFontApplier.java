package com.mordor.kelly.ui.chat.font;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.text.Text;

/**
 * 聊天字体应用器：遍历 JavaFX 场景图节点树，对所有文本节点应用字体设置。
 *
 * <h3>工作原理</h3>
 * <p>JavaFX 的 UI 由<strong>场景图（Scene Graph）</strong>构成——所有可见元素都是 {@link Node}，
 * 节点之间构成树形父子关系。本类通过递归遍历这棵树，找到所有包含文本的节点并设置字体。</p>
 *
 * <h3>支持的节点类型</h3>
 * <ul>
 *   <li>{@link Labeled} - 带文字标签的控件（Button、Label 等）</li>
 *   <li>{@link TextInputControl} - 文本输入控件（TextField、PasswordField 等）</li>
 *   <li>{@link Text} - 纯文本节点（TextFlow 的子节点）</li>
 * </ul>
 *
 * <h3>字体族 vs 字号</h3>
 * <ul>
 *   <li>字体族（font-family）通过 CSS 样式设在根节点上，子节点继承</li>
 *   <li>字号（font-size）需要逐个节点设置，因为各节点原始字号不同，缩放是基于原始值的</li>
 * </ul>
 *
 * <p>工具类，不能实例化。</p>
 */
public final class ChatFontApplier {

    private ChatFontApplier() { }

    /**
     * 将字体设置应用到以 root 为根的整个节点子树。
     *
     * @param root 场景图的根节点（如 ChatPane）
     * @param s    字体设置
     */
    public static void apply(Node root, ChatFontSettings s) {
        if (root == null || s == null) {
            return;
        }
        // 字体族设在根节点，子节点通过 CSS 继承自动生效
        if (s.fontFamily() != null) {
            String escaped = s.fontFamily().replace("'", "''");
            root.setStyle("-fx-font-family: '" + escaped + "'");
        }
        // 递归遍历并缩放所有文本节点的字号
        walk(root, s.scale().factor());
    }

    /**
     * 递归遍历节点树，对文本类节点设置缩放后的字号。
     *
     * <p>遍历策略：先处理当前节点，再递归处理所有子节点。
     * 单个节点处理失败不影响其他节点（容错设计）。</p>
     *
     * @param node   当前遍历的节点
     * @param factor 缩放因子（来自 {@link ChatFontScale}）
     */
    private static void walk(Node node, double factor) {
        try {
            if (node instanceof Labeled l && l.getFont() != null) {
                // Labeled（Button、Label 等）：获取原始字号并缩放
                double base = l.getFont().getSize();
                if (base > 0) {
                    l.setStyle(formatFontSize(base * factor));
                }
            } else if (node instanceof TextInputControl t && t.getFont() != null) {
                // TextInputControl（TextField、TextArea 等）
                double base = t.getFont().getSize();
                if (base > 0) {
                    t.setStyle(formatFontSize(base * factor));
                }
            } else if (node instanceof Text tx && tx.getFont() != null) {
                // Text 纯文本节点（TextFlow 中的文字片段）
                double base = tx.getFont().getSize();
                if (base > 0) {
                    tx.setStyle(formatFontSize(base * factor));
                }
            }
        } catch (Exception ignore) {
            // 单节点失败不能中断遍历，忽略继续
        }
        // 递归：如果是容器节点，遍历其所有子节点
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                walk(child, factor);
            }
        }
    }

    /**
     * 将缩放后的字号格式化为 CSS 内联样式字符串。
     *
     * @param size 缩放后的字号（px）
     * @return CSS 样式字符串，如 "-fx-font-size: 14px;"
     */
    private static String formatFontSize(double size) {
        long rounded = Math.round(size);
        return "-fx-font-size: " + rounded + "px;";
    }
}