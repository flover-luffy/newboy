# 🌐 Newboy Web前端远程部署方案

## 📋 部署架构概述

```
┌─────────────────┐    HTTPS     ┌──────────────────┐    HTTP/WS    ┌─────────────────┐
│   GitHub Pages  │ ──────────► │   反向代理服务器   │ ──────────► │  Newboy插件服务器 │
│   (前端静态页面)  │              │  (Nginx/Caddy)   │              │ (Mirai Console)  │
└─────────────────┘              └──────────────────┘              └─────────────────┘
     用户访问                         SSL终止/转发                      内网服务
```

## 🚀 实施方案

### 第一阶段：前端部署到Pages服务

#### 1. 项目结构调整
```
newboy-web-admin/
├── public/
│   ├── index.html
│   └── favicon.ico
├── src/
│   ├── components/
│   ├── views/
│   ├── api/
│   │   └── client.ts          # API客户端配置
│   ├── config/
│   │   └── environment.ts     # 环境配置
│   └── main.ts
├── .env.production            # 生产环境配置
├── .env.development          # 开发环境配置
└── vite.config.ts            # Vite配置
```

#### 2. 环境配置文件

**.env.production**
```env
# 生产环境 - Pages部署
VITE_API_BASE_URL=https://your-domain.com/api
VITE_WS_BASE_URL=wss://your-domain.com/ws
VITE_APP_TITLE=Newboy管理面板
VITE_ENABLE_MOCK=false
```

**.env.development**
```env
# 开发环境 - 本地开发
VITE_API_BASE_URL=http://localhost:8080/api
VITE_WS_BASE_URL=ws://localhost:8080/ws
VITE_APP_TITLE=Newboy管理面板(开发)
VITE_ENABLE_MOCK=true
```

#### 3. API客户端配置

**src/config/environment.ts**
```typescript
export const config = {
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api',
  wsBaseUrl: import.meta.env.VITE_WS_BASE_URL || 'ws://localhost:8080/ws',
  appTitle: import.meta.env.VITE_APP_TITLE || 'Newboy管理面板',
  enableMock: import.meta.env.VITE_ENABLE_MOCK === 'true',
  
  // 认证配置
  auth: {
    tokenKey: 'newboy_token',
    refreshTokenKey: 'newboy_refresh_token',
    tokenExpireTime: 24 * 60 * 60 * 1000, // 24小时
  },
  
  // 请求配置
  request: {
    timeout: 30000,
    retryTimes: 3,
    retryDelay: 1000,
  }
};
```

**src/api/client.ts**
```typescript
import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';
import { config } from '@/config/environment';

class ApiClient {
  private instance: AxiosInstance;
  
  constructor() {
    this.instance = axios.create({
      baseURL: config.apiBaseUrl,
      timeout: config.request.timeout,
      headers: {
        'Content-Type': 'application/json',
      },
    });
    
    this.setupInterceptors();
  }
  
  private setupInterceptors() {
    // 请求拦截器 - 添加认证token
    this.instance.interceptors.request.use(
      (config) => {
        const token = localStorage.getItem('newboy_token');
        if (token) {
          config.headers.Authorization = `Bearer ${token}`;
        }
        return config;
      },
      (error) => Promise.reject(error)
    );
    
    // 响应拦截器 - 处理认证失败
    this.instance.interceptors.response.use(
      (response) => response,
      (error) => {
        if (error.response?.status === 401) {
          // Token过期，清除本地存储并跳转到登录页
          localStorage.removeItem('newboy_token');
          window.location.href = '/login';
        }
        return Promise.reject(error);
      }
    );
  }
  
  // API方法
  async get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.instance.get(url, config);
    return response.data;
  }
  
  async post<T>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.instance.post(url, data, config);
    return response.data;
  }
  
  async put<T>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.instance.put(url, data, config);
    return response.data;
  }
  
  async delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.instance.delete(url, config);
    return response.data;
  }
}

export const apiClient = new ApiClient();
```

#### 4. GitHub Pages部署配置

**.github/workflows/deploy.yml**
```yaml
name: Deploy to GitHub Pages

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    
    steps:
    - name: Checkout
      uses: actions/checkout@v3
      
    - name: Setup Node.js
      uses: actions/setup-node@v3
      with:
        node-version: '18'
        cache: 'npm'
        
    - name: Install dependencies
      run: npm ci
      
    - name: Build
      run: npm run build
      env:
        VITE_API_BASE_URL: ${{ secrets.API_BASE_URL }}
        VITE_WS_BASE_URL: ${{ secrets.WS_BASE_URL }}
        
    - name: Deploy to GitHub Pages
      uses: peaceiris/actions-gh-pages@v3
      if: github.ref == 'refs/heads/main'
      with:
        github_token: ${{ secrets.GITHUB_TOKEN }}
        publish_dir: ./dist
```

