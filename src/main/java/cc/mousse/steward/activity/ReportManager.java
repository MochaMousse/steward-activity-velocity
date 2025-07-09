package cc.mousse.steward.activity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.velocitypowered.api.scheduler.Scheduler;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okhttp3.Request;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * @author MochaMousse
 */
public final class ReportManager {
  // 安全的消息最大长度, 用于QQ消息自动分片
  private static final int MAX_MESSAGE_LENGTH = 1800;

  private final Gson gson;
  private final Main plugin;
  private final Logger logger;
  private final Scheduler scheduler;
  private final OkHttpClient httpClient;
  private final DatabaseManager databaseManager;
  private final Map<String, String> serverNameMappings;
  private final PluginConfig.GoCqHttpConfig cqHttpConfig;

  public ReportManager(Main plugin) {
    this.plugin = plugin;
    this.gson = new Gson();
    this.logger = plugin.getLogger();
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    this.scheduler = plugin.getServer().getScheduler();
    this.databaseManager = plugin.getDatabaseManager();
    this.cqHttpConfig = plugin.getConfig().getGoCqhttp();
    this.serverNameMappings = this.cqHttpConfig.getServerNameMappings();
  }

  /** 命令触发生成并仅发送日报到日志群 */
  public void sendDailyReportToLogGroup() {
    logger.info("命令触发生成日报");
    LocalDate today = LocalDate.now();
    // 从今天凌晨开始
    LocalDateTime startTime = today.atStartOfDay();
    // 到当前时刻结束
    LocalDateTime endTime = LocalDateTime.now();
    String title = String.format("%s 当日实时日报", today);
    databaseManager
        .queryDetailedSessionDataAsync(startTime, endTime)
        .thenAcceptAsync(
            sessionList -> {
              if (sessionList == null || sessionList.isEmpty()) {
                plugin.getLogger().info("命令触发日报: 当日尚无有效的玩家在线数据");
                sendAdminNotification(String.format("🗓️ %s 🗓️%s今日暂无玩家活动记录", title, "\n\n"));
                return;
              }
              List<PlayerStats> playerStatsList = aggregateStats(sessionList, false);
              List<String> detailedMessages = formatDetailedReport(title, playerStatsList, false);
              sendBatchedMessages(detailedMessages, cqHttpConfig.getGroups().getLogGroupId());
            });
  }

  /** 命令触发生成并仅发送月报到日志群 */
  public void sendMonthlyReportToLogGroup() {
    logger.info("命令触发生成月报");
    LocalDate thisMonthDate = LocalDate.now();
    // 从本月第一天凌晨开始
    LocalDateTime startTime = thisMonthDate.withDayOfMonth(1).atStartOfDay();
    // 到当前时刻结束
    LocalDateTime endTime = LocalDateTime.now();
    String title =
        String.format("%d年%d月 实时月报", thisMonthDate.getYear(), thisMonthDate.getMonthValue());
    databaseManager
        .queryDetailedSessionDataAsync(startTime, endTime)
        .thenAcceptAsync(
            sessionList -> {
              if (sessionList == null || sessionList.isEmpty()) {
                plugin.getLogger().info("命令触发月报: 当月尚无有效的玩家在线数据");
                sendAdminNotification(String.format("🗓️ %s 🗓️%s本月暂无玩家活动记录", title, "\n\n"));
                return;
              }
              List<PlayerStats> playerStatsList = aggregateStats(sessionList, true);
              List<String> detailedMessages = formatDetailedReport(title, playerStatsList, true);
              sendBatchedMessages(detailedMessages, cqHttpConfig.getGroups().getLogGroupId());
            });
  }

  public void generateAndDispatchDailyReport() {
    logger.info("开始生成日报");
    LocalDate yesterday = LocalDate.now().minusDays(1);
    databaseManager
        .queryDetailedSessionDataAsync(yesterday.atStartOfDay(), yesterday.atTime(LocalTime.MAX))
        .thenAcceptAsync(
            sessionList -> {
              if (sessionList.isEmpty()) {
                logger.info("昨日无玩家在线数据");
                return;
              }
              List<PlayerStats> playerStatsList = aggregateStats(sessionList, false);
              List<String> detailedMessages =
                  formatDetailedReport(
                      String.format("%s 服务器详细日报", yesterday), playerStatsList, false);
              List<String> simpleMessages =
                  formatSimpleReport(String.format("%s 玩家活跃榜", yesterday), playerStatsList, false);
              dispatchMessages(detailedMessages, simpleMessages);
            });
  }

