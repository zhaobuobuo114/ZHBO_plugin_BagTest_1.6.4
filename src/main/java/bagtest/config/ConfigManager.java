package bagtest.config;

import bagtest.util.TextUtil;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 【模块三：配置层】
 * 配置管理器 v2 - 权限组 + 多页仓库配置, 支持热重载
 *
 * 设计要点:
 * 1. 所有字段提供默认值, 配置缺失/类型错误时自动回退, 绝不抛异常;
 * 2. 数值字段做范围修正 (页数 1-100, 行数 1-6), 非法配置不破坏运行;
 * 3. 权限组完全配置驱动: 组名/权限节点/页数/行数/每页标题全部自定义;
 * 4. 组判定: 玩家拥有的组权限中取"页数最多"的组, 无任何组权限用 default 组;
 * 5. storageSignature() 生成存储配置指纹, 热重载时判断是否需要重建存储。
 */
public class ConfigManager {

    private final JavaPlugin plugin;

    // ==================== 权限组模型 ====================

    /** 权限组: 页数 + 每页行数 + 每页标题 */
    public static class Group {
        public final String name;
        /** 权限节点 (default 组为 null, 不需要权限) */
        public final String permission;
        /** 仓库页数 */
        public final int pages;
        /** 每页行数 (1-6) */
        public final int rows;
        /** 逐页标题: 页码 -> 标题 (优先级高于模板) */
        public final Map<Integer, String> titles;
        /** 统一标题模板 ({page}/{player} 占位符) */
        public final String titleTemplate;

        Group(String name, String permission, int pages, int rows,
              Map<Integer, String> titles, String titleTemplate) {
            this.name = name;
            this.permission = permission;
            this.pages = pages;
            this.rows = rows;
            this.titles = titles;
            this.titleTemplate = titleTemplate;
        }

        public boolean isDefault() {
            return permission == null;
        }
    }

    /** 全部权限组 (配置顺序保留, 便于日志排查) */
    private final Map<String, Group> groups = new LinkedHashMap<String, Group>();
    /** 兜底默认组 (配置中 groups 段整体缺失/为空时使用) */
    private Group defaultGroup = new Group("default", null, 1, 6,
            new LinkedHashMap<Integer, String>(), null);

    // 存储配置
    private String storageType = "YAML";
    private String yamlDataFolder = "playerdata";
    private String mysqlHost = "localhost";
    private int mysqlPort = 3306;
    private String mysqlDatabase = "minecraft";
    private String mysqlUsername = "root";
    private String mysqlPassword = "";
    private String mysqlTablePrefix = "bag_";
    private boolean mysqlUseSsl = false;

    // 仓库通用配置
    private int openCooldown = 0;
    /** 仓库总页数硬上限 (权限组页数 + 拓展器页数之和的上限, v1.3.0) */
    private int maxTotalPages = 20;

    // ==================== 锁定格配置 (v1.3.0) ====================

    /** 锁定格功能总开关 */
    private boolean lockedSlotsEnabled = true;
    /** 锁定格位置模式: BOTTOM_HALF=下半部分 / CUSTOM=手动槽位 */
    private String lockedSlotsMode = "BOTTOM_HALF";
    /** CUSTOM 模式手动槽位列表 */
    private List<Integer> lockedSlotsCustom = new ArrayList<Integer>(
            Arrays.asList(46, 47, 48, 49, 50, 51, 52));
    /** 锁定格显示物品 */
    private Material lockMaterial = Material.STAINED_GLASS_PANE;
    private short lockData = 14;
    private String lockName = "&c🔒 锁定格子";
    private List<String> lockLore = new ArrayList<String>(
            Arrays.asList("&7手持开锁器点击此格解锁", "&8每个开锁器仅能解锁一个格子"));

    // ==================== 特殊物品配置 (v1.3.0, v1.4.0 附魔, v1.5.0 绑定+来源) ====================

    /** 物品 lore 中的绑定行前缀 (give 时追加, 判定归属用) */
    public static final String BIND_PREFIX = "&8绑定: ";
    /** 物品 lore 中的来源行前缀 (give 时追加, 消耗时写入日志) */
    public static final String SOURCE_PREFIX = "&8来源: ";

    /** 开锁器: 手持右键自动消耗 (或在界面内点击锁定格), 解锁一格 */
    private Material unlockerMaterial = Material.IRON_INGOT;
    private short unlockerData = 0;
    private boolean unlockerEnchanted = true;
    /** 开锁器是否绑定玩家 (v1.6.1: 默认不绑定, 可自由交易; 仅拓展器默认绑定) */
    private boolean unlockerBindOwner = false;
    private String unlockerName = "&e🔑 仓库开锁器";
    private List<String> unlockerLore = new ArrayList<String>(
            Arrays.asList("&7手持右键自动消耗", "&8可解锁一个锁定格子"));

    /** 拓展器: 右键消耗一个, 开启一个新的仓库页 (VIP 上限内) */
    private Material expanderMaterial = Material.EMERALD;
    private short expanderData = 0;
    private boolean expanderEnchanted = true;
    /** 拓展器是否绑定玩家 (v1.6.1: 唯一默认绑定的特殊物品, 限量道具防转移) */
    private boolean expanderBindOwner = true;
    private String expanderName = "&a📦 仓库拓展器";
    private List<String> expanderLore = new ArrayList<String>(
            Arrays.asList("&7手持右键消耗", "&8开启一个新的仓库页", "&c⚠ 请妥善保管, 丢失概不负责"));
    /** 拓展器累计获得上限 (v1.6.0, 0 = 不限量) */
    private int expanderGainLimit = 10;

    // ---- 体验券 (v1.6.0): 两种时长, 右键消耗临时升级到下一级权限组 ----
    // v1.6.1: 不绑定玩家 (默认 false), 可自由交易
    private Material ticket3Material = Material.PAPER;
    private short ticket3Data = 0;
    private boolean ticket3Enchanted = true;
    private boolean ticket3BindOwner = false;
    private int ticket3Days = 3;
    private String ticket3Name = "&b🎟 仓库体验券 &7(3天)";
    private List<String> ticket3Lore = new ArrayList<String>(Arrays.asList(
            "&7手持右键消耗", "&8暂时升级到下一权限组", "&8解锁全部格子与权限内仓库页",
            "&c⚠ 请妥善保管, 丢失概不负责"));

    private Material ticket7Material = Material.PAPER;
    private short ticket7Data = 0;
    private boolean ticket7Enchanted = true;
    private boolean ticket7BindOwner = false;
    private int ticket7Days = 7;
    private String ticket7Name = "&d🎟 仓库体验券 &7(7天)";
    private List<String> ticket7Lore = new ArrayList<String>(Arrays.asList(
            "&7手持右键消耗", "&8暂时升级到下一权限组", "&8解锁全部格子与权限内仓库页",
            "&c⚠ 请妥善保管, 丢失概不负责"));

    // ==================== 音效 / 粒子配置 (v1.5.0) ====================

    /** 音效配置: 1.12.2 Sound 枚举 + 音量 + 音调 */
    public static class SoundCfg {
        public final Sound sound;
        public final float volume;
        public final float pitch;

        SoundCfg(Sound sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
        }
    }

