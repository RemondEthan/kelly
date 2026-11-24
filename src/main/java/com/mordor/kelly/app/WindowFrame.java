package com.mordor.kelly.app;

import javafx.event.EventHandler;
import javafx.event.EventTarget;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.function.BooleanSupplier;

/**
 * 自绘窗口框架：去掉系统装饰，改由应用自己画标题栏。
 *
 * <h3>为什么这么做</h3>
 * <p>系统原生标题栏的颜色由桌面环境决定（多数 Linux 发行版是白色），
 * 和应用自己的浅蓝雪花壁纸完全不搭，也无法调整高度和圆角。
 * 去掉装饰后，标题栏由 {@link com.mordor.kelly.ui.chat.ChatHeader} 绘制，
 * 颜色、宽度、圆角全部跟随应用主题。</p>
 *
 * <h3>组成</h3>
 * <ul>
 *   <li>{@link #apply(Stage)} - 配置窗口样式</li>
 *   <li>{@link #applyRoundedClip(Region)} - 给根节点加圆角裁剪</li>
 *   <li>{@link #makeDraggable(Node)} - 让某个节点成为窗口拖动区域</li>
 *   <li>{@link #windowButtons(Node)} - 生成最小化 / 最大化 / 关闭按钮组</li>
 * </ul>
 *
 * <h3>拖动规则</h3>
 * <p>只有落在「非交互控件」上的按下事件才启动拖动：按钮、输入框
 * （{@link Button} / {@link TextInputControl}）会消费自己的鼠标事件，
 * 因此点它们不会误触发窗口移动；点标签、图标等纯展示节点则可以拖动窗口。</p>
 */
public final class WindowFrame {

    /** 窗口圆角半径（像素） */
    public static final double CORNER_RADIUS = 12;

    /** 最小化按钮文字 */
    private static final String GLYPH_MINIMIZE = "–";

    /** 关闭按钮文字 */
    private static final String GLYPH_CLOSE = "✕";

    private WindowFrame() {
    }

    /**
     * 配置舞台样式。DDE/X11 上 JavaFX 无边框窗口缺少原生最小化权限，
     * 因此先用原生边框创建窗口，显示后再隐藏系统边框。
     *
     * <p>必须在 {@code stage.show()} 之前调用。
     * {@link StageStyle#TRANSPARENT} 同时意味着「无装饰」和「透明」，
     * 配合 {@link #applyRoundedClip(Region)} 的圆角裁剪，
     * 窗口四角才会真正透出桌面，而不是被系统画成直角。</p>
     *
     * @param stage 主窗口
     */
    public static void apply(Stage stage) {
        stage.initStyle(X11WindowFrame.isDeepinSession() ? StageStyle.DECORATED : StageStyle.TRANSPARENT);
    }

    /**
     * 给根节点加圆角裁剪：窗口内容被裁成圆角矩形，四角透出桌面。
     *
     * <p>裁剪矩形跟随根节点尺寸变化。</p>
     *
     * @param root 场景根节点
     */
    public static void applyRoundedClip(Region root) {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(CORNER_RADIUS * 2);
        clip.setArcHeight(CORNER_RADIUS * 2);
        clip.widthProperty().bind(root.widthProperty());
        clip.heightProperty().bind(root.heightProperty());
        root.setClip(clip);
    }

    /**
     * 让节点成为窗口拖动区域：按住拖动即可移动窗口。
     *
     * <p>窗口在按下时才解析，因此节点可以先构造、后入场景。</p>
     *
     * @param region 拖动区域（通常是标题栏）
     */
    public static void makeDraggable(Node region) {
        makeDraggable(region, () -> true);
    }