  public void generateAndDispatchMonthlyReport() {
    logger.info("开始生成月报");
    LocalDate lastMonth = LocalDate.now().minusMonths(1);
    LocalDateTime startOfMonth = lastMonth.withDayOfMonth(1).atStartOfDay();
    LocalDateTime endOfMonth =
        lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()).atTime(LocalTime.MAX);
    databaseManager
        .queryDetailedSessionDataAsync(startOfMonth, endOfMonth)
        .thenAcceptAsync(
            sessionList -> {
              if (sessionList == null || sessionList.isEmpty()) {
                logger.info("上月无玩家在线数据");
                return;
              }
              List<PlayerStats> playerStatsList = aggregateStats(sessionList, true);
              List<String> detailedMessages =
                  formatDetailedReport(
                      String.format(
                          "%d年%d月 服务器详细月报", lastMonth.getYear(), lastMonth.getMonthValue()),
                      playerStatsList,
                      true);
              List<String> simpleMessages =
                  formatSimpleReport(
                      String.format("%d年%d月 玩家荣誉榜", lastMonth.getYear(), lastMonth.getMonthValue()),
                      playerStatsList,
                      true);
              dispatchMessages(detailedMessages, simpleMessages);
            });
  }

  private List<PlayerStats> aggregateStats(
      List<DatabaseManager.PlayerSessionData> sessions, boolean isMonthly) {
    Map<String, PlayerStats> statsMap = new HashMap<>();
    Map<String, Set<LocalDate>> activeDaysMap = isMonthly ? new HashMap<>() : null;
    for (DatabaseManager.PlayerSessionData session : sessions) {
      PlayerStats playerStats =
          statsMap.computeIfAbsent(session.uuid(), k -> new PlayerStats(session.username()));
      playerStats.totalDurationMs += session.durationMilliseconds();
      playerStats.totalLoginCount++;
      ServerStats serverStats =
          playerStats.perServerStats.computeIfAbsent(session.serverName(), k -> new ServerStats());
      serverStats.durationMs += session.durationMilliseconds();
      serverStats.loginCount++;
      if (isMonthly) {
        activeDaysMap
            .computeIfAbsent(session.uuid(), k -> new HashSet<>())
            .add(session.loginTimestamp().toLocalDate());
      }
    }
    if (isMonthly) {
      statsMap.forEach((uuid, stats) -> stats.activeDays = activeDaysMap.get(uuid).size());
    }
    return statsMap.values().stream()
        .sorted(Comparator.comparingLong(s -> -s.totalDurationMs))
        .toList();
  }

  private List<String> formatDetailedReport(
      String title, List<PlayerStats> playerStatsList, boolean isMonthly) {
    List<String> messages = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    Long totalSessions = playerStatsList.stream().mapToLong(s -> s.totalLoginCount).sum();
    sb.append(String.format("🗓️ %s 🗓️", title)).append("\n");
    sb.append(String.format("总览: %s名独立玩家, %s次登录会话", playerStatsList.size(), totalSessions))
        .append("\n");
    int rank = 1;
    for (PlayerStats stats : playerStatsList) {
      StringBuilder playerBlock = new StringBuilder("\n");
      playerBlock.append(String.format("No.%s [%s]", rank++, stats.username)).append("\n");
      playerBlock
          .append(String.format("- 总时长: %s", formatDuration(stats.totalDurationMs)))
          .append("\n");
      playerBlock.append(String.format("- 总次数: %d次", stats.totalLoginCount)).append("\n");
      if (isMonthly) {
        playerBlock.append(String.format("- 活跃天数: %d天", stats.activeDays)).append("\n");
      }
      playerBlock.append("- 服务器详情:\n");
      stats.perServerStats.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> {
                String serverId = entry.getKey() != null ? entry.getKey() : "unknown";
                String serverDisplayName = serverNameMappings.getOrDefault(serverId, serverId);
                ServerStats serverStats = entry.getValue();
                playerBlock
                    .append(
                        String.format(
                            "  - %s: %s (%d次)",
                            serverDisplayName,
                            formatDuration(serverStats.durationMs),
                            serverStats.loginCount))
                    .append("\n");
              });
      if (sb.length() + playerBlock.length() > MAX_MESSAGE_LENGTH) {
        messages.add(sb.toString());
        sb = new StringBuilder(String.format("🗓️ %s 🗓️ (续)", title)).append("\n");
      }
      sb.append(playerBlock);
    }
    messages.add(sb.toString());
    return messages;
  }

  private List<String> formatSimpleReport(
      String title, List<PlayerStats> playerStatsList, boolean isMonthly) {
    List<String> messages = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("🗓️ %s 🗓️", title)).append("\n");
    int rank = 1;
    for (PlayerStats stats : playerStatsList) {
      StringBuilder playerBlock = new StringBuilder("\n");
      playerBlock.append(String.format("No.%s [%s]", rank++, stats.username)).append("\n");
      playerBlock
          .append(isMonthly ? "🕒 本月在线: " : "🕒 总在线: ")
          .append(formatDuration(stats.totalDurationMs))
          .append("\n");
      if (isMonthly) {
        playerBlock.append(String.format("🗓️ 活跃天数: %d天", stats.activeDays)).append("\n");
      }
      playerBlock.append("🐾 游戏足迹:\n");
      stats.perServerStats.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> {
                String serverId = entry.getKey() != null ? entry.getKey() : "unknown";
                String serverDisplayName = serverNameMappings.getOrDefault(serverId, serverId);
                ServerStats serverStats = entry.getValue();
                playerBlock
                    .append(
                        String.format(
                            "   - %s: %s",
                            serverDisplayName, formatDuration(serverStats.durationMs)))
                    .append("\n");
              });
      if (sb.length() + playerBlock.length() > MAX_MESSAGE_LENGTH) {
        messages.add(sb.toString());
        sb = new StringBuilder(String.format("🗓️ %s 🗓️ (续)", title)).append("\n");
      }
      sb.append(playerBlock);
    }
    messages.add(sb.toString());
    return messages;
  }

  private void dispatchMessages(List<String> detailedMessages, List<String> simpleMessages) {
    Long logGroupId = cqHttpConfig.getGroups().getLogGroupId();
    if (logGroupId != null) {
      sendBatchedMessages(detailedMessages, logGroupId);
    }
    if (cqHttpConfig.getReports().isSendToCommunityGroup()) {
      Long communityGroupId = cqHttpConfig.getGroups().getCommunityGroupId();
      if (communityGroupId != null) {
        scheduler
            .buildTask(plugin, () -> sendBatchedMessages(simpleMessages, communityGroupId))
            .delay(detailedMessages.size() * 2L + 1L, TimeUnit.SECONDS)
            .schedule();
      }
    }
  }

  public void sendAdminNotification(String message) {
    Long logGroupId = cqHttpConfig.getGroups().getLogGroupId();
    if (logGroupId != null) {
      sendMessageToGoCqHttp(message, logGroupId);
    }
  }

  private void sendBatchedMessages(List<String> messages, Long groupId) {
    for (int i = 0; i < messages.size(); i++) {
      final String message = messages.get(i);
      scheduler
          .buildTask(plugin, () -> sendMessageToGoCqHttp(message, groupId))
          .delay(i * 2L, TimeUnit.SECONDS)
          .schedule();
    }
  }

  private void sendMessageToGoCqHttp(String message, Long groupId) {
    JsonObject payload = new JsonObject();
    payload.addProperty("group_id", groupId);
    payload.addProperty("message", message);
    RequestBody body =
        RequestBody.create(gson.toJson(payload), MediaType.get("application/json; charset=utf-8"));
    Request request =
        new Request.Builder().url(cqHttpConfig.getUrl() + "/send_group_msg").post(body).build();
    httpClient
        .newCall(request)
        .enqueue(
            new okhttp3.Callback() {
              @Override
              public void onFailure(@NotNull Call call, @NotNull IOException e) {
                logger.error(
                    "发送消息到QQ群'{}'失败: {URL:{},错误:{}}",
                    groupId,
                    call.request().url(),
                    e.getMessage());
              }

              @Override
              public void onResponse(@NotNull Call call, @NotNull Response response)
                  throws IOException {
                try (ResponseBody responseBody = response.body()) {
                  if (!response.isSuccessful()) {
                    logger.error(
                        "发送消息到QQ群'{}'失败: {响应码:{},响应体:{}}",
                        groupId,
                        response.code(),
                        responseBody != null ? responseBody.string() : "N/A");
                  } else {
                    logger.info("成功发送报告片段到QQ群'{}'", groupId);
                  }
                }
              }
            });
  }

  private String formatDuration(long milliseconds) {
    if (milliseconds < 0) {
      return "0秒";
    }
    if (milliseconds < 60000) {
      return String.format("%s秒", milliseconds / 1000);
    }
    long hours = TimeUnit.MILLISECONDS.toHours(milliseconds);
    long minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % 60;
    if (hours == 0) {
      return String.format("%d分钟", minutes);
    }
    return String.format("%d小时 %d分钟", hours, minutes);
  }

  Duration calculateDelayUntil(LocalTime targetTime) {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime nextRun = now.with(targetTime);
    if (now.isAfter(nextRun)) {
      nextRun = nextRun.plusDays(1);
    }
    return Duration.between(now, nextRun);
  }

  // 内部数据类, 用于在Java中聚合统计数据
  private static class ServerStats {
    Long durationMs = 0L;
    Integer loginCount = 0;
  }

  private static class PlayerStats {
    String username;
    Long totalDurationMs = 0L;
    Integer totalLoginCount = 0;
    Integer activeDays = 0;
    Map<String, ServerStats> perServerStats = new HashMap<>();

    PlayerStats(String username) {
      this.username = username;
    }
  }
}
