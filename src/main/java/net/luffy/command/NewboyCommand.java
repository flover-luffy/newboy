package net.luffy.command;

import net.mamoe.mirai.console.command.CommandSender;
import net.mamoe.mirai.console.command.java.JCompositeCommand;
import net.mamoe.mirai.console.util.ConsoleExperimentalApi;
import net.mamoe.mirai.event.events.GroupMessageEvent;
import net.mamoe.mirai.contact.Group;
import net.luffy.Newboy;
import net.luffy.util.OnlineStatusMonitor;

import java.util.List;
import kotlin.jvm.JvmDefault;
import net.mamoe.mirai.console.command.descriptor.ExperimentalCommandDescriptors;

public class NewboyCommand extends JCompositeCommand {
    public static final NewboyCommand INSTANCE = new NewboyCommand();

    private NewboyCommand() {
        super(Newboy.INSTANCE, "newboy");
    }

    @SubCommand
    public void reload(CommandSender sender) {
        // Newboy.INSTANCE.reloadPlugin();
        sender.sendMessage("Newboy 插件重新加载功能暂未实现");
    }

    @SubCommand
    public void status(CommandSender sender) {
        sender.sendMessage("Newboy 插件运行正常");
    }

    @SubCommand
    public void help(CommandSender sender) {
        sender.sendMessage("Newboy 插件帮助:\n/newboy reload - 重新加载插件\n/newboy status - 查看插件状态\n/newboy help - 显示帮助信息\n/newboy monitor - 在线状态监控功能\n\n详细命令请使用 /newboy monitor 查看");
    }
    
    @SubCommand
    public void monitor(CommandSender sender) {
        String helpText = "📊 在线状态监控功能\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "📋 基本命令:\n" +
                "  /newboy monitor add <成员名> - 添加监控\n" +
                "  /newboy monitor remove <成员名> - 移除监控\n" +
                "  /newboy monitor list - 查看监控列表\n" +
                "  /newboy monitor list realtime - 实时状态对比\n" +
                "  /newboy monitor check <成员名> - 查询在线状态\n" +
                "\n🔧 管理命令:\n" +
                "  /newboy monitor toggle - 开关监控功能\n" +
                "  /newboy monitor sync - 同步所有状态\n" +
                "  /newboy monitor stats - 查看统计信息\n" +
                "  /newboy monitor interval <cron表达式> - 设置监控间隔\n" +
                "\n💡 示例:\n" +
                "  /newboy monitor add 张三\n" +
                "  /newboy monitor interval */5 * * * *\n" +
                "━━━━━━━━━━━━━━━━━━━━";
        sender.sendMessage(helpText);
    }
    
    @SubCommand
    public void monitor(CommandSender sender, String action) {
        if (!(sender.getSubject() instanceof Group)) {
            sender.sendMessage("❌ 监控功能只能在群聊中使用");
            return;
        }
        
        Group group = (Group) sender.getSubject();
        long groupId = group.getId();
        OnlineStatusMonitor monitor = OnlineStatusMonitor.INSTANCE;
        
        switch (action.toLowerCase()) {
            case "list":
                sender.sendMessage(monitor.getMonitorList(groupId));
                break;
            case "toggle":
            case "switch":
                boolean newStatus = monitor.toggleMonitoring();
                sender.sendMessage(String.format("🔧 监控功能已%s", newStatus ? "启用" : "禁用"));
                break;
            case "sync":
                sender.sendMessage(monitor.syncGroupStatus(groupId));
                break;
            case "stats":
                sender.sendMessage(monitor.getMonitorStats());
                break;
            default:
                sender.sendMessage("❌ 未知操作，请使用 /newboy monitor 查看帮助");
        }
    }
    
    @SubCommand
    public void monitor(CommandSender sender, String action, String parameter) {
        if (!(sender.getSubject() instanceof Group)) {
            sender.sendMessage("❌ 监控功能只能在群聊中使用");
            return;
        }
        
        Group group = (Group) sender.getSubject();
        long groupId = group.getId();
        OnlineStatusMonitor monitor = OnlineStatusMonitor.INSTANCE;
        
        switch (action.toLowerCase()) {
            case "add":
            case "subscribe":
                boolean added = Newboy.INSTANCE.getConfig().addOnlineStatusSubscribe(parameter, groupId);
                if (added) {
                    // 初始化监控状态
                    monitor.addMonitor(parameter, groupId);
                    sender.sendMessage(String.format("✅ 已添加 %s 到监控列表", parameter));
                } else {
                    sender.sendMessage(String.format("❌ %s 已在监控列表中", parameter));
                }
                break;
            case "remove":
            case "unsubscribe":
                boolean removed = Newboy.INSTANCE.getConfig().rmOnlineStatusSubscribe(parameter, groupId);
                if (removed) {
                    monitor.removeMonitor(parameter, groupId);
                    sender.sendMessage(String.format("✅ 已从监控列表移除 %s", parameter));
                } else {
                    sender.sendMessage(String.format("❌ %s 不在监控列表中", parameter));
                }
                break;
            case "check":
                String result = monitor.checkStatusChange(parameter, groupId);
                sender.sendMessage(result);
                break;
            case "list":
                if ("realtime".equalsIgnoreCase(parameter)) {
                    sender.sendMessage(monitor.getMonitorList(groupId, true));
                } else {
                    sender.sendMessage("❌ 未知参数，使用 'realtime' 查看实时状态对比");
                }
                break;
            case "interval":
                boolean success = Newboy.INSTANCE.getConfig().setOnlineStatusInterval(parameter);
                if (success) {
                    sender.sendMessage(String.format("✅ 监控间隔已设置为: %s", parameter));
                } else {
                    sender.sendMessage("❌ 无效的Cron表达式");
                }
                break;
            default:
                sender.sendMessage("❌ 未知操作，请使用 /newboy monitor 查看帮助");
        }
    }
}