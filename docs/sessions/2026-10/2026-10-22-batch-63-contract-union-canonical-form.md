# 2026-10-22 batch-63 —— 契约合并驱动器「逐字节一致性」修复（工具链车道）

## 摘要

本批**不交付任何 L4 功能模块**（台账不变：已交付 316 / 进行中 0 / 未开发 149），
交付的是**一条工具链修复车道**：让 git merge driver 在并入契约文件时，写出的字节
**与 codegen 的输出逐字节一致**。

起因：CI 的 `catalog-e2e` job 恒红。判据是
`pnpm contracts:generate` 之后 `git diff --exit-code -- packages/api-contracts/src` 必须为空，
而契约文件长期处于「被 driver 改写过的形状」⇒ 重新生成必然产生 diff。

**三个独立的红因**（只修一个都不够）：

| #   | 红因                             | 机理                                                                                                                                                         |
| --- | -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ①   | `DO NOT EDIT` 横幅被挪到**末行** | `merge-union.mjs:176` 把含横幅的 `o.head` 拼在合并后的导出**之后**                                                                                           |
| ②   | 类型序不是字典序                 | `mergeEntries` 输出 = ours 插入序 + theirs 独有键追加；生成器是 `sorted(schemas.items())`                                                                    |
| ③   | `.openapi.json` 形状不同         | driver 写 `JSON.stringify(a, null, 2)`（`0.000001`、无末尾换行、键序漂）；生成器写 `json.dumps(..., indent=2)` + `'\n'`（`1e-06`、有末尾换行、键序继承输入） |

取证基线（`eabd6635`）：6 个服务契约里 **5 个**横幅在末行
（catalog@864 / channel@1349 / crm@160 / trade@1718 / warehouse@1078），只有 identity.ts@159 正常；
6 个 `.openapi.json` 的键序**全部**非字典序。
**identity.ts 之所以好，是因为它从未撞过契约冲突 ⇒ 从未被 driver 改写过** ——
5 个坏文件全都是历史上某次冲突合并被 driver 重写过的。

---

## 模块 tooling.contract-union-driver 「契约合并驱动器：横幅归首行 + 类型名排序」

**这个功能做什么**：把「并行车道把契约文件合到一起」这件事，从「合出一个**和生成器不一样**的文件」
改成「合出**和生成器逐字节一样**的文件」。用户视角：CI 不再因为「代码生成物被合并改写」而恒红。

**技术落地**：

- **迁移**：无（不涉及数据库）。
- **端点**：无（不涉及 API）。
- **页面**：无（不涉及前端）。
- **改动文件**：
  - `tools/module-progress/merge-union.mjs`（TS 分支 + JSON 分支 + 去掉全局短路）
  - `tools/module-progress/json-format.py`（**新增**：与生成器同款的 JSON 序列化器）
  - `tools/codegen/catalog-contracts.py`（**只加一处** `sort_keys=True`）
  - `tools/module-progress/merge-union.itest.mjs`（回归测试）
- **测试**：`tools/module-progress/merge-union.itest.mjs` 覆盖
  横幅归首行 / 类型按名升序 / 输出与生成器期望字节**严格字符串相等** / **幂等** /
  三方语义未破（base 是裁判、两边同改取 theirs 且进 `CONFLICT_KEYS`）/ JSON 分支的
  键序字典序 + `1e-06` 浮点形态 + 末尾换行。

**关键设计决策**：

1. **driver 不手搓 JSON 序列化，改为委托 Python**（新增 `json-format.py`，内部就是生成器同款
   `json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + '\n'`）。
   **否决方案**：在 JS 里实现 Python 的 `repr`。否决理由：Python 与 JS 的浮点序列化在指数边界不同
   （`1e-6` → Python `1e-06` / JS `0.000001`；`1e-8` → Python `1e-08` / JS `1e-8`），
   **prettier 也不会帮忙**（实测四个形态喂 prettier 各自原样保留）⇒ `JSON.stringify` 无解。
   **两个写入者共用同一个序列化器**，是唯一能长期收敛的做法。
   附带：`spawnSync` 的 `maxBuffer` 放开到 256MB（trade/channel 快照 1.4–1.6MB，默认 1MB 会截断）；
   脚本路径用 `import.meta.url` 定位，**不依赖进程 cwd**；
   **python3 不可用时保留原文件内容不动 + stderr 大声告警 + 非零退出** ——
   宁可这次合并不生效，也不写出一个与生成器不一致的坏文件。
