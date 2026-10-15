# 2026-10-15 19:30 调度轮次：陈旧调度闸与并入通道堵死取证

> 本轮**未派发**新车道、未跑 L2、未 push。原因是批次落在当天最后一格（19:30），
> 无法在活动窗口内收口。本轮实际动作是**取证 + 修掉一个会堵死次日并入的隐患**。

## 一句话结论

17:01 的调度会话在 `merge-ready` 标记 10.5.1.2 冲突后**异常退出**，
既没释放调度闸，也没提交 `sync.mjs` 回写的台账；
后者让主工作区一直脏，而 `merge-ready` 对脏树是**硬闸 exit 1** —— 次日 08:00 会**一条车道都并不进来**。

## 台账现状（`sync.mjs` 实时值）

| 指标 | 值 |
| --- | --- |
| 模块总数 | 465 |
| 已交付 | 163（35.1%） |
| 进行中 | 0 |
| 未开发 | 302 |
| 台账行数 | 465 ✓ |
| 重复模块 ID | 无 ✓ |

## 107 条车道的状态分布

| 状态 | 条数 |
| --- | --- |
| merged | 105 |
| abandoned | 1（7.8.1.1 分配策略配置） |
| **conflict** | **1（10.5.1.2 申报要素）** |
| running / ready | 0 / 0 |

## 取证一：调度闸是陈旧的，不是别人持有

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "held", "ageSeconds": 9062 }        # 约 2.5 小时

$ cat .dev-agent/dispatch.gate/owner
86617
2026-10-15T09:01:29.117Z                       # = 17:01:29 CST

$ ps -p 86617          → 进程不存在
$ ps aux | grep -E 'lane\.mjs|mvnw|next build|vitest'  → 空
```

持有者 PID 86617 **已退出**，且全机没有任何 `lane.mjs` / maven / next 进程。
10.5.1.2 的 `landedAt` 是 09:00:57Z（17:00:57 CST），闸在 **32 秒后**被取走 ——
时间线完全吻合「17:01 的会话取闸 → 跑 merge-ready → 撞冲突 → 崩掉，没走 release」。

**处置：不动它。** `lane.mjs:474` 的 `gateAcquire(ttlSeconds = 600)` 自带 TTL，
age 9062s 远超 600s，下次 `acquire` 会自动当陈旧闸接管。
所以它**不构成阻塞**，也不需要 `force`（协议禁止为了通过而 force 清闸）。

## 取证二：10.5.1.2 的冲突是真的，不是假信号

历史上出现过「状态写着 conflict、其实内容早就并进去了」的假信号，所以先验拓扑：

```
$ git merge-base --is-ancestor feat/10-5-1-2 integration/module-dev  → ✗ NO
$ git rev-list --count integration/module-dev..feat/10-5-1-2      → 1 个独有提交
```

再用非破坏性三方合并预演确认是**真内容冲突**（不是脏树假象）：

```
$ git merge-tree --write-tree integration/module-dev feat/10-5-1-2
CONFLICT (content): Merge conflict in apps/web/src/lib/api-proxy.ts
# stage 1/2/3 blob 三者互不相同 ⇒ 真冲突
```

冲突文件：`apps/web/src/lib/api-proxy.ts`（累积白名单，历史上反复冲突的那一个）。
`merge-tree` 同时显示 `merge-union` 驱动对手改前两个文件做过行级并集兜底。

**处置：按协议不自行强解，写进汇报，次日处理。**

## 取证三：脏工作区会堵死整条并入通道（本轮实际修复）

`lane.mjs:882-886` 是硬闸，不是警告：

```js
const dirty = git(['status', '--porcelain']);
if (dirty) {
  console.error(`✗ 主工作区不干净，拒绝切分支：\n${dirty}`);
  process.exit(1);          // ← 整个并入通道直接退出
}
```

17:01 的会话死前跑过 `sync.mjs`，台账被改写成 163 却没提交，于是主工作区长期脏。
更危险的是：**待并车道 10.5.1.2 自己也改台账**，脏树与来入改动重叠 ⇒
`git merge --no-ff` 会以「local changes would be overwritten」失败 ⇒
脚本随即 `git merge --abort` 并把车道标成 `conflict` ——
**产生一个和代码内容毫无关系的假冲突信号**。
`lane.mjs:907-912` 的注释记录了这个级联：2026-10-07 一晚被同一缺陷撂了两次。

**处置：提交台账，恢复不变式。**

```
29d29aee chore(ledger): 同步台账至 163 已交付，清理崩溃会话遗留的脏工作区
```

提交后 `git status --porcelain` 输出为空，硬闸解除。
⚠️ 写这份会话日志后也**必须立刻提交**，否则等于亲手重建刚修好的隐患。

## 静态预检：本批新迁移干净

对已并入的 V135 / V136 / V137 与待并的 V138 定向扫描
（`ADD COLUMN` 无 DEFAULT / `SET NOT NULL` / `ADD CONSTRAINT ... CHECK`）：

| 迁移 | 命中 |
| --- | --- |
| `V135__trade_waybill_address_cancel.sql`（trade） | 无 |
| `V136__logistics_channel_config.sql`（channel） | 无 |
| `V137__vendor_supply_perspective.sql`（catalog） | 无 |
| `V138__trade_customs_declaration_elements.sql`（trade，待并） | 无 |

全量 grep 的命中项绝大多数来自**早期迁移与注释行**
（`-- 因此既没有 ADD COLUMN / SET NOT NULL` 这类自我说明），不构成本批风险。

## 未 push 的未验证提交

集成分支 `c4575495` **领先 upstream `9a24fb1c` 共 11 个提交**（含本轮台账提交），从未 push。
其中 3 条车道的 merge commit 正文是空的，**本批没有跑过 L2 全量验证**
（`.dev-agent/l2-*.log` 最新的是 10-14，10-15 没有 L2 记录）。

⇒ 次日 08:00 的正确顺序是：**先解 10.5.1.2 冲突 → 跑 L2 建立基线 → 绿了才 push 这 11 个提交**，
不要在没跑 L2 的情况下把这批推上去。

## 本轮为什么没派发

节流闸放行（距上批 179 分钟 ≥ 阈值），容量闸也全绿
（therm 无降频、load 2.09/1.78/1.64、PG 容器 0、running 0），
**但本轮是当天 cron 的最后一格（19:30）**：车道中位寿命 35 分钟 ⇒ 约 20:11 落地，
L2 约 20:25 才开始 —— 整批物理上无法在活动窗口内收口，
且会把 19:30–20:25 这段本该静默的时间变成发热窗口。

夜间静默（19:30 → 次日 08:00 不唤醒）是用户明确要求，用户还两次报过热。
叠加次日 08:00 已有「解冲突 + L2 + push」三件重活在排队，
本轮选择零派发，把机器留在空闲状态。

## 遗留待办（次日 08:00）

1. 人工解 `apps/web/src/lib/api-proxy.ts` 与 10.5.1.2 的内容冲突
2. 跑 L2 全量验证（这是当前 11 个未 push 提交**唯一**的验证依据）
3. 绿了才 `git push upstream integration/module-dev`
4. 再 `plan --lanes 4` 派发

## 落盘位置

- 本文件：`docs/sessions/2026-10/2026-10-15-1930-scheduler-stale-gate-and-merge-channel.md`
- 涉及的代码/数据文件：仅 `docs/product/capability-blueprint/module-progress.md`（由 `sync.mjs` 生成，人工未编辑）
