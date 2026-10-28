package bagtest.manager;

import bagtest.BagPlugin;
import bagtest.model.PlayerBag;
import bagtest.storage.Storage;
import bagtest.util.ItemSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 【模块四：核心管理层】
 * 仓库管理器 v3 - 多页仓库的缓存 + 加载 + 保存 + 实时保存
 *
 * 数据格式 v3 (多页 + 锁定格 + 拓展页):
 *   version: 3
 *   name: 玩家名
 *   bonus-pages: 拓展器开启的额外页数
 *   unlocked:          每页已解锁槽位
 *     1: [27, 28, 29]
 *   pages:
 *     1:
 *       items:
 *         0: <base64 物品>
 *         3: <base64 物品>
 *
 * 兼容性: 自动识别旧版 v1 单页数据 (顶层 items 节点) 并迁移为第 1 页,
 * v2/v1 数据无 bonus-pages/unlocked 字段时按 0/空处理, 玩家无感知升级。
 *
 * 线程模型 (保证稳定的核心):
 *   加载: 异步线程读原始字符串 -> 主线程反序列化并放入缓存
 *   保存: 主线程序列化为字符串 -> 异步线程写盘/写库
 *   => ItemStack 全部只在主线程接触, 数据文件/数据库只在异步线程接触
 *
 * 实时保存 (v1.3.0): saveRealtime 采用"合并写入"策略 -
 *   界面每次物品变动后调用, 同一玩家 2 tick 内的连续操作合并为一次落盘,
 *   既做到"放进去就保存"的实时性, 又不会因连续快速点击产生大量 IO。
 *
 * 性能优化:
 *   1. 空页不落盘 (全 null 的页跳过序列化, 文件最小化);
 *   2. 空槽位不序列化 (只写非空物品);
 *   3. 页数组懒创建 (见 PlayerBag);
 *   4. 序列化与 IO 分离 (主线程只做内存操作, 磁盘/网络全部异步);
 *   5. 实时保存合并写入 (连续 GUI 操作只触发一次磁盘写)。
 */
public class BagManager {

    private final BagPlugin plugin;
    private final Storage storage;

    /** 在线玩家仓库缓存: UUID -> 仓库数据 */
    private final Map<UUID, PlayerBag> cache = new ConcurrentHashMap<>();

    /** 打开冷却记录: 玩家UUID -> 上次打开时间戳(毫秒), 退出时清理防泄漏 */
    private final Map<UUID, Long> openCooldown = new ConcurrentHashMap<>();

