package bagtest.listener;

import bagtest.BagPlugin;
import bagtest.config.ConfigManager;
import bagtest.gui.BagGui;
import bagtest.gui.BagHolder;
import bagtest.model.PlayerBag;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.UUID;

/**
 * 【GUI 层】
 * 界面事件监听器 v1.6.0 - 物品存取 + 翻页 + 锁定格 + 实时保存 + 体验券只读
 *
 * 设计说明:
 * 1. 普通格子: 不取消点击/拖拽事件, 让原版箱子式逻辑自然完成物品移动 (最稳),
 *    变动后的下一个游戏刻把界面内容同步回缓存并触发实时保存;
 * 2. 翻页按钮格 (最后一行两端): 取消一切物品操作, 可用按钮则翻页;
 * 3. 锁定格: 取消一切物品操作;
 *    仓库主人手持(光标)开锁器点击锁定格 -> 消耗一个开锁器并解锁该格;
 * 4. 只读页/只读格 (v1.6.0): 体验券到期后超出原权限的页与遗留物品的锁定格,
 *    只允许"取出方向"的点击 (拿取/shift 移出到背包/收集到光标),
 *    一切"存入方向"的操作 (放置/交换/shift 从背包移入/数字键交换) 全部取消;
 *    只读页取空后自动关闭, 只读格取空后恢复锁定占位;
 * 5. 关闭界面立即同步并异步保存 (双保险, 配合实时保存);
 * 6. 管理员查看离线玩家仓库: 关闭界面后延迟卸载缓存 (保存后移除)。
 *
 * 数据保护 (锁定格底层物品):
 * - 锁定期间同步界面时跳过锁定格, 底层旧物品保留在存档中, 解锁后自动恢复显示;
 * - 只读格同步以界面为准 (取出即清空底层), 取空后恢复锁定占位显示。
 */
public class GuiListener implements Listener {

    private final BagPlugin plugin;

    public GuiListener(BagPlugin plugin) {
        this.plugin = plugin;
    }