    /** 粒子配置: 1.12.2 Particle 枚举 + 数量 + 三轴偏移 + 速度 */
    public static class ParticleCfg {
        public final Particle particle;
        public final int count;
        public final double offsetX;
        public final double offsetY;
        public final double offsetZ;
        public final double speed;

        ParticleCfg(Particle particle, int count,
                    double offsetX, double offsetY, double offsetZ, double speed) {
            this.particle = particle;
            this.count = count;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.speed = speed;
        }
    }

    /** 音效总开关 */
    private boolean soundsEnabled = true;
    /** 场景音效表: open / page-turn / unlock / expand / fail */
    private final Map<String, SoundCfg> soundCfgs = new HashMap<String, SoundCfg>();
    /** 粒子总开关 */
    private boolean particlesEnabled = true;
    /** 场景粒子表: unlock / expand */
    private final Map<String, ParticleCfg> particleCfgs = new HashMap<String, ParticleCfg>();

    // 界面翻页按钮配置 (v1.2.0)
    private String guiPrevName = "&e◀ 上一页";
    private String guiPrevLore = "&7点击翻到第 {page} 页";
    private String guiNextName = "&e下一页 ▶";
    private String guiNextLore = "&7点击翻到第 {page} 页";
    private String guiLockedName = "&c未解锁";
    private String guiLockedLore = "&7手持 &e仓库拓展器 &7右键解锁";
    private String guiVipLockedName = "&c权限不足";
    private String guiVipLockedLore = "&7该页需要 &e{group} &7组权限";
    private String guiFirstName = "&c已是第一页";
    private String guiFirstLore = "&7没有更前面的页了";
    private String guiLastName = "&c已是最后一页";
    private String guiLastLore = "&7没有更后面的页了";

    // 菜单配置
    private String menuTitle = "&7===== &6个人仓库 选项菜单 &7=====";
    private String menuPagesLabel = "&7我的仓库页: ";
    private String menuPageButton = "&e[&6{page}&e]";
    private String menuHint = "&7提示: &8点击上方选项即可执行";
    private List<MenuEntry> menuEntries = defaultMenuEntries();

    /** 菜单条目: 文本 + 点击指令 + 是否填入聊天框 + 是否仅管理员可见 */
    public static class MenuEntry {
        public final String text;
        public final String command;
        public final boolean suggest;
        public final boolean admin;

        public MenuEntry(String text, String command, boolean suggest, boolean admin) {
            this.text = text == null ? "" : text;
            this.command = command == null ? "" : command;
            this.suggest = suggest;
            this.admin = admin;
        }

        public boolean isClickable() {
            return !command.isEmpty();
        }
    }

    /** 默认菜单 (配置缺失/为空时使用) */
    private static List<MenuEntry> defaultMenuEntries() {
        List<MenuEntry> list = new ArrayList<MenuEntry>();
        list.add(new MenuEntry("&e/bag open [页码] &7- 打开自己的仓库", "/bag open ", true, false));
        list.add(new MenuEntry("&e/bag help &7- 显示本菜单", "/bag help", false, false));
        list.add(new MenuEntry("&e/bag vip <玩家> <编号> &7- 设置玩家VIP组 (如 /bag vip Steve 1)", "/bag vip ", true, true));
        list.add(new MenuEntry("&e/bag vip <玩家> remove &7- 移除玩家VIP绑定", "/bag vip ", true, true));
        list.add(new MenuEntry("&e/bag give <玩家> <开锁器|拓展器> [数量] &7- 给予特殊物品", "/bag give ", true, true));
        list.add(new MenuEntry("&e/bag group list &7- 查看全部权限组与页数", "/bag group list", false, true));
        list.add(new MenuEntry("&e/bag info &7- 查看插件运行信息", "/bag info", false, true));
        list.add(new MenuEntry("&e/bag open <玩家> [页码] &7- 打开指定玩家的仓库", "/bag open ", true, true));
        list.add(new MenuEntry("&e/bag reload &7- 手动热重载配置文件", "/bag reload", false, true));
        list.add(new MenuEntry("&e/bag save &7- 保存全部在线玩家数据", "/bag save", false, true));
        // 作者与联系方式 (无 command, 仅展示不可点击)
        list.add(new MenuEntry("&7作者: &ezhaobuobuo &7| 联系: &e1071576270@QQ.COM", "", false, false));
        return list;
    }

    // 功能开关
    private int autoSaveMinutes = 10;
    private boolean autoReload = true;
    private int checkIntervalSeconds = 5;

