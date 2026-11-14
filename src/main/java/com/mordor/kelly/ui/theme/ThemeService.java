package com.mordor.kelly.ui.theme;

import com.mordor.kelly.app.Kelly;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;

import java.net.URL;
import java.util.prefs.Preferences;

/**
 * 界面主题服务：负责主题的持久化，以及把主题「应用到某个场景」。
 *
 * <h3>换肤原理</h3>
 * <p>JavaFX 的 CSS 支持自定义属性（{@code -color-xxx}）。色板定义在
 * {@code ui/theme/theme.css}（亮色）与 {@code ui/theme/dark.css}（深色）里，
 * 两者都写在 {@code .theme-root} 选择器上，深色版多一个 {@code theme-dark} 类：</p>
 * <pre>
 *   .theme-root            { -color-text-primary: #212121; }   // 亮色
 *   .theme-root.theme-dark { -color-text-primary: #FFFFFF; }   // 深色
 * </pre>
 * <p>因此切换主题只需要给场景根节点加上/去掉 {@code theme-dark} 样式类，
 * JavaFX 会在下一个脉冲重新应用 CSS，整棵树随之变色。</p>
 *
 * <h3>多场景</h3>
 * <p>应用里有多个独立 Scene（主窗口、对话框、@提及弹窗），每个场景的根节点
 * 都要单独装载色板样式表并打上主题标记，参见 {@link #apply(Node, AppTheme)}。</p>
 *
 * <p>线程安全：Preferences 本身线程安全；{@link #apply(Node, AppTheme)}
 * 只能在 JavaFX 线程调用。</p>
 */
public final class ThemeService {

    /** 深色主题在根节点上的样式类 */
    public static final String DARK_STYLE_CLASS = "theme-dark";

    /**
     * 色板锚点样式类：色板定义在 {@code .theme-root} 上，
     * 子节点才能通过属性名查到颜色。
     */
    public static final String ROOT_STYLE_CLASS = "theme-root";

    /** Preferences 存储键：主题枚举名 */
    private static final String KEY_THEME = "ui.theme";

    /** Java Preferences 存储后端 */
    private final Preferences prefs;

    /**
     * 使用 Kelly 应用的默认 Preferences 节点。
     */
    public ThemeService() {
        this(Preferences.userNodeForPackage(Kelly.class));
    }

    /**
     * 使用指定的 Preferences 节点（便于测试注入）。
     *
     * @param prefs Preferences 存储后端
     */
    public ThemeService(Preferences prefs) {
        this.prefs = prefs;
    }

    /**
     * 从 Preferences 读取当前主题。
     *
     * <p>容错处理：存储的字符串不是合法枚举名时回退到亮色。</p>
     *
     * @return 保存的主题，没有保存过则返回 {@link AppTheme#LIGHT}
     */
    public AppTheme load() {
        try {
            return AppTheme.valueOf(prefs.get(KEY_THEME, AppTheme.LIGHT.name()));
        } catch (IllegalArgumentException e) {
            return AppTheme.LIGHT;  // 回退到默认值
        }
    }

    /**
     * 把主题保存到 Preferences（跨会话保留）。
     *
     * @param theme 要保存的主题
     */
    public void save(AppTheme theme) {
        prefs.put(KEY_THEME, theme.name());
    }

    /**
     * 给色板锚点节点装载色板样式表。
     *
     * <p>色板定义在 {@code .theme-root} 上，所以必须挂在色板锚点节点
     * （通常是场景根节点）上，子节点才能通过属性名查到颜色。</p>
     *
     * <p>锚点节点是容器（{@link Parent}）时挂到节点上；否则退而挂到所属 Scene 上。</p>
     *
     * @param root 色板锚点节点
     */
    public static void installStylesheets(Node root) {
        URL theme = ThemeService.class.getResource("theme.css");
        URL dark = ThemeService.class.getResource("dark.css");
        if (root instanceof Parent parent) {
            addStylesheet(parent.getStylesheets(), theme);
            addStylesheet(parent.getStylesheets(), dark);
        } else if (root.getScene() != null) {
            addStylesheet(root.getScene().getStylesheets(), theme);
            addStylesheet(root.getScene().getStylesheets(), dark);
        }
    }

    /**
     * 应用主题到色板锚点节点：装载色板并切换 {@code theme-dark} 样式类。
     *
     * <p>除了场景根节点，任何「自带独立 Scene 的弹层」（例如 ComboBox 的下拉列表）
     * 也可以调用本方法把自己变成色板锚点，从而取到同一套颜色。</p>
     *
     * @param root  色板锚点节点
     * @param theme 要应用的主题
     */
    public static void apply(Node root, AppTheme theme) {
        installStylesheets(root);
        if (!root.getStyleClass().contains(ROOT_STYLE_CLASS)) {
            root.getStyleClass().add(ROOT_STYLE_CLASS);
        }
        if (theme == AppTheme.DARK) {
            if (!root.getStyleClass().contains(DARK_STYLE_CLASS)) {
                root.getStyleClass().add(DARK_STYLE_CLASS);
            }
        } else {
            root.getStyleClass().remove(DARK_STYLE_CLASS);
        }
    }

    /**
     * 幂等地给节点追加样式类。
     *
     * <p>为什么不能直接调 {@code getStyleClass().add(...)}：JavaFX 的样式类列表
     * 在内容变化时会回调 {@link Node#reapplyCSS()} 触发整棵子树重刷 CSS，
     * 而 {@code add} 并不去重。{@link javafx.scene.control.ListCell} 这类
     * <strong>复用</strong>的控件每次 {@code updateItem} 都会走这段代码，
     * 于是同一个样式类被重复添加几千次——列表越长，CSS 匹配越慢，
     * 最终表现为「下拉框一展开就卡死」。</p>
     *
     * <p>本方法先查重，保证重复调用只产生一次 CSS 重刷。</p>
     *
     * @param node      目标节点
     * @param styleClass 样式类名
     */
    public static void addStyleClass(Node node, String styleClass) {
        if (!node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        }
    }

    /**
     * 读取某个场景当前生效的主题。
     *
     * @param sceneRoot 场景根节点
     * @return 当前主题，未标记深色时为 {@link AppTheme#LIGHT}
     */
    public static AppTheme currentOf(Node sceneRoot) {
        return sceneRoot.getStyleClass().contains(DARK_STYLE_CLASS)
                ? AppTheme.DARK
                : AppTheme.LIGHT;
    }

    /**
     * 追加样式表（已存在则跳过）。
     *
     * @param sheets 样式表列表
     * @param url    样式表资源地址，可以为 {@code null}
     */
    private static void addStylesheet(ObservableList<String> sheets, URL url) {
        if (url == null) {
            return;
        }
        String external = url.toExternalForm();
        if (!sheets.contains(external)) {
            sheets.add(external);
        }
    }
}
