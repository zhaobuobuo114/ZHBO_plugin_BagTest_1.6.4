package bagtest.manager;

import bagtest.BagPlugin;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 【模块：日志层】(v1.5.0 新增)
 * 特殊物品(开锁器/拓展器)来源日志管理器
 *
 * 职责:
 * 1. give 时记录"谁给谁发了什么"(同时写入物品 lore 的来源行);
 * 2. 玩家消耗道具时记录"谁用了什么 + 来源 + 用途"(开锁格/开新页);
 * 3. 全部记录追加到 plugins/ZHBO_plugin_BagTest_1.6.4/itemlog.txt, 便于追溯氪金道具流转。
 *
 * 线程模型: record 在主线程拼接字符串, 文件写入丢给异步线程;
 * 关服等调度不可用的极端情况降级为同步写, 保证日志不丢。
 */
public class ItemLogManager {

    /** 日志文件名 (位于插件数据文件夹) */
    private static final String FILE_NAME = "itemlog.txt";

    private final BagPlugin plugin;
    private final File logFile;

    /** 完整时间戳格式 (日志行首) */
    private final SimpleDateFormat fullFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    /** 简短时间格式 (物品 lore 来源行用, 控制长度) */
    private final SimpleDateFormat shortFormat = new SimpleDateFormat("MM/dd HH:mm");

    public ItemLogManager(BagPlugin plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /** 生成物品 lore 来源行内容: "发放者 MM/dd HH:mm" */
    public String buildSource(String giver) {
        return giver + " " + shortFormat.format(new Date());
    }

    /**
     * 记录一条特殊物品流转日志 (给予/消耗)
     * 同时输出到后台控制台 (INFO 级), 方便实时监控
     *
     * @param line 日志正文 (时间戳由本方法自动添加)
     */
    public void record(String line) {
        final String stamped = "[" + fullFormat.format(new Date()) + "] " + line;
        plugin.getLogger().info("[物品日志] " + line);
        try {
            // 异步写文件 (主线程调用时); 调度不可用(关服中)则同步写兜底
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> append(stamped));
        } catch (Throwable t) {
            append(stamped);
        }
    }

    /** 追加一行到日志文件 (自动创建父目录与文件) */
    private void append(String line) {
        try {
            File dir = logFile.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return; // 目录创建失败: 放弃本次写入 (控制台已有记录)
            }
            PrintWriter writer = new PrintWriter(new FileWriter(logFile, true));
            try {
                writer.println(line);
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("物品日志写入失败: " + e.getMessage());
        }
    }
}