    // 消息配置
    private String prefix = "&6[仓库]&r ";
    private String msgNoPermission = "&c你没有权限执行该操作!";
    private String msgPlayerOnly = "&c该指令只能由玩家执行!";
    private String msgPlayerNotFound = "&c目标玩家 &e{player} &c不存在或从未进过服务器!";
    private String msgNotLoaded = "&c仓库数据尚未加载完成, 请稍后再试!";
    private String msgLoading = "&7正在加载玩家 &e{player} &7的仓库数据...";
    private String msgCooldown = "&c操作过于频繁, 请 &e{seconds} &c秒后再试!";
    private String msgPageInvalid = "&c页码 &e{page} &c无效! 你的可用范围: &e1-{max} &c页";
    private String msgPageLocked = "&c第 &e{page} &c页是 &e{group} &c专属仓库 (需 &e{pages} &c页权限), 你当前可用 &e1-{max} &c页";
    private String msgOpened = "&7已打开仓库 &e第{page}页&7.";
    private String msgOpenedOther = "&7已打开玩家 &e{player} &7的仓库 &e第{page}页&7.";
    private String msgReloaded = "&a配置文件已重载! 耗时 &e{time}ms&a, 当前存储: &e{storage}&a.";
    private String msgStorageSwitched = "&e检测到存储配置变更: {old} -> {new}, 已保存全部数据并切换 (数据不会自动迁移, 请自行导入)!";
    private String msgSaved = "&a已保存 &e{count} &a名玩家的仓库数据.";
    // 分组管理消息 (v1.1.0)
    private String msgGroupNotFound = "&c权限组 &e{group} &c不存在! 可用组: &e{groups}";
    private String msgGroupBound = "&a已将玩家 &e{player} &a设为 &e{group} &a组 (&e{pages} 页 x &e{rows} 行&a)";
    private String msgGroupUnbound = "&a已移除玩家 &e{player} &a的自定义分组, 恢复权限判定 (当前: &e{group}&a)";
    private String msgGroupNotBound = "&e{player} &7没有自定义分组绑定.";
    private String msgGroupInfo = "&7玩家 &e{player} &7当前生效组: &e{group} &7(&e{pages} 页 x &e{rows} 行&7) &8来源: {source}";
    private String msgGroupListHeader = "&7===== &6权限组列表 &7(&e{count}&7) =====";
    private String msgGroupListItem = "&e{group} &7- &e{pages} 页 x &e{rows} 行 &8({perm})";
    // 锁定格 / 特殊物品消息 (v1.3.0, v1.4.0 更新)
    private String msgSlotLockedHint = "&c该格子已锁定! 手持 &e仓库开锁器 &c右键或点击此格解锁.";
    private String msgSlotUnlocked = "&a开锁成功! 已解锁一个锁定格子.";
    private String msgUnlockerUsed = "&a开锁成功! 已解锁 &e第{page}页 &a的一个锁定格子.";
    private String msgUnlockerNone = "&c没有可解锁的锁定格子了, 开锁器未消耗.";
    private String msgUnlockerNotOwner = "&c该开锁器绑定于玩家 &e{player} &c, 你无法使用!";
    private String msgExpanderUsed = "&a仓库拓展成功! 已开启新的仓库页: &e第{page}页&a.";
    private String msgExpanderMax = "&c已达你的 VIP 页数上限 &e{max} &c页, 拓展器未消耗.";
    private String msgPageNeedExpander = "&c第 &e{page} &c页尚未解锁! 手持 &e仓库拓展器 &c右键即可开启.";
    // 特殊物品防护消息 (v1.5.0)
    private String msgItemCantDrop = "&c仓库特殊物品 &e(开锁器/拓展器/体验券) &c不可丢弃!";
    private String msgItemDeathKept = "&e你的开锁器/拓展器/体验券已绑定保留, 重生时自动归还.";
    // 体验券消息 (v1.6.0, v1.6.1 叠加时间)
    private String msgTicketUsed = "&a体验券已生效! 你暂时拥有 &e{group} &a权限, 剩余 &e{time}&a.";
    private String msgTicketExtend = "&a体验时间已叠加! 当前体验 &e{group}&a, 剩余 &e{time}&a.";
    private String msgTicketMax = "&c你当前已是最高权限组 &e{group}&c, 体验券未消耗.";
    private String msgTicketExpired = "&e你的仓库体验券已到期, 已回退至 &e{group} &e权限.";
    private String msgTicketReadonlyPage = "&e该页超出当前权限, 已进入只读模式: &c只能取物, 不能存放&7(取空后无法再打开).";
    private String msgTicketReadonlyDeny = "&c该页为只读页, 只能取出物品, 不能存放!";
    private String msgTicketPageEmpty = "&e该页物品已全部取回, 页面已关闭, 之后将无法再打开.";
    private String ticketTitleSuffix = " &b{group}剩余: &f{time}";
    private String msgTicketInfoLine = "&7体验权限: &e{group} &7剩余 &e{time}";
    private String msgExpanderGainLimit = "&c玩家 &e{player} &c拓展器获得数已达上限 &e{limit}&c (已获得 &e{current}&c), 本次无法发放.";
    private String msgExpanderGainTruncated = "&e拓展器获得数接近上限, 本次实际发放 &e{amount} &e个 (已获得 {current}/{limit}).";
    private String msgGiveUsage = "&c用法: /bag give <玩家> <unlocker|expander> [数量] &8(开锁器/拓展器 可用中文)";
    private String msgGiveInvalidType = "&c未知物品类型: &e{type}&c! 可用: &eunlocker&c(开锁器) / &eexpander&c(拓展器)";
    private String msgGivePlayerOffline = "&c玩家 &e{player} &c不在线!";
    private String msgGiveSuccess = "&a已给予 &e{player} &e{amount} &a个 &r{item}&a.";
    private String msgGiveReceived = "&a你获得了 &e{amount} &a个 &r{item} &7— 手持右键使用";

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 首次加载: 释放默认配置文件并读取 */
    public void load() {
        plugin.saveDefaultConfig();
        readValues();
    }

    /** 热重载: 重新从磁盘读取 config.yml 并刷新全部字段 */
    public void reload() {
        plugin.reloadConfig();
        readValues();
    }

