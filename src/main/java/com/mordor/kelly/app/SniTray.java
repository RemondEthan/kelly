package com.mordor.kelly.app;

import javafx.stage.Stage;

import org.freedesktop.dbus.ObjectPath;
import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.DBusBoundProperty;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.interfaces.Properties.PropertiesChanged;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Linux 桌面的 StatusNotifierItem (SNI/AppIndicator) 托盘后端。
 *
 * <p>为什么需要它：AWT 的 {@link java.awt.SystemTray} 走 XEmbed 托盘协议，
 * GNOME 3.26 起原生不支持，Wayland 会话下即使装了 AppIndicator 扩展，
 * XEmbed 图标也永远无法嵌入（实测 {@code _NET_SYSTEM_TRAY_S0} 无人持有，
 * {@code add()} 静默失败、窗口一直 IsUnMapped）。AppIndicator 扩展只认
 * StatusNotifierItem 协议，所以 Linux 上直接在会话总线上导出 SNI 对象。
 *
 * <p>注册流程：
 * <ol>
 *   <li>连接会话总线，确认存在 org.kde.StatusNotifierWatcher（GNOME 的
 *       AppIndicator 扩展 / KDE plasma 都提供该服务）</li>
 *   <li>在 /StatusNotifierItem 导出属性对象（实现 org.freedesktop.DBus.Properties），
 *       在 /MenuBar 导出 com.canonical.dbusmenu 菜单对象</li>
 *   <li>用本连接的唯一名调用 Watcher.RegisterStatusNotifierItem(":1.x")；
 *       扩展会反查名字持有者并按固定路径 /StatusNotifierItem 内省</li>
 * </ol>
 *
 * <p>闪烁：UnreadAlert 的 BlinkTimer 每 500ms 调 {@link #setIconImage}，
 * 这里按传入 AWT Image 的身份缓存已编码像素（正常/提醒各一份），
 * 更新后发 PropertiesChanged(IconPixmap)，面板立刻换图。
 *
 * <p>退出清理：进程 halt 后总线名字消失，扩展会自动移除图标，
 * 无需显式反注册（QuitManager 的 AWT 清理路径在 SNI 模式下本来就跳过）。
 *
 * <p>线程安全：D-Bus 回调在 dbus-java 的接收线程上，菜单动作经
 * {@link FxStageSupport} 跳线程进入 JavaFX；sendMessage 线程安全。
 */
final class SniTray {

    /** 面板可能用到的图标尺寸，多给几组让面板按 DPI 挑 */
    private static final int[] ICON_SIZES = {16, 22, 24, 32, 48};
    /** watcher 内省 SNI 对象的固定路径（扩展的 DEFAULT_ITEM_OBJECT_PATH） */
    private static final String ITEM_PATH = "/StatusNotifierItem";
    private static final String MENU_PATH = "/MenuBar";
    private static final String ITEM_IFACE = "org.kde.StatusNotifierItem";
    private static final String WATCHER_NAME = "org.kde.StatusNotifierWatcher";

    /** D-Bus 会话连接，install 成功后非空 */
    private final DBusConnection bus;
    /** 恢复窗口用 */
    private final Stage stage;
    /** 托盘菜单"退出"，由 TrayManager 转发 QuitManager 回调 */
    private final Supplier<Runnable> onQuit;
    /** 已导出的属性对象（保留引用防止 GC，dbus-java 导出表是弱引用） */
    private final Item item;
    /** 已导出的菜单对象 */
    private final Menu menu;
    /** Image 身份 → 编码好的多尺寸像素，UnreadAlert 固定交替两张图，命中后零分配 */
    private final Map<java.awt.Image, List<SniPixmap>> encodeCache = new ConcurrentHashMap<>();

    private SniTray(DBusConnection bus, Stage stage, Supplier<Runnable> onQuit) {
        this.bus = bus;
        this.stage = stage;
        this.onQuit = onQuit;
        this.item = new Item(bus, stage, loadPixmaps("/icons/kelly.png"));
        this.menu = new Menu(
                () -> FxStageSupport.show(stage),
                () -> FxStageSupport.hide(stage),
                () -> onQuit.get().run());
    }

    /**
     * 连接总线并注册 SNI。任何一步失败都返回 null，调用方回退 AWT 后端。
     *
     * @param stage  主窗口，菜单"打开/隐藏"用
     * @param onQuit 菜单"退出"回调供应商（TrayManager.onQuit 可能稍后才被 set）
     * @return 后端实例；无会话总线 / 无 watcher / 注册失败时为 null
     */
    static SniTray install(Stage stage, Supplier<Runnable> onQuit) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return null;
        }
        DBusConnection bus = null;
        try {
            bus = DBusConnectionBuilder.forSessionBus().build();
            DBus freedesktop = bus.getRemoteObject(
                    "org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class, false);
            boolean watcherOnline = false;
            for (String name : freedesktop.ListNames()) {
                if (WATCHER_NAME.equals(name)) {
                    watcherOnline = true;
                    break;
                }
            }
            if (!watcherOnline) {
                System.err.println("[Tray] 无 " + WATCHER_NAME + "（AppIndicator 扩展未启用?），回退 AWT 托盘");
                bus.disconnect();
                return null;
            }
            SniTray tray = new SniTray(bus, stage, onQuit);
            bus.exportObject(ITEM_PATH, tray.item);
            bus.exportObject(MENU_PATH, tray.menu);
            Watcher watcher = bus.getRemoteObject(WATCHER_NAME, "/StatusNotifierWatcher", Watcher.class, false);
            watcher.RegisterStatusNotifierItem(bus.getUniqueName());
            System.out.println("[Tray] StatusNotifierItem 已注册 (" + bus.getUniqueName() + ITEM_PATH + ")");
            return tray;
        } catch (Throwable e) {
            System.err.println("[Tray] SNI 托盘不可用: " + e.getMessage() + "，回退 AWT 托盘");
            if (bus != null) {
                try {
                    bus.disconnect();
                } catch (Throwable ignored) {
                    // 回退路径，清理失败不影响主流程
                }
            }
            return null;
        }
    }

    /**
     * 切换托盘像素图。按 Image 身份缓存编码结果；
     * 未知图像（理论上来不到）现编一次也接受。
     *
     * @param image UnreadAlert 传入的正常/提醒 AWT 图
     */
    void setIconImage(java.awt.Image image) {
        List<SniPixmap> pixmaps = encodeCache.computeIfAbsent(image,
                img -> SniPixmap.encodeAll(SniPixmap.toBuffered(img), ICON_SIZES));
        item.updatePixmaps(pixmaps);
    }

    /**
     * 加载 classpath 图标并编码为多尺寸 SNI 像素。
     *
     * @param path 图标资源路径
     * @return 编码结果；资源缺失时返回空列表（面板显示占位）
     */
    private static List<SniPixmap> loadPixmaps(String path) {
        try (InputStream is = SniTray.class.getResourceAsStream(path)) {
            if (is == null) {
                System.err.println("[Tray] 找不到 " + path);
                return List.of();
            }
            BufferedImage img = ImageIO.read(is);
            return img == null ? List.of() : SniPixmap.encodeAll(img, ICON_SIZES);
        } catch (Exception e) {
            System.err.println("[Tray] 加载图标失败: " + e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // D-Bus 接口定义（方法名即 D-Bus member 名，签名由参数类型推导）
    // ------------------------------------------------------------------

    /** org.kde.StatusNotifierItem 上的主动作方法（面板点击/激活时调用） */
    @DBusInterfaceName(ITEM_IFACE)
    public interface ItemIface extends DBusInterface {
        void Activate(int x, int y);

        void SecondaryActivate(int x, int y);

        void Scroll(int delta, String orientation);
    }

    /** org.kde.StatusNotifierWatcher，仅需注册方法 */
    @DBusInterfaceName(WATCHER_NAME)
    public interface Watcher extends DBusInterface {
        void RegisterStatusNotifierItem(String service);
    }

    /** com.canonical.dbusmenu 菜单协议（dbusMenu.js 客户端实际用到的子集） */
    @DBusInterfaceName("com.canonical.dbusmenu")
    public interface MenuIface extends DBusInterface {
        /**
         * 返回 (版本 u, 节点 (ia{sv}av))。
         * 注意：dbus-java 会把直接返回的 Struct 拆成多个返回参数
         * （u 与 (ia{sv}av)），但 D-Bus 线路上字节布局与单个结构体
         * "(u(ia{sv}av))" 完全一致，客户端按规范签名解析不受影响。
         */
        MenuLayout GetLayout(int parentId, int recursionDepth, String[] propertyNames);

        /** 批量取节点属性，返回 a(ia{sv})；ids 为空表示全部 */
        List<ItemProps> GetGroupProperties(int[] ids, String[] propertyNames);

        /** 用户点击：eventId 为 "clicked" */
        void Event(int id, String eventId, Variant data, UInt32 timestamp);

        /** 返回 false 表示布局自上次拉取后未变化 */
        boolean AboutToShow(int id);
    }

    /** 菜单树节点 (ia{sv}av)。children 用数组：dbus-java 内省不接受 List&lt;Variant&lt;Struct&gt;&gt; 字段 */
    public static final class MenuNode extends Struct {
        @Position(0)
        public final int id;
        @Position(1)
        public final Map<String, Variant> properties;
        @Position(2)
        public final Variant<MenuNode>[] children;

        public MenuNode(int id, Map<String, Variant> properties, Variant<MenuNode>[] children) {
            this.id = id;
            this.properties = properties;
            this.children = children;
        }
    }

    /** GetLayout 返回 (u (ia{sv}av)) */
    public static final class MenuLayout extends Struct {
        @Position(0)
        public final UInt32 revision;
        @Position(1)
        public final MenuNode root;

        public MenuLayout(UInt32 revision, MenuNode root) {
            this.revision = revision;
            this.root = root;
        }
    }

    /** 节点属性对 (ia{sv})，GetGroupProperties 的元素 / GetItemProperties 的返回 */
    public static final class ItemProps extends Struct {
        @Position(0)
        public final int id;
        @Position(1)
        public final Map<String, Variant> properties;

        public ItemProps(int id, Map<String, Variant> properties) {
            this.id = id;
            this.properties = properties;
        }
    }

    /** SNI ToolTip 属性 (sa(iiay)ss) */
    public static final class SniToolTip extends Struct {
        @Position(0)
        public final String iconName;
        @Position(1)
        public final List<SniPixmap> pixmaps;
        @Position(2)
        public final String title;
        @Position(3)
        public final String description;

        public SniToolTip(String iconName, List<SniPixmap> pixmaps, String title, String description) {
            this.iconName = iconName;
            this.pixmaps = pixmaps;
            this.title = title;
            this.description = description;
        }
    }

    // ------------------------------------------------------------------
    // 导出对象
    // ------------------------------------------------------------------

    /**
     * /StatusNotifierItem：手动实现 org.freedesktop.DBus.Properties。
     * 不用 @DBusBoundProperty：5.2.2 的 GetAll 反射派发有
     * "object is not an instance of declaring class" 缺陷。public static：
     * dbus-java 跨模块反射需要公开类。
     */
    public static final class Item implements ItemIface, Properties {

        private volatile List<SniPixmap> pixmaps;
        private final DBusConnection bus;
        private final Stage stage;

        Item(DBusConnection bus, Stage stage, List<SniPixmap> initial) {
            this.bus = bus;
            this.stage = stage;
            this.pixmaps = initial;
        }

        void updatePixmaps(List<SniPixmap> fresh) {
            this.pixmaps = fresh;
            try {
                // 网络层立刻推送，面板免轮询换图
                bus.sendMessage(new PropertiesChanged(ITEM_PATH, ITEM_IFACE,
                        Map.of("IconPixmap", new Variant<>(fresh, "a(iiay)")), List.of()));
            } catch (DBusException e) {
                System.err.println("[Tray] PropertiesChanged 发送失败: " + e.getMessage());
            }
        }

        @Override
        public Map<String, Variant<?>> GetAll(String interfaceName) {
            return interfaceName.equals(ITEM_IFACE) ? properties() : Map.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <A> A Get(String interfaceName, String propertyName) {
            if (!interfaceName.equals(ITEM_IFACE)) {
                return null;
            }
            Variant<?> v = properties().get(propertyName);
            return v == null ? null : (A) v.getValue();
        }

        @Override
        public <A> void Set(String interfaceName, String propertyName, A value) {
            // 面板不会回写 SNI 属性，忽略
        }

        /** 全部 SNI 属性。IconName 留空让面板直接用 IconPixmap。 */
        private Map<String, Variant<?>> properties() {
            Map<String, Variant<?>> m = new LinkedHashMap<>();
            m.put("Id", new Variant<>("kelly"));
            m.put("Category", new Variant<>("ApplicationStatus"));
            m.put("Status", new Variant<>("Active"));
            m.put("Title", new Variant<>("Kelly"));
            m.put("WindowId", new Variant<>(new UInt32(0)));
            m.put("IconName", new Variant<>(""));
            m.put("IconPixmap", new Variant<>(pixmaps, "a(iiay)"));
            m.put("AttentionIconName", new Variant<>(""));
            m.put("AttentionIconPixmap", new Variant<>(List.of(), "a(iiay)"));
            m.put("OverlayIconName", new Variant<>(""));
            m.put("OverlayIconPixmap", new Variant<>(List.of(), "a(iiay)"));
            m.put("ToolTip", new Variant<>(new SniToolTip("", List.of(), "Kelly", ""), "(sa(iiay)ss)"));
            m.put("Menu", new Variant<>(new ObjectPath(bus.getUniqueName(), MENU_PATH)));
            m.put("ItemIsMenu", new Variant<>(true));
            return m;
        }

        @Override
        public void Activate(int x, int y) {
            FxStageSupport.show(stage);
        }

        @Override
        public void SecondaryActivate(int x, int y) {
            FxStageSupport.show(stage);
        }

        @Override
        public void Scroll(int delta, String orientation) {
            // 无操作
        }

        @Override
        public String getObjectPath() {
            return ITEM_PATH;
        }
    }

    /**
     * /MenuBar：静态三项目菜单，与 AWT 托盘菜单一致。
     * 布局固定（revision 1），客户端 AboutToShow 恒 false 即可。
     */
    public static final class Menu implements MenuIface {

        static final int ROOT = 0;
        static final int OPEN = 1;
        static final int HIDE = 2;
        static final int SEPARATOR = 3;
        static final int QUIT = 4;
        static final UInt32 REVISION = new UInt32(1);

        private final Runnable open;
        private final Runnable hide;
        private final Runnable quit;

        Menu(Runnable open, Runnable hide, Runnable quit) {
            this.open = open;
            this.hide = hide;
            this.quit = quit;
        }

        /** 单节点属性。client 默认 type=standard/visible/enabled，这里显式给全。 */
        static Map<String, Variant> propsOf(int id) {
            Map<String, Variant> m = new LinkedHashMap<>();
            if (id == ROOT) {
                m.put("children-display", new Variant<>("submenu"));
                m.put("label", new Variant<>("Kelly"));
                m.put("enabled", new Variant<>(true));
                m.put("visible", new Variant<>(true));
                return m;
            }
            m.put("enabled", new Variant<>(true));
            m.put("visible", new Variant<>(true));
            if (id == SEPARATOR) {
                m.put("type", new Variant<>("separator"));
                m.put("label", new Variant<>(""));
                return m;
            }
            m.put("type", new Variant<>("standard"));
            m.put("label", new Variant<>(switch (id) {
                case OPEN -> "打开 Kelly";
                case HIDE -> "隐藏窗口";
                default -> "退出";
            }));
            return m;
        }

        /** id → 自身节点；children 仅根节点有 */
        @SuppressWarnings("unchecked")
        private MenuNode node(int id, int depth) {
            Variant<MenuNode>[] children = new Variant[0];
            if (id == ROOT && depth != 0) {
                int[] ids = {OPEN, HIDE, SEPARATOR, QUIT};
                children = new Variant[ids.length];
                for (int i = 0; i < ids.length; i++) {
                    children[i] = new Variant<>(node(ids[i], depth - 1));
                }
            }
            return new MenuNode(id, propsOf(id), children);
        }

        @Override
        public MenuLayout GetLayout(int parentId, int recursionDepth, String[] propertyNames) {
            int depth = recursionDepth < 0 ? Integer.MAX_VALUE : recursionDepth;
            return new MenuLayout(REVISION, node(parentId, depth));
        }

        @Override
        public List<ItemProps> GetGroupProperties(int[] ids, String[] propertyNames) {
            int[] wanted = ids == null || ids.length == 0
                    ? new int[]{ROOT, OPEN, HIDE, SEPARATOR, QUIT}
                    : ids;
            List<ItemProps> out = new ArrayList<>(wanted.length);
            for (int id : wanted) {
                out.add(new ItemProps(id, propsOf(id)));
            }
            return out;
        }

        @Override
        public void Event(int id, String eventId, Variant data, UInt32 timestamp) {
            if (!"clicked".equals(eventId)) {
                return;
            }
            try {
                switch (id) {
                    case OPEN -> open.run();
                    case HIDE -> hide.run();
                    case QUIT -> quit.run();
                    default -> {
                        // 分隔线等不可点击项
                    }
                }
            } catch (Throwable e) {
                System.err.println("[Tray] 菜单动作失败: " + e.getMessage());
            }
        }

        @Override
        public boolean AboutToShow(int id) {
            return false;
        }

        @Override
        public String getObjectPath() {
            return MENU_PATH;
        }
    }
}
