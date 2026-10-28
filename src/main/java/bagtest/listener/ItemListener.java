package bagtest.listener;

import bagtest.BagPlugin;
import bagtest.config.ConfigManager;
import bagtest.config.ConfigManager.Group;
import bagtest.model.PlayerBag;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【监听层】(v1.3.0 新增, v1.4.0 开锁器改右键, v1.5.0 绑定+防护+特效+日志)
 * 特殊物品监听器 - 拓展器/开锁器 右键消耗 + 防丢防转移
 *
 * 功能:
 * 1. 拓展器右键: 在 VIP 页数上限内开启一个新的仓库页 (音效+粒子);
 * 2. 开锁器右键: 自动找到"下一个未解锁的锁定格"并解锁 (音效+粒子),
 *    也可在打开仓库界面后手持开锁器直接点击锁定格 (走 GuiListener);
 * 3. 绑定校验 (v1.5.0): 开锁器绑定玩家后, 非绑定者右键/点击均无效且不消耗;
 * 4. 来源日志 (v1.5.0): 消耗时读取物品 lore 的来源行, 记入 itemlog.txt;
 * 5. 防丢防转移 (v1.5.0): 不可丢弃 / 不可被熔炉烧 / 不可被漏斗等容器转移,
 *    死亡不掉落, 重生自动归还。
 *
 * 物品识别: 材质+名称+描述前缀 精确比对 (附魔/绑定行/来源行不参与比对)。
 */
public class ItemListener implements Listener {

    private final BagPlugin plugin;

    /** 死亡保留的特殊物品 (玩家UUID -> 待归还物品, 重生时清空) */
    private final Map<UUID, List<ItemStack>> deathKept = new ConcurrentHashMap<UUID, List<ItemStack>>();

    public ItemListener(BagPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        // 仅右键触发 (空气/方块), 左键与物理碰撞不处理
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // 手中物品精确比对 (非特殊物品直接放行, 不影响原版交互)
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        ConfigManager cfg = plugin.getConfigManager();
        boolean isExpander = cfg.isExpander(item);
        boolean isUnlocker = cfg.isUnlocker(item);
        boolean isTicket3 = cfg.isTicket3(item);
        boolean isTicket7 = cfg.isTicket7(item);
        if (!isExpander && !isUnlocker && !isTicket3 && !isTicket7) {
            return;
        }

        // 是特殊物品: 取消原版交互 (防止放置/使用等副作用), 走消耗逻辑
        event.setCancelled(true);
        Player player = event.getPlayer();

        PlayerBag bag = plugin.getBagManager().getBag(player.getUniqueId());
        if (bag == null) {
            // 数据未加载完成 (进服瞬间右键的极端情况)
            player.sendMessage(cfg.getMsg("not-loaded"));
            cfg.playSound(player, "fail");
            return;
        }

        // ---- 消耗前捕获物品元数据 (绑定者/来源, consumeOne 后物品可能已变) ----
        String bindOwner = cfg.getBindOwner(item);
        String source = cfg.getItemSource(item);

        // ---- 真实基础组 (体验升级路径的判定基准, v1.6.1) ----
        Group baseGroup = plugin.getGroupManager().getEffectiveGroup(player.getUniqueId(), player);

        if (isTicket3 || isTicket7) {
            // 体验券: 临时升级到下一权限组 / 激活中叠加时间 (v1.6.1)
            handleTicket(event, player, bag, cfg, baseGroup,
                    isTicket3 ? "ticket3" : "ticket7", source);
            return;
        }

        // ---- 生效组: 体验激活且体验组更高时取体验组 (v1.6.1) ----
        Group group = resolveEffectiveGroup(bag, cfg, baseGroup);
        boolean ticketActive = group != baseGroup;
        int pageLimit = Math.min(group.pages,
                Math.max(cfg.getMaxTotalPages(), group.pages));
        // 已解锁页数: 体验期间体验组全部页面默认解锁; 平时 = 1 + 拓展器数 (v1.6.1)
        int unlockedPages = ticketActive ? pageLimit
                : Math.min(1 + bag.getBonusPages(), pageLimit);

        if (isExpander) {
            handleExpander(event, player, bag, cfg, pageLimit, unlockedPages, bindOwner, source);
        } else {
            handleUnlocker(event, player, bag, cfg, unlockedPages, group, source);
        }
    }

