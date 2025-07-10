package cc.mousse.steward.activity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.velocitypowered.api.scheduler.Scheduler;
import java.io.IOException;
import java.time.*;
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
  /** 安全的消息最大长度, 用于QQ消息自动分片 */
  private static final int MAX_MESSAGE_LENGTH = 1800;

  private static final String UNKNOWN_SERVER = "unknown";
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
    LocalDate today = LocalDateTime.now(Main.SHANGHAI_ZONE).toLocalDate();
    // 从今天凌晨开始
    LocalDateTime startOfDay = today.atStartOfDay();
    // 到当前时刻结束
    LocalDateTime endTime = LocalDateTime.now(Main.SHANGHAI_ZONE);
    String title = String.format("%s 当日实时日报", today);
    databaseManager
        .queryDetailedSessionDataAsync(startOfDay, endTime)
        .thenAcceptAsync(
            sessionList -> {
              if (sessionList == null || sessionList.isEmpty()) {
                plugin.getLogger().info("命令触发日报: 当日尚无有效的玩家在线数据");
                sendAdminNotification(String.format("🗓️ %s 🗓️%s今日暂无玩家活动记录", title, "\n\n"));
                return;
              }
              List<PlayerStats> playerStatsList =
                  aggregateStats(sessionList, false, startOfDay, endTime);
              List<String> detailedMessages = formatDetailedReport(title, playerStatsList, false);
              sendBatchedMessages(detailedMessages, cqHttpConfig.getGroups().getLogGroupId());
            });
  }

  /** 命令触发生成并仅发送月报到日志群 */
  public void sendMonthlyReportToLogGroup() {
    logger.info("命令触发生成月报");
    LocalDate thisMonthDate = LocalDateTime.now(Main.SHANGHAI_ZONE).toLocalDate();
    // 从本月第一天凌晨开始
    LocalDateTime startOfMonth = thisMonthDate.withDayOfMonth(1).atStartOfDay();
    // 到当前时刻结束
    LocalDateTime endTime = LocalDateTime.now(Main.SHANGHAI_ZONE);
    String title =
        String.format("%d年%d月 实时月报", thisMonthDate.getYear(), thisMonthDate.getMonthValue());
    databaseManager
        .queryDetailedSessionDataAsync(startOfMonth, endTime)
        .thenAcceptAsync(
            sessionList -> {
              if (sessionList == null || sessionList.isEmpty()) {
                plugin.getLogger().info("命令触发月报: 当月尚无有效的玩家在线数据");
                sendAdminNotification(String.format("🗓️ %s 🗓️%s本月暂无玩家活动记录", title, "\n\n"));
                return;
              }
              List<PlayerStats> playerStatsList =
                  aggregateStats(sessionList, true, startOfMonth, endTime);
              List<String> detailedMessages = formatDetailedReport(title, playerStatsList, true);
              sendBatchedMessages(detailedMessages, cqHttpConfig.getGroups().getLogGroupId());
            });
  }

  public void generateAndDispatchDailyReport() {
    logger.info("开始生成日报");
    LocalDate yesterday = LocalDateTime.now(Main.SHANGHAI_ZONE).toLocalDate().minusDays(1);
    LocalDateTime startOfDay = yesterday.atStartOfDay();
    LocalDateTime endOfDay = yesterday.atTime(LocalTime.MAX);
    databaseManager
        .queryDetailedSessionDataAsync(startOfDay, endOfDay)
        .thenAcceptAsync(
            sessionList -> {
              if (sessionList.isEmpty()) {
                logger.info("昨日无玩家在线数据");
                return;
              }
              List<PlayerStats> playerStatsList =
                  aggregateStats(sessionList, false, startOfDay, endOfDay);
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
    LocalDate lastMonth = LocalDateTime.now(Main.SHANGHAI_ZONE).toLocalDate().minusMonths(1);
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
              List<PlayerStats> playerStatsList =
                  aggregateStats(sessionList, true, startOfMonth, endOfMonth);
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

  /**
   * 核心聚合逻辑
   *
   * @param sessions 从数据库获取的原始会话列表
   * @param isMonthly 是否为月报
   * @param reportStart 报告的开始时间
   * @param reportEnd 报告的结束时间
   * @return 聚合统计后的玩家列表
   */
  private List<PlayerStats> aggregateStats(
      List<DatabaseManager.PlayerSessionData> sessions,
      boolean isMonthly,
      LocalDateTime reportStart,
      LocalDateTime reportEnd) {
    Map<String, PlayerStats> statsMap = new HashMap<>(8);
    Map<String, Set<LocalDate>> activeDaysMap = isMonthly ? new HashMap<>(8) : null;
    for (var session : sessions) {
      processSingleSession(session, statsMap, activeDaysMap, isMonthly, reportStart, reportEnd);
    }
    // 在所有会話都处理完毕后再统一计算每个玩家最终的活跃天数
    if (isMonthly) {
      statsMap.forEach(
          (uuid, stats) ->
              stats.activeDays = activeDaysMap.getOrDefault(uuid, Collections.emptySet()).size());
    }
    // 按总时长倒序排序并返回
    return statsMap.values().stream()
        .sorted(Comparator.comparingLong(s -> -s.totalDurationMs))
        .toList();
  }

  /** 负责处理单条会话记录的所有复杂计算和聚合逻辑。 */
  private void processSingleSession(
      DatabaseManager.PlayerSessionData session,
      Map<String, PlayerStats> statsMap,
      Map<String, Set<LocalDate>> activeDaysMap,
      boolean isMonthly,
      LocalDateTime reportStart,
      LocalDateTime reportEnd) {
    PlayerStats playerStats =
        statsMap.computeIfAbsent(session.uuid(), k -> new PlayerStats(session.username()));
    // 计算会话与报告周期的交集时长
    LocalDateTime sessionStart = session.loginTimestamp();
    LocalDateTime sessionEnd =
        (session.logoutTimestamp() == null) ? reportEnd : session.logoutTimestamp();
    LocalDateTime effectiveStart = sessionStart.isAfter(reportStart) ? sessionStart : reportStart;
    LocalDateTime effectiveEnd = sessionEnd.isBefore(reportEnd) ? sessionEnd : reportEnd;
    long durationForThisReport =
        Math.max(0, Duration.between(effectiveStart, effectiveEnd).toMillis());
    // 聚合数据
    playerStats.totalDurationMs += durationForThisReport;
    playerStats.totalLoginCount++;
    ServerStats serverStats =
        playerStats.perServerStats.computeIfAbsent(
            session.serverName() != null ? session.serverName() : UNKNOWN_SERVER,
            k -> new ServerStats());
    serverStats.durationMs += durationForThisReport;
    serverStats.loginCount++;
    // 如果是月报记录活跃天数
    if (isMonthly) {
      Set<LocalDate> playerActiveDays =
          activeDaysMap.computeIfAbsent(session.uuid(), k -> new HashSet<>());
      for (LocalDate date = effectiveStart.toLocalDate();
          !date.isAfter(effectiveEnd.toLocalDate());
          date = date.plusDays(1)) {
        playerActiveDays.add(date);
      }
    }
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
                String serverId = entry.getKey() != null ? entry.getKey() : UNKNOWN_SERVER;
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
                String serverId = entry.getKey() != null ? entry.getKey() : UNKNOWN_SERVER;
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
    long oneMinuteInMilliseconds = 60000;
    if (milliseconds < oneMinuteInMilliseconds) {
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
    LocalDateTime now = LocalDateTime.now(Main.SHANGHAI_ZONE);
    LocalDateTime nextRun = now.with(targetTime);
    if (now.isAfter(nextRun)) {
      nextRun = nextRun.plusDays(1);
    }
    return Duration.between(now, nextRun);
  }


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
