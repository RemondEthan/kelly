package com.mordor.kelly.ui.chat;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.CubicCurveTo;
import javafx.scene.shape.HLineTo;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.QuadCurveTo;
import javafx.scene.shape.VLineTo;
import javafx.scene.text.HitInfo;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.Cursor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 可选择、可复制的 TextFlow 组件。
 *
 * <h3>核心问题</h3>
 * <p>JavaFX 的 {@link Text} 控件在拖动鼠标时<b>不会</b>自动更新 selectionStart/selectionEnd，
 * 因此需要自己通过 hitTest（命中测试）计算鼠标选区。本类实现了完整的鼠标选区管理和
 * Ctrl/Cmd+C 复制功能。</p>
 *
 * <h3>选区管理原理</h3>
 * <ol>
 *   <li>鼠标按下时记录锚点位置（anchorRaw）</li>
 *   <li>鼠标拖动时通过 {@link #rawIndexAt} 将屏幕坐标映射为原始文本索引</li>
 *   <li>{@link #applyRawSelection} 将原始文本选区映射到各 Text 子节点的 selectionStart/End</li>
 *   <li>Prism 渲染引擎根据 selectionStart/End 绘制选中高亮</li>
 * </ol>
 *
 * <h3>emoji 处理</h3>
 * <p>emoji 在 TextFlow 中显示为 {@link ImageView}，不参与字符命中测试，
 * 但通过 {@link #charOffsets} 映射，raw 子串仍包含对应 emoji 的 Unicode 字符。</p>
 *
 * <h3>富文本支持</h3>
 * <p>通过 {@link Segment} 密封接口支持多种文本片段：
 * 纯文本（加粗/斜体）、emoji、行内代码、超链接。</p>
 *
 * <h3>Scene 级复制过滤器</h3>
 * <p>每个 Scene 只安装一份 Ctrl+C 事件过滤器，通过 {@link #ACTIVE} 原子引用
 * 追踪当前活跃的 SelectableTextFlow 实例，由它负责写出剪贴板内容。</p>
 */
public class SelectableTextFlow extends TextFlow {

    /**
     * 富文本片段密封接口：支持多种文本类型。
     *
     * <p>使用 Java 17+ 的 sealed interface 和 record 特性，
     * 编译器确保所有实现类都在本文件中定义。</p>
     */
    public sealed interface Segment {
        /** 纯文本片段（可加粗/斜体） */
        record Text(String value, boolean bold, boolean italic) implements Segment {
            public Text(String value) { this(value, false, false); }
        }
        /** Emoji 片段 */
        record Emoji(String codepoint) implements Segment {}
        /** 行内代码片段 */
        record Code(String value) implements Segment {}
        /** 超链接片段 */
        record Link(String text, String dest) implements Segment {}
    }

    /** Ctrl+C 快捷键（Windows/Linux） */
    private static final KeyCombination COPY_WIN = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    /** Cmd+C 快捷键（macOS） */
    private static final KeyCombination COPY_MAC = new KeyCodeCombination(KeyCode.C, KeyCombination.META_DOWN);
    /** Scene 属性中用于标记已安装复制过滤器的 key */
    private static final Object SCENE_COPY_KEY = new Object();
    /** 当前活跃的 SelectableTextFlow（用于复制操作） */
    private static final AtomicReference<SelectableTextFlow> ACTIVE = new AtomicReference<>();
    /** Prism 渲染引擎将 selectionFill 作为选中字形颜色，必须保持深色（默认白色会导致选区不可见） */
    static final Color SELECTION_GLYPH = Color.web("#212121");
    /** 自定义选区高亮颜色（蓝色背景） */
    private static final Color SELECTION_HIGHLIGHT = Color.web("#90CAF9");

    /** 每个子节点在原始文本中的字符偏移量 */
    private int[] charOffsets;
    /** 当前选区 [start, end] 在原始文本中的索引 */
    private final AtomicReference<int[]> currentSelection = new AtomicReference<>(new int[]{0, 0});
    /** 原始文本（包含 emoji 的 Unicode 字符串） */
    private String raw;
    /** 鼠标按下时的锚点位置（原始文本索引） */
    private int anchorRaw;
    /** 文本属性（支持外部绑定） */
    private final StringProperty text = new SimpleStringProperty();
    /** 选区高亮路径（覆盖在 Text 子节点上方） */
    private final Path highlight = new Path();

    public final StringProperty textProperty() { return text; }

    {
        text.addListener((obs, oldVal, newVal) -> {
            if (!Objects.equals(newVal, raw)) {
                rebuildFromText(newVal);
            }
        });
    }

    public final String getText() { return text.get(); }

    public final void setText(String value) {
        text.set(value);
        rebuildFromText(value);
    }

    private void rebuildFromText(String value) {
        getChildren().clear();
        this.raw = value == null ? "" : value;
        if (value == null || value.isEmpty()) {
            this.charOffsets = new int[0];
            currentSelection.set(new int[]{0, 0});
            return;
        }
        var parts = EmojiImages.flowWithMap(value);
        getChildren().addAll(parts.flow().getChildren());
        this.charOffsets = parts.charOffsets();
        decorateContent();
        attachHighlight();
        attachTextListeners();
        currentSelection.set(new int[]{0, 0});
        refreshHighlight();
    }

    SelectableTextFlow(List<Node> children, int[] charOffsets, String raw) {
        this.charOffsets = charOffsets == null ? new int[0] : charOffsets;
        this.raw = raw == null ? "" : raw;
        text.set(this.raw);
        getStyleClass().add("selectable-text-flow");
        setFocusTraversable(true);
        setCursor(Cursor.TEXT);
        getChildren().addAll(children);
        decorateContent();
        attachHighlight();
        attachTextListeners();
        setOnMousePressed(this::onPress);
        setOnMouseDragged(this::onDrag);
        sceneProperty().addListener((obs, oldScene, newScene) -> installSceneCopy(newScene));
        if (getScene() != null) {
            installSceneCopy(getScene());
        }
    }

    public static SelectableTextFlow forText(String text) {
        var parts = EmojiImages.flowWithMap(text);
        return new SelectableTextFlow(
                List.copyOf(parts.flow().getChildren()),
                parts.charOffsets(),
                text);
    }

    public static SelectableTextFlow forSegments(List<Segment> segments, String raw) {
        return forSegments(segments, raw, dest -> { /* no-op */ });
    }

    public static SelectableTextFlow forSegments(List<Segment> segments, String raw, Consumer<String> onLinkClick) {
        List<Node> children = new ArrayList<>();
        int[] offsets = computeOffsetsForSegments(segments, raw);
        for (Segment seg : segments) {
            if (seg instanceof Segment.Text t) {
                Text txt = new Text(t.value());
                if (t.bold()) {
                    txt.getStyleClass().add("md-bold");
                }
                if (t.italic()) {
                    txt.setStyle("-fx-font-style: italic;");
                }
                children.add(txt);
            } else if (seg instanceof Segment.Emoji e) {
                children.add(EmojiImages.view(e.codepoint(), 16));
            } else if (seg instanceof Segment.Code c) {
                Text codeText = new Text(c.value());
                codeText.getStyleClass().add("md-inline-code");
                children.add(codeText);
            } else if (seg instanceof Segment.Link l) {
                Text linkText = new Text(l.text());
                linkText.getStyleClass().add("md-link");
                linkText.setOnMouseClicked(ev -> {
                    if (ACTIVE.get() instanceof SelectableTextFlow flow && flow.currentRawSubstring() != null) {
                        ev.consume();
                        return;
                    }
                    onLinkClick.accept(l.dest());
                });
                children.add(linkText);
            }
        }
        return new SelectableTextFlow(children, offsets, raw);
    }

    private static int[] computeOffsetsForSegments(List<Segment> segments, String raw) {
        int[] offsets = new int[segments.size()];
        int cursor = 0;
        for (int i = 0; i < segments.size(); i++) {
            offsets[i] = cursor;
            Segment seg = segments.get(i);
            int len = switch (seg) {
                case Segment.Text t -> t.value().length();
                case Segment.Emoji e -> e.codepoint().length();
                case Segment.Code c -> c.value().length();
                case Segment.Link l -> l.text().length();
            };
            cursor += len;
        }
        return offsets;
    }

    private void attachTextListeners() {
        for (int i = 0; i < getChildren().size(); i++) {
            if (getChildren().get(i) instanceof Text t) {
                t.selectionStartProperty().addListener((obs, ov, nv) -> recomputeSelection());
                t.selectionEndProperty().addListener((obs, ov, nv) -> recomputeSelection());
            }
        }
    }

    private void recomputeSelection() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < getChildren().size(); i++) {
            if (getChildren().get(i) instanceof Text t) {
                int ts = t.getSelectionStart();
                int te = t.getSelectionEnd();
                if (ts >= 0 && te > ts) {
                    int rs = charOffsets[i] + ts;
                    int re = charOffsets[i] + te;
                    if (rs < min) min = rs;
                    if (re > max) max = re;
                }
            }
        }
        if (min == Integer.MAX_VALUE) {
            currentSelection.set(new int[]{0, 0});
        } else {
            currentSelection.set(new int[]{min, max});
        }
    }

    /** 把 raw 区间画到各 Text 子节点上；供鼠标拖选和测试调用。 */
    void applyRawSelection(int start, int end) {
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        start = clamp(start, 0, raw.length());
        end = clamp(end, 0, raw.length());
        currentSelection.set(new int[]{start, end});
        int childCount = getChildren().size();
        for (int i = 0; i < childCount; i++) {
            if (!(getChildren().get(i) instanceof Text t)) {
                continue;
            }
            int nodeStart = charOffset(i);
            int nodeEnd = nodeEnd(i);
            int visStart = Math.max(0, start - nodeStart);
            int visEnd = Math.min(nodeEnd - nodeStart, end - nodeStart);
            if (start < nodeEnd && end > nodeStart && visEnd > visStart) {
                t.setSelectionStart(visStart);
                t.setSelectionEnd(visEnd);
            } else {
                t.setSelectionStart(-1);
                t.setSelectionEnd(-1);
            }
        }
        refreshHighlight();
    }

    private void decorateContent() {
        for (Node n : getChildren()) {
            if (n instanceof Text t) {
                t.setSelectionFill(SELECTION_GLYPH);
            }
        }
    }

    private void attachHighlight() {
        highlight.setManaged(false);
        highlight.setMouseTransparent(true);
        highlight.setStroke(null);
        highlight.setFill(SELECTION_HIGHLIGHT);
        highlight.setViewOrder(1);
        if (!getChildren().contains(highlight)) {
            getChildren().add(highlight);
        }
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        refreshHighlight();
    }

    private void refreshHighlight() {
        highlight.getElements().clear();
        for (Node n : getChildren()) {
            if (!(n instanceof Text t)) {
                continue;
            }
            int ts = t.getSelectionStart();
            int te = t.getSelectionEnd();
            if (ts < 0 || te <= ts) {
                continue;
            }
            PathElement[] shape = t.getSelectionShape();
            if (shape == null) {
                continue;
            }
            double dx = t.getLayoutX();
            double dy = t.getLayoutY();
            for (PathElement el : shape) {
                highlight.getElements().add(shifted(el, dx, dy));
            }
        }
    }

    private static PathElement shifted(PathElement el, double dx, double dy) {
        if (el instanceof MoveTo m) {
            return new MoveTo(m.getX() + dx, m.getY() + dy);
        }
        if (el instanceof LineTo m) {
            return new LineTo(m.getX() + dx, m.getY() + dy);
        }
        if (el instanceof HLineTo m) {
            return new HLineTo(m.getX() + dx);
        }
        if (el instanceof VLineTo m) {
            return new VLineTo(m.getY() + dy);
        }
        if (el instanceof CubicCurveTo m) {
            return new CubicCurveTo(
                    m.getControlX1() + dx, m.getControlY1() + dy,
                    m.getControlX2() + dx, m.getControlY2() + dy,
                    m.getX() + dx, m.getY() + dy);
        }
        if (el instanceof QuadCurveTo m) {
            return new QuadCurveTo(
                    m.getControlX() + dx, m.getControlY() + dy,
                    m.getX() + dx, m.getY() + dy);
        }
        if (el instanceof ClosePath) {
            return new ClosePath();
        }
        return el;
    }

    private void onPress(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        requestFocus();
        setActive(this);
        anchorRaw = rawIndexAt(e.getSceneX(), e.getSceneY());
        applyRawSelection(anchorRaw, anchorRaw);
    }

    private void onDrag(MouseEvent e) {
        if (!e.isPrimaryButtonDown()) {
            return;
        }
        setActive(this);
        applyRawSelection(anchorRaw, rawIndexAt(e.getSceneX(), e.getSceneY()));
        e.consume();
    }

    int rawIndexAt(double sceneX, double sceneY) {
        int childCount = getChildren().size();
        if (childCount == 0 || raw.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < childCount; i++) {
            Node n = getChildren().get(i);
            if (n == highlight) {
                continue;
            }
            Point2D local = n.sceneToLocal(sceneX, sceneY);
            if (n.contains(local)) {
                return rawIndexInNode(i, n, local);
            }
        }
        int nearest = 0;
        double best = Double.MAX_VALUE;
        int rawAt = 0;
        for (int i = 0; i < childCount; i++) {
            Node n = getChildren().get(i);
            if (n == highlight) {
                continue;
            }
            Point2D local = n.sceneToLocal(sceneX, sceneY);
            var b = n.getBoundsInLocal();
            double cx = clamp(local.getX(), b.getMinX(), b.getMaxX());
            double cy = clamp(local.getY(), b.getMinY(), b.getMaxY());
            double d = Math.hypot(local.getX() - cx, local.getY() - cy);
            if (d < best) {
                best = d;
                nearest = i;
                if (local.getX() >= b.getMaxX()) {
                    rawAt = nodeEnd(i);
                } else if (local.getX() <= b.getMinX()) {
                    rawAt = charOffset(i);
                } else {
                    rawAt = rawIndexInNode(i, n, local);
                }
            }
        }
        return nearest == 0 && best == Double.MAX_VALUE ? 0 : rawAt;
    }

    private int rawIndexInNode(int i, Node n, Point2D local) {
        if (n instanceof Text t) {
            try {
                HitInfo hit = t.hitTest(local);
                int idx = hit.getCharIndex();
                int pos = hit.isLeading() ? idx : idx + 1;
                pos = clamp(pos, 0, t.getText() == null ? 0 : t.getText().length());
                return charOffset(i) + pos;
            } catch (RuntimeException ignored) {
                return charOffset(i);
            }
        }
        var b = n.getBoundsInLocal();
        return local.getX() < b.getMinX() + b.getWidth() / 2.0 ? charOffset(i) : nodeEnd(i);
    }

    private int charOffset(int i) {
        if (charOffsets == null || i < 0 || i >= charOffsets.length) {
            return 0;
        }
        return charOffsets[i];
    }

    private int nodeEnd(int i) {
        int next = i + 1;
        while (next < getChildren().size() && getChildren().get(next) == highlight) {
            next++;
        }
        if (next < getChildren().size() && next < charOffsets.length) {
            return charOffsets[next];
        }
        return raw.length();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static void setActive(SelectableTextFlow flow) {
        SelectableTextFlow prev = ACTIVE.getAndSet(flow);
        if (prev != null && prev != flow) {
            prev.applyRawSelection(0, 0);
        }
    }

    private static void installSceneCopy(Scene scene) {
        if (scene == null) {
            return;
        }
        if (scene.getProperties().putIfAbsent(SCENE_COPY_KEY, Boolean.TRUE) == null) {
            scene.addEventFilter(KeyEvent.KEY_PRESSED, SelectableTextFlow::handleSceneCopy);
        }
    }

    private static void handleSceneCopy(KeyEvent e) {
        if (!COPY_WIN.match(e) && !COPY_MAC.match(e)) {
            return;
        }
        SelectableTextFlow flow = ACTIVE.get();
        if (flow == null) {
            return;
        }
        String selected = flow.currentRawSubstring();
        if (selected == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(selected);
        Clipboard.getSystemClipboard().setContent(content);
        e.consume();
    }

    /** 当前选区对应的 raw 子串;无选区返回 null。 */
    String currentRawSubstring() {
        int[] sel = currentSelection.get();
        if (sel[0] == sel[1]) return null;
        int start = Math.max(0, Math.min(sel[0], raw.length()));
        int end = Math.max(start, Math.min(sel[1], raw.length()));
        return raw.substring(start, end);
    }
}
