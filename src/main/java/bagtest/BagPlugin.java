package bagtest;

import bagtest.command.BagCommand;
import bagtest.config.ConfigManager;
import bagtest.listener.GuiListener;
import bagtest.listener.ItemListener;
import bagtest.listener.PlayerListener;
import bagtest.manager.BagManager;
import bagtest.manager.GroupManager;
import bagtest.manager.ItemLogManager;
import bagtest.storage.Storage;
import bagtest.storage.StorageFactory;
import bagtest.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.logging.Level;

/**
 * 【模块八：主类】
 * 【ZHBO】plugin_BagTest_1.6.4 - MC 1.12.2 个人仓库插件
 * 作者: zhaobuobuo | 联系方式: 1071576270@QQ.COM
 *
 * 模块结构:
 *   config/   配置管理 + 热重载
 *   model/    玩家仓库数据模型
 *   storage/  存储层 (YAML / MySQL, 只搬运字符串)
 *   manager/  核心管理 (缓存 + 异步加载保存)
 *   gui/      界面 (Holder 标识 + 创建打开)
 *   listener/ 事件监听 (界面事件 + 玩家进退)
 *   command/  指令 (/bag)
 *   util/     工具 (颜色 / 物品序列化)
 *
 * 生命周期:
 *   onEnable  -> 加载配置 -> 初始化存储 -> 注册监听/指令 -> 定时任务 -> 预加载在线玩家
 *   onDisable -> 同步保存全部 -> 关闭存储 (异步线程已不可靠)
 */
