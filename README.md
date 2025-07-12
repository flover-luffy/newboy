# Newboy - 傻妞一号

一个基于 Mirai Console 的多功能 QQ 机器人插件，专为 SNH48 Group 相关服务设计。

## ✨ 功能特性

### 📱 口袋48 播报
- **房间消息监控**: 实时监控指定房间的消息并转发到 QQ 群
- **开播提醒**: 自动检测成员开播状态并发送通知
- **多群组支持**: 支持同时向多个 QQ 群推送消息
- **消息过滤**: 智能过滤重复和无效消息

### 📊 在线状态监控
- **成员状态追踪**: 实时监控指定成员的在线/离线状态
- **状态变化通知**: 成员上线/下线时自动发送格式化通知
- **多成员管理**: 支持同时监控多个成员
- **历史记录**: 保存状态变化历史记录
- **灵活配置**: 支持自定义监控间隔和通知格式

### 🌐 微博监控
- **用户动态**: 监控指定用户的微博动态
- **超话监控**: 跟踪超级话题的最新内容
- **实时推送**: 新动态自动推送到指定群组

### 🛒 微店订单监控
- **订单提醒**: 监控微店新订单并及时通知
- **商品更新**: 跟踪商品信息变化
- **多店铺支持**: 支持监控多个微店账号

## 🚀 快速开始

### 环境要求
- Java 8 或更高版本
- Mirai Console 2.16.0+
- Kotlin 2.0.21+

### 安装步骤

1. **下载插件**
   ```bash
   git clone https://github.com/your-repo/newboy.git
   cd newboy
   ```

2. **编译插件**
   ```bash
   ./gradlew buildPlugin
   ```

3. **安装插件**
   - 编译完成后，插件文件位于 `build/mirai/` 目录
   - 将 `.mirai2.jar` 文件复制到 Mirai Console 的 `plugins` 目录

4. **启动 Mirai Console**
   - 首次启动会自动生成配置文件
   - 配置文件位于 `config/net.luffy.newboy/config.setting`

## ⚙️ 配置说明

### 基础配置
```ini
# 插件启用状态
enable=true

# 管理员QQ号（多个用逗号分隔）
admins=123456789,987654321

# 安全群组（仅这些群可使用管理命令）
secureGroup=111111111,222222222
```

### 口袋48 配置
```ini
# 监控间隔（Cron表达式）
pocket48_pattern=*/30 * * * * ?

# 登录方式1：使用账号密码
pocket48_account=your_account
pocket48_password=your_password

# 登录方式2：使用Token（推荐）
pocket48_token=your_token
```

### 在线状态监控配置
```ini
# 启用在线状态监控
onlineStatus_enable=true

# 监控间隔（每2分钟检查一次）
[schedule]
onlineStatus=*/2 * * * *

# 监控订阅配置
[subscribe]
onlineStatus=[{"qqGroup":123456789,"memberSubs":["成员1","成员2"]}]
```

### 微博配置
```ini
# 监控间隔
weibo_pattern=*/5 * * * * ?

# 用户订阅配置
[subscribe]
weibo_user=[{"qqGroup":123456789,"userSubs":[123456,789012]}]
weibo_superTopic=[{"qqGroup":123456789,"sTopicSubs":["话题1","话题2"]}]
```

## 🎮 命令使用

### 基础命令
- `/newboy help` - 显示帮助信息
- `/newboy status` - 查看插件运行状态
- `/newboy reload` - 重新加载插件配置

### 在线状态监控命令
- `/newboy monitor` - 显示监控功能帮助
- `/newboy monitor add <成员名>` - 添加成员到监控列表
- `/newboy monitor remove <成员名>` - 从监控列表移除成员
- `/newboy monitor list` - 查看当前监控列表
- `/newboy monitor list realtime` - 查看实时状态对比
- `/newboy monitor check <成员名>` - 查询指定成员在线状态
- `/newboy monitor toggle` - 开关监控功能
- `/newboy monitor sync` - 同步所有成员状态
- `/newboy monitor stats` - 查看监控统计信息
- `/newboy monitor interval <cron表达式>` - 设置监控间隔

### 使用示例
```
# 添加成员到监控
/newboy monitor add 张三

# 设置每5分钟检查一次
/newboy monitor interval */5 * * * *

# 查看实时状态
/newboy monitor list realtime
```

## 🔧 开发说明

### 项目结构
```
src/main/java/net/luffy/
├── Newboy.java              # 主插件类
├── Listener.java            # 消息监听器
├── command/
│   └── NewboyCommand.java   # 命令处理器
├── handler/                 # 各功能处理器
│   ├── Pocket48Handler.java
│   ├── WeiboHandler.java
│   ├── WeidianHandler.java
│   └── Xox48Handler.java
├── util/                    # 工具类
│   ├── ConfigOperator.java # 配置管理
│   ├── OnlineStatusMonitor.java # 在线状态监控
│   └── Properties.java     # 配置属性
├── model/                   # 数据模型
└── sender/                  # 消息发送器
```

### 依赖库
- **Hutool**: 5.8.38 - Java工具库
- **pinyin4j**: 2.5.0 - 中文转拼音
- **thumbnailator**: 0.4.20 - 图片处理
- **Mirai Console**: 2.16.0 - 机器人框架

### 构建命令
```bash
# 编译项目
./gradlew build

# 构建插件
./gradlew buildPlugin

# 清理构建文件
./gradlew clean
```

## 📝 更新日志

### v1.0.0
- ✨ 初始版本发布
- 🎉 支持口袋48消息播报
- 📊 新增在线状态监控功能
- 🌐 支持微博动态监控
- 🛒 支持微店订单监控
- 🎮 完整的命令系统
- ⚙️ 灵活的配置管理

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 🙏 致谢

- [Mirai](https://github.com/mamoe/mirai) - 优秀的 QQ 机器人框架
- [Hutool](https://hutool.cn/) - 强大的 Java 工具库
- 所有贡献者和用户的支持

## 📞 联系方式

- 作者: luffy
- 项目地址: [GitHub Repository](https://github.com/your-repo/newboy)
- 问题反馈: [Issues](https://github.com/your-repo/newboy/issues)

---

**注意**: 本插件仅供学习和研究使用，请遵守相关平台的使用条款和法律法规。