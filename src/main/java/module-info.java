module com.mordor.kelly {
    requires javafx.controls;
    requires com.fasterxml.jackson.databind;
    requires javafx.fxml;
    requires javafx.web;
    requires java.desktop;   // 用于 AWT SystemTray（托盘图标）
    requires java.prefs;     // Preferences API，用于持久化上次登录配置
    requires java.net.http;  // java.net.http.WebSocket
    requires org.commonmark;
    requires org.commonmark.ext.gfm.tables;

    requires agentscope.core;
    requires agentscope.harness;
    requires agentscope.extensions.model.openai;
    requires reactor.core;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.materialdesign2;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires com.almasb.fxgl.all;

    requires com.sun.jna;
    requires com.sun.jna.platform;

    exports com.mordor.kelly.app;
    exports com.mordor.kelly.ui.login;
    exports com.mordor.kelly.ui.chat;
    exports com.mordor.kelly.model;
    exports com.mordor.kelly.service;
    exports com.mordor.kelly.common;

    opens com.mordor.kelly.app      to javafx.fxml;
    opens com.mordor.kelly.ui.login to javafx.fxml;
    opens com.mordor.kelly.ui.chat  to javafx.fxml;
    opens com.mordor.kelly.model    to javafx.fxml;
    opens com.mordor.kelly.service  to javafx.fxml;
    opens com.mordor.kelly.common   to javafx.fxml;
    opens com.mordor.kelly.kelsy.config to com.fasterxml.jackson.databind;
    opens com.mordor.kelly.kelsy.provider to com.fasterxml.jackson.databind;
}
