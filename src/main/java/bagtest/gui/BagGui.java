package bagtest.gui;

import bagtest.BagPlugin;
import bagtest.config.ConfigManager;
import bagtest.config.ConfigManager.Group;
import bagtest.manager.BagManager;
import bagtest.model.PlayerBag;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 【GUI 层】
 * 仓库界面 v1.6.0 - 多页仓库 + 界面翻页按钮 + 锁定格 + 体验券
 *
 * 界面结构: 存储格子 + 最后一行两端的翻页按钮 (左=上一页, 右=下一页)
 *   - 可翻页: 箭头物品, 点击翻页;
 *   - 下一页在 VIP 权限内但未解锁: 屏障"未解锁" (需拓展器开启);
 *   - 下一页超出 VIP 权限: 屏障"权限不足" (标注需要的组);
 *   - 已到第一页边界: 屏障显示边界提示。
 *
 * 页数模型 (v1.4.0): VIP 只是"解锁权力", 页面需拓展器逐页解锁
 *   - 已解锁页数 = 1 (基础) + bonusPages (拓展器已用数量);
 *   - 页数上限 = VIP 组 pages 值 (拓展器最多解锁到此数)。
 *
 * 体验券模型 (v1.6.0):
 *   - 激活中: 生效组 = 体验组 (取基础组与体验组页数较大者),
 *     权限内全部页面可开 + 全部锁定格临时解锁, 标题追加"剩余时间";
 *   - 已到期: 回退基础组; 超出原权限但有物品的页进入"只读模式"
 *     (只能取物不能存, 取空后无法再打开); 锁定格中遗留的物品同样只读可取。
 *
 * 翻页按钮占用的两个格子数据保护:
 *   - 打开时若旧数据在这两格有物品, 自动挪到本页其他空格 (无空格则保留数据不显示);
 *   - 同步界面到缓存时跳过这两格 (按钮物品绝不写入存档);
 *   - 点击/拖拽涉及这两格时全部取消 (物品无法进出)。
 */
public final class BagGui {

    private BagGui() {
    }

    /**
     * 为 viewer 打开 owner 仓库的指定页
     *
     * @return true=打开成功; false=页码无效或数据未加载 (已发送提示消息)
     */
    public static boolean open(BagPlugin plugin, Player viewer, UUID owner,
                               String ownerName, int page) {
        return open(plugin, viewer, owner, ownerName, page, false);
    }

