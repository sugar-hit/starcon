# 17.7.2.1 平台参数与开关（阶段 D7 · L3 锚点 17.7.2）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/17-7-2-1`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/17.7.2.1`）
- **基线**：`integration/module-dev` @ `f8733478`
- **落地服务**：`identity`（蓝图 README「后端主责服务」列对卷 17 标 identity）
- **迁移版本号**：`V324__platform_feature_flags.sql`（预分配；identity 现存最大是 V163，
  本批全局连号 V321–V324，identity 拿到 V323/V324，跳号无害）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/17-org-permission-and-settings.md` 第 285-290 行）：

> ### 17.7.2.1 平台参数与开关
> - **职责**：平台级功能开关与参数（灰度放量、按租户禁用某模块、限流阈值、公告），用于平台侧运营而非租户配置。
> - **输入**：开关与参数配置；灰度策略。
> - **输出**：全局配置；功能可用性判定。
> - **关系**：上游 = 平台运营；下游 = 全系统功能门控。**平台开关与租户参数（17.5.1）分层**，平台开关不可被租户覆盖。

本车道把这句话落成**两层互不相同的数据**，而不是一列可覆盖的开关值。

### 1. 两层分层（蓝图硬约束的落点）

| 层 | 表 | tenant_id | RLS | GRANT |
| --- | --- | --- | --- | --- |
| 平台全局层 | `platform_flag_definition`（出厂码表）/ `platform_flag_setting`（全局当前值）/ `platform_flag_history`（变更历史） | **无** | **不加** | **只 SELECT** |
| 租户例外层 | `platform_flag_tenant_exception` / `platform_flag_exception_history` | `NOT NULL` | ENABLE + FORCE isolation | SELECT/INSERT（历史）/ SELECT,INSERT,DELETE（例外） |

平台全局层照 V53 `dict_catalog` 的先例：**不带 tenant_id、不加租户 RLS、只 GRANT SELECT**。

### 2. 「平台开关不可被租户覆盖」的四道独立防线

1. **全局层没有租户副本** —— 库里根本不存在「按租户的那一份平台开关」这个对象，
   所以「租户覆盖平台开关」没有可落地的位置。
2. **应用角色写不了全局层** —— `platform_flag_setting` 只授予 SELECT；
   唯一写入口是 `platform_flag_apply_setting()`（SECURITY DEFINER），
   权限校验、类别形态校验与变更留痕都在那一个函数里完成。
   **直接 SQL 写会被数据库 42501 挡下**（测试实跑）。
3. **租户例外只能收紧** —— `effect` 的取值域被 CHECK **钉死在 `'DISABLED'`**，
   租户例外永远不可能把平台关掉的功能打开。
4. **限流阈值与公告不参与按租户禁用** —— `platform_flag_exception_kind_guard()`
   触发器拒绝给非 `MODULE` 类开关建例外：禁用一个限流阈值等于**放宽**限制，不是收紧。

裁决顺序（`PlatformFlagController.adjudicate`）：平台没开 → 不可用（租户例外打不开它）；
平台开了但本租户被单独禁用 → 不可用；平台开了且无例外 → 看灰度分桶。
**三层（平台值 / 租户例外 / 最终生效值）并列返回**，不给孤零零的布尔值。

### 3. 灰度放量必须确定性

分桶 = `sha256(salt|tenant_id)` 前 4 字节**大端无符号** 32 位整数 mod 10000，
命中条件是分桶 < 放量比例 × 100（万分比，整数比较，无浮点）。

- Java 侧用 `long` 而不是 `int`：**Java 的 int 有符号**，首字节 ≥128 时
  `(b0 & 0xFF) << 24` 会把符号位置 1，整个值变负，取模后落在 −9999..−1。
  这个 bug 由「Java 与数据库函数逐个租户断言一致」的测试抓到。
- 数据库函数 `platform_flag_rollout_bucket` 用 `bigint` + `get_byte`，与 Java 逐位同口径，
  判定口径可被 SQL 直接复查。
- 不用 `md5(text)::bit(32)::int`：PG 的 `::bit(32)` 取低位且有符号，取模结果随实现漂移。
- 不用 `hashtextextended`：PG 的 hash 函数族**不保证跨大版本稳定**，换版本会重新洗牌已放量的租户。

### 4. 限流阈值不给默认值（照 16.5.2.1 先例）

`platform_flag_definition` 里没有限流阈值的出厂默认值，
`platform_flag_setting` 上 `RATE_LIMIT` 类的行**必须**带 `rate_limit_per_minute`（表级 CHECK 钉死）。
出厂**不预置**这一行 ⇒ 判定如实返回 `NOT_CONFIGURED`，不替平台悄悄选一个数字。

### 5. 平台运营写权限的处置（本车道最需要解释的一条）

- 迁移**只**把 `identity.platform_flag.read` 授给 OWNER；
  `update` / `create` / `delete` **不默认授给任何角色**（蓝图：17.7 平台运营不对租户开放），
  必须由 17.3.1 角色管理显式分配。
- **额外要求调用方所在租户是根租户**（`parent_tenant_id IS NULL`）。
  原因：本仓 `create_child` 会把 `permission` 表里的**全部**权限码授给子租户的 OWNER 角色
  （`INSERT INTO role_permission SELECT cid, rid, code FROM permission`），
  只看权限码的话，**子租户的 OWNER 也能改全平台配置**。
  「根租户才写得了平台全局层」把那个口子堵住：根租户是平台本身。
  判定走 `platform_flag_tenant_is_root()`（SECURITY DEFINER）——
  应用角色对 `tenant` 表**没有任何权限**，应用层直接查会被数据库挡下。
- 「按租户禁用」的目标租户 = 调用方自己 + 自己的后代租户（`tenant_closure`，受 RLS 约束，
  所以可管理性判定必须在**调用方租户上下文**里做；真正的写在**目标租户上下文**里做 ——
  两个先后独立的事务，不是嵌套）。

## 关键 trade-off

| 取舍 | 选择 | 理由 |
| --- | --- | --- |
| 平台值存一份还是每租户一份 | 全局一份（不带 tenant_id） | 每租户一份就是「租户手里有可改的副本」，正是蓝图禁止的 |
| 租户例外允许哪些效果 | 只允许 `DISABLED` | 用 CHECK 钉死而不是靠代码自觉：库层物理保证例外只能收紧 |
| 限流/公告能否按租户禁用 | 不能（触发器拒绝） | 禁用它们等于放宽限制，与「例外只能收紧」矛盾 |
| 裁决结果给布尔值还是三层 | 三层并列 | 只给布尔值时用户无法区分「平台关了 / 被单独禁用 / 没进灰度」，三者处置完全不同 |
| 灰度用随机还是哈希 | 稳定哈希（SHA-256） | 随机分桶会让同一租户反复请求得到不同结果，用户看到开关时开时关 |
| 全局层写入放在应用层还是数据库 | SECURITY DEFINER 函数 | 「应用角色写不了全局层」必须是 DB 级事实；形态校验要与表级 CHECK 同口径；留痕要与写入同事务 |
| 平台写权限默认授给 OWNER 吗 | **不授**（只授 read） | 17.7 不对租户开放；写权限必须显式分配 |
| 子租户 OWNER 有写权限码怎么办 | 额外要求根租户 | `create_child` 自动授全部权限码，只看权限码挡不住 |

## 文件清单

**新增**

- `backend/services/identity/src/main/resources/db/migration/V324__platform_feature_flags.sql`
- `backend/services/identity/src/main/java/com/starcon/identity/PlatformFlagController.java`
- `backend/services/identity/src/test/java/com/starcon/identity/PlatformFlagIntegrationTest.java`
- `apps/web/src/components/iam/platform-flag-model.ts`
- `apps/web/src/components/iam/platform-flag-model.test.ts`
- `apps/web/src/components/iam/platform-flag-workspace.tsx`
- `apps/web/src/app/org/platform-flags/page.tsx`

**改动**

- `apps/web/src/lib/api-proxy.ts` —— **services[] 数组末尾**（`pii-access` 之后）**纯新增一条**
  `prefix: 'identity/'` 元素；既有条目一字未动、不重排、不格式化整文件。
  首 token「platform-flags」在全文**零命中**；既有那唯一一条 `identity/` 元素对本模块的
  全部路径形状**零命中**（逐条双向实测），所以既不会吞掉本模块路径，本模块也不会吃掉别人的路径。
  放行 9 个形状，刻意不放行 6 个形状（含 `platform-flags/tenant-switch`——
  「租户覆盖平台开关」是蓝图**明令禁止**的能力，放行它等于把一条不存在的后门登记成公开接口）。
- `apps/web/src/components/iam/identity-workspace.tsx` —— 导航里加一条目的地 +
  `visible()` 里加 `identity.platform_flag.read` 的可见性判断。
- `packages/api-contracts/src/contracts/identity.ts` + `packages/api-contracts/src/identity.openapi.json`
  —— **codegen 产物**，由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 生成，
  **未手改**。其余 5 个服务的契约文件逐字节未变（改动前先验证过 codegen 往返幂等：
  基线 spec → 重新生成 → 与已提交产物逐字节相同）。
- `docs/product/capability-blueprint/module-progress.md` —— **只改 17.7.2.1 那一行**的状态与证据两格。

## 验证

```bash
# L0 编译闸
cd backend && ./mvnw -q -pl services/identity -am compile     # 通过
pnpm typecheck                                                # 3 successful / 3 total

