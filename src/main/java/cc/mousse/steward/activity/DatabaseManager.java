package cc.mousse.steward.activity;

import com.alibaba.druid.pool.DruidDataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author MochaMousse
 */
public class DatabaseManager {
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
  private static final String QUERY_DETAILED_SESSION_DATA_SQL =
"""
SELECT uuid, username, duration_milliseconds, initial_server_name, login_timestamp
FROM player_sessions
WHERE login_timestamp BETWEEN ? AND ?
  AND duration_milliseconds IS NOT NULL
  AND duration_milliseconds > 0;
""";
  private final Main plugin;
  private final DruidDataSource dataSource;

  public DatabaseManager(Main plugin, PluginConfig.DatabaseConfig dbConfig) throws SQLException {
    this.plugin = plugin;
    dataSource = new DruidDataSource();
    // 从配置对象构建JDBC URL
    String jdbcUrl =
        String.format(
            "jdbc:mysql://%s:%d/%s%s",
            dbConfig.getHost(),
            dbConfig.getPort(),
            dbConfig.getDatabase(),
            dbConfig.getExtraParams());
    dataSource.setUrl(jdbcUrl);
    dataSource.setUsername(dbConfig.getUsername());
    dataSource.setPassword(dbConfig.getPassword());
    dataSource.init();
  }

