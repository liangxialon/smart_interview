# 智面Rush · AI 智能模拟面试平台

> 面向 Java 后端求职者的 AI 驱动模拟面试系统，支持简历诊断、AI 自适应面试、RAG 增强评分、面试报告导出。

🔗 演示地址：http://59.110.243.60:3000/

---

## 功能介绍

| 模块 | 说明 |
|------|------|
| 简历诊断 | 上传 PDF 简历，AI 自动解析并生成诊断报告和结构化评分 |
| AI 模拟面试 | 大模型扮演 BAT 高级面试官，根据简历和难度等级自适应提问 |
| RAG 增强评分 | 题目向量化后与题库匹配，命中则注入标准答案辅助 AI 评分 |
| 面试报告 | 面试结束后自动生成含逐题评分的报告，支持 PDF 导出 |

---

## 技术栈

| 分类 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3、MyBatis-Plus |
| 数据库 | MySQL 8 |
| 缓存 | Redis |
| 消息队列 | RabbitMQ |
| AI 接入 | DashScope（通义千问）、text-embedding-v3 |
| 推流协议 | SSE（Server-Sent Events） |
| PDF 处理 | Apache PDFBox（解析）、iText7（生成） |
| 接口文档 | Knife4j（Springdoc OpenAPI3） |
| 对象存储 | 阿里云 OSS |

---

## 核心设计

### 1. 双轨异步架构

简历处理涉及 PDF 解析、AI 分析、AI 评分三个耗时步骤，同步执行需等待 10s 以上。

```
上传接口 (200ms 返回)
    │
    ├── 前台：SSE 流式推送 AI 分析结果（用户即时看到逐字输出）
    │
    └── 后台：RabbitMQ 三队列异步流水线
            resume_parse_queue  → PDF 文本提取
            resume_score_queue  → AI 结构化评分
            interview_score_queue → 每轮面试评分
```

两条链路解耦，互不阻塞，接口响应从 10s+ 降至 200ms。

---

### 2. RAG 检索增强评分

纯大模型评分存在主观性强、标准不一的问题，引入 RAG 提升评分准确性：

```
AI 提问
  → 提取题目文本
  → text-embedding-v3 生成 1024 维向量
  → 与内存题库（CopyOnWriteArrayList）做余弦相似度计算
  → 相似度 >= 0.75：注入标准答案到评分 Prompt
  → 相似度 < 0.75：AI 仅凭自身能力评分（降级处理）
```

题库约 200 道，向量数据直接存 MySQL，启动时加载至内存，查询延迟可忽略，无需引入 Milvus 等向量数据库。

---

### 3. 滑动窗口上下文管理

用 Redis List 管理多轮对话历史，控制 Token 消耗：

- 每轮对话结束后 `rightPushAll` 追加消息
- `trim(-40, -1)` 只保留最近 40 条
- TTL 360 分钟，过期自动释放
- 每次出题前拼接历史记录进 Prompt，保持多轮连贯

---

### 4. 三级拦截器认证链

```
RefreshInterceptor (order=0)
  → 提取 token（Header 或 URL 参数）
  → 解析 JWT，从 Redis 加载用户信息至 ThreadLocal
  → 刷新 TTL（续签）

LoginInterceptor (order=1)
  → 检查 ThreadLocal，无用户则返回 401

AdminInterceptor (order=2)
  → 检查 @AdminRequired 注解，验证 role=1
```

JWT 负责签发验证，Redis 负责会话存储和续期，支持主动登出。

---

## 快速启动

### 环境要求

- JDK 17+
- MySQL 8.0+
- Redis 6.0+
- RabbitMQ 3.x

### 步骤

**1. 克隆项目**

```bash
git clone https://github.com/liangxia/smart_interview.git
cd smart_interview
```

**2. 初始化数据库**

```bash
# 执行 sql 目录下的建表脚本
mysql -u root -p < sql/smart_interview.sql
```

**3. 修改配置**

编辑 `src/main/resources/application.yml`，填入以下配置：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/smart_interview
    username: your_username
    password: your_password
  redis:
    host: localhost
    port: 6379
  rabbitmq:
    host: localhost
    port: 5672

dashscope:
  api-key: your_dashscope_api_key   # 阿里云 DashScope API Key

aliyun:
  oss:
    access-key-id: your_access_key
    access-key-secret: your_secret
    bucket-name: your_bucket
    endpoint: your_endpoint
```

**4. 启动**

```bash
mvn spring-boot:run
```

**5. 接口文档**

启动后访问：http://localhost:8080/doc.html

---

## 项目结构

```
smart_interview
├── src/main/java/com/zhimian
│   ├── controller/        # 接口层
│   ├── service/           # 业务逻辑
│   ├── manager/           # 公共组件（ChatContextManager、PromptManager）
│   ├── mapper/            # 数据访问（MyBatis-Plus）
│   ├── listener/          # RabbitMQ 消费者
│   ├── interceptor/       # 三级认证拦截器
│   └── config/            # 配置类
├── src/main/resources
│   ├── prompts/           # Prompt 模板（.st 文件）
│   ├── fonts/             # iText7 中文字体
│   └── application.yml
└── sql/                   # 建表脚本
```

---

## 可扩展方向

- **向量检索**：题库规模增大后引入 ElasticSearch dense_vector 或 Milvus 替代内存遍历
- **消息幂等**：MQ 消费端加 Redis SETNX 防止重复评分
- **死信队列**：评分失败消息进入死信队列兜底，避免静默丢失
- **本地缓存**：Caffeine 做 L1 缓存减少 Redis 网络开销
- **限流**：大模型 API 调用加令牌桶限流，防止 QPS 超限
