package bagtest.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 【GUI 层】
 * 仓库界面持有者 - 给创建出的 Inventory 打上"本插件仓库"标识,
 * 并记录翻页与锁定格所需的全部信息 (v1.3.0 扩展)。
 * 事件监听器通过 instanceof 判断归属, 不依赖标题文本, 稳定且不受其他插件干扰。
 */
public class BagHolder implements InventoryHolder {

    /** 仓库归属者 (查看者可以是管理员, 归属者始终是数据主人) */
    private final UUID owner;

    /** 归属玩家名字 (翻页打开新界面时用于标题 {player} 占位符) */
    private final String ownerName;

    /** 当前界面展示的页码 (从 1 开始) */
    private final int page;

    /**
     * 归属者可翻到的最大页数 (v1.6.2: 已解锁页 + 其后有物品的只读页,
     * 翻页越界判定; 只读页用于体验到期后取回超出权限页中的遗留物品)
     */
    private final int ownerMaxPages;

    /** 归属者的页数权限上限 (VIP 组 pages 值, 拓展器最多解锁到此数) */
    private final int pageLimit;

    /** 界面大小 (格数, = 行数 x 9) */
    private final int pageSize;

    /**
     * 本页当前仍锁定的槽位集合 (打开时按配置计算, 已扣除主人解锁过的格子)
     * 可变集合: 玩家开锁后由监听器移除, 后步同步即按普通格处理
     */
    private final Set<Integer> lockedSlots;

    /**
     * 只读页标志 (v1.6.0): 体验券到期后超出原权限但有物品的页
     * true 时整页只能取物不能存放
     */
    private final boolean readOnly;

    /**
     * 只读格集合 (v1.6.0): 锁定格中存有物品的格子 (体验期间存入, 到期后锁定格恢复锁定)
     * 这些格子显示真实物品, 仅允许取出, 取空后恢复锁定占位
     */
    private final Set<Integer> readOnlySlots;

    public BagHolder(UUID owner, String ownerName, int page, int ownerMaxPages,
                     int pageLimit, int pageSize, Set<Integer> lockedSlots) {
        this(owner, ownerName, page, ownerMaxPages, pageLimit, pageSize,
                lockedSlots, false, null);
    }

    /** 全参构造 (v1.6.0: 增加只读页标志与只读格集合) */
    public BagHolder(UUID owner, String ownerName, int page, int ownerMaxPages,
                     int pageLimit, int pageSize, Set<Integer> lockedSlots,
                     boolean readOnly, Set<Integer> readOnlySlots) {
        this.owner = owner;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.page = page;
        this.ownerMaxPages = ownerMaxPages;
        this.pageLimit = pageLimit;
        this.pageSize = pageSize;
        this.lockedSlots = lockedSlots != null ? lockedSlots : new TreeSet<Integer>();
        this.readOnly = readOnly;
        this.readOnlySlots = readOnlySlots != null ? readOnlySlots : new TreeSet<Integer>();
    }

    public UUID getOwner() {
        return owner;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public int getPage() {
        return page;
    }

    public int getOwnerMaxPages() {
        return ownerMaxPages;
    }

    /** 页数权限上限 (VIP 组 pages 值) */
    public int getPageLimit() {
        return pageLimit;
    }

    public int getPageSize() {
        return pageSize;
    }

    /** 本页仍锁定的槽位集合 (可变, 开锁后移除) */
    public Set<Integer> getLockedSlots() {
        return lockedSlots;
    }

    /** 上一页按钮槽位 (最后一行第一格) */
    public int getPrevSlot() {
        return pageSize - 9;
    }

    /** 下一页按钮槽位 (最后一行最后一格) */
    public int getNextSlot() {
        return pageSize - 1;
    }

    /** 指定槽位是否是翻页按钮格 (禁止物品进出) */
    public boolean isPageButtonSlot(int slot) {
        return slot == getPrevSlot() || slot == getNextSlot();
    }

    /** 指定槽位是否是锁定格 (禁止物品进出, 仅开锁器可解锁) */
    public boolean isLockedSlot(int slot) {
        return lockedSlots.contains(slot);
    }

    /** 是否为只读页 (v1.6.0: 整页只能取不能存) */
    public boolean isReadOnly() {
        return readOnly;
    }

    /** 只读格集合 (v1.6.0: 锁定格中存有物品的格子, 可变集合) */
    public Set<Integer> getReadOnlySlots() {
        return readOnlySlots;
    }

    /** 指定槽位是否是只读格 */
    public boolean isReadOnlySlot(int slot) {
        return readOnlySlots.contains(slot);
    }

    /** 指定槽位是否禁止"存入" (只读页内任意格 / 任意页内的只读格, v1.6.0) */
    public boolean isTakeOnlySlot(int slot) {
        return readOnly || readOnlySlots.contains(slot);
    }

    /** 是否可以翻到上一页 */
    public boolean hasPrev() {
        return page > 1;
    }

    /** 是否可以翻到下一页 (在归属者页数权限内) */
    public boolean hasNext() {
        return page < ownerMaxPages;
    }

    @Override
    public Inventory getInventory() {
        // 本插件从不通过 holder 反查背包, 返回 null 即可 (接口要求实现)
        return null;
    }
}
