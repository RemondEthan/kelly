# Chat Font & Size Setting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a user-facing font family + 4-step font size control that applies to the ChatPane subtree and persists across restarts.

**Architecture:** Five new production classes in `com.mordor.kelly.ui.chat.font` (`ChatFontScale`, `ChatFontSettings`, `ChatFontSettingsService`, `ChatFontApplier`, `FontSettingsDialog`). Settings persist via `java.util.prefs.Preferences`. Application is done by walking the chat subtree and calling `setStyle("-fx-font-size: <base*factor>px")` for `Labeled`/`TextInputControl`/`Text`, plus `root.setStyle("-fx-font-family: '...'")` on the ChatPane root. Font family inherits via JavaFX CSS; size does not, hence the walk. Prerequisite: `chat.css` must have all `-fx-font-size` rules removed so `Node.getFont().getSize()` returns the unmodified default.

**Tech Stack:** Java 21, JavaFX 21.0.2 (OpenJFX), `java.util.prefs.Preferences`, Ikonli MaterialDesign2 (existing dep).

## Global Constraints

These come from the spec verbatim and apply to every task:

- **Scope:** Only the `ChatPane` subtree. Login page, popovers, AWT tray menu are not affected.
- **Enum values:** `ChatFontScale.SMALL = 0.9`, `MEDIUM = 1.0`, `LARGE = 1.1`, `XLARGE = 1.2`. Order is fixed.
- **Default settings:** `fontFamily = null`, `scale = MEDIUM`. `null` family means "fall through to app.css's existing chain `"SF Pro Text", "PingFang SC", "Microsoft YaHei", sans-serif`".
- **Persistence keys:** `chat.font.family` (String, unset = null), `chat.font.scale` (enum name, default `MEDIUM`).
- **Corrupt enum name on load:** fall back to `MEDIUM`; do not throw.
- **Service location:** `Preferences.userNodeForPackage(Kelly.class)`.
- **Settings dialog dimensions:** `setWidth(420)` + `setHeight(220)` + `setResizable(false)`.
- **Settings dialog modality:** `Modality.APPLICATION_MODAL`. ESC = cancel (close without saving).
- **Apply button sequence in dialog:** build new `ChatFontSettings` → `service.save(...)` → `ChatFontApplier.apply(chatRoot, newSettings)` → `dialog.close()`.
- **FontIcon to use:** `MaterialDesignF.FORMAT_FONT`.
- **Package for new classes:** `com.mordor.kelly.ui.chat.font`.
- **Prerequisite before any applier test:** all `-fx-font-size` rules removed from `chat.css`.

---

## File Structure

New files (all under `com.mordor.kelly.ui.chat.font`):

- `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontScale.java` — enum, four scale factors.
- `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontSettings.java` — record `(String fontFamily, ChatFontScale scale)` with `defaults()`.
- `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsService.java` — load/save around `Preferences`.
- `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontApplier.java` — `apply(Node root, ChatFontSettings)` entry point + private DFS `walk`.
- `src/main/java/com/mordor/kelly/ui/chat/font/FontSettingsDialog.java` — modal Stage with ComboBox, 4 RadioButtons, Apply/Cancel.

Tests:

- `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsTest.java`
- `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsServiceTest.java`
- `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontApplierTest.java`

Modified:

- `src/main/resources/com/mordor/kelly/ui/chat/chat.css` — delete all `-fx-font-size` rules.
- `src/main/java/com/mordor/kelly/ui/chat/ChatPane.java` — append `ChatFontApplier.apply(this, service.load())` at end of constructor.
- `src/main/java/com/mordor/kelly/ui/chat/ChatHeader.java` — add FontIcon button + click handler that calls `FontSettingsDialog.show(...)`.

---

## Task 1: Remove font-size rules from chat.css

**Files:**
- Modify: `src/main/resources/com/mordor/kelly/ui/chat/chat.css`

This is the prerequisite for `ChatFontApplier.walk` — if `-fx-font-size` rules remain, `Node.getFont().getSize()` returns the CSS-injected value and our `* factor` will compound on top of an already-scaled size.

