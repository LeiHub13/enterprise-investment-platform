# 数据采集服务（招聘信号 · 阶段 1）

对应《泛悦城市广场招商 Multi-Agent 系统》阶段 1：统一企业行为数据池。

```
企业候选池（企业资源库）
   ↓ 企业名称 / 统一社会信用代码
企查查招聘 API（开放平台 718：/Recruitment/GetList，按企业查询）
   ↓ 招聘快照标准化
Spring Boot 采集接口（/api/collector/records，content_hash 去重）
   ↓
collector_record（原始记录）+ ent_company_signal（行为信号）
   ↓
Agent 评分 / 报告引用
```

## 定位（重要）

企查查招聘接口是**按已知企业查询**，不是全量发现引擎。候选企业永远来自企业资源库；
58 同城求租数据需商务合作确认，确认前不接入（保留适配层）。

## 前置条件

1. 后端启动时设置采集令牌（与 `collector/.env` 的 `COLLECTOR_TOKEN` 一致）：
   ```bash
   COLLECTOR_TOKEN=<自定义随机串> java -jar backend/target/investment-platform-0.1.0-SNAPSHOT.jar
   ```
2. 企查查招聘接口开通（企业实名 + 应用场景审核）后，在 `collector/.env` 配置
   `QCC_APP_KEY` / `QCC_SECRET_KEY`（与标讯雷达共用同一开放平台账号即可）。
   查询参数使用公开文档中的 `searchKey`，每次最多 50 条。正式账号开通后仍需核对返回封装并完成真实联调。

## 运行

```bash
py -3 -m collector.sync --companies collector/companies.sample.csv --dry-run   # 示例，不消耗企查查额度
py -3 -m collector.sync --companies collector/companies.sample.csv --max-pages 1 # 开通后按企业付费查询
py -3 -m collector.sync --companies collector/companies.sample.csv --watch --interval-hours 24
py -3 -m collector.sync --status                                     # 采集入库统计
```

企业名单 CSV：`name,uscc,tier`（uscc 可空；tier 用于分级监测规划）。

## 去重与证据

- 招聘采集生成「当日快照」，`external_id = uscc或名称:快照日期`。后端按来源、外部标识、记录类型、企业及演示标记维护独立游标；相同内容不重复入库，内容更新时创建新版本并保留旧记录。
- 信号记录关联 `source_record_id`，线索可经 `crm_lead_evidence` 回溯到招聘快照。
- 原始数据只保存获准使用的字段（职位、日期、地点等），不保存未授权原文。

采集 API 返回 `changeType`：`NEW` 新增、`UPDATED` 更新、`UNCHANGED` 内容不变、`STALE` 来源时间较旧。版本以 `recordVersion` 展示，旧版本为 `SUPERSEDED`，到期记录为 `EXPIRED`。`sourceUpdatedAt` 应使用带时区的来源更新时间；已知更新时间的记录不会被时间更早、时间缺失或同时间但内容不同的版本覆盖。来源未提供更新时间时只能按到达顺序处理，不能保证乱序保护。演示和非演示记录使用不同游标。

求租数据可提交带时区的 `expiresAt`。业务查询和评分会即时排除已过期记录；运行 `python -m collector.sync --expire-only` 可持久化一批到期状态并生成失效事件。连续采集时可加 `--expire-records`，每轮最多扫描 1000 条；应按实际数据规模安排定期运行。

## 成本控制

| 企业类型 | 建议频率 |
| --- | --- |
| 已发现求租/强信号 | 1～3 天 |
| 招聘活跃 | 7 天 |
| 普通候选 | 30 天 |
| 失效/不符合 | 暂停 |

可以手动筛选 CSV 名单分批执行，或为每家企业的监测任务设置不同间隔；CSV 中的 `tier` 目前仅作标记，不会自动改变采集频率。实际计费以开放平台当前规则为准。

## 尚未接入

- 58 同城办公室求租（等待商务合作确认能否提供检索/推送/授权联系方式）。
- `connectors/fifty_eight.py` 提供显式演示连接器和未实现的正式接口边界；演示连接器只返回标记为 demo 的输入数据，尚未接入自动采集或消息通知。可用 `python -m collector.rental_demo --company "已入库企业全称"` 预览，添加 `--upload` 才会写入标记为演示的数据。
- 招聘默认只查询 1 页，`--max-pages` 可设 1～10；每页及重试都可能产生费用。返回满页时会提示结果可能截断。

## 企业主体核验

