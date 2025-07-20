package net.luffy;

import net.luffy.util.CommandOperator;
import net.luffy.command.CustomPrefixCommand;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.contact.Member;
import net.mamoe.mirai.contact.User;
import net.mamoe.mirai.event.EventHandler;
import net.mamoe.mirai.event.ListeningStatus;
import net.mamoe.mirai.event.SimpleListenerHost;
import net.mamoe.mirai.event.events.GroupMessageEvent;
import net.mamoe.mirai.event.events.MemberJoinEvent;
import net.mamoe.mirai.event.events.UserMessageEvent;
import net.mamoe.mirai.message.data.At;
import net.mamoe.mirai.message.data.Message;

public class Listener extends SimpleListenerHost {

    public CommandOperator operator = new CommandOperator();

    @EventHandler()
    public ListeningStatus onGroupMessage(GroupMessageEvent event) {
        Member sender = event.getSender();
        Group group = event.getGroup();
        String message = event.getMessage().contentToString();

        // 处理自定义前缀命令 (! 或 #)
        if (message.startsWith("!") || message.startsWith("#")) {
            Message customResponse = CustomPrefixCommand.handleGroupCommand(message, group, sender.getId());
            if (customResponse != null) {
                group.sendMessage(customResponse);
                return ListeningStatus.LISTENING;
            }
        }
        

        
        if (message.startsWith("/")) {
            // 排除Mirai Console注册的命令，避免冲突
            String[] parts = message.split(" ");
            String command = parts[0].substring(1); // 移除 "/"
            
            // 如果是注册的Console命令，不处理，让Console命令系统处理
            if ("newboy".equals(command)) {
                return ListeningStatus.LISTENING;
            }
            
            Message m = operator.executePublic(message.split(" "), group, sender.getId());
            if (m != null)
                group.sendMessage(m);
        }

        return ListeningStatus.LISTENING;
    }

    @EventHandler()
    public ListeningStatus onUserMessage(UserMessageEvent event) {
        User sender = event.getSender();
        String message = event.getMessage().contentToString();

        // 处理自定义前缀命令 (! 或 #)
        if (message.startsWith("!") || message.startsWith("#")) {
            Message customResponse = CustomPrefixCommand.handlePrivateCommand(message, sender);
            if (customResponse != null) {
                try {
                    sender.sendMessage(customResponse);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return ListeningStatus.LISTENING;
            }
        }
        
        if (message.startsWith("/")) {
            // 排除Mirai Console注册的命令，避免冲突
            String[] parts = message.split(" ");
            String command = parts[0].substring(1); // 移除 "/"
            
            // 如果是注册的Console命令，不处理，让Console命令系统处理
            if ("newboy".equals(command)) {
                return ListeningStatus.LISTENING;
            }
            
            try {
                Message m = operator.executePrivate(message, event);
                if (m != null) {
                    sender.sendMessage(m);
                }
            } catch (Exception e) {
                // 记录错误但不中断程序运行
                e.printStackTrace();
                try {
                    sender.sendMessage("处理命令时发生错误，请稍后重试");
                } catch (Exception sendError) {
                    // 如果连错误消息都发送失败，只记录日志
                    sendError.printStackTrace();
                }
            }
        }

        return ListeningStatus.LISTENING;
    }

    // 进群欢迎功能已移除
}
