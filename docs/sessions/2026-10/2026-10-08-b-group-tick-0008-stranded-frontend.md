# B 组 tick 00:08 —— 17.6.1.1 前端被搁浅在未并入的 commit（停机前收敛轮）

时间：2026-10-08 00:08（距 01:00 硬停机 52 分钟）
性质：**零派发**（收敛轮，非闸 STOP）

## 结论摘要

三条车道里只有一条是真问题，且它的形态此前未被点名：

| 车道                    | 状态                 | 未并入工作量         | 判断                         |
| ----------------------- | -------------------- | -------------------- | ---------------------------- |
| `10.4.1.2` 在途跟踪     | running，心跳 5 分钟 | 3 commits / +4055 行 | 正常在途，别动               |
| `10.5.1.1` 报关单管理   | running，心跳 4 分钟 | 4 commits / +4243 行 | 正常在途，别动               |
| `17.6.1.1` 账簿与本位币 | **conflict**         | 1 commit / +1966 行  | **全部是前端，脚本永不重试** |

## 关键发现：`17.6.1.1` 的后端已并入，前端整体被搁浅

`feat/17-6-1-1` 上只剩 **1 个未并入 commit** `9754735`
「feat(ledger): 17.6.1.1 账簿与本位币收尾（页面 + 白名单 + 契约重生成）」，内容：

- `apps/web/src/components/iam/ledger-book-workspace.tsx`（+766）
- `apps/web/src/components/iam/ledger-book-model.ts` + `.test.ts`（+292）
- `apps/web/src/app/settings/books/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（白名单，+1/-1 —— 就是当年把 `settings/books` 放行的那行）
- `packages/api-contracts/src/identity.openapi.json`（+462）

已核实：以上三个前端文件在 `integration/module-dev` 上**全部 MISSING**
（`git cat-file -e` 逐个确认；集成分支上只有**兄弟模块**的 `accounting-book-*`，不是本模块的 `ledger-book-*`）。

台账行 `| 17.6.1.1 |` 状态是**进行中**，证据栏写「缺前端页面与 OpenAPI 契约重生成，后端已绿」——
**台账没有虚报**，与 commit `868938a`「把台账改回如实状态」一致。所以 108/1/356 这个数是可信的。

### 为什么它会一直卡住

`lane.mjs merge-ready` 的冲突车道只做**一次**判定：
`git merge-base --is-ancestor <headSha> <integration>` 为真才补记 `merged`，
否则只打印 ⚠ 并跳过（`lane.mjs:822-842`）。
**conflict 车道永远不会被重试合并**，所以 `9754735` 不会自己回来。

上次冲突文件：`apps/web/src/components/iam/identity-workspace.tsx`、`apps/web/src/lib/api-proxy.ts`。

### 下一轮/人工该怎么处理（不要照做顺序，要照做闸）

1. `api-proxy.ts` 是**两层 alternation** 白名单，只求并内层 `inventory/(?:…)` 会**静默丢掉外层分支**
   （外层含 `warehouses` / `goods-receipts` / `first-mile-shipments` 等）。
   要按**顶层分支**求并，不是二选一。
2. 验法不能是「冲突标记没了」，要**从前端源码反向抽真实调用点**逐条断言放行：
   `grep -rhoE "['\`\"]warehouse/[a-zA-Z0-9/_\${}.-]+['\`\"]" apps/web/src | sort -u`，
   并额外断言「抽取到 > 0 条」——抽取器坏掉返回 0 条会被当成「全部通过」。
3. 注意**契约漂移**：集成分支上已有 `4e126a2` 重生成过契约（+6 账簿 DTO），
   而 `9754735` 里又带了一份 `identity.openapi.json`。并入后应从**合并后的 spec** 重新生成：
   `cd backend && ./mvnw verify` 然后
   `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`。

## 本轮为什么零派发（四条闸其实全绿）

running 2 < 4、load 1min 2.26（且 `1min < 5min`，尖峰已过，远低于核数 10）、
Testcontainers PG 容器 **0** 个（只有常驻 `starcon-mig-v2`）、台账 465 行无重复。
按 0.2 字面判定是 GO。仍然不派发的理由：

- 距停机 52 分钟，**车道寿命实测 40–67 分钟**（今晚 10.4.1.2 / 10.5.1.1 都超了 40 分钟才接手完成）。
  新车道要「实现 + 全量 verify + land + 并入」在 01:00 前走完，概率低。
- 唯一能真正落地的就是手上这 2 条。给它们抢 CPU/风扇（用户 22:56 明确把噪音当**硬约束**），
  换一条大概率来不及 land 的车道，**风险收益不对称**。

顺带核实：`merge-ready` 的 legacy reap 只会动 `inPlace === true` 的车道，
而这三条 `inPlace` 均为空 ⇒ **不会被误扫成 ready**，跑它零风险（本轮实跑：`merged: []`，exit 0）。

## 本轮动作清单

- `lane.mjs status` / `gate status`（free）/ `uptime` / `docker ps` / 文件写入取证 —— 全只读
- `sync.mjs` → 465 模块 · 已交付 108 · 进行中 1 · 未开发 356
- `merge-ready` → `merged: []`，⚠ 点名 17.6.1.1 待人工处理，exit 0
- **未取闸、未 plan、未派发**
- 台账完整性：465 行 ✓、无重复 ID ✓
