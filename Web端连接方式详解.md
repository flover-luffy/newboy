# 🌐 Web端连接插件的具体方式详解

## 📡 连接架构概览

当你将Newboy插件部署到具有公网IP的机器上时，Web端通过以下方式连接并管理插件：

```
┌─────────────────┐    HTTPS/WSS     ┌──────────────────┐    HTTP/WS    ┌─────────────────┐
│   用户浏览器     │ ──────────────► │   反向代理服务器   │ ──────────► │  Newboy插件服务器 │
│ (GitHub Pages)  │                 │  (Nginx/Caddy)   │              │ (公网IP:8080)   │
└─────────────────┘                 └──────────────────┘              └─────────────────┘
     前端界面                         SSL终止/转发                      内网服务
```

## 🔌 具体连接方式

### 1. HTTP API连接

**连接流程**:
```javascript
// 前端API客户端配置
const apiClient = axios.create({
  baseURL: 'https://your-domain.com/api',  // 通过域名访问
  // 或者直接使用IP: 'https://123.456.789.123/api'
  timeout: 3000,  // 优化为3秒超时，确保快速响应
  headers: {
    'Content-Type': 'application/json'
  }
});

// 添加认证token
apiClient.interceptors.request.use(config => {
  const token = localStorage.getItem('newboy_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});
```

**API调用示例**:
```javascript
// 获取系统状态
const getSystemStatus = async () => {
  const response = await apiClient.get('/system/status');
  return response.data;
};

// 添加Pocket48房间订阅
const addPocket48Room = async (roomData) => {
  const response = await apiClient.post('/pocket48/rooms', roomData);
  return response.data;
};

// 更新配置
const updateConfig = async (config) => {
  const response = await apiClient.put('/config', config);
  return response.data;
};
```

### 2. WebSocket实时连接

**连接建立**:
```javascript
// WebSocket连接管理
class WebSocketManager {
  constructor() {
    this.ws = null;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 5;
    this.reconnectInterval = 5000;
  }
  
  connect() {
    const wsUrl = 'wss://your-domain.com/ws';
    // 或者直接使用IP: 'wss://123.456.789.123/ws'
    
    this.ws = new WebSocket(wsUrl);
    
    this.ws.onopen = () => {
      console.log('WebSocket连接已建立');
      this.reconnectAttempts = 0;
      
      // 发送认证信息
      const token = localStorage.getItem('newboy_token');
      this.send('auth', { token });
    };
    
    this.ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      this.handleMessage(message);
    };
    
    this.ws.onclose = () => {
      console.log('WebSocket连接已断开');
      this.reconnect();
    };
    
    this.ws.onerror = (error) => {
      console.error('WebSocket错误:', error);
    };
  }
  
  send(type, data) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type, data, timestamp: Date.now() }));
    }
  }
  
  handleMessage(message) {
    switch (message.type) {
      case 'log':
        // 处理实时日志
        this.onLog(message.data);
        break;
      case 'stats':
        // 处理统计数据更新
        this.onStatsUpdate(message.data);
        break;
      case 'config_changed':
        // 处理配置变更通知
        this.onConfigChanged(message.data);
        break;
    }
  }
  
  reconnect() {
    if (this.reconnectAttempts < this.maxReconnectAttempts) {
      this.reconnectAttempts++;
      console.log(`尝试重连... (${this.reconnectAttempts}/${this.maxReconnectAttempts})`);
      setTimeout(() => this.connect(), this.reconnectInterval);
    }
  }
}
```

## 🛡️ 安全认证机制

### 1. JWT Token认证

**登录流程**:
```javascript
// 用户登录
const login = async (username, password) => {
  try {
    const response = await fetch('https://your-domain.com/api/auth/login', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ username, password })
    });
    
    const data = await response.json();
    
    if (data.success) {
      // 保存token
      localStorage.setItem('newboy_token', data.token);
      localStorage.setItem('newboy_refresh_token', data.refreshToken);
      
      // 设置token过期时间
      const expireTime = Date.now() + (24 * 60 * 60 * 1000); // 24小时
      localStorage.setItem('newboy_token_expire', expireTime.toString());
      
      return true;
    }
  } catch (error) {
    console.error('登录失败:', error);
    return false;
  }
};

// Token自动刷新
const refreshToken = async () => {
  const refreshToken = localStorage.getItem('newboy_refresh_token');
  if (!refreshToken) return false;
  
  try {
    const response = await fetch('https://your-domain.com/api/auth/refresh', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${refreshToken}`
      }
    });
    
    const data = await response.json();
    if (data.success) {
      localStorage.setItem('newboy_token', data.token);
      return true;
    }
  } catch (error) {
    console.error('Token刷新失败:', error);
  }
  
  return false;
};
```

### 2. 请求拦截和错误处理

```javascript
// 响应拦截器 - 处理认证失败
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;
    
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true;
      
      // 尝试刷新token
      const refreshed = await refreshToken();
      if (refreshed) {
        // 重新发送原始请求
        const newToken = localStorage.getItem('newboy_token');
        originalRequest.headers.Authorization = `Bearer ${newToken}`;
        return apiClient(originalRequest);
      } else {
        // 刷新失败，跳转到登录页
        localStorage.clear();
        window.location.href = '/login';
      }
    }
    
    return Promise.reject(error);
  }
);
```

## 🌐 网络配置方案

### 方案一：使用域名 (推荐)

**优势**: 更安全、更专业、支持SSL证书

**配置步骤**:
```bash
# 1. 购买域名 (如: newboy-admin.com)
# 2. 配置DNS记录
A    newboy-admin.com    -> 你的公网IP
AAAA newboy-admin.com    -> 你的IPv6地址 (可选)

