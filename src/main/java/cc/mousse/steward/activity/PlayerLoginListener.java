package cc.mousse.steward.activity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * @author MochaMousse
 */
public final class PlayerLoginListener {
  private final Main plugin;
  private final ReportManager reportManager;
  private final DatabaseManager databaseManager;
  private final Map<String, String> serverNameMappings;

  /** 用于追踪当前在线玩家的会话信息 */
  private final ConcurrentMap<UUID, Long> activeSessionIds = new ConcurrentHashMap<>();

  /** 用于计算在线时长 */
  private final ConcurrentMap<UUID, Instant> loginTimestamps = new ConcurrentHashMap<>();

  /** 用于缓存下线原因 */
  private final Map<UUID, String> kickReasonsCache = new ConcurrentHashMap<>();

  /** 用于实时追踪玩家所在的最后一个服务器 */
  private final ConcurrentMap<UUID, RegisteredServer> lastKnownServerCache =
      new ConcurrentHashMap<>();

  public PlayerLoginListener(Main plugin) {
    this.plugin = plugin;
    reportManager = plugin.getReportManager();
    databaseManager = plugin.getDatabaseManager();
    serverNameMappings = plugin.getConfig().getGoCqhttp().getServerNameMappings();
  }

  /** 玩家登录代理成功, 标记为“预备登录”状态 */
  @Subscribe
  public void onPostLogin(PostLoginEvent event) {
    // 在这里，我们只记录登录时间戳，表示一个会话的开始
    // 我们不在这里执行任何需要服务器信息的操作
    loginTimestamps.put(event.getPlayer().getUniqueId(), Instant.now());
  }

