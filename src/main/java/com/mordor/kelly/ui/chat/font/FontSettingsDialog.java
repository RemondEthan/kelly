package com.mordor.kelly.ui.chat.font;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * 字体设置对话框：让用户选择字体族和缩放等级。
 *
 * <h3>JavaFX Stage/Scene 模型</h3>
 * <ul>
 *   <li>{@link Stage} - 顶层窗口，相当于一个操作系统窗口</li>
 *   <li>{@link Scene} - Stage 内的场景，管理场景图根节点</li>
 *   <li>{@link Modality#APPLICATION_MODAL} - 模态窗口：打开时主窗口不可交互</li>
 * </ul>
 *
 * <h3>布局结构</h3>
 * <pre>
 *   VBox (root)
 *   ├── HBox: "字体：" + ComboBox（字体选择）
 *   ├── HBox: "字号：" + RadioButton × 4（小/中/大/特大）
 *   └── HBox: 应用 + 取消按钮
 * </pre>
 *
 * <p>工具类，不能实例化。</p>
 */
public final class FontSettingsDialog {

    private FontSettingsDialog() { }

    /**
     * 显示字体设置对话框（阻塞式）。
     *
     * @param owner    父窗口，对话框居中于其上
     * @param current  当前字体设置（用于预选）
     * @param chatRoot 聊天界面根节点，应用设置后立即生效
     * @param service  字体设置持久化服务
     */
    public static void show(Stage owner,
                            ChatFontSettings current,
                            Node chatRoot,
                            ChatFontSettingsService service) {
        // 创建新的 Stage 作为对话框
        Stage dialog = new Stage();
        dialog.setTitle("字体设置");
        dialog.initOwner(owner);       // 绑定父窗口
        dialog.initModality(Modality.APPLICATION_MODAL);  // 模态：阻止与父窗口交互
        dialog.setResizable(false);

        // 获取系统所有可用字体族，填充到 ComboBox
        ObservableList<String> families = FXCollections.observableArrayList(Font.getFamilies());
        ComboBox<String> fontBox = new ComboBox<>(families);
        fontBox.setMaxWidth(Double.MAX_VALUE);
        // 设置初始选中值
        String initialFamily = current.fontFamily();
        if (initialFamily == null && !families.isEmpty()) {
            initialFamily = families.get(0);
        }
        if (initialFamily != null) {
            fontBox.setValue(initialFamily);
        }

        // 创建四个缩放等级的 RadioButton，放入同一 ToggleGroup（互斥选择）
        ToggleGroup group = new ToggleGroup();
        RadioButton small = radio("小", ChatFontScale.SMALL, group, current.scale());
        RadioButton medium = radio("中", ChatFontScale.MEDIUM, group, current.scale());
        RadioButton large = radio("大", ChatFontScale.LARGE, group, current.scale());
        RadioButton xlarge = radio("特大", ChatFontScale.XLARGE, group, current.scale());
        HBox sizeRow = new HBox(12, small, medium, large, xlarge);

        // 按钮行
        Button apply = new Button("应用");
        Button cancel = new Button("取消");
        HBox buttons = new HBox(8, apply, cancel);
        buttons.setPadding(new Insets(8, 0, 0, 0));

        // 根布局 VBox：垂直排列三行
        VBox root = new VBox(10,
                labelled("字体：", fontBox),
                labelled("字号：", sizeRow),
                buttons);
        root.setPadding(new Insets(14));

        // "应用"按钮：保存设置 → 应用到聊天界面 → 关闭对话框
        apply.setOnAction(e -> {
            String family = fontBox.getValue();
            ChatFontScale scale = (ChatFontScale) group.getSelectedToggle().getUserData();
            ChatFontSettings next = new ChatFontSettings(family, scale);
            service.save(next);            // 持久化
            ChatFontApplier.apply(chatRoot, next);  // 立即应用到 UI
            dialog.close();
        });
        cancel.setOnAction(e -> dialog.close());
        apply.setDefaultButton(true);  // 回车键触发应用

        // 创建 Scene 并设置 ESC 键关闭
        Scene scene = new Scene(root);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                dialog.close();
            }
        });
        dialog.setScene(scene);
        dialog.setWidth(420);
        dialog.setHeight(220);
        dialog.showAndWait();  // 阻塞等待用户操作
    }

    /**
     * 创建一个单选按钮并设置到 ToggleGroup 中。
     *
     * @param text    按钮文字
     * @param scale   对应的缩放等级
     * @param group   互斥选择组
     * @param current 当前选中的缩放等级（用于预选）
     * @return 配置好的 RadioButton
     */
    private static RadioButton radio(String text, ChatFontScale scale, ToggleGroup group, ChatFontScale current) {
        RadioButton r = new RadioButton(text);
        r.setToggleGroup(group);
        r.setUserData(scale);  // 用 userData 存储关联的枚举值
        if (scale == current) {
            r.setSelected(true);
        }
        return r;
    }

    /**
     * 创建带标签的表单行：标签 + 表单控件水平排列。
     *
     * @param text  标签文字
     * @param field 表单控件
     * @return HBox 布局行
     */
    private static HBox labelled(String text, Node field) {
        Label label = new Label(text);
        HBox row = new HBox(8, label, field);
        label.setMinWidth(40);
        HBox.setHgrow(field, javafx.scene.layout.Priority.ALWAYS);  // 控件填充剩余空间
        return row;
    }
}
