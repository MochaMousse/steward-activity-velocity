package cc.mousse.steward.activity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.Scheduler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;

/**
 * @author MochaMousse
 */
public class ServerStatusMonitor {
  public enum ServerStatus {
    ONLINE,
    OFFLINE,
    UNKNOWN
  }

  private final Main plugin;
  private final Logger logger;
  private final ProxyServer server;
  private final Scheduler scheduler;
  private final ReportManager reportManager;
  private final Map<String, String> serverNameMappings;

  // 使用ConcurrentHashMap来安全地在主线程和异步线程间共享状态
  private final ConcurrentMap<String, ServerStatus> serverStatusCache = new ConcurrentHashMap<>();

  public ServerStatusMonitor(Main plugin) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
    this.server = plugin.getServer();
    this.scheduler = plugin.getServer().getScheduler();
    this.reportManager = plugin.getReportManager();
    this.serverNameMappings = plugin.getConfig().getGoCqhttp().getServerNameMappings();
    initializeCache();
  }

  private void initializeCache() {
    server
        .getAllServers()
        .forEach(
            s ->
                serverStatusCache.put(
                    s.getServerInfo().getName(), ServerStatusMonitor.ServerStatus.UNKNOWN));
  }

  /** 执行轮询检查 */
  public void pollServerStatus() {
    for (RegisteredServer s : this.server.getAllServers()) {
      final String serverName = s.getServerInfo().getName();
      // 异步ping服务器
      s.ping()
          .whenComplete(
              (ping, throwable) ->
                  // 将状态处理逻辑也放入一个异步任务，避免在Netty线程中执行耗时操作
                  scheduler
                      .buildTask(
                          plugin,
                          () -> {
                            if (throwable != null) {
                              handleStatusChange(serverName, ServerStatus.OFFLINE);
                            } else {
                              handleStatusChange(serverName, ServerStatus.ONLINE);
                            }
                          })
                      .schedule());
    }
  }

  /**
   * 处理状态变化
   *
   * @param serverName 服务器名
   * @param newStatus 检测到的新状态
   */
  private void handleStatusChange(String serverName, ServerStatus newStatus) {
    // 获取上一次的状态, 默认为UNKNOWN
    ServerStatus oldStatus = serverStatusCache.getOrDefault(serverName, ServerStatus.UNKNOWN);
    // 只有当状态发生真实变化时才进行操作
    if (newStatus != oldStatus) {
      // 更新缓存中的状态
      serverStatusCache.put(serverName, newStatus);
      String serverDisplayName = serverNameMappings.getOrDefault(serverName, serverName);
      if (newStatus == ServerStatus.OFFLINE) {
        logger.error("检测到后端服务器 [{}] 已下线", serverName);
        reportManager.sendAdminNotification(String.format("🔴 后端服务器 [%s] 已下线", serverDisplayName));
      } else if (newStatus == ServerStatus.ONLINE && oldStatus == ServerStatus.OFFLINE) {
        // 我们只在它从 OFFLINE 恢复时才发送通知，避免启动时发送
        logger.info("后端服务器 [{}] 已上线。", serverName);
        reportManager.sendAdminNotification(String.format("🟢 后端服务器 [%s] 已上线", serverDisplayName));
      }
    }
  }
}