- [ ] **Step 1: List current `-fx-font-size` occurrences**

Run: `grep -n "font-size" src/main/resources/com/mordor/kelly/ui/chat/chat.css`
Expected: ~30 lines like `    -fx-font-size: 14px;`.

- [ ] **Step 2: Delete every line containing `-fx-font-size`**

Use Edit tool with `replace_all = false` per occurrence, or a bulk delete. Delete the entire line including its leading 4-space indent. Do NOT touch other properties (`-fx-icon-size`, `-fx-padding`, colors, backgrounds).

Expected after: every line is gone, file structure (sections, comments, other properties) unchanged.

- [ ] **Step 3: Verify zero `-fx-font-size` lines remain**

Run: `grep -c "font-size" src/main/resources/com/mordor/kelly/ui/chat/chat.css`
Expected: `0`.

- [ ] **Step 4: Verify `-fx-icon-size` lines are still there (sanity)**

Run: `grep -c "icon-size" src/main/resources/com/mordor/kelly/ui/chat/chat.css`
Expected: at least 1.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/com/mordor/kelly/ui/chat/chat.css
git commit -m "refactor: remove font-size rules from chat.css

Prerequisite for ChatFontApplier: getFont().getSize() must return
the unmodified default. Sizes will be set at runtime by Java."
```

---

## Task 2: ChatFontScale enum + ChatFontSettings record

**Files:**
- Create: `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontScale.java`
- Create: `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontSettings.java`
- Create: `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsTest.java`

`ChatFontScale` carries the four factors. `ChatFontSettings` is a record with a `defaults()` factory.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsTest.java`:

```java
package com.mordor.kelly.ui.chat.font;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatFontSettingsTest {

    @Test
    void scaleFactors_areFixedPerSpec() {
        assertEquals(0.9, ChatFontScale.SMALL.factor(), 1e-9);
        assertEquals(1.0, ChatFontScale.MEDIUM.factor(), 1e-9);
        assertEquals(1.1, ChatFontScale.LARGE.factor(), 1e-9);
        assertEquals(1.2, ChatFontScale.XLARGE.factor(), 1e-9);
    }

    @Test
    void defaults_areMediumAndNullFamily() {
        ChatFontSettings d = ChatFontSettings.defaults();
        assertNull(d.fontFamily());
        assertEquals(ChatFontScale.MEDIUM, d.scale());
    }
}
```

- [ ] **Step 2: Run test to verify it fails (compile error)**

Run: `mvn -q -Dtest=ChatFontSettingsTest test`
Expected: compilation failure because `ChatFontScale` / `ChatFontSettings` don't exist yet.

- [ ] **Step 3: Create `ChatFontScale.java`**

Create `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontScale.java`:

```java
package com.mordor.kelly.ui.chat.font;

public enum ChatFontScale {
    SMALL(0.9),
    MEDIUM(1.0),
    LARGE(1.1),
    XLARGE(1.2);

    private final double factor;

    ChatFontScale(double factor) {
        this.factor = factor;
    }

    public double factor() {
        return factor;
    }
}
```

- [ ] **Step 4: Create `ChatFontSettings.java`**

Create `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontSettings.java`:

```java
package com.mordor.kelly.ui.chat.font;

public record ChatFontSettings(String fontFamily, ChatFontScale scale) {

    public static ChatFontSettings defaults() {
        return new ChatFontSettings(null, ChatFontScale.MEDIUM);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -Dtest=ChatFontSettingsTest test`
Expected: PASS, 2 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mordor/kelly/ui/chat/font/ChatFontScale.java \
        src/main/java/com/mordor/kelly/ui/chat/font/ChatFontSettings.java \
        src/test/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsTest.java
git commit -m "feat(font): add ChatFontScale enum and ChatFontSettings record"
```

---

## Task 3: ChatFontSettingsService

**Files:**
- Create: `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsService.java`
- Create: `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsServiceTest.java`

Loads/saves via `java.util.prefs.Preferences`. Constructor takes a `Preferences` (so tests can inject a temp one); convenience ctor uses `Preferences.userNodeForPackage(Kelly.class)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsServiceTest.java`:

```java
package com.mordor.kelly.ui.chat.font;

