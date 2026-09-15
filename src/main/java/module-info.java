/**
 * Kelly 桌面聊天应用的模块描述符。
 *
 * <p>模块名：com.mordor.kelly
 *
 * <h2>整体架构</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────┐
 * │                    Kelly 模块                            │
 * │                                                         │
 * │  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐  │
 * │  │   app    │  │   ui     │  │       kelsy          │  │
 * │  │ 入口/托盘 │  │ 登录/聊天 │  │  AI 助手子系统        │  │
 * │  └────┬─────┘  └────┬─────┘  └──────────┬───────────┘  │
 * │       │              │                   │              │
 * │  ┌────┴──────────────┴───────────────────┴───────────┐  │
 * │  │              service (网络/加密/存储)               │  │
 * │  └────────────────────┬──────────────────────────────┘  │
 * │                       │                                 │
 * │  ┌────────────────────┴──────────────────────────────┐  │
 * │  │         model (数据模型) + common (工具)           │  │
 * │  └───────────────────────────────────────────────────┘  │
 * └─────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>依赖分类</h2>
 * <ul>
 *   <li><b>JavaFX</b>：桌面 UI 框架（controls/fxml/web/swing/media）</li>
 *   <li><b>AI/Agent</b>：AgentScope 框架 + Reactor 响应式编程</li>
 *   <li><b>UI 增强</b>：ControlsFX/BootstrapFX/Ikonli/TilesFX/FXGL</li>
 *   <li><b>序列化</b>：Jackson（JSON 配置文件）</li>
 *   <li><b>Markdown</b>：CommonMark（AI 回复渲染）</li>
 *   <li><b>原生调用</b>：JNA（Windows 任务栏闪烁等）</li>
 *   <li><b>JDK 标准</b>：java.desktop(AWT)/java.prefs/java.net.http(WebSocket)</li>
 * </ul>
 */
module com.mordor.kelly {

    // ─── JavaFX 核心 ─────────────────────────────────────────
    requires javafx.controls;   // JavaFX 基础控件（Button, TextField, VBox 等）
    requires javafx.fxml;       // FXML 加载支持（虽然本项目 UI 主要代码构建，但保留 FXML 能力）
    requires javafx.web;        // WebView 组件（用于渲染 HTML 内容，如 Markdown 预览）

    // ─── JDK 标准库 ──────────────────────────────────────────
    requires java.desktop;      // AWT/Swing：SystemTray 托盘图标、Taskbar 任务栏、Desktop 事件
    requires java.prefs;        // Java Preferences API：持久化登录配置、字体设置等用户偏好
    requires java.net.http;     // JDK 11+ HTTP 客户端：WebSocket 通信（ImClient 使用）

    // ─── 序列化 ──────────────────────────────────────────────
    requires com.fasterxml.jackson.databind;  // Jackson JSON：解析 kelsy 配置文件（providers.json 等）

    // ─── Markdown 渲染 ──────────────────────────────────────
    requires org.commonmark;                    // CommonMark 解析器：Markdown → AST
    requires org.commonmark.ext.gfm.tables;    // GitHub 风格表格扩展：支持 Markdown 表格语法

    // ─── AI Agent 框架 ──────────────────────────────────────
    requires agentscope.core;                   // AgentScope 核心：AI Agent 抽象、消息流、工具调用
    requires agentscope.harness;                // AgentScope 运行时：Agent 生命周期管理
    requires agentscope.extensions.model.openai; // OpenAI 兼容模型：GPT/Claude/本地模型统一接口
    requires reactor.core;                      // Project Reactor：响应式流，AgentScope 的流式响应底层
    requires org.xerial.sqlitejdbc;         // 嵌入式 SQLite：知识库 FTS 索引

    // ─── UI 增强库 ──────────────────────────────────────────
    requires org.controlsfx.controls;       // ControlsFX：高级控件（Popover, Toast, ToggleSwitch 等）
    requires com.dlsc.formsfx;              // FormsFX：表单控件（登录表单）
    requires net.synedra.validatorfx;       // ValidatorFX：输入验证（登录字段校验）
    requires org.kordamp.ikonli.javafx;     // Ikonli：图标字体框架
    requires org.kordamp.ikonli.materialdesign2; // Material Design 2 图标集
    requires org.kordamp.bootstrapfx.core;  // BootstrapFX：Bootstrap 风格 CSS 主题
    requires eu.hansolo.tilesfx;            // TilesFX：仪表盘/统计卡片控件
    requires com.almasb.fxgl.all;           // FXGL：游戏引擎（可能用于动画效果）

    // ─── 原生调用 ──────────────────────────────────────────
    requires com.sun.jna;           // JNA 核心：Java 原生接口（不写 C 代码调用系统 API）
    requires com.sun.jna.platform;  // JNA 平台扩展：User32.dll 封装（Windows 任务栏闪烁）

    // ─── 导出包（供其他模块或反射访问）────────────────────
    exports com.mordor.kelly.app;       // 应用入口、托盘、快捷键、退出管理
    exports com.mordor.kelly.ui.login;  // 登录界面
    exports com.mordor.kelly.ui.chat;   // 聊天界面
    exports com.mordor.kelly.model;     // 数据模型（Message, AppState 等）
    exports com.mordor.kelly.service;   // 服务层（ImClient, CryptoService 等）
    exports com.mordor.kelly.common;    // 通用工具（Diagnostics 日志）

    // ─── opens（允许反射访问，FXML 和 Jackson 需要）─────────
    opens com.mordor.kelly.app           to javafx.fxml;  // FXML 反射实例化 Controller
    opens com.mordor.kelly.ui.login      to javafx.fxml;
    opens com.mordor.kelly.ui.chat       to javafx.fxml;
    opens com.mordor.kelly.model         to javafx.fxml;
    opens com.mordor.kelly.service       to javafx.fxml;
    opens com.mordor.kelly.common        to javafx.fxml;
    opens com.mordor.kelly.kelsy.config  to com.fasterxml.jackson.databind;  // Jackson 反射反序列化配置
    opens com.mordor.kelly.kelsy.provider to com.fasterxml.jackson.databind; // Jackson 反射反序列化提供者
}
