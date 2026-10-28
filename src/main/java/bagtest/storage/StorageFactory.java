package bagtest.storage;

import bagtest.config.ConfigManager;

import java.util.logging.Logger;

/**
 * 【模块四：存储层】
 * 存储工厂 - 根据配置创建对应存储实现, 上层不感知具体类型
 */
public final class StorageFactory {

    private StorageFactory() {
    }

    /**
     * 依据配置的 storage.type 创建存储实例
     * (大小写不敏感; 未识别的类型回退 YAML, 保证插件总能启动)
     */
    public static Storage create(ConfigManager config, Logger logger) {
        String type = config.getStorageType() == null ? "" : config.getStorageType().trim().toUpperCase();
        if ("MYSQL".equals(type) || "DATABASE".equals(type)) {
            return new MySqlStorage(config, logger);
        }
        return new YamlStorage(config, logger);
    }
}
