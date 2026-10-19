# 11.2.2.2 实收分配（阶段 D4，L3 锚点 11.2.2）

- 日期：2026-10-19
- 车道：`feat/11-2-2-2`（worktree `.worktrees/11.2.2.2`，基线 `integration/module-dev`）
- 上游：11.2.2.1 退货入库（`V172__trade_return_receipt_intake.sql`，已交付）
- 下游：9 订单（已退数量消费侧，**未交付**）、11.2.3.3 财务对账（**未交付**）

## 决策摘要

**1. 已退数量落成新表，不 ALTER `sales_order_line`。**
蓝图输出句要求「订单行已退数量更新」，但 `sales_order_line`（V99）属 9 订单那条线的写入所有权，
且同批另有三条车道往 trade 落迁移 —— ALTER 别人正在改的表是**结构性冲突**，retry 不会自愈。
故落 `order_line_returned_qty`，本模块拥有它，写入路径唯一（两个 DB 触发器）。
另**刻意不建永远为 0 的影子引用列**：那会让页面显示成「这行从没退过」。

**2. 幂等键由派生而非由调用方给定。**
`idempotency_key` 恒为 `RECEIPT:` + `receipt_id`，DB 侧有 CHECK 钉住形状。
自由文本键会让「同一个收货事件用了两个键」成为可能 —— 那正是重复累加的最常见路径。
传输层**没有** force / allowDuplicate 开关：蓝图加粗句「按收货事件去重分配」直接决定了
「把一次资损做成一次点击」这件事不该存在。重复提交返回**同一张**分配单。

**3. 超收的真实来源是跨行分配，不是「同授权行实收多于授权」。**
后者已被 V172 的登记闸门（`trg_guard_return_receipt_line`）在**登记时刻**拒掉，
**任何夹具都造不出来** —— 那是 11.2.2.1 的职责边界。
本模块判的是**分配时刻、按收货事件**：源授权行额度充足**不代表**目标授权行额度充足。
V172 第四节明说「合并/拆分属 11.2.2.2 实收分配的职责」，跨行分配因此落在本模块，
而它正是超收最主要的来源。跨**订单**分配被拒（会让「这批货退给了谁」变成两笔资金事实）。

**4. 超收/少收量与金额差由触发器算，服务层只读不算。**
`allocated_qty` 是实收数**不被 clamp**；差额整额落 `over_received_qty` / `short_received_qty` /
`variance_amount`。金额差用 PG 的 `NUMERIC × NUMERIC`（任意精度十进制，**不是浮点**）。
这样「调用方传什么都不影响结果」才成立，而不是靠服务层自觉。

**5. 差额处置结论刻意没有「已解决」。**
超收多退的钱要追回还是认了是财务决定。只有 `PENDING_REVIEW` / `ACCEPTED_AS_ACTUAL`。
编一个「已解决」会让财务侧以为这笔钱已经结清了。

## 关键 trade-off

- **已退数量 vs 已分配量分列**：撤销时只回冲已退、保留已分配。两者之差就是
  「已退但已不在任何有效分配单上」的部分，而那正是财务要追问的。
  代价：读数的人必须知道有这两列（详情与汇总都同时给出）。
- **已对账 / 已撤销是终态**：不允许「对账完又撤销」。代价是录错时只能人工修数据；
  换来的是差额说明与已退数量不会互相矛盾（这类矛盾在财务对账时几乎无法自证是哪次操作造成的）。
- **超期不拒收**：货已收到，退回商品默认进待检是 11.2.2.1 的硬规则，
  拒收等于让货无处可去。超期是可解释的事实，不是错误。

## 文件清单

**新增**