  public boolean initialize() {
    String createTableSql =
        """
        CREATE TABLE IF NOT EXISTS `player_sessions`
        (
            /* ... 字段定义保持不变 ... */
            `id`                    BIGINT PRIMARY KEY AUTO_INCREMENT,
            `uuid`                  VARCHAR(36) NOT NULL COMMENT '玩家UUID',
            `username`              VARCHAR(16) NOT NULL COMMENT '玩家当次会话的用户名',
            `ip_address`            VARCHAR(45) NOT NULL COMMENT '玩家IP地址 (兼容IPv6)',
            `login_timestamp`       DATETIME(3) NOT NULL COMMENT '登录时间 (UTC), 精确到毫秒',
            `logout_timestamp`      DATETIME(3)  DEFAULT NULL COMMENT '登出时间 (UTC), 精确到毫-秒',
            `duration_milliseconds` BIGINT       DEFAULT NULL COMMENT '在线时长 (毫秒)',
            `initial_server_name`   VARCHAR(255) DEFAULT NULL COMMENT '玩家首次进入的后端服务器名',
            `protocol_version`      INT          DEFAULT NULL COMMENT '玩家客户端的协议版本号',
            `proxy_id`              VARCHAR(255) DEFAULT NULL COMMENT '记录日志的代理服务器ID',
            `disconnect_reason`     TEXT         DEFAULT NULL COMMENT '玩家下线的原因'
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
        """;

    String[] createIndexSqls = {
      "CREATE INDEX `idx_sessions_uuid` ON `player_sessions` (`uuid`)",
      "CREATE INDEX `idx_sessions_login_timestamp` ON `player_sessions` (`login_timestamp`)",
      "CREATE INDEX `idx_sessions_ip_address` ON `player_sessions` (`ip_address`)"
    };

    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      // 先创建表
      stmt.execute(createTableSql);
      // 逐个创建索引，调用新的辅助方法
      for (String indexSql : createIndexSqls) {
        createIndexIfNotExists(stmt, indexSql);
      }
      return true;
    } catch (SQLException e) {
      plugin.getLogger().error("数据库初始化失败！", e);
      return false;
    }
  }

  private void createIndexIfNotExists(Statement stmt, String indexSql) throws SQLException {
    try {
      stmt.execute(indexSql);
      // 提取索引名用于日志
      plugin.getLogger().info("索引创建成功: {}", indexSql);
    } catch (SQLException e) {
      // MySQL错误码1061表示"Duplicate key name" (索引已存在)
      if (e.getErrorCode() == 1061) {
        // 这是一个正常情况，表示索引已经就位，我们只需记录一下即可
        plugin.getLogger().info("索引已存在: {}", indexSql);
      } else {
        // 如果是其他未知错误则是一个严重问题, 需要向上抛出
        plugin.getLogger().error("创建索引时发生未知错误: {}", indexSql);
        throw e;
      }
    }
  }

  public Connection getConnection() throws SQLException {
    return dataSource.getConnection();
  }

  public void close() {
    if (dataSource != null && !dataSource.isClosed()) {
      dataSource.close();
      plugin.getLogger().info("数据库连接池已关闭");
    }
  }

  /**
   * 异步记录玩家登录时的详细信息并返回该记录在数据库中的主键ID
   *
   * @param uuid 玩家的UUID
   * @param username 玩家当前的用户名
   * @param ipAddress 玩家的IP地址
   * @param loginTimestampMs 登录时的UTC毫秒时间戳
   * @param serverId 玩家首次进入的后端服务器ID
   * @param protocolVersion 玩家客户端的协议版本号
   * @param proxyId 当前Velocity代理实例的ID
   * @return CompletableFuture<Long> 包含新生成记录ID的Future (操作成功:返回记录ID；失败:返回-1L)
   */
  public CompletableFuture<Long> logPlayerLoginAndGetIdAsync(
      String uuid,
      String username,
      String ipAddress,
      long loginTimestampMs,
      String serverId,
      int protocolVersion,
      String proxyId) {
    return CompletableFuture.supplyAsync(
        () -> {
          String sql =
              "INSERT INTO player_sessions (uuid, username, ip_address, login_timestamp, initial_server_name, protocol_version, proxy_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
          try (Connection conn = getConnection();
              PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            // 依次设置SQL语句中的参数
            ps.setString(1, uuid);
            ps.setString(2, username);
            ps.setString(3, ipAddress);
            // 将Java的毫秒时间戳转换为JDBC的Timestamp对象，以正确写入DATETIME(3)字段
            ps.setTimestamp(4, new Timestamp(loginTimestampMs));
            ps.setString(5, serverId);
            ps.setInt(6, protocolVersion);
            ps.setString(7, proxyId);
            // 执行插入操作
            int affectedRows = ps.executeUpdate();
            // 检查是否成功插入了数据
            if (affectedRows > 0) {
              // 获取数据库自动生成的主键 (AUTO_INCREMENT的ID)
              try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                  // 返回获取到的主键ID
                  return rs.getLong(1);
                }
              }
            }
          } catch (SQLException e) {
            // 打印详细的错误日志
            plugin.getLogger().error("记录玩家'{}'的登录信息时数据库出错。", username, e);
          }
          // 如果中间任何环节出错都返回-1L
          return -1L;
        });
  }

  /**
   * 异步更新玩家的登出信息
   *
   * @param recordId 要更新的会话记录在数据库中的主键ID
   * @param logoutTimestampMs 登出时的UTC毫秒时间戳
   * @param durationMs 这次会话的总持续时长 (毫秒)
   * @param reason 离线原因
   */
  public void updatePlayerLogoutAsync(
      long recordId, long logoutTimestampMs, long durationMs, String reason) {
    // 使用 Velocity 的调度器来在独立的线程中异步执行数据库操作
    plugin
        .getServer()
        .getScheduler()
        .buildTask(
            plugin,
            () -> {
              String sql =
                  "UPDATE player_sessions SET logout_timestamp = ?, duration_milliseconds = ?, disconnect_reason = ? WHERE id = ?";
              try (Connection conn = getConnection();
                  PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setTimestamp(1, new Timestamp(logoutTimestampMs));
                ps.setLong(2, durationMs);
                ps.setString(3, reason);
                ps.setLong(4, recordId);
                ps.executeUpdate();
              } catch (SQLException e) {
                // 如果发生任何SQL异常，打印详细的错误日志
                plugin.getLogger().error("更新ID为'{}'的玩家登出信息时数据库出错", recordId, e);
              }
            })
        .schedule();
  }

  /**
   * 异步查询指定时间范围内的所有详细玩家会话数据
   *
   * @param start 时间范围的开始 (UTC)
   * @param end 时间范围的结束 (UTC)
   * @return 包含所有会话原始数据的 Future List
   */
  public CompletableFuture<List<PlayerSessionData>> queryDetailedSessionDataAsync(
      LocalDateTime start, LocalDateTime end) {
    return CompletableFuture.supplyAsync(
        () -> {
          List<PlayerSessionData> results = new ArrayList<>();
          // 选择需要的字段并过滤掉时长为空或为0的无效会话
          try (Connection conn = getConnection();
              PreparedStatement ps = conn.prepareStatement(QUERY_DETAILED_SESSION_DATA_SQL)) {
            ps.setString(1, start.format(DATE_TIME_FORMATTER));
            ps.setString(2, end.format(DATE_TIME_FORMATTER));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                results.add(
                    new PlayerSessionData(
                        rs.getString("uuid"),
                        rs.getString("username"),
                        rs.getLong("duration_milliseconds"),
                        rs.getString("initial_server_name"),
                        rs.getTimestamp("login_timestamp").toLocalDateTime()));
              }
            }
          } catch (SQLException e) {
            plugin.getLogger().error("查询详细会话数据时出错", e);
            return Collections.emptyList();
          }
          return results;
        });
  }

  // 用于承载从数据库查询出的单条会话原始数据
  public record PlayerSessionData(
      String uuid,
      String username,
      Long durationMilliseconds,
      String serverName,
      LocalDateTime loginTimestamp) {}
}