    /**
     * 为 viewer 打开 owner 仓库的指定页 (v1.6.2 增加静默模式)
     *
     * @param quiet true=打开失败时不发送提示消息 (翻页按钮步进查找用,
     *              避免跳过空的只读页时刷屏)
     * @return true=打开成功; false=页码无效或数据未加载
     */
    public static boolean open(BagPlugin plugin, Player viewer, UUID owner,
                               String ownerName, int page, boolean quiet) {
        BagManager manager = plugin.getBagManager();
        PlayerBag bag = manager.getBag(owner);
        if (bag == null) {
            // 数据未加载完成 (进服瞬间立刻输指令的极端情况)
            if (!quiet) {
                viewer.sendMessage(plugin.getConfigManager().getMsg("not-loaded"));
                plugin.getConfigManager().playSound(viewer, "fail"); // 失败音效 (v1.5.0)
            }
            return false;
        }

        ConfigManager cfg = plugin.getConfigManager();
        boolean selfView = viewer.getUniqueId().equals(owner);

        // ---- 基础组判定 (绑定组优先, 其次权限节点) ----
        Player ownerOnline = Bukkit.getPlayer(owner);
        Group baseGroup;
        if (ownerOnline != null) {
            baseGroup = plugin.getGroupManager().getEffectiveGroup(owner, ownerOnline);
        } else {
            // 离线 (管理员查看): 取最高组, 保证历史数据可见
            baseGroup = cfg.getMaxGroup();
        }

        // ---- 体验券状态判定 (v1.6.2 动态解析) ----
        // 体验组不再使用存档记录的组名, 而是"基础组的上一级"实时计算:
        //   - 体验永不被基础组变化失效 (修复: 绑定 vip1 后 vip1 体验被判无效,
        //     倒计时消失 + 体验页被拓展器屏障锁死, 物品被关在里面);
        //   - 基础组升到 vip1 -> 体验自动变为 vip2; 降回 default -> 体验回到 vip1;
        //   - 基础组已是最高组 -> 无更高级可体验, 体验自然无效果 (不显示倒计时)。
        boolean ticketActive = false;
        Group ticketGroup = null;
        if (bag.isTicketActive()) {
            ticketGroup = cfg.getNextGroup(baseGroup);
            if (ticketGroup != null) {
                ticketActive = true;
            }
        } else if (bag.hasTicketRecord() && selfView && ownerOnline != null) {
            // 有过期记录: 提示一次回退 (每次打开提示, 告知权限变化原因)
            viewer.sendMessage(cfg.getMsg("ticket-expired")
                    .replace("{group}", baseGroup.name));
        }

        // ---- 生效组: 体验激活时取体验组, 否则基础组 ----
        Group group = ticketActive ? ticketGroup : baseGroup;

        // ---- 页数判定: 上限 = 生效组页数 ----
        int hardCap = Math.max(cfg.getMaxTotalPages(), group.pages);
        int pageLimit = Math.min(group.pages, hardCap);
        // 已解锁页数 (v1.6.1 修复):
        //   体验激活中 -> 体验组全部页面默认解锁 (无需拓展器, 这就是"体验VIP仓库");
        //   平时 -> 1 (基础页) + bonusPages (拓展器已用数量), 上限内截断
        int unlockedPages = ticketActive ? pageLimit
                : Math.min(1 + bag.getBonusPages(), pageLimit);

        // ---- 到期只读页判定 (v1.6.0): 超出基础权限但有物品的页 ----
        boolean readOnlyPage = false;
        if (!ticketActive && page >= 1 && page > unlockedPages) {
            // 页内有物品才允许只读打开 (取空后无法再打开)
            ItemStack[] raw = bag.getPageIfExists(page);
            if (hasAnyItem(raw)) {
                readOnlyPage = true;
            }
        }

        // ---- 最大可达页 (v1.6.4 修复: 超出权限上限的只读页同样可达) ----
        // 可达页 = 已解锁页 + 其后"仍有物品"的只读页:
        // 体验到期后超出权限的页只要还有物品, 就能通过下一页按钮进入取回。
        // v1.6.4 修复: 此前扫描上界是 pageLimit (基础组页数), 曾体验更高组
        // (如 vip2 的第 5 页) 回退 default 后, 第 5 页超出 pageLimit 扫不到,
        // 翻页按钮永远到不了 (虽然 /bag open 5 命令可以打开), 物品被锁死。
        // 现在上界扩展到"数据中实际存在的最大页"; 体验激活期间仍限制在
        // 体验组 pageLimit 内 (体验只有权限内的页, 防止按钮误导)。
        int maxReachable = unlockedPages;
        int scanTo = ticketActive ? pageLimit
                : Math.max(pageLimit, bag.getMaxPageWithData());
        for (int p = unlockedPages + 1; p <= scanTo; p++) {
            if (hasAnyItem(bag.getPageIfExists(p))) {
                maxReachable = p;
            }
        }

        if (page < 1 || page > unlockedPages) {
            if (!readOnlyPage) {
                // 区分提示: 权限够但没解锁 -> 拓展器提示; 权限不够 -> VIP 提示
                if (selfView && page >= 1 && page > pageLimit) {
                    Group lockGroup = cfg.getGroupForPage(page);
                    if (lockGroup != null && lockGroup.pages > group.pages) {
                        // 超出 VIP 权限上限: 提示需要的组
                        if (!quiet) {
                            viewer.sendMessage(cfg.getMsg("page-locked")
                                    .replace("{page}", String.valueOf(page))
                                    .replace("{group}", lockGroup.name)
                                    .replace("{pages}", String.valueOf(lockGroup.pages))
                                    .replace("{max}", String.valueOf(unlockedPages)));
                            cfg.playSound(viewer, "fail"); // 失败音效 (v1.5.0)
                        }
                        return false;
                    }
                }
                if (selfView && page >= 1 && page <= pageLimit) {
                    // 权限内但尚未解锁: 提示用拓展器开启
                    if (!quiet) {
                        viewer.sendMessage(cfg.getMsg("page-need-expander")
                                .replace("{page}", String.valueOf(page)));
                        cfg.playSound(viewer, "fail"); // 失败音效 (v1.5.0)
                    }
                    return false;
                }
                if (!quiet) {
                    viewer.sendMessage(cfg.getMsg("page-invalid")
                            .replace("{page}", String.valueOf(page))
                            .replace("{max}", String.valueOf(unlockedPages)));
                    cfg.playSound(viewer, "fail"); // 失败音效 (v1.5.0)
                }
                return false;
            }
        }

        // ---- 页大小: 组行数, 但绝不小于已有数据长度 (防止体验组行数回退截断数据) ----
        ItemStack[] existing = bag.getPageIfExists(page);
        int pageSize = Math.max(group.rows * 9, existing != null ? existing.length : 0);
        pageSize = Math.min(pageSize, 54); // 界面硬上限
        ItemStack[] items = bag.getPage(page, pageSize); // pageSize >= 数据长度, 不会截断

        // ---- 本页仍锁定的槽位 (配置位置 - 已解锁格子) ----
        Set<Integer> lockedForView = cfg.computeLockedSlots(pageSize);
        lockedForView.removeAll(bag.getUnlockedSlots(page));

        // 体验激活: 全部锁定格临时解锁 (v1.6.0)
        if (ticketActive) {
            lockedForView.clear();
        }

        // ---- 只读格: 锁定格中遗留物品的格子 (体验期间存入, 到期后只读可取) ----
        // 只读页同样提取: 体验期间存入锁定格的物品, 到期后必须能显示并取回
        Set<Integer> readOnlySlots = new TreeSet<Integer>();
        if (!ticketActive) {
            java.util.Iterator<Integer> it = lockedForView.iterator();
            while (it.hasNext()) {
                int slot = it.next();
                if (slot < items.length && items[slot] != null
                        && items[slot].getType() != Material.AIR) {
                    readOnlySlots.add(slot);
                    it.remove(); // 该格显示真实物品 (只读), 不显示锁定占位
                }
            }
        }

        // ---- 翻页按钮格旧数据迁移 (旧版本数据可能存在这两格) ----
        // v1.6.2: ownerMaxPages 传"最大可达页" (已解锁页+有物品的只读页),
        // 使下一页按钮可以导航进入只读页取回到期仓库中的遗留物品
        BagHolder holder = new BagHolder(owner, ownerName, page,
                maxReachable, pageLimit, pageSize, lockedForView,
                readOnlyPage, readOnlySlots);
        migrateButtonSlot(items, holder.getPrevSlot());
        migrateButtonSlot(items, holder.getNextSlot());

        // ---- 创建界面: 页标题 (体验激活时追加剩余时间后缀) ----
        String title = cfg.getPageTitle(group, page, ownerName);
        if (ticketActive) {
            // 后缀格式如 " vip1剩余: 2天23时21分" (最小单位分钟)
            // v1.6.2: 显示动态解析的体验组名 (基础组的上一级)
            title = title + cfg.getTicketTitleSuffix(
                    ticketGroup.name, bag.getTicketExpire());
        }
        // 只读页标题追加只读标记, 玩家一眼可知当前页状态
        if (readOnlyPage) {
            title = title + readOnlyMark(plugin);
        }
        Inventory inv = Bukkit.createInventory(holder, pageSize, title);

        // 填充物品 (拷贝一份, 界面与缓存解耦)
        inv.setContents(items.clone());

        // ---- 渲染锁定格物品 (覆盖对应槽位, 底层数据保留待解锁后恢复) ----
        if (!lockedForView.isEmpty()) {
            ItemStack lockItem = cfg.buildLockItem();
            for (int slot : lockedForView) {
                inv.setItem(slot, lockItem.clone());
            }
        }

        // ---- 渲染翻页按钮 (最后一行两端) ----
        renderPageButtons(plugin, inv, holder, group);

        // ---- 只读页提示 (打开时告知规则) ----
        if (readOnlyPage && selfView) {
            viewer.sendMessage(plugin.getConfigManager().getMsg("ticket-readonly-page"));
        }

        viewer.openInventory(inv);
        return true;
    }

