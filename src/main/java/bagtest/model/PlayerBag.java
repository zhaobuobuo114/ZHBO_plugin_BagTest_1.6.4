package bagtest.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 【模块二：数据模型层】
 * 玩家仓库数据模型 v3 - 多页仓库 + 锁定格 + 拓展页 (每位玩家一份独立实例)
 *
 * 结构: 页码 -> 物品数组 (下标即槽位)
 * 性能设计:
 *   1. 页数组懒创建: 只有真正被打开/写入的页才分配内存;
 *   2. 保存时空页不落盘 (见 BagManager), 数据文件最小化;
 *   3. TreeMap 保证页码有序遍历 (序列化输出稳定, 便于人工检查)。
 *
 * v1.3.0 新增字段:
 *   bonusPages    拓展器开启的额外页数 (权限组页数之外, 右键拓展器 +1);
 *   unlockedSlots 每页已解锁的槽位集合 (页码 -> 槽位集合, 未出现的槽位按锁定处理)。
 *
 * 线程约定: 本对象仅在主线程读写 (GUI 操作/序列化均在主线程)
 */
public class PlayerBag {

    /** 仓库归属玩家的 UUID (每人独立仓库的唯一标识) */
    private final UUID owner;

    /** 玩家最近一次使用的名字 (仅用于 MySQL 表记录/排查, 不作数据主键) */
    private volatile String lastName = "";

    /** 多页数据: 页码 -> 物品数组 (懒创建, 只有访问过的页才存在) */
    private final TreeMap<Integer, ItemStack[]> pages = new TreeMap<Integer, ItemStack[]>();

    /** 拓展器开启的额外页数 (0 = 无拓展页; 可用页数 = 权限组页数 + 此值) */
    private int bonusPages = 0;

    /** 每页已解锁的槽位: 页码 -> 槽位集合 (懒创建, 未解锁的页不占内存) */
    private final TreeMap<Integer, TreeSet<Integer>> unlockedSlots =
            new TreeMap<Integer, TreeSet<Integer>>();

    /** 体验券生效的权限组名 (v1.6.0; 空串 = 无体验记录) */
    private String ticketGroup = "";

    /** 体验券到期时间戳 (毫秒, v1.6.0; 0 = 无体验记录) */
    private long ticketExpire = 0L;

    /** 累计获得的拓展器数量 (v1.6.0; give 时累加, 消耗不减, 用于获得上限判定) */
    private int gainedExpanders = 0;

