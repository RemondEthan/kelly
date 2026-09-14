package com.mordor.kelly.ui.chat;

import com.mordor.kelly.model.AppState;
import com.mordor.kelly.ui.chat.font.ChatFontSettingsService;
import com.mordor.kelly.ui.chat.font.FontSettingsDialog;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;

/**
 * 聊天界面顶部标题栏。
 *
 * <h3>布局结构（HBox 水平排列）</h3>
 * <pre>
 *   [用户名 🟢] --- [spacer 弹性空间] --- [知识库] [用户A ↔ 用户B] [字体图标]
 * </pre>
 *
 * <h3>组件说明</h3>
 * <ul>
 *   <li><b>标题</b> - 显示当前用户名 + 在线状态图标（🟢/🔴），使用 Twemoji PNG 渲染</li>
 *   <li><b>知识库按钮</b> - 仅在 Kelsy 助手启用时可见，点击切换知识库面板</li>
 *   <li><b>连接信息</b> - 显示 "用户A ↔ 用户B"，通过属性绑定实时更新</li>
 *   <li><b>字体按钮</b> - 点击打开字体设置对话框</li>
 * </ul>
 *
 * <h3>JavaFX 属性绑定</h3>
 * <p>{@code visibleProperty().bind(...)} 将按钮可见性与控制器的 kelsyEnabled 属性绑定，
 * Kelsy 启用/禁用时按钮自动显示/隐藏。{@code Bindings.createStringBinding} 创建
 * 派生字符串绑定，当 peerDisplay 属性变化时自动重新计算显示文本。</p>
 */
public class ChatHeader extends HBox {

    /**
     * 构造聊天顶部标题栏。
     *
     * @param state       应用状态（用户名、在线状态等）
     * @param controller  聊天控制器
     * @param chatRoot    聊天根节点（用于字体设置对话框）
     * @param fontService 字体设置持久化服务
     */
    public ChatHeader(AppState state, ChatController controller, Node chatRoot, ChatFontSettingsService fontService) {
        super();
        getStyleClass().add("header");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(10, 12, 10, 12));
        setSpacing(8);

        // 用户名标题 + 在线状态图标
        Label title = new Label();
        title.getStyleClass().add("header-title");
        title.setText(state.username());
        title.setGraphicTextGap(6);
        // 使用 EmojiImages 渲染状态图标（Windows 不支持 Label 内彩色 emoji）
        title.setGraphic(EmojiImages.view("🟢", 14));
        // 监听在线状态属性，状态变化时切换图标
        state.onlineProperty().addListener((obs, oldVal, online) ->
                title.setGraphic(EmojiImages.view(online ? "🟢" : "🔴", 14)));

        // 弹性空间：占满标题栏剩余宽度，将右侧组件推到右边
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 知识库按钮：仅在 Kelsy 启用时可见
        Button knowledge = new Button("知识库");
        knowledge.getStyleClass().add("header-button");
        // visible 和 managed 双绑定：隐藏时同时不占布局空间
        knowledge.visibleProperty().bind(controller.kelsyEnabledProperty());
        knowledge.managedProperty().bind(controller.kelsyEnabledProperty());
        knowledge.setOnAction(e -> controller.knowledgeVisibleProperty().set(
                !controller.knowledgeVisibleProperty().get()));

        // 连接信息：显示 "自己 ↔ 对方"
        Label meta = new Label();
        meta.getStyleClass().add("header-meta");
        // createStringBinding：依赖 peerDisplay 属性，变化时重新计算
        meta.textProperty().bind(Bindings.createStringBinding(
                () -> state.username() + " ↔ " + state.peerDisplayProperty().get(),
                state.peerDisplayProperty()));

        getChildren().addAll(title, spacer, knowledge, meta);

        // 字体设置按钮（使用 ikonli 图标库）
        FontIcon fontButton = new FontIcon(MaterialDesignF.FORMAT_FONT);
        fontButton.getStyleClass().add("header-font-button");
        fontButton.setCursor(javafx.scene.Cursor.HAND);
        fontButton.setOnMouseClicked(e ->
                FontSettingsDialog.show(
                        getScene() == null ? null : (Stage) getScene().getWindow(),
                        fontService.load(),
                        chatRoot,
                        fontService));
        getChildren().add(fontButton);
    }
}