2. **生成器加 `sort_keys=True`**：让输出与「输入 spec 的键序」**彻底解耦**。
   理由：CI 的输入是 live `/v3/api-docs`（键序取决于服务返回顺序），本地闸的输入是 MockMvc 快照；
   不解耦的话，**本地闸绿也可能是假绿**。
3. **排序键用默认 `Array.prototype.sort()`（UTF-16 code unit 序），禁用 `localeCompare`** ——
   Python 的 `sorted()` 是码位序，本地化序与之不同（大小写 / 下划线 / 数字先后会差）。
4. **拆掉 `if (ours === theirs) { writeFileSync(oursPath, ours); exit(0) }` 的全局短路**：
   短路会让一份「两边一样但排版是坏的」契约**原样留在仓库里**，CI 照样红。
   短路只保留在最底层的行级兜底分支。
5. **不改 `.gitattributes`、不改 CI workflow、不改生成器的其余逻辑** —— 本车道只修「输出形状」，
   不动「谁来写」。

---

## 模块 tooling.contract-union-regen 「契约产物重生成（横幅归位 + 快照格式归一）」

**这个功能做什么**：把仓库里已经漂掉的 11 个契约产物，重新生成为 codegen 的规范形态。

**技术落地**：

- **迁移**：无。**端点**：无。**页面**：无。
- **改动文件**：`packages/api-contracts/src/` 下 **11 个**
  （`contracts/catalog.ts`、`channel.ts`、`crm.ts`、`trade.ts`、`warehouse.ts`
  - `catalog/channel/crm/trade/warehouse/identity.openapi.json`）。
    ⚠️ **`contracts/identity.ts` 与 `index.ts` 未出现在改动清单里** —— 它们从未被 driver 改写过，
    形状本来就是对的 ⇒ **Lane A 的 TS 修正在这两个文件上是 no-op**（不能拿它们当「被修过」的样本）。
- **重生成方式**：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  —— 用 **Java 契约测试导出的新鲜快照**（`backend/services/*/src/test/java/com/starcon/*/OpenApiContractTest.java`
  导出到 `backend/target/openapi/`，与 CI 用的 live `/v3/api-docs` **同源**），
  **不加 `--no-prettier`**（CI 跑的就是带 prettier 的版本）。
- **规模与性质**：**346190 增 = 346190 删 ⇒ 纯重排、零增删**。
- **数量不变核对**（排版变更 vs 内容丢失的分界线）：

  | 服务      | paths     | schemas     | 导出声明数  |
  | --------- | --------- | ----------- | ----------- |
  | catalog   | 494 → 494 | 863 → 863   | 863 → 863   |
  | channel   | 710 → 710 | 1348 → 1348 | 1348 → 1348 |
  | crm       | 91 → 91   | 159 → 159   | 159 → 159   |
  | trade     | 789 → 789 | 1717 → 1717 | 1717 → 1717 |
  | warehouse | 518 → 518 | 1077 → 1077 | 1077 → 1077 |
  | identity  | 112 → 112 | 158 → 158   | 158 → 158   |

  ⇒ **无新增、无删除** ⇒ 纯排版变更（兄弟清单里的「类别②」），**不是内容丢失**。

**关键设计决策**：

1. **用 Java 契约测试导出的新鲜快照做输入，而不是仓库里那份旧的**：
   旧快照与当前服务的语义一致性由上表保证（数量逐个相等、无增无减），
   而新鲜快照能顺带暴露「仓库快照已过期」这类问题。
2. **重生成与 driver 修复必须分两个分支、两次并入**（详见下节「合并次序」）。
3. **不手工编辑任何一个字节** —— 契约是 codegen 产物，要改就改生成器/驱动然后重新生成。

---

## 合并次序：一条容易踩、且踩了「看起来没生效」的坑

**机理**：驱动注册是**相对路径**
（`.git/config`：`merge.contract-union.driver = node tools/module-progress/merge-union.mjs %O %A %B`），
git 执行 merge driver 时 **cwd = 工作树顶层** ⇒ 跑的是**目标分支工作区里那份**驱动，
**不是被合进来那份**。

⇒ **module-dev→main 必须拆成两个 PR，顺序不可换**：

