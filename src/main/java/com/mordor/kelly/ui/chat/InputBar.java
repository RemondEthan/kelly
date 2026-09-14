package com.mordor.kelly.ui.chat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.mordor.kelly.kelsy.KelsyMention;
import com.mordor.kelly.model.RoomMember;
import com.mordor.kelly.service.ImageDraft;
import com.mordor.kelly.service.PasteImage;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import javax.imageio.ImageIO;

/**
 * 聊天输入栏：可选图片草稿 + 附件 + 文本 + 表情 + 发送。
 */
public class InputBar extends VBox {

    private final TextField textField;
    private final Button sendBtn;
    private final Label hint = new Label();
    private final PauseTransition hideHint = new PauseTransition(Duration.seconds(3));
    private final HBox draftRow = new HBox(8);
    private final ImageView draftThumb = new ImageView();
    private ImageDraft draft;

    private final EmojiPopover emojiPopover;
    private final Supplier<String> secretaryNickname;
    private final ObservableList<RoomMember> members;
    private final MentionPopover mentionPopover;

    public InputBar(Function<String, ChatController.SendResult> onSend,
                    Supplier<String> secretaryNickname) {
        this(onSend, secretaryNickname, draft -> ChatController.SendResult.reject(null),
                FXCollections.observableArrayList(), (m, n) -> null);
    }

    public InputBar(Function<String, ChatController.SendResult> onSend,
                    Supplier<String> secretaryNickname,
                    ObservableList<RoomMember> members,
                    BiFunction<RoomMember, String, Image> avatarOf) {
        this(onSend, secretaryNickname, draft -> ChatController.SendResult.reject(null),
                members, avatarOf);
    }

