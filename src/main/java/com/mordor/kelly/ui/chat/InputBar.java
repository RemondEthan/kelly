package com.mordor.kelly.ui.chat;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.mordor.kelly.kelsy.KelsyMention;
import com.mordor.kelly.model.RoomMember;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

/**
 * 聊天输入栏：附件按钮 + 文本输入框 + 表情按钮 + 发送按钮。
 *
 * HBox 横向排列 4 个元素：附件 | 输入框 | 表情 | 发送。
 * 文本框占据所有剩余空间（Hgrow=ALWAYS），按钮固定大小。
 */
public class InputBar extends HBox {

    // 文本框（消息内容）和发送按钮在整个生命周期内都需要引用，所以提为字段
    private final TextField textField;
    private final Button sendBtn;
    private final Label hint = new Label();
    private final PauseTransition hideHint = new PauseTransition(Duration.seconds(3));

    // 表情弹窗组件，按表情按钮时弹出
    private final EmojiPopover emojiPopover;
    private final Supplier<String> secretaryNickname;
    private final ObservableList<RoomMember> members;
    private final MentionPopover mentionPopover;

    public InputBar(Function<String, ChatController.SendResult> onSend,
                    Supplier<String> secretaryNickname) {
        this(onSend, secretaryNickname, FXCollections.observableArrayList(), (m, n) -> null);
    }

    public InputBar(Function<String, ChatController.SendResult> onSend,
                    Supplier<String> secretaryNickname,
                    ObservableList<RoomMember> members,
                    BiFunction<RoomMember, String, Image> avatarOf) {
        super(6);  // HBox 子节点之间水平间距 6px
        this.secretaryNickname = secretaryNickname == null
                ? () -> RoomMember.SECRETARY_NAME : secretaryNickname;
        getStyleClass().add("input-bar");
        setAlignment(Pos.CENTER_LEFT);  // 子节点垂直居中、水平靠左

        // ---- 附件按钮：用 Material Design 图标库的回形针图标 ----
        Button attach = new Button();
        // FontIcon：ikonli 包提供的图标节点，setGraphic 把图标放进 Button
        attach.setGraphic(new FontIcon(MaterialDesignP.PAPERCLIP));
        attach.setOnAction(e -> showAttachStub());

        // ---- 发送按钮（先创建并禁用，因为还没输入内容）----
        sendBtn = new Button();
        sendBtn.setGraphic(new FontIcon(MaterialDesignS.SEND));
        sendBtn.setDisable(true);  // 初始禁用：空文本不能发送

        hint.getStyleClass().add("kelsy-busy-hint");
        hint.setVisible(false);
        hint.setManaged(false);
        hideHint.setOnFinished(e -> {
            hint.setVisible(false);
            hint.setManaged(false);
        });

        // ---- 文本输入框 ----
        textField = new TextField();
        textField.setPromptText("输入消息...");
        // setOnAction：按回车键时触发，相当于"提交"
        textField.setOnAction(e -> send(onSend));
        // 监听文本变化：空文本时禁用发送按钮，否则启用
        textField.textProperty().addListener((obs, o, n) ->
                sendBtn.setDisable(n == null || n.isBlank()));
        // setHgrow：让 textField 占据所有剩余水平空间
        HBox.setHgrow(textField, Priority.ALWAYS);

        this.members = members == null ? FXCollections.observableArrayList() : members;
        mentionPopover = new MentionPopover(avatarOf == null ? (m, n) -> null : avatarOf, this::pickMember);
        textField.textProperty().addListener((obs, o, n) -> refreshMention());
        textField.caretPositionProperty().addListener((obs, o, n) -> refreshMention());
        textField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (mentionPopover.handleKey(e)) {
                e.consume();
            }
        });

        // ---- 表情按钮 ----
        Button emoji = new Button();
        emoji.setGraphic(EmojiImages.view("😊", 18));
        emoji.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
        emojiPopover = new EmojiPopover(textField);
        emoji.setOnAction(e -> {
            mentionPopover.hide();
            emojiPopover.show(emoji);
        });

        // 发送按钮的点击事件（创建完 textField 后再绑定，避免引用顺序问题）
        sendBtn.setOnAction(e -> send(onSend));

        // 按顺序加入 HBox：附件 → 文本框 → 表情 → 发送 → 忙碌提示
        getChildren().addAll(attach, textField, emoji, sendBtn, hint);
    }

    // 把当前文本发出去；空文本则忽略；拒绝时保留输入并按 hint 提示
    private void send(Function<String, ChatController.SendResult> onSend) {
        String text = textField.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        ChatController.SendResult result = onSend.apply(text);
        if (result == null || !result.accepted()) {
            if (result != null && result.hint() != null && !result.hint().isBlank()) {
                hint.setText(result.hint());
                hint.setVisible(true);
                hint.setManaged(true);
                hideHint.stop();
                hideHint.playFromStart();
            }
            return;
        }
        clear();
        mentionPopover.hide();
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

    // 文件传输的占位提示：当前 demo 阶段没实现真实附件
    private void showAttachStub() {
        // Alert：模态对话框（INFORMATION 风格）
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText("文件传输未实现（demo 模式）");
        alert.showAndWait();  // 显示并阻塞等待用户关闭
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
