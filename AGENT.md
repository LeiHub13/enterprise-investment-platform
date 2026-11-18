# AGENT.md

给 AI 编码代理（以及新同学）的项目操作手册。**产品与部署细节以 [README.md](README.md) 为准；Agent 细节以 [agent/README.md](agent/README.md) 为准**。本文件只记录：怎么跑、怎么改、怎么验证、有哪些坑。

> 这个仓库在**并行开发**中（JWT、事件发件箱、待审核线索、评分 v2 等由多人推进）。动手前先 `git status`、`git log --oneline -10`，并重新读相关文件——下文的文件名/接口可能已被改动。

## 1. 这是什么

一套「全国企业资源与招商管理平台」，按业务流程串起：企业资源库 → 招商项目(楼宇) → 数据采集(招聘/求租信号) → Agent 获客工作流 → 人工审核 → CRM 线索/跟进/商机/合同/回款。

多进程构成：

| 服务 | 目录 | 技术 | 默认端口 |
| --- | --- | --- | --- |
| 业务后端 | `backend/` | Spring Boot 3.3.5 / Java 17 / MyBatis / MySQL 8 | 8080 |
| 招商 Agent | `agent/` | Python 3.13 / FastAPI / LangGraph / LangChain（DeepSeek） | 8766 |
| 采集器 | `collector/` | Python（企查查招聘 / 模拟 58） | 无（Cron/CLI） |
| 前端工作台 | `frontend/` | Vue 3 + TS + Element Plus + Vite | 5173（preview）/ 8088（容器） |
| 标讯雷达 | `bidradar/` | Python 独立服务 | 8765 |
| 泛悦独立骨架 | `panyue-agent/` | 独立库 `panyue_agent`，schema+占位 | 18080/18081（Compose） |

数据库：MySQL `enterprise_db`（主平台）。`panyue-agent/` 用**独立库**，不要把它的 SQL 跑进 `enterprise_db`。

## 2. 本地启动（当前目录名含 `~`，走 preview，不要 dev）

前置：JDK 17、Maven 3.9、Node 22、MySQL 8、Python 3.13。

```bash
# 1) 数据库：本机 MySQL 直接建库；或 docker compose up -d --wait mysql（首次执行 enterprise_db.sql）
# 2) 后端（首次或改了 Java：先停掉旧 jar 再打包，见 §7）
mvn -f backend/pom.xml -DskipTests package
java -jar backend/target/investment-platform-0.1.0-SNAPSHOT.jar      # 8080
# 3) Agent（在 agent/ 下；Windows 上推荐带 --loop）
cd agent && python -m uvicorn app.main:app --host 127.0.0.1 --port 8766 --loop app.loop:selector_loop
# 4) 前端（在 frontend/ 下）：本目录名含 ~，vite dev 会 403，用 build+preview
cd frontend && npm run build && npm run preview -- --port 5173 --strictPort
```

- 后端配置走环境变量 + `application.yml` 默认值：`DB_URL`/`MYSQL_USER`/`MYSQL_PASSWORD`(默认 root/123456)、`SERVER_PORT`、`APP_ADMIN_USERNAME/PASSWORD`、`APP_TOKEN_TTL_HOURS`、`APP_JWT_SECRET`、`COLLECTOR_TOKEN`、`AGENT_BASE_URL`、`APP_CONTACT_ENCRYPTION_KEY`。
- 管理员密码**只在首次建库生效**（已有启用管理员时重启不覆盖）。`APP_ADMIN_PASSWORD` 至少 12 字符。
- Agent 配置在 `agent/.env`（`DEEPSEEK_API_KEY`、`BUSINESS_BASE_URL`、`DEEPSEEK_BASE_URL`/`DEEPSEEK_MODEL` 或兼容的 `LLM_*`、可选 `AGENT_CHECKPOINT_POSTGRES_URI`）。无 Key 时对话不可用，但其余页面/业务可用。
- 采集器：`py -3 -m collector.sync --companies companies.sample.csv --dry-run`；真实入库需在后端设置 `COLLECTOR_TOKEN`，采集器用 `X-Collector-Token` 调用。
- 容器全套：见 `README.md` 的「容器启动招商工作台」（`docker compose --profile app up -d --build --wait`，前端 8088，MySQL 示例 13307）。

## 3. 后端约定

