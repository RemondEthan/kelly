package com.mordor.kelly.ui;

import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;

/**
 * 圆形头像组件。
 *
 * <p>继承自 {@link StackPane}，利用 JavaFX 的<strong>场景图（Scene Graph）</strong>机制：
 * StackPane 将子节点层叠放置，通过 {@link #setClip} 设置圆形裁剪区域实现圆形头像效果。</p>
 *
 * <h3>渲染策略</h3>
 * <ul>
 *   <li>如果提供了有效的头像图片，则以 <b>cover</b> 模式裁剪为整圆显示</li>
 *   <li>如果没有图片或图片加载失败，则显示占位圆形背景 + 名字首字母/自定义占位文字</li>
 * </ul>
 *
 * <h3>JavaFX 属性绑定说明</h3>
 * <p>{@code clip.centerXProperty().bind(widthProperty().divide(2))} 表示裁剪圆心 X 坐标
 * 与组件宽度的一半<strong>实时绑定</strong>——当组件尺寸变化时圆心自动跟随调整，
 * 这是 JavaFX 的<strong>响应式属性绑定</strong>机制。</p>
 *
 * @param name     用户名，用于生成首字母占位
 * @param photo    头像图片，可为 null
 * @param self     是否为当前用户（影响占位背景色样式）
 * @param size     头像显示尺寸（宽高相同）
 * @param emptyText 自定义占位文字，为 null 时自动取名字首字母
 */
public class AvatarView extends StackPane {

    /**
     * 简化构造：不指定自定义占位文字，自动使用名字首字母。
     *
     * @param name  用户名
     * @param photo 头像图片
     * @param self  是否为当前用户
     * @param size  显示尺寸
     */
    public AvatarView(String name, Image photo, boolean self, double size) {
        this(name, photo, self, size, null);
    }

    /**
     * 完整构造：创建圆形头像组件。
     *
     * <p>流程：
     * 1. 设置 CSS 样式类和固定尺寸
     * 2. 创建圆形 {@link Circle} 作为裁剪蒙版，并通过属性绑定使圆心始终位于组件中心
     * 3. 判断图片是否可用：可用则以 cover 模式缩放显示；不可用则绘制圆形背景 + 占位文字</p>
     *
     * @param name      用户名
     * @param photo     头像图片
     * @param self      是否为当前用户
     * @param size      显示尺寸（px）
     * @param emptyText 自定义占位文字
     */
    public AvatarView(String name, Image photo, boolean self, double size, String emptyText) {
        // 添加 CSS 样式类，方便外部通过 CSS 控制外观
        getStyleClass().add("avatar-view");
        // 固定宽高：min/max/pref 都设为同一值，确保组件不被布局拉伸
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);

        // 创建圆形裁剪蒙版
        double radius = size / 2;
        Circle clip = new Circle(radius);
        // 属性绑定：圆心 X = 组件宽度 / 2，圆心 Y = 组件高度 / 2
        // bind() 建立双向响应式连接，尺寸变化时自动重新计算
        clip.centerXProperty().bind(widthProperty().divide(2));
        clip.centerYProperty().bind(heightProperty().divide(2));
        setClip(clip);  // setClip 将内容裁剪为圆形

        // 判断图片是否有效（非 null、未出错、宽高 > 0）
        if (photo != null && !photo.isError() && photo.getWidth() > 0 && photo.getHeight() > 0) {
            // cover 模式：取宽高缩放比中较大的值，确保图片完全覆盖圆形区域
            ImageView view = new ImageView(photo);
            view.setPreserveRatio(true);   // 保持宽高比
            view.setSmooth(true);          // 启用平滑缩放（双线性插值）
            double scale = Math.max(size / photo.getWidth(), size / photo.getHeight());
            view.setFitWidth(photo.getWidth() * scale);
            view.setFitHeight(photo.getHeight() * scale);
            getChildren().add(view);  // 添加到 StackPane 场景图中
            return;
        }

        // 无图片时的回退方案：圆形背景 + 占位文字
        Circle bg = new Circle(radius);
        // 根据是否为当前用户选择不同的 CSS 样式（不同背景色）
        bg.getStyleClass().add(self ? "avatar-fallback-self" : "avatar-fallback");
        Label initial = new Label(placeholder(name, emptyText));
        // 根据是自定义文字还是自动生成的首字母选择不同样式
        initial.getStyleClass().add(emptyText == null || emptyText.isBlank()
                ? "avatar-initial" : "avatar-placeholder");
        // StackPane 层叠：背景圆在下，文字在上
        getChildren().addAll(bg, initial);
    }

    /**
     * 计算占位显示文字。
     *
     * @param name      用户名
     * @param emptyText 自定义占位文字
     * @return 显示的占位文字
     */
    private static String placeholder(String name, String emptyText) {
        if (emptyText != null && !emptyText.isBlank()) {
            return emptyText;
        }
        if (name == null || name.isBlank()) {
            return "?";  // 无名用户显示问号
        }
        return name.substring(0, 1);  // 取名字首字母
    }
}
