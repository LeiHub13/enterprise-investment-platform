# 泛悦城市广场智能招商 Agent

本目录是首个交付物：独立项目骨架、MySQL 初始化脚本、Docker Compose，以及 Spring Boot 和 FastAPI 的最小健康接口。它与上一级已有的全国企业资源库使用不同的 Compose 项目、数据卷、数据库和默认端口。

项目目标是以南京鼓楼区、100～300㎡的办公需求为目标，未来从已有企业库获取候选企业，并结合经授权的招聘、求租和企业核验信息，生成有证据、需人工审核的招商建议。目前没有连接任何外部数据源，也没有生成真实线索。

## 目录

| 路径 | 作用 |
| --- | --- |
| `db/init/001_schema.sql` | 10 张核心表、约束及一条楼宇目标配置 |
| `backend/business-api` | Spring Boot 3 / Java 17 业务服务骨架 |
| `services/agent` | FastAPI Agent 服务骨架 |
| `services/collector` | 数据采集服务预留目录 |
| `frontend/web` | 后续 Vue 招商工作台预留目录 |
| `docker-compose.yml` | MySQL、Redis、RabbitMQ 和两个服务的本地编排 |
| `docs/database.md` | 表关系、数据含义及边界 |
| `docs/source-validation.md` | 数据源验证清单与待确认事项 |

## 本机启动

需要 Docker Desktop（含 Compose）和可获取所需镜像及构建依赖的网络。以下命令在 PowerShell 中从本目录执行：

```powershell
Copy-Item .env.example .env
notepad .env
# 将 .env 里的三个示例口令改为本机开发口令，保存后继续。
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
```

Docker 首次创建本项目的 MySQL 数据卷时会自动建库 `panyue_agent` 并执行 `db/init/001_schema.sql`；已有数据卷不会再次执行初始化脚本。脚本使用 `CREATE TABLE IF NOT EXISTS` 便于初始运行和重入，但不会迁移已存在表的结构；以后的结构变更需要单独的升级脚本。请不要把它执行到已有的 `enterprise_db`。

默认端口只绑定到本机，且可在 `.env` 调整：

| 服务 | 本机地址或端口 |
| --- | --- |
| Spring Boot 存活检查 | `http://127.0.0.1:18080/api/health` |
| Spring Boot 数据库连通检查 | `http://127.0.0.1:18080/api/health/ready` |
| FastAPI 存活检查 | `http://127.0.0.1:18081/internal/health` |
| MySQL | `127.0.0.1:13306` |
| Redis | `127.0.0.1:16379` |
| RabbitMQ / 管理页面 | `127.0.0.1:15673` / `http://127.0.0.1:25672` |

RabbitMQ 开发账号为 `panyue`，口令来自 `.env`。检查接口：

```powershell
Invoke-RestMethod http://127.0.0.1:18080/api/health
Invoke-RestMethod http://127.0.0.1:18080/api/health/ready
Invoke-RestMethod http://127.0.0.1:18081/internal/health
```

`/api/health` 只表示 Java 进程可响应；`/api/health/ready` 会执行 MySQL `SELECT 1`，失败时返回 503。两个检查均不代表 Redis、RabbitMQ、采集源或未来业务流程已经可用。

查看新库中的表：

```powershell
docker compose exec mysql mysql -u panyue -p panyue_agent
```

输入 `.env` 中的 `PANYUE_MYSQL_PASSWORD` 后，在 MySQL 提示符执行 `SHOW TABLES;`。退出使用 `exit`。停止服务使用 `docker compose stop`；这不会删除数据卷。

## 当前边界

- Java 和 Python 只提供健康检查，尚无企业、线索、审核、跟进或 Agent 业务接口；也没有自动调度、评分、LangGraph 和真实采集。
- `t_company.enterprise_id`、`t_lead.crm_lead_id` 只是未来与现有企业库及 CRM 建立关联的预留字段，不会自动导入、同步或创建 CRM 线索。后续应通过现有业务服务的授权接口对接。
- 已写入的楼宇面积是本次招商的目标范围，并非经核实的可租面积；地址、租金等尚未提供，保持未知。
- PostgreSQL Checkpoint 和 MinIO 属于后续阶段，当前 Compose 没有启动。Redis 和 RabbitMQ 已编排，但服务骨架暂未连接它们。
- `.env.example` 中的口令仅是本机示例，`.env` 不应提交；当前骨架未实现登录、权限、服务间认证或生产部署配置。

数据源开通、接口费用和数据授权需按 `docs/source-validation.md` 核实。未取得授权前不调用外部 API、不假设 58 提供目标数据。