    /** 已排队的实时保存玩家集合 (同一玩家 2 tick 内只排队一次, 合并写入) */
    private final java.util.Set<UUID> pendingRealtime =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    public BagManager(BagPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    // ==================== 打开冷却 ====================

    /**
     * 检查玩家是否处于打开冷却中; 不在冷却则记录本次时间并放行
     *
     * @return 剩余冷却秒数; 0 表示可以打开
     */
    public long checkOpenCooldown(UUID uuid) {
        int cooldownSeconds = plugin.getConfigManager().getOpenCooldown();
        if (cooldownSeconds <= 0) {
            return 0; // 功能关闭
        }
        long now = System.currentTimeMillis();
        Long last = openCooldown.get(uuid);
        if (last == null) {
            openCooldown.put(uuid, now);
            return 0;
        }
        long elapsed = (now - last) / 1000L;
        if (elapsed >= cooldownSeconds) {
            openCooldown.put(uuid, now);
            return 0;
        }
        return cooldownSeconds - elapsed;
    }

    /** 清理玩家的冷却记录 (退出服务器时调用) */
    public void clearCooldown(UUID uuid) {
        openCooldown.remove(uuid);
    }

    // ==================== 加载 ====================

    /**
     * 异步加载玩家仓库 (进服预加载 / 管理员查看离线玩家时触发)
     *
     * @param uuid          玩家 UUID
     * @param name          玩家名
     * @param requireOnline true=玩家须在线才入缓存(进服预加载用, 防泄漏)
     *                      false=离线玩家也加载(管理员查看用, 由界面关闭时卸载)
     * @param afterLoaded   加载完成后的回调 (主线程执行, 可为 null)
     */
    public void loadAsync(final UUID uuid, final String name,
                          final boolean requireOnline, final Runnable afterLoaded) {
        // 已有缓存: 直接回调, 不重复加载 (防止覆盖未保存的修改)
        if (cache.containsKey(uuid)) {
            if (afterLoaded != null) {
                afterLoaded.run();
            }
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String raw = storage.loadRaw(uuid);
            // 回到主线程完成 ItemStack 反序列化
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (requireOnline && Bukkit.getPlayer(uuid) == null) {
                    return; // 玩家已下线, 丢弃避免缓存泄漏
                }
                if (!cache.containsKey(uuid)) {
                    PlayerBag bag = fromYaml(uuid, raw);
                    bag.setLastName(name);
                    cache.put(uuid, bag);
                }
                if (afterLoaded != null) {
                    afterLoaded.run();
                }
            });
        });
    }

    /** 数据是否已加载完成 (指令打开 GUI 前必须检查) */
    public boolean isLoaded(UUID uuid) {
        return cache.containsKey(uuid);
    }

    /** 获取缓存中的仓库 (未加载返回 null) */
    public PlayerBag getBag(UUID uuid) {
        return cache.get(uuid);
    }

    // ==================== 保存 ====================

    /**
     * 异步保存单个玩家 (主线程调用)
     * 序列化在主线程完成后, 仅把字符串交给异步线程
     */
    public void saveAsync(UUID uuid) {
        PlayerBag bag = cache.get(uuid);
        if (bag == null) {
            return;
        }
        final String yaml = toYaml(bag);
        final String name = bag.getLastName();
        if (yaml == null) {
            return; // 序列化整体失败, 保留缓存等待下次保存
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> storage.saveRaw(uuid, name, yaml));
    }

    /** 异步保存全部缓存 (自动保存任务调用), 返回保存人数 */
    public int saveAllAsync() {
        int count = 0;
        for (UUID uuid : cache.keySet()) {
            saveAsync(uuid);
            count++;
        }
        return count;
    }

    /**
     * 实时保存 (v1.3.0): 界面物品变动后立即调用
     * 合并策略: 同一玩家 2 tick 内的连续操作只触发一次落盘,
     * 保存时序列化的是"当时最新"的缓存数据, 因此连续变动不会丢;
     * 玩家退出时 pendingRealtime 中的残留排队由 unload 兜底保存。
     *
     * v1.6.3 防复制修复: 保存仓库的同时同步保存玩家数据 (.dat)。
     *   此前异常关服会复制物品: 插件仓库文件 2 tick 内实时落盘,
     *   而原版玩家 .dat 停留在上次自动保存 (默认 5 分钟) --
     *   崩溃回档后背包是旧数据 (物品还在背包), 仓库是新数据 (物品也在仓库)。
     *   绑定后两个文件在同一合并窗口内相继落盘, 复制窗口从分钟级缩到毫秒级。
     */
    public void saveRealtime(UUID uuid) {
        if (!cache.containsKey(uuid)) {
            return;
        }
        // 已有排队中的保存: 新变动会体现在那次保存里, 不重复排队
        if (!pendingRealtime.add(uuid)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingRealtime.remove(uuid);
            saveAsync(uuid);
            // 绑定保存玩家数据 (主线程同步写 .dat, 文件极小开销可忽略)
            org.bukkit.entity.Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                try {
                    p.saveData();
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "玩家数据保存失败 (防复制保护): " + uuid, ex);
                }
            }
        }, 2L);
    }

    /**
     * 同步保存全部缓存 (关服 / 切换存储时调用, 主线程直接执行)
     * 注: 关服流程中异步线程不可靠, 必须同步落盘
     */
    public void saveAllSync() {
        for (Map.Entry<UUID, PlayerBag> e : cache.entrySet()) {
            String yaml = toYaml(e.getValue());
            if (yaml != null) {
                storage.saveRaw(e.getKey(), e.getValue().getLastName(), yaml);
            }
        }
    }

    /**
     * 卸载玩家缓存 (退出服务器时调用): 先序列化再移除, 异步写盘
     */
    public void unload(UUID uuid) {
        PlayerBag bag = cache.remove(uuid);
        if (bag == null) {
            return;
        }
        final String yaml = toYaml(bag);
        if (yaml != null) {
            final String name = bag.getLastName();
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> storage.saveRaw(uuid, name, yaml));
        }
    }

    /** 清空全部缓存 (切换存储后由在线玩家重新加载) */
    public void clearCache() {
        cache.clear();
    }

    /** 当前缓存数量 (info 展示) */
    public int getCacheCount() {
        return cache.size();
    }

    // ==================== 序列化 (仅主线程调用) ====================

    /**
     * 仓库对象 -> YAML 字符串 (v3 多页 + 锁定格 + 拓展页格式)
     * 性能: 空页与空槽位全部跳过, 输出文件最小化
     */
    private String toYaml(PlayerBag bag) {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("version", 3);
            y.set("name", bag.getLastName());
            // 拓展器开启的额外页数 (v1.3.0)
            if (bag.getBonusPages() > 0) {
                y.set("bonus-pages", bag.getBonusPages());
            }
            // 体验券记录 (v1.6.0; 无记录不落盘)
            if (bag.hasTicketRecord()) {
                y.set("ticket.group", bag.getTicketGroup());
                y.set("ticket.expire", bag.getTicketExpire());
            }
            // 累计获得的拓展器数量 (v1.6.0; 0 不落盘)
            if (bag.getGainedExpanders() > 0) {
                y.set("gained-expanders", bag.getGainedExpanders());
            }
            // 每页已解锁的槽位 (v1.3.0; 空记录不落盘)
            for (Map.Entry<Integer, java.util.TreeSet<Integer>> entry
                    : bag.getUnlockedSlotsAll().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    y.set("unlocked." + entry.getKey(),
                            new java.util.ArrayList<Integer>(entry.getValue()));
                }
            }
            for (Map.Entry<Integer, ItemStack[]> pageEntry : bag.getPages().entrySet()) {
                int page = pageEntry.getKey();
                ItemStack[] items = pageEntry.getValue();
                boolean pageHasItem = false;
                for (int i = 0; i < items.length; i++) {
                    ItemStack item = items[i];
                    if (item != null && item.getType() != Material.AIR) {
                        String b64 = ItemSerializer.itemToBase64(item, plugin.getLogger());
                        if (b64 != null) {
                            y.set("pages." + page + ".items." + i, b64);
                            pageHasItem = true;
                        }
                    }
                }
                // 空页不落盘: 反序列化时按需懒创建, 数据不丢失
                if (!pageHasItem) {
                    y.set("pages." + page, null);
                }
            }
            return y.saveToString();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "仓库序列化失败 (" + bag.getOwner() + "), 本次跳过保存", e);
            return null;
        }
    }

    /**
     * YAML 字符串 -> 仓库对象
     * 兼容: v2 多页格式 / v1 单页格式 (自动迁移为第 1 页)
     * 任何数据损坏都降级为空仓库, 保证不崩溃
     */
    private PlayerBag fromYaml(UUID uuid, String raw) {
        PlayerBag bag = new PlayerBag(uuid);
        if (raw == null || raw.isEmpty()) {
            return bag; // 新玩家
        }
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(raw);
            bag.setLastName(y.getString("name", ""));

            // ---- v1.3.0 新字段: 拓展页数 + 已解锁槽位 (旧数据无此字段按默认处理) ----
            bag.setBonusPages(y.getInt("bonus-pages", 0));
            // ---- v1.6.0 新字段: 体验券记录 + 拓展器累计获得数 ----
            String ticketGroup = y.getString("ticket.group", "");
            long ticketExpire = y.getLong("ticket.expire", 0L);
            if (ticketGroup != null && !ticketGroup.isEmpty() && ticketExpire > 0) {
                bag.setTicket(ticketGroup, ticketExpire);
            }
            bag.setGainedExpanders(y.getInt("gained-expanders", 0));
            ConfigurationSection unlockedSec = y.getConfigurationSection("unlocked");
            if (unlockedSec != null) {
                for (String pageKey : unlockedSec.getKeys(false)) {
                    int page;
                    try {
                        page = Integer.parseInt(pageKey);
                    } catch (NumberFormatException ignored) {
                        continue; // 非法页码键跳过
                    }
                    if (page < 1 || page > 100) {
                        continue;
                    }
                    for (int slot : unlockedSec.getIntegerList(pageKey)) {
                        bag.unlockSlot(page, slot); // unlockSlot 内部过滤非法槽位
                    }
                }
            }

            // ---- v2 多页格式 ----
            ConfigurationSection pagesSec = y.getConfigurationSection("pages");
            if (pagesSec != null) {
                for (String pageKey : pagesSec.getKeys(false)) {
                    int page;
                    try {
                        page = Integer.parseInt(pageKey);
                    } catch (NumberFormatException ignored) {
                        continue; // 非法页码键跳过
                    }
                    if (page < 1 || page > 100) {
                        continue; // 页码超出安全范围跳过
                    }
                    ConfigurationSection itemsSec =
                            pagesSec.getConfigurationSection(pageKey + ".items");
                    if (itemsSec == null) {
                        continue;
                    }
                    // 先按数据中最大槽位确定页大小 (打开时再按配置 resize)
                    int maxSize = 0;
                    for (String slotKey : itemsSec.getKeys(false)) {
                        try {
                            maxSize = Math.max(maxSize, Integer.parseInt(slotKey) + 1);
                        } catch (NumberFormatException ignored) {
                            // 非法槽位键忽略
                        }
                    }
                    if (maxSize <= 0) {
                        continue; // 该页无有效物品
                    }
                    ItemStack[] items = new ItemStack[Math.max(9, maxSize)];
                    for (String slotKey : itemsSec.getKeys(false)) {
                        int slot;
                        try {
                            slot = Integer.parseInt(slotKey);
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        if (slot >= 0 && slot < items.length) {
                            items[slot] = ItemSerializer.base64ToItem(
                                    itemsSec.getString(slotKey), plugin.getLogger());
                        }
                    }
                    bag.setPage(page, items);
                }
                return bag;
            }

            // ---- v1 旧单页格式: 全部物品迁移到第 1 页 ----
            ConfigurationSection legacyItems = y.getConfigurationSection("items");
            if (legacyItems != null) {
                int maxSize = 0;
                for (String slotKey : legacyItems.getKeys(false)) {
                    try {
                        maxSize = Math.max(maxSize, Integer.parseInt(slotKey) + 1);
                    } catch (NumberFormatException ignored) {
                        // 非法槽位键忽略
                    }
                }
                if (maxSize > 0) {
                    ItemStack[] items = new ItemStack[Math.max(9, maxSize)];
                    for (String slotKey : legacyItems.getKeys(false)) {
                        int slot;
                        try {
                            slot = Integer.parseInt(slotKey);
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        if (slot >= 0 && slot < items.length) {
                            items[slot] = ItemSerializer.base64ToItem(
                                    legacyItems.getString(slotKey), plugin.getLogger());
                        }
                    }
                    bag.setPage(1, items);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "仓库数据解析失败 (" + uuid + "), 按空仓库处理, 原始数据未被覆盖", e);
        }
        return bag;
    }
}