已按 [企查查企业信息核验 2001 文档](https://openapi.qcc.com/dataApi/2001) 实现企业全称或信用代码查询。2001 与招聘 718 需分别取得接口授权；真实请求可能计费，需先确认合同及额度。提供 `QCC_APP_KEY`、`QCC_SECRET_KEY`、`COLLECTOR_TOKEN`，并对既有库执行 `enterprise_verification_upgrade.sql`。

```bash
python -m collector.verify --company "已入库企业全称"                  # 仅预览，不联网也不写库
python -m collector.verify --company "已入库企业全称" --upload-demo    # 写入明确标记的演示记录
python -m collector.verify --company "已入库企业全称" --execute        # 正式查询并入库，可能计费
```

必要时用 `--uscc` 指定企业信用代码。仅保存核验流水号、名称、信用代码、登记状态和注册地址等字段，不保存接口返回的联系人或银行信息。演示记录始终为待核验，不影响真实核验结果；真实结果保留 30 天有效期，到期后回到待核验，历史仍可查询。主体不一致、来源未找到或登记状态异常会暂停自动建线索，需人工核实；没有真实结果时不会伪称已核验。

监测任务默认不会调用 2001。只有显式设置 `QCC_VERIFY_ON_MONITOR=true` 后，正式监测才会在缺少有效核验时额外发起请求；演示任务不调用。单次核验失败会记录固定的工作进程提示，招聘采集仍继续，结果维持原状态。由于接口和每次重试均可能计费，启用前请核对授权与预算。正式账号的响应和实际费用仍需联调确认。

## RabbitMQ 事件投递

先对业务库执行 `collector_event_outbox_upgrade.sql`，并安装 `uv pip install -r collector/requirements.txt`。采集记录、信号和发件箱事件由 Java 在同一事务中保存。配置 `RABBITMQ_URL` 与 `COLLECTOR_TOKEN` 后，可运行 `python -m collector.events.publisher --watch` 持续投递，或在采集命令中添加 `--publish-events`。

发布使用持久队列和 broker confirm；中断后租约会过期并重试。事件类型区分 `COLLECTOR_RECORD_CREATED`、`COLLECTOR_RECORD_UPDATED` 和 `COLLECTOR_RECORD_EXPIRED`；同一条记录的创建与失效事件分别拥有独立事件 ID。如果消息已被 RabbitMQ 接收但确认业务 API 失败，消息可能重复，消费者按事件 ID 去重。

可选运行 `python -m collector.events.consumer --watch` 自动消费事件；需配置 `RABBITMQ_URL`、`BUSINESS_BASE_URL` 和 `AGENT_EVENT_API_TOKEN`（令牌本身，不带 `Bearer`）。令牌所属用户必须具有 `agent:use`、`enterprise:view`、`project:view` 和 `lead:view`；需要确认创建线索时还需 `lead:create`。该用户在招商 Agent 的历史任务中查看和审核结果。令牌有有效期，过期后需安全更新并重启进程；不要把令牌提交到代码库。启动消费者可能调用配置的模型并产生费用，默认不会随 Compose 自动启动。

同一事件只登记一个任务；演示、已过期和被新版本替代的记录不会自动触发模型分析。尚在运行或暂时无法连接的任务进入 `<队列>.retry`，消费者确认转存后才确认原消息；达到重试上限或任务明确失败时进入 `<队列>.failed`，需由操作人员检查。进程故障后长期处于运行中的任务会在 15 分钟后标记为错误；可在问题排除后从页面手动重新分析来源记录。任何自动任务都停在人工确认，不能自行创建线索。

## 企业监测任务

在「数据采集 → 采集器 → 监测任务」选择已入库企业、演示/正式模式、间隔和最多页数。创建后任务默认暂停；显式启用后，再运行 `python -m collector.monitor --watch` 领取到期任务。只有带 `monitor:manage` 的用户可创建、修改和启停，默认授予管理员，可由角色管理员再分配。演示模式只使用本地样例并标记为演示；正式模式按企查查授权和当前计费规则查询，采集范围仍限已知企业。

监测任务以 30 分钟租约避免并发重复领取，进程中断后可重新领取并将旧运行标记为中断；连续失败三次自动暂停，排除问题后可手动重新启用。成功或无记录的运行会保存最近完成时间、查询页数和可能截断标记；当前企查查接口未提供可验证的增量分页游标，系统仍按配置页数重新查询，不宣称覆盖全部结果。

### 容器运行

在项目根目录配置好与业务后端一致的 `COLLECTOR_TOKEN` 后，可用 `docker compose --profile app --profile collector up -d --build` 启动应用、监测进程与事件发布器。正式模式另需设置 `QCC_APP_KEY` 和 `QCC_SECRET_KEY`；演示模式无需企查查密钥。监测任务仍须在页面显式启用。服务只使用内部网络调用业务 API；Compose 的 RabbitMQ 用户名和密码会被正确编码后用于连接。

事件消费者单独使用 `automation` profile：先启动并构建上述采集器服务，再设置 `AGENT_EVENT_API_TOKEN`，执行 `docker compose --profile app --profile automation up -d --no-build collector-consumer`。同一部署应持续使用既有的数据库口令和 `APP_JWT_SECRET`；不要在重启时重新生成密钥，否则旧 JWT 会失效。消费者不会因为启动监测任务而自动启用。