import org.junit.jupiter.api.Test;

import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

class ChatFontSettingsServiceTest {

    private static Preferences inMemoryPrefs() {
        return new AbstractPreferences(null, "") {
            @Override protected void putSpi(String k, String v) { }
            @Override protected String getSpi(String k) { return null; }
            @Override protected void removeSpi(String k) { }
            @Override protected void removeNodeSpi() { }
            @Override protected void flushSpi() { }
            @Override protected AbstractPreferences childSpi(String n) { return null; }
            @Override protected String[] childrenNamesSpi() { return new String[0]; }
            @Override protected String[] keysSpi() { return new String[0]; }
        };
    }

    @Test
    void load_emptyPrefs_returnsDefaults() {
        ChatFontSettingsService svc = new ChatFontSettingsService(inMemoryPrefs());
        ChatFontSettings s = svc.load();
        assertNull(s.fontFamily());
        assertEquals(ChatFontScale.MEDIUM, s.scale());
    }

    @Test
    void roundTrip_familyAndScale() {
        ChatFontSettingsService svc = new ChatFontSettingsService(inMemoryPrefs());
        svc.save(new ChatFontSettings("Microsoft YaHei", ChatFontScale.LARGE));
        ChatFontSettings loaded = svc.load();
        assertEquals("Microsoft YaHei", loaded.fontFamily());
        assertEquals(ChatFontScale.LARGE, loaded.scale());
    }

    @Test
    void save_nullFamily_removesKey() {
        ChatFontSettingsService svc = new ChatFontSettingsService(inMemoryPrefs());
        svc.save(new ChatFontSettings("Arial", ChatFontScale.SMALL));
        svc.save(new ChatFontSettings(null, ChatFontScale.SMALL));
        ChatFontSettings loaded = svc.load();
        assertNull(loaded.fontFamily());
        assertEquals(ChatFontScale.SMALL, loaded.scale());
    }