  /** 家成功连接到某个后端服务器 */
  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    Player player = event.getPlayer();
    UUID playerUuid = player.getUniqueId();
    // 核心逻辑: 通过检查loginTimestamps来判断这是否是玩家的“首次”连接
    // loginTimestamps.remove() 会原子性地移除并返回时间戳, 确保此逻辑只执行一次
    Instant loginInstant = loginTimestamps.remove(playerUuid);
    if (loginInstant == null) {
      // 如果为null, 说明这不是首次连接 (比如玩家在服务器之间切换), 忽略
      return;
    }
    String uuid = playerUuid.toString();
    String username = player.getUsername();
    String ipAddress = player.getRemoteAddress().getAddress().getHostAddress();
    int protocolVersion = player.getProtocolVersion().getProtocol();
    LocalDateTime loginDateTime = LocalDateTime.ofInstant(loginInstant, Main.SHANGHAI_ZONE);
    String serverId = event.getServer().getServerInfo().getName();
    String proxyId = plugin.getConfig().getGoCqhttp().getProxyId();
    lastKnownServerCache.put(playerUuid, event.getServer());
    // 异步写入数据库
    databaseManager
        .logPlayerLoginAndGetIdAsync(
            uuid, username, ipAddress, loginDateTime, serverId, protocolVersion, proxyId)
        .thenAcceptAsync(
            recordId -> {
              if (recordId != -1L) {
                // 将会话标记为"已激活"
                activeSessionIds.put(playerUuid, recordId);
                // 重新把登录时间存回去, 这次是为了给onDisconnect计算时长用
                loginTimestamps.put(playerUuid, loginInstant);
                plugin.getLogger().info("已为玩家'{}'创建会话，数据库记录ID: {}", username, recordId);
              } else {
                plugin.getLogger().warn("未能为玩家'{}'创建数据库会话记录", username);
              }
            });
    // 异步发送实时登录通知
    plugin
        .getServer()
        .getScheduler()
        .buildTask(
            plugin,
            () -> {
              String serverDisplayName = serverNameMappings.getOrDefault(serverId, serverId);
              Collection<Player> playersOnServer = event.getServer().getPlayersConnected();
              int serverPlayerCount =
                  playersOnServer.contains(player)
                      ? playersOnServer.size()
                      : (playersOnServer.size() + 1);
              int totalPlayerCount = plugin.getServer().getPlayerCount();
              String loginMessage =
                  String.format(
                      "⬆️ 玩家 [%s] 已上线%s- IP地址: %s%s- 服务器: %s%s- 在线人数: %d / %d",
                      username,
                      "\n",
                      ipAddress,
                      "\n",
                      serverDisplayName,
                      "\n",
                      serverPlayerCount,
                      totalPlayerCount);
              reportManager.sendAdminNotification(loginMessage);
            })
        .schedule();
  }

  /** 处理玩家断开连接的事件 */
  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    Player player = event.getPlayer();
    UUID playerUuid = player.getUniqueId();
    Instant disconnectInstant = Instant.now();
    // 从缓存中获取并移除该玩家的会话ID和登录时间
    Long recordId = activeSessionIds.remove(playerUuid);
    Instant loginTime = loginTimestamps.remove(playerUuid);
    RegisteredServer lastServer = lastKnownServerCache.remove(playerUuid);
    if (recordId == null || loginTime == null) {
      // 如果缓存中没有该玩家说明可能是在登录完成前就断开了或服务器重启过
      // 这种不完整的会话我们不作处理
      return;
    }
    //  获取所有登出时需要的数据
    long durationMs = Duration.between(loginTime, disconnectInstant).toMillis();
    LocalDateTime logoutDateTime = LocalDateTime.ofInstant(disconnectInstant, Main.SHANGHAI_ZONE);
    String reason;
    // 优先检查他是不是刚刚被踢出了
    // 检查并移除
    String kickReason = kickReasonsCache.remove(playerUuid);
    if (kickReason != null) {
      reason = "被踢出: " + kickReason;
    } else {
      reason = "客户端主动断开";
    }
    // 异步更新数据库中的登出信息
    databaseManager.updatePlayerLogoutAsync(recordId, logoutDateTime, durationMs, reason);
    // 异步发送实时登出通知到日志群
    plugin
        .getServer()
        .getScheduler()
        .buildTask(
            plugin,
            () -> {
              String serverDisplayName;
              String serverPlayerCount;
              if (lastServer != null && lastServer.getServerInfo() != null) {
                // 如果能获取到最后所在的服务器信息
                String serverId = lastServer.getServerInfo().getName();
                serverDisplayName = serverNameMappings.getOrDefault(serverId, serverId);
                serverPlayerCount = String.valueOf(lastServer.getPlayersConnected().size());
              } else {
                // 如果获取不到
                serverDisplayName = "unknown";
                serverPlayerCount = "-";
              }
              int totalPlayerCount = plugin.getServer().getPlayerCount();
              String logoutMessage =
                  String.format(
                      "⬇️ 玩家 [%s] 已下线%s- IP地址: %s%s- 服务器: %s%s- 在线时长: %s%s- 离线原因: %s%s- 在线人数: %s / %d",
                      player.getUsername(),
                      "\n",
                      player.getRemoteAddress().getAddress().getHostAddress(),
                      "\n",
                      serverDisplayName,
                      "\n",
                      formatDuration(durationMs),
                      "\n",
                      reason,
                      "\n",
                      serverPlayerCount,
                      totalPlayerCount);
              reportManager.sendAdminNotification(logoutMessage);
            })
        .schedule();
  }

  @Subscribe
  public void onKickedFromServer(KickedFromServerEvent event) {
    // 当玩家被子服踢出时在这里捕获原因
    String reason =
        event
            .getServerKickReason()
            .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
            .orElse("被服务器踢出 (未知原因)");
    // 将原因存入缓存, 等待后续的DisconnectEvent处理
    // 通常被踢后会立刻触发DisconnectEvent
    kickReasonsCache.put(event.getPlayer().getUniqueId(), reason);
  }

  /** 将毫秒格式化为 "X小时 Y分钟 Z秒" */
  private String formatDuration(long milliseconds) {
    if (milliseconds < 0) {
      return "0秒";
    }
    long seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds);
    int oneMinuteInSeconds = 60;
    if (seconds < oneMinuteInSeconds) {
      return String.format("%s秒", seconds);
    }
    long hours = TimeUnit.MILLISECONDS.toHours(milliseconds);
    long minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % 60;
    long remainingSeconds = seconds % 60;
    StringBuilder sb = new StringBuilder();
    if (hours > 0) {
      sb.append(String.format("%s小时 ", hours));
    }
    // 即使分钟为0但小时不为0也显示分钟
    if (minutes > 0 || hours > 0) {
      sb.append(String.format("%s分钟 ", minutes));
    }
    sb.append(String.format("%s秒 ", remainingSeconds));
    return sb.toString().trim();
  }
}
