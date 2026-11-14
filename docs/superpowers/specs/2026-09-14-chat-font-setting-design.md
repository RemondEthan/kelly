# Chat Font & Size Setting — Design

**Date:** 2026-09-14
**Status:** Approved (pending implementation plan)
**Owner:** user

## 1. Background

`kelly` 聊天主界面目前所有文字走系统默认字体 + 硬编码字号。`app.css:12` 已经写了一份跨平台 fallback chain (`"SF Pro Text", "PingFang SC", "Microsoft YaHei", sans-serif`)，但用户不能修改；`chat.css` 内 30+ 处 `-fx-font-size` 全部写死。

需要新增一个用户级的字体和字号调整入口，作用于聊天主界面（不含登录页 / 不含弹层 / 不含原生菜单）。

## 2. Goals

- 用户能在聊天顶栏一键打开设置对话框。
- 用户能选择系统已装字体（从 `Font.getFamilies()` 下拉）。
- 用户能选择 4 档字号预设：小/中/大/特大。
- 选择结果跨重启保留。

## 3. Non-Goals

- 不支持用户从 `.ttf` 文件导入字体。
- 不支持登录页字体调整。
- 不支持字体粗细、行高、字间距等扩展属性。
- 不在聊天主界面之外的任何 UI 应用本设置。
- 不做设置预览面板（设置对话框用系统默认字号渲染，避免反馈循环）。

## 4. Scope

仅 `ChatPane` 子树：

| 子组件 | 是否跟随 |
|--------|----------|
| MessageListView（聊天气泡 Text） | ✅ |
| ChatHeader（顶栏） | ✅ |
| RoomMemberList（左侧成员） | ✅ |
| InputBar（输入栏） | ✅ |
| KnowledgePane（知识库面板，开启时） | ✅ |

不跟随：

- LoginPane
- Alert / EmojiPopover / MentionPopover 等弹层
- TrayManager 任务栏原生菜单（AWT 渲染，不走 CSS）
- 标题栏 OS 渲染部分

## 5. Data Model

```java
public enum ChatFontScale {
    SMALL(0.9), MEDIUM(1.0), LARGE(1.1), XLARGE(1.2);
    private final double factor;
    public double factor() { return factor; }
}

public record ChatFontSettings(String fontFamily, ChatFontScale scale) {
    public static ChatFontSettings defaults() {
        return new ChatFontSettings(null, ChatFontScale.MEDIUM);
    }
}
```

`fontFamily == null` 表示使用 `app.css:12` 的 fallback chain。

## 6. Persistence

`ChatFontSettingsService`，基于 `java.util.prefs.Preferences`，与 `SaveLastLoginService` 同包、同位置。

| Key | Type | Default |
|-----|------|---------|
| `chat.font.family` | String | (unset → null) |
| `chat.font.scale` | String enum name | `MEDIUM` |

读：

```java
public ChatFontSettings load() {
    String family = prefs.get(KEY_FAMILY, null);
    ChatFontScale scale;
    try {
        scale = ChatFontScale.valueOf(prefs.get(KEY_SCALE, "MEDIUM"));
    } catch (IllegalArgumentException e) {
        scale = ChatFontScale.MEDIUM;
    }
    return new ChatFontSettings(
            (family == null || family.isBlank()) ? null : family,
            scale);
}
```

写：`save(ChatFontSettings)`，`null` 家族 → `prefs.remove(KEY_FAMILY)`。

## 7. Application Layer

`ChatFontApplier`，唯一入口 `apply(Node root, ChatFontSettings s)`。

### 7.1 字体（root 继承）

```java
if (s.fontFamily() != null) {
    String escaped = s.fontFamily().replace("'", "''");
    root.setStyle("-fx-font-family: '" + escaped + "'");
}
```

JavaFX 的 `-fx-font-family` 是继承属性，root 设一次即传播到所有未显式覆盖的子节点。

### 7.2 字号（DFS 遍历 setStyle）

```java
private static void walk(Node node, double factor) {
    try {
        if (node instanceof Labeled l && l.getFont() != null) {
            double base = l.getFont().getSize();
            if (base > 0) l.setStyle("-fx-font-size: " + (base * factor) + "px;");
        } else if (node instanceof TextInputControl t && t.getFont() != null) {
            double base = t.getFont().getSize();
            if (base > 0) t.setStyle("-fx-font-size: " + (base * factor) + "px;");
        } else if (node instanceof Text tx && tx.getFont() != null) {
            double base = tx.getFont().getSize();
            if (base > 0) tx.setStyle("-fx-font-size: " + (base * factor) + "px;");
        }
    } catch (Exception ignore) {
        // 单节点失败不影响兄弟节点
    }
    if (node instanceof Parent p) {
        for (Node child : p.getChildrenUnmodifiable()) walk(child, factor);
    }
}
```

### 7.3 前置条件：chat.css 删字号

**`chat.css` 中所有 `-fx-font-size` 规则必须先删掉**，否则 `l.getFont().getSize()` 取到的是 CSS 注入值，缩放系数会叠加错乱。

具体动作：保留 layout / color / `-fx-icon-size` / padding / background；删除约 30 处 `-fx-font-size: Npx;` 行。

## 8. UI Entry

