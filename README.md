# 智面Rush · AI 智能模拟面试平台

> 面向求职者的 AI 驱动模拟面试系统，支持简历诊断、AI 自适应面试、RAG 增强评分、实时语音输入、面试报告导出。

🔗 演示地址：http://59.110.243.60:3000/

---

## 功能介绍

| 模块 | 说明 |
|------|------|
| 用户管理 | 手机验证码 + 密码双登录方式，JWT + Redis 会话管理，头像上传 |
| 简历诊断 | 上传 PDF 简历，RabbitMQ 异步解析，AI 流式生成诊断报告和优化建议 |
| AI 模拟面试 | 大模型扮演高级面试官，根据简历和难度等级自适应提问，SSE 流式对话 |
| 实时语音输入 | WebSocket + DashScope paraformer 实时语音识别，边说边转文字 |
| RAG 增强评分 | 题目向量化存入 ES，混合检索（关键词 + 语义相似度）匹配标准答案辅助评分 |
| 面试报告 | 异步评分 + 占位符模式解决时序问题，自动计算综合得分、正确率，支持 PDF 导出 |
| 薄弱项训练 | AI 分析答错题目识别薄弱领域，自动生成专项练习题，结果缓存避免重复调用 |
| 错题收藏 | 收藏典型面试题便于复习，支持取消收藏 |
| 题库管理 | 管理员 Excel 批量导入，自动生成 Embedding 向量写入 ES，分页模糊查询 |

---

## 技术栈

| 分类 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3、MyBatis-Plus |
| 数据库 | MySQL 8 |
| 缓存 | Redis |
| 消息队列 | RabbitMQ |
| 搜索引擎 | Elasticsearch 8（IK 分词 + dense_vector 向量检索）|
| AI 接入 | langchain4j + DashScope（通义千问）、text-embedding-v3 |
| 推流协议 | SSE（Server-Sent Events） |
| 实时语音 | WebSocket + DashScope paraformer-realtime-v2 |
| PDF 处理 | Apache PDFBox（解析）、iText7（生成） |
| 接口文档 | Knife4j（Springdoc OpenAPI3） |
| 对象存储 | 阿里云 OSS |
| Excel 处理 | EasyExcel |

---

## 核心设计

### 1. RAG 混合检索增强评分

纯大模型评分存在主观性强、标准不一的问题，引入 RAG 提升评分准确性：

```
AI 提问
  → 提取题目文本
  → 调 DashScope text-embedding-v3 生成 1024 维向量
  → ES function_score 混合查询：
      ├─ IK 分词关键词匹配（文本相关性）
      └─ dense_vector 余弦相似度（语义相关性）
  → 综合得分 >= 80：命中标准答案，注入评分 Prompt
  → 综合得分 < 80：AI 仅凭自身能力评分（降级处理）
```

题目导入时自动生成 Embedding 写入 ES，支持批量重建向量索引。

---

### 2. 异步评分 + 占位符模式

面试评分通过 RabbitMQ 异步处理，采用占位符模式解决时序问题：

```
用户回答问题
  → 创建 InterviewReport 占位记录（questionText 为空）
  → 发送评分消息到 interview_score_queue
  → MQ 消费者调 AI 评分（分数 + 评语 + 是否正确）
  → 更新占位记录，填充评分数据
  → 前端轮询报告接口，检测所有题目评分完成
```

占位符模式避免了前端轮询时报告记录尚未创建的问题。

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
  → 提取 token（Header 或 URL 参数，支持 SSE 场景）
  → 解析 JWT，从 Redis 加载用户信息至 ThreadLocal
  → 刷新 TTL（续签）

LoginInterceptor (order=1)
  → 检查 ThreadLocal，无用户则返回 401

AdminInterceptor (order=2)
  → 检查 @AdminRequired 注解，验证 role=1
```

JWT 负责签发验证，Redis 负责会话存储和续期，支持主动登出。

---

### 5. 实时语音识别

```
前端 WebSocket 连接 /ws/audio?token=xxx
  → 发送 PCM 音频流（16kHz 单声道）
  → 后端调 DashScope paraformer-realtime-v2 实时识别
  → 返回识别文字片段
  → 发送 "END" 结束音频流
```

Token 在 WebSocket 握手阶段通过 Redis 校验，支持前端边说边显示。

---

## 快速启动

### 环境要求

- JDK 17+
- MySQL 8.0+
- Redis 6.0+
- RabbitMQ 3.x
- Elasticsearch 8.x

### 步骤

**1. 克隆项目**

```bash
git clone https://github.com/liangxia/smart_interview.git
cd smart_interview
```

**2. 初始化数据库**

```bash
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
  elasticsearch:
    host: localhost
    port: 9200

ai:
  api-key: your_dashscope_api_key   # 阿里云 DashScope API Key（langchain4j OpenAI 兼容）
  base-url: https://dashscope.aliyuncs.com/compatible-mode/v1

dashscope:
  api-key: your_dashscope_api_key   # DashScope 原生 API Key（语音识别等）

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
├── src/main/java/com/smartinterview
│   ├── controller/          # 接口层（用户、简历、面试、报告、题库）
│   ├── service/             # 业务逻辑
│   │   ├── impl/            # 服务实现
│   │   └── ai/              # AI 相关服务（评分、简历优化、薄弱项训练等）
│   ├── common/
│   │   ├── manager/         # 公共组件（ChatContext、Prompt、RedisChatMemory）
│   │   ├── constants/       # 常量（Redis Key、RabbitMQ 队列名）
│   │   ├── util/            # 工具类（JWT、OSS、正则、简历解析）
│   │   ├── result/          # 统一返回格式
│   │   └── exception/       # 自定义异常
│   ├── config/              # 配置类（AI、ES、MQ、Redis、WebSocket、CORS 等）
│   ├── interceptor/         # 三级认证拦截器
│   ├── Listener/            # RabbitMQ 消费者（简历解析、面试评分）
│   ├── websocket/           # WebSocket 处理器（语音识别）
│   ├── entity/              # 实体类
│   ├── dto/                 # 请求参数
│   ├── vo/                  # 响应对象
│   └── mapper/              # 数据访问层（MyBatis-Plus）
├── src/main/resources
│   ├── prompts/             # Prompt 模板（.st 文件）
│   ├── fonts/               # iText7 中文字体
│   └── application.yml
└── sql/                     # 建表脚本
```

---

## 可扩展方向

- **限流**：大模型 API 调用加令牌桶限流，防止 QPS 超限
- **多轮面试回顾**：支持面试过程回放，查看完整对话时间线
