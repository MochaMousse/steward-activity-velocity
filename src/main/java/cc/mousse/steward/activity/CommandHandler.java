package cc.mousse.steward.activity;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * @author MochaMousse
 */
public class CommandHandler {
  private final Main plugin;

  public CommandHandler(Main plugin) {
    this.plugin = plugin;
  }

  public BrigadierCommand createBrigadierCommand() {
    // 创建一个字面量命令节点, 即 /activity
    LiteralCommandNode<CommandSource> stewardCommandNode =
        LiteralArgumentBuilder.<CommandSource>literal("activity")
            // 设置权限节点
            .requires(source -> source.hasPermission("steward.activity.admin"))
            // 子命令: /activity reload
            .then(
                LiteralArgumentBuilder.<CommandSource>literal("reload")
                    .executes(
                        context -> {
                          context
                              .getSource()
                              .sendMessage(
                                  Component.text(
                                      "重新加载Steward-Activity插件配置", NamedTextColor.YELLOW));
                          plugin.reload();
                          context
                              .getSource()
                              .sendMessage(Component.text("插件配置已成功重载", NamedTextColor.GREEN));
                          // 表示命令成功执行
                          return Command.SINGLE_SUCCESS;
                        }))
            // 子命令: /activity report
            .then(
                LiteralArgumentBuilder.<CommandSource>literal("report")
                    // 子命令: /activity report daily
                    .then(
                        LiteralArgumentBuilder.<CommandSource>literal("daily")
                            .executes(
                                context -> {
                                  context
                                      .getSource()
                                      .sendMessage(Component.text("手动触发日报生成", NamedTextColor.AQUA));
                                  // 调用ReportManager中的方法，仅发送到日志群
                                  plugin.getReportManager().sendDailyReportToLogGroup();
                                  context
                                      .getSource()
                                      .sendMessage(
                                          Component.text("日报已在后台生成并发送到日志群", NamedTextColor.GREEN));
                                  return Command.SINGLE_SUCCESS;
                                }))
                    // 子命令: /activity report monthly
                    .then(
                        LiteralArgumentBuilder.<CommandSource>literal("monthly")
                            .executes(
                                context -> {
                                  context
                                      .getSource()
                                      .sendMessage(Component.text("手动触发月报生成", NamedTextColor.AQUA));
                                  // 调用ReportManager中的方法，仅发送到日志群
                                  plugin.getReportManager().sendMonthlyReportToLogGroup();
                                  context
                                      .getSource()
                                      .sendMessage(
                                          Component.text("月报已在后台生成并发送到日志群", NamedTextColor.GREEN));
                                  return Command.SINGLE_SUCCESS;
                                })))
            .build();
    // 将创建的命令节点包装成Velocity可以识别的BrigadierCommand对象
    return new BrigadierCommand(stewardCommandNode);
  }
}