# L1 模块闸（只跑本模块新增的测试类）
cd backend && ./mvnw -pl services/identity -am test \
  -Dtest='PlatformFlag*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# → Tests run: 19, Failures: 0, Errors: 0, Skipped: 0

# 前端
pnpm vitest run apps/web/src/components/iam/platform-flag-model.test.ts
# → 1 file / 25 tests passed
pnpm vitest run apps/web/src/app/api/trade
# → 68 files / 379 tests passed（改了共享 api-proxy，跑全量 allowlist 回归）
pnpm vitest run apps/web/src/components/iam apps/web/src/lib
# → 28 files / 722 tests passed
```

## 后续待办

1. **下游功能门控接入**：仓内**没有**统一的 feature-flag 客户端 / SDK，
   各业务服务尚未消费本模块的判定结果。本模块只产出判定能力，
   **没有跨服务改代码**（接入是后续待办）。`capabilities` 里 `DOWNSTREAM_GATING` 标
   `available=false` 并写明归属模块。
2. **17.7.1.1 套餐与权益的超限降级**：同批并行车道，此刻尚不存在 ⇒
   `capabilities` 里 `PLAN_ENTITLEMENT_DEGRADE` 标 `available=false`，`ownerModule=17.7.1.1`。
3. **17.7.2.2 平台运维视图**：`capabilities` 里 `PLATFORM_OPS_VIEW` 标 `available=false`，
   `ownerModule=17.7.2.2`（本模块只出判定，不出跨租户运维看板）。
4. **页面迁到 `apps/admin`**：`apps/admin/` 目前仍只有一个空的 `.gitkeep` 脚手架
   （没有 app router），本轮页面放在 `apps/web`，用**权限码 + 服务端校验**做平台运营门控。
   迁到 `apps/admin` 是后续待办。
5. **⚠️ 顺带发现（不属于本车道，未改）**：`apps/web/src/lib/api-proxy.ts` 全文
   **没有 `dictionary` / `dict-catalog` / `dict-history` 这几个串**（grep 计数 = 0），
   而 17.5.2.1 的页面 `components/iam/dictionary-workspace.tsx` 调的正是
   `identity/dict-catalog` 与 `identity/dict-history` ⇒ **该页面在浏览器里会 404**
   （白名单只在浏览器端生效，typecheck / lint / vitest / Playwright 全绿）。
   这是 17.5.2.1 车道当年漏登记的，**本车道按纪律只新增自己那一条 elements**，
   不动别人的路由 —— 建议单独派一条车道补登记。
6. **create_child 自动授予全部权限码**：本车道用「根租户才写得了平台全局层」堵住了它对
   平台配置的影响，但 `create_child` 本身仍会给子租户 OWNER 配上全部权限码
   （含未来新增的其它平台级写权限码）。若后续再有「平台级」权限码，
   建议在 `create_child` 里显式排除，而不是每个模块各加一道判定。
