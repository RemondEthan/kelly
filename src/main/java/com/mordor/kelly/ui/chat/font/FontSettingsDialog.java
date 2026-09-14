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

public final class FontSettingsDialog {

    private FontSettingsDialog() { }

    public static void show(Stage owner,
                            ChatFontSettings current,
                            Node chatRoot,
                            ChatFontSettingsService service) {
        Stage dialog = new Stage();
        dialog.setTitle("字体设置");
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setResizable(false);

        ObservableList<String> families = FXCollections.observableArrayList(Font.getFamilies());
        ComboBox<String> fontBox = new ComboBox<>(families);
        fontBox.setMaxWidth(Double.MAX_VALUE);
        String initialFamily = current.fontFamily();
        if (initialFamily == null && !families.isEmpty()) {
            initialFamily = families.get(0);
        }
        if (initialFamily != null) {
            fontBox.setValue(initialFamily);
        }

        ToggleGroup group = new ToggleGroup();
        RadioButton small = radio("小", ChatFontScale.SMALL, group, current.scale());
        RadioButton medium = radio("中", ChatFontScale.MEDIUM, group, current.scale());
        RadioButton large = radio("大", ChatFontScale.LARGE, group, current.scale());
        RadioButton xlarge = radio("特大", ChatFontScale.XLARGE, group, current.scale());
        HBox sizeRow = new HBox(12, small, medium, large, xlarge);

        Button apply = new Button("应用");
        Button cancel = new Button("取消");
        HBox buttons = new HBox(8, apply, cancel);
        buttons.setPadding(new Insets(8, 0, 0, 0));

        VBox root = new VBox(10,
                labelled("字体：", fontBox),
                labelled("字号：", sizeRow),
                buttons);
        root.setPadding(new Insets(14));

        apply.setOnAction(e -> {
            String family = fontBox.getValue();
            ChatFontScale scale = (ChatFontScale) group.getSelectedToggle().getUserData();
            ChatFontSettings next = new ChatFontSettings(family, scale);
            service.save(next);
            ChatFontApplier.apply(chatRoot, next);
            dialog.close();
        });
        cancel.setOnAction(e -> dialog.close());
        apply.setDefaultButton(true);

        Scene scene = new Scene(root);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                dialog.close();
            }
        });
        dialog.setScene(scene);
        dialog.setWidth(420);
        dialog.setHeight(220);
        dialog.showAndWait();
    }

    private static RadioButton radio(String text, ChatFontScale scale, ToggleGroup group, ChatFontScale current) {
        RadioButton r = new RadioButton(text);
        r.setToggleGroup(group);
        r.setUserData(scale);
        if (scale == current) {
            r.setSelected(true);
        }
        return r;
    }

    private static HBox labelled(String text, Node field) {
        Label label = new Label(text);
        HBox row = new HBox(8, label, field);
        label.setMinWidth(40);
        HBox.setHgrow(field, javafx.scene.layout.Priority.ALWAYS);
        return row;
    }
}