- `backend/services/trade/src/main/resources/db/migration/V178__trade_return_receipt_allocation.sql`
  — 五张新表 + 四个触发器 + RLS + GRANT，零 ALTER 既有表、零外键
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnReceiptAllocationModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnReceiptAllocationService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnReceiptAllocationController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/ReturnReceiptAllocationIntegrationTest.java`（28 例）
- `backend/services/trade/src/test/java/com/starcon/trade/ReturnReceiptAllocationTenancyIntegrationTest.java`（5 例）

**改动**

- `apps/web/src/lib/api-proxy.ts` — 新增一条 `services[]` 元素（锚在 11.2.2.1 之后、11.1.1.2 之前）
- `packages/api-contracts/src/contracts/trade.ts` + `trade.openapi.json` — codegen 产物
- `docs/product/capability-blueprint/module-progress.md` — **仅** 11.2.2.2 那一行

## 过程中修掉的真实缺陷

1. **已退数量挂在分配单头 INSERT 上 ⇒ 永远是 0，且报错为空。**
   分配单头必须先落（它带幂等键与归属，是去重的锚点），分配行后写；
   触发器跑到时行还不存在，FOR 循环一条都扫不到。表现是「分配成功、已退数量不涨、
   下游读到的永远是『这行从没退过』」。拆成「分配行 INSERT 累加」+「分配单头 UPDATE 撤销回冲」两个触发器。

2. **撤销路径的触发器必须 `SECURITY DEFINER`。**
   该表 UPDATE 已从运行角色收回，`SECURITY INVOKER` 下撤销会被权限拒绝 ——
   **测试里能过是因为测试用超级用户跑迁移**，那是测试环境的假象。生产上「撤销分配」会直接执行不了。
   `search_path` 显式钉死；FORCE RLS 对表所有者同样生效，所以 definer 不会变成绕过租户隔离。

3. **`guard_return_allocation_frozen` 拦得太宽。**
   最初拦一切 UPDATE，把「分配行落完后回写头部汇总」也当成非法状态转换拦掉。
   状态**未变**的内容更新必须放行，转换规则只管状态**变化**。

4. **跨订单校验有个洞。** 原先只从**授权行**推导订单，而 `order_line_id` 是调用方可传参 ——
   「同订单的授权行 + 别的订单的订单行」在库里完全合法，已退数量会记到**别的订单**上。已补一次直接校验。

5. **plpgsql 的 RAISE 对复合格式串要求整体加括号。**
   字面量用 `||` 拼接时报 `syntax error at or near ||`，而**报错位置落在函数结尾**，
   与真正的原因隔着几百行。

6. **测试断言写了中文「重复键」「权限」，在 C locale 的测试容器里必然假失败**（PG 报错是英文）。
   改为断言**约束名**与**异常类型**，并补「越权写入后值未变」的正对照 ——
   这比匹配错误文本更强，也不受 locale 影响。

7. **夹具把另一个租户的读取写在 `asB` 的事务内部（嵌套 `run`）**，会让整段断言在
   「当前租户已被换成 A」的状态下执行从而**假绿**：B 读到了 A 的收货事件，又命中按收货事件的幂等返回，
   于是**不抛异常**。不是产品缺陷，是夹具写法踩到嵌套事务边界；夹具 id 必须在 lambda 外取好。

## 验证方式

```bash
# L0 编译闸
cd backend && ./mvnw -q compile
pnpm typecheck

# L1 模块闸（两个属性名都带）
cd backend && ./mvnw -pl services/trade -am test \
    -Dtest='ReturnReceiptAllocation*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# → Tests run: 33, Failures: 0, Errors: 0, Skipped: 0

# 契约（codegen 产物，禁止手改）
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi

# 前端
pnpm vitest run apps/web/src/lib/api-proxy.test.ts apps/web/src/lib/api-proxy-union.test.ts
# → 262 passed
```

## 后续待办

- **9 订单**：按 AGENTS.md「走授权 API / event」消费 `order_line_returned_qty`，
  消费侧未交付。
- **11.2.1.1 的可退量校验**：`receivedQty` 长期为 null 并标注归属模块（V161 交付时的既有口径），
  本模块交付后应在同一处叠加 —— **读侧改动属 11.2.1.1 / 11.2.1.2 的所有权，本车道未碰**。
- **11.2.3.3 财务对账**：消费差额事实（数量差 + 金额差 + 说明 + 待核实结论），未交付。
- **前端页面**：本车道**未建** `/aftersales/…` 工作区（不在本轮验收标准内）。
  后端能力声明端点已把两条硬规则、未交付下游与刻意不做的事下发齐全，可直接接。
