package bagtest.listener;

import bagtest.BagPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * 【模块七：GUI 层】(玩家生命周期监听)
 * 进服预加载 / 退服保存并卸载缓存
 */
public class PlayerListener implements Listener {

    private final BagPlugin plugin;

    public PlayerListener(BagPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        // 异步预加载: 玩家进服时立刻把仓库读入缓存, 打开指令零等待
        // requireOnline=true: 加载完成时若玩家已退出则丢弃, 防止缓存泄漏
        plugin.getBagManager().loadAsync(uuid, name, true, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // 退出: 卸载缓存 (内部先异步保存再移除), 同时清理打开冷却记录防止内存泄漏
        plugin.getBagManager().unload(uuid);
        plugin.getBagManager().clearCooldown(uuid);
    }
}
