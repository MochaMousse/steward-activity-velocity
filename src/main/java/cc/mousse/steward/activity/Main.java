package cc.mousse.steward.activity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import org.slf4j.Logger;

/**
 * @author MochaMousse
 */
@Getter
@Plugin(id = "steward-activity-velocity", name = "steward-activity-velocity", version = "2025.8.18")
public class Main {
  public static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

  @Inject
  @SuppressWarnings("unused")
  private Logger logger;

  @Inject
  @SuppressWarnings("unused")
  private ProxyServer server;

  @Inject
  @DataDirectory
  @SuppressWarnings("unused")
  private Path dataDirectory;

  private PluginConfig config;
  private ConfigManager configManager;
  private ReportManager reportManager;
  private DatabaseManager databaseManager;
  private ScheduledTask dailyReportTask;
  private ScheduledTask monthlyReportTask;
  private ScheduledTask serverStatusTask;
  private ServerStatusMonitor serverStatusMonitor;
  private PlayerLoginListener playerLoginListener;

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    // 确保插件数据目录存在
    if (!dataDirectory.toFile().exists()) {
      boolean pathExisting = dataDirectory.toFile().mkdirs();
      if (!pathExisting) {
        logger.error("配置文件目录初始化失败");
        return;
      }
    }
    // 加载配置文件
    logger.info("加载配置文件");
    configManager = new ConfigManager(this);
    configManager.load();
    // 获取并持有配置对象的引用
    config = configManager.get();
    // 初始化数据库管理器
    logger.info("初始化数据库连接池");
    try {
      databaseManager = new DatabaseManager(this, config.getDatabase());
    } catch (SQLException e) {
      logger.error("初始化数据库连接池失败");
      cleanupAndAbort();
      return;
    }
    if (!databaseManager.initialize()) {
      logger.error("数据库初始化失败");
      cleanupAndAbort();
      return;
    }
    // 初始化报告管理器
    logger.info("初始化报告管理器");
    reportManager = new ReportManager(this);
    serverStatusMonitor = new ServerStatusMonitor(this);
    playerLoginListener =
        new PlayerLoginListener(
            this, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    // 启动定时任务
    scheduleTasks();
    // 注册事件监听器
    logger.info("注册事件监听器");
    server.getEventManager().register(this, playerLoginListener);
    // 注册命令
    logger.info("注册命令");
    // 获取CommandManager
    CommandManager commandManager = server.getCommandManager();
    // 构建命令的元数据 (Metadata)
    CommandMeta commandMeta =
        commandManager
            .metaBuilder("activity")
            // 将命令与本插件关联
            .plugin(this)
            .build();
    // 创建命令的处理器实例
    CommandHandler commandHandler = new CommandHandler(this);
    // 注册
    commandManager.register(commandMeta, commandHandler.createBrigadierCommand());
    logger.info("插件已加载");
    reportManager.sendAdminNotification("[activity]::已就绪");
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    // 在插件关闭时也取消任务
    cancelScheduledTasks();
    logger.info("插件已卸载");
    reportManager.sendAdminNotification("[activity]::已关闭");
  }

  public void reload() {
    logger.info("开始重载插件");
    // 1. 【新增】在销毁旧实例前，保存关键的会话缓存
    ConcurrentMap<UUID, Long> preservedSessionIds = new ConcurrentHashMap<>();
    ConcurrentMap<UUID, Instant> preservedLoginTimestamps = new ConcurrentHashMap<>();
    ConcurrentMap<UUID, RegisteredServer> preservedLastServers = new ConcurrentHashMap<>();
    if (this.playerLoginListener != null) {
      preservedSessionIds = this.playerLoginListener.getActiveSessionIds();
      preservedLoginTimestamps = this.playerLoginListener.getLoginTimestamps();
      preservedLastServers = this.playerLoginListener.getLastKnownServerCache();
      logger.info("已保留 {} 个在线玩家的会话状态。", preservedSessionIds.size());
    }
    // 取消旧的定时任务和监听器
    cancelScheduledTasks();
    server.getEventManager().unregisterListeners(this);
    // 重新加载配置
    configManager.load();
    config = configManager.get();
    logger.info("配置文件已重新加载");
    // 按照正确的顺序重新创建所有实例
    reportManager = new ReportManager(this);
    serverStatusMonitor = new ServerStatusMonitor(this);
    playerLoginListener =
        new PlayerLoginListener(
            this, preservedSessionIds, preservedLoginTimestamps, preservedLastServers);
    // 重新调度任务和注册监听器
    logger.info("正在重新调度任务和注册监听器");
    scheduleTasks();
    server.getEventManager().register(this, playerLoginListener);
    logger.info("插件重载完成！");
    reportManager.sendAdminNotification("[activity]::已重载");
  }

  /** 封装的任务调度方法 */
  private void scheduleTasks() {
    // 将ReportManager中的调度逻辑移到这里并保存任务引用
    LocalTime dailyReportTime = LocalTime.of(0, 5);
    dailyReportTask =
        server
            .getScheduler()
            .buildTask(this, () -> reportManager.generateAndDispatchDailyReport())
            .delay(reportManager.calculateDelayUntil(dailyReportTime))
            .repeat(24, TimeUnit.HOURS)
            .schedule();
    LocalTime monthlyCheckTime = LocalTime.of(0, 10);
    monthlyReportTask =
        server
            .getScheduler()
            .buildTask(
                this,
                () -> {
                  if (LocalDateTime.now(Main.SHANGHAI_ZONE).toLocalDate().getDayOfMonth() == 1) {
                    reportManager.generateAndDispatchMonthlyReport();
                  }
                })
            .delay(reportManager.calculateDelayUntil(monthlyCheckTime))
            .repeat(24, TimeUnit.HOURS)
            .schedule();
    logger.info("日报和月报任务已调度");
    // 初始化所有服务器的初始状态
    serverStatusTask =
        server
            .getScheduler()
            .buildTask(this, scheduledTask -> serverStatusMonitor.pollServerStatus())
            .repeat(30, TimeUnit.SECONDS)
            .delay(10, TimeUnit.SECONDS)
            .schedule();
    logger.info("后端服务器状态任务已调度");
  }

  /** 取消所有已调度的任务 */
  private void cancelScheduledTasks() {
    if (dailyReportTask != null) {
      dailyReportTask.cancel();
    }
    if (monthlyReportTask != null) {
      monthlyReportTask.cancel();
    }
    if (serverStatusTask != null) {
      serverStatusTask.cancel();
    }
  }

  private void cleanupAndAbort() {
    // 注销所有已经在这个插件实例中注册的事件监听器
    logger.warn("注销所有事件监听器");
    server.getEventManager().unregisterListeners(this);
    // 关闭已经打开的资源
    if (databaseManager != null) {
      logger.warn("关闭数据库连接");
      databaseManager.close();
    }
  }
}
