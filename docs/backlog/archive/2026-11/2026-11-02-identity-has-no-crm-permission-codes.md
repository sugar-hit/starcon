> **状态：Done（2026-11-03 销账）**
>
> - 判定类别：**已修复但条目未销账**（本条发现时属实：只有 `crm.customers` 一族被登记）
> - 根因（仍然成立）：`role_permission.permission_code` 上有指向 `permission(code)` 的
>   **外键** ⇒ 一个权限码没登记进 `permission` 表，就**没有任何角色能持有它**
>   （外键直接拒绝那次授权写入），而 `access.require(bearer, code)` 按「已认证成员持有的
>   权限码」判定 ⇒ 端点恒定 403。
> - 修复：`backend/services/identity/src/main/resources/db/migration/V442__crm_permission_codes.sql`
>   一次性登记 **21 个族 × read/update = 42 个权限码** + 21 条 `page_resource`，
>   并按 V432 / V439 同口径给存量租户的 OWNER 角色补权（新租户由 identity 的
>   `LocalBootstrap` 在建租时授予当时的全部 permission，天然齐全）。
> - 防回归：`backend/services/crm/src/test/java/com/starcon/crm/CrmPermissionCodesGuardTest.java`
>   —— 从 **crm 自己的源码**反向抽取真实用到的权限码，再去 identity 的迁移里核对登记
>   （两个方向都断言：缺码红、多登记的幽灵码也红）。
>   ⚠️ 与本条目正文「处置建议 2」建议的「跨服务读 identity 的库」不同：本实现改为
>   **构建期静态比对迁移源码**，不连对方的库 —— AGENTS.md 禁止跨库查询，且静态比对
>   能在单元测试层就跑，不必等集成测试层。
> - 实测：移除 V442 ⇒ 守卫立刻报出缺失码（`crm.auto_reply.read` 起）；加回 ⇒ 2 例全绿。
> - 结论：原缺口已补齐并有守卫钉住 ⇒ 移入 `docs/backlog/archive/2026-11/`

# identity 的 permission 表里从来没有过任何 `crm.*` 权限码 ⇒ crm 服务全部端点在真实环境恒 403

> 发现时间：2026-11-02 10:45（流水线 round 130，15.3.1.1 客户列表车道实测时发现）
> 严重度：**高（存量）** —— **代码本身是对的**，红/不通的是**部署后的授权链路**：
> crm 服务所有端点都要求一个 `crm.*` 权限码，而 identity 的 `permission` 表**一行 `crm.*` 都没有**
> ⇒ 已交付的 crm 模块（15.1.x 消息 / 15.2.x 评价 / 15.3.2.x 客户标签与接触记录 / 15.4.x 工单 …）
> 在真实环境里**一调就是 403**。
> 归属：identity 的权限码登记（`backend/services/identity/src/main/resources/db/migration/V*__crm_*_permission.sql`）

---

## 现象

15.3.1.1 车道按任务书要求登记了 `crm.customers.read` / `crm.customers.update` / `crm.customers.pii.read`
三个码（`V432__crm_customer_permission.sql`）。登记时核对发现：

```
identity 迁移目录里没有任何一个 V*__crm_*_permission.sql
⇒ permission 表里 crm.* 的行数 = 0
```

而 crm 服务的端点（15.1.1.1 消息收件箱 `/crm/message-inbox`、15.3.2.1 客户标签 `/crm/customer-tags`、
15.3.2.2 接触记录 `/crm/customer-contacts` …）**一律按 `crm.*` 权限码做授权判定**
⇒ 这些端点在真实部署里**全部 403**，而**所有测试都是绿的**
（集成测试用 Testcontainers 起库、测试夹具直接插权限行，绕过了「生产库有没有登记这个码」这件事）。

## 根因

**权限码登记与端点实现脱节**：每个 crm 模块的车道在实现端点时，
**没有把「在 identity 的 permission 表里登记自己的权限码」当成必做项**，
于是 15.1/15.2/15.3.2/15.4 各车道各写各的，只有 15.3.1.1 顺手登记了。
⇒ **不是某一个模块的锅，是一整卷（15）的系统性遗漏。**

## 处置建议

1. **单开车道，把卷 15 已交付模块的权限码一次性补齐**：
   - 逐个模块列出它实际用到的 `crm.*` 码（从各服务的 `AccessService` / 控制器注解里 grep 出来，不要凭印象）；
   - 在 identity 里**一个模块一个迁移**补登记（版本号由调度器预分配，**不要复用已占用的号**）；
   - 每个码同时登记 `page_resource`（如 `crm_customer_tag`），并按既有纪律决定授给哪些角色。
2. **补一条集成测试钉住这个性质**：每个服务都应有一条测试断言
   「本服务用到的每个权限码，在 identity 的 permission 表里都存在」
   —— 否则下一个卷还会重演同样的遗漏。
   （这条测试必须**跨服务读 identity 的库**，所以只能放在集成测试层，且要显式说明跨服务只读的原因。）
3. 🔴 **不要去改 crm 服务里既有的授权判定逻辑** —— 端点侧是对的，缺的是登记。

## 通用教训

**「端点存在 + 测试绿」不等于「功能可用」**。授权码是一个横切约束，
它不在任何单个模块的测试覆盖范围内 ⇒ 只有真的部署出来调一次才会暴露。
⇒ 建议把「本模块用到的每个权限码都在 identity 里登记过了」写进各车道的任务书自检项，
与 `docs/backlog/2026-11-02-platform-proxy-route-does-not-export-put.md` 记的是同一类横切缺口。
