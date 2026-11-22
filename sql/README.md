# MySQL 脚本

| 文件 | 用途 |
| --- | --- |
| `enterprise_db.sql` | 全量建库（RBAC、CRM、Agent、采集与事件流水线）；Docker 首次初始化自动执行 |
| `seed_demo_data.sql` | 演示企业与 CRM 数据（可选，在已有 schema 上执行） |
| `seed_collector_demo.sql` | 采集器演示记录与信号（可选） |
| `create-role-users.sql` | 为 DATA_STEWARD / INVESTMENT_MANAGER / INVESTMENT_OFFICER 创建初始账号 |

本机 MySQL：

```powershell
mysql -u root -p < sql/enterprise_db.sql
# 可选演示数据
mysql -u root -p enterprise_db < sql/seed_demo_data.sql
```