- **鉴权**：`TokenService` 默认签发随机不透明 Token（SHA-256 存 `sys_access_token`，`APP_TOKEN_TTL_HOURS` 默认 12h）；**配置 `APP_JWT_SECRET`（base64，≥32 字节）后改签 HS256 JWT**，服务端仍按库内令牌校验有效期/撤销。`SecurityConfiguration` 无状态。
- **授权**：方法级 `@PreAuthorize("@ss.hasPermission('xxx')")`（`PermissionService` 别名 `@ss`）；权限点来自 `sys_menu.permission_code` + `sys_role_menu`；数据范围 `sys_role_data_scope`（`ALL` / `SELF` / `OWN_LEADS`，见 `DataScopeResolver`）。
- **采集口**：`/api/collector/**` 由 `CollectorTokenFilter` 用 `X-Collector-Token` 保护，token 为空则整体禁用。
- **持久层**：Controller + `@Mapper` 接口 + `resources/mapper/*.xml`（`mapper-locations=classpath:mapper/*.xml`）。XML 在**启动时解析**，写错会启动失败。通用返回/工具：`Rows`(view/required/params/contains/trimmed)、`Paging`、`PageResult`。
- **写操作留痕**：`AuditMapper.record(actorId, operation, resourceType, resourceId)`。
- 表前缀：`ent_*`（企业/信号/标签/变更）、`crm_*`（线索/跟进/联系人/项目/商机/合同/回款/评分/观察/待审核线索）、`agent_*`（运行审计）、`sys_*`（账号/角色/菜单/令牌）。

## 4. Agent 约定（重要）

- **单图**：`agent/app/graph.py` 的 `build_graph`。`START` 按 `mode` 路由：`CHAT` 走需求解析→搜索→富化→`supervisor` 分析→评分→查重→报告→人工审核；`EVENT` 走 `load_event→route_source→analyze_recruitment|analyze_rental→verify_enterprise→associate_entity→match_building→calculate_score→lead_condition→人工审核→建待审核线索/保存观察记录`。两条分支共享评分、审核、建线索节点。
- **Agent 不直连业务库**：`tools.py` 的 `BusinessClient` 携带**当前用户 Bearer Token** 调 Spring Boot `/api`，继承该用户 RBAC 与数据范围。Token 只存进程内存（`runtime.py`），**不进 checkpoint**。
- **分析/评分**：`supervisor.py`（Deep Agents 总控 + 招聘/求租只读子代理）；`priority_scoring.py` 默认综合分 `fit*0.45 + intent*0.40 + confidence*0.15`（可用 `AGENT_SCORE_WEIGHTS` 覆盖）；`scoring.py` 为地区解析/旧评分兼容。
- **人工审核/线索**：`interrupt` 暂停；恢复走 `/api/agent/tasks/{runId}/decision`（须 `AWAITING_REVIEW`）。建线索走 `create_pending_lead → POST /api/agent/leads/pending`，审核 `/api/leads/{id}/approve|reject`，证据 `/api/leads/{id}/evidence`，Java 侧二次校验权限/评分/证据。
- **事件链路**：`/api/collector/records` 入库并写**发件箱**（`COLLECTOR_RECORD_CREATED/UPDATED/EXPIRED`）→ 可选 RabbitMQ 消费 → `AgentEventDispatchService.prepare` 登记一次运行（演示记录 `is_demo=1` 或失效/非创建类事件会被 SKIPPED）→ `/api/agent/events/dispatch` → Agent `/agent/events`（EVENT 图）。另有手动入口 `POST /api/agent/events {recordId}`（前端「事件获客」用）。
- **SSE 事件**：`meta / node / tool / score / report / message / review_required / lead_created / done / error`；后端透传同时落 `agent_tool_log`、`crm_lead_score`、`agent_run`；`observation` 事件用于前端提示观察记录。
- 改了图/节点必测（在 `agent/` 下）：
  ```bash
  python -c "from langgraph.checkpoint.memory import MemorySaver; from app.graph import build_graph; build_graph(MemorySaver()); print('GRAPH_OK')"
  ```

## 5. 前端约定

- 路由 `frontend/src/router.ts`（`RouteView` + `meta.permission`）；实际组件在 `App.vue` 按 `route.name` 渲染。**新增一个页面要三处同步**：`router.ts` 路由 + `App.vue`（import、`CustomView` 类型、`CUSTOM_VIEWS`、`viewLabels`、渲染分支）+ 后端 `sys_menu` 菜单（授予角色后 `/auth/me` 才会返回）。
- 左侧菜单来自后端 `user.menus`；权限判断用 `user.permissions`，`permissions.ts` 只是兜底映射。
- UI 统一 Element Plus 既定组件；**显式按钮、不做整卡点击、不做 autofocus**。
- 构建前必过类型检查：`npm run typecheck`（`vue-tsc --noEmit`），再 `vite build`（即 `npm run build`）。没有单测。

## 6. 数据库与迁移