### 第二阶段：后端服务器配置

#### 1. Newboy插件Web服务器增强

**WebServerConfig.java**
```java
public class WebServerConfig {
    // Web服务器配置
    public boolean web_server_enabled = false;
    public int web_server_port = 8080;
    public String web_server_host = "0.0.0.0"; // 监听所有接口
    public String web_server_context_path = "/api";
    
    // 安全配置
    public String jwt_secret = "your-super-secret-key-change-this";
    public long jwt_expire_time = 24 * 60 * 60 * 1000; // 24小时
    public String[] allowed_origins = {"https://your-username.github.io"};
    public String[] admin_tokens = {"admin-token-1", "admin-token-2"};
    
    // SSL配置（可选）
    public boolean ssl_enabled = false;
    public String ssl_keystore_path = "";
    public String ssl_keystore_password = "";
}
```

**CorsConfig.java**
```java
@Component
public class CorsConfig {
    
    public void configureCors(Application application) {
        application.install(CORS.Feature) {
            // 允许的域名
            allowHost("your-username.github.io", schemes = listOf("https"));
            allowHost("localhost:3000", schemes = listOf("http")); // 开发环境
            
            // 允许的HTTP方法
            allowMethod(HttpMethod.GET);
            allowMethod(HttpMethod.POST);
            allowMethod(HttpMethod.PUT);
            allowMethod(HttpMethod.DELETE);
            allowMethod(HttpMethod.OPTIONS);
            
            // 允许的请求头
            allowHeader(HttpHeaders.Authorization);
            allowHeader(HttpHeaders.ContentType);
            allowHeader("X-Requested-With");
            
            // 允许携带凭证
            allowCredentials = true;
            
            // 预检请求缓存时间
            maxAgeInSeconds = 3600;
        }
    }
}
```

#### 2. JWT认证实现

**JwtAuthService.java**
```java
@Service
public class JwtAuthService {
    private final String secret;
    private final long expireTime;
    
    public JwtAuthService(WebServerConfig config) {
        this.secret = config.jwt_secret;
        this.expireTime = config.jwt_expire_time;
    }
    
    public String generateToken(String username, List<String> roles) {
        return JWT.create()
            .withSubject(username)
            .withClaim("roles", roles)
            .withIssuedAt(new Date())
            .withExpiresAt(new Date(System.currentTimeMillis() + expireTime))
            .sign(Algorithm.HMAC256(secret));
    }
    
    public boolean validateToken(String token) {
        try {
            JWT.require(Algorithm.HMAC256(secret))
                .build()
                .verify(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    public String getUsernameFromToken(String token) {
        return JWT.decode(token).getSubject();
    }
}
```

### 第三阶段：反向代理配置

#### 1. Nginx配置

**/etc/nginx/sites-available/newboy**
```nginx
server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-domain.com;
    
    # SSL证书配置
    ssl_certificate /path/to/your/cert.pem;
    ssl_certificate_key /path/to/your/private.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-RSA-AES256-GCM-SHA512:DHE-RSA-AES256-GCM-SHA512:ECDHE-RSA-AES256-GCM-SHA384:DHE-RSA-AES256-GCM-SHA384;
    ssl_prefer_server_ciphers off;
    
    # API代理
    location /api/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # CORS headers
        add_header Access-Control-Allow-Origin "https://your-username.github.io" always;
        add_header Access-Control-Allow-Methods "GET, POST, PUT, DELETE, OPTIONS" always;
        add_header Access-Control-Allow-Headers "Authorization, Content-Type, X-Requested-With" always;
        add_header Access-Control-Allow-Credentials "true" always;
        
        # 处理预检请求
        if ($request_method = 'OPTIONS') {
            return 204;
        }
    }
    
    # WebSocket代理
    location /ws/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    
    # 健康检查
    location /health {
        proxy_pass http://127.0.0.1:8080/health;
        access_log off;
    }
}
```

#### 2. Caddy配置（更简单的选择）

**Caddyfile**
```caddy
your-domain.com {
    # 自动HTTPS
    
    # API代理
    handle /api/* {
        reverse_proxy 127.0.0.1:8080
        
        header {
            Access-Control-Allow-Origin "https://your-username.github.io"
            Access-Control-Allow-Methods "GET, POST, PUT, DELETE, OPTIONS"
            Access-Control-Allow-Headers "Authorization, Content-Type, X-Requested-With"
            Access-Control-Allow-Credentials "true"
        }
    }
    
    # WebSocket代理
    handle /ws/* {
        reverse_proxy 127.0.0.1:8080
    }
    
    # 健康检查
    handle /health {
        reverse_proxy 127.0.0.1:8080
    }
}
```

### 第四阶段：安全配置

