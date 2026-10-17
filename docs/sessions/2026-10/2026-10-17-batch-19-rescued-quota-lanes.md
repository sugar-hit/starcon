# 批次 19 · 接管三条配额暴毙车道（11.1.2.1 / 11.3.1.2 / 17.4.2.1）

> 调度会话：2026-10-17 22:55（**用户手动指令，绕过 20:30 静默窗口；窗口配置本身未改动**）
> 集成头 `53cd8ff3` ｜ 前置提交 `3d6a50a1`

## 这批的来历：不是正常规划，是事故恢复

18:00 tick 的 4 条车道里，3 条在 18:27 因 `errorCode 42212`（Token Plan 用量上限）被杀死。
它们的形态是**最危险的那种**：分支上零提交，成果只存在于 worktree 工作区的未跟踪文件里，
且**一次测试都没跑过**。19:00 tick 在启动第 1 秒又撞同一堵配额墙，什么都没做。

⚠️ 接手前取证到的一个陷阱：`lane.mjs status` 当时报 `lanesRunning: 0` —— **是假的**。
那 3 条心跳停在 95–99 分钟前，超过 90 分钟 TTL 被容量闸**排除出计数**，
于是闸以为机器空闲、可以再派 6 条。真实状态必须读 `lanes.json` 非终态条目
+ 会话 `status` + 进程表三者交叉。

## 三条车道的接手结果

| 车道 | 服务 | 迁移 | 后端用例 | 前端用例 | 原有代码状态 |
| --- | --- | --- | --- | --- | --- |
| 11.1.2.1 原因分类 | trade | V158 | 41 | 24 | 约 90% 完成，**3 个真缺陷** |
| 11.3.1.2 退款状态 | trade | V159 | 52 | 53 | 后端完好，**4 个边界缺陷**（含 1 个致命） |
| 17.4.2.1 待我审批 | identity | V160 | 21 | 22 | 后端完好，缺租户测试类 |

三条的共同流程：`wip()` 抢救提交 → 合入 `integration/module-dev`（各落后 8 个提交，均无冲突）
→ 重新生成契约 → 补台账 → L0 → L1 → 修缺陷 → 会话日志 → `land`。

## 共享白名单：三条车道同时改 `api-proxy.ts`

三条都往 `apps/web/src/lib/api-proxy.ts` 加规则。这是**共享白名单累积正则**，
取错一侧会静默删掉别人已交付的路由，表现为页面 404，而 typecheck / lint / vitest / Playwright **全部全绿**。

`merge-ready` 对该文件走了**行级兜底并集**并明确要求人工确认。逐项核实结果：

- `services[]` 元素 23 → 25，白名单正则 21 → 23
- 三条新规则都在：`identity` 元素内新增 `my-approvals` + `approval-instances`（第 98 行）、
  新增 `refunds/…` 元素（第 1063 行）、新增 `aftersales/reasons/…` 元素（第 1167 行）
- 批次 18 的 `cases/{uuid}` 系列规则仍在
- **严格超集证明**：把新 identity 正则里插入的 98 字符片段去掉后，与旧正则**逐字节相等**
  ⇒ 零丢失，纯增量（`+98` 字符差值 = 插入片段长度）

## 接手过程中修掉的真实缺陷

### 11.1.2.1 —— 三个缺陷，两个让页面根本编译不过

1. **`TS2552: Cannot find name 'updateReasonMapping'`** —— workspace 调用了它但**漏了 import**
   （该函数确实在 model:122 导出）。缺 import ⇒ `tsc --noEmit` 非零 ⇒ 整个页面编译失败
   ⇒ 映射启用/停用按钮根本不存在。
2. **`TS2552: Cannot find name 'setAttributionMessage'`** —— 实际 setter 叫 `setAttrMessage`，
   是早期草稿留下的**过期名字**。
3. **契约把 `AftersalesReasonMappingCreate.categoryId` 声明为 required `string`**，
   而后端语义是「传了才生效」（`coalesce(?::uuid, category_id)`）。前端做「仅切状态」的开关时
   只能塞 `""`，**靠 Jackson 把空串强转 null 才碰巧能用** —— 这是实现细节，不是契约。改为可空，
   前端显式传 `null`，并加后端 HTTP 边界回归测试钉住。

⚠️ **一处自我纠正值得记下**：最初判定缺陷 3 会导致「每次点击都 400」，据此写了
「`""` ⇒ 400」的断言，实跑返回 **200** —— Jackson 确实会强转。所以空串只是**潜在脆弱**，
真正的破口是漏 import。那条错误断言被**主动删掉**：把当前的强转行为钉成契约，会挡住将来
正确的收紧。

另修了 Controller Javadoc 里 2 个 U+FFFD 乱码字符（被杀时的字节截断）。

### 11.3.1.2 —— 一个让页面永远打不到后端的致命缺陷