| 顺序           | PR 内容                                               | 合并时跑的驱动                                             | 结果                                                   |
| -------------- | ----------------------------------------------------- | ---------------------------------------------------------- | ------------------------------------------------------ |
| **PR#1**       | 驱动修复 + 生成器 `sort_keys` —— **不含任何契约产物** | main 的**旧**驱动，但**没有契约文件参与冲突** ⇒ 驱动不触发 | ✅ main 拿到修好的驱动                                 |
| **PR#2**       | 11 个重生成产物                                       | main 的**新**驱动                                          | ✅ 触发新驱动 ⇒ 输出 canonical 形态                    |
| ❌ 合成一个 PR | 驱动 + 产物一起                                       | main 的**旧**驱动，且契约文件参与冲突                      | ❌ 产物被重新写坏 ⇒ **现象是「修复合进去了却没生效」** |

本流水线在 **module-dev 侧**同理，所以本批就是**先并 driver 分支、再并产物分支**：

```
1884710d docs(backlog): 契约合并驱动器必须产出与 codegen 逐字节一致的产物 —— 4 条硬约束与判据 A–E
cf1174e1 merge(tooling.contract-union-driver): 契约合并驱动器：横幅归首行 + 类型名排序
2d2f2ea3 merge(tooling.contract-union-regen): 契约产物重生成（横幅归位 + 快照格式归一）
```

⚠️ 这条约束已写进 `docs/backlog/2026-10-22-contract-union-driver-canonical-form.md`，
并已知会 `main → module-dev` 同步任务。

---

## 本批验证（L2，单独一轮，在集成头上跑一次）

L2 脚本：`.tmp/l2-batch63.sh`（11 项）。契约文件本身就是 L2 的验证对象，
所以本批**单独占一轮 L2**，没有塞进任何在跑的功能批次。

