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
import javafx.scene.text.Font;
import javafx.stage.Stage;

/**
 * Kelly 聊天应用的主入口类，继承自 JavaFX Application。
 *
 * <p>JavaFX 应用程序的生命周期由 {@link #start(Stage)} 方法驱动，
 * JavaFX 运行时会自动创建主 Stage（窗口）并传入。
 *
 * <p>核心架构：
 * <ul>
 *   <li>根容器使用 {@link StackPane}，通过 {@code getChildren().setAll()} 切换
 *       {@link LoginPane}（登录页）和 {@link ChatPane}（聊天页），实现页面跳转</li>
 *   <li>{@link SingleInstance} 保证同一时间只有一个实例运行，第二个启动的进程会唤醒已有窗口</li>
 *   <li>{@link TrayManager} 将应用最小化到系统托盘，支持 macOS / Windows / Linux</li>
 *   <li>{@link UnreadAlert} 监听未读消息，在托盘图标、窗口图标、任务栏图标上闪烁提醒</li>
 * </ul>
 *
 * <p>窗口关闭行为：
 * <ul>
 *   <li>{@code Platform.setImplicitExit(false)} 阻止关闭最后一个窗口时 JVM 自动退出</li>
 *   <li>{@code stage.setOnCloseRequest} 中消费关闭事件，只隐藏窗口不退出应用</li>
 *   <li>用户通过托盘右键菜单或快捷键 Cmd/Ctrl+Q 才能真正退出</li>
 * </ul>
 */
public class Kelly extends Application {

    /** 窗口默认宽度（像素） */
    private static final double WIDTH = 720;
    /** 窗口默认高度（像素） */
    private static final double HEIGHT = 520;

    /** JavaFX 主窗口，应用生命周期内只有一个实例 */
    private Stage stage;
    /** 根容器，用于在登录页和聊天页之间切换 */
    private StackPane root;
    /** 当前的即时通讯客户端连接，登录成功后创建，退出时关闭 */
    private ImClient session;
    /** 未读消息提醒管理器，监听消息并触发图标闪烁 */
    private UnreadAlert unreadAlert;
    /** 当前的聊天面板，登出时需要手动关闭 */
    private ChatPane chatPane;

    /**
     * JavaFX 应用程序的生命周期入口方法。
     *
     * <p>当应用程序启动时，JavaFX 运行时会调用此方法，
     * 传入主窗口 Stage。此方法负责初始化所有 UI 组件和系统级功能。
     *
     * @param stage JavaFX 运行时创建的主窗口
     */
    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.root = new StackPane();
        root.getStyleClass().add("app-bg");

        // 预加载 Ikonli Material Design 字体，避免 Windows 上字体加载失败导致图标乱码
        Font.loadFont(getClass().getResourceAsStream(
                "/META-INF/resources/materialdesignicons2/5.8.55/fonts/materialdesignicons-webfont.ttf"), 0);

        showLogin();

        // 创建场景，设置固定尺寸，加载全局样式表
        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets().add(
                Kelly.class.getResource("app.css").toExternalForm());

        stage.setTitle("kelly");
        // 设置窗口标题栏图标（多尺寸，适配 macOS Retina 和 Windows 任务栏）
        AppIcons.applyStage(stage, "/icons/kelly.png");
        stage.setScene(scene);
        stage.setMinWidth(560);
        stage.setMinHeight(360);
        // 阻止关闭最后一个窗口时 JVM 自动退出——本应用要靠系统托盘常驻后台
        Platform.setImplicitExit(false);

        // 单实例检查：如果端口已被占用，说明已有实例在运行
        // 向已有实例发送唤醒信号，本进程退出
        if (!SingleInstance.claim(() -> FxStageSupport.show(stage))) {
            Platform.exit();
            return;
        }

        stage.show();
        // 启动 JavaFX 线程监控，检测 UI 线程是否卡死
        Diagnostics.startFxWatchdog();

        // 初始化系统托盘（AWT），图标加载和菜单创建在 AWT 线程完成
        TrayManager trayManager = TrayManager.install(stage);
        // 设置 Windows 任务栏图标
        AppIcons.applyTaskbar(AppIcons.awtImage("/icons/kelly.png"));
        // 初始化未读消息提醒（监听窗口焦点、最小化状态，触发图标闪烁）
        unreadAlert = UnreadAlert.install(stage, trayManager);
        // 退出管理器：负责先关闭会话再强制终止 JVM 进程
        QuitManager quitManager = new QuitManager(trayManager, this::closeSession);

        // 托盘右键菜单的"退出"按钮触发退出流程
        trayManager.setOnQuit(quitManager::quit);
        // 注册快捷键：Cmd+W / Ctrl+W 最小化，Cmd+Q / Ctrl+Q 退出
        ShortcutRegistrar.register(scene, () -> FxStageSupport.minimize(stage), quitManager::quit);
        // 点击窗口关闭按钮时：消费事件，只隐藏窗口不退出（红点功能依赖窗口隐藏）
        // Cmd+W 走快捷键最小化；会话保持连接，点 Dock 图标可恢复聊天窗口
        stage.setOnCloseRequest(e -> {
            e.consume();
            FxStageSupport.hide(stage);
        });
        // 注册 macOS 系统级退出事件（Glass 层 ⌘Q、Desktop APP_QUIT_HANDLER）和 Dock 点击事件
        OsQuitHandlers.install(quitManager::quit, () -> FxStageSupport.show(stage));
    }

    /**
     * 显示登录页面。
     * 重置窗口标题，将根容器内容替换为 {@link LoginPane}。
     * LoginPane 的回调 {@code enterChat} 在登录成功后被调用。
     */
    private void showLogin() {
        stage.setTitle("kelly");
        root.getChildren().setAll(new LoginPane(this::enterChat));
    }

    /**
     * 登录成功后切换到聊天页面。
     *
     * <p>流程：
     * <ol>
     *   <li>先关闭之前的会话（如果有）</li>
     *   <li>保存新的客户端连接，注册未读消息监听</li>
     *   <li>更新窗口标题为用户名</li>
     *   <li>创建 {@link ChatPane} 并替换根容器内容</li>
     * </ol>
     *
     * @param state 登录后的应用状态，包含用户名和 IM 客户端连接
     */
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

    /**
     * 关闭当前会话，清理所有资源。
     *
     * <p>按顺序执行：
     * <ol>
     *   <li>关闭聊天面板 UI</li>
     *   <li>清除未读消息提醒</li>
     *   <li>关闭 IM 客户端网络连接</li>
     *   <li>关闭 Kelsy 运行时引擎</li>
     * </ol>
     *
     * <p>此方法在以下场景调用：
     * <ul>
     *   <li>切换用户重新登录时</li>
     *   <li>退出应用时（通过 QuitManager.beforeHalt）</li>
     * </ul>
     */
    private void closeSession() {
        if (chatPane != null) {
            chatPane.close();
            chatPane = null;
        }
        if (unreadAlert != null) {
            unreadAlert.clear();
        }
        // 先取走引用再关闭，避免并发问题
        ImClient client = session;
        session = null;
        if (client != null) {
            client.close();
        }
        KelsyRuntime.shutdown();
    }

    /**
     * 应用程序入口。
     *
     * <p>先调用 {@link AwtSupport#preinit()} 在独立线程中预初始化 AWT Toolkit，
     * 避免后续 AWT 操作在 macOS 上与 JavaFX 线程死锁。
     * 然后调用 {@link #launch()} 启动 JavaFX 运行时。
     *
     * @param args 命令行参数（未使用）
     */
    public static void main(String[] args) {
        AwtSupport.preinit();
        launch();
    }
}
