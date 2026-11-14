package com.mordor.kelly.ui.theme;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 色板一致性测试：样式表里引用的每个 {@code -color-xxx} 都必须在
 * 亮色（theme.css）和深色（dark.css）两套色板里都有定义。
 *
 * <p>JavaFX 里引用一个没定义的自定义属性只会打一条警告、颜色静默失效，
 * 光看界面很难发现，所以用测试兜住。</p>
 */
class ThemeCssTest {

    /** 亮色色板样式表 */
    private static final String THEME_CSS = "/com/mordor/kelly/ui/theme/theme.css";
    /** 深色色板样式表 */
    private static final String DARK_CSS = "/com/mordor/kelly/ui/theme/dark.css";
    /** 其它会引用色板的样式表 */
    private static final String[] CONSUMER_CSS = {
            "/com/mordor/kelly/app/app.css",
            "/com/mordor/kelly/ui/login/login.css",
            "/com/mordor/kelly/ui/chat/chat.css",
    };

    /** 自定义属性名 */
    private static final Pattern PROPERTY = Pattern.compile("(-color-[a-z0-9-]+)\\s*:");

    @Test
    void everyReferencedColor_isDefinedInBothPalettes() throws IOException {
        Set<String> light = definedColors(read(THEME_CSS));
        Set<String> dark = definedColors(read(DARK_CSS));
        assertTrue(light.size() > 10, "亮色色板看起来没解析到：" + light);

        Set<String> referenced = new LinkedHashSet<>();
        referenced.addAll(referencedColors(read(THEME_CSS)));
        referenced.addAll(referencedColors(read(DARK_CSS)));
        for (String path : CONSUMER_CSS) {
            referenced.addAll(referencedColors(read(path)));
        }

        for (String name : referenced) {
            assertTrue(light.contains(name), "亮色色板缺少定义：" + name);
            assertTrue(dark.contains(name), "深色色板缺少定义：" + name);
        }
    }

    @Test
    void darkPalette_coversEveryLightPaletteColor() throws IOException {
        Set<String> light = definedColors(read(THEME_CSS));
        Set<String> dark = definedColors(read(DARK_CSS));
        assertTrue(dark.containsAll(light),
                "深色色板缺少这些色板项：" + minus(light, dark));
    }

    /**
     * 读取样式表文本。
     */
    private static String read(String path) throws IOException {
        try (InputStream in = ThemeCssTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "样式表缺失：" + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 提取样式表里定义的自定义属性名。
     */
    private static Set<String> definedColors(String css) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PROPERTY.matcher(css);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * 提取样式表里「被引用」的自定义属性名（出现在值位置、后面没有冒号）。
     */
    private static Set<String> referencedColors(String css) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(-color-[a-z0-9-]+)(\\s*)").matcher(css);
        while (m.find()) {
            // 定义处是 "名字:"，引用处是 "名字"（后面没有冒号）
            if (!m.group(2).isEmpty() || !isDefinition(css, m.end(1))) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /**
     * 判断属性名后面紧跟的是不是冒号（是则是定义，不是引用）。
     */
    private static boolean isDefinition(String css, int endIndex) {
        int i = endIndex;
        while (i < css.length() && Character.isWhitespace(css.charAt(i))) {
            i++;
        }
        return i < css.length() && css.charAt(i) == ':';
    }

    /**
     * 集合差集（用于错误信息）。
     */
    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