    /**
     * 解析当前生效权限组 (v1.6.2 动态解析):
     * 体验激活中 -> 恒定取"基础组的上一级" (getNextGroup), 而非存档记录的组名。
     * 效果: 基础组中途变化时体验自动跟随调整 (升到 vip1 -> 体验变 vip2),
     * 体验永不被基础组变化"失效", 倒计时/体验页不会凭空消失。
     */
    private Group resolveEffectiveGroup(PlayerBag bag, ConfigManager cfg, Group baseGroup) {
        if (bag.isTicketActive()) {
            Group next = cfg.getNextGroup(baseGroup);
            if (next != null) {
                return next;
            }
        }
        return baseGroup;
    }

    /**
     * 体验券右键 (v1.6.2 动态规则):
     * - 体验组恒定为"基础组的上一级" (实时解析, 随基础组变化自动调整):
     *     default -> vip1, vip1 -> vip2, vip2 -> svip ...;
     * - 体验激活中再使用: 叠加时间 (到期时间 += 券时长), 不重复升级;
     * - 基础组已是最高组: 提示且不消耗;
     * - 体验券不绑定玩家 (任何人都可使用)。
     */
    private void handleTicket(PlayerInteractEvent event, Player player, PlayerBag bag,
                              ConfigManager cfg, Group baseGroup, String type, String source) {
        int days = cfg.getTicketDays(type);
        long duration = days * 86400000L;

        // ---- 动态体验组: 基础组的上一级 (v1.6.2) ----
        Group nextGroup = cfg.getNextGroup(baseGroup);
        if (nextGroup == null) {
            // 基础组已是最高组: 无更高级可体验, 不消耗
            player.sendMessage(cfg.getMsg("ticket-max")
                    .replace("{group}", baseGroup.name));
            cfg.playSound(player, "fail");
            return;
        }

        // ---- 分支一: 体验激活中 -> 叠加时间, 体验组保持动态跟随 (v1.6.2) ----
        if (bag.isTicketActive()) {
            consumeOne(event, player);
            long newExpire = bag.getTicketExpire() + duration;
            // 记录组名同步为当前动态组 (仅作展示/日志用, 判定一律动态解析)
            bag.setTicket(nextGroup.name, newExpire);
            plugin.getBagManager().saveRealtime(player.getUniqueId());

            player.sendMessage(cfg.getMsg("ticket-extend")
                    .replace("{group}", nextGroup.name)
                    .replace("{time}", ConfigManager.formatRemain(
                            newExpire - System.currentTimeMillis())));

            cfg.playSound(player, "ticket");
            cfg.playParticle(player, "ticket");
            plugin.getItemLogManager().record(player.getName() + " 使用体验券(" + days
                    + "天) x1 | 来源: " + (source != null ? source : "未知(旧版物品)")
                    + " | 叠加时间: " + nextGroup.name
                    + " 新到期时间戳: " + newExpire);
            return;
        }

        // ---- 分支二: 首次/过期后使用 -> 临时升级到基础组的上一级 ----
        consumeOne(event, player);

        // ---- 生效体验: 记录组名与到期时间戳 (毫秒) ----
        long expire = System.currentTimeMillis() + duration;
        bag.setTicket(nextGroup.name, expire);
        plugin.getBagManager().saveRealtime(player.getUniqueId());

        player.sendMessage(cfg.getMsg("ticket-used")
                .replace("{group}", nextGroup.name)
                .replace("{time}", days + "天"));

        // ---- 成功特效 + 来源日志 (v1.6.0) ----
        cfg.playSound(player, "ticket");
        cfg.playParticle(player, "ticket");
        plugin.getItemLogManager().record(player.getName() + " 使用体验券(" + days
                + "天) x1 | 来源: " + (source != null ? source : "未知(旧版物品)")
                + " | 临时升级: " + baseGroup.name + " -> " + nextGroup.name
                + " | 到期时间戳: " + expire);
    }