    public static void makeDraggable(Node region, BooleanSupplier enabled) {
        DragHandler handler = new DragHandler(region);
        region.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (enabled.getAsBoolean()) {
                handler.handle(event);
            }
        });
        region.addEventHandler(MouseEvent.MOUSE_DRAGGED, handler);
        region.addEventHandler(MouseEvent.MOUSE_RELEASED, handler);
    }

    /**
     * 生成窗口按钮组：最小化 / 最大化 / 关闭。
     *
     * <p>关闭按钮沿用应用原有行为——隐藏窗口而不是退出进程，
     * 应用继续留在系统托盘里运行。</p>
     *
     * @param context 用于解析所属窗口的节点（调用时需已在场景中）
     * @return 按钮组
     */
    public static HBox windowButtons(Node context) {
        Button minimize = new Button(GLYPH_MINIMIZE);
        minimize.setAccessibleText("最小化");
        minimize.getStyleClass().addAll("window-button", "window-minimize-button");
        minimize.setOnAction(e -> {
            Stage stage = stageOf(context);
            if (stage != null) {
                FxStageSupport.minimize(stage);
            }
        });

        Button close = new Button(GLYPH_CLOSE);
        close.setAccessibleText("关闭");
        close.getStyleClass().addAll("window-button", "window-close-button");
        close.setOnAction(e -> {
            Stage stage = stageOf(context);
            if (stage != null) {
                FxStageSupport.hide(stage);
            }
        });

        Button maximize = new Button("□");
        maximize.setAccessibleText("最大化或还原");
        maximize.getStyleClass().addAll("window-button", "window-maximize-button");
        maximize.setOnAction(e -> {
            Stage stage = stageOf(context);
            if (stage != null) {
                stage.setMaximized(!stage.isMaximized());
            }
        });

        HBox buttons = new HBox(minimize, maximize, close);
        buttons.getStyleClass().add("window-buttons");
        buttons.setAlignment(Pos.CENTER);
        return buttons;
    }

    /** 为无边框窗口补充边缘和四角缩放；过滤事件避免与标题栏拖动冲突。 */
    public static void makeResizable(Stage stage, Scene scene) {
        ResizeHandler handler = new ResizeHandler(stage, scene);
        scene.addEventFilter(MouseEvent.ANY, handler);
    }

    private static final class ResizeHandler implements EventHandler<MouseEvent> {
        private final Stage stage;
        private final Scene scene;
        private boolean left, right, top, bottom, resizing;
        private double mouseX, mouseY, x, y, width, height;
        private Node cursorTarget;
        private Cursor previousCursor;

        ResizeHandler(Stage stage, Scene scene) {
            this.stage = stage;
            this.scene = scene;
        }

        private void clearCursor() {
            if (cursorTarget != null) {
                cursorTarget.setCursor(previousCursor);
                cursorTarget = null;
            }
            scene.setCursor(null);
        }

        @Override
        public void handle(MouseEvent event) {
            if (stage.isMaximized() || stage.isFullScreen() || !stage.isResizable()) {
                resizing = false;
                clearCursor();
                return;
            }
            if (event.getEventType() == MouseEvent.MOUSE_MOVED
                    || event.getEventType() == MouseEvent.MOUSE_PRESSED) {
                left = event.getSceneX() <= 6;
                right = event.getSceneX() >= scene.getWidth() - 6;
                top = event.getSceneY() <= 6;
                bottom = event.getSceneY() >= scene.getHeight() - 6;
                Cursor cursor = left ? (top ? Cursor.NW_RESIZE : bottom ? Cursor.SW_RESIZE : Cursor.W_RESIZE)
                        : right ? (top ? Cursor.NE_RESIZE : bottom ? Cursor.SE_RESIZE : Cursor.E_RESIZE)
                        : top ? Cursor.N_RESIZE : bottom ? Cursor.S_RESIZE : null;
                clearCursor();
                scene.setCursor(cursor);
                if (cursor != null) {
                    Node target = event.getPickResult() == null ? null
                            : event.getPickResult().getIntersectedNode();
                    if (target == null && event.getTarget() instanceof Node node) target = node;
                    if (target != null && !target.cursorProperty().isBound()) {
                        cursorTarget = target;
                        previousCursor = target.getCursor();
                        target.setCursor(cursor);
                    }
                }
                if (event.getEventType() == MouseEvent.MOUSE_PRESSED
                        && event.getButton() == MouseButton.PRIMARY && cursor != null) {
                    resizing = true;
                    mouseX = event.getScreenX();
                    mouseY = event.getScreenY();
                    x = stage.getX();
                    y = stage.getY();
                    width = stage.getWidth();
                    height = stage.getHeight();
                    event.consume();
                }
            } else if (event.getEventType() == MouseEvent.MOUSE_DRAGGED && resizing) {
                double dx = event.getScreenX() - mouseX;
                double dy = event.getScreenY() - mouseY;
                if (left || right) {
                    double next = Math.max(stage.getMinWidth(),
                            Math.min(stage.getMaxWidth(), width + (left ? -dx : dx)));
                    stage.setWidth(next);
                    if (left) stage.setX(x + width - next);
                }
                if (top || bottom) {
                    double next = Math.max(stage.getMinHeight(),
                            Math.min(stage.getMaxHeight(), height + (top ? -dy : dy)));
                    stage.setHeight(next);
                    if (top) stage.setY(y + height - next);
                }
                event.consume();
            } else if (event.getEventType() == MouseEvent.MOUSE_RELEASED && resizing) {
                resizing = false;
                clearCursor();
                event.consume();
            } else if (event.getEventType() == MouseEvent.MOUSE_EXITED && !resizing) {
                clearCursor();
            }
        }
    }

    /**
     * 解析节点所属的 Stage。
     *
     * @param context 上下文节点
     * @return 所属窗口；尚未进入场景时返回 {@code null}
     */
    private static Stage stageOf(Node context) {
        if (context.getScene() == null || context.getScene().getWindow() == null) {
            return null;
        }
        return (Stage) context.getScene().getWindow();
    }

    /**
     * 窗口拖动处理器：按下时记录鼠标相对窗口的偏移，拖动时同步窗口位置。
     */
    private static final class DragHandler implements EventHandler<MouseEvent> {

        /** 拖动区域节点，用于反查所属窗口 */
        private final Node region;
        /** 鼠标相对窗口左上角的偏移 */
        private double offsetX;
        /** 鼠标相对窗口左上角的偏移 */
        private double offsetY;
        /** 是否正在拖动 */
        private boolean dragging;

        DragHandler(Node region) {
            this.region = region;
        }

        @Override
        public void handle(MouseEvent event) {
            if (event.getEventType() == MouseEvent.MOUSE_PRESSED) {
                dragging = false;
                if (event.getButton() != MouseButton.PRIMARY || isInteractive(event.getTarget())) {
                    return;  // 落在按钮 / 输入框上，不启动拖动
                }
                Stage stage = stageOf(region);
                if (stage == null || stage.isMaximized() || stage.isFullScreen()) {
                    return;
                }
                offsetX = event.getScreenX() - stage.getX();
                offsetY = event.getScreenY() - stage.getY();
                dragging = true;
            } else if (event.getEventType() == MouseEvent.MOUSE_DRAGGED) {
                if (!dragging) {
                    return;
                }
                Stage stage = stageOf(region);
                if (stage == null) {
                    return;
                }
                stage.setX(event.getScreenX() - offsetX);
                stage.setY(event.getScreenY() - offsetY);
            } else if (event.getEventType() == MouseEvent.MOUSE_RELEASED) {
                dragging = false;
            }
        }

        /**
         * 判断鼠标落点是否在交互控件上。
         *
         * @param target 事件源
         * @return 是交互控件返回 {@code true}
         */
        private static boolean isInteractive(EventTarget target) {
            if (!(target instanceof Node node)) {
                return false;
            }
            for (Node cur = node; cur != null; cur = cur.getParent()) {
                if (cur instanceof Button || cur instanceof TextInputControl) {
                    return true;
                }
            }
            return false;
        }
    }
}