4. **`api-proxy` 白名单里完全没有 `trade/refunds` 条目。** 后端 11 个端点、前端页面都在，
   但浏览器侧**什么都不放行** ⇒ 静默 404，且**后端日志一行都没有**。
   这是「页面 404 但全绿」那类故障的最坏形态：所有自动化检查都通过，功能完全不可用。
   已补一条独立 `services[]` 元素 + 双向 allowlist 断言。

另外三个：model 自造了 cva 里不存在的 `variant` 词表（改为语义留在 model、用
`buttonVariant()` 映射到真实 token，`caution→outline` 是为了让「核验」与「重试」在视觉上
最大区分，避免真金白银的误点）；以及**契约里两处 `Map` 退化成空 `{}`** ——
`countByState` / `amountByCurrency` 的裸 `Map<String,BigDecimal>` 在 OpenAPI 里序列化不出结构，
改为 list bucket（与批次 18 的 `AftersalesAmountBucket` 同一决策）。

### 17.4.2.1 —— 后端零缺陷，补上缺失的租户测试类

新写 `ApprovalInboxTenancyIntegrationTest`（5 例）。原有 16 例首跑即绿，说明上一任 worker 的
后端代码是健康的。四个失败全部出在**新写的测试自身**，其中一个暴露了我自己的断言错误：
「伪造租户头」的正确行为**不是** 404，而是**写入仍落在会话租户名下** ——
伪造头不该撤销一个合法审批人在自己租户的既有权限。

一个值得记的测试设计教训：最初连接池那条用例每轮重复审批**同一个**实例，
第二轮拿到 404 —— 那是「不能重复审批」的正确行为，却**掩盖了**本来要验证的隔离结论。
改为每轮提交**新实例**后，隔离断言才真正成立。

## 三条车道都独立撞上了同一个坑：契约快照过期

工作区里的 `backend/target/openapi/*.json` 是上一任 worker 留下的旧快照。
若直接 `pnpm contracts:generate`，会**把批次 18 已交付的端点悄悄删掉**，而
typecheck / lint / vitest / Playwright **全部照绿**。

正确顺序必须是：先跑该服务的 `OpenApiContractTest` 产出新 spec → 再生成契约 → 再核对已交付端点仍在。
其中 17.4.2.1 还发现**同一个坑在 trade 侧也成立**（identity 的 spec 是新的，trade 的仍是 18:21 的旧件）。

11.1.2.1 进一步证明：重新生成后 `trade.ts` 是 724 个类型**一个不多一个不少**，
git diff 里的 26 增 26 删纯属**重排序**。

## 顺手发现（不在本批范围内，留给下一轮）

11.3.1.2 报告指出：`OrderChangeCancelBlocker`（9.x）与 `SalesOrderOverview`（9.1.1.x）
里还留着**同一类空 `{}` 契约缺陷** —— 裸 `Map` 在 OpenAPI 里丢结构。
已交付模块里的同族问题，本轮未修（不在三条车道所有权内）。

## L2 批次闸结果（集成头 `53cd8ff3`，全绿）

```
后端 ./mvnw verify        BUILD SUCCESS
  257 + 1502 + 295 + 1485 + 1045 + 18 = 4602 例，Failures 0 / Errors 0
  本批三模块在 L2 内共 114 例全绿（identity 21 + AftersalesReason 41 + RefundStatus 52）
前端 TURBO_FORCE=true typecheck   3 successful, Cached: 0 cached, 3 total
前端 TURBO_FORCE=true lint        3 successful, Cached: 0 cached, 3 total
前端 pnpm test                    214 files / 3796 例全绿（较批次 18 的 3697 新增 99 例）
Next production build             绿
sync.mjs --check                  ✓ 台账与蓝图一致
```

⚠️ 上一批定性的 flake 用例 `FulfillmentRequestIntegrationTest` 本轮 **33/33 全绿**，
进一步确认它是**间歇性**而非确定性失败（批次 18 首跑 1 例报错、定向复跑全绿、本轮全绿）。

## 静态预检

V158（4 张表）/ V159（6 张表）/ V160（2 张表）三份迁移**全部零命中**危险形状：
无 `ADD COLUMN`、无 `SET NOT NULL`、无 `ADD CONSTRAINT ... CHECK`、无 `DROP TABLE`，
对既有表**零 ALTER**，只有 `ENABLE/FORCE ROW LEVEL SECURITY`。

## 后续待办

1. `OrderChangeCancelBlocker` / `SalesOrderOverview` 的空 `{}` 契约缺陷（同族，非本批）
2. 17.4.2.2（审批记录）未交付：`approval_instance_action` 只有 `SELECT,INSERT`，无查询端点
3. 17.4.2.1 的 CONDITIONAL 节点路由恒返回 409 —— V83 只校验条件表达式从不求值
4. 三条车道的来源系统全部报 `delivered=false`：入口 API 已上线，但还没有业务系统真正调用