    public InputBar(Function<String, ChatController.SendResult> onSend,
                    Supplier<String> secretaryNickname,
                    Function<ImageDraft, ChatController.SendResult> onSendImage,
                    ObservableList<RoomMember> members,
                    BiFunction<RoomMember, String, Image> avatarOf) {
        super(4);
        this.secretaryNickname = secretaryNickname == null
                ? () -> RoomMember.SECRETARY_NAME : secretaryNickname;
        getStyleClass().add("input-bar");
        setAlignment(Pos.CENTER_LEFT);

        draftRow.getStyleClass().add("image-draft");
        draftRow.setAlignment(Pos.CENTER_LEFT);
        draftThumb.setFitHeight(56);
        draftThumb.setFitWidth(80);
        draftThumb.setPreserveRatio(true);
        Button clearDraft = new Button();
        clearDraft.setText("×");
        clearDraft.setOnAction(e -> setDraft(null));
        Label draftHint = new Label("截图草稿，发送后传给对方");
        draftRow.getChildren().addAll(draftThumb, draftHint, clearDraft);
        hideDraft();

        Button attach = new Button();
        attach.setGraphic(new FontIcon(MaterialDesignP.PAPERCLIP));
        attach.setOnAction(e -> showAttachStub());

        sendBtn = new Button();
        sendBtn.setGraphic(new FontIcon(MaterialDesignS.SEND));
        sendBtn.setDisable(true);

        hint.getStyleClass().add("kelsy-busy-hint");
        hint.setVisible(false);
        hint.setManaged(false);
        hideHint.setOnFinished(e -> {
            hint.setVisible(false);
            hint.setManaged(false);
        });

        textField = new TextField();
        textField.setPromptText("输入消息...");
        textField.setOnAction(e -> send(onSend, onSendImage));
        textField.textProperty().addListener((obs, o, n) -> refreshSendEnabled());
        HBox.setHgrow(textField, Priority.ALWAYS);

        this.members = members == null ? FXCollections.observableArrayList() : members;
        mentionPopover = new MentionPopover(avatarOf == null ? (m, n) -> null : avatarOf, this::pickMember);
        textField.textProperty().addListener((obs, o, n) -> refreshMention());
        textField.caretPositionProperty().addListener((obs, o, n) -> refreshMention());
        textField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (mentionPopover.handleKey(e)) {
                e.consume();
                return;
            }
            if (e.getCode() == KeyCode.V && e.isShortcutDown()) {
                if (tryPasteImage()) {
                    e.consume();
                }
            }
            if (e.getCode() == KeyCode.ESCAPE && draft != null) {
                setDraft(null);
                e.consume();
            }
        });

        Button emoji = new Button();
        emoji.setGraphic(EmojiImages.view("😊", 18));
        emoji.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
        emojiPopover = new EmojiPopover(textField);
        emoji.setOnAction(e -> {
            mentionPopover.hide();
            emojiPopover.show(emoji);
        });

        sendBtn.setOnAction(e -> send(onSend, onSendImage));

        HBox editor = new HBox(6);
        editor.setAlignment(Pos.CENTER_LEFT);
        editor.getChildren().addAll(attach, textField, emoji, sendBtn, hint);
        getChildren().addAll(draftRow, editor);
    }

    private void send(Function<String, ChatController.SendResult> onSend,
                      Function<ImageDraft, ChatController.SendResult> onSendImage) {
        if (draft != null) {
            ImageDraft payload = new ImageDraft(draft.bytes(), draft.mime(), textField.getText());
            ChatController.SendResult result = onSendImage.apply(payload);
            if (result == null || !result.accepted()) {
                showHint(result);
                return;
            }
            setDraft(null);
            clear();
            mentionPopover.hide();
            return;
        }
        String text = textField.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        ChatController.SendResult result = onSend.apply(text);
        if (result == null || !result.accepted()) {
            showHint(result);
            return;
        }
        clear();
        mentionPopover.hide();
    }

    private void showHint(ChatController.SendResult result) {
        if (result != null && result.hint() != null && !result.hint().isBlank()) {
            hint.setText(result.hint());
            hint.setVisible(true);
            hint.setManaged(true);
            hideHint.stop();
            hideHint.playFromStart();
        }
    }

    private boolean tryPasteImage() {
        Clipboard cb = Clipboard.getSystemClipboard();
        Optional<byte[]> raw = Optional.empty();
        if (cb.hasImage()) {
            byte[] png = pngFromFx(cb.getImage());
            if (png != null) {
                raw = Optional.of(png);
            }
        }
        List<Path> files = new ArrayList<>();
        if (cb.hasFiles()) {
            for (File file : cb.getFiles()) {
                files.add(file.toPath());
            }
        }
        Optional<PasteImage.Accepted> accepted = PasteImage.resolve(raw, files, cb.hasString());
        if (accepted.isEmpty()) {
            if (cb.hasImage() || hasImageFile(files)) {
                showHint(ChatController.SendResult.reject(ChatController.IMAGE_TOO_LARGE_HINT));
                return true;
            }
            return false;
        }
        setDraft(new ImageDraft(accepted.get().bytes(), accepted.get().mime(), textField.getText()));
        return true;
    }

    private static boolean hasImageFile(List<Path> files) {
        return PasteImage.resolve(Optional.empty(), files, false).isPresent()
                || files.stream().anyMatch(p -> p.getFileName().toString().matches("(?i).*\\.(png|jpe?g|gif|webp)"));
    }

    private void setDraft(ImageDraft next) {
        this.draft = next;
        if (next == null) {
            hideDraft();
        } else {
            draftThumb.setImage(new Image(new ByteArrayInputStream(next.bytes())));
            draftRow.setVisible(true);
            draftRow.setManaged(true);
        }
        refreshSendEnabled();
    }

    private void hideDraft() {
        draftRow.setVisible(false);
        draftRow.setManaged(false);
        draftThumb.setImage(null);
    }

    private void refreshSendEnabled() {
        String n = textField.getText();
        sendBtn.setDisable(draft == null && (n == null || n.isBlank()));
    }

    static byte[] pngFromFx(Image img) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
            return null;
        }
        int w = (int) Math.round(img.getWidth());
        int h = (int) Math.round(img.getHeight());
        PixelReader reader = img.getPixelReader();
        if (reader == null) {
            return null;
        }
        WritablePixelFormat<IntBuffer> fmt = PixelFormat.getIntArgbInstance();
        int[] pix = new int[w * h];
        reader.getPixels(0, 0, w, h, fmt, pix, 0, w);
        BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        buf.setRGB(0, 0, w, h, pix, 0, w);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(buf, "png", out)) {
                return null;
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void refreshMention() {
        if (mentionPopover == null) {
            return;
        }
        var token = MentionQuery.parse(textField.getText(), textField.getCaretPosition());
        if (token.isEmpty()) {
            mentionPopover.hide();
            return;
        }
        var found = MentionQuery.candidates(members, token.get().query());
        if (found.isEmpty()) {
            mentionPopover.hide();
            return;
        }
        emojiPopover.hide();
        mentionPopover.show(textField, found);
    }

    private void pickMember(RoomMember member) {
        var token = MentionQuery.parse(textField.getText(), textField.getCaretPosition());
        if (token.isEmpty()) {
            return;
        }
        var applied = MentionQuery.apply(
                textField.getText(), token.get().atIndex(), textField.getCaretPosition(), member.username());
        textField.setText(applied.text());
        textField.positionCaret(applied.caret());
        textField.requestFocus();
    }

    private void showAttachStub() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText("文件传输未实现（demo 模式）");
        alert.showAndWait();
    }

    public String getText() {
        return textField.getText();
    }

    public void clear() {
        textField.clear();
    }

    /** 在输入框开头插入当前秘书昵称；若已是提及则仅聚焦。 */
    public void insertMention(String snippet) {
        String cur = textField.getText() == null ? "" : textField.getText();
        if (KelsyMention.isMention(cur, secretaryNickname.get())) {
            textField.requestFocus();
            return;
        }
        textField.setText(snippet + cur);
        textField.positionCaret(textField.getText().length());
        textField.requestFocus();
    }
}