    /**
     * 拓展器右键: 已解锁页数 < VIP 上限时消耗一个并开启新页 (音效+粒子+日志)
     * v1.6.1: 拓展器是唯一绑定玩家的特殊物品, 非绑定者右键无效且不消耗
     */
    private void handleExpander(PlayerInteractEvent event, Player player, PlayerBag bag,
                                ConfigManager cfg, int pageLimit, int unlockedPages,
                                String bindOwner, String source) {
        // ---- 绑定校验: 拓展器绑定了别的玩家 -> 拒绝使用 (v1.6.1) ----
        if (bindOwner != null && !bindOwner.equalsIgnoreCase(player.getName())) {
            player.sendMessage(cfg.getMsg("unlocker-not-owner")
                    .replace("{player}", bindOwner));
            cfg.playSound(player, "fail");
            return; // 不消耗物品
        }

        // ---- VIP 页数上限校验 (VIP 只是权力, 页面靠拓展器逐页解锁) ----
        if (unlockedPages >= pageLimit) {
            player.sendMessage(cfg.getMsg("expander-max")
                    .replace("{max}", String.valueOf(pageLimit)));
            cfg.playSound(player, "fail");
            return; // 达上限: 不消耗物品
        }

        // ---- 消耗一个拓展器 (区分主手/副手) ----
        consumeOne(event, player);

        // ---- 拓展页数 +1 并实时保存 ----
        bag.setBonusPages(bag.getBonusPages() + 1);
        plugin.getBagManager().saveRealtime(player.getUniqueId());
        int newPage = unlockedPages + 1; // 新解锁的页码 = 消耗前的已解锁页数 + 1
        player.sendMessage(cfg.getMsg("expander-used")
                .replace("{page}", String.valueOf(newPage)));

        // ---- 成功特效 + 来源日志 (v1.5.0) ----
        cfg.playSound(player, "expand");
        cfg.playParticle(player, "expand");
        plugin.getItemLogManager().record(player.getName() + " 消耗拓展器 x1 | 来源: "
                + (source != null ? source : "未知(旧版物品)") + " | 开启新页: 第" + newPage + "页");
    }

    /**
     * 开锁器右键: 自动解锁下一个未解锁的锁定格
     * (v1.6.1: 开锁器不绑定玩家, 任何人持有效开锁器均可使用)
     */
    private void handleUnlocker(PlayerInteractEvent event, Player player, PlayerBag bag,
                                ConfigManager cfg, int unlockedPages, Group group,
                                String source) {
        // ---- 在已解锁的页中按 页码/槽位 顺序找第一个仍锁定的格子 ----
        int baseSize = group.rows * 9;
        int foundPage = -1;
        int foundSlot = -1;
        for (int page = 1; page <= unlockedPages && foundPage < 0; page++) {
            // v1.6.4 修复: 页大小与 GUI (BagGui.open) 保持一致 --
            // 取 max(组行数, 该页现有数据长度), 防止热重载缩小 rows 后,
            // 超出组行数的历史大页中锁定格永远右键找不到 (界面里却显示锁定)。
            ItemStack[] existing = bag.getPageIfExists(page);
            int pageSize = Math.min(54, Math.max(baseSize,
                    existing != null ? existing.length : 0));
            Set<Integer> locked = cfg.computeLockedSlots(pageSize);
            locked.removeAll(bag.getUnlockedSlots(page));
            if (!locked.isEmpty()) {
                // TreeSet 排序后取最小槽位 (computeLockedSlots 返回 TreeSet)
                TreeSet<Integer> sorted = (TreeSet<Integer>) locked;
                foundPage = page;
                foundSlot = sorted.first();
            }
        }
        if (foundPage < 0) {
            // 没有任何可解锁的格子: 提示且不消耗
            player.sendMessage(cfg.getMsg("unlocker-none"));
            cfg.playSound(player, "fail");
            return;
        }

        // ---- 消耗一个开锁器 ----
        consumeOne(event, player);

        // ---- 解锁该格并实时保存 ----
        bag.unlockSlot(foundPage, foundSlot);
        plugin.getBagManager().saveRealtime(player.getUniqueId());
        player.sendMessage(cfg.getMsg("unlocker-used")
                .replace("{page}", String.valueOf(foundPage)));

        // ---- 成功特效 + 来源日志 (v1.5.0) ----
        cfg.playSound(player, "unlock");
        cfg.playParticle(player, "unlock");
        plugin.getItemLogManager().record(player.getName() + " 消耗开锁器 x1 | 来源: "
                + (source != null ? source : "未知(旧版物品)")
                + " | 解锁: 第" + foundPage + "页 第" + foundSlot + "格");
    }

    // ==================== 防丢防转移 (v1.5.0) ====================