#### 1. 防火墙配置
```bash
# 只允许特定端口访问
sudo ufw allow 22/tcp      # SSH
sudo ufw allow 80/tcp      # HTTP
sudo ufw allow 443/tcp     # HTTPS
sudo ufw deny 8080/tcp     # 禁止直接访问Newboy端口
sudo ufw enable
```

#### 2. 内网访问限制
```java
// 在Newboy插件中添加IP白名单
public class SecurityConfig {
    private final String[] allowedIPs = {
        "127.0.0.1",      // 本地访问
        "::1",            // IPv6本地
        "10.0.0.0/8",     // 内网段
        "172.16.0.0/12",  // 内网段
        "192.168.0.0/16"  // 内网段
    };
    
    public boolean isAllowedIP(String clientIP) {
        // 实现IP白名单检查逻辑
        return Arrays.stream(allowedIPs)
            .anyMatch(allowed -> isIPInRange(clientIP, allowed));
    }
}
```

#### 3. 环境变量配置
```bash
# 在服务器上设置环境变量
export NEWBOY_JWT_SECRET="your-super-secret-jwt-key"
export NEWBOY_ADMIN_TOKEN="your-admin-token"
export NEWBOY_ALLOWED_ORIGINS="https://your-username.github.io"
```

## 🔧 部署步骤

### 1. 前端部署
```bash
# 1. 克隆前端项目
git clone https://github.com/your-username/newboy-web-admin.git
cd newboy-web-admin

# 2. 安装依赖
npm install

# 3. 配置环境变量
echo "VITE_API_BASE_URL=https://your-domain.com/api" > .env.production
echo "VITE_WS_BASE_URL=wss://your-domain.com/ws" >> .env.production

# 4. 构建项目
npm run build

# 5. 推送到GitHub（自动部署到Pages）
git add .
git commit -m "Deploy to GitHub Pages"
git push origin main
```

### 2. 后端服务器配置
```bash
# 1. 更新Newboy插件
# 添加Web服务器支持和API接口

# 2. 配置反向代理
sudo apt update
sudo apt install nginx
sudo systemctl enable nginx

# 3. 配置SSL证书（使用Let's Encrypt）
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com

# 4. 启动服务
sudo systemctl restart nginx
```

### 3. 域名和DNS配置
```
# DNS记录配置
A    your-domain.com    -> 你的服务器IP
AAAA your-domain.com    -> 你的服务器IPv6（可选）
```

## 📊 监控和维护

### 1. 日志监控
```bash
# Nginx访问日志
tail -f /var/log/nginx/access.log

# Nginx错误日志
tail -f /var/log/nginx/error.log

# Newboy插件日志
tail -f logs/latest.log
```

### 2. 性能监控
```bash
# 系统资源监控
htop
iotop
netstat -tulpn

# 服务状态检查
sudo systemctl status nginx
sudo systemctl status newboy
```

### 3. 自动化脚本

**deploy.sh**
```bash
#!/bin/bash
# 自动部署脚本

set -e

echo "开始部署Newboy Web管理界面..."

# 更新前端
echo "更新前端代码..."
cd /path/to/newboy-web-admin
git pull origin main
npm ci
npm run build

# 重启服务
echo "重启服务..."
sudo systemctl reload nginx
sudo systemctl restart newboy

echo "部署完成！"
echo "前端地址: https://your-username.github.io/newboy-web-admin"
echo "API地址: https://your-domain.com/api"
```

## 🚨 故障排除

### 常见问题

1. **CORS错误**
   - 检查Nginx配置中的CORS头
   - 确认前端域名在后端白名单中

2. **WebSocket连接失败**
   - 检查代理配置中的Upgrade头
   - 确认防火墙允许WebSocket连接

3. **认证失败**
   - 检查JWT密钥配置
   - 确认token未过期

4. **API请求超时**
   - 检查服务器负载
   - 调整Nginx代理超时设置

### 调试命令
```bash
# 测试API连接
curl -X GET https://your-domain.com/api/health

# 测试CORS
curl -H "Origin: https://your-username.github.io" \
     -H "Access-Control-Request-Method: GET" \
     -X OPTIONS https://your-domain.com/api/config

# 检查SSL证书
openssl s_client -connect your-domain.com:443 -servername your-domain.com
```

## 💡 优化建议

### 性能优化
- 启用Gzip压缩
- 配置浏览器缓存
- 使用CDN加速静态资源
- 实现API响应缓存

### 安全加固
- 定期更新SSL证书
- 实现API限流
- 添加请求日志审计
- 使用WAF防护

### 可用性提升
- 配置健康检查
- 实现自动故障转移
- 添加监控告警
- 定期备份配置

---

**总结**: 通过这个方案，你可以将前端部署到GitHub Pages等免费服务上，通过反向代理安全地连接到部署了Newboy插件的服务器，实现完全的前后端分离和远程管理。