package bagtest.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * 【模块二：工具层】
 * 物品序列化工具 - ItemStack <-> Base64 字符串
 *
 * 采用 Bukkit 原生对象流 (BukkitObjectStream), 对 1.12.2 物品的
 * 附魔/NBT/耐久等全部属性兼容, 是最稳定的序列化方案。
 *
 * 重要约定: 所有 ItemStack 的序列化/反序列化必须发生在主线程!
 */
public final class ItemSerializer {

    private ItemSerializer() {
    }

    /**
     * 物品转 Base64 字符串
     *
     * @param item 物品 (null 返回 null)
     * @return Base64 字符串; 序列化失败返回 null (由调用方决定忽略该槽位)
     */
    public static String itemToBase64(ItemStack item, Logger logger) {
        if (item == null) {
            return null;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos);
            boos.writeObject(item);
            boos.close();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            // 单个物品序列化失败不应中断整体保存, 跳过并记录
            logger.warning("物品序列化失败, 已跳过该槽位: " + e.getMessage());
            return null;
        }
    }

    /**
     * Base64 字符串还原物品
     *
     * @param base64 Base64 字符串 (null/空 返回 null)
     * @return 物品; 数据损坏时返回 null (视为空槽位, 保证插件不崩溃)
     */
    public static ItemStack base64ToItem(String base64, Logger logger) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
            BukkitObjectInputStream bois = new BukkitObjectInputStream(bais);
            Object obj = bois.readObject();
            bois.close();
            return obj instanceof ItemStack ? (ItemStack) obj : null;
        } catch (Exception e) {
            // 数据损坏时按空槽位处理, 保证插件逻辑稳定不中断
            logger.warning("物品反序列化失败, 已按空槽位处理: " + e.getMessage());
            return null;
        }
    }
}