| #      | 检查                                                                                                              | 结果 |
| ------ | ----------------------------------------------------------------------------------------------------------------- | ---- |
| L2-1   | 后端全量 `mvnw verify`                                                                                            | 见下 |
| L2-2   | `pnpm typecheck`                                                                                                  | 见下 |
| L2-3   | `pnpm lint`                                                                                                       | 见下 |
| L2-4   | `pnpm test`                                                                                                       | 见下 |
| L2-5   | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`                                                      | 见下 |
| L2-6   | `sync.mjs --check`（台账一致性）                                                                                  | 见下 |
| L2-7   | **契约闸（CI `catalog-e2e` 同款）**：默认 out-dir 重新生成 + `git diff --exit-code -- packages/api-contracts/src` | 见下 |
| L2-8   | **判据 E**：`--no-prettier` + 仓库内 out-dir 逐字节比对                                                           | 见下 |
| L2-9   | **逐字节不变量 C1/C2/C3**（横幅首行 / 类型升序 / json 键序字典序）                                                | 见下 |
| L2-10  | **显式**跑 `merge-union.itest.mjs`（该套件**不在 CI、也不在流水线 L2 里自动跑**）                                 | 见下 |
| L2-11  | `pnpm test:codegen`（codegen python 单测）                                                                        | 见下 |
| 补充-1 | **强制 typecheck**：`pnpm turbo run typecheck --force` + `apps/web` 的 `tsc --noEmit`（turbo 缓存不含契约目录）   | 见下 |
| 补充-2 | **判据 E 的前置核对**（口径见下）                                                                                 | 见下 |
| 补充-3 | **JS 形态指纹断言**：不得出现 `0.000001`、末尾字节须 `0a`                                                         | 见下 |
| 补充-4 | **往返一致**：`json-format.py` ≡ 生成器 `json.dumps`                                                              | 见下 |

### 判据 E 的「前置」到底是什么（口径订正，避免把它写死成永远不成立）

判据 E 要回答的是「**当前已提交的契约**，相对当前后端 spec 而言是不是 canonical 的」。
⇒ **`packages/api-contracts/` 是判据 E 的「测量目标」，不是「前置」** ——
「契约相对快照变了」恰恰是它要测的东西（本次修复做的就是让它变）。
把它当前置，会让判据 E 在修复后**永远无法成立**。

⇒ **正确的前置只有两条**：

1. 快照 6 份齐全，且每份 mtime **晚于本批产物 merge commit 的提交时间**；
2. **`backend/services/` 自快照生成以来未变**（只有后端变了，spec 才过期、判据 E 才失去意义）。

⇒ 假绿风险随之收敛为「**只有 backend 变过时判据 E 才可能假绿**」。
本批实测：前置①在 `mvnw verify` 跑完后全部满足；
前置② `2d2f2ea3..HEAD` 触碰 `backend/` 的提交数 = **0** ⇒ 成立。

### ⚠️ C3 的断言口径（别写成「必须有 `1e-06`」）

断言要写成「**不得有 `0.000001`**（JS 形态指纹）」，**不要**写成「必须有 `1e-06`」——
实测 `1e-06` 出现次数：identity **0** / catalog **2** / channel **5** / warehouse **0** / trade **0** / crm **6**；
而 `0.000001` 是 **6/6 全为 0**。写成「必须有 `1e-06`」会在 identity/warehouse/trade 上**误判 FAIL**。

### L2 实测结果（**全绿**，14:46:21 → 15:31:14 = **44 分 53 秒**）

| #      | 检查                                                                                    | 实测结果                                                                                                                                                                                                                           |
| ------ | --------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| L2-1   | 后端全量 `mvnw verify`                                                                  | ✅ **BUILD SUCCESS**，Failures 0 / Errors 0（日志尾段可见 catalog 195 例全绿）                                                                                                                                                     |
| L2-2   | `pnpm typecheck`                                                                        | ✅ 通过                                                                                                                                                                                                                            |
| L2-3   | `pnpm lint`                                                                             | ✅ 通过                                                                                                                                                                                                                            |
| L2-4   | `pnpm test`                                                                             | ✅ **462 个测试文件 / 6810 例全过**                                                                                                                                                                                                |
| L2-5   | Next production build                                                                   | ✅ 通过                                                                                                                                                                                                                            |
| L2-6   | `sync.mjs --check`                                                                      | ✅ 通过                                                                                                                                                                                                                            |
| L2-7   | **契约闸（CI `catalog-e2e` 同款）**                                                     | ✅ `contracts:generate` 通过 + `git diff --exit-code -- packages/api-contracts/src` **零差异**                                                                                                                                     |
| L2-8   | **判据 E**（`--no-prettier` + 仓库内 out-dir）                                          | ✅ **逐字节零差异**                                                                                                                                                                                                                |
| L2-9   | **逐字节不变量 C1/C2/C3**                                                               | ✅ 6/6 文件：横幅在**第 1 行**（末行 0）、类型按名升序、json `paths` 与 `components.schemas` 键序均字典序                                                                                                                          |
| L2-10  | **显式** `node tools/module-progress/merge-union.itest.mjs`                             | ✅ 通过（含新增断言：`OpenAPI json：不出现 JS 的 0.000001` ✅、`末尾有换行` ✅、`paths 键序是字典序` ✅、`components.schemas 键序是字典序` ✅）                                                                                    |
| L2-11  | `pnpm test:codegen`                                                                     | ✅ 通过                                                                                                                                                                                                                            |
| 补充-1 | **强制 typecheck**（`pnpm turbo run typecheck --force` + `apps/web` 的 `tsc --noEmit`） | ✅ **3 个包 0 缓存**（`cache bypass, force executing`）、25.1s；`apps/web` 的 `tsc --noEmit` 也单独跑过 ✅                                                                                                                         |
| 补充-2 | **判据 E 的前置核对**                                                                   | ✅ 前置①：6 份快照 mtime 全部晚于 merge commit（14:45:45）：catalog 14:51:44 / channel 15:01:03 / crm 15:29:54 / trade 15:27:38 / warehouse 15:13:00 / identity 14:47:34；前置②：`2d2f2ea3..HEAD` 触碰 `backend/` 的提交数 = **0** |
| 补充-3 | **JS 形态指纹 + 末尾换行**                                                              | ✅ `0.000001` 出现次数 **6/6 全为 0**；`1e-06` = catalog 2 / channel 5 / crm 6 / trade 0 / warehouse 0 / identity 0；末尾字节 **6/6 全为 `0x0a`**                                                                                  |
| 补充-4 | **往返一致**（`json-format.py` ≡ 生成器 `json.dumps`）                                  | ✅ **6/6 服务逐字节相同**                                                                                                                                                                                                          |

⇒ **L2 全绿 ⇒ 允许 push。**

### 本轮在 L2 期间还做的一件事（时间敏感）

`MAIN_MERGE_CRON_PROMPT.md` 是「集成分支并入 main」任务（**每半小时触发一次**）的协议来源，
它**从集成头读**。而 §0「契约产物必须拆成两个 PR」的硬约束当时**只在兄弟会话的本地分支与
PR#1 分支上，集成头没有** ⇒ **同步任务下一轮看不到** ⇒ 它会按 §7.1 的流程
（基于 main 建分支 → merge module-dev）用 **main 那份旧驱动**把已 canonical 化的 11 个产物
**重新写回 JS 形态**。

⇒ 已把 `ba98aca8`（父提交正是当时的集成头 HEAD ⇒ **零冲突快进**）推到集成头
⇒ 同步任务下一轮即可读到 §1.5（PR#1 白名单 / 开 PR#2 的放行断言 / 「不 merge 直接搬」的备选路径）。

**PR#1 分支已备好**：`origin/chore/fix-contract-union-driver` = `8627e85a`
（与 `origin/main` 差 **5 个文件**、`packages/api-contracts/` 差 **0 个**、该分支上 itest 33 断言全绿）。
**本轮没有替同步任务开 PR** —— 按它自己协议第 0 节，开 PR 合入 main 是它的职责，
且开 PR 到 main 是外部可见动作、而本轮授权只到 `integration/module-dev`；
已把完整开 PR 命令 + 放行断言 + main 侧现状实测发给它。

### 独立复核（不采信 worker 自证）

兄弟会话（batch-62）在 worker 的 worktree 里**自己重跑了一遍**（只读 + 一次生成，产物已还原）：
itest 全绿、`pnpm test:codegen` 17 例全绿、**判据 E 零差异**、幂等确认
（二次生成 `git diff --stat` 增删行数不变 346190/346190）、
`crm` 逐项形状核对（横幅首行 ✅、导出声明数 159 不变 ✅、`0.000001` 出现 **0** 次、
`1e-06` 出现 6 次、末尾字节 `0a` ✅、paths 与 schemas 键序均字典序 ✅）。
调度者侧另独立核过「数量不变」与「fresh vs committed 快照零内容差异」。

---

## 顺手修掉的仓库卫生缺口

| 缺口                                                   | 后果                                                                                                                       | 处理                                                                      |
| ------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `.gitignore` 没有 `__pycache__/`                       | 跑一次 `pnpm test:codegen` 就留下未跟踪目录 ⇒ `lane.mjs land` 的「工作区必须干净」闸会拒绝 ⇒ 任何 agent 都可能误提交字节码 | 补 `.gitignore` 条目（独立 `chore` commit）                               |
| `*.itest.mjs` **既不在 CI 里跑、也不在流水线 L2 里跑** | 回归测试写了也没人执行 ⇒ 形同虚设                                                                                          | 本轮 L2 **显式**跑了 `merge-union.itest.mjs`；**「接进 CI」是未做的待办** |

---

## 后续待办（**未完成**，别当已完成）

0. **`*.itest.mjs` 回归测试套件（13 个）目前跑在「没人跑」的位置** —— CI 零引用、
   `package.json` 无 script、流水线 L2 清单里也没有 ⇒ **本批新写的 `merge-union.itest.mjs`
   （33 条断言）同样不在任何自动路径上**：它被 worker 与调度者各**手动**跑过一次，
   但下一轮流水线不会自动跑。
   ⇒ 本轮处置：**在本地 `.dev-agent/l2-run.sh` 里补上两条**（本地文件，不进版本库，
   不构成协议改动）——

   ```
   node tools/module-progress/merge-union.itest.mjs
   python3 -m unittest discover -s tools/codegen -p 'test_*.py'
   ```

   ⚠️ **进版本库的根治方案仍未做**：应写进 `PIPELINE.md` 的 L2 清单或 CI 的 test job ——
   这属于协议改动，**等用户定夺**，不在本批擅自改。
   ⇒ 同类问题还有「契约类变更必须 `--force` typecheck」（turbo 的 input hash 不覆盖契约目录），
   建议与上述三条协议建议并列，作为第 4 条协议纪律。

1. **main 侧必须实跑契约闸**：PR 合到 main 后在 main 上跑
   `pnpm contracts:generate && git diff --exit-code -- packages/api-contracts/src`。
   ⚠️ **本地无法代验**（CI 用 live spec，本地闸用 MockMvc 快照）⇒ 由 PR 后的 CI / 实跑确认。
   本批 L2 **只覆盖 module-dev 侧**。
2. **module-dev→main 拆两个 PR**（见「合并次序」），PR#1 不得含契约产物；
   放行前先确认 **main 侧 `merge-union.mjs` 已是修复版**。
3. **把 `tools/module-progress/*.itest.mjs` 接进 CI 或流水线 L2**（当前无人自动执行）。
4. 三条协议建议待用户定夺（**尚未改动协议文件**）：
   「同一 worktree 禁止并发构建」「释放闸前必须做完所有写操作」「`lane.mjs merge-ready` 加闸校验」。

---

## 文件清单

| 文件                                                                         | 动作                                                                                      |
| ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `tools/module-progress/merge-union.mjs`                                      | 改（TS 分支 + JSON 分支 + 去全局短路）                                                    |
| `tools/module-progress/json-format.py`                                       | 新增                                                                                      |
| `tools/codegen/catalog-contracts.py`                                         | 改（只加 `sort_keys=True`）                                                               |
| `tools/module-progress/merge-union.itest.mjs`                                | 改（加回归测试）                                                                          |
| `packages/api-contracts/src/**`（11 个）                                     | 重生成                                                                                    |
| `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`                            | 改（快进 `ba98aca8`：§1.5「契约产物必须拆成两个 PR」硬约束 ⇒ 同步任务**下一轮就能读到**） |
| `docs/backlog/2026-10-22-contract-union-driver-canonical-form.md`            | 新增（4 条硬约束 + 4 个缺口 + 判据 A–E）                                                  |
| `docs/sessions/2026-10/2026-10-22-batch-63-contract-union-canonical-form.md` | 新增（本文件）                                                                            |
| `.gitignore`                                                                 | 改（补 `__pycache__/`）                                                                   |
| `docs/product/capability-blueprint/module-progress.md`                       | 无变化（本批不交付 L4 模块，台账 316/0/149 不变）                                         |

**关联 backlog**：
`docs/backlog/2026-10-22-contract-union-driver-canonical-form.md`（本批新增，耐久约束）、
`docs/backlog/2026-10-22-contract-union-byte-stability-next-sync-checklist.md`（**第 27 轮 main→module-dev 同步任务**写的下一轮同步校验清单，v4 已加 §0 次序硬约束）、
`docs/backlog/2026-10-20-contract-drift-ci-gate.md`（原始取证）。

### 一条有意识的「没做」：没有替同步任务 prettier 化它的 checklist

`docs/backlog/2026-10-22-contract-union-byte-stability-next-sync-checklist.md`
目前**不符合 prettier**（`prettier --check` 报 warn）。**本批没有替它格式化**，理由三条：

1. **仓库惯例如此**：`.prettierignore` 只排除 codegen 产物与台账，`docs/**/*.md` 里
   **257 个文件**不符合 prettier（全仓 622 个）⇒ docs 不 prettier 化是**项目现状惯例**，
   为其中一篇做对齐反而与惯例不一致。
2. **CI 的 Lint job 早已因格式债而红**（`ci-gate.mjs` 记录的基线里就有 `Lint`），
   少 1 个文件不改变任何结论。
3. **该文件归第 27 轮同步任务所有且正被它高频改写**（14:25 与 14:47 两次重写，233 行）——
   调度者代改别人正在改的文档，正是第 24/25/26 轮那三轮「3 文件 11 区 add/add 同墙」的成因。

⇒ 处置：**留给文件所有者**。本批自己新增的两个 `.md`（本文件 + 耐久约束 backlog）
**已 prettier 化并复查通过**（不与惯例冲突的方向：clean 永远不是错）。

### 归属更正（记录在案）

本轮曾观测到 `9c24313b`、`e328e9c2` 两个 backlog 提交在**调度者持闸期间**落到集成头，
一度被误记为 batch-62 流水线会话所为；复核 `git log --format='%an'` 确认二者 author 均为
**第 27 轮 main→module-dev 同步任务**（git 身份 `yuntong guan`）。
⇒ 协议观察本身成立（**持闸期间不应有第二个会话改集成头**），观察对象是同步任务而非 batch-62。
本轮未造成损害（纯文档提交、工作区干净、调度者的两次 merge 均无冲突），
但**已知的失败模式是 MERGE_HEAD 残骸与「工作区不干净」堵死合并通道** ⇒ 建议后续：
兄弟会话要往集成头写文档时，**先取闸**，或交由调度者代提交。