    public PlayerBag(UUID owner) {
        this.owner = owner;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getLastName() {
        return lastName == null ? "" : lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName == null ? "" : lastName;
    }

    /**
     * 获取指定页的物品数组 (不存在则创建空页, 大小不符则自动调整)
     *
     * @param page 页码 (从 1 开始)
     * @param size 该页应有格数 (行数 x 9), 与当前不符时自动扩容/截断
     */
    public ItemStack[] getPage(int page, int size) {
        ItemStack[] items = pages.get(page);
        if (items == null) {
            // 懒创建: 首次访问该页才分配
            items = new ItemStack[clampSize(size)];
            pages.put(page, items);
        } else if (items.length != size) {
            // 热重载/权限组变化导致行数变化: 扩容保留前部 / 缩容截断尾部
            items = Arrays.copyOf(items, clampSize(size));
            pages.put(page, items);
        }
        return items;
    }

    /** 用界面内容覆盖指定页 (GUI 同步时调用) */
    public void setPage(int page, ItemStack[] items) {
        if (items == null) {
            pages.remove(page);
            return;
        }
        // 拷贝一份存入, 与界面数组解耦 (防止界面复用导致的数据串改)
        ItemStack[] copy = Arrays.copyOf(items, clampSize(items.length));
        // 清理 AIR 伪物品 (GUI 操作可能残留 air 堆叠)
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] != null && copy[i].getType() == Material.AIR) {
                copy[i] = null;
            }
        }
        pages.put(page, copy);
    }

    /** 获取全部已有页 (只读视图, 序列化用) */
    public Map<Integer, ItemStack[]> getPages() {
        return pages;
    }

    /**
     * 获取指定页的现有数据数组 (v1.6.0)
     * 与 getPage 不同: 不创建空页、不扩容 resize, 纯只读访问
     *
     * @return 该页现有数据; 页不存在返回 null
     */
    public ItemStack[] getPageIfExists(int page) {
        return pages.get(page);
    }

    /** 数据中存在的最大页码 (无数据返回 0) */
    public int getMaxPageWithData() {
        return pages.isEmpty() ? 0 : pages.lastKey();
    }

    /** 统计全部页已用槽位数 (info 指令展示用) */
    public int getUsedSlots() {
        int used = 0;
        for (ItemStack[] items : pages.values()) {
            for (ItemStack item : items) {
                if (item != null && item.getType() != Material.AIR) {
                    used++;
                }
            }
        }
        return used;
    }

    // ==================== 拓展页 / 锁定格 (v1.3.0) ====================

    /** 拓展器开启的额外页数 */
    public int getBonusPages() {
        return bonusPages;
    }

    /** 设置拓展页数 (加载存档时调用; 范围 0-99 自动修正) */
    public void setBonusPages(int bonusPages) {
        this.bonusPages = Math.max(0, Math.min(99, bonusPages));
    }

    /** 指定页的已解锁槽位集合 (无记录返回空集合, 不会创建条目) */
    public TreeSet<Integer> getUnlockedSlots(int page) {
        TreeSet<Integer> set = unlockedSlots.get(page);
        return set != null ? set : new TreeSet<Integer>();
    }

    /** 全部解锁记录 (只读视图, 序列化用) */
    public Map<Integer, TreeSet<Integer>> getUnlockedSlotsAll() {
        return unlockedSlots;
    }

    /** 解锁指定页的一个槽位 (开锁器消耗后调用; 槽位范围 0-53 自动过滤) */
    public void unlockSlot(int page, int slot) {
        if (page < 1 || page > 100 || slot < 0 || slot > 53) {
            return;
        }
        TreeSet<Integer> set = unlockedSlots.get(page);
        if (set == null) {
            set = new TreeSet<Integer>();
            unlockedSlots.put(page, set);
        }
        set.add(slot);
    }

    /** 指定页指定槽位是否已解锁 (未解锁按锁定处理) */
    public boolean isSlotUnlocked(int page, int slot) {
        TreeSet<Integer> set = unlockedSlots.get(page);
        return set != null && set.contains(slot);
    }

    /** 格数合法化: 最少 9 (1行), 最多 54 (6行) */
    private static int clampSize(int size) {
        int s = (size / 9) * 9;
        if (s < 9) return 9;
        if (s > 54) return 54;
        return s;
    }

    // ==================== 体验券 / 拓展器计数 (v1.6.0) ====================

    /** 体验券生效的权限组名 (空串 = 无体验记录) */
    public String getTicketGroup() {
        return ticketGroup == null ? "" : ticketGroup;
    }

    /** 体验券到期时间戳 (毫秒, 0 = 无体验记录) */
    public long getTicketExpire() {
        return ticketExpire;
    }

    /** 是否存在体验记录 (不论是否过期) */
    public boolean hasTicketRecord() {
        return ticketGroup != null && !ticketGroup.isEmpty() && ticketExpire > 0;
    }

    /** 体验是否处于激活状态 (有记录且未到期) */
    public boolean isTicketActive() {
        return hasTicketRecord() && System.currentTimeMillis() < ticketExpire;
    }

    /** 设置体验权限 (使用体验券时调用; expire <= 0 视为清除记录) */
    public void setTicket(String group, long expire) {
        this.ticketGroup = group == null ? "" : group;
        this.ticketExpire = Math.max(0, expire);
    }

    /** 清除体验记录 (永不过期数据, 一般无需调用) */
    public void clearTicket() {
        this.ticketGroup = "";
        this.ticketExpire = 0L;
    }

    /** 累计获得的拓展器数量 (give 时累加, 消耗不减) */
    public int getGainedExpanders() {
        return gainedExpanders;
    }

    /** 增加累计获得计数 (发放时调用, 范围 0-9999 自动修正) */
    public void addGainedExpanders(int amount) {
        this.gainedExpanders = Math.max(0, Math.min(9999, this.gainedExpanders + amount));
    }

    /** 加载存档时直接设置累计获得计数 (范围 0-9999 自动修正) */
    public void setGainedExpanders(int amount) {
        this.gainedExpanders = Math.max(0, Math.min(9999, amount));
    }
}