    /** 从 FileConfiguration 读取全部配置到字段 (带默认值兜底) */
    private void readValues() {
        FileConfiguration c = plugin.getConfig();

        // ---------- 存储设置 ----------
        storageType = c.getString("storage.type", "YAML");
        yamlDataFolder = c.getString("storage.yaml.data-folder", "playerdata");
        mysqlHost = c.getString("storage.mysql.host", "localhost");
        mysqlPort = clamp(c.getInt("storage.mysql.port", 3306), 1, 65535);
        mysqlDatabase = c.getString("storage.mysql.database", "minecraft");
        mysqlUsername = c.getString("storage.mysql.username", "root");
        mysqlPassword = c.getString("storage.mysql.password", "");
        mysqlTablePrefix = c.getString("storage.mysql.table-prefix", "bag_");
        mysqlUseSsl = c.getBoolean("storage.mysql.use-ssl", false);

        // ---------- 权限组设置 ----------
        parseGroups(c.getConfigurationSection("groups"));

        // ---------- 仓库通用设置 ----------
        openCooldown = Math.max(0, c.getInt("bag.open-cooldown", 0));
        // 总页数硬上限 (1-100, v1.3.0)
        maxTotalPages = Math.max(1, Math.min(100, c.getInt("bag.max-pages", 20)));

        // ---------- 锁定格设置 (v1.3.0) ----------
        lockedSlotsEnabled = c.getBoolean("locked-slots.enabled", true);
        String mode = c.getString("locked-slots.mode", "BOTTOM_HALF");
        lockedSlotsMode = "CUSTOM".equalsIgnoreCase(mode) ? "CUSTOM" : "BOTTOM_HALF";
        List<Integer> customSlots = c.getIntegerList("locked-slots.slots");
        if (!customSlots.isEmpty()) {
            lockedSlotsCustom = customSlots;
        }
        lockMaterial = matchMaterial(c.getString("locked-slots.material", "STAINED_GLASS_PANE"),
                Material.STAINED_GLASS_PANE);
        lockData = (short) Math.max(0, Math.min(Short.MAX_VALUE, c.getInt("locked-slots.data", 14)));
        lockName = c.getString("locked-slots.name", lockName);
        lockLore = c.getStringList("locked-slots.lore");

        // ---------- 特殊物品设置 (v1.3.0, v1.4.0 附魔, v1.5.0 绑定) ----------
        unlockerMaterial = matchMaterial(c.getString("items.unlocker.material", "IRON_INGOT"),
                Material.IRON_INGOT);
        unlockerData = (short) Math.max(0, Math.min(Short.MAX_VALUE, c.getInt("items.unlocker.data", 0)));
        unlockerEnchanted = c.getBoolean("items.unlocker.enchanted", true);
        unlockerBindOwner = c.getBoolean("items.unlocker.bind-owner", false);
        unlockerName = c.getString("items.unlocker.name", unlockerName);
        unlockerLore = c.getStringList("items.unlocker.lore");
        expanderMaterial = matchMaterial(c.getString("items.expander.material", "EMERALD"),
                Material.EMERALD);
        expanderData = (short) Math.max(0, Math.min(Short.MAX_VALUE, c.getInt("items.expander.data", 0)));
        expanderEnchanted = c.getBoolean("items.expander.enchanted", true);
        expanderBindOwner = c.getBoolean("items.expander.bind-owner", true);
        expanderName = c.getString("items.expander.name", expanderName);
        expanderLore = c.getStringList("items.expander.lore");
        expanderGainLimit = Math.max(0, Math.min(9999, c.getInt("items.expander.gain-limit", 10)));

        // ---------- 体验券设置 (v1.6.0) ----------
        ticket3Material = matchMaterial(c.getString("items.ticket3.material", "PAPER"),
                Material.PAPER);
        ticket3Data = (short) Math.max(0, Math.min(Short.MAX_VALUE, c.getInt("items.ticket3.data", 0)));
        ticket3Enchanted = c.getBoolean("items.ticket3.enchanted", true);
        ticket3BindOwner = c.getBoolean("items.ticket3.bind-owner", false);
        ticket3Days = Math.max(1, Math.min(365, c.getInt("items.ticket3.days", 3)));
        ticket3Name = c.getString("items.ticket3.name", ticket3Name);
        ticket3Lore = c.getStringList("items.ticket3.lore");
        ticket7Material = matchMaterial(c.getString("items.ticket7.material", "PAPER"),
                Material.PAPER);
        ticket7Data = (short) Math.max(0, Math.min(Short.MAX_VALUE, c.getInt("items.ticket7.data", 0)));
        ticket7Enchanted = c.getBoolean("items.ticket7.enchanted", true);
        ticket7BindOwner = c.getBoolean("items.ticket7.bind-owner", false);
        ticket7Days = Math.max(1, Math.min(365, c.getInt("items.ticket7.days", 7)));
        ticket7Name = c.getString("items.ticket7.name", ticket7Name);
        ticket7Lore = c.getStringList("items.ticket7.lore");

        // ---------- 音效 / 粒子设置 (v1.5.0) ----------
        soundsEnabled = c.getBoolean("effects.sounds.enabled", true);
        soundCfgs.clear();
        parseSound(c.getString("effects.sounds.open", "BLOCK_CHEST_OPEN;1.0;1.0"), "open");
        parseSound(c.getString("effects.sounds.page-turn", "ITEM_BOOK_PAGE_TURN;1.0;1.2"), "page-turn");
        parseSound(c.getString("effects.sounds.unlock", "ENTITY_PLAYER_LEVELUP;1.0;1.5"), "unlock");
        parseSound(c.getString("effects.sounds.expand", "ITEM_TOTEM_USE;1.0;1.0"), "expand");
        parseSound(c.getString("effects.sounds.fail", "ENTITY_VILLAGER_NO;1.0;0.8"), "fail");
        parseSound(c.getString("effects.sounds.ticket", "ENTITY_ELDER_GUARDIAN_CURSE;1.0;1.5"), "ticket");
        particlesEnabled = c.getBoolean("effects.particles.enabled", true);
        particleCfgs.clear();
        parseParticle(c.getString("effects.particles.unlock", "TOTEM;40;0.5;0.9;0.5;0.2"), "unlock");
        parseParticle(c.getString("effects.particles.expand", "FIREWORKS_SPARK;50;0.5;1.0;0.5;0.05"), "expand");
        parseParticle(c.getString("effects.particles.ticket", "SPELL_WITCH;50;0.5;1.0;0.5;0.1"), "ticket");

        // ---------- 界面翻页按钮设置 (v1.2.0) ----------
        guiPrevName = c.getString("gui.prev-name", guiPrevName);
        guiPrevLore = c.getString("gui.prev-lore", guiPrevLore);
        guiNextName = c.getString("gui.next-name", guiNextName);
        guiNextLore = c.getString("gui.next-lore", guiNextLore);
        guiLockedName = c.getString("gui.locked-name", guiLockedName);
        guiLockedLore = c.getString("gui.locked-lore", guiLockedLore);
        guiVipLockedName = c.getString("gui.vip-locked-name", guiVipLockedName);
        guiVipLockedLore = c.getString("gui.vip-locked-lore", guiVipLockedLore);
        guiFirstName = c.getString("gui.first-name", guiFirstName);
        guiFirstLore = c.getString("gui.first-lore", guiFirstLore);
        guiLastName = c.getString("gui.last-name", guiLastName);
        guiLastLore = c.getString("gui.last-lore", guiLastLore);

        // ---------- 选项菜单设置 ----------
        menuTitle = c.getString("menu.title", menuTitle);
        menuPagesLabel = c.getString("menu.pages-label", menuPagesLabel);
        menuPageButton = c.getString("menu.page-button", menuPageButton);
        menuHint = c.getString("menu.hint", menuHint);
        List<MenuEntry> parsed = new ArrayList<MenuEntry>();
        for (Map<?, ?> raw : c.getMapList("menu.entries")) {
            Object textObj = raw.get("text");
            if (textObj == null || String.valueOf(textObj).isEmpty()) {
                continue; // 无文本的条目直接跳过
            }
            Object cmdObj = raw.get("command");
            String command = cmdObj == null ? "" : String.valueOf(cmdObj);
            boolean suggest = Boolean.TRUE.equals(raw.get("suggest"));
            boolean admin = Boolean.TRUE.equals(raw.get("admin"));
            parsed.add(new MenuEntry(String.valueOf(textObj), command, suggest, admin));
        }
        this.menuEntries = parsed.isEmpty() ? defaultMenuEntries() : parsed;

        // ---------- 功能开关 ----------
        autoSaveMinutes = Math.max(0, c.getInt("settings.auto-save-minutes", 10));
        autoReload = c.getBoolean("settings.auto-reload", true);
        checkIntervalSeconds = clamp(c.getInt("settings.check-interval-seconds", 5), 1, 3600);

        // ---------- 消息设置 ----------
        prefix = c.getString("messages.prefix", "&6[仓库]&r ");
        msgNoPermission = c.getString("messages.no-permission", msgNoPermission);
        msgPlayerOnly = c.getString("messages.player-only", msgPlayerOnly);
        msgPlayerNotFound = c.getString("messages.player-not-found", msgPlayerNotFound);
        msgNotLoaded = c.getString("messages.not-loaded", msgNotLoaded);
        msgLoading = c.getString("messages.loading", msgLoading);
        msgCooldown = c.getString("messages.cooldown", msgCooldown);
        msgPageInvalid = c.getString("messages.page-invalid", msgPageInvalid);
        msgOpened = c.getString("messages.opened", msgOpened);
        msgOpenedOther = c.getString("messages.opened-other", msgOpenedOther);
        msgReloaded = c.getString("messages.reloaded", msgReloaded);
        msgStorageSwitched = c.getString("messages.storage-switched", msgStorageSwitched);
        msgSaved = c.getString("messages.saved", msgSaved);
        msgGroupNotFound = c.getString("messages.group-not-found", msgGroupNotFound);
        msgGroupBound = c.getString("messages.group-bound", msgGroupBound);
        msgGroupUnbound = c.getString("messages.group-unbound", msgGroupUnbound);
        msgGroupNotBound = c.getString("messages.group-not-bound", msgGroupNotBound);
        msgGroupInfo = c.getString("messages.group-info", msgGroupInfo);
        msgGroupListHeader = c.getString("messages.group-list-header", msgGroupListHeader);
        msgGroupListItem = c.getString("messages.group-list-item", msgGroupListItem);
        msgSlotLockedHint = c.getString("messages.slot-locked-hint", msgSlotLockedHint);
        msgSlotUnlocked = c.getString("messages.slot-unlocked", msgSlotUnlocked);
        msgUnlockerUsed = c.getString("messages.unlocker-used", msgUnlockerUsed);
        msgUnlockerNone = c.getString("messages.unlocker-none", msgUnlockerNone);
        msgUnlockerNotOwner = c.getString("messages.unlocker-not-owner", msgUnlockerNotOwner);
        msgExpanderUsed = c.getString("messages.expander-used", msgExpanderUsed);
        msgExpanderMax = c.getString("messages.expander-max", msgExpanderMax);
        msgPageNeedExpander = c.getString("messages.page-need-expander", msgPageNeedExpander);
        msgItemCantDrop = c.getString("messages.item-cant-drop", msgItemCantDrop);
        msgItemDeathKept = c.getString("messages.item-death-kept", msgItemDeathKept);
        msgTicketUsed = c.getString("messages.ticket-used", msgTicketUsed);
        msgTicketExtend = c.getString("messages.ticket-extend", msgTicketExtend);
        msgTicketMax = c.getString("messages.ticket-max", msgTicketMax);
        msgTicketExpired = c.getString("messages.ticket-expired", msgTicketExpired);
        msgTicketReadonlyPage = c.getString("messages.ticket-readonly-page", msgTicketReadonlyPage);
        msgTicketReadonlyDeny = c.getString("messages.ticket-readonly-deny", msgTicketReadonlyDeny);
        msgTicketPageEmpty = c.getString("messages.ticket-page-empty", msgTicketPageEmpty);
        ticketTitleSuffix = c.getString("messages.ticket-title-suffix", ticketTitleSuffix);
        msgTicketInfoLine = c.getString("messages.ticket-info-line", msgTicketInfoLine);
        msgExpanderGainLimit = c.getString("messages.expander-gain-limit", msgExpanderGainLimit);
        msgExpanderGainTruncated = c.getString("messages.expander-gain-truncated", msgExpanderGainTruncated);
        msgGiveUsage = c.getString("messages.give-usage", msgGiveUsage);
        msgGiveInvalidType = c.getString("messages.give-invalid-type", msgGiveInvalidType);
        msgGivePlayerOffline = c.getString("messages.give-player-offline", msgGivePlayerOffline);
        msgGiveSuccess = c.getString("messages.give-success", msgGiveSuccess);
        msgGiveReceived = c.getString("messages.give-received", msgGiveReceived);
    }

