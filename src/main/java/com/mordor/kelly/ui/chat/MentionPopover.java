package com.mordor.kelly.ui.chat;

import com.mordor.kelly.model.RoomMember;
import com.mordor.kelly.ui.AvatarView;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public final class MentionPopover {

    private static final int MAX_VISIBLE = 6;
    private static final double ROW_H = 40;

    private final Popup popup = new Popup();
    private final VBox list = new VBox();
    private final ScrollPane scroller = new ScrollPane(list);
    private final BiFunction<RoomMember, String, Image> avatarOf;
    private final Consumer<RoomMember> onPick;

    private final List<RoomMember> items = new ArrayList<>();
    private int active;

    public MentionPopover(BiFunction<RoomMember, String, Image> avatarOf,
                          Consumer<RoomMember> onPick) {
        this.avatarOf = avatarOf;
        this.onPick = onPick;
        list.getStyleClass().add("mention-popup");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setMaxHeight(MAX_VISIBLE * ROW_H);
        scroller.setPrefWidth(220);
        String css = MentionPopover.class.getResource("chat.css").toExternalForm();
        scroller.getStylesheets().add(css);
        popup.getContent().add(scroller);
        popup.setAutoHide(true);
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    public void hide() {
        popup.hide();
        items.clear();
    }

    public void show(Node anchor, List<RoomMember> candidates) {
        items.clear();
        if (candidates == null || candidates.isEmpty()) {
            hide();
            return;
        }
        items.addAll(candidates);
        active = 0;
        rebuild();
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        if (b == null) {
            return;
        }
        scroller.applyCss();
        scroller.autosize();
        double h = Math.min(items.size(), MAX_VISIBLE) * ROW_H + 8;
        popup.show(anchor, b.getMinX(), b.getMinY() - h);
    }

    public boolean handleKey(KeyEvent e) {
        if (!popup.isShowing() || items.isEmpty()) {
            return false;
        }
        if (e.getCode() == KeyCode.UP) {
            active = (active - 1 + items.size()) % items.size();
            rebuild();
            e.consume();
            return true;
        }
        if (e.getCode() == KeyCode.DOWN) {
            active = (active + 1) % items.size();
            rebuild();
            e.consume();
            return true;
        }
        if (e.getCode() == KeyCode.ENTER) {
            pick(items.get(active));
            e.consume();
            return true;
        }
        if (e.getCode() == KeyCode.ESCAPE) {
            hide();
            e.consume();
            return true;
        }
        return false;
    }

    private void rebuild() {
        list.getChildren().clear();
        for (int i = 0; i < items.size(); i++) {
            RoomMember m = items.get(i);
            String name = m.username() == null || m.username().isBlank() ? "?" : m.username();
            Image photo = avatarOf.apply(m, name);
            HBox row = new HBox(8);
            row.getStyleClass().add("mention-row");
            if (i == active) {
                row.getStyleClass().add("mention-row-active");
            }
            row.getChildren().add(new AvatarView(name, photo, false, 28));
            Label label = new Label(name);
            label.getStyleClass().add("mention-name");
            row.getChildren().add(label);
            final int index = i;
            row.setOnMouseEntered(ev -> {
                active = index;
                rebuild();
            });
            row.addEventHandler(MouseEvent.MOUSE_CLICKED, ev -> pick(m));
            list.getChildren().add(row);
        }
    }

    private void pick(RoomMember member) {
        hide();
        onPick.accept(member);
    }
}