# 3. 配置Nginx
server {
    listen 443 ssl http2;
    server_name newboy-admin.com;
    
    # SSL证书 (Let's Encrypt免费)
    ssl_certificate /etc/letsencrypt/live/newboy-admin.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/newboy-admin.com/privkey.pem;
    
    # API代理到插件服务器
    location /api/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    
    # WebSocket代理
    location /ws/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

**前端配置**:
```typescript
// .env.production
VITE_API_BASE_URL=https://newboy-admin.com/api
VITE_WS_BASE_URL=wss://newboy-admin.com/ws
```

### 方案二：直接使用公网IP

**优势**: 无需购买域名，配置简单
**劣势**: 无法使用免费SSL证书，安全性较低

**配置步骤**:
```bash
# 1. 配置Nginx (HTTP模式)
server {
    listen 80;
    server_name _;
    
    # API代理
    location /api/ {
        proxy_pass http://127.0.0.1:8080/;
        # ... 其他配置
    }
    
    # WebSocket代理
    location /ws/ {
        proxy_pass http://127.0.0.1:8080/;
        # ... 其他配置
    }
}
```

**前端配置**:
```typescript
// .env.production
VITE_API_BASE_URL=http://123.456.789.123/api
VITE_WS_BASE_URL=ws://123.456.789.123/ws
```

### 方案三：自签名SSL证书

**适用场景**: 想要HTTPS但不想购买域名

```bash
# 生成自签名证书
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout /etc/ssl/private/newboy.key \
  -out /etc/ssl/certs/newboy.crt

# Nginx配置
server {
    listen 443 ssl;
    server_name _;
    
    ssl_certificate /etc/ssl/certs/newboy.crt;
    ssl_certificate_key /etc/ssl/private/newboy.key;
    
    # ... 其他配置
}
```

**注意**: 浏览器会显示安全警告，需要手动信任证书

## 🔧 插件端配置

### 1. Web服务器配置

**config.setting中添加**:
```properties
# Web服务器配置
web_server_enabled=true
web_server_port=8080
web_server_host=0.0.0.0
web_server_context_path=/

# 安全配置
jwt_secret=your-super-secret-key-change-this-immediately
jwt_expire_time=86400000
allowed_origins=https://your-username.github.io,https://newboy-admin.com
admin_username=admin
admin_password=your-secure-password

# CORS配置
cors_enabled=true
cors_max_age=3600
```

### 2. 防火墙配置

```bash
# 开放必要端口
sudo ufw allow 22/tcp      # SSH
sudo ufw allow 80/tcp      # HTTP
sudo ufw allow 443/tcp     # HTTPS

# 如果直接暴露插件端口 (不推荐)
# sudo ufw allow 8080/tcp

# 启用防火墙
sudo ufw --force enable

# 查看状态
sudo ufw status
```

### 3. 系统服务配置

**创建systemd服务**:
```bash
# /etc/systemd/system/newboy.service
[Unit]
Description=Newboy Mirai Plugin
After=network.target

[Service]
Type=simple
User=newboy
WorkingDirectory=/opt/newboy
ExecStart=/usr/bin/java -jar mirai-console-wrapper.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
# 启用服务
sudo systemctl enable newboy
sudo systemctl start newboy
sudo systemctl status newboy
```

## 📊 连接状态监控

### 1. 前端连接状态显示

```vue
<template>
  <div class="connection-status">
    <el-tag :type="connectionStatus.type" size="small">
      <el-icon><Connection /></el-icon>
      {{ connectionStatus.text }}
    </el-tag>
    <span class="ping-time" v-if="pingTime > 0">
      延迟: {{ pingTime }}ms
    </span>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';

const isApiConnected = ref(false);
const isWsConnected = ref(false);
const pingTime = ref(0);

const connectionStatus = computed(() => {
  if (isApiConnected.value && isWsConnected.value) {
    return { type: 'success', text: '已连接' };
  } else if (isApiConnected.value) {
    return { type: 'warning', text: 'API已连接' };
  } else {
    return { type: 'danger', text: '连接断开' };
  }
});

// 定期检查连接状态
const checkConnection = async () => {
  try {
    const start = Date.now();
    await apiClient.get('/health');
    pingTime.value = Date.now() - start;
    isApiConnected.value = true;
  } catch (error) {
    isApiConnected.value = false;
    pingTime.value = 0;
  }
};

onMounted(() => {
  // 每30秒检查一次连接
  setInterval(checkConnection, 30000);
  checkConnection();
});
</script>
```

### 2. 后端健康检查

```java
@RestController
public class HealthController {
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        health.put("uptime", getUptime());
        health.put("version", getVersion());
        
        // 检查各个组件状态
        health.put("components", Map.of(
            "database", checkDatabase(),
            "mirai", checkMirai(),
            "websocket", checkWebSocket()
        ));
        
        return ResponseEntity.ok(health);
    }
    
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("pong");
    }
}
```

## 🚨 故障排除

### 常见连接问题

**1. 无法连接到API**
```bash
# 检查服务状态
sudo systemctl status newboy
sudo systemctl status nginx

# 检查端口监听
sudo netstat -tlnp | grep :8080
sudo netstat -tlnp | grep :80
sudo netstat -tlnp | grep :443

# 检查防火墙
sudo ufw status

# 测试本地连接
curl http://localhost:8080/api/health
```

**2. CORS错误**
```bash
# 检查Nginx配置
sudo nginx -t
sudo tail -f /var/log/nginx/error.log

# 测试CORS
curl -H "Origin: https://your-frontend-domain.com" \
     -H "Access-Control-Request-Method: GET" \
     -X OPTIONS https://your-domain.com/api/health
```

**3. WebSocket连接失败**
```bash
# 检查WebSocket代理配置
# 确保Nginx配置包含Upgrade头
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";

# 测试WebSocket连接
wscat -c wss://your-domain.com/ws
```

**4. SSL证书问题**
```bash
# 检查证书有效性
openssl s_client -connect your-domain.com:443 -servername your-domain.com

# 更新证书
sudo certbot renew
sudo systemctl reload nginx
```

### 调试工具

**浏览器开发者工具**:
- Network标签页: 查看API请求状态
- Console标签页: 查看JavaScript错误
- Application标签页: 查看localStorage中的token

**服务器端日志**:
```bash
# Nginx日志
sudo tail -f /var/log/nginx/access.log
sudo tail -f /var/log/nginx/error.log

# Newboy插件日志
tail -f /opt/newboy/logs/latest.log

# 系统日志
sudo journalctl -u newboy -f
sudo journalctl -u nginx -f
```

## 📈 性能优化

### 1. 连接池配置

```javascript
// 前端HTTP连接池
const apiClient = axios.create({
  baseURL: 'https://your-domain.com/api',
  timeout: 30000,
  maxRedirects: 3,
  // 连接复用
  httpAgent: new http.Agent({ keepAlive: true }),
  httpsAgent: new https.Agent({ keepAlive: true })
});
```

### 2. 请求缓存

```javascript
// API响应缓存
const cache = new Map();
const CACHE_TTL = 5 * 60 * 1000; // 5分钟

const cachedRequest = async (url, options = {}) => {
  const cacheKey = `${url}_${JSON.stringify(options)}`;
  const cached = cache.get(cacheKey);
  
  if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
    return cached.data;
  }
  
  const response = await apiClient.get(url, options);
  cache.set(cacheKey, {
    data: response.data,
    timestamp: Date.now()
  });
  
  return response.data;
};
```

### 3. WebSocket心跳

```javascript
// WebSocket心跳机制
class WebSocketManager {
  constructor() {
    this.heartbeatInterval = 30000; // 30秒
    this.heartbeatTimer = null;
  }
  
  startHeartbeat() {
    this.heartbeatTimer = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.send('ping', { timestamp: Date.now() });
      }
    }, this.heartbeatInterval);
  }
  
  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }
}
```

---

## 📝 总结

Web端通过以下方式连接到部署在公网IP机器上的Newboy插件：

1. **HTTP API连接**: 使用HTTPS协议进行RESTful API调用
2. **WebSocket实时连接**: 建立持久连接接收实时数据
3. **JWT认证**: 确保连接安全性
4. **反向代理**: 通过Nginx/Caddy提供SSL终止和负载均衡
5. **CORS配置**: 允许跨域访问
6. **健康检查**: 监控连接状态和服务健康

这种架构既保证了安全性，又提供了良好的用户体验和系统可维护性。