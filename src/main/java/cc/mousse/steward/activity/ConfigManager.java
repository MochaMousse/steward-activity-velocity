package cc.mousse.steward.activity;

import java.io.IOException;
import java.nio.file.Path;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * @author MochaMousse
 */
public class ConfigManager {
  private final Main plugin;
  private final Path configFile;
  private final YamlConfigurationLoader loader;
  private PluginConfig config;
  private CommentedConfigurationNode root;

  public ConfigManager(Main plugin) {
    this.plugin = plugin;
    this.configFile = plugin.getDataDirectory().resolve("config.yml");
    this.loader = YamlConfigurationLoader.builder().path(configFile).build();
  }

  public void load() {
    try {
      // 如果文件不存在, 创建它并写入默认值
      if (!configFile.toFile().exists()) {
        plugin.getLogger().info("初始化配置文件");
        saveDefaults();
      }
      // 加载文件
      root = loader.load();
      // 将节点映射到配置对象
      config = root.get(PluginConfig.class);
      // 重新保存一次, 可以自动添加新版本的配置项
      save();
    } catch (IOException e) {
      plugin.getLogger().error("加载配置文件失败", e);
      // 加载失败时使用默认配置
      config = new PluginConfig();
    }
  }

  public void save() throws IOException {
    // 如果根节点或配置对象为空，则不能保存
    if (root == null || config == null) {
      return;
    }
    // 将配置对象转换回节点树，然后保存到文件
    root.set(PluginConfig.class, config);
    loader.save(root);
  }

  private void saveDefaults() throws IOException {
    root = loader.createNode();
    // 直接从一个新的, 包含默认值的对象来设置
    root.set(PluginConfig.class, new PluginConfig());
    loader.save(root);
  }

  public PluginConfig get() {
    return config;
  }
}
