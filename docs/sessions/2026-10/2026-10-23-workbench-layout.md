# 1.4.1.1 工作台布局（阶段 D7 · L3 锚点 1.4.1）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/1-4-1-1`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/1.4.1.1`）
- **基线**：`integration/module-dev` @ `7725fdd0`
- **迁移版本号**：`V339__*.sql` **预分配但未占用**（本模块不建表，理由见下）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/01-dashboard-and-portal.md`）：

> ### 1.4.1.1 工作台布局
>
> - **职责**：用户拖拽调整指标卡顺序、隐藏不需要的卡片、选择默认落地 Tab。
> - **输入**：拖拽/显隐操作；系统可选卡片清单。
> - **输出**：个性化后的工作台布局；可重置为默认布局。
> - **关系**：上游 = 前端卡片注册表；下游 = 1.1 全部卡片渲染。**布局是纯个人偏好，不进业务库。**

本车道落地要点：

1. **不建表（V339 特意留空）。** 蓝图那句「**布局是纯个人偏好，不进业务库**」直接否掉了新表。
   布局落在 1.4.1.2 已建的 `user_preference` 里，用本模块自己的 page_key `dashboard.layout`。
   同批还有 1.2.2.1 / 1.4.2.1 两条车道用同一张表 ⇒ **零 ALTER**（任何无 DEFAULT 的 ADD COLUMN
   都会让对方的 INSERT 违约）。**没有为了「用掉版本号」造一张没有业务事实的表。**
2. **零新增端点、零改后端生产代码。** `UserPreferencePayloadRules` 的校验是**通用形状**校验
   （对象 / 键名字符集 / 深度 ≤4 / 键数 ≤64 / 数组 ≤64 / 字符串 ≤512 / 体积 ≤16KB / 敏感键拒收），
   **不是按 page key 分派** ⇒ 本模块的载荷 `{order[], hidden[], defaultTab}` 直接被它覆盖，
   **不需要**加性扩展、也不需要另开端点。租户与 membership_id 仍只从已认证会话解析。
3. **零 api-proxy 改动。** 1.4.1.2 登记的那条独立元素（`user-preferences` 裸路径 + 单个小写 slug 段）
   已覆盖任何 slug 形状的 page_key，**含 `dashboard.layout`** ⇒ 不碰这个多车道共用的累积型白名单，
   也就不需要跑全量 allowlist 回归。仍写了 `workbench-layout-api-proxy.test.ts` 把这条钉住
   （从 `api-proxy.ts` 源码抽正则 + 从模型源码抽调用点，双向对撞）。
4. **系统事实与个人偏好严格分开。** 卡片清单 = **代码里的声明**（`workbench-card-registry.ts`），
   改一次对所有人生效；用户布局 = **个人数据**（`user_preference`）。
   绝不把清单塞进 `user_preference` —— 系统事实变一次就要给所有人改数据，那是另一种设计。
5. **只登记「已交付、真的能打开」的卡片。** 1.1.2.1 核心指标卡、1.1.3.2 资金与回款风险尚未交付
   ⇒ **不登记**（指向未开发模块 = 假承诺，首页已经因为这个原则改过一次）。
   登记的五张全部指向**已存在的路由**，并有测试对着真实文件系统核对（`existsSync(page.tsx)`）。
6. **卡片顺序靠载荷里的数组顺序表达，不新增列存 `sort_order`。**
   🔴 配套的一条地基断言：**jsonb 会重排对象的键，但不会重排数组元素** —— 顺序一旦被重排，
   用户的布局就全乱了。这条被写成了后端集成测试。
7. **缺键套默认值，而不是报错。** 老布局没有新字段是常态；存回来的顺序只认「现在认识的卡片」
   （下架的卡片不占位）；**系统新加的卡片默认可见**（只有用户明确藏过的那几张才继续藏着）；
   落库的 `order` 永远是一份**完整排列**。
8. **恢复时不写库。** 只有用户**真的改了**什么才落库 —— 否则「从没调过布局的人」也会留下一行，
   而那一行会冻结当时的卡片清单，以后系统新加的卡老用户就再也看不到。
9. **重置是真的重置。** `DELETE` 删掉服务端那一行（不是「提示用户刷新」），且幂等。
10. **排序方式 = HTML5 原生拖拽 + 上移/下移按钮。** 照抄本仓既有写法
    （`components/iam/department-tree.tsx`、`packages/ui/src/components/layout/workspace-tabs.tsx`）；
    既有拖拽实现都是纯鼠标路径，所以**每一行额外给「上移 / 下移」按钮**（键盘与读屏可达），
    **不**为此引入重量级依赖。

## 关键 trade-off

| 决策点                                     | 取舍                        | 理由                                                                                                                                                              |
| ------------------------------------------ | --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 复用 `user-preferences` 端点 vs 另开端点   | **复用（零改动）**          | rules 是通用形状校验而非按 page key 分派；复用就不碰多车道共用的白名单与共享校验类                                                                             |
| 卡片清单放代码 vs 放 `user_preference`      | **放代码**                  | 卡片清单是**系统事实**（系统能提供哪些卡片），用户布局是**个人偏好**；混在一起 ⇒ 系统上线一张新卡要给所有人改数据                                                     |
| `order` 存全部卡片 vs 只存可见的           | **存全部（完整排列）**      | 藏起来不等于丢掉位置：取消隐藏时卡片回到原来那一格；「谁在第几位」只有一份真相，落库的 order 恒为完整排列                                                          |
| 新卡片插到「同类旁边」vs 接在末尾          | **接在末尾**                | 用户可能把某张卡拖到第一位；按「同类旁边」插入会让刚上线的卡突然出现在精心排好的最前面，比排在最后更困惑。没调过布局的人全部按注册表顺序铺开 |
| 拖拽 vs 纯按钮排序                         | **两者都给**                | HTML5 拖拽对键盘/读屏用户不可用（本仓既有拖拽实现也都是纯鼠标路径）；按钮路径成本低、无新依赖，两条路都能完成排序                                                        |
| 路由 `/dashboard/layout` vs `/dashboard/workbench-layout` | **后者**          | `layout` 是 Next.js App Router 的保留文件名，用作目录名容易踩坑                                                                                                     |
| 是否改首页 `/` 接入布局                    | **不改**                    | `apps/web/src/app/page.tsx` 是跨车道共用文件（源码里明写「首页是人工与别的模块共用的」）；本模块把「按布局渲染的卡片区」做成独立组件，首页接入留给首页所有者 |

## 文件清单

**迁移（0）**

- 无。`V339` **未占用**（理由：纯个人偏好，复用 `user_preference`；且同表另有两条并行车道，零 ALTER 才是安全解）。

**后端生产代码（0）**

- 无。复用 1.4.1.2 的 `UserPreferenceController` / `UserPreferenceService`（jOOQ 显式 SQL，upsert / 读 / 删）。

**后端测试（2 新）**

- `backend/services/identity/src/test/java/com/starcon/identity/WorkbenchLayoutIntegrationTest.java`（11 例）
- `backend/services/identity/src/test/java/com/starcon/identity/WorkbenchLayoutTenancyIntegrationTest.java`（4 例，三断言实跑）

**前端（6 新）**

- `apps/web/src/components/dashboard/workbench-card-registry.ts` — **系统可选卡片清单**（系统事实）
- `apps/web/src/components/dashboard/workbench-layout-model.ts` — 纯模型 + 接口调用
- `apps/web/src/components/dashboard/use-workbench-layout.ts` — 可复用 hook
- `apps/web/src/components/dashboard/workbench-layout-workspace.tsx` — 工作区（按布局渲染的卡片区 + 编辑区）
- `apps/web/src/components/dashboard/workbench-layout-model.test.ts`（35 例）
- `apps/web/src/components/dashboard/workbench-layout-api-proxy.test.ts`（7 例，白名单对撞）
- `apps/web/src/app/dashboard/workbench-layout/page.tsx` — 路由

**改动的既有文件（1）**

- `docs/product/capability-blueprint/module-progress.md` — 只改 1.4.1.1 那一行的「状态」「证据」两格
  （**未跑 `sync.mjs`**：它会重写派生汇总表、并把 1.4.1.2 的路由格重置成「—」；本车道只动自己那一行）

**未改动（刻意）**

- `apps/web/src/lib/api-proxy.ts` — 白名单已覆盖 `dashboard.layout`
- `apps/web/src/app/page.tsx`（首页）、`packages/ui/src/lib/nav-config.ts`、`packages/ui/src/lib/task-pages.ts`
- `packages/api-contracts/**` — 后端生产代码零改动 ⇒ 契约零漂移（实测逐字节相同）

## 验证

```bash
# L0 编译闸
cd backend && ./mvnw -q compile                    # EXIT=0
cd <worktree> && pnpm typecheck                     # 3 packages successful
pnpm lint                                           # 3 successful（只剩既有 warning，均不在本车道文件）

# L1 模块闸（只跑本模块）
cd backend && ./mvnw -pl services/identity -am test \
    -Dtest='WorkbenchLayout*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# WorkbenchLayoutIntegrationTest       Tests run: 11, Failures: 0
# WorkbenchLayoutTenancyIntegrationTest Tests run: 4,  Failures: 0
# Tests run: 15, Failures: 0   BUILD SUCCESS

# 前端（只跑本模块新增的测试文件）
pnpm vitest run apps/web/src/components/dashboard/workbench-layout-model.test.ts \
                apps/web/src/components/dashboard/workbench-layout-api-proxy.test.ts
# Test Files 2 passed · Tests 42 passed
```

**契约零漂移的取证**：`OpenApiContractTest` 导出当前 identity spec →
`python3 tools/codegen/catalog-contracts.py --spec-dir .tmp/spec-dir --out-dir .tmp/contracts-out` →
`contracts/identity.ts` / `identity.openapi.json` / `index.ts` 与 `packages/api-contracts/src/` 下
**逐字节相同**（其余 5 个服务的差异是「快照往返后 `properties` 键序被 `sort_keys` 重排」的取样假象，
本车道没碰那 5 个服务的任何代码）。

**PG 容器**：本车道 verify 期间同时存活 **11 个 `postgres:16-alpine`**（本车道自己起 1 个，
其余 10 个来自并行车道）；本车道那个容器由 Testcontainers 的 Ryuk 在 JVM 退出时回收
（基类刻意不手工 `stop()`，否则先结束的测试类会把共享容器停掉）。

## 后续待办

1. **首页 `/` 接入布局**：`apps/web/src/app/page.tsx` 是跨车道共用文件，本车道没动。
   工作台卡片区已做成独立组件（`WorkbenchLayoutWorkspace` 内的「我的工作台」区块），
   首页接入由首页所有者决定。
2. **1.1.2.1 核心指标卡 / 1.1.3.2 资金与回款风险** 交付后，把它们登记进 `workbench-card-registry.ts`
   （改代码 ⇒ 对所有人生效，**不需要**给任何人改数据 —— 这正是「系统事实与个人偏好分开」的红利）。
3. **1.4.2.1 语言与时区 / 1.2.2.1 常用页面收藏** 同样复用 `user_preference`，各自用自己的 page_key，
   **不许**互相改对方的 key 语义。
4. **台账派生汇总表是陈旧的**：模块明细行实际 354 已交付 / 111 未开发，而汇总表自称 350 / 115
   （本车道交付后为 355 / 110）。本车道**刻意没跑 `sync.mjs`**（会重写派生表并动到 1.4.1.2 的路由格），
   留给调度者在集成头统一重生成。
