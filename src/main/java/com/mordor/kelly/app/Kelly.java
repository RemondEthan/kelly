package com.mordor.kelly.app;

import com.mordor.kelly.common.Diagnostics;
import com.mordor.kelly.kelsy.KelsyRuntime;
import com.mordor.kelly.model.AppState;
import com.mordor.kelly.service.ImClient;
import com.mordor.kelly.ui.chat.ChatPane;
import com.mordor.kelly.ui.login.LoginPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class Kelly extends Application {

    private static final double WIDTH = 720;
    private static final double HEIGHT = 520;

    private Stage stage;
    private StackPane root;
    private ImClient session;
    private UnreadAlert unreadAlert;
    private ChatPane chatPane;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.root = new StackPane();
        root.getStyleClass().add("app-bg");
        showLogin();

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets().add(
                Kelly.class.getResource("app.css").toExternalForm());

        stage.setTitle("kelly");
        AppIcons.applyStage(stage, "/icons/kelly.png");
        stage.setScene(scene);
        stage.setMinWidth(560);
        stage.setMinHeight(360);
        Platform.setImplicitExit(false);

        if (!SingleInstance.claim(() -> FxStageSupport.show(stage))) {
            Platform.exit();
            return;
        }

        stage.show();
        Diagnostics.startFxWatchdog();

        TrayManager trayManager = TrayManager.install(stage);
        AppIcons.applyTaskbar(AppIcons.awtImage("/icons/kelly.png"));
        unreadAlert = UnreadAlert.install(stage, trayManager);
        QuitManager quitManager = new QuitManager(trayManager, this::closeSession);

        trayManager.setOnQuit(quitManager::quit);
        ShortcutRegistrar.register(scene, () -> FxStageSupport.minimize(stage), quitManager::quit);
        // 红点：藏窗口。⌘W：最小化。会话保持，点 Dock 还原聊天窗。
        stage.setOnCloseRequest(e -> {
            e.consume();
            FxStageSupport.hide(stage);
        });
        OsQuitHandlers.install(quitManager::quit, () -> FxStageSupport.show(stage));
    }

    private void showLogin() {
        stage.setTitle("kelly");
        root.getChildren().setAll(new LoginPane(this::enterChat));
    }

    private void enterChat(AppState state) {
        closeSession();
        if (state.client() != null) {
            session = state.client();
            unreadAlert.watch(session);
        }
        stage.setTitle(state.username());
        chatPane = new ChatPane(state);
        root.getChildren().setAll(chatPane);
    }

    private void closeSession() {
        if (chatPane != null) {
            chatPane.close();
            chatPane = null;
        }
        if (unreadAlert != null) {
            unreadAlert.clear();
        }
        ImClient client = session;
        session = null;
        if (client != null) {
            client.close();
        }
        KelsyRuntime.shutdown();
    }

    public static void main(String[] args) {
        AwtSupport.preinit();
        launch();
    }
}
