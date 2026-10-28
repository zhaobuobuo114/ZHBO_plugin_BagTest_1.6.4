package bagtest.manager;

import bagtest.BagPlugin;
import bagtest.config.ConfigManager;
import bagtest.config.ConfigManager.Group;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 【模块二：分组管理层】(v1.1.0 新增)
 * 玩家权限组绑定管理 - 插件自存分组数据, 不依赖任何权限插件
 *
 * 数据文件: plugins/ZHBO_plugin_BagTest_1.6.4/groups.yml
 *   <玩家UUID>: 组名
 *
 * 判定优先级 (见 ConfigManager.getGroup):
 *   1. groups.yml 绑定的组 (本类管理, /bag group add 指令写入);
 *   2. 玩家拥有的组权限节点 (兼容权限插件授权方式);
 *   3. default 兜底组。
 * 两种授权方式并存: 指令绑定优先, 未绑定者走权限节点。
 *
 * 线程模型: 绑定数据量小 (每玩家一行), 读写均在主线程完成,
 * 保存采用原子写 (临时文件 + rename), 断电/崩溃不损文件。
 */
public class GroupManager {

    private final BagPlugin plugin;

    /** 绑定表: 玩家UUID -> 组名 (保持插入顺序, 便于 dump 排查) */
    private final Map<UUID, String> bindings = new LinkedHashMap<UUID, String>();

    /** 数据文件 (plugins/ZHBO_plugin_BagTest_1.6.4/groups.yml) */
    private final File file;

    /** 脏标记: 有修改未落盘 */
    private boolean dirty = false;

    public GroupManager(BagPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "groups.yml");
    }

    // ==================== 加载 / 保存 ====================

    /** 启动时加载绑定数据 (文件不存在则自动创建空文件) */
    public void load() {
        bindings.clear();
        try {
            if (!file.exists()) {
                // 首次运行: 释放空文件便于服主手动编辑
                save();
                return;
            }
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            for (String key : y.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String group = y.getString(key, "");
                    // 仅接受配置中真实存在的组名 (配置删除的组绑定自动失效)
                    if (!group.isEmpty() && plugin.getConfigManager().getGroupByName(group) != null) {
                        bindings.put(uuid, group);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 非法 UUID 键跳过
                }
            }
            plugin.getLogger().info("已加载玩家分组绑定: " + bindings.size() + " 条");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "groups.yml 加载失败, 按空绑定处理", e);
        }
    }

    /** 原子保存到磁盘 (主线程调用, 文件极小不卡服) */
    public void save() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            for (Map.Entry<UUID, String> e : bindings.entrySet()) {
                y.set(e.getKey().toString(), e.getValue());
            }
            // 原子写: 先写临时文件再替换, 崩溃窗口内不会产生半截文件
            File tmp = new File(file.getParentFile(), "groups.yml.tmp");
            y.save(tmp);
            if (file.exists() && !file.delete()) {
                plugin.getLogger().warning("groups.yml 旧文件删除失败, 尝试直接覆盖");
            }
            if (!tmp.renameTo(file)) {
                // 个别系统 rename 失败: 降级为直接写目标文件
                y.save(file);
            }
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "groups.yml 保存失败", e);
        }
    }

    /** 有未落盘修改时保存 (自动保存任务调用) */
    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    // ==================== 绑定操作 ====================

    /**
     * 给玩家绑定权限组 (/bag group add <玩家> <组名>)
     *
     * @return 成功提示消息; 失败返回 null (原因消息已由调用方处理或此返回 null 表示组不存在)
     */
    public boolean bind(UUID uuid, String groupName) {
        if (plugin.getConfigManager().getGroupByName(groupName) == null) {
            return false; // 组不存在
        }
        bindings.put(uuid, groupName);
        dirty = true;
        save(); // 绑定操作低频, 立即落盘保证安全
        return true;
    }

    /**
     * 解除玩家的绑定 (/bag group remove <玩家>)
     *
     * @return true=原本有绑定并已解除; false=本就没有绑定
     */
    public boolean unbind(UUID uuid) {
        if (bindings.remove(uuid) != null) {
            dirty = true;
            save();
            return true;
        }
        return false;
    }

    /** 查询玩家的绑定组名 (未绑定返回 null) */
    public String getBinding(UUID uuid) {
        return bindings.get(uuid);
    }

    /** 全部绑定 (只读, info/list 指令展示用) */
    public Map<UUID, String> getBindings() {
        return bindings;
    }

    // ==================== 组查询辅助 ====================

    /** 绑定数量 (info 展示) */
    public int getBindingCount() {
        return bindings.size();
    }

    /** 把 UUID 翻译成玩家名 (在线优先, 离线查用户缓存; 查不到返回 UUID 短串) */
    public String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        if (name != null) {
            return name;
        }
        return uuid.toString().substring(0, 8);
    }

    /**
     * 查询玩家实际生效的组 (绑定优先, 权限节点其次, 兜底 default)
     * 在线/离线玩家通用 (离线玩家无权限判定, 仅看绑定)
     */
    public Group getEffectiveGroup(UUID uuid, Player onlinePlayer) {
        ConfigManager cfg = plugin.getConfigManager();
        // 1. 绑定的组 (最高优先, 在线离线都生效)
        String binding = bindings.get(uuid);
        if (binding != null) {
            Group group = cfg.getGroupByName(binding);
            if (group != null) {
                return group;
            }
        }
        // 2. 权限节点判定 (仅在线玩家可用)
        if (onlinePlayer != null) {
            return cfg.getGroup(onlinePlayer);
        }
        // 3. 离线且无绑定: default 兜底
        return cfg.getDefaultGroup();
    }
}
