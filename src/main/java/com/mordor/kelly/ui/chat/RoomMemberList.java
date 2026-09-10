package com.mordor.kelly.ui.chat;

import com.mordor.kelly.kelsy.KelsyMention;
import com.mordor.kelly.model.AppState;
import com.mordor.kelly.model.RoomMember;
import com.mordor.kelly.service.AvatarService;
import com.mordor.kelly.service.SaveLastLoginService;
import com.mordor.kelly.ui.AvatarView;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.util.function.Consumer;

/**
 * 左侧成员列表：头像 + 小字名字，可收起成窄栏。点自己的头像可更换；点 tars 插入提及。
 */
public class RoomMemberList extends VBox {

    private static final double EXPANDED_MIN = 120;
    private static final double EXPANDED_PREF = 136;
    private static final double EXPANDED_MAX = 168;
    private static final double COLLAPSED_WIDTH = 52;

    private final ChatController controller;
    private final Consumer<String> onMention;
    private final VBox rows = new VBox(10);
    private final Label title = new Label("聊天室");
    private final Label count = new Label();
    private final Button addKelsyBtn = new Button("添加秘书");
    private final FontIcon toggleIcon = new FontIcon(MaterialDesignC.CHEVRON_LEFT);
    private final SaveLastLoginService saveService = new SaveLastLoginService();
    private boolean expanded = true;

    public RoomMemberList(ChatController controller, Consumer<String> onMention) {
        this.controller = controller;
        this.onMention = onMention;
        getStyleClass().add("member-list");
        setPadding(new Insets(10, 8, 10, 8));

        title.getStyleClass().add("member-list-title");
        count.getStyleClass().add("member-list-count");
        count.textProperty().bind(controller.humanCountProperty().asString("%d 人"));

        addKelsyBtn.getStyleClass().add("member-list-add-kelsy");
        addKelsyBtn.setFocusTraversable(false);
        addKelsyBtn.setOnAction(e -> pickKelsyAvatar());
        addKelsyBtn.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);

        toggleIcon.getStyleClass().add("member-list-toggle");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox heading = new HBox(4, title, count, addKelsyBtn, spacer, toggleIcon);
        heading.getStyleClass().add("member-list-heading");
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.setCursor(Cursor.HAND);
        heading.setOnMouseClicked(e -> setExpanded(!expanded));

        rows.setFillWidth(true);
        controller.getMembers().addListener((ListChangeListener<RoomMember>) c -> rebuild());
        controller.getState().avatarProperty().addListener((obs, o, n) -> rebuild());
        controller.peerAvatars().addListener((MapChangeListener<Integer, Image>) c -> rebuild());
        rebuild();

        ScrollPane scroll = new ScrollPane(rows);
        scroll.getStyleClass().add("member-list-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(heading, scroll);
        applyExpanded();
    }

    private void setExpanded(boolean value) {
        if (expanded == value) {
            return;
        }
        expanded = value;
        applyExpanded();
        rebuild();
    }

    private void applyExpanded() {
        getStyleClass().remove("member-list-collapsed");
        title.setVisible(expanded);
        title.setManaged(expanded);
        count.setVisible(expanded);
        count.setManaged(expanded);
        updateAddKelsyButton();
        if (expanded) {
            setMinWidth(EXPANDED_MIN);
            setPrefWidth(EXPANDED_PREF);
            setMaxWidth(EXPANDED_MAX);
            toggleIcon.setIconCode(MaterialDesignC.CHEVRON_LEFT);
        } else {
            getStyleClass().add("member-list-collapsed");
            setMinWidth(COLLAPSED_WIDTH);
            setPrefWidth(COLLAPSED_WIDTH);
            setMaxWidth(COLLAPSED_WIDTH);
            toggleIcon.setIconCode(MaterialDesignC.CHEVRON_RIGHT);
        }
    }

    private void rebuild() {
        rows.getChildren().clear();
        for (RoomMember member : controller.getMembers()) {
            rows.getChildren().add(row(member));
        }
        updateAddKelsyButton();
    }

    private void updateAddKelsyButton() {
        boolean hasKelsy = false;
        for (RoomMember member : controller.getMembers()) {
            if (member.isKelsy()) {
                hasKelsy = true;
                break;
            }
        }
        boolean show = expanded && !hasKelsy;
        addKelsyBtn.setVisible(show);
        addKelsyBtn.setManaged(show);
    }

    private HBox row(RoomMember member) {
        String name = member.username() == null || member.username().isBlank()
                ? "?" : member.username();
        Image photo = member.isKelsy()
                ? controller.avatarOfSecretary()
                : controller.avatarOf(name);
        AvatarView avatar = new AvatarView(name, photo, member.self(), 28);

        HBox cell = new HBox(8);
        cell.getStyleClass().add("member-row");
        cell.setAlignment(expanded ? Pos.CENTER_LEFT : Pos.CENTER);
        cell.getChildren().add(avatar);
        if (expanded) {
            Label label = new Label(member.self() ? name + "（我）" : name);
            label.getStyleClass().add("member-name");
            label.setWrapText(false);
            label.setTextOverrun(OverrunStyle.ELLIPSIS);
            label.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(label, Priority.ALWAYS);
            cell.getChildren().add(label);
        }
        if (member.self()) {
            cell.setCursor(Cursor.HAND);
            cell.setOnMouseClicked(e -> pickSelfAvatar(controller.getState()));
        } else if (member.isKelsy()) {
            cell.setCursor(Cursor.HAND);
            cell.setOnMouseClicked(e -> onMention.accept(KelsyMention.insert(controller.secretaryNickname())));
            if (expanded) {
                Button remove = new Button("移除");
                remove.getStyleClass().add("member-list-remove-kelsy");
                remove.setFocusTraversable(false);
                remove.setOnAction(e -> controller.disableKelsy());
                remove.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
                cell.getChildren().add(remove);
            }
        }
        return cell;
    }

    private void pickKelsyAvatar() {
        AvatarService.chooseAndStoreKelsy(
                        getScene() == null ? null : getScene().getWindow(),
                        controller.imCode())
                .ifPresent(path -> {
                    TextInputDialog dialog = new TextInputDialog(RoomMember.SECRETARY_NAME);
                    dialog.setTitle("秘书昵称");
                    dialog.setHeaderText(null);
                    dialog.setContentText("秘书昵称");
                    dialog.showAndWait().ifPresentOrElse(
                            nick -> controller.enableKelsy(path, nick),
                            () -> { /* 取消则不 enable，settings 不落盘 */ });
                });
    }

    private void pickSelfAvatar(AppState state) {
        AvatarService.chooseAndStore(getScene() == null ? null : getScene().getWindow())
                .ifPresent(path -> {
                    saveService.saveAvatarPath(path);
                    state.setAvatar(AvatarService.load(path).orElse(null));
                    if (state.client() != null) {
                        AvatarService.thumbnailBase64(path).ifPresent(state.client()::setAvatarPlaintext);
                    }
                });
    }
}
