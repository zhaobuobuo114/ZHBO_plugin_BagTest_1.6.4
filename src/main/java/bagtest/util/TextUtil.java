package bagtest.util;

import org.bukkit.ChatColor;

/**
 * 【模块二：工具层】
 * 文本工具类 - 统一处理颜色代码翻译, 避免各处重复实现
 */
public final class TextUtil {

    private TextUtil() {
    }

    /**
     * 将 & 颜色代码翻译为真正的颜色符号
     * 空安全: 传入 null 返回空字符串
     */
    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
