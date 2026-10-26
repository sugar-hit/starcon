# 2026-10-24 finance 迁移：DELETE 行触发器里读 `NEW` ⇒ 任何物理 DELETE 都会炸（当前潜伏）

发现：2026-10-24 00:0x（batch-79 派发的 12.1.2.2 车道）。发现人：批次调度者。
影响范围：**V338 与 V340 共 5 个触发器函数**（12.1.1.1 应收单管理、12.1.2.1 应付单管理落地的迁移）。
状态：**潜伏未爆** —— 当前没有任何 Java 代码物理 DELETE 这几张表，但运行角色**有** DELETE 授权。

## 现象（当前不会红，但一旦有 DELETE 路径就会炸）

PostgreSQL 里 **DELETE 行的触发器中 `NEW` 是未赋值的 NULL 记录**。
直接读 `NEW.tenant_id` 会抛：

```
ERROR:  record "new" is not assigned yet
```

（`COALESCE(NEW.x, OLD.x)` **也救不了** —— 读 `NEW.x` 这一步就已经报错。必须按 `TG_OP` 分流。）

⚠️ **本仓库早在 2026-10-07 就把这个坑写进会话日志了**：
[`docs/sessions/2026-10/2026-10-07-inventory-bucket-detail.md:95-96`](../../sessions/2026-10/2026-10-07-inventory-bucket-detail.md)

> DELETE 行触发器里 `NEW` 未赋值：直接取 `NEW.tenant_id` 会抛「record new is not assigned yet」，
> `COALESCE(NEW.x, OLD.x)` 也救不了，要按 `TG_OP` 分流。

**⇒ V338 与 V340 踩了同一个已知的坑。** 而**同批最新的 V341 踩对了** ——
`guard_finance_receipt_allocation` 第一件事就是 `IF TG_OP = 'DELETE' THEN`（V341 第 267–273 行）。
⇒ 正确写法在本仓**已经存在**，只是前两份迁移没照抄。

## 精确范围（2026-10-24 全服务扫描结果）

扫描方法：解析 finance 服务全部迁移里的 `CREATE TRIGGER`，取出触发 DELETE 的那些，
再逐个检查其函数体**在读到 `NEW.*` 之前有没有先判 `TG_OP`**。

| 迁移 | 表 | 触发器函数 | 状态 |
| --- | --- | --- | --- |
| `V338__finance_receivable.sql` | `finance_receivable_line` | `guard_finance_receivable_amount` | ⚠️ **先读 `NEW` 后判 `TG_OP`** |
| `V338__finance_receivable.sql` | `finance_receivable_line` | `guard_finance_receivable_line_frozen` | ⚠️ **先读 `NEW` 后判 `TG_OP`** |
| `V340__finance_payable.sql` | `finance_payable_line` | `guard_finance_payable_amount` | ⚠️ **先读 `NEW` 后判 `TG_OP`** |
| `V340__finance_payable.sql` | `finance_payable_line` | `guard_finance_payable_line_frozen` | ⚠️ **先读 `NEW` 后判 `TG_OP`** |
| `V340__finance_payable.sql` | `finance_prepayment_allocation` | `guard_finance_prepayment_amount` | ⚠️ **先读 `NEW` 后判 `TG_OP`** |
| `V341__finance_receipt.sql` | `finance_receipt_allocation` | `guard_finance_receipt_allocation` | ✅ 先判 `TG_OP` |

**讽刺的是**：V338 的 `guard_finance_receivable_amount` 里**专门写了注释**
「单头已不存在（级联删除中）：无事可做」—— 作者**打算**支持级联删除，
但函数体顶部那句 `WHERE tenant_id = NEW.tenant_id` 恰好让这条路径根本走不到。

## 为什么现在还没爆

- **没有任何 Java 代码物理 DELETE 这三张表**（2026-10-24 实测：
  `grep -rn "delete from finance_" backend/services/finance/src/main/java/` 只命中
  `finance_aging_bucket`（V339，12.1.1.2 自己的表，无 DELETE 触发器）与
  `finance_payment_milestone`（V343，12.1.2.2 自己的表，**已按 `TG_OP` 分流写对**））。
- 但 **运行角色 `finance_app` 对这些表是有 DELETE 授权的**（V338/V340 的
  `GRANT SELECT,INSERT,UPDATE,DELETE ON ...`），
  **任何一条走物理删除的路径**（级联、清理、纠错脚本）都会当场炸。

## 处置方案（**不要改 V338 / V340**）

⚠️ **V338 与 V340 都已进过集成头并被应用过** ——
改它们的内容会让 **Flyway checksum 校验炸掉**（常驻库 `starcon-mig-v2` 尤其敏感）。

⇒ 正确做法是**新增一份迁移**（版本号走全局唯一分配器，**不要自己找最大号**），
里面用 `CREATE OR REPLACE FUNCTION` 把这 5 个函数替换成 `TG_OP` 分流版：

```sql
CREATE OR REPLACE FUNCTION guard_finance_payable_amount() RETURNS trigger AS $$
DECLARE
  head finance_payable%ROWTYPE;
  row_tenant uuid;
  ...
BEGIN
  -- ⚠️ DELETE 行触发器里 NEW 是未赋值的 NULL 记录，读它的字段会直接报错
  --    （PostgreSQL: record "new" is not assigned yet）⇒ 必须按 TG_OP 分流取 id，
  --    绝不能写 COALESCE(NEW.batch_id, OLD.batch_id)。
  IF TG_OP = 'DELETE' THEN
    row_tenant := OLD.tenant_id;
    ...  -- 用 OLD 的外键
  ELSE
    row_tenant := NEW.tenant_id;
    ...
  END IF;
```

`CREATE OR REPLACE` **不改** V338/V340 的 checksum ⇒ 不影响已应用过的库。

**可直接照抄的样板**：本批 12.1.2.2 车道的
`V343__finance_payment_plan.sql` 第 377–400 行
（`guard_finance_payment_batch_amount`）—— 它就是按 `TG_OP` 分流写的，
注释里也把这条纪律写清楚了。

## 派发时要注意的边界

- 这 5 个函数**分属两个已交付模块**（12.1.1.1 与 12.1.2.1），
  但**它们的表都在 finance 服务内**，可以由**一条修复车道**统一处理
  （新建一份迁移 + `CREATE OR REPLACE` 5 个函数），
  **不需要去改那两个模块的既有迁移**。
- 修复车道必须**补一条真实 DELETE 的测试**来证明修好了 ——
  照抄本仓既有纪律（见 `docs/sessions/2026-10/2026-10-08-9-3-2-2-fulfillment-routing.md`）：
  **不要用 `TRUNCATE` 证明**（`TRUNCATE` 不触发行级 DELETE 触发器，会得到一个「什么都没发生」的假绿灯），
  要用**显式 `DELETE`** 并断言它真的成功了。