    /** 只读页标题标记 (灰色 [只读]) */
    private static String readOnlyMark(BagPlugin plugin) {
        return bagtest.util.TextUtil.color(" &8[只读]");
    }

    /** 数组中是否存在非空物品 */
    private static boolean hasAnyItem(ItemStack[] items) {
        if (items == null) {
            return false;
        }
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                return true;
            }
        }
        return false;
    }

    /**
     * 旧数据迁移: 若翻页按钮格有物品, 挪到本页第一个空格
     * (本页无空格的极端情况保留原数据, 不丢失)
     */
    private static void migrateButtonSlot(ItemStack[] items, int slot) {
        ItemStack old = items[slot];
        if (old == null || old.getType() == Material.AIR) {
            return;
        }
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null || items[i].getType() == Material.AIR) {
                items[i] = old;
                items[slot] = null;
                return;
            }
        }
        // 本页全满: 数据保留在数组中, 界面被按钮覆盖暂不可见 (绝不删除)
    }

    /**
     * 渲染底部翻页按钮:
     * - 上一页: 可翻=箭头 / 第 1 页=屏障"已是第一页"
     * - 下一页: 可翻=箭头 / vip专属未解锁=屏障"未解锁" / 全局最后一页=屏障"已是最后一页"
     */
    private static void renderPageButtons(BagPlugin plugin, Inventory inv,
                                          BagHolder holder, Group group) {
        ConfigManager cfg = plugin.getConfigManager();

        // ---- 上一页按钮 (最后一行第一格) ----
        if (holder.hasPrev()) {
            inv.setItem(holder.getPrevSlot(),
                    buildButton(Material.ARROW, cfg.getGuiPrevName(holder.getPage() - 1),
                            cfg.getGuiPrevLore(holder.getPage() - 1)));
        } else {
            inv.setItem(holder.getPrevSlot(),
                    buildButton(Material.BARRIER, cfg.getGuiFirstName(), cfg.getGuiFirstLore()));
        }

        // ---- 下一页按钮 (最后一行最后一格) ----
        int nextPage = holder.getPage() + 1;
        if (holder.hasNext()) {
            // 下一页已解锁: 可用箭头
            inv.setItem(holder.getNextSlot(),
                    buildButton(Material.ARROW, cfg.getGuiNextName(nextPage),
                            cfg.getGuiNextLore(nextPage)));
        } else if (nextPage <= holder.getPageLimit()) {
            // 下一页在 VIP 权限内但尚未解锁: 屏障"未解锁" (提示用拓展器)
            inv.setItem(holder.getNextSlot(),
                    buildButton(Material.BARRIER, cfg.getGuiLockedName(),
                            cfg.getGuiLockedLore()));
        } else {
            // 超出 VIP 权限上限: 屏障"权限不足" (标注需要的组)
            ConfigManager cfg2 = plugin.getConfigManager();
            Group lockGroup = cfg2.getGroupForPage(nextPage);
            String lockName = lockGroup != null ? lockGroup.name : "更高VIP";
            inv.setItem(holder.getNextSlot(),
                    buildButton(Material.BARRIER, cfg2.getGuiVipLockedName(),
                            cfg2.getGuiVipLockedLore(lockName)));
        }
    }

    /** 构造翻页按钮物品 (指定材质 + 名称 + 描述) */
    private static ItemStack buildButton(Material material, String name, java.util.List<String> lore) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
