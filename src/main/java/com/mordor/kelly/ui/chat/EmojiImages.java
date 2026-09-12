package com.mordor.kelly.ui.chat;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class EmojiImages {

    private static final Map<String, String> EMOJI_MAP = loadEmojiMap();
    private static final List<String> EMOJI_LIST = Collections.unmodifiableList(new ArrayList<>(EMOJI_MAP.values()));

    static {
        if (EMOJI_MAP.isEmpty()) {
            System.err.println("[EmojiImages] Warning: No emoji PNG resources found on classpath /emoji/");
        }
    }

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
                if (!buf.isEmpty()) {
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
        if (!buf.isEmpty()) {
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
                int j = i;
                while (j < text.length()) {
                    String m = matchAt(text, j);
                    if (m != null) break;
                    j++;
                }
                if (j > i) count++;
                if (j < text.length()) {
                    i = j;
                } else {
                    count++;
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
            // Headless test environment
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
        String key = hexKey(emoji, false);
        if (resourceExists(key)) {
            return key;
        }
        String noVs = hexKey(emoji, true);
        return resourceExists(noVs) ? noVs : key;
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

    private static Map<String, String> loadEmojiMap() {
        Map<String, String> map = new HashMap<>();
        List<String> candidateKeys = generateCandidateKeys();
        for (String key : candidateKeys) {
            if (resourceExists(key)) {
                String emoji = hexKeyToEmoji(key);
                if (emoji != null && !emoji.isEmpty()) {
                    map.put(key, emoji);
                }
            }
        }
        return map;
    }

    private static List<String> generateCandidateKeys() {
        List<String> keys = new ArrayList<>();
        int[][] emojiRanges = {
            {0x1F600, 0x1F64F}, // Emoticons
            {0x1F300, 0x1F5FF}, // Misc Symbols and Pictographs
            {0x1F680, 0x1F6FF}, // Transport and Map Symbols
            {0x1F700, 0x1F77F}, // Alchemical Symbols
            {0x1F780, 0x1F7FF}, // Geometric Shapes Extended
            {0x1F800, 0x1F8FF}, // Supplemental Arrows-C
            {0x1F900, 0x1F9FF}, // Supplemental Symbols and Pictographs
            {0x1FA00, 0x1FA6F}, // Chess Symbols
            {0x1FA70, 0x1FAFF}, // Symbols and Pictographs Extended-A
            {0x2600, 0x26FF},   // Misc Symbols
            {0x2700, 0x27BF},   // Dingbats
            {0x2300, 0x23FF},   // Miscellaneous Technical
            {0x2B50, 0x2B55},   // Stars
            {0x3030, 0x3030},   // Wavy Dash
            {0x303D, 0x303D},   // Part Alternation Mark
            {0x3297, 0x3297},   // Circled Ideograph Congratulation
            {0x3299, 0x3299},   // Circled Ideograph Secret
            {0xFE0F, 0xFE0F},   // Variation Selector-16
        };
        for (int[] range : emojiRanges) {
            for (int cp = range[0]; cp <= range[1]; cp++) {
                keys.add(Integer.toHexString(cp));
                // Also add with variation selector for emojis that have both forms
                keys.add(Integer.toHexString(cp) + "-fe0f");
            }
        }
        return keys;
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

    private static String hexKeyToEmoji(String key) {
        try {
            String[] parts = key.split("-");
            int[] cps = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                cps[i] = Integer.parseInt(parts[i], 16);
            }
            StringBuilder sb = new StringBuilder();
            for (int cp : cps) {
                sb.append(Character.toChars(cp));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean resourceExists(String key) {
        return EmojiImages.class.getResource("/emoji/" + key + ".png") != null;
    }

    private static String matchAt(String text, int index) {
        for (String emoji : EMOJI_LIST) {
            if (text.startsWith(emoji, index)) {
                return emoji;
            }
        }
        return null;
    }

    private static final ConcurrentHashMap<String, Image> CACHE = new ConcurrentHashMap<>();

    public static List<String> getSupportedEmojis() {
        return new ArrayList<>(EMOJI_LIST);
    }
}