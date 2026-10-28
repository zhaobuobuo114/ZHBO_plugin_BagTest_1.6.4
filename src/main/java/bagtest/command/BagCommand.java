package bagtest.command;

import bagtest.BagPlugin;
import bagtest.config.ConfigManager;
import bagtest.config.ConfigManager.Group;
import bagtest.gui.BagGui;
import bagtest.manager.GroupManager;
import bagtest.util.TextUtil;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 【模块七：指令层】
 * 主指令 /bag 的执行器与 Tab 补全 (v2 多页版)
 *
 * 指令结构:
 *   /bag                    显示选项菜单 (含可点击的页码按钮)
 *   /bag open [页码]         打开自己仓库的指定页 (默认第 1 页)
 *   /bag <页码>              上一条的简写 (如 /bag 2)
 *   /bag open <玩家> [页码]  打开指定玩家仓库 (admin, 支持离线)
 *   /bag vip <玩家> <编号|组名|remove>  快捷设置玩家VIP组 (admin, v1.2.0)
 *   /bag give <玩家> <unlocker|expander> [数量]  给予开锁器/拓展器 (admin, v1.3.0)
 *   /bag group add <玩家> <组>  设置玩家权限组 (admin, v1.1.0)
 *   /bag group remove <玩家>    移除玩家自定义分组 (admin)
 *   /bag group info <玩家>      查看玩家生效的组 (admin)
 *   /bag group list             列出全部权限组 (admin)
 *   /bag reload             手动热重载配置文件 (admin)
 *   /bag save               保存全部在线玩家数据 (admin)
 *   /bag info               查看插件运行信息 (含自己的生效组)
 *   /bag help               显示选项菜单
 */
