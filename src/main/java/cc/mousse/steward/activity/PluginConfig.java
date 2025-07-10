package cc.mousse.steward.activity;

import java.util.Collections;
import java.util.Map;
import lombok.Data;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/**
 * @author MochaMousse
 */
@Data
@ConfigSerializable
public class PluginConfig {
  private DatabaseConfig database = new DatabaseConfig();
  private GoCqHttpConfig goCqhttp = new GoCqHttpConfig();

  @Data
  @ConfigSerializable
  public static class DatabaseConfig {
    private String host;
    private Integer port;
    private String database;
    private String username;
    private String password;

    @Comment("JDBC URL的额外参数(?useUnicode=true&characterEncoding=utf8)")
    private String extraParams = "?useUnicode=true&characterEncoding=utf8";
  }

  @Data
  @ConfigSerializable
  public static class GoCqHttpConfig {
    private String url;
    private String proxyId;
    private GroupsConfig groups = new GroupsConfig();
    private ReportsConfig reports = new ReportsConfig();

    @Comment("服务器ID与其显示名称的映射")
    private Map<String, String> serverNameMappings = Collections.emptyMap();

    @Data
    @ConfigSerializable
    public static class GroupsConfig {
      @Comment("用于发送日志和详细报告的群")
      private Long logGroupId;

      @Comment("玩家社群")
      private Long communityGroupId;
    }

    @Data
    @ConfigSerializable
    public static class ReportsConfig {
      @Comment("是否将日报/月报也发送到玩家社群")
      private boolean sendToCommunityGroup;
    }
  }
}
