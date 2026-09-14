package com.mordor.kelly.ui.login;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

/**
 * 密码可见性切换控件：PasswordField + TextField 叠放，通过眼睛按钮切换掩码/明文。
 *
 * <h3>实现原理</h3>
 * <p>{@link PasswordField} 和 {@link TextField} 通过 {@link StackPane} 叠放，
 * 任意时刻只有一个可见。切换时在两个字段间同步文本内容。</p>
 *
 * <h3>对外接口</h3>
 * <p>{@link #getValue()} 永远返回当前可见字段的文本——
 * 外部调用者（LoginController 校验/落盘）不需要关心当前是掩码还是明文状态。</p>
 *
 * <h3>UI 状态</h3>
 * <ul>
 *   <li>掩码模式（默认）：PasswordField 可见，眼睛图标 👁</li>
 *   <li>明文模式：TextField 可见，眼睛图标 🙈</li>
 * </ul>
 */
public class PasswordVisibilityField extends StackPane {

    /** 眼睛打开图标（掩码模式） */
    private static final String EYE_OPEN = "👁";
    /** 眼睛关闭图标（明文模式） */
    private static final String EYE_CLOSED = "🙈";

    /** 密码掩码字段（显示圆点） */
    private final PasswordField masked = new PasswordField();
    /** 明文显示字段 */
    private final TextField visible = new TextField();
    /** 可见性切换按钮 */
    private final Button eye = new Button(EYE_OPEN);
    /** 当前是否为明文模式 */
    private boolean showingVisible;

    public PasswordVisibilityField() {
        getStyleClass().add("login-password-stack");

        visible.setVisible(false);
        visible.setManaged(false);
        masked.setVisible(true);
        masked.setManaged(true);

        eye.getStyleClass().addAll("login-eye-button", "login-eye-shown");
        eye.setFocusTraversable(false);
        eye.setCursor(javafx.scene.Cursor.HAND);
        eye.setOnAction(e -> toggle());

        StackPane.setAlignment(eye, Pos.CENTER_RIGHT);
        StackPane.setMargin(eye, new javafx.geometry.Insets(0, 6, 0, 0));

        getChildren().addAll(masked, visible, eye);

        // placeholder 在两个字段间同步
        masked.promptTextProperty().addListener((obs, o, n) -> {
            if (visible.getPromptText() == null || visible.getPromptText().isEmpty()) {
                visible.setPromptText(n);
            }
        });
        visible.promptTextProperty().addListener((obs, o, n) -> masked.setPromptText(n));

        // Cascade disable state to all child nodes
        disabledProperty().addListener((obs, wasDisabled, nowDisabled) -> {
            masked.setDisable(nowDisabled);
            visible.setDisable(nowDisabled);
            eye.setDisable(nowDisabled);
        });
    }

    public String getValue() {
        return showingVisible ? visible.getText() : masked.getText();
    }

    public void setPromptText(String text) {
        masked.setPromptText(text);
        visible.setPromptText(text);
    }

    private void toggle() {
        if (showingVisible) {
            String s = visible.getText();
            masked.setText(s);
            masked.positionCaret(s.length());
            visible.setVisible(false);
            visible.setManaged(false);
            masked.setVisible(true);
            masked.setManaged(true);
            eye.setText(EYE_OPEN);
            eye.getStyleClass().remove("login-eye-hidden");
            eye.getStyleClass().add("login-eye-shown");
            showingVisible = false;
        } else {
            String s = masked.getText();
            visible.setText(s);
            visible.positionCaret(s.length());
            masked.setVisible(false);
            masked.setManaged(false);
            visible.setVisible(true);
            visible.setManaged(true);
            eye.setText(EYE_CLOSED);
            eye.getStyleClass().remove("login-eye-shown");
            eye.getStyleClass().add("login-eye-hidden");
            showingVisible = true;
        }
    }
}
