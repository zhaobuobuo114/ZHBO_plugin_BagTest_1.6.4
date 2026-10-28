package bagtest.storage;

import java.util.UUID;

/**
 * 【模块四：存储层】
 * 存储抽象接口 - 屏蔽 YAML 与 MySQL 的差异
 *
 * 统一约定: 存储层只负责"YAML 格式字符串"的读写, 不接触 ItemStack,
 * 物品序列化全部由上层 (BagManager) 在主线程完成, 保证线程安全。
 */
public interface Storage {

    /**
     * 初始化存储 (创建目录 / 建表 / 建立连接)
     *
     * @throws Exception 初始化失败 (连接不上/无权限等), 由上层决定禁用或回退
     */
    void init() throws Exception;

    /** 关闭存储 (释放连接等), 插件卸载/切换存储时调用 */
    void close();

    /**
     * 读取玩家仓库数据 (可在异步线程调用)
     *
     * @param uuid 玩家 UUID
     * @return YAML 格式字符串; 无数据返回 null; 读取出错返回 null 并记录日志
     */
    String loadRaw(UUID uuid);

    /**
     * 保存玩家仓库数据 (可在异步线程调用)
     *
     * @param uuid     玩家 UUID
     * @param lastName 玩家最近名字 (便于人工排查数据)
     * @param yamlData YAML 格式字符串 (由 BagManager 在主线程序列化完成)
     */
    void saveRaw(UUID uuid, String lastName, String yamlData);

    /** 存储类型名称 (展示用, 如 YAML / MYSQL) */
    String getTypeName();
}