public final class BagPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private Storage storage;
    private BagManager bagManager;
    private GroupManager groupManager;
    /** 特殊物品来源日志 (v1.5.0) */
    private ItemLogManager itemLogManager;
    /** 特殊物品监听引用 (v1.6.4: 关服时归还死亡保留物品兜底) */
    private ItemListener itemListener;

    /** 自动保存任务 (可配置关闭) */
    private BukkitTask autoSaveTask;
    /** 配置文件监听任务 (热重载, 可配置关闭) */
    private BukkitTask watchTask;
    /** 上次见到的 config.yml 修改时间 (变更检测) */
    private volatile long configLastModified;

    @Override
    public void onEnable() {
        // ---------- 1. 配置模块 ----------
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        // ---------- 2. 存储模块 ----------
        this.storage = StorageFactory.create(configManager, getLogger());
        try {
            storage.init();
        } catch (Exception e) {
            // MySQL 连接失败: 自动回退 YAML 文件存储, 保证插件可用
            getLogger().log(Level.SEVERE, "存储初始化失败 (" + storage.getTypeName()
                    + "), 回退到 YAML 文件存储: " + e.getMessage(), e);
            this.storage = new bagtest.storage.YamlStorage(configManager, getLogger());
            try {
                storage.init();
                getLogger().warning("已回退到 YAML 存储, 请检查 storage 配置后执行 /bag reload");
            } catch (Exception fatal) {
                getLogger().log(Level.SEVERE, "YAML 存储也无法初始化, 插件禁用", fatal);
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
        }

        // ---------- 3. 核心管理模块 ----------
        this.bagManager = new BagManager(this, storage);
        // 分组管理模块 (v1.1.0: 玩家-组绑定, /bag group 指令操作)
        this.groupManager = new GroupManager(this);
        this.groupManager.load();
        // 特殊物品来源日志模块 (v1.5.0: give/消耗 流转记录)
        this.itemLogManager = new ItemLogManager(this);

        // ---------- 4. 事件监听模块 ----------
        Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        // 特殊物品监听 (v1.3.0: 拓展器右键消耗; v1.6.4 保存引用供关服兜底)
        this.itemListener = new ItemListener(this);
        Bukkit.getPluginManager().registerEvents(itemListener, this);

        // ---------- 5. 指令模块 ----------
        PluginCommand cmd = getCommand("bag");
        if (cmd != null) {
            BagCommand executor = new BagCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // ---------- 6. 定时任务 ----------
        startAutoSaveTask();
        startConfigWatchTask();

        // ---------- 7. 预加载当前在线玩家 (支持 /reload 热启) ----------
        for (Player p : Bukkit.getOnlinePlayers()) {
            bagManager.loadAsync(p.getUniqueId(), p.getName(), true, null);
        }

        getLogger().info("【ZHBO】plugin_BagTest_1.6.4 已启用! 作者: zhaobuobuo | 联系: 1071576270@QQ.COM");
        getLogger().info("存储: " + storage.getTypeName()
                + ", 权限组: " + configManager.getGroupCount() + " 个"
                + ", 默认组: " + configManager.getDefaultGroup().pages + " 页 x "
                + configManager.getDefaultGroup().rows + " 行");
    }

    @Override
    public void onDisable() {
        // 停止定时任务 (防止关服过程中触发)
        cancelTasks();

        // v1.6.4 兜底: 归还死亡保留的特殊物品 (正常关服时玩家退出已归还,
        // 此处处理极端残留), 必须先于玩家 .dat 保存执行
        if (itemListener != null) {
            itemListener.restoreAllOnShutdown();
        }

        // v1.6.3 防复制: 先保存全部在线玩家数据 (.dat), 再保存仓库文件,
        // 两类数据同批落盘, 保证关服瞬间背包与仓库状态一致
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.saveData();
            } catch (Exception ignored) {
                // 单个玩家保存失败不阻断关服流程
            }
        }

        // 同步保存全部缓存数据 (关服时异步线程可能已被终止, 必须同步写)
        if (bagManager != null) {
            bagManager.saveAllSync();
        }
        // 分组绑定落盘 (v1.1.0)
        if (groupManager != null) {
            groupManager.saveIfDirty();
        }
        // 关闭存储连接
        if (storage != null) {
            storage.close();
        }
        getLogger().info("【ZHBO】plugin_BagTest_1.6.4 已卸载, 全部数据已保存.");
    }

    /** 启动自动保存任务 (auto-save-minutes = 0 时关闭) */
    private void startAutoSaveTask() {
        long minutes = configManager.getAutoSaveMinutes();
        if (minutes <= 0) {
            return;
        }
        long ticks = minutes * 60L * 20L;
        this.autoSaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            bagManager.saveAllAsync();
            groupManager.saveIfDirty(); // 分组绑定一并落盘 (v1.1.0)
        }, ticks, ticks);
    }

    /**
     * 启动配置文件监听任务 (热重载核心):
     * 异步线程周期检查 config.yml 的修改时间, 变化则回到主线程执行完整重载
     */
    private void startConfigWatchTask() {
        if (!configManager.isAutoReload()) {
            return;
        }
        // 记录初始修改时间 (在监听启动之前, 避免首次触发误重载)
        this.configLastModified = configFile().lastModified();
        long ticks = configManager.getCheckIntervalSeconds() * 20L;
        this.watchTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            long m = configFile().lastModified();
            if (m != configLastModified) {
                configLastModified = m;
                // 文件变更: 回到主线程执行重载 (避免异步线程触碰 Bukkit API)
                Bukkit.getScheduler().runTask(BagPlugin.this,
                        () -> reloadAll(Bukkit.getConsoleSender()));
            }
        }, ticks, ticks);
    }

    private File configFile() {
        return new File(getDataFolder(), "config.yml");
    }

    private void cancelTasks() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (watchTask != null) {
            watchTask.cancel();
            watchTask = null;
        }
    }

    /**
     * 【热重载完整流程】 (指令 /bag reload 或配置文件自动检测触发)
     *
     * 1. 重读 config.yml 全部配置;
     * 2. 若存储相关配置变化: 同步保存全部数据 -> 关闭旧存储 ->
     *    初始化新存储 -> 清空缓存 -> 在线玩家重新异步加载;
     * 3. 重启受配置影响的定时任务;
     * 4. 向触发者反馈耗时与存储状态。
     */
    public void reloadAll(org.bukkit.command.CommandSender feedback) {
        long start = System.currentTimeMillis();
        String oldSignature = configManager.storageSignature();
        String oldType = storage.getTypeName();

        // 1. 重读配置
        configManager.reload();
        // 分组绑定重新校验 (配置中删除的组绑定自动失效) + 未落盘数据补写
        groupManager.saveIfDirty();
        groupManager.load();

        // 2. 判断存储是否需要重建
        boolean storageChanged = !oldSignature.equals(configManager.storageSignature());
        if (storageChanged) {
            // 同步保存全部现有数据 (主线程, 保证顺序)
            bagManager.saveAllSync();
            storage.close();

            Storage newStorage = StorageFactory.create(configManager, getLogger());
            try {
                newStorage.init();
                this.storage = newStorage;
                this.bagManager = new BagManager(this, storage);
                // 在线玩家从新存储重新加载
                for (Player p : Bukkit.getOnlinePlayers()) {
                    bagManager.loadAsync(p.getUniqueId(), p.getName(), true, null);
                }
                getLogger().info("存储已切换: " + oldType + " -> " + storage.getTypeName());
            } catch (Exception e) {
                // 新存储初始化失败: 回退 YAML, 保证插件继续可用
                getLogger().log(Level.SEVERE, "新存储初始化失败, 回退 YAML: " + e.getMessage(), e);
                Storage fallback = new bagtest.storage.YamlStorage(configManager, getLogger());
                try {
                    fallback.init();
                    this.storage = fallback;
                    this.bagManager = new BagManager(this, storage);
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        bagManager.loadAsync(p.getUniqueId(), p.getName(), true, null);
                    }
                } catch (Exception fatal) {
                    getLogger().log(Level.SEVERE, "YAML 回退失败, 插件禁用", fatal);
                    Bukkit.getPluginManager().disablePlugin(this);
                    return;
                }
            }
        }

        // 3. 重启定时任务 (自动保存间隔/检测间隔可能已变化)
        cancelTasks();
        startAutoSaveTask();
        startConfigWatchTask();

        // 4. 反馈
        long time = System.currentTimeMillis() - start;
        String msg = configManager.getMsg("reloaded")
                .replace("{time}", String.valueOf(time))
                .replace("{storage}", storage.getTypeName());
        feedback.sendMessage(TextUtil.color(msg));
        if (storageChanged) {
            feedback.sendMessage(TextUtil.color(configManager.getMsg("storage-switched")
                    .replace("{old}", oldType)
                    .replace("{new}", storage.getTypeName())));
        }
    }

    // ==================== Getter ====================

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public Storage getStorage() {
        return storage;
    }

    public BagManager getBagManager() {
        return bagManager;
    }

    public GroupManager getGroupManager() {
        return groupManager;
    }

    /** 特殊物品来源日志 (v1.5.0) */
    public ItemLogManager getItemLogManager() {
        return itemLogManager;
    }
}