public class BagCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_USE = "bagtest.use";
    private static final String PERM_ADMIN = "bagtest.admin";

    private final BagPlugin plugin;

    public BagCommand(BagPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 无参数 = 显示选项菜单
        if (args.length == 0) {
            sendMenu(sender);
            return true;
        }

        // 简写: /bag <数字> = 打开自己仓库的对应页
        Integer shortPage = tryParse(args[0]);
        if (shortPage != null) {
            handleOpenSelf(sender, shortPage);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "open":
                handleOpen(sender, args);
                return true;
            case "reload":
                handleReload(sender);
                return true;
            case "save":
                handleSave(sender);
                return true;
            case "info":
                handleInfo(sender);
                return true;
            case "group":
                // v1.1.0: 分组管理指令 (/bag group add/remove/info/list)
                handleGroup(sender, args);
                return true;
            case "vip":
                // v1.2.0: VIP 快捷分组 (/bag vip <玩家> <编号|组名|remove>)
                handleVip(sender, args);
                return true;
            case "give":
                // v1.3.0: 给予特殊物品 (/bag give <玩家> <unlocker|expander> [数量])
                handleGive(sender, args);
                return true;
            case "help":
            case "menu":
                sendMenu(sender);
                return true;
            default:
                // 未知子指令: 不报错, 直接显示菜单
                sendMenu(sender);
                return true;
        }
    }

    // ==================== 子指令实现 ====================

    /** 打开自己仓库的指定页 (/bag <页码> 与 /bag open [页码] 共用) */
    private void handleOpenSelf(CommandSender sender, int page) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("player-only"));
            return;
        }
        if (!sender.hasPermission(PERM_USE)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("no-permission"));
            return;
        }
        Player player = (Player) sender;
        if (!checkCooldown(player)) {
            return;
        }
        // openView 返回 true 时发送打开提示 (含页码) + 打开音效 (v1.5.0)
        if (openView(plugin, player, player.getUniqueId(), player.getName(), page, true)) {
            plugin.getConfigManager().playSound(player, "open");
            player.sendMessage(plugin.getConfigManager().getMsg("opened")
                    .replace("{page}", String.valueOf(page)));
        }
    }

    /**
     * /bag open [页码] : 打开自己仓库
     * /bag open <玩家> [页码] : 打开他人仓库 (admin, 支持离线)
     */
    private void handleOpen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("player-only"));
            return;
        }
        if (!sender.hasPermission(PERM_USE)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("no-permission"));
            return;
        }
        Player player = (Player) sender;

        // ---- /bag open [页码]: 第一参数是数字 = 自己的页码 ----
        Integer page = (args.length >= 2) ? tryParse(args[1]) : null;
        if (args.length == 1 || page != null) {
            handleOpenSelf(sender, page == null ? 1 : page);
            return;
        }

        // ---- /bag open <玩家> [页码]: 管理员打开他人 ----
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("no-permission"));
            return;
        }
        String name = args[1];
        int targetPage = 1;
        if (args.length >= 3) {
            Integer parsed = tryParse(args[2]);
            if (parsed == null) {
                sendMenu(sender); // 页码参数非法: 显示菜单引导
                return;
            }
            targetPage = parsed;
        }

        // 优先匹配在线玩家
        Player target = Bukkit.getPlayerExact(name);
        if (target != null) {
            if (openView(plugin, player, target.getUniqueId(), target.getName(), targetPage, false)) {
                plugin.getConfigManager().playSound(player, "open"); // 打开音效 (v1.5.0)
                player.sendMessage(plugin.getConfigManager().getMsg("opened-other")
                        .replace("{player}", target.getName())
                        .replace("{page}", String.valueOf(targetPage)));
            }
            return;
        }

        // 离线玩家: 通过用户缓存解析 UUID (仅支持进过服务器的玩家, 不发起网络请求)
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (!offline.hasPlayedBefore()) {
            sender.sendMessage(plugin.getConfigManager().getMsg("player-not-found")
                    .replace("{player}", name));
            return;
        }
        final UUID ownerUuid = offline.getUniqueId();
        final int finalPage = targetPage;
        // 离线仓库可能未加载: 先触发异步加载, 完成后自动打开
        if (plugin.getBagManager().getBag(ownerUuid) == null) {
            player.sendMessage(plugin.getConfigManager().getMsg("loading")
                    .replace("{player}", name));
            plugin.getBagManager().loadAsync(ownerUuid, name, false, () -> {
                if (player.isOnline()) {
                    if (BagGui.open(plugin, player, ownerUuid, name, finalPage)) {
                        plugin.getConfigManager().playSound(player, "open"); // 打开音效 (v1.5.0)
                        player.sendMessage(plugin.getConfigManager().getMsg("opened-other")
                                .replace("{player}", name)
                                .replace("{page}", String.valueOf(finalPage)));
                    }
                }
            });
            return;
        }
        if (openView(plugin, player, ownerUuid, name, finalPage, false)) {
            plugin.getConfigManager().playSound(player, "open"); // 打开音效 (v1.5.0)
            player.sendMessage(plugin.getConfigManager().getMsg("opened-other")
                    .replace("{player}", name)
                    .replace("{page}", String.valueOf(finalPage)));
        }
    }

    /**
     * 统一的打开入口 (数据已在缓存的场景)
     *
     * @param self true=打开自己的仓库(未加载时提示稍后), false=查看他人
     * @return true=打开成功
     */
    private boolean openView(BagPlugin plugin, Player viewer, UUID owner,
                             String ownerName, int page, boolean self) {
        if (plugin.getBagManager().getBag(owner) != null) {
            return BagGui.open(plugin, viewer, owner, ownerName, page);
        }
        if (self) {
            // 自己的仓库进服时已预加载, 走到这里说明还在加载中
            viewer.sendMessage(plugin.getConfigManager().getMsg("not-loaded"));
        } else {
            viewer.sendMessage(plugin.getConfigManager().getMsg("loading")
                    .replace("{player}", ownerName));
        }
        plugin.getConfigManager().playSound(viewer, "fail"); // 失败音效 (v1.5.0)
        return false;
    }

    /** /bag reload : 手动热重载配置 */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("no-permission"));
            return;
        }
        plugin.reloadAll(sender);
    }

    /** /bag save : 保存全部在线玩家数据 */
    private void handleSave(CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("no-permission"));
            return;
        }
        int count = plugin.getBagManager().saveAllAsync();
        sender.sendMessage(plugin.getConfigManager().getMsg("saved")
                .replace("{count}", String.valueOf(count)));
    }

    /** /bag info : 插件运行信息 (含自己的权限组与页数) */
    private void handleInfo(CommandSender sender) {
        if (!sender.hasPermission(PERM_USE)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("no-permission"));
            return;
        }
        ConfigManager cfg = plugin.getConfigManager();
        sender.sendMessage(TextUtil.color(cfg.getMsg("prefix") + "&7===== &6【ZHBO】plugin_BagTest_1.6.4 信息 &7====="));
        sender.sendMessage(TextUtil.color("&7作者: &ezhaobuobuo &7| 联系: &e1071576270@QQ.COM"));
        sender.sendMessage(TextUtil.color("&7版本: &e" + plugin.getDescription().getVersion()));
        sender.sendMessage(TextUtil.color("&7存储方式: &e" + plugin.getStorage().getTypeName()));
        sender.sendMessage(TextUtil.color("&7已加载权限组: &e" + cfg.getGroupCount() + " &7个"));
        if (sender instanceof Player) {
            Player p = (Player) sender;
            // 展示发送者自己的生效组 (v1.1.0: 绑定组优先, 附来源与总格数)
            Group group = plugin.getGroupManager().getEffectiveGroup(p.getUniqueId(), p);
            String binding = plugin.getGroupManager().getBinding(p.getUniqueId());
            String source = (binding != null && cfg.getGroupByName(binding) != null)
                    ? "指令绑定" : "权限判定";
            sender.sendMessage(TextUtil.color("&7你的权限组: &e" + group.name
                    + " &7(&e" + group.pages + " 页 &7x &e" + group.rows + " 行 &7= &e"
                    + (group.pages * group.rows * 9) + " 格&7, 来源: " + source + ")"));
            // 已解锁页数 (v1.4.0: VIP 只是权力, 页面靠拓展器逐页解锁;
            // v1.6.1: 体验激活时体验组全部页面默认解锁)
            int pageLimit = Math.min(group.pages,
                    Math.max(cfg.getMaxTotalPages(), group.pages));
            int unlockedPages = pageLimit;
            bagtest.model.PlayerBag selfBag = plugin.getBagManager().getBag(p.getUniqueId());
            if (selfBag != null) {
                boolean ticketActive = false;
                if (selfBag.isTicketActive()) {
                    // v1.6.2 动态解析: 体验组 = 基础组上一级, 全部页面默认解锁
                    Group tg = cfg.getNextGroup(group);
                    if (tg != null) {
                        ticketActive = true; // 体验组全开
                    }
                }
                if (!ticketActive) {
                    unlockedPages = Math.min(1 + selfBag.getBonusPages(), pageLimit);
                }
            }
            sender.sendMessage(TextUtil.color("&7已解锁页数: &e" + unlockedPages
                    + " / " + pageLimit + " &8(拓展器可继续解锁)"));
            // 体验券状态 (v1.6.0): 激活中显示生效组与剩余时间, 过期显示回退提示
            bagtest.model.PlayerBag infoBag = selfBag;
            if (infoBag == null) {
                infoBag = plugin.getBagManager().getBag(p.getUniqueId());
            }
            if (infoBag != null) {
                if (infoBag.isTicketActive()) {
                    // v1.6.2: 动态解析当前体验组 (基础组上一级)
                    Group tg = cfg.getNextGroup(group);
                    if (tg != null) {
                        sender.sendMessage(TextUtil.color(cfg.getMsg("ticket-info-line")
                                .replace("{group}", tg.name)
                                .replace("{time}", ConfigManager.formatRemain(
                                        infoBag.getTicketExpire() - System.currentTimeMillis()))));
                    }
                } else if (infoBag.hasTicketRecord()) {
                    sender.sendMessage(TextUtil.color("&7体验权限: &c已过期 "
                            + "&7(曾体验 &e" + infoBag.getTicketGroup() + "&7)"));
                }
                // 拓展器获得进度 (v1.6.0: 累计获得上限)
                int limit = cfg.getExpanderGainLimit();
                if (limit > 0) {
                    sender.sendMessage(TextUtil.color("&7拓展器获得进度: &e"
                            + infoBag.getGainedExpanders() + " / " + limit));
                }
            }
        }
        sender.sendMessage(TextUtil.color("&7指令绑定分组数: &e"
                + plugin.getGroupManager().getBindingCount()));
        sender.sendMessage(TextUtil.color("&7已缓存仓库: &e" + plugin.getBagManager().getCacheCount()));
        sender.sendMessage(TextUtil.color("&7自动保存: &e"
                + (cfg.getAutoSaveMinutes() > 0 ? cfg.getAutoSaveMinutes() + " 分钟" : "关闭")));
        sender.sendMessage(TextUtil.color("&7配置热重载: &e"
                + (cfg.isAutoReload() ? "自动检测开启" : "关闭 (仅手动 /bag reload)")));
    }

    // ==================== 分组管理指令 (v1.1.0 新增) ====================

    /**
     * /bag group 指令组 - 玩家权限组绑定管理 (admin)
     *   /bag group add <玩家> <组名>   设置玩家权限组 (离线玩家也可)
     *   /bag group remove <玩家>       移除自定义分组, 恢复权限节点判定
     *   /bag group info <玩家>         查看玩家当前生效的组与来源
     *   /bag group list                列出全部权限组
     * 数据存于 plugins/ZHBO_plugin_BagTest_1.6.4/groups.yml, 判定优先于权限节点
     */
    private void handleGroup(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("no-permission"));
            return;
        }
        if (args.length < 2) {
            // 无子动作: 显示 group 用法
            sender.sendMessage(TextUtil.color(plugin.getConfigManager().getMsg("prefix")
                    + "&7===== &6分组管理 &7====="));
            sender.sendMessage(TextUtil.color("&e/bag group add <玩家> <组名> &7- 设置玩家权限组"));
            sender.sendMessage(TextUtil.color("&e/bag group remove <玩家> &7- 移除玩家自定义分组"));
            sender.sendMessage(TextUtil.color("&e/bag group info <玩家> &7- 查看玩家生效的组"));
            sender.sendMessage(TextUtil.color("&e/bag group list &7- 查看全部权限组"));
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "add":
                handleGroupAdd(sender, args);
                return;
            case "remove":
                handleGroupRemove(sender, args);
                return;
            case "info":
                handleGroupInfo(sender, args);
                return;
            case "list":
                handleGroupList(sender);
                return;
            default:
                // 未知子动作: 重新显示用法
                handleGroup(sender, new String[]{"group"});
        }
    }

    /** /bag group add <玩家> <组名> : 绑定玩家分组 */
    private void handleGroupAdd(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(TextUtil.color("&c用法: /bag group add <玩家> <组名>"));
            return;
        }
        String name = args[2];
        String groupName = args[3];

        // 组名校验 (提示可用组列表)
        ConfigManager cfg = plugin.getConfigManager();
        Group group = cfg.getGroupByName(groupName);
        if (group == null) {
            sender.sendMessage(cfg.getMsg("group-not-found")
                    .replace("{group}", groupName)
                    .replace("{groups}", cfg.getGroupNames()));
            return;
        }

        // 解析目标玩家 (在线精确匹配优先, 离线查用户缓存)
        Player online = Bukkit.getPlayerExact(name);
        UUID uuid;
        String realName;
        if (online != null) {
            uuid = online.getUniqueId();
            realName = online.getName();
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (!offline.hasPlayedBefore()) {
                sender.sendMessage(cfg.getMsg("player-not-found").replace("{player}", name));
                return;
            }
            uuid = offline.getUniqueId();
            realName = name;
        }

        // 绑定并立即落盘
        plugin.getGroupManager().bind(uuid, group.name);
        sender.sendMessage(cfg.getMsg("group-bound")
                .replace("{player}", realName)
                .replace("{group}", group.name)
                .replace("{pages}", String.valueOf(group.pages))
                .replace("{rows}", String.valueOf(group.rows)));
        // 提示重开界面生效 (已打开的界面不会热变化)
        if (online != null) {
            online.sendMessage(TextUtil.color(cfg.getMsg("prefix")
                    + "&a你的仓库已升级为 &e" + group.pages + " 页&a, 输入 &e/bag &a查看!"));
        }
    }

    /** /bag group remove <玩家> : 移除玩家分组绑定 */
    private void handleGroupRemove(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(TextUtil.color("&c用法: /bag group remove <玩家>"));
            return;
        }
        String name = args[2];
        ConfigManager cfg = plugin.getConfigManager();

        Player online = Bukkit.getPlayerExact(name);
        UUID uuid;
        if (online != null) {
            uuid = online.getUniqueId();
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (!offline.hasPlayedBefore()) {
                sender.sendMessage(cfg.getMsg("player-not-found").replace("{player}", name));
                return;
            }
            uuid = offline.getUniqueId();
        }

        if (plugin.getGroupManager().unbind(uuid)) {
            // 移除后立即查一次生效组 (供管理员确认结果)
            Group now = plugin.getGroupManager().getEffectiveGroup(uuid, online);
            sender.sendMessage(cfg.getMsg("group-unbound")
                    .replace("{player}", name)
                    .replace("{group}", now.name + " (" + now.pages + " 页)"));
        } else {
            sender.sendMessage(cfg.getMsg("group-not-bound").replace("{player}", name));
        }
    }

    /** /bag group info <玩家> : 查看玩家生效的组 */
    private void handleGroupInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(TextUtil.color("&c用法: /bag group info <玩家>"));
            return;
        }
        String name = args[2];
        ConfigManager cfg = plugin.getConfigManager();

        Player online = Bukkit.getPlayerExact(name);
        UUID uuid;
        if (online != null) {
            uuid = online.getUniqueId();
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (!offline.hasPlayedBefore()) {
                sender.sendMessage(cfg.getMsg("player-not-found").replace("{player}", name));
                return;
            }
            uuid = offline.getUniqueId();
        }

        GroupManager gm = plugin.getGroupManager();
        Group effective = gm.getEffectiveGroup(uuid, online);
        // 来源判定: 绑定 > 权限节点 > 默认组
        String binding = gm.getBinding(uuid);
        String source;
        if (binding != null && cfg.getGroupByName(binding) != null) {
            source = "指令绑定";
        } else if (online != null) {
            source = "权限节点";
        } else {
            source = "默认组";
        }
        sender.sendMessage(cfg.getMsg("group-info")
                .replace("{player}", name)
                .replace("{group}", effective.name)
                .replace("{pages}", String.valueOf(effective.pages))
                .replace("{rows}", String.valueOf(effective.rows))
                .replace("{source}", source));
    }

    /** /bag group list : 列出全部权限组 */
    private void handleGroupList(CommandSender sender) {
        ConfigManager cfg = plugin.getConfigManager();
        sender.sendMessage(cfg.getMsg("group-list-header")
                .replace("{count}", String.valueOf(cfg.getGroupCount())));
        for (Group group : cfg.getAllGroups()) {
            String perm = group.isDefault()
                    ? "默认组, 无需权限" : group.permission + " 或指令绑定";
            sender.sendMessage(cfg.getMsg("group-list-item")
                    .replace("{group}", group.name)
                    .replace("{pages}", String.valueOf(group.pages))
                    .replace("{rows}", String.valueOf(group.rows))
                    .replace("{perm}", perm));
        }
        // 附带当前绑定数量
        sender.sendMessage(TextUtil.color("&7当前指令绑定玩家数: &e"
                + plugin.getGroupManager().getBindingCount()));
    }

    // ==================== VIP 快捷分组指令 (v1.2.0 新增) ====================

    /**
     * /bag vip <玩家> <编号|组名|remove> - 简化版分组指令 (admin)
     *   /bag vip Steve 1       把 Steve 设为 vip1 组
     *   /bag vip Steve 2       把 Steve 设为 vip2 组
     *   /bag vip Steve svip    把 Steve 设为 svip 组
     *   /bag vip Steve remove  移除 Steve 的绑定 (恢复权限节点判定)
     * 编号 N 自动映射 "vipN" 组名; 也可直接输入任意组名。
     */
    private void handleVip(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("no-permission"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(TextUtil.color("&c用法: /bag vip <玩家> <编号|组名|remove>"));
            sender.sendMessage(TextUtil.color("&7示例: &e/bag vip Steve 1 &7= 设为 vip1 组"));
            return;
        }
        String name = args[1];
        String value = args[2];

        // ---- 解析目标玩家 (在线精确匹配优先, 离线查用户缓存) ----
        ConfigManager cfg = plugin.getConfigManager();
        Player online = Bukkit.getPlayerExact(name);
        UUID uuid;
        String realName;
        if (online != null) {
            uuid = online.getUniqueId();
            realName = online.getName();
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (!offline.hasPlayedBefore()) {
                sender.sendMessage(cfg.getMsg("player-not-found").replace("{player}", name));
                return;
            }
            uuid = offline.getUniqueId();
            realName = name;
        }

        // ---- 解析组参数: remove 解绑 / 数字 N -> vipN / 其他按组名 ----
        if (value.equalsIgnoreCase("remove") || value.equalsIgnoreCase("off")
                || value.equals("0")) {
            // 解除绑定
            if (plugin.getGroupManager().unbind(uuid)) {
                Group now = plugin.getGroupManager().getEffectiveGroup(uuid, online);
                sender.sendMessage(cfg.getMsg("group-unbound")
                        .replace("{player}", realName)
                        .replace("{group}", now.name + " (" + now.pages + " 页)"));
            } else {
                sender.sendMessage(cfg.getMsg("group-not-bound").replace("{player}", realName));
            }
            return;
        }

        String groupName = value;
        Integer num = tryParse(value);
        if (num != null) {
            groupName = "vip" + num; // 编号 N -> vipN 组名
        }
        Group group = cfg.getGroupByName(groupName);
        if (group == null) {
            sender.sendMessage(cfg.getMsg("group-not-found")
                    .replace("{group}", groupName)
                    .replace("{groups}", cfg.getGroupNames()));
            return;
        }

        // ---- 绑定并立即落盘 ----
        plugin.getGroupManager().bind(uuid, group.name);
        sender.sendMessage(cfg.getMsg("group-bound")
                .replace("{player}", realName)
                .replace("{group}", group.name)
                .replace("{pages}", String.valueOf(group.pages))
                .replace("{rows}", String.valueOf(group.rows)));
        // 在线玩家实时提示
        if (online != null) {
            online.sendMessage(TextUtil.color(cfg.getMsg("prefix")
                    + "&a你的仓库已升级为 &e" + group.pages + " 页&a, 输入 &e/bag &a查看!"));
        }
    }

    // ==================== 特殊物品给予指令 (v1.3.0 新增) ====================

    /**
     * /bag give <玩家> <unlocker|expander|ticket3|ticket7> [数量] - 给予特殊物品 (admin)
     *   /bag give Steve unlocker 5   给 Steve 5 个开锁器 (自动绑定 Steve)
     *   /bag give Steve expander     给 Steve 1 个拓展器 (受获得上限限制)
     *   /bag give Steve ticket3      给 Steve 1 张 3 天体验券 (自动绑定)
     *   /bag give Steve ticket7 2    给 Steve 2 张 7 天体验券
     * 物品按配置文件中的材质/名称/描述生成; v1.5.0 起自动附加:
     *   - 开锁器/体验券: "绑定: <目标玩家>" 行 (仅绑定者可用, 可用 bind-owner 关闭)
     *   - 全部物品: "来源: <发放者> <时间>" 行 (消耗时写入 itemlog.txt)
     * v1.6.0: 拓展器每人累计获得上限 gain-limit (默认 10), 超出自动截断/拒绝
     */
    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("no-permission"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.getConfigManager().getMsg("give-usage"));
            return;
        }
        ConfigManager cfg = plugin.getConfigManager();

        // ---- 目标玩家必须在线 (物品无法给离线玩家) ----
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(cfg.getMsg("give-player-offline").replace("{player}", args[1]));
            return;
        }

        // ---- 来源描述: 发放者名 + 时间 (v1.5.0, 记入物品 lore 与日志) ----
        String source = plugin.getItemLogManager().buildSource(sender.getName());

        // ---- 解析物品类型: unlocker(开锁器) / expander(拓展器) / ticket3/ticket7(体验券) ----
        String type = args[2];
        String normalized; // 统一类型标识
        if (type.equalsIgnoreCase("unlocker") || type.equalsIgnoreCase("开锁器")) {
            normalized = "unlocker";
        } else if (type.equalsIgnoreCase("expander") || type.equalsIgnoreCase("拓展器")) {
            normalized = "expander";
        } else if (type.equalsIgnoreCase("ticket3") || type.equalsIgnoreCase("体验券3天")
                || type.equalsIgnoreCase("体验券3")) {
            normalized = "ticket3";
        } else if (type.equalsIgnoreCase("ticket7") || type.equalsIgnoreCase("体验券7天")
                || type.equalsIgnoreCase("体验券7")) {
            normalized = "ticket7";
        } else {
            sender.sendMessage(cfg.getMsg("give-invalid-type").replace("{type}", type));
            return;
        }

        // ---- 拓展器获得上限校验 (v1.6.0): 累计获得计数, 消耗不减 ----
        int amount = 1;
        if (args.length >= 4) {
            Integer parsed = tryParse(args[3]);
            if (parsed != null && parsed >= 1) {
                amount = Math.min(64, parsed);
            }
        }
        boolean bindOwner = false; // 是否绑定领取者
        if ("expander".equals(normalized)) {
            int limit = cfg.getExpanderGainLimit();
            if (limit > 0) {
                bagtest.model.PlayerBag targetBag = plugin.getBagManager()
                        .getBag(target.getUniqueId());
                if (targetBag == null) {
                    // 数据未加载 (刚进服极端情况): 提示稍后再试
                    sender.sendMessage(cfg.getMsg("give-player-offline")
                            .replace("{player}", target.getName()));
                    return;
                }
                int current = targetBag.getGainedExpanders();
                if (current >= limit) {
                    // 已达上限: 全部拒绝
                    sender.sendMessage(cfg.getMsg("expander-gain-limit")
                            .replace("{player}", target.getName())
                            .replace("{limit}", String.valueOf(limit))
                            .replace("{current}", String.valueOf(current)));
                    return;
                }
                if (current + amount > limit) {
                    // 超出上限: 截断为剩余可发数
                    int actual = limit - current;
                    sender.sendMessage(cfg.getMsg("expander-gain-truncated")
                            .replace("{amount}", String.valueOf(actual))
                            .replace("{current}", String.valueOf(current))
                            .replace("{limit}", String.valueOf(limit)));
                    amount = actual;
                }
            }
        }

        // ---- 构造物品 (按类型选择构造器; v1.6.1: 仅拓展器绑定领取者) ----
        ItemStack item;
        String displayName;
        String typeLabel; // 日志/提示用中文名
        if ("unlocker".equals(normalized)) {
            // v1.6.1: 开锁器不绑定, 可自由交易
            bindOwner = cfg.isUnlockerBindOwner();
            item = cfg.buildUnlocker(1, bindOwner ? target.getName() : null, source);
            displayName = cfg.getUnlockerDisplayName();
            typeLabel = "开锁器";
        } else if ("expander".equals(normalized)) {
            // v1.6.1: 拓展器是唯一绑定的特殊物品 (限量道具防转移)
            bindOwner = cfg.isExpanderBindOwner();
            item = cfg.buildExpander(1, bindOwner ? target.getName() : null, source);
            displayName = cfg.getExpanderDisplayName();
            typeLabel = "拓展器";
        } else {
            // v1.6.1: 体验券不绑定, 可自由交易
            bindOwner = cfg.isTicketBindOwner(normalized);
            item = cfg.buildTicket(normalized, 1,
                    bindOwner ? target.getName() : null, source);
            displayName = cfg.getTicketDisplayName(normalized);
            typeLabel = "体验券" + cfg.getTicketDays(normalized) + "天";
        }
        item.setAmount(amount);

        // ---- 累计获得计数 (v1.6.0: 拓展器限量判定用, 发放后立即入档) ----
        if ("expander".equals(normalized)) {
            bagtest.model.PlayerBag targetBag = plugin.getBagManager()
                    .getBag(target.getUniqueId());
            if (targetBag != null) {
                targetBag.addGainedExpanders(amount);
                plugin.getBagManager().saveRealtime(target.getUniqueId());
            }
        }

        // ---- 给予物品 (背包满则掉落在玩家脚下, 不丢失) ----
        java.util.Map<Integer, ItemStack> leftover = target.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        // v1.6.3 防复制: give 后立即保存目标玩家数据, 防止异常关服回档后道具双份
        try {
            target.saveData();
        } catch (Exception ignored) {
            // 保存失败不影响 give 流程
        }

        // ---- 来源日志: 记录本次发放 (v1.5.0) ----
        plugin.getItemLogManager().record(sender.getName() + " 给予 " + target.getName()
                + " " + typeLabel + " x" + amount
                + " | 来源: " + source
                + (bindOwner ? " | 已绑定: " + target.getName() : ""));

        // 管理员回执 + 目标玩家的使用提示 (附绑定说明)
        sender.sendMessage(cfg.getMsg("give-success")
                .replace("{player}", target.getName())
                .replace("{amount}", String.valueOf(amount))
                .replace("{item}", displayName));
        target.sendMessage(cfg.getMsg("give-received")
                .replace("{amount}", String.valueOf(amount))
                .replace("{item}", displayName));
        if (bindOwner) {
            target.sendMessage(TextUtil.color(cfg.getMsg("prefix")
                    + "&7该" + typeLabel + "已绑定你本人, 其他玩家无法使用."));
        }
    }

    // ==================== 选项菜单 (配置驱动 + 动态页码按钮) ====================

    /**
     * 显示选项菜单:
     * - 页码按钮区: 按发送者权限组动态生成 (vip1 显示 2 个按钮, vip2 显示 5 个);
     * - 固定条目: 来自 config.yml 的 menu.entries (admin 条目仅管理员可见);
     * - 控制台: 纯文本 (无页码按钮, 控制台不能开界面)。
     */
    private void sendMenu(CommandSender sender) {
        if (!sender.hasPermission(PERM_USE)) {
            sender.sendMessage(plugin.getConfigManager().getMsg("no-permission"));
            return;
        }
        ConfigManager cfg = plugin.getConfigManager();
        String prefix = cfg.getMsg("prefix");
        boolean isAdmin = sender.hasPermission(PERM_ADMIN);

        sender.sendMessage(TextUtil.color(prefix + cfg.getMenuTitle()));

        // ---- 页码按钮区 (仅玩家, 按已解锁页数生成) ----
        if (sender instanceof Player) {
            Player player = (Player) sender;
            Group group = plugin.getGroupManager().getEffectiveGroup(player.getUniqueId(), player);
            bagtest.model.PlayerBag selfBag = plugin.getBagManager().getBag(player.getUniqueId());
            // 体验激活时: 生效组 = 基础组上一级 (v1.6.2 动态解析), 全部页面默认解锁
            boolean ticketActive = false;
            if (selfBag != null && selfBag.isTicketActive()) {
                Group ticketGroup = cfg.getNextGroup(group);
                if (ticketGroup != null) {
                    group = ticketGroup;
                    ticketActive = true;
                }
            }
            int pageLimit = Math.min(group.pages,
                    Math.max(cfg.getMaxTotalPages(), group.pages));
            // 已解锁页数: 体验期间全开; 平时 = 1 + 拓展器数量 (v1.6.1)
            int unlockedPages = pageLimit; // 未加载时按上限展示 (点击时再校验)
            if (selfBag != null && !ticketActive) {
                unlockedPages = Math.min(1 + selfBag.getBonusPages(), pageLimit);
            }
            try {
                // 整行一次发送: 标签 + 全部页码按钮 (每个按钮可独立点击)
                TextComponent line = new TextComponent(
                        TextComponent.fromLegacyText(prefix + cfg.getMenuPagesLabel()));
                for (int page = 1; page <= unlockedPages; page++) {
                    TextComponent btn = new TextComponent(
                            TextComponent.fromLegacyText(cfg.getMenuPageButton(page)));
                    btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/bag open " + page));
                    btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new ComponentBuilder(TextUtil.color(
                                    "&7点击打开: &e第" + page + "页")).create()));
                    line.addExtra(btn);
                    line.addExtra(new TextComponent(" "));
                }
                player.spigot().sendMessage(line);
            } catch (Throwable t) {
                // 个别服务端核心不支持聊天组件: 整行降级为纯文本
                plugin.getLogger().log(Level.WARNING,
                        "页码按钮组件发送失败, 已降级为纯文本: "
                                + t.getClass().getName() + ": " + t.getMessage());
                StringBuilder sb = new StringBuilder(prefix + cfg.getMenuPagesLabel());
                for (int page = 1; page <= group.pages; page++) {
                    sb.append(cfg.getMenuPageButton(page)).append(" ");
                }
                player.sendMessage(sb.toString());
            }
        }

        // ---- 固定条目 (admin 条目仅管理员可见) ----
        if (!(sender instanceof Player)) {
            // 控制台: 纯文本
            for (ConfigManager.MenuEntry entry : cfg.getMenuEntries()) {
                sender.sendMessage(TextUtil.color(prefix + entry.text));
            }
        } else {
            Player player = (Player) sender;
            for (ConfigManager.MenuEntry entry : cfg.getMenuEntries()) {
                if (entry.admin && !isAdmin) {
                    continue; // 权限过滤
                }
                String line = TextUtil.color(prefix + entry.text);
                if (!entry.isClickable()) {
                    player.sendMessage(line);
                    continue;
                }
                try {
                    TextComponent comp = new TextComponent(TextComponent.fromLegacyText(line));
                    comp.setClickEvent(new ClickEvent(
                            entry.suggest ? ClickEvent.Action.SUGGEST_COMMAND : ClickEvent.Action.RUN_COMMAND,
                            entry.command));
                    comp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new ComponentBuilder(TextUtil.color(
                                    entry.suggest ? "&7点击填入: &e" + entry.command
                                            : "&7点击执行: &e" + entry.command)).create()));
                    player.spigot().sendMessage(comp);
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING,
                            "菜单组件发送失败, 已降级为纯文本: "
                                    + t.getClass().getName() + ": " + t.getMessage());
                    player.sendMessage(line);
                }
            }
        }
        sender.sendMessage(TextUtil.color(prefix + cfg.getMenuHint()));
    }

    /** 打开冷却检查, 冷却中则发送提示并返回 false */
    private boolean checkCooldown(Player player) {
        long remain = plugin.getBagManager().checkOpenCooldown(player.getUniqueId());
        if (remain > 0) {
            player.sendMessage(plugin.getConfigManager().getMsg("cooldown")
                    .replace("{seconds}", String.valueOf(remain)));
            plugin.getConfigManager().playSound(player, "fail"); // 失败音效 (v1.5.0)
            return false;
        }
        return true;
    }

    /** 安全解析正整数 (非法返回 null) */
    private static Integer tryParse(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            int v = Integer.parseInt(s);
            return v >= 1 ? v : null; // 页码从 1 开始, 0/负数视为非法
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== Tab 补全 ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            // 一级子指令 + 自己的可用页码
            String input = args[0].toLowerCase();
            for (String s : new String[]{"open", "info", "help"}) {
                if (s.startsWith(input)) {
                    result.add(s);
                }
            }
            if (sender.hasPermission(PERM_ADMIN)) {
                for (String s : new String[]{"vip", "give", "group", "reload", "save"}) {
                    if (s.startsWith(input)) {
                        result.add(s);
                    }
                }
            }
            // 页码补全 (/bag <页码>, 按已解锁页数: 1 基础 + 拓展数)
            if (sender instanceof Player) {
                Player p = (Player) sender;
                Group group = plugin.getGroupManager().getEffectiveGroup(p.getUniqueId(), p);
                int pageLimit = Math.min(group.pages,
                        Math.max(plugin.getConfigManager().getMaxTotalPages(), group.pages));
                int max = pageLimit;
                bagtest.model.PlayerBag bag = plugin.getBagManager().getBag(p.getUniqueId());
                if (bag != null) {
                    max = Math.min(1 + bag.getBonusPages(), pageLimit);
                }
                for (int i = 1; i <= max; i++) {
                    String ps = String.valueOf(i);
                    if (ps.startsWith(input)) {
                        result.add(ps);
                    }
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            // /bag open <页码 或 玩家名>
            String input = args[1].toLowerCase();
            if (sender instanceof Player) {
                // 自己的可用页码 (按已解锁页数: 1 基础 + 拓展数)
                Player p = (Player) sender;
                Group group = plugin.getGroupManager().getEffectiveGroup(p.getUniqueId(), p);
                int pageLimit = Math.min(group.pages,
                        Math.max(plugin.getConfigManager().getMaxTotalPages(), group.pages));
                int max = pageLimit;
                bagtest.model.PlayerBag bag = plugin.getBagManager().getBag(p.getUniqueId());
                if (bag != null) {
                    max = Math.min(1 + bag.getBonusPages(), pageLimit);
                }
                for (int i = 1; i <= max; i++) {
                    String ps = String.valueOf(i);
                    if (ps.startsWith(input)) {
                        result.add(ps);
                    }
                }
            }
            // 管理员: 在线玩家名
            if (sender.hasPermission(PERM_ADMIN)) {
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    if (pl.getName().toLowerCase().startsWith(input)) {
                        result.add(pl.getName());
                    }
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("open")
                && sender.hasPermission(PERM_ADMIN)) {
            // /bag open <玩家> <页码>: 目标玩家的可用页码 (在线按其生效组, 离线按最大组)
            String input = args[2].toLowerCase();
            Player target = Bukkit.getPlayerExact(args[1]);
            Group group = (target != null)
                    ? plugin.getGroupManager().getEffectiveGroup(target.getUniqueId(), target)
                    : plugin.getConfigManager().getMaxGroup();
            for (int i = 1; i <= group.pages; i++) {
                String ps = String.valueOf(i);
                if (ps.startsWith(input)) {
                    result.add(ps);
                }
            }
        } else if (args[0].equalsIgnoreCase("give") && sender.hasPermission(PERM_ADMIN)) {
            // /bag give <玩家> <unlocker|expander> [数量] 补全 (v1.3.0)
            if (args.length == 2) {
                String input = args[1].toLowerCase();
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    if (pl.getName().toLowerCase().startsWith(input)) {
                        result.add(pl.getName());
                    }
                }
            } else if (args.length == 3) {
                String input = args[2].toLowerCase();
                for (String s : new String[]{"unlocker", "expander", "ticket3", "ticket7",
                        "开锁器", "拓展器", "体验券3天", "体验券7天"}) {
                    if (s.toLowerCase().startsWith(input)) {
                        result.add(s);
                    }
                }
            } else if (args.length == 4) {
                String input = args[3];
                for (String s : new String[]{"1", "5", "10", "64"}) {
                    if (s.startsWith(input)) {
                        result.add(s);
                    }
                }
            }
        } else if (args[0].equalsIgnoreCase("vip") && sender.hasPermission(PERM_ADMIN)) {
            // /bag vip <玩家> <编号|组名|remove> 补全 (v1.2.0)
            if (args.length == 2) {
                // 玩家名: 在线玩家 + 已绑定离线玩家
                String input = args[1].toLowerCase();
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    if (pl.getName().toLowerCase().startsWith(input)) {
                        result.add(pl.getName());
                    }
                }
                for (java.util.UUID uuid : plugin.getGroupManager().getBindings().keySet()) {
                    String n = plugin.getGroupManager().nameOf(uuid);
                    if (n.toLowerCase().startsWith(input)) {
                        result.add(n);
                    }
                }
            } else if (args.length == 3) {
                // 组参数: remove + vip 编号 + 全部组名
                String input = args[2].toLowerCase();
                if ("remove".startsWith(input)) {
                    result.add("remove");
                }
                for (Group group : plugin.getConfigManager().getAllGroups()) {
                    String g = group.name.toLowerCase();
                    if (g.startsWith(input)) {
                        result.add(group.name);
                    }
                    // vipN 组额外补全纯编号 (如 vip1 -> "1")
                    if (g.startsWith("vip") && g.length() > 3) {
                        String num = g.substring(3);
                        if (input.isEmpty() || num.startsWith(input)) {
                            result.add(num);
                        }
                    }
                }
            }
        } else if (args[0].equalsIgnoreCase("group") && sender.hasPermission(PERM_ADMIN)) {
            // /bag group 子指令补全 (v1.1.0)
            if (args.length == 2) {
                String input = args[1].toLowerCase();
                for (String s : new String[]{"add", "remove", "info", "list"}) {
                    if (s.startsWith(input)) {
                        result.add(s);
                    }
                }
            } else if (args.length == 3
                    && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove")
                        || args[1].equalsIgnoreCase("info"))) {
                // 玩家名补全: 在线玩家 + 已绑定的离线玩家
                String input = args[2].toLowerCase();
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    if (pl.getName().toLowerCase().startsWith(input)) {
                        result.add(pl.getName());
                    }
                }
                for (java.util.UUID uuid : plugin.getGroupManager().getBindings().keySet()) {
                    String name = plugin.getGroupManager().nameOf(uuid);
                    if (name.toLowerCase().startsWith(input)) {
                        result.add(name);
                    }
                }
            } else if (args.length == 4 && args[1].equalsIgnoreCase("add")) {
                // 组名补全: 全部权限组
                String input = args[3].toLowerCase();
                for (Group group : plugin.getConfigManager().getAllGroups()) {
                    if (group.name.toLowerCase().startsWith(input)) {
                        result.add(group.name);
                    }
                }
            }
        }
        return result;
    }
}
