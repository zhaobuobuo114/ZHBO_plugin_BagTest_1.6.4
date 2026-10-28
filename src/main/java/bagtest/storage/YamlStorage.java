package bagtest.storage;

import bagtest.config.ConfigManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 【模块四：存储层】
 * YAML 文件存储实现 - 每位玩家一个独立文件 playerdata/<uuid>.yml
 *
 * 优点: 零依赖、易备份、肉眼可查; 适合中小型服务器
 */
public class YamlStorage implements Storage {

    private final Logger logger;
    private final File dataFolder;

    public YamlStorage(ConfigManager config, Logger logger) {
        this.logger = logger;
        this.dataFolder = new File(config.getPluginDataFolder(), config.getYamlDataFolder());
    }

    @Override
    public void init() throws Exception {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("无法创建数据目录: " + dataFolder.getAbsolutePath());
        }
    }

    @Override
    public void close() {
        // 文件存储无需释放资源
    }

    @Override
    public String loadRaw(UUID uuid) {
        File file = fileOf(uuid);
        if (!file.exists()) {
            return null; // 新玩家, 无历史数据
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log(Level.WARNING, "读取玩家数据文件失败: " + file.getName(), e);
            return null;
        }
    }

    @Override
    public void saveRaw(UUID uuid, String lastName, String yamlData) {
        File file = fileOf(uuid);
        // 先写临时文件再原子重命名, 避免写入中途断电/崩溃导致数据文件损坏
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            Files.write(tmp.toPath(), yamlData.getBytes(StandardCharsets.UTF_8));
            if (file.exists() && !file.delete()) {
                logger.warning("旧数据文件删除失败, 尝试直接覆盖: " + file.getName());
            }
            if (!tmp.renameTo(file)) {
                // 个别系统 rename 失败时回退为直接写入
                Files.write(file.toPath(), yamlData.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "保存玩家数据文件失败: " + file.getName(), e);
        }
    }

    private File fileOf(UUID uuid) {
        return new File(dataFolder, uuid.toString() + ".yml");
    }

    @Override
    public String getTypeName() {
        return "YAML";
    }
}
