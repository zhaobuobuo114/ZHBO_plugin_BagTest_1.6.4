package bagtest.storage;

import bagtest.config.ConfigManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 【模块四：存储层】
 * MySQL 数据库存储实现
 *
 * 说明:
 * 1. 1.12.2 服务端自带 MySQL 驱动 (mysql-connector-java 5.x), 通过反射加载,
 *    编译期不依赖驱动, 也无需 shade 打包。
 * 2. 使用单一共享连接 + synchronized 保护, 对单服仓库插件而言足够稳定;
 *    连接断开时自动重连。
 * 3. 表结构: 前缀+player (uuid 主键, name 记录名, data 存 YAML 字符串)。
 */
public class MySqlStorage implements Storage {

    private final Logger logger;
    private final ConfigManager config;

    private Connection connection;
    private String table;

    public MySqlStorage(ConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    @Override
    public synchronized void init() throws Exception {
        loadDriver();
        this.table = config.getMysqlTablePrefix() + "player";
        openConnection();
        createTable();
    }

    /** 反射加载数据库驱动 (兼容 5.x 与 8.x 驱动类名) */
    private void loadDriver() throws Exception {
        String[] drivers = {"com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"};
        for (String name : drivers) {
            try {
                Class.forName(name);
                return;
            } catch (ClassNotFoundException ignored) {
                // 尝试下一个驱动类名
            }
        }
        throw new Exception("未找到 MySQL 驱动, 请确认服务端 libs 中包含 mysql-connector");
    }

    private void openConnection() throws SQLException {
        String url = "jdbc:mysql://" + config.getMysqlHost() + ":" + config.getMysqlPort()
                + "/" + config.getMysqlDatabase()
                + "?useUnicode=true&characterEncoding=utf8"
                + "&useSSL=" + config.isMysqlUseSsl()
                + "&connectTimeout=5000&socketTimeout=30000";
        this.connection = DriverManager.getConnection(url,
                config.getMysqlUsername(), config.getMysqlPassword());
    }

    private void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS `" + table + "` ("
                + "`uuid` VARCHAR(36) NOT NULL, "
                + "`name` VARCHAR(16) NOT NULL DEFAULT '', "
                + "`data` LONGTEXT, "
                + "`updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (`uuid`)) DEFAULT CHARSET=utf8";
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    /** 确保连接可用, 断线自动重连 (最多尝试一次) */
    private synchronized void ensureConnection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException ignored) {
            }
            openConnection();
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.warning("关闭 MySQL 连接时出错: " + e.getMessage());
        }
    }

    @Override
    public synchronized String loadRaw(UUID uuid) {
        final String sql = "SELECT `data` FROM `" + table + "` WHERE `uuid` = ?";
        try {
            ensureConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("data");
                    }
                    return null; // 无数据
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "MySQL 读取玩家数据失败 (" + uuid + "): " + e.getMessage());
            return null;
        }
    }

    @Override
    public synchronized void saveRaw(UUID uuid, String lastName, String yamlData) {
        // REPLACE INTO: 主键存在则替换, 不存在则插入, 幂等且简单可靠
        final String sql = "REPLACE INTO `" + table + "` (`uuid`, `name`, `data`) VALUES (?, ?, ?)";
        try {
            ensureConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, lastName == null ? "" : lastName);
                ps.setString(3, yamlData);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "MySQL 保存玩家数据失败 (" + uuid + "): " + e.getMessage());
        }
    }

    @Override
    public String getTypeName() {
        return "MYSQL";
    }
}