- 新库：`enterprise_db.sql`；存量库：根目录 `*_upgrade.sql` **均为幂等脚本**（`IF NOT EXISTS` / 动态 `ALTER`），按需执行；新迁移记录在 `investment_schema_migration`。
- **执行顺序**以 `README.md`「本地启动」列出的顺序为准（`agent_upgrade.sql` → `data_upgrade.sql` → `event_pipeline_upgrade.sql` → `agent_score_v2_upgrade.sql` → `collector_event_outbox_upgrade.sql` → … → `agent_building_seed_upgrade.sql`）。
- **关键前提**：`data_upgrade.sql` 给 `crm_project` 加 `address/area_min/area_max/rent_price/facilities`、给 `ent_company_signal` 加 `source_record_id/confidence/expires_at`——`ProjectMapper` 和 Agent 评分/楼宇匹配依赖这些列，未执行会直接报错。
- 企业/项目主表：`ent_enterprise`、`crm_project`（一楼宇一项目，含面积/地址/配套）；采集原始记录 `collector_record`、信号 `ent_company_signal`、线索证据 `crm_lead_evidence`、观察记录 `crm_lead_observation`。

## 7. 常见坑（Windows 本机，务必看）

1. **jar 被占用**：`java -jar` 正在跑时，`mvn package` 的 `repackage` 会因无法重命名 jar 而失败并留下**瘦 jar（无 BOOT-INF/lib，几十 MB 才正常，瘦 jar ~300KB）**。改 Java 的正确顺序：**先停后端进程 → `mvn -DskipTests clean package` → 启动新 jar**。
2. **`mvn compile` 不生效**：运行时是打好的 jar；只编译不打包、不重启，改动不会生效。别信 jar 的 mtime，看 `unzip -l … | grep -c BOOT-INF/lib`。
3. **控制台是 GBK**：中文打印/回显乱码 ≠ 数据损坏，按字节或写 UTF-8 文件核对；`mysql` 用 `--default-character-set=utf8mb4`，`curl` 传中文用 `--data-binary @file`，Python 设 `PYTHONUTF8=1`。
4. **`.env.*` 是 Windows DPAPI 密文**（如 `.env.admin-password`、`.env.contact-key`，UTF-16LE 加密），不是明文；用 PowerShell `ProtectedData.Unprotect` 解；**绝不提交、绝不写进 yaml**。
5. **端口**：8080（后端）、8766（Agent）、5173（前端）、3306（MySQL）、8765（标讯）。8080/3306 可能被别的程序占用，起服务前 `netstat -ano | findstr :8080` 查 PID 归属，**别杀别人的进程**；必要时 `SERVER_PORT`/`DB_URL` 改端口并同步前端代理。
6. **Agent 的 NO_PROXY**：`agent/app/config.py` 会裁剪 `NO_PROXY` 里的 `[::1]`（否则 httpx 报 `Invalid port`）；`BusinessClient` 用 `trust_env=False`。
7. **前端 dev 403**：项目目录名含 `~`，Vite dev 的文件白名单会误判 → 用 `npm run build && npm run preview`。
8. **`panyue-agent/` 独立库**（`panyue_agent`），SQL/数据不要和 `enterprise_db` 混用。
9. **Agent 只监听回环**，不对外暴露；对外统一经 Spring Boot 代理并鉴权。

## 8. 验证与交付

**静态（提交前至少跑这些）**
- 后端：`mvn -f backend/pom.xml -DskipTests package` → 看 `BUILD SUCCESS` + fat jar（~49MB，`BOOT-INF/lib` 有内容）。
- Agent：§4 的建图脚本；可跑 `python -m pytest -q tests`（用模拟模型，不耗额度）。
- 前端：`npm run build`（typecheck + vite）。
- Mapper XML 至少做一次 well-formed 校验（如 Python `xml.etree.ElementTree.parse`）。

**运行时**：默认**交用户实跑**（除非明确让你「启动」）；「全部拉起」= MySQL + 后端 + Agent + 前端并保持运行。要浏览器实测，用系统 Chrome（Playwright `channel="chrome"`），别依赖内置浏览器。

**交付纪律**
- 只删除**自己创建的**测试数据；破坏性 DB 操作（清库/批量删）**先 `mysqldump` 备份并确认**。
- 一功能一提交；提交前核对 `staged` 清单与密钥混入；`push` 先重查 `git status` 与远端新提交（多人并行）。
- 用户会审计你声称的验证结果：说清楚**跑了哪些层**（静态/编译/运行时），没跑的别声称跑过。

## 9. 先读哪些文档

- `README.md` — 部署、容器、角色与数据范围、迁移顺序。
- `agent/README.md` — Agent 目录、接口契约（前端/后端/工具三段）、SSE 事件、安全边界、测试。
- `docs/招商Agent重构计划.md` — 架构现状、目标与分阶段进度。
- `collector/README.md` — 采集口径、企查查接口、成本控制、未接入项。
- 各 `*_upgrade.sql` 头部注释 — 每个迁移解决什么问题。