    /** 解析权限组配置段 (组名/页数/行数/权限节点/每页标题全部自定义) */
    private void parseGroups(ConfigurationSection section) {
        groups.clear();
        Group fallbackDefault = null;

        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection sec = section.getConfigurationSection(key);
                if (sec == null) {
                    continue; // 非法节点跳过
                }
                int pages = clamp(sec.getInt("pages", 1), 1, 100);
                int rows = clamp(sec.getInt("rows", 6), 1, 6);
                String permission = sec.getString("permission", null);

                // 逐页标题 (优先级高)
                Map<Integer, String> titles = new LinkedHashMap<Integer, String>();
                ConfigurationSection titleSec = sec.getConfigurationSection("titles");
                if (titleSec != null) {
                    for (String pageKey : titleSec.getKeys(false)) {
                        try {
                            titles.put(Integer.parseInt(pageKey), titleSec.getString(pageKey, ""));
                        } catch (NumberFormatException ignored) {
                            // 非数字页码键跳过
                        }
                    }
                }
                // 统一模板 (备选)
                String template = sec.getString("title", null);

                Group group = new Group(key, permission, pages, rows, titles, template);
                groups.put(key, group);
                // 名为 default 或无权限节点的第一个组作为兜底默认组
                if (fallbackDefault == null && group.isDefault()) {
                    fallbackDefault = group;
                }
            }
        }

        // 兜底: 无任何合法组时使用内置默认组 (1 页 6 行)
        if (fallbackDefault == null) {
            fallbackDefault = defaultGroup;
        }
        this.defaultGroup = fallbackDefault;
    }

    // ==================== 权限组判定 ====================

    /**
     * 获取玩家的权限组: 拥有权限的组中取页数最多者, 无则 default 组
     * (性能: 组数量通常 < 10, 遍历开销可忽略; 每次 open 时调用)
     */
    public Group getGroup(Player player) {
        Group best = defaultGroup;
        for (Group group : groups.values()) {
            if (group.permission != null
                    && group.pages > best.pages
                    && player.hasPermission(group.permission)) {
                best = group;
            }
        }
        return best;
    }

    /** 全局页数最大的组 (管理员查看离线玩家时的页数上限) */
    public Group getMaxGroup() {
        Group best = defaultGroup;
        for (Group group : groups.values()) {
            if (group.pages > best.pages) {
                best = group;
            }
        }
        return best;
    }

    /** 兜底默认组 */
    public Group getDefaultGroup() {
        return defaultGroup;
    }

    /** 已加载的权限组数量 (info 展示用) */
    public int getGroupCount() {
        return groups.size();
    }

    /** 按组名查组 (不存在返回 null; 大小写不敏感) */
    public Group getGroupByName(String name) {
        if (name == null) {
            return null;
        }
        for (Group group : groups.values()) {
            if (group.name.equalsIgnoreCase(name)) {
                return group;
            }
        }
        return null;
    }

    /**
     * 查询"能解锁指定页数"的最小权限组 (专属页提示用)
     * 例: default=1页 vip1=2页 vip2=5页, 查第2页 -> vip1, 查第4页 -> vip2
     * 页数超过全部组的最大页数时返回 null (显示通用无效页提示)
     */
    public Group getGroupForPage(int page) {
        Group best = null;
        for (Group group : groups.values()) {
            if (group.pages >= page && (best == null || group.pages < best.pages)) {
                best = group;
            }
        }
        return best;
    }

    /** 全部组名 (逗号分隔, group-not-found 消息展示用) */
    public String getGroupNames() {
        StringBuilder sb = new StringBuilder();
        for (Group group : groups.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(group.name);
        }
        return sb.toString();
    }

    /** 全部组对象 (list 指令展示用, 保持配置顺序) */
    public java.util.Collection<Group> getAllGroups() {
        return groups.values();
    }

    /**
     * 生成指定页的界面标题
     * 优先级: 逐页标题 > 统一模板 > 内置默认
     * 占位符: {page}=页码, {player}=玩家名 (自动翻译颜色代码)
     */
    public String getPageTitle(Group group, int page, String playerName) {
        String title = group.titles.get(page);
        if (title == null) {
            title = group.titleTemplate;
        }
        if (title == null) {
            title = "&8仓库 {page}";
        }
        return TextUtil.color(title
                .replace("{page}", String.valueOf(page))
                .replace("{player}", playerName == null ? "" : playerName));
    }

    // ==================== 工具方法 ====================

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** 获取消息 (自动拼接前缀 + 翻译颜色代码) */
    public String getMsg(String key) {
        String raw;
        switch (key) {
            case "no-permission": raw = msgNoPermission; break;
            case "player-only": raw = msgPlayerOnly; break;
            case "player-not-found": raw = msgPlayerNotFound; break;
            case "not-loaded": raw = msgNotLoaded; break;
            case "loading": raw = msgLoading; break;
            case "cooldown": raw = msgCooldown; break;
            case "page-invalid": raw = msgPageInvalid; break;
            case "opened": raw = msgOpened; break;
            case "opened-other": raw = msgOpenedOther; break;
            case "reloaded": raw = msgReloaded; break;
            case "storage-switched": raw = msgStorageSwitched; break;
            case "saved": raw = msgSaved; break;
            case "page-locked": raw = msgPageLocked; break;
            case "group-not-found": raw = msgGroupNotFound; break;
            case "group-bound": raw = msgGroupBound; break;
            case "group-unbound": raw = msgGroupUnbound; break;
            case "group-not-bound": raw = msgGroupNotBound; break;
            case "group-info": raw = msgGroupInfo; break;
            case "group-list-header": raw = msgGroupListHeader; break;
            case "group-list-item": raw = msgGroupListItem; break;
            case "slot-locked-hint": raw = msgSlotLockedHint; break;
            case "slot-unlocked": raw = msgSlotUnlocked; break;
            case "unlocker-used": raw = msgUnlockerUsed; break;
            case "unlocker-none": raw = msgUnlockerNone; break;
            case "unlocker-not-owner": raw = msgUnlockerNotOwner; break;
            case "expander-used": raw = msgExpanderUsed; break;
            case "expander-max": raw = msgExpanderMax; break;
            case "page-need-expander": raw = msgPageNeedExpander; break;
            case "item-cant-drop": raw = msgItemCantDrop; break;
            case "item-death-kept": raw = msgItemDeathKept; break;
            case "ticket-used": raw = msgTicketUsed; break;
            case "ticket-extend": raw = msgTicketExtend; break;
            case "ticket-max": raw = msgTicketMax; break;
            case "ticket-expired": raw = msgTicketExpired; break;
            case "ticket-readonly-page": raw = msgTicketReadonlyPage; break;
            case "ticket-readonly-deny": raw = msgTicketReadonlyDeny; break;
            case "ticket-page-empty": raw = msgTicketPageEmpty; break;
            case "ticket-info-line": raw = msgTicketInfoLine; break;
            case "expander-gain-limit": raw = msgExpanderGainLimit; break;
            case "expander-gain-truncated": raw = msgExpanderGainTruncated; break;
            case "give-usage": raw = msgGiveUsage; break;
            case "give-invalid-type": raw = msgGiveInvalidType; break;
            case "give-player-offline": raw = msgGivePlayerOffline; break;
            case "give-success": raw = msgGiveSuccess; break;
            case "give-received": raw = msgGiveReceived; break;
            default: raw = ""; break;
        }
        return TextUtil.color(prefix + raw);
    }

    /** 存储配置指纹 (热重载时判断是否需要重建存储连接) */
    public String storageSignature() {
        return storageType + "|" + yamlDataFolder + "|" + mysqlHost + ":" + mysqlPort
                + "/" + mysqlDatabase + "|" + mysqlUsername + "|" + mysqlPassword
                + "|" + mysqlTablePrefix + "|" + mysqlUseSsl;
    }

    // ==================== Getter ====================

    public File getPluginDataFolder() {
        return plugin.getDataFolder();
    }

    public String getStorageType() {
        return storageType;
    }

    public String getYamlDataFolder() {
        return yamlDataFolder;
    }

    public String getMysqlHost() {
        return mysqlHost;
    }

    public int getMysqlPort() {
        return mysqlPort;
    }

    public String getMysqlDatabase() {
        return mysqlDatabase;
    }

    public String getMysqlUsername() {
        return mysqlUsername;
    }

    public String getMysqlPassword() {
        return mysqlPassword;
    }

    public String getMysqlTablePrefix() {
        return mysqlTablePrefix;
    }

    public boolean isMysqlUseSsl() {
        return mysqlUseSsl;
    }

    public int getOpenCooldown() {
        return openCooldown;
    }

    /** 仓库总页数硬上限 (v1.3.0) */
    public int getMaxTotalPages() {
        return maxTotalPages;
    }

    // ==================== 锁定格 / 特殊物品 (v1.3.0) ====================

    /** 材质名解析: 匹配失败时回退默认材质 (不抛异常) */
    private Material matchMaterial(String name, Material fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        Material m = Material.matchMaterial(name);
        if (m == null) {
            plugin.getLogger().warning("配置中的材质名无效: " + name + ", 回退为 " + fallback);
            return fallback;
        }
        return m;
    }

    /**
     * 计算指定界面大小的锁定格槽位集合
     * - BOTTOM_HALF 模式: 界面上下两半的下半部分 (自动避开两端翻页按钮格);
     * - CUSTOM 模式: 配置的槽位列表 (过滤越界值与按钮格);
     * - 功能关闭时返回空集合。
     */
    public Set<Integer> computeLockedSlots(int pageSize) {
        Set<Integer> set = new TreeSet<Integer>();
        if (!lockedSlotsEnabled || pageSize <= 0) {
            return set;
        }
        int prevBtn = pageSize - 9; // 上一页按钮格
        int nextBtn = pageSize - 1; // 下一页按钮格
        if ("CUSTOM".equals(lockedSlotsMode)) {
            for (int slot : lockedSlotsCustom) {
                if (slot >= 0 && slot < pageSize && slot != prevBtn && slot != nextBtn) {
                    set.add(slot);
                }
            }
        } else {
            // 下半部分: 从界面中点开始到末尾 (整数除法, 54格时为 27-53)
            for (int slot = pageSize / 2; slot < pageSize; slot++) {
                if (slot != prevBtn && slot != nextBtn) {
                    set.add(slot);
                }
            }
        }
        return set;
    }

    /** 构造锁定格显示物品 (配置的材质/名称/描述) */
    public ItemStack buildLockItem() {
        return buildSpecialItem(lockMaterial, lockData, lockName, lockLore, 1, false);
    }

    /**
     * 构造开锁器物品 (give 指令用, v1.5.0 附魔光效 + 可选绑定行 + 来源行)
     *
     * @param amount    数量
     * @param bindPlayer 绑定玩家名 (null=不绑定; bind-owner 开启时由指令层传入)
     * @param source    来源描述 (发放者名+时间, null=不记录)
     */
    public ItemStack buildUnlocker(int amount, String bindPlayer, String source) {
        List<String> lore = new ArrayList<String>(unlockerLore);
        if (bindPlayer != null && !bindPlayer.isEmpty()) {
            lore.add(BIND_PREFIX + bindPlayer);
        }
        if (source != null && !source.isEmpty()) {
            lore.add(SOURCE_PREFIX + source);
        }
        return buildSpecialItem(unlockerMaterial, unlockerData, unlockerName, lore,
                amount, unlockerEnchanted);
    }

    /**
     * 构造拓展器物品 (give 指令用, v1.6.1 附魔光效 + 可选绑定行 + 来源行)
     *
     * @param amount     数量
     * @param bindPlayer 绑定玩家名 (null=不绑定; bind-owner 开启时由指令层传入)
     * @param source     来源描述 (发放者名+时间, null=不记录)
     */
    public ItemStack buildExpander(int amount, String bindPlayer, String source) {
        List<String> lore = new ArrayList<String>(expanderLore);
        if (bindPlayer != null && !bindPlayer.isEmpty()) {
            lore.add(BIND_PREFIX + bindPlayer);
        }
        if (source != null && !source.isEmpty()) {
            lore.add(SOURCE_PREFIX + source);
        }
        return buildSpecialItem(expanderMaterial, expanderData, expanderName, lore,
                amount, expanderEnchanted);
    }

    /** 开锁器是否启用玩家绑定 (v1.6.1: 默认关闭) */
    public boolean isUnlockerBindOwner() {
        return unlockerBindOwner;
    }

    /** 拓展器是否启用玩家绑定 (v1.6.1: 默认开启, 唯一绑定物品) */
    public boolean isExpanderBindOwner() {
        return expanderBindOwner;
    }

    /** 开锁器显示名 (give-success 消息 {item} 占位符用) */
    public String getUnlockerDisplayName() {
        return TextUtil.color(unlockerName);
    }

    /** 拓展器显示名 (give-success 消息 {item} 占位符用) */
    public String getExpanderDisplayName() {
        return TextUtil.color(expanderName);
    }

    /** 拓展器累计获得上限 (v1.6.0, 0 = 不限量) */
    public int getExpanderGainLimit() {
        return expanderGainLimit;
    }

    // ==================== 体验券 (v1.6.0) ====================

    /**
     * 构造体验券物品 (give 指令用)
     *
     * @param type       "ticket3" 或 "ticket7"
     * @param amount     数量
     * @param bindPlayer 绑定玩家名 (对应券 bind-owner 开启时传入, null=不绑定)
     * @param source     来源描述 (null=不记录)
     */
    public ItemStack buildTicket(String type, int amount, String bindPlayer, String source) {
        boolean t3 = "ticket3".equals(type);
        Material material = t3 ? ticket3Material : ticket7Material;
        short data = t3 ? ticket3Data : ticket7Data;
        String name = t3 ? ticket3Name : ticket7Name;
        List<String> baseLore = t3 ? ticket3Lore : ticket7Lore;
        boolean enchanted = t3 ? ticket3Enchanted : ticket7Enchanted;
        boolean bind = t3 ? ticket3BindOwner : ticket7BindOwner;

        List<String> lore = new ArrayList<String>(baseLore);
        if (bind && bindPlayer != null && !bindPlayer.isEmpty()) {
            lore.add(BIND_PREFIX + bindPlayer);
        }
        if (source != null && !source.isEmpty()) {
            lore.add(SOURCE_PREFIX + source);
        }
        return buildSpecialItem(material, data, name, lore, amount, enchanted);
    }

    /** 精确比对物品是否为 3 天体验券 */
    public boolean isTicket3(ItemStack item) {
        return matches(item, ticket3Material, ticket3Data, ticket3Name, ticket3Lore);
    }

    /** 精确比对物品是否为 7 天体验券 */
    public boolean isTicket7(ItemStack item) {
        return matches(item, ticket7Material, ticket7Data, ticket7Name, ticket7Lore);
    }

    /** 是否任意一种体验券 */
    public boolean isTicket(ItemStack item) {
        return isTicket3(item) || isTicket7(item);
    }

    /** 体验券是否启用绑定 (对应券种 bind-owner 开关) */
    public boolean isTicketBindOwner(String type) {
        return "ticket3".equals(type) ? ticket3BindOwner : ticket7BindOwner;
    }

    /** 体验券时长 (天) */
    public int getTicketDays(String type) {
        return "ticket3".equals(type) ? ticket3Days : ticket7Days;
    }

    /** 体验券显示名 (give-success 消息 {item} 占位符用) */
    public String getTicketDisplayName(String type) {
        return TextUtil.color("ticket3".equals(type) ? ticket3Name : ticket7Name);
    }

    /**
     * 取"下一个更高权限组": 按页数升序, 返回页数大于当前组的最小组
     * (体验券升级路径: default -> vip1 -> vip2 -> svip ...)
     *
     * @return 下一级组; 已是最高组返回 null
     */
    public Group getNextGroup(Group current) {
        Group next = null;
        for (Group g : groups.values()) {
            if (g.pages > current.pages && (next == null || g.pages < next.pages)) {
                next = g;
            }
        }
        return next;
    }

    /**
     * 剩余时间格式化 (v1.6.0): 毫秒 -> "X天X时X分" (最小单位分钟, 不足 1 分钟按 1 分算)
     */
    public static String formatRemain(long millis) {
        if (millis <= 0) {
            return "0分";
        }
        long minutes = millis / 60000L;
        if (minutes < 1) {
            minutes = 1; // 剩余不足 1 分钟: 显示 1 分
        }
        long days = minutes / 1440L;
        minutes %= 1440L;
        long hours = minutes / 60L;
        minutes %= 60L;
        return days + "天" + hours + "时" + minutes + "分";
    }

    /**
     * 体验券界面标题后缀 (已着色, {group}/{time} 已替换)
     * 用于 BagGui 拼接在原页标题之后
     */
    public String getTicketTitleSuffix(String group, long expireAt) {
        return TextUtil.color(ticketTitleSuffix)
                .replace("{group}", group)
                .replace("{time}", formatRemain(expireAt - System.currentTimeMillis()));
    }

    /**
     * 精确比对物品是否为开锁器: 材质 + 数据值 + 名称 + 描述前缀全部一致
     * (v1.5.0: 配置描述行之后允许追加 绑定/来源 元数据行, 不影响识别;
     * 铁砧改名/其他插件物品因名称或描述不一致不会误判)
     */
    public boolean isUnlocker(ItemStack item) {
        return matches(item, unlockerMaterial, unlockerData, unlockerName, unlockerLore);
    }

    /** 精确比对物品是否为拓展器 (同上规则) */
    public boolean isExpander(ItemStack item) {
        return matches(item, expanderMaterial, expanderData, expanderName, expanderLore);
    }

    /** 是否任意一种特殊物品 (开锁器/拓展器/体验券, 防丢防转移拦截用) */
    public boolean isSpecialItem(ItemStack item) {
        return isUnlocker(item) || isExpander(item) || isTicket(item);
    }

    /**
     * 读取物品 lore 中的绑定玩家名 (v1.5.0)
     *
     * @return 绑定的玩家名; 无绑定行返回 null (旧版物品, 不限制使用)
     */
    public String getBindOwner(ItemStack item) {
        return readLoreMeta(item, BIND_PREFIX);
    }

    /**
     * 读取物品 lore 中的来源描述 (v1.5.0)
     *
     * @return 来源描述 (如 "Steve 08/26 22:00"); 无来源行返回 null
     */
    public String getItemSource(ItemStack item) {
        return readLoreMeta(item, SOURCE_PREFIX);
    }

    /** 从物品 lore 中读取指定前缀的元数据行内容 (无则 null) */
    private String readLoreMeta(ItemStack item, String rawPrefix) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return null;
        }
        String prefix = TextUtil.color(rawPrefix);
        for (String line : meta.getLore()) {
            if (line != null && line.startsWith(prefix) && line.length() > prefix.length()) {
                return line.substring(prefix.length());
            }
        }
        return null;
    }

    /** 通用特殊物品构造 (v1.4.0: 支持附魔光效, 隐藏附魔属性文字) */
    private ItemStack buildSpecialItem(Material material, short data, String name,
                                       List<String> lore, int amount, boolean enchanted) {
        ItemStack item = new ItemStack(material, Math.max(1, amount), data);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(TextUtil.color(name));
            List<String> colored = new ArrayList<String>();
            for (String line : lore) {
                colored.add(TextUtil.color(line));
            }
            meta.setLore(colored);
            if (enchanted) {
                // 耐久 I 附魔只为发光视觉效果, 隐藏属性文字保持物品栏整洁
                meta.addEnchant(org.bukkit.enchantments.Enchantment.DURABILITY, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 通用特殊物品精确比对 (材质/数据值/彩色名称/彩色描述前缀)
     * v1.5.0: 描述只需前 N 行与配置完全一致 (N=配置描述行数),
     * 其后的 绑定/来源 元数据行参与归属判定但不影响"是特殊物品"的识别
     */
    private boolean matches(ItemStack item, Material material, short data,
                            String name, List<String> lore) {
        if (item == null || item.getType() != material || item.getDurability() != data) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return false;
        }
        if (!meta.getDisplayName().equals(TextUtil.color(name))) {
            return false;
        }
        // 描述前缀比对: 物品描述行数 >= 配置行数, 且前 N 行逐行精确一致
        List<String> itemLore = meta.hasLore() ? meta.getLore() : new ArrayList<String>();
        if (itemLore.size() < lore.size()) {
            return false;
        }
        for (int i = 0; i < lore.size(); i++) {
            if (!itemLore.get(i).equals(TextUtil.color(lore.get(i)))) {
                return false;
            }
        }
        return true;
    }

    // ==================== 音效 / 粒子 (v1.5.0) ====================

    /**
     * 解析音效配置 "音效名;音量;音调" 到场景表
     * 格式非法/音效名不存在: 后台警告并跳过 (播放时静默无声, 不抛异常)
     */
    private void parseSound(String def, String key) {
        if (def == null || def.trim().isEmpty()) {
            return; // 留空 = 该场景关闭音效
        }
        try {
            String[] parts = def.split(";");
            // v1.6.4 修复: 1.8 旧音效名兼容映射。1.9 起音效枚举加了前缀,
            // 旧配置/旧教程里的 CHEST_OPEN 在 1.12.2 不存在, 此前直接被跳过
            // 导致打开仓库音效静默失效, 现在自动映射到正确枚举。
            String name = parts[0].trim().toUpperCase();
            if ("CHEST_OPEN".equals(name)) {
                name = "BLOCK_CHEST_OPEN";
            } else if ("CHEST_CLOSE".equals(name)) {
                name = "BLOCK_CHEST_CLOSE";
            } else if ("CLICK".equals(name)) {
                name = "UI_BUTTON_CLICK";
            }
            Sound sound = Sound.valueOf(name);
            float volume = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 1.0f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 1.0f;
            soundCfgs.put(key, new SoundCfg(sound,
                    Math.max(0f, Math.min(10f, volume)),
                    Math.max(0f, Math.min(10f, pitch))));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("音效配置无效 (已跳过 " + key + "): " + def);
        }
    }

    /** 解析粒子配置 "粒子名;数量;偏移X;偏移Y;偏移Z;速度" 到场景表 */
    private void parseParticle(String def, String key) {
        if (def == null || def.trim().isEmpty()) {
            return; // 留空 = 该场景关闭粒子
        }
        try {
            String[] parts = def.split(";");
            Particle particle = Particle.valueOf(parts[0].trim().toUpperCase());
            int count = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 20;
            double ox = parts.length > 2 ? Double.parseDouble(parts[2].trim()) : 0.5;
            double oy = parts.length > 3 ? Double.parseDouble(parts[3].trim()) : 0.9;
            double oz = parts.length > 4 ? Double.parseDouble(parts[4].trim()) : 0.5;
            double speed = parts.length > 5 ? Double.parseDouble(parts[5].trim()) : 0.1;
            particleCfgs.put(key, new ParticleCfg(particle,
                    Math.max(1, Math.min(500, count)), ox, oy, oz, Math.max(0, speed)));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("粒子配置无效 (已跳过 " + key + "): " + def);
        }
    }

    /**
     * 播放场景音效 (总开关关闭/场景未配置/玩家不在线时静默跳过)
     *
     * @param key open / page-turn / unlock / expand / fail
     */
    public void playSound(Player player, String key) {
        if (player == null || !soundsEnabled) {
            return;
        }
        SoundCfg cfg = soundCfgs.get(key);
        if (cfg != null) {
            player.playSound(player.getLocation(), cfg.sound, cfg.volume, cfg.pitch);
        }
    }

    /**
     * 在玩家头顶播放场景粒子 (总开关关闭/场景未配置时静默跳过)
     *
     * @param key unlock / expand
     */
    public void playParticle(Player player, String key) {
        if (player == null || !particlesEnabled) {
            return;
        }
        ParticleCfg cfg = particleCfgs.get(key);
        if (cfg != null) {
            player.spawnParticle(cfg.particle, player.getLocation().add(0, 1, 0),
                    cfg.count, cfg.offsetX, cfg.offsetY, cfg.offsetZ, cfg.speed);
        }
    }

    // ==================== 界面翻页按钮 Getter (v1.2.0) ====================

    /** 翻页按钮名称/描述 (已翻译颜色, {page} 已替换; locked 含 {group}) */
    public String getGuiPrevName(int page) {
        return TextUtil.color(guiPrevName);
    }

    public java.util.List<String> getGuiPrevLore(int page) {
        return java.util.Collections.singletonList(
                TextUtil.color(guiPrevLore.replace("{page}", String.valueOf(page))));
    }

    public String getGuiNextName(int page) {
        return TextUtil.color(guiNextName);
    }

    public java.util.List<String> getGuiNextLore(int page) {
        return java.util.Collections.singletonList(
                TextUtil.color(guiNextLore.replace("{page}", String.valueOf(page))));
    }

    /** 未解锁下一页 (需拓展器) 的按钮名/描述 (v1.4.0) */
    public String getGuiLockedName() {
        return TextUtil.color(guiLockedName);
    }

    public java.util.List<String> getGuiLockedLore() {
        return java.util.Collections.singletonList(TextUtil.color(guiLockedLore));
    }

    /** 超出 VIP 权限的下一页按钮名/描述 (v1.4.0, {group}=需要的组名) */
    public String getGuiVipLockedName() {
        return TextUtil.color(guiVipLockedName);
    }

    public java.util.List<String> getGuiVipLockedLore(String group) {
        return java.util.Collections.singletonList(
                TextUtil.color(guiVipLockedLore.replace("{group}", group)));
    }

    public String getGuiFirstName() {
        return TextUtil.color(guiFirstName);
    }

    public java.util.List<String> getGuiFirstLore() {
        return java.util.Collections.singletonList(TextUtil.color(guiFirstLore));
    }

    public String getGuiLastName() {
        return TextUtil.color(guiLastName);
    }

    public java.util.List<String> getGuiLastLore() {
        return java.util.Collections.singletonList(TextUtil.color(guiLastLore));
    }

    public String getMenuTitle() {
        return TextUtil.color(menuTitle);
    }

    public String getMenuPagesLabel() {
        return TextUtil.color(menuPagesLabel);
    }

    /** 页码按钮样式 ({page} 已替换为指定页码) */
    public String getMenuPageButton(int page) {
        return TextUtil.color(menuPageButton.replace("{page}", String.valueOf(page)));
    }

    public String getMenuHint() {
        return TextUtil.color(menuHint);
    }

    public List<MenuEntry> getMenuEntries() {
        return menuEntries;
    }

    public int getAutoSaveMinutes() {
        return autoSaveMinutes;
    }

    public boolean isAutoReload() {
        return autoReload;
    }

    public int getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }
}