    /** 丢弃拦截: 开锁器/拓展器不可扔在地上 (Q 键/关闭界面丢弃均触发) */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        ConfigManager cfg = plugin.getConfigManager();
        if (cfg.isSpecialItem(item)) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            player.sendMessage(cfg.getMsg("item-cant-drop"));
            cfg.playSound(player, "fail");
        }
    }

    /** 熔炉拦截: 特殊物品放入熔炉也不会被烧炼 (成品不产出) */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        if (plugin.getConfigManager().isSpecialItem(event.getSource())) {
            event.setCancelled(true);
        }
    }

    /** 容器转移拦截: 漏斗/漏斗矿车/投掷器等一切自动搬运都无法转移特殊物品 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (plugin.getConfigManager().isSpecialItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /**
     * 吸取拦截 (v1.6.4 修复): 漏斗/漏斗矿车吸取地上的特殊物品掉落物。
     * 此前只拦截了容器间转移 (InventoryMoveItemEvent), 掉落物被吸进漏斗
     * 后会卡死在漏斗里 (转移被拦) 无法取回, 现在直接不允许被吸取。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperPickup(org.bukkit.event.inventory.InventoryPickupItemEvent event) {
        if (plugin.getConfigManager().isSpecialItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** 死亡保留: 特殊物品不掉落 (从掉落物移除, 待重生归还) */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        ConfigManager cfg = plugin.getConfigManager();
        List<ItemStack> kept = null;
        Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (cfg.isSpecialItem(item)) {
                if (kept == null) {
                    kept = new ArrayList<ItemStack>();
                }
                kept.add(item);
                it.remove(); // 从地面掉落物中移除
            }
        }
        if (kept != null) {
            deathKept.put(event.getEntity().getUniqueId(), kept);
        }
    }

    /** 重生归还: 把死亡保留的特殊物品放回背包 (满则掉脚下, 不丢失) */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        final List<ItemStack> kept = deathKept.remove(player.getUniqueId());
        if (kept == null || kept.isEmpty()) {
            return;
        }
        // 下一刻归还 (复活当刻背包可能尚未就绪)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                // v1.6.4 修复: 重生后 1 tick 内退出 (秒退), 此前直接 return
                // 物品凭空丢失。放回 deathKept 由下次进服 (onJoin) 归还。
                deathKept.put(player.getUniqueId(), kept);
                return;
            }
            for (ItemStack item : kept) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                for (ItemStack rest : leftover.values()) {
                    // 背包已满: 脚下掉落兜底 (自然掉落不走丢弃事件, 不会被拦截)
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
            player.sendMessage(plugin.getConfigManager().getMsg("item-death-kept"));
            // v1.6.3 防复制: 归还后立即保存玩家数据, 防止异常关服回档后物品双份
            try {
                player.saveData();
            } catch (Exception ignored) {
                // 保存失败不影响归还流程
            }
        });
    }

    /**
     * 退出兜底 (v1.6.4 修复): 死亡未重生直接退出游戏 (死亡界面点退出),
     * 此前 deathKept 中的物品无人认领, 物品永久丢失。
     * 退出当刻把物品塞回背包, 服务器随后的玩家数据保存 (.dat) 会带上它们。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        List<ItemStack> kept = deathKept.remove(event.getPlayer().getUniqueId());
        if (kept == null || kept.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        returnKeptItems(player, kept);
    }

    /** 进服兜底 (v1.6.4): 归还 deathKept 中的遗留物品 (重生后秒退等场景) */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final List<ItemStack> kept = deathKept.remove(player.getUniqueId());
        if (kept == null || kept.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                deathKept.put(player.getUniqueId(), kept); // 秒退: 留给下次进服
                return;
            }
            returnKeptItems(player, kept);
        });
    }

    /** 归还保留物品到背包 (满则脚下掉落) 并立即保存玩家数据 */
    private void returnKeptItems(Player player, List<ItemStack> kept) {
        for (ItemStack item : kept) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        try {
            player.saveData();
        } catch (Exception ignored) {
            // 保存失败不影响归还
        }
    }

    /** 关服兜底 (v1.6.4): 正常关服时玩家退出已全部归还, 此处仅对
     *  极端残留输出日志提示, 防止物品无痕消失 (管理员可凭日志人工处理) */
    public void restoreAllOnShutdown() {
        for (Map.Entry<UUID, List<ItemStack>> e : deathKept.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) {
                returnKeptItems(p, e.getValue());
            } else {
                plugin.getLogger().warning("关服时仍有 " + e.getValue().size()
                        + " 个保留物品未能归还给玩家 " + e.getKey()
                        + " (已离线), 请管理员人工核查!");
            }
        }
        deathKept.clear();
    }

    // ==================== 消耗工具 ====================

    /** 消耗手中一个物品 (区分主手/副手, 供拓展器/开锁器共用) */
    private void consumeOne(PlayerInteractEvent event, Player player) {
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        int amount = item.getAmount();
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            if (amount <= 1) {
                player.getInventory().setItemInOffHand(null);
            } else {
                item.setAmount(amount - 1);
                player.getInventory().setItemInOffHand(item);
            }
        } else {
            // 主手 (含个别核心不传 hand 的兼容分支)
            if (amount <= 1) {
                player.getInventory().setItemInMainHand(null);
            } else {
                item.setAmount(amount - 1);
                player.getInventory().setItemInMainHand(item);
            }
        }
    }
}