    @Test
    void load_corruptScaleName_fallsBackToMedium() {
        // We can't write to the in-memory prefs via public API easily, so use a real userRoot
        // with a unique sub-tree path to avoid polluting other tests.
        Preferences real = Preferences.userRoot().node("kelly-test-corrupt-" + System.nanoTime());
        try {
            real.put("chat.font.family", "Anything");
            real.put("chat.font.scale", "NOT_AN_ENUM");
            ChatFontSettingsService svc = new ChatFontSettingsService(real);
            ChatFontSettings s = svc.load();
            assertEquals(ChatFontScale.MEDIUM, s.scale());
            assertEquals("Anything", s.fontFamily());
        } finally {
            real.removeNode();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails (compile error)**

Run: `mvn -q -Dtest=ChatFontSettingsServiceTest test`
Expected: compilation failure because `ChatFontSettingsService` doesn't exist.

- [ ] **Step 3: Create `ChatFontSettingsService.java`**

Create `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsService.java`:

```java
package com.mordor.kelly.ui.chat.font;

import com.mordor.kelly.app.Kelly;

import java.util.prefs.Preferences;

public final class ChatFontSettingsService {

    private static final String KEY_FAMILY = "chat.font.family";
    private static final String KEY_SCALE = "chat.font.scale";

    private final Preferences prefs;

    public ChatFontSettingsService() {
        this(Preferences.userNodeForPackage(Kelly.class));
    }

    public ChatFontSettingsService(Preferences prefs) {
        this.prefs = prefs;
    }

    public ChatFontSettings load() {
        String family = prefs.get(KEY_FAMILY, null);
        ChatFontScale scale;
        try {
            scale = ChatFontScale.valueOf(prefs.get(KEY_SCALE, "MEDIUM"));
        } catch (IllegalArgumentException | NullPointerException e) {
            scale = ChatFontScale.MEDIUM;
        }
        return new ChatFontSettings(
                (family == null || family.isBlank()) ? null : family,
                scale);
    }

    public void save(ChatFontSettings s) {
        if (s.fontFamily() == null) {
            prefs.remove(KEY_FAMILY);
        } else {
            prefs.put(KEY_FAMILY, s.fontFamily());
        }
        prefs.put(KEY_SCALE, s.scale().name());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=ChatFontSettingsServiceTest test`
Expected: PASS, 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsService.java \
        src/test/java/com/mordor/kelly/ui/chat/font/ChatFontSettingsServiceTest.java
git commit -m "feat(font): add ChatFontSettingsService with Preferences backend"
```

---

## Task 4: ChatFontApplier

**Files:**
- Create: `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontApplier.java`
- Create: `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontApplierTest.java`

`apply(Node root, ChatFontSettings s)` is the only entry point. Font family is set on `root` so it inherits. Font size is applied via DFS `walk`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mordor/kelly/ui/chat/font/ChatFontApplierTest.java`:

```java
package com.mordor.kelly.ui.chat.font;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;

import static org.junit.jupiter.api.Assertions.*;

class ChatFontApplierTest {

    private static boolean toolkitStarted = false;

    @BeforeAll
    static void startToolkit() throws Exception {
        try {
            Platform.startup(() -> { });
            toolkitStarted = true;
        } catch (IllegalStateException already) {
            toolkitStarted = true;
        }
    }

    @AfterAll
    static void stopToolkit() throws Exception {
        if (toolkitStarted) {
            try { Platform.exit(); } catch (Exception ignore) { }
        }
    }

    @Test
    void nullFamily_doesNotInjectFontFamilyStyle() {
        VBox root = new VBox(new Label("a"));
        ChatFontApplier.apply(root, new ChatFontSettings(null, ChatFontScale.MEDIUM));
        String style = root.getStyle();
        assertFalse(style.contains("-fx-font-family"),
                "null family must not inject -fx-font-family on root. style=" + style);
    }

    @Test
    void nonNullFamily_setsRootFontFamilyInStyle() {
        VBox root = new VBox(new Label("a"));
        ChatFontApplier.apply(root, new ChatFontSettings("Arial", ChatFontScale.MEDIUM));
        assertTrue(root.getStyle().contains("Arial"), "style=" + root.getStyle());
        assertTrue(root.getStyle().contains("-fx-font-family"), "style=" + root.getStyle());
    }

    @Test
    void largeScale_labelGetsLargerFontSizeStyle() {
        Label lbl = new Label("hi");
        VBox root = new VBox(lbl);
        double before = lbl.getFont().getSize();
        ChatFontApplier.apply(root, new ChatFontSettings(null, ChatFontScale.LARGE));
        assertTrue(lbl.getStyle().contains("-fx-font-size"),
                "Label should have -fx-font-size after apply, style=" + lbl.getStyle());
        // factor 1.1 -> style includes "<before*1.1>px"
        double expected = before * ChatFontScale.LARGE.factor();
        assertTrue(lbl.getStyle().contains(String.format(java.util.Locale.ROOT, "%.3fpx", expected).replaceAll("0+$", "").replaceAll("\\.$", "")),
                "style=" + lbl.getStyle() + " expected near " + expected);
    }

    @Test
    void mediumScale_textInputControlGetsFontSizeStyle() {
        TextField tf = new TextField();
        VBox root = new VBox(tf);
        double before = tf.getFont().getSize();
        ChatFontApplier.apply(root, new ChatFontSettings(null, ChatFontScale.MEDIUM));
        assertTrue(tf.getStyle().contains("-fx-font-size"),
                "TextField should have -fx-font-size after apply, style=" + tf.getStyle());
        assertTrue(tf.getStyle().contains(String.valueOf((int) Math.round(before))),
                "style=" + tf.getStyle() + " expected near " + before);
    }

    @Test
    void walk_visitsNestedTextNode() {
        Text t = new Text("hello");
        VBox root = new VBox(t);
        ChatFontApplier.apply(root, new ChatFontSettings(null, ChatFontScale.SMALL));
        assertTrue(t.getStyle().contains("-fx-font-size"),
                "Text should have -fx-font-size after apply, style=" + t.getStyle());
    }

    @Test
    void deepNestedNodes_areVisited() {
        VBox inner = new VBox(new Label("inner"));
        VBox outer = new VBox(inner);
        ChatFontApplier.apply(outer, new ChatFontSettings(null, ChatFontScale.XLARGE));
        Label innerLabel = (Label) inner.getChildren().get(0);
        assertTrue(innerLabel.getStyle().contains("-fx-font-size"),
                "deep Label should be reached by walk, style=" + innerLabel.getStyle());
    }
}
```

- [ ] **Step 2: Run test to verify it fails (compile error)**

Run: `mvn -q -Dtest=ChatFontApplierTest test`
Expected: compilation failure because `ChatFontApplier` doesn't exist.

- [ ] **Step 3: Create `ChatFontApplier.java`**

Create `src/main/java/com/mordor/kelly/ui/chat/font/ChatFontApplier.java`:

```java
package com.mordor.kelly.ui.chat.font;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.text.Text;

public final class ChatFontApplier {

    private ChatFontApplier() { }

    public static void apply(Node root, ChatFontSettings s) {
        if (root == null || s == null) {
            return;
        }
        if (s.fontFamily() != null) {
            String escaped = s.fontFamily().replace("'", "''");
            root.setStyle("-fx-font-family: '" + escaped + "'");
        }
        walk(root, s.scale().factor());
    }

    private static void walk(Node node, double factor) {
        try {
            if (node instanceof Labeled l && l.getFont() != null) {
                double base = l.getFont().getSize();
                if (base > 0) {
                    l.setStyle(formatFontSize(base * factor));
                }
            } else if (node instanceof TextInputControl t && t.getFont() != null) {
                double base = t.getFont().getSize();
                if (base > 0) {
                    t.setStyle(formatFontSize(base * factor));
                }
            } else if (node instanceof Text tx && tx.getFont() != null) {
                double base = tx.getFont().getSize();
                if (base > 0) {
                    tx.setStyle(formatFontSize(base * factor));
                }
            }
        } catch (Exception ignore) {
            // single node failure must not break sibling traversal
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                walk(child, factor);
            }
        }
    }

    private static String formatFontSize(double size) {
        long rounded = Math.round(size);
        return "-fx-font-size: " + rounded + "px;";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=ChatFontApplierTest test`
Expected: PASS, 6 tests, 0 failures.

If `largeScale_labelGetsLargerFontSizeStyle` fails on the formatted px assertion (JavaFX rounds the label's reported `getFont().getSize()` to a whole number, so `before*1.1` produces a non-integer), relax the assertion to:
```java
assertTrue(lbl.getStyle().matches(".*-fx-font-size: \\d+px;.*"), "style=" + lbl.getStyle());
```
and rerun. Acceptable: any integer-pixel font-size in the style.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mordor/kelly/ui/chat/font/ChatFontApplier.java \
        src/test/java/com/mordor/kelly/ui/chat/font/ChatFontApplierTest.java
git commit -m "feat(font): add ChatFontApplier with DFS scale walk and family on root"
```

---

## Task 5: FontSettingsDialog

**Files:**
- Create: `src/main/java/com/mordor/kelly/ui/chat/font/FontSettingsDialog.java`

GUI; not unit-tested (no headless test infra for stages). Manual verification only.

- [ ] **Step 1: Create `FontSettingsDialog.java`**

Create `src/main/java/com/mordor/kelly/ui/chat/font/FontSettingsDialog.java`:

```java
package com.mordor.kelly.ui.chat.font;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;

public final class FontSettingsDialog {

    private FontSettingsDialog() { }

    public static void show(Stage owner,
                            ChatFontSettings current,
                            Node chatRoot,
                            ChatFontSettingsService service) {
        Stage dialog = new Stage();
        dialog.setTitle("字体设置");
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setResizable(false);

        ObservableList<String> families = FXCollections.observableArrayList(Font.getFamilies());
        ComboBox<String> fontBox = new ComboBox<>(families);
        fontBox.setMaxWidth(Double.MAX_VALUE);
        String initialFamily = current.fontFamily();
        if (initialFamily == null && !families.isEmpty()) {
            initialFamily = families.get(0);
        }
        if (initialFamily != null) {
            fontBox.setValue(initialFamily);
        }

        ToggleGroup group = new ToggleGroup();
        RadioButton small = radio("小", ChatFontScale.SMALL, group, current.scale());
        RadioButton medium = radio("中", ChatFontScale.MEDIUM, group, current.scale());
        RadioButton large = radio("大", ChatFontScale.LARGE, group, current.scale());
        RadioButton xlarge = radio("特大", ChatFontScale.XLARGE, group, current.scale());
        HBox sizeRow = new HBox(12, small, medium, large, xlarge);

        Button apply = new Button("应用");
        Button cancel = new Button("取消");
        HBox buttons = new HBox(8, apply, cancel);
        buttons.setPadding(new Insets(8, 0, 0, 0));

        VBox root = new VBox(10,
                labelled("字体：", fontBox),
                labelled("字号：", sizeRow),
                buttons);
        root.setPadding(new Insets(14));

        apply.setOnAction(e -> {
            String family = fontBox.getValue();
            ChatFontScale scale = (ChatFontScale) group.getSelectedToggle().getUserData();
            ChatFontSettings next = new ChatFontSettings(family, scale);
            service.save(next);
            ChatFontApplier.apply(chatRoot, next);
            dialog.close();
        });
        cancel.setOnAction(e -> dialog.close());
        apply.setDefaultButton(true);

        Scene scene = new Scene(root);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                dialog.close();
            }
        });
        dialog.setScene(scene);
        dialog.setWidth(420);
        dialog.setHeight(220);
        dialog.showAndWait();
    }

    private static RadioButton radio(String text, ChatFontScale scale, ToggleGroup group, ChatFontScale current) {
        RadioButton r = new RadioButton(text);
        r.setToggleGroup(group);
        r.setUserData(scale);
        if (scale == current) {
            r.setSelected(true);
        }
        return r;
    }

    private static HBox labelled(String text, Node field) {
        Label label = new Label(text);
        HBox row = new HBox(8, label, field);
        label.setMinWidth(40);
        HBox.setHgrow(field, javafx.scene.layout.Priority.ALWAYS);
        return row;
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mordor/kelly/ui/chat/font/FontSettingsDialog.java
git commit -m "feat(font): add FontSettingsDialog with ComboBox and 4-scale radio group"
```

---

## Task 6: Wire ChatPane and ChatHeader

**Files:**
- Modify: `src/main/java/com/mordor/kelly/ui/chat/ChatPane.java`
- Modify: `src/main/java/com/mordor/kelly/ui/chat/ChatHeader.java`

`ChatPane` constructor calls `ChatFontApplier.apply(this, service.load())` once. `ChatHeader` gets a new FontIcon button after the existing `meta` label.

- [ ] **Step 1: Modify `ChatPane.java`**

After the last line of the existing constructor (after `getChildren().addAll(wallpaper(), ui);`), insert:

```java
        ChatFontSettingsService fontService = new ChatFontSettingsService();
        ChatFontApplier.apply(this, fontService.load());
```

Add import at top (if not present):

```java
import com.mordor.kelly.ui.chat.font.ChatFontApplier;
import com.mordor.kelly.ui.chat.font.ChatFontSettingsService;
```

Note: `this` refers to the `ChatPane` (which is a `StackPane` extending `Region` extending `Parent` extending `Node`), and `ChatFontApplier.apply` only sets `-fx-font-family` on root + walks the subtree. The font family is inherited; sizes are walked.

- [ ] **Step 2: Modify `ChatHeader.java`**

Existing constructor signature: `public ChatHeader(AppState state, ChatController controller)`. We need `chatRoot` and `fontService` in scope. Modify the signature:

```java
public ChatHeader(AppState state, ChatController controller, Node chatRoot, ChatFontSettingsService fontService)
```

Add imports:

```java
import com.mordor.kelly.ui.chat.font.ChatFontSettingsService;
import com.mordor.kelly.ui.chat.font.FontSettingsDialog;
import javafx.scene.Node;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
```

After `getChildren().addAll(title, spacer, knowledge, meta);`, append:

```java
        FontIcon fontButton = new FontIcon(MaterialDesignF.FORMAT_FONT);
        fontButton.getStyleClass().add("header-font-button");
        fontButton.setCursor(javafx.scene.Cursor.HAND);
        fontButton.setOnMouseClicked(e ->
                FontSettingsDialog.show(
                        getScene() == null ? null : (Stage) getScene().getWindow(),
                        fontService.load(),
                        chatRoot,
                        fontService));
        getChildren().add(fontButton);
```

- [ ] **Step 3: Update `ChatPane.java` callsite that constructs `ChatHeader`**

In `ChatPane.java` constructor, change:

```java
ui.setTop(new ChatHeader(state, controller));
```

to:

```java
ui.setTop(new ChatHeader(state, controller, this, fontService));
```

(`fontService` is the local declared in Step 1.)

- [ ] **Step 4: Compile to verify**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Run all unit tests**

Run: `mvn -q test`
Expected: all green (existing tests + the 12 new ones from Tasks 2-4).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mordor/kelly/ui/chat/ChatPane.java \
        src/main/java/com/mordor/kelly/ui/chat/ChatHeader.java
git commit -m "feat(font): wire ChatPane apply + ChatHeader settings button"
```

---

## Task 7: Manual verification

GUI behavior. Run the app and confirm:

- [ ] **Step 1: Start the app**

Run: `mvn -q javafx:run`
Expected: app launches, login screen shows normally.

- [ ] **Step 2: Verify login screen is unaffected**

Confirm: login form font and size are unchanged (this validates scope = chat only).

- [ ] **Step 3: Log in and open the chat**

Confirm: chat opens with default font and size.

- [ ] **Step 4: Open font settings dialog**

Click the FontIcon button in the chat header (rightmost icon). Confirm: dialog opens at ~420×220, modal, with font ComboBox and 4 RadioButtons.

- [ ] **Step 5: Change font and scale, click Apply**

Pick a different font in the ComboBox. Pick "大". Click 应用. Confirm: dialog closes; chat text re-renders with the new family and larger size.

- [ ] **Step 6: Cancel preserves previous state**

Reopen dialog, change scale to "特大", click 取消 (NOT 应用). Confirm: dialog closes; chat is unchanged (still "大" from step 5).

- [ ] **Step 7: Restart preserves settings**

Quit the app. Restart with `mvn -q javafx:run`. Log in. Confirm: chat loads with the family and scale from step 5.

- [ ] **Step 8: ESC closes dialog without saving**

Open dialog, change settings, press ESC. Confirm: dialog closes; chat unchanged.

- [ ] **Step 9: Edge: missing system fonts**

On the Linux machine (no Chinese fonts installed), open dialog, pick the first available family (likely `Dialog` or `SansSerif`). Confirm: chat text still renders (via fallback chain end `sans-serif`).

If any step fails, fix and re-run from that step. Do NOT mark complete until all 9 steps pass.

---

## Self-Review Notes (filled by plan author before commit)

Spec coverage check:

- §5 Data Model → Task 2 (ChatFontScale + ChatFontSettings)
- §6 Persistence → Task 3 (ChatFontSettingsService)
- §7 Application Layer → Task 4 (ChatFontApplier)
- §7.3 prereq (chat.css without font-size) → Task 1
- §8 UI Entry (FontIcon button) → Task 6
- §9 Settings Dialog → Task 5
- §10 Wiring → Task 6
- §11 Error Handling → covered by `walk()` try/catch in Task 4 and corrupt-enum fallback in Task 3
- §12.1 Unit tests → Tasks 2-4 each have tests
- §12.2 Manual integration → Task 7
- §13 Files Changed → all 8 files listed in this plan

Placeholder scan: none. Every code block is complete.

Type consistency: `ChatFontScale.factor()`, `ChatFontSettings.defaults()`, `ChatFontSettingsService.load()/save(ChatFontSettings)`, `ChatFontApplier.apply(Node, ChatFontSettings)`, `FontSettingsDialog.show(Stage, ChatFontSettings, Node, ChatFontSettingsService)` — all signatures match across Tasks 2-6.