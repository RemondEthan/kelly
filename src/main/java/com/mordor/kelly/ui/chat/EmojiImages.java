package com.mordor.kelly.ui.chat;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Windows 上 JavaFX 无法渲染彩色 emoji 字体，用 Twemoji PNG 画表情。
 * 协议仍传 Unicode，只在界面替换为图片。
 */
public final class EmojiImages {

    static final String[] CATALOG = {
            "😀", "😁", "😂", "🤣", "😊", "😍",
            "😘", "😎", "🤩", "🥳", "🤔", "🙄",
            "😴", "😪", "🤗", "🤭", "🤫", "🤐",
            "👍", "👎", "👏", "🙏", "💪", "🤝",
            "❤️", "💔", "💯", "🔥", "✨", "🎉",
            "☕", "🍕", "🍺", "🎁", "📎", "📁",
            "🟢", "🔴"
    };

    private static final List<String> LONGEST_FIRST = longestFirst();
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private EmojiImages() {}

    public record FlowParts(TextFlow flow, int[] charOffsets) {}

    public static TextFlow flow(String text) {
        return flowWithMap(text).flow();
    }

    public static FlowParts flowWithMap(String text) {
        TextFlow flow = new TextFlow();
        if (text == null || text.isEmpty()) {
            return new FlowParts(flow, new int[0]);
        }
        int[] offsets = new int[countChildren(text)];
        int childIdx = 0;
        int i = 0;
        int rawOffset = 0;
        StringBuilder buf = new StringBuilder();
        while (i < text.length()) {
            String match = matchAt(text, i);
            if (match != null) {
                if (buf.length() > 0) {
                    offsets[childIdx] = rawOffset;
                    flow.getChildren().add(new Text(buf.toString()));
                    rawOffset += buf.length();
                    buf.setLength(0);
                    childIdx++;
                }
                offsets[childIdx] = rawOffset;
                flow.getChildren().add(view(match, 16));
                rawOffset += match.length();
                i += match.length();
                childIdx++;
            } else {
                buf.append(text.charAt(i));
                i++;
            }
        }
        if (buf.length() > 0) {
            offsets[childIdx] = rawOffset;
            flow.getChildren().add(new Text(buf.toString()));
        }
        return new FlowParts(flow, offsets);
    }

    private static int countChildren(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        int i = 0;
        while (i < text.length()) {
            String match = matchAt(text, i);
            if (match != null) {
                count++;
                i += match.length();
            } else {
                // 找下一段连续非 emoji 字符
                int j = i;
                while (j < text.length()) {
                    String m = matchAt(text, j);
                    if (m != null) break;
                    j++;
                }
                if (j > i) count++; // 跳过了一段 Text（非 emoji 区域）
                if (j < text.length()) {
                    i = j; // j 处有 emoji，继续处理
                } else {
                    // j 到达末尾：剩余的 [i, end) 在主算法里由 post-loop flush 处理
                    // 如果还有未处理字符则算 1 个 Text（会在 post-loop flush 中被计数）
                    if (i < text.length()) count++;
                    i = j;
                    break;
                }
            }
        }
        return count;
    }

    public static ImageView view(String emoji, double size) {
        ImageView view = new ImageView();
        try {
            Image img = image(emoji);
            if (img != null) {
                view.setImage(img);
            }
        } catch (Throwable e) {
            // Headless test environment: JavaFX graphics subsystem throws
            // NoClassDefFoundError / ExceptionInInitializerError / RuntimeException
            // depending on which class fails to load. Return the view empty so
            // headless tests don't crash; real displays never hit this path.
        }
        view.setFitHeight(size);
        view.setFitWidth(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }

    static String resourceKey(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return "";
        }
        String withVs = hexKey(emoji, false);
        if (resourceExists(withVs)) {
            return withVs;
        }
        String noVs = hexKey(emoji, true);
        return resourceExists(noVs) ? noVs : withVs;
    }

    private static Image image(String emoji) {
        String key = resourceKey(emoji);
        if (key.isEmpty()) {
            return null;
        }
        Image cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        var url = EmojiImages.class.getResource("/emoji/" + key + ".png");
        if (url == null) {
            return null;
        }
        Image loaded = new Image(url.toExternalForm(), true);
        CACHE.put(key, loaded);
        return loaded;
    }

    private static String matchAt(String text, int index) {
        for (String emoji : LONGEST_FIRST) {
            if (text.startsWith(emoji, index)) {
                return emoji;
            }
        }
        return null;
    }

    private static String hexKey(String emoji, boolean stripVs) {
        List<String> parts = new ArrayList<>();
        emoji.codePoints().forEach(cp -> {
            if (stripVs && cp == 0xFE0F) {
                return;
            }
            parts.add(Integer.toHexString(cp));
        });
        return String.join("-", parts);
    }

    private static boolean resourceExists(String key) {
        return EmojiImages.class.getResource("/emoji/" + key + ".png") != null;
    }

    private static List<String> longestFirst() {
        List<String> list = new ArrayList<>(List.of(CATALOG));
        list.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(list);
    }
}
