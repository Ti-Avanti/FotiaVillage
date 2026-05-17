package gg.fotia.fotiavillage.util;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 旧版颜色代码转换器，将 & 和 § 颜色代码转换为 MiniMessage 格式。
 */
public final class LegacyColorConverter {
    private static final Map<Character, String> COLOR_MAP = new HashMap<>();
    private static final Map<Character, String> FORMAT_MAP = new HashMap<>();
    private static final Pattern HEX_PATTERN = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern BUKKIT_HEX_PATTERN = Pattern.compile("[§&]x([§&][0-9a-fA-F]){6}");
    private static final Pattern COLOR_PATTERN = Pattern.compile("[&§]([0-9a-fA-FklmnorKLMNOR])");

    static {
        COLOR_MAP.put('0', "<black>");
        COLOR_MAP.put('1', "<dark_blue>");
        COLOR_MAP.put('2', "<dark_green>");
        COLOR_MAP.put('3', "<dark_aqua>");
        COLOR_MAP.put('4', "<dark_red>");
        COLOR_MAP.put('5', "<dark_purple>");
        COLOR_MAP.put('6', "<gold>");
        COLOR_MAP.put('7', "<gray>");
        COLOR_MAP.put('8', "<dark_gray>");
        COLOR_MAP.put('9', "<blue>");
        COLOR_MAP.put('a', "<green>");
        COLOR_MAP.put('b', "<aqua>");
        COLOR_MAP.put('c', "<red>");
        COLOR_MAP.put('d', "<light_purple>");
        COLOR_MAP.put('e', "<yellow>");
        COLOR_MAP.put('f', "<white>");

        FORMAT_MAP.put('k', "<obfuscated>");
        FORMAT_MAP.put('l', "<bold>");
        FORMAT_MAP.put('m', "<strikethrough>");
        FORMAT_MAP.put('n', "<underlined>");
        FORMAT_MAP.put('o', "<italic>");
        FORMAT_MAP.put('r', "<reset>");
    }

    private LegacyColorConverter() {
    }

    public static String convertToMiniMessage(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        text = convertHexColors(text);
        text = convertBukkitHexColors(text);
        return convertBasicColors(text);
    }

    private static String convertHexColors(String text) {
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, "<#" + matcher.group(1).toLowerCase() + ">");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String convertBukkitHexColors(String text) {
        Matcher matcher = BUKKIT_HEX_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String match = matcher.group();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < match.length(); i++) {
                char current = match.charAt(i);
                if (Character.isLetterOrDigit(current) && current != 'x' && current != 'X') {
                    hex.append(current);
                }
            }
            matcher.appendReplacement(result, "<#" + hex.toString().toLowerCase() + ">");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String convertBasicColors(String text) {
        Matcher matcher = COLOR_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            char code = Character.toLowerCase(matcher.group(1).charAt(0));
            String replacement = COLOR_MAP.get(code);
            if (replacement == null) {
                replacement = FORMAT_MAP.getOrDefault(code, matcher.group());
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
