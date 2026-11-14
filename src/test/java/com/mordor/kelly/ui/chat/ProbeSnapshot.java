package com.mordor.kelly.ui.chat;

import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.Sender;
import com.mordor.kelly.ui.theme.AppTheme;
import com.mordor.kelly.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.embed.swing.SwingFXUtils;

import javax.imageio.ImageIO;
import java.io.File;
import java.time.LocalDateTime;

/** 临时：渲染聊天气泡，导出亮/深色两张截图。 */
public class ProbeSnapshot {

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
        });
        for (AppTheme theme : new AppTheme[]{AppTheme.LIGHT, AppTheme.DARK}) {
            VBox box = new VBox(8);
            box.getStyleClass().add("chat-root");
            box.getStylesheets().add(ChatPane.class.getResource("chat.css").toExternalForm());
            ThemeService.apply(box, theme);

            var width = new SimpleDoubleProperty(360);
            box.getChildren().addAll(
                    bubble(Sender.SYSTEM, "已加入房间，等待对方连接", width),
                    bubble(Sender.SYSTEM, "已与 alice 连接", width),
                    bubble(Sender.SELF, "你好，我是本地消息", width),
                    bubble(Sender.PEER, "你好，我是对方消息", width));

            Scene scene = new Scene(box, 420, 400);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            box.applyCss();
            box.layout();
            scene.getRoot().applyCss();
            scene.getRoot().layout();

            javafx.scene.image.WritableImage img = scene.getRoot().snapshot(null, null);
            File out = new File("/tmp/kelly-" + theme.name().toLowerCase() + ".png");
            javax.imageio.ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
            System.out.println("wrote " + out);

            for (javafx.scene.Node n : box.getChildren()) {
                dump(n, theme.name());
            }
            stage.close();
        }
        Platform.exit();
    }

    private static void dump(javafx.scene.Node n, String tag) {
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
                dump(c, tag);
            }
        } else if (n instanceof Text t) {
            Object fill = t.getFill();
            Color c = fill instanceof Color ? (Color) fill : null;
            System.out.println(tag + " text='" + t.getText() + "' classes=" + t.getStyleClass()
                    + " parentClasses=" + (t.getParent() == null ? "-" : t.getParent().getStyleClass())
                    + " fill=" + c);
        }
    }

    private static MessageBubble bubble(Sender sender, String content, SimpleDoubleProperty width) {
        return new MessageBubble(
                new Message("id-" + content.hashCode(), sender, content, LocalDateTime.now()),
                "我", "对方", null, null, width);
    }
}
