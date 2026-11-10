# 招商 Agent 服务（Python + FastAPI + LangGraph）

自然语言需求 → 现有企业候选池 → Deep Agents 招商总控与按需招聘/求租分析 → 确定性评分 → 报告 → 人工确认 → 带证据的待审核线索 → 人工审核 → 跟进。招聘信号不是求租证明；没有可追溯的有效证据时不能创建 Agent 线索。

## 目录结构

```
agent/
  app/
    main.py       FastAPI 入口与 SSE 流
    graph.py      LangGraph 工作流节点与路由
    state.py      InvestmentAgentState
    tools.py      Tool Calling：全部经 Spring Boot 业务 API（携带用户 Token）
    supervisor.py Deep Agents 总控及两个只读专业 Subagent
    priority_scoring.py 意向、匹配、可信度及加权优先级
    scoring.py    地区解析及旧评分兼容代码
    llm.py        DeepSeek（OpenAI 兼容）接入与 JSON 解析
    config.py     环境变量配置
  data/           SQLite checkpoint（gitignore）
  requirements.txt
  .env.example
```

## 运行

```powershell
cd agent
uv venv .venv
uv pip install --python .venv/Scripts/python.exe -r requirements.txt
Copy-Item .env.example .env # 填入 DEEPSEEK_API_KEY，或直接设置环境变量
& .venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8766 --loop app.loop:selector_loop
```

Spring Boot 侧配置 `app.agent.base-url`（默认 http://127.0.0.1:8766）。
Agent 只监听回环地址，由 Spring Boot 代理并鉴权，不对外暴露。

检查点默认写入本地 SQLite；设置 `AGENT_CHECKPOINT_POSTGRES_URI` 可使用 PostgreSQL，启动时自动执行 checkpoint 表初始化。Windows 上的 psycopg 异步连接不支持默认的 Proactor 事件循环，需使用上述 `--loop app.loop:selector_loop`。`DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL` 可配置模型，旧的 `LLM_BASE_URL` / `LLM_MODEL` 仍可作为兼容回退。PostgreSQL 检查点不存放业务表。

默认综合分为 `fitScore * 0.45 + intentScore * 0.40 + confidenceScore * 0.15`，四舍五入到整数。未知面积、区域、行业、预算或规模不会当作不匹配；可用 `AGENT_SCORE_WEIGHTS` 传入 JSON 覆盖完整权重组，键和组内权重必须完整且合计为 1。默认值和规则见 `app/priority_scoring.py`。招聘职位统计仅基于可用快照，缺职位 ID 时使用职位、地点、日期近似去重。

主体核验读取企业档案中最近的授权企查查 2001 核验结果；未核验、演示或超过 30 天的结果保持待核验。核验不一致、未找到或登记状态异常时评分为 HOLD；Java 在人工确认创建线索时再次检查当前核验与档案状态。采集与启用方式见[采集器说明](../collector/README.md)。

## 接口契约

### 前端 → Spring Boot（用户 Bearer Token，权限点 agent:use）

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| /api/agent/chat | POST | {conversationId, projectId, message, source=INTERNAL|EXTERNAL} → SSE 事件流（source 默认 INTERNAL；EXTERNAL 从消息中提取外部企业，匹配档案或仅凭用户信息评估） |
| /api/agent/tasks/{runId}/decision | POST | {approved} → 恢复人工审核节点，SSE 续流 |
| /api/agent/runs | GET | 当前用户最近 20 条任务 |
| /api/agent/tasks/{runId} | GET | 当前用户的指定任务（管理员可查看其他任务） |
| /api/agent/tasks/{runId}/trace | GET | {run, tools, scores} 审计与评分明细 |

任务列表支持 `status=AWAITING_REVIEW` 及 `page` 参数，每页 20 条；界面可筛选并翻页查看待确认任务。

### Spring Boot → Agent（回环内部调用，转发用户 Authorization）

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| /agent/chat | POST | {runId, conversationId, projectId, message} → SSE |
| /agent/tasks/{runId}/decision | POST | {approved} → SSE（LangGraph interrupt 恢复） |
| /health | GET | 就绪与模型配置状态 |

### Agent → Spring Boot 工具（用户 Bearer，继承权限与数据范围）

| Tool | 业务接口 |
| --- | --- |
| search_company | GET /api/enterprises?cityCode&keyword&page&size |
| get_company_detail | GET /api/enterprises/{id} |
| search_company_signals | GET /api/enterprises/{id}/signals |
| get_building_info | GET /api/projects/{id} |
| check_duplicate_lead | GET /api/leads?enterpriseId&projectId&size=1 |
| query_recruitment / search_office_rentals | 从已授权候选企业的信号中读取相应证据，不直接访问数据库 |
| create_pending_lead | POST /api/agent/leads/pending；任务确认后创建待审核线索并关联有效来源证据 |
| calculate_match_score | 本地规则引擎 priority_scoring.py，不走 HTTP |

待审核线索通过 `POST /api/leads/{id}/approve` 或 `/reject` 审核，通过后可在现有线索页添加跟进；`GET /api/leads/{id}/evidence` 返回证据。

### SSE 事件（Agent → Spring Boot 透传并落审计 → 前端）

meta / node(running|done) / tool / score / report / message / review_required / lead_created / done / error。
Spring Boot 在透传同时将 tool→agent_tool_log、score→crm_lead_score、report/lead_created/状态→agent_run 落库。

## 安全边界

- Agent 不直连 MySQL 业务表；任务经 human_review interrupt 确认后才能请求创建待审核线索，Java 再检查用户权限、任务状态、评分和证据。
- 用户 Token 逐请求转发，工具调用继承该用户的 RBAC 与数据范围；Token 仅保存在进程内存，不写入 checkpoint。
- 评分与查重为确定性算法；LLM 只负责需求解析、信号解释与报告撰写。

## 尚未完成

每次运行使用独立 checkpoint thread；同一用户同一会话最多带入最近四轮已生成的报告作为上下文，不能跨用户复用会话。58 mock 接口尚未接入自动采集，正式 58 及企查查授权响应联调尚未完成。无 DeepSeek Key 时，不能完成真实模型对话；测试使用明确标记的模拟模型。采集事件支持事务发件箱、RabbitMQ 投递与可选消费者；消费者必须显式配置有权限用户的令牌，由该用户审核待确认任务。第三方分页同步游标及完整生产部署仍待实现。

## 测试

安装测试依赖后在 `agent/` 目录运行 `uv pip install --python .venv/Scripts/python.exe pytest`，然后运行 `& .venv/Scripts/python.exe -m pytest -q tests`。PostgreSQL 恢复测试仅在设置 `TEST_CHECKPOINT_POSTGRES_URI` 时运行；其余测试使用模拟模型，不消耗模型额度。

在 Windows PowerShell、Docker Desktop 已启动且已构建根 Compose 三个应用镜像和采集器镜像时，可在项目根目录运行 `& ./agent/tests/support/run_isolated_e2e.ps1`。采集器镜像可先用 `docker compose --profile app --profile collector build collector-monitor` 构建。脚本创建独立且随机命名的容器、数据库和模拟模型，验证演示监测、两个专业分析器、人工确认、待审核线索、证据、审核、跟进、事件消费和去重，随后仅删除本次创建的测试容器和数据卷。测试记录均明确标注为非真实数据，不访问第三方模型或接口。
