package com.mordor.kelly.ui.chat;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emoji 图片加载与渲染工具类。
 *
 * <h3>设计背景</h3>
 * <p>JavaFX 的 Label/Text 控件在 Windows 上无法正确渲染彩色 emoji（显示为黑白方块），
 * 因此本项目采用 <b>Twemoji PNG</b> 方案：将 emoji Unicode 码点映射为 PNG 图片资源，
 * 以 {@link ImageView} 方式嵌入到 {@link TextFlow} 中。</p>
 *
 * <h3>资源映射机制</h3>
 * <ol>
 *   <li>启动时扫描 classpath 下 {@code /emoji/*.png} 文件</li>
 *   <li>将文件名（十六进制码点，如 {@code 1f600.png}）映射为对应的 emoji 字符串</li>
 *   <li>构建 {@link #EMOJI_MAP}（hexKey → emoji）和 {@link #EMOJI_LIST}（所有支持的 emoji）</li>
 * </ol>
 *
 * <h3>TextFlow 混排</h3>
 * <p>{@link #flowWithMap} 将文本中的 emoji 替换为 {@link ImageView}，
 * 非 emoji 部分保持为 {@link Text} 节点，形成图文混排的 {@link TextFlow}。
 * 返回的 {@link FlowParts#charOffsets()} 记录每个子节点在原始文本中的起始偏移，
 * 用于 {@link SelectableTextFlow} 的选区计算。</p>
 *
 * <p>工具类，不能实例化。使用 {@link ConcurrentHashMap} 缓存已加载的图片。</p>
 */
public final class EmojiImages {

    /** hexKey → emoji 字符串 的映射（如 "1f600" → "😀"） */
    private static final Map<String, String> EMOJI_MAP = loadEmojiMap();
    /** 所有支持的 emoji 字符串列表（不可变） */
    private static final List<String> EMOJI_LIST = Collections.unmodifiableList(new ArrayList<>(EMOJI_MAP.values()));

    static {
        if (EMOJI_MAP.isEmpty()) {
            System.err.println("[EmojiImages] Warning: No emoji PNG resources found on classpath /emoji/");
        }
    }

    private EmojiImages() {}

    /**
     * TextFlow 混排结果：包含 TextFlow 和每个子节点在原始文本中的字符偏移量。
     *
     * @param flow       包含 Text 和 ImageView 子节点的 TextFlow
     * @param charOffsets 每个子节点在原始文本中的起始字符偏移
     */
    public record FlowParts(TextFlow flow, int[] charOffsets) {}

    /**
     * 将文本解析为包含 emoji 图片的 TextFlow（仅返回 flow，不含偏移信息）。
     *
     * @param text 原始文本
     * @return 包含 emoji 图片的 TextFlow
     */
    public static TextFlow flow(String text) {
        return flowWithMap(text).flow();
    }

    /**
     * 将文本解析为包含 emoji 图片的 TextFlow，并返回偏移映射。
     *
     * <p>解析算法：逐字符扫描文本，遇到 emoji 序列时插入 {@link ImageView}，
     * 非 emoji 部分累积到缓冲区后作为 {@link Text} 节点插入。</p>
     *
     * @param text 原始文本
     * @return 混排结果（TextFlow + 偏移映射）
     */
    public static FlowParts flowWithMap(String text) {
        TextFlow flow = new TextFlow();
        if (text == null || text.isEmpty()) {
            return new FlowParts(flow, new int[0]);
        }
        int[] offsets = new int[countChildren(text)];  // 预分配偏移数组
        int childIdx = 0;
        int i = 0;
        int rawOffset = 0;       // 当前位置在原始文本中的偏移
        StringBuilder buf = new StringBuilder();  // 累积非 emoji 文本
        while (i < text.length()) {
            String match = matchAt(text, i);  // 尝试匹配 emoji
            if (match != null) {
                // 先输出累积的文本
                if (!buf.isEmpty()) {
                    offsets[childIdx] = rawOffset;
                    flow.getChildren().add(new Text(buf.toString()));
                    rawOffset += buf.length();
                    buf.setLength(0);
                    childIdx++;
                }
                // 输出 emoji 图片
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
        // 输出剩余文本
        if (!buf.isEmpty()) {
            offsets[childIdx] = rawOffset;
            flow.getChildren().add(new Text(buf.toString()));
        }
        return new FlowParts(flow, offsets);
    }

    /**
     * 计算文本解析后的子节点数量（用于预分配偏移数组）。
     */
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

    /**
     * 创建指定 emoji 的 ImageView（指定尺寸）。
     *
     * @param emoji emoji 字符串
     * @param size  显示尺寸（px）
     * @return 配置好的 ImageView
     */
    public static ImageView view(String emoji, double size) {
        ImageView view = new ImageView();
        try {
            Image img = image(emoji);
            if (img != null) {
                view.setImage(img);
            }
        } catch (Throwable e) {
            // Headless 测试环境中可能无图片资源，忽略
        }
        view.setFitHeight(size);
        view.setFitWidth(size);
        view.setPreserveRatio(true);  // 保持宽高比
        view.setSmooth(true);         // 平滑缩放
        return view;
    }

    /**
     * 将 emoji 字符串转换为 PNG 资源文件名（hexKey）。
     *
     * @param emoji emoji 字符串
     * @return 十六进制文件名（如 "1f600"）
     */
    static String resourceKey(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return "";
        }
        // 先尝试完整 key（含 variation selector）
        String key = hexKey(emoji, false);
        if (resourceExists(key)) {
            return key;
        }
        // 再尝试去掉 variation selector (0xFE0F) 的 key
        String noVs = hexKey(emoji, true);
        return resourceExists(noVs) ? noVs : key;
    }

    /**
     * 加载 emoji 图片（带缓存）。
     */
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
        Image loaded = new Image(url.toExternalForm(), true);  // 异步加载
        CACHE.put(key, loaded);
        return loaded;
    }

    /**
     * 扫描 classpath，构建 emoji 映射表。
     */
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

    /**
     * 生成所有可能的 emoji 码点对应的 hexKey 候选列表。
     *
     * <p>覆盖 Unicode 标准中的主要 emoji 范围：
     * 表情符号、杂项符号、运输符号、炼金符号、棋盘符号等。</p>
     */
    private static List<String> generateCandidateKeys() {
        List<String> keys = new ArrayList<>();
        int[][] emojiRanges = {
            {0x1F600, 0x1F64F}, // Emoticons（表情符号）
            {0x1F300, 0x1F5FF}, // Misc Symbols and Pictographs（杂项符号和象形文字）
            {0x1F680, 0x1F6FF}, // Transport and Map Symbols（运输和地图符号）
            {0x1F700, 0x1F77F}, // Alchemical Symbols（炼金符号）
            {0x1F780, 0x1F7FF}, // Geometric Shapes Extended（扩展几何图形）
            {0x1F800, 0x1F8FF}, // Supplemental Arrows-C（补充箭头-C）
            {0x1F900, 0x1F9FF}, // Supplemental Symbols and Pictographs（补充符号和象形文字）
            {0x1FA00, 0x1FA6F}, // Chess Symbols（棋盘符号）
            {0x1FA70, 0x1FAFF}, // Symbols and Pictographs Extended-A（扩展象形文字-A）
            {0x2600, 0x26FF},   // Misc Symbols（杂项符号）
            {0x2700, 0x27BF},   // Dingbats（装饰符号）
            {0x2300, 0x23FF},   // Miscellaneous Technical（杂项技术符号）
            {0x2B50, 0x2B55},   // Stars（星号）
            {0x3030, 0x3030},   // Wavy Dash
            {0x303D, 0x303D},   // Part Alternation Mark
            {0x3297, 0x3297},   // Circled Ideograph Congratulation
            {0x3299, 0x3299},   // Circled Ideograph Secret
            {0xFE0F, 0xFE0F},   // Variation Selector-16（变体选择符）
        };
        for (int[] range : emojiRanges) {
            for (int cp = range[0]; cp <= range[1]; cp++) {
                keys.add(Integer.toHexString(cp));
                // 同时添加带 variation selector 的版本
                keys.add(Integer.toHexString(cp) + "-fe0f");
            }
        }
        return keys;
    }

    /**
     * 将 emoji 字符串转换为十六进制文件名。
     *
     * @param emoji   emoji 字符串
     * @param stripVs 是否去掉 variation selector (0xFE0F)
     * @return 十六进制文件名（如 "1f600" 或 "1f44d-fe0f"）
     */
    private static String hexKey(String emoji, boolean stripVs) {
        List<String> parts = new ArrayList<>();
        emoji.codePoints().forEach(cp -> {
            if (stripVs && cp == 0xFE0F) {
                return;  // 跳过 variation selector
            }
            parts.add(Integer.toHexString(cp));
        });
        return String.join("-", parts);
    }

    /**
     * 将十六进制文件名还原为 emoji 字符串。
     */
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

    /**
     * 检查 classpath 下是否存在对应 PNG 资源。
     */
    private static boolean resourceExists(String key) {
        return EmojiImages.class.getResource("/emoji/" + key + ".png") != null;
    }

    /**
     * 从文本指定位置开始匹配 emoji。
     *
     * @param text  文本
     * @param index 起始位置
     * @return 匹配到的 emoji 字符串，无匹配返回 null
     */
    private static String matchAt(String text, int index) {
        for (String emoji : EMOJI_LIST) {
            if (text.startsWith(emoji, index)) {
                return emoji;
            }
        }
        return null;
    }

    /** emoji 图片缓存：hexKey → Image，避免重复加载 */
    private static final ConcurrentHashMap<String, Image> CACHE = new ConcurrentHashMap<>();

    /**
     * 获取所有支持的 emoji 列表（用于表情选择弹窗）。
     *
     * @return emoji 字符串列表的副本
     */
    public static List<String> getSupportedEmojis() {
        return new ArrayList<>(EMOJI_LIST);
    }
}