    /** 判断某界面的持有者是否为本插件仓库 (返回 null 表示不是本插件界面) */
    private BagHolder holderOf(Inventory inv) {
        if (inv != null && inv.getHolder() instanceof BagHolder) {
            return (BagHolder) inv.getHolder();
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        // 注意: 玩家点自己背包时 top inventory 仍是仓库, shift 操作会移动物品进仓库,
        // 因此用 getInventory() (顶部容器) 而非 getClickedInventory() 判断归属
        BagHolder holder = holderOf(event.getInventory());
        if (holder == null) {
            return;
        }

        int rawSlot = event.getRawSlot();
        // rawSlot 是"顶部容器+玩家背包"合并视图的绝对槽位, 顶部容器内 0 ~ size-1
        boolean topClick = rawSlot >= 0 && rawSlot < holder.getPageSize();

        // ---- 翻页按钮格: 取消一切物品操作, 可用按钮则翻页 ----
        if (topClick && holder.isPageButtonSlot(rawSlot)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player) {
                Player viewer = (Player) event.getWhoClicked();
                // 下一刻执行翻页 (避免在事件处理中直接重开界面)
                if (rawSlot == holder.getPrevSlot() && holder.hasPrev()) {
                    plugin.getConfigManager().playSound(viewer, "page-turn"); // 翻页音效 (v1.5.0)
                    turnPage(viewer, holder, holder.getPage() - 1);
                } else if (rawSlot == holder.getNextSlot() && holder.hasNext()) {
                    plugin.getConfigManager().playSound(viewer, "page-turn"); // 翻页音效 (v1.5.0)
                    turnPage(viewer, holder, holder.getPage() + 1);
                }
            }
            return;
        }

        // ---- 锁定格: 取消一切物品操作; 主人手持开锁器则解锁 (v1.3.0) ----
        if (topClick && holder.isLockedSlot(rawSlot)) {
            event.setCancelled(true);
            handleLockedSlotClick(event, holder, rawSlot);
            return;
        }

        // ---- 只读格 / 只读页 (v1.6.0): 拦截"存入方向"操作, 放行"取出方向" ----
        boolean readOnlyRelevant = holder.isReadOnly()
                || (topClick && holder.isReadOnlySlot(rawSlot));
        if (readOnlyRelevant) {
            if (isDepositIntoTop(event, holder)) {
                // 存入方向: 全部取消 (只读页/只读格内不能放东西)
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player) {
                    Player viewer = (Player) event.getWhoClicked();
                    viewer.sendMessage(plugin.getConfigManager().getMsg("ticket-readonly-deny"));
                    plugin.getConfigManager().playSound(viewer, "fail");
                }
                return;
            }
            // 取出方向: 放行, 下一刻同步 + 只读取空检测
            final Inventory top = event.getInventory();
            final BagHolder h = holder;
            final Player viewer = (Player) event.getWhoClicked();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sync(top, h)) {
                    plugin.getBagManager().saveRealtime(h.getOwner());
                }
                checkReadOnlyEmptied(top, h, viewer);
            });
            return;
        }

        // ---- 普通格子: 下一刻同步 + 实时保存 ----
        final Inventory top = event.getInventory();
        final BagHolder h = holder;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sync(top, h)) {
                plugin.getBagManager().saveRealtime(h.getOwner());
            }
        });
    }

    /**
     * 判断本次点击是否为"存入方向"操作 (物品会进入仓库顶部容器)
     * 只读页/只读格场景下用于拦截 (v1.6.0)
     *
     * 规则:
     * - shift 点击底部背包 -> 移入顶部 = 存入;
     * - 点击顶部时光标有物品 (放置/交换) = 存入;
     * - 数字键交换: 手持格有物品换入顶部 = 存入;
     * - 收集到光标 (双击) = 取出; shift 点击顶部移出 = 取出。
     */
    private boolean isDepositIntoTop(InventoryClickEvent event, BagHolder holder) {
        int raw = event.getRawSlot();
        boolean topClick = raw >= 0 && raw < holder.getPageSize();
        org.bukkit.event.inventory.ClickType ct = event.getClick();

        // shift: 点击底部背包 -> 物品移入顶部 = 存入; 点击顶部 -> 移出 = 取出
        if (ct == org.bukkit.event.inventory.ClickType.SHIFT_LEFT
                || ct == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT) {
            return !topClick;
        }
        if (!topClick) {
            return false; // 点击自己背包的非 shift 操作: 背包内部移动, 不进仓库
        }
        if (ct == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
            // 数字键交换: 手持格物品与顶部格互换, 手持格有物品 = 存入
            // (1.12.2 无 SWAP_OFFHAND 点击类型: F 键副手交换仅在玩家自己的
            //  物品栏界面生效, 容器界面客户端不发送该操作, 无需处理)
            ItemStack hotbar = event.getWhoClicked().getInventory()
                    .getItem(event.getHotbarButton());
            return hotbar != null && hotbar.getType() != Material.AIR;
        }
        if (ct == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) {
            return false; // 双击收集到光标 = 取出方向
        }
        // 普通/双击点击顶部: 光标有物品 = 放置或交换 (均含存入成分)
        ItemStack cursor = event.getCursor();
        return cursor != null && cursor.getType() != Material.AIR;
    }

    /**
     * 只读取空检测 (v1.6.0, 取出操作后的下一刻调用):
     * - 只读格取空 -> 恢复锁定占位显示, 并回归锁定格集合 (v1.6.2 修复:
     *   此前取空后该格既不在锁定集合也不在只读集合, 锁定占位物品会被同步
     *   写入存档造成数据污染);
     * - 只读页整页取空 -> 提示并自动关闭 (之后无法再打开)。
     *   判空时跳过翻页按钮格与锁定格占位 (占位物品不算真实物品, v1.6.2 修复:
     *   此前占位被计入导致只读页永远判不空、无法自动关闭)。
     */
    private void checkReadOnlyEmptied(Inventory top, BagHolder holder, Player viewer) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        ConfigManager cfg = plugin.getConfigManager();

        // ---- 只读格取空: 恢复锁定占位并回归锁定格集合 ----
        if (!holder.getReadOnlySlots().isEmpty()) {
            Iterator<Integer> it = holder.getReadOnlySlots().iterator();
            while (it.hasNext()) {
                int slot = it.next();
                ItemStack ui = top.getItem(slot);
                if (ui == null || ui.getType() == Material.AIR) {
                    it.remove(); // 退出只读集合
                    holder.getLockedSlots().add(slot); // 回归锁定格: sync 跳过, 防占位符入库
                    top.setItem(slot, cfg.buildLockItem()); // 恢复锁定占位显示
                }
            }
        }

        // ---- 只读页整页取空: 提示并关闭 ----
        if (holder.isReadOnly()) {
            boolean empty = true;
            for (int i = 0; i < holder.getPageSize(); i++) {
                // 翻页按钮格与锁定格占位不算真实物品
                if (holder.isPageButtonSlot(i) || holder.isLockedSlot(i)) {
                    continue;
                }
                ItemStack ui = top.getItem(i);
                if (ui != null && ui.getType() != Material.AIR) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                viewer.sendMessage(cfg.getMsg("ticket-page-empty"));
                cfg.playSound(viewer, "fail");
                viewer.closeInventory();
            }
        }
    }

    /** 锁定格点击处理: 主人光标/主手持开锁器 -> 消耗一个并解锁; 其他情况提示 */
    private void handleLockedSlotClick(InventoryClickEvent event, BagHolder holder, int slot) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player viewer = (Player) event.getWhoClicked();
        ConfigManager cfg = plugin.getConfigManager();

        // 只读页内禁止开锁 (v1.6.0): 防止体验到期后用开锁器变相永久解锁超权限页
        if (holder.isReadOnly()) {
            viewer.sendMessage(cfg.getMsg("ticket-readonly-page"));
            cfg.playSound(viewer, "fail");
            return;
        }

        // 只有仓库主人本人可以解锁 (管理员查看他人仓库时仅拦截)
        if (!viewer.getUniqueId().equals(holder.getOwner())) {
            return;
        }

        // 开锁器判定: 光标优先, 其次主手 (支持手持直接点击)
        // v1.6.1: 开锁器不绑定玩家, 任何人持有效开锁器均可解锁自己的仓库格
        ItemStack cursor = event.getCursor();
        ItemStack mainHand = viewer.getInventory().getItemInMainHand();
        boolean cursorIsUnlocker = cfg.isUnlocker(cursor);
        boolean handIsUnlocker = cfg.isUnlocker(mainHand);
        // 待消耗的开锁器 (来源信息从它读取, v1.5.0)
        ItemStack unlockerItem = cursorIsUnlocker ? cursor : (handIsUnlocker ? mainHand : null);
        if (cursorIsUnlocker || handIsUnlocker) {
            // ---- 消耗前捕获来源 (用于日志) ----
            String source = cfg.getItemSource(unlockerItem);

            // ---- 消耗一个开锁器 (优先扣光标上的, 每个开锁器仅解锁一个格子) ----
            if (cursorIsUnlocker) {
                int amount = cursor.getAmount();
                if (amount <= 1) {
                    event.setCursor(null);
                } else {
                    ItemStack less = cursor.clone();
                    less.setAmount(amount - 1);
                    event.setCursor(less);
                }
            } else {
                int amount = mainHand.getAmount();
                if (amount <= 1) {
                    viewer.getInventory().setItemInMainHand(null);
                } else {
                    mainHand.setAmount(amount - 1);
                }
            }

            // ---- 解锁格子并更新视图 ----
            PlayerBag bag = plugin.getBagManager().getBag(holder.getOwner());
            if (bag == null) {
                return;
            }
            bag.unlockSlot(holder.getPage(), slot);
            holder.getLockedSlots().remove(slot); // 本视图内该格转普通格
            // 重新显示该格底层物品 (可能是锁定前遗留的旧物品, 也可能为空)
            ItemStack[] pageData = bag.getPage(holder.getPage(), holder.getPageSize());
            event.getInventory().setItem(slot, slot < pageData.length ? pageData[slot] : null);

            // 实时保存 (解锁记录 + 页数据)
            plugin.getBagManager().saveRealtime(holder.getOwner());
            viewer.sendMessage(cfg.getMsg("slot-unlocked"));

            // ---- 成功特效 + 来源日志 (v1.5.0) ----
            cfg.playSound(viewer, "unlock");
            cfg.playParticle(viewer, "unlock");
            plugin.getItemLogManager().record(viewer.getName() + " 消耗开锁器(GUI点击) x1 | 来源: "
                    + (source != null ? source : "未知(旧版物品)")
                    + " | 解锁: 第" + holder.getPage() + "页 第" + slot + "格");
        } else {
            // 未手持开锁器: 提示引导 + 失败音效
            viewer.sendMessage(cfg.getMsg("slot-locked-hint"));
            cfg.playSound(viewer, "fail");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        BagHolder holder = holderOf(event.getInventory());
        if (holder == null) {
            return;
        }
        // 拖拽 = 放置物品 = 存入方向:
        // 涉及翻页按钮格/锁定格, 或目标页为只读页/含只读格时整体取消
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < holder.getPageSize()) {
                if (holder.isPageButtonSlot(rawSlot) || holder.isLockedSlot(rawSlot)
                        || holder.isReadOnly() || holder.isReadOnlySlot(rawSlot)) {
                    event.setCancelled(true);
                    if (holder.isReadOnly() || holder.isReadOnlySlot(rawSlot)) {
                        if (event.getWhoClicked() instanceof Player) {
                            Player viewer = (Player) event.getWhoClicked();
                            viewer.sendMessage(
                                    plugin.getConfigManager().getMsg("ticket-readonly-deny"));
                            plugin.getConfigManager().playSound(viewer, "fail");
                        }
                    }
                    return;
                }
            }
        }
        // 普通拖拽: 下一刻同步 + 实时保存
        final Inventory top = event.getInventory();
        final BagHolder h = holder;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sync(top, h)) {
                plugin.getBagManager().saveRealtime(h.getOwner());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        BagHolder holder = holderOf(event.getInventory());
        if (holder == null) {
            return;
        }
        // 关闭界面: 同步内容到缓存对应页并立即触发保存 (与实时保存互为双保险)
        if (sync(event.getInventory(), holder)) {
            plugin.getBagManager().saveAsync(holder.getOwner());
            // v1.6.3 防复制: 关闭界面时同步保存查看者玩家数据,
            // 确保背包状态与仓库文件同时落盘 (异常关服不产生两边都有物品)
            if (event.getPlayer() instanceof Player) {
                Player viewer = (Player) event.getPlayer();
                if (viewer.isOnline()) {
                    try {
                        viewer.saveData();
                    } catch (Exception ignored) {
                        // 保存失败不影响仓库数据落盘
                    }
                }
            }
        }

        // 管理员查看离线玩家仓库: 延迟 1 秒卸载缓存 (保存后移除, 防止缓存泄漏)
        // 卸载前再次确认: 玩家仍离线 且 没有其他管理员还开着该仓库界面
        UUID owner = holder.getOwner();
        if (Bukkit.getPlayer(owner) == null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (Bukkit.getPlayer(owner) == null && !isViewingByAnyone(owner)) {
                    plugin.getBagManager().unload(owner);
                }
            }, 20L);
        }
    }

    /** 点击翻页按钮: 下一刻打开新页 (旧页内容在每次点击后已持续同步)
     *  v1.6.2: 目标页打不开时沿点击方向步进查找最近的可开页
     *  (跳过"已取空的只读页"等中间页, 不刷屏不卡死) */
    private void turnPage(Player viewer, BagHolder holder, int newPage) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            // 玩家仍在线才翻页 (防止关闭后误开)
            if (!viewer.isOnline()) {
                return;
            }
            // 步进方向: 下一页按钮向高页码找, 上一页按钮向低页码找
            int dir = newPage > holder.getPage() ? 1 : -1;
            int page = newPage;
            // v1.6.4 修复: 步进上界此前用 pageLimit (基础组页数), 超出权限
            // 上限的只读页 (体验期间存过物品) 永远步进不到, 翻页按钮失效。
            // 改用 ownerMaxPages (已解锁页 + 有物品的只读页, 打开界面时计算)。
            while (page >= 1 && page <= holder.getOwnerMaxPages()) {
                // 静默尝试: 中间跳过的页不发送提示消息
                if (BagGui.open(plugin, viewer, holder.getOwner(),
                        holder.getOwnerName(), page, true)) {
                    return; // 打开成功
                }
                page += dir; // 该页不可开 (如已取空的只读页): 继续找
            }
            // 方向上无可开页: 仅播放失败音效
            plugin.getConfigManager().playSound(viewer, "fail");
        });
    }

    /** 检查是否还有在线玩家正开着指定归属者的仓库界面 */
    private boolean isViewingByAnyone(UUID owner) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof BagHolder
                    && owner.equals(((BagHolder) top.getHolder()).getOwner())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把界面当前内容同步回缓存对应页 (幂等, 可高频调用)
     * - 翻页按钮格: 数据置空 (打开时已把旧数据迁移走, 按钮物品绝不写入存档);
     * - 锁定格: 保留底层数据不变 (锁定期间不可见, 解锁后恢复显示);
     * - 只读格 (v1.6.0): 以界面为准 (取出即清空底层);
     * - 普通格: 以界面内容为准覆盖。
     */
    private boolean sync(Inventory top, BagHolder holder) {
        PlayerBag bag = plugin.getBagManager().getBag(holder.getOwner());
        if (bag == null) {
            return false; // 缓存已被卸载 (玩家退出等), 界面也随之失效
        }
        ItemStack[] data = bag.getPage(holder.getPage(), holder.getPageSize());
        ItemStack[] ui = top.getContents();
        for (int i = 0; i < data.length && i < ui.length; i++) {
            if (holder.isPageButtonSlot(i)) {
                data[i] = null; // 按钮格不存数据
            } else if (holder.isLockedSlot(i)) {
                // 锁定格: 保留底层数据 (锁定期不可见, 解锁后恢复)
            } else {
                data[i] = ui[i]; // 普通格/只读格: 以界面为准
            }
        }
        // setPage 内部自动拷贝 + 清理 AIR 伪物品
        bag.setPage(holder.getPage(), data);
        return true;
    }
}
