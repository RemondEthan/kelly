package com.mordor.kelly.ui.chat;

import com.mordor.kelly.model.AppState;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

public class ChatHeader extends HBox {

    public ChatHeader(AppState state, ChatController controller) {
        super();
        getStyleClass().add("header");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(10, 12, 10, 12));
        setSpacing(8);

        Label title = new Label();
        title.getStyleClass().add("header-title");
        // Windows 上 Label 无法渲染彩色 emoji,把指示点单独作为 ImageView 渲染
        // (与项目内 EmojiImages 渲染策略一致：emoji 一律走 Twemoji PNG)
        title.setText(state.username());
        title.setGraphicTextGap(6);
        title.setGraphic(EmojiImages.view("🟢", 14));
        state.onlineProperty().addListener((obs, oldVal, online) ->
                title.setGraphic(EmojiImages.view(online ? "🟢" : "🔴", 14)));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button knowledge = new Button("知识库");
        knowledge.getStyleClass().add("header-button");
        knowledge.visibleProperty().bind(controller.kelsyEnabledProperty());
        knowledge.managedProperty().bind(controller.kelsyEnabledProperty());
        knowledge.setOnAction(e -> controller.knowledgeVisibleProperty().set(
                !controller.knowledgeVisibleProperty().get()));

        Label meta = new Label();
        meta.getStyleClass().add("header-meta");
        meta.textProperty().bind(Bindings.createStringBinding(
                () -> state.username() + " ↔ " + state.peerDisplayProperty().get(),
                state.peerDisplayProperty()));

        getChildren().addAll(title, spacer, knowledge, meta);
    }
}
