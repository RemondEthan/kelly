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
 * 聊天输入栏组件：整合图片草稿、附件、文本输入、表情选择和发送功能。
 *
 * <h3>布局结构</h3>
 * <pre>
 *   VBox (本类, input-bar)
 *   ├── HBox (draftRow, 图片草稿预览行，有草稿时才显示)
 *   │   ├── ImageView (缩略图)
 *   │   ├── Label ("截图草稿，发送后传给对方")
 *   │   └── Button ("×" 清除草稿)
 *   └── HBox (editor, 编辑行)
 *       ├── Button (📎 附件)
 *       ├── TextField (文本输入)
 *       ├── Button (😊 表情)
 *       ├── Button (➤ 发送)
 *       └── Label (提示信息)
 * </pre>
 *
 * <h3>功能说明</h3>
 * <ul>
 *   <li><b>图片粘贴</b> - Ctrl+V 粘贴剪贴板图片，显示草稿预览</li>
 *   <li><b>@提及</b> - 输入 @ 后自动弹出成员选择列表</li>
 *   <li><b>表情插入</b> - 点击 😊 按钮弹出表情网格</li>
 *   <li><b>发送</b> - 回车或点击发送按钮，先发图片草稿再发文本</li>
 * </ul>
 *
 * <h3>JavaFX 事件处理</h3>
 * <p>{@link KeyEvent#KEY_PRESSED} 事件过滤器处理快捷键（Ctrl+V 粘贴、ESC 清除草稿），
 * 并将按键事件委托给 {@link MentionPopover} 处理（上下选择、回车确认）。</p>
 */
public class InputBar extends VBox {

    /** 文本输入框 */
    private final TextField textField;
    /** 发送按钮 */
    private final Button sendBtn;
    /** 提示标签（显示"秘书还在回复"等信息） */
    private final Label hint = new Label();
    /** 提示自动隐藏定时器：3 秒后隐藏 */
    private final PauseTransition hideHint = new PauseTransition(Duration.seconds(3));
    /** 图片草稿预览行（有草稿时才显示） */
    private final HBox draftRow = new HBox(8);
    /** 草稿缩略图 */
    private final ImageView draftThumb = new ImageView();
    /** 当前图片草稿，null 表示无草稿 */
    private ImageDraft draft;

    /** 表情选择弹窗 */
    private final EmojiPopover emojiPopover;
    /** 秘书昵称供应器（用于 @提及检测） */
    private final Supplier<String> secretaryNickname;
    /** 房间成员列表（用于 @提及候选人） */
    private final ObservableList<RoomMember> members;
    /** @提及选择弹窗 */
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

    /**
     * 将 JavaFX Image 转换为 PNG 字节数组。
     *
     * <p>JavaFX 的 {@link Image} 使用 {@link PixelReader} 读取像素数据，
     * 本方法将像素数据转为 {@link BufferedImage} 后通过 ImageIO 编码为 PNG。</p>
     *
     * @param img JavaFX 图片
     * @return PNG 字节数组，转换失败返回 null
     */
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
        // 读取所有像素为 ARGB int 数组
        WritablePixelFormat<IntBuffer> fmt = PixelFormat.getIntArgbInstance();
        int[] pix = new int[w * h];
        reader.getPixels(0, 0, w, h, fmt, pix, 0, w);
        // 转为 BufferedImage
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