`ChatHeader` 右上角新增一个 FontIcon 按钮（`MaterialDesignF.FORMAT_FONT`），点击 → 调用 `FontSettingsDialog.show(...)`。完整签名见第 10 节。

## 9. Settings Dialog

`FontSettingsDialog`，独立 Stage。

| 属性 | 值 |
|------|------|
| 尺寸 | `setWidth(420)` + `setHeight(220)` + `setResizable(false)` |
| 模态 | `Modality.APPLICATION_MODAL` |
| Owner | 主 Stage |
| 关闭行为 | 应用 → 关闭并落盘；取消 → 关闭不落盘；ESC → 等同取消 |

布局（自上而下）：

1. `Label("字体：")` + `ComboBox<String> fontBox`（项 = `Font.getFamilies()`）
2. `Label("字号：")` + 4 个 `RadioButton`（小 / 中 / 大 / 特大）+ `ToggleGroup`
3. 按钮行：`Button("应用")` + `Button("取消")`

"应用"按钮的完整动作：

1. 构造新 `ChatFontSettings(fontBox.getValue(), selectedScale)`。
2. `service.save(newSettings)`。
3. `ChatFontApplier.apply(chatRoot, newSettings)`（`chatRoot` 由调用方传入）。
4. `dialog.close()`。

默认选中：

- 字体：当前设置；若 `null`，选 `Font.getFamilies()[0]`
- 字号：当前设置；首次进入选 `MEDIUM`

## 10. Wiring

`ChatPane` 构造末尾追加：

```java
ChatFontSettingsService fontService = new ChatFontSettingsService();
ChatFontApplier.apply(this, fontService.load());
```

`ChatHeader` 持有 `chatRoot`（即 `ChatPane`）与 `fontService` 引用，新增按钮回调：

```java
FontIcon button = new FontIcon(MaterialDesignF.FORMAT_FONT);
button.setOnMouseClicked(e ->
    FontSettingsDialog.show(stage, fontService.load(), chatRoot, fontService));
```

签名：`FontSettingsDialog.show(Stage owner, ChatFontSettings current, Node chatRoot, ChatFontSettingsService service)`。

## 11. Error Handling

| 场景 | 处理 |
|------|------|
| Preferences 读取失败 | `Diagnostics.warn` + 用 `ChatFontSettings.defaults()` |
| Preferences 写入失败 | `Diagnostics.warn` + 静默；UI 仍按内存值显示 |
| `getFont()` 返回 null | 跳过该节点 |
| 用户选了一个不在 `getFamilies()` 列表里的字体 | JavaFX 自动 fallback 到 system default（理论上 ComboBox 不会出现此情况） |
| `walk()` 内部抛异常 | `try/catch` 包住，递归继续 |

## 12. Testing

### 12.1 单元测试

| 测试类 | 验证点 |
|--------|--------|
| `ChatFontScaleTest` | 4 档 `factor()` 返回值（0.9 / 1.0 / 1.1 / 1.2） |
| `ChatFontSettingsServiceTest` | round-trip：save 后 load 取回相同值；损坏枚举名 → 回退到 MEDIUM |
| `ChatFontApplierTest` | 在小型 Scene 上构造 Labeled/TextInputControl/Text，断言 setStyle 被正确调用 |

### 12.2 集成（手动）

- 启动项目，登录进入聊天
- 点顶栏"字体设置"图标 → 对话框打开
- 选字体 + 切档 → 点应用 → 聊天界面立刻变化
- 重启 → 设置保留
- 切回登录页 → 字号不变（验证 scope 正确）

## 13. Files Changed

新增：

- `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontScale.java`
- `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontSettings.java`
- `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsService.java`
- `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontApplier.java`
- `src/main/java/com/mordor/kelly/ui/chat/font/FontSettingsDialog.java`
- `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontScaleTest.java`
- `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsServiceTest.java`
- `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontApplierTest.java`

修改：

- `src/main/resources/com/mordor/kelly/ui/chat/chat.css`（删除所有 `-fx-font-size`）
- `src/main/java/com/mordor/kelly/ui/chat/ChatPane.java`（构造末尾追加 apply 调用）
- `src/main/java/com/mordor/kelly/ui/chat/ChatHeader.java`（新增 FontIcon 按钮）

## 14. Open Risks

| 风险 | 缓解 |
|------|------|
| `walk()` 漏掉某类带字体的 Node（罕见自定义控件） | 该节点会保留 JavaFX 系统默认 12px，不影响主体 |
| chat.css 删除字号后某些控件视觉过小 / 过大 | 实施前扫一遍 chat.css 中所有 -fx-font-size 数值，作为 fallback；测试时人眼检查 |
| FontIcon 按钮位置与现有 ChatHeader 控件冲突 | 在 ChatHeader 右侧 HBox 末尾追加，不动现有布局 |
| 用户在 Linux 上系统未装任何中文 UI 字体 | 走 fallback chain 末端 `sans-serif`，由系统兜底 |

## 15. Implementation Order

1. 删 chat.css 中 `-fx-font-size`（30+ 处）
2. 新建 5 个生产代码类（数据/服务/应用/对话框）
3. 改 ChatPane / ChatHeader
4. 写 3 个单元测试
5. 手动验证