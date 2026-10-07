# 2026-10-07 3.8.1.1 导入向导并入冲突收尾

车道 `3.8.1.1` / 分支 `feat/3-8-1-1`，锚点 L3 `3.8.1`。本轮不做开发，只做**并入收尾**：同步基线、解冲突、验证、落地。

## 决策摘要

1. **把集成头合进车道分支**（不是反过来），一次 merge 收掉 20 个提交的落后，产出 merge commit `175398b`（父 `28a845a` + `071ef34`）。
2. **`api-proxy.ts` 冲突两边都留**。该白名单是一整行 3800+ 字符的巨型正则，不是多行数组，git 只能文本冲突。两侧各有对方没有的路由段，正确解法是合并成一条正则而不是二选一。
3. **迁移保持 `V29__import_wizard.sql`**，不改台账记的 V32。
4. **台账重复行去重并恢复 3.8.1.1「已交付」**——并集驱动把三个模块的台账行各追加了一份。

## 关键 trade-off

### 冲突为什么必须两边都留

`apps/web/src/lib/api-proxy.ts` 的 catalog 白名单是单行正则。两边各自新增：

- 集成头（3.6.1.1）：`parties(?:/(?:overview|candidates|[0-9a-f-]+(?:/(?:status|roles(?:/[0-9a-f-]+)?))?))?`
- 本车道（3.8.1.1）：`import-templates|import-field-mappings|import-jobs` 三段（挂在 `bom-explosions` 之后）

实测两侧各有对方没有的内容，不是同一处被改坏，所以任何「选一边」的做法都会静默删掉另一组端点。
解法：保留车道原有布局，把 `parties(...)` 插到 `brands(...)` 之后（与 `categories(...)` 之前），两边注释块都保留。

**这是安全边界**：少一段 = 那一段端点前端 404；正则写坏 = 整份白名单崩掉、所有 API 全挂。
`services` 未导出，无法写单测覆盖，故用一次性脚本对解后正则做行为验证（结果见「验证」）。

### 为什么保持 V29 而不是台账里的 V32

台账把本车道预分配成 V32，是调度器重新 plan 时改的，与磁盘上实际落盘的 `V29__import_wizard.sql` 不一致。

- 集成头最高是 `V28__physical_safety_attributes.sql`，**V29 未被占用**
- V30 已被 3.6.1.2（角色档案）、V31 已被 3.6.3.1（地址与联系人）预分配
- 改成 V32 → V29/V30 之间留空洞；改成 V29 以外任何号 → 与那两条车道撞号

保持 V29：连续、无冲突、无空洞。

### 台账重复行：并集驱动的已知缺陷复发

合入后的台账里 `3.6.1.1` / `3.7.2.1` / `3.8.1.1` **各出现两行**（一行「未开发」、一行「已交付」）。
这与集成头 `071ef34` 修过的 3.6.1.1 是同一个缺陷，说明并集驱动对台账行仍然会追加而非合并。

`sync.mjs` 的去重**保留后出现的那行**：

- 3.6.1.1 / 3.7.2.1：顺序是「未开发 → 已交付」→ 侥幸保留正确
- 3.8.1.1：顺序是「已交付 → 未开发」→ **状态被静默回退成未开发**

因此不能只跑一次 `sync.mjs`，必须再把 3.8.1.1 的已交付行写回去再 sync 一次（写回后 summary 计数需重算，
所以顺序是「写回正确行 → 再 sync → check」）。最终三个模块均 1 行「已交付」，`--check` 绿。

## 文件清单

| 文件 | 动作 |
| --- | --- |
| `apps/web/src/lib/api-proxy.ts` | 手工解冲突：合并两条路由段，保留双方注释块 |
| `docs/product/capability-blueprint/module-progress.md` | 去重 3 行，恢复 3.8.1.1「已交付」，第 03 卷计数 28→29 |
| 其余合并内容 | 来自集成头（parties / physical-safety / V27 / V28 / 会话日志），未改动 |

迁移文件 `V29__import_wizard.sql` **未改名、未改动**。

## 验证

| 命令 | 结果 |
| --- | --- |
| 白名单行为脚本（一次性，`.tmp/`） | 24 正例全中 / 7 反例全拒，0 失败 |
| `backend/./mvnw verify` | **BUILD SUCCESS**，catalog 889 tests / 0 failures（导入向导 59 例：16+24+19） |
| `pnpm test` | 40 files / **681 tests 全绿** |
| `pnpm lint` | exit 0（仅 `variant-combination-workspace.tsx` 既有 warning） |
| `node tools/module-progress/sync.mjs --check` | ✓ 台账与蓝图一致 |
| `pnpm typecheck` / `next build` | ❌ 红 —— **集成头既有基线问题，非本车道引入** |

### 集成头基线红的确证

`packages/api-contracts/src/catalog.ts` 在集成头已提交的状态下**单独**跑 `tsc --noEmit` 即报 6 个 `TS2300`
（`PhysicalSafetyInput` / `Profile` / `Row` 各重复声明两次）。而同一集成头的 `catalog.openapi.json` 里
`"PhysicalSafetyProfile"` **只有 1 处**——说明是集成头已提交的**生成物**被并集驱动污染，不是 spec 本身重复。
本车道未触碰任何 PhysicalSafety 文件（`physical-safety-model.ts` 与集成头逐字节相同）。

## 后续待办

1. **【高】修集成头的 `packages/api-contracts/src/catalog.ts` 重复声明**。它是生成物，重新从 `catalog.openapi.json`
   生成即可（spec 是干净的）。在此之前 `@starcon/web` 的 `typecheck` 与 `next build` 全红，**任何车道落地都会被卡**。
2. **【高】并集驱动对台账行仍会追加重复行**，`071ef34` 只修了 3.6.1.1 这一个具体行，没有修驱动本身。
   下次并入若台账出现重复行，必须人工核对每个模块保留了哪一份，**不能只看 `--check` 是否绿**。
3. `sync.mjs` 的去重「保留后出现者」这个策略建议改成「保留状态更靠前者」或直接报冲突，避免静默回退。
4. 3.6.1.2（V30）与 3.6.3.1（V31）并入后集成头迁移推进到 V31，本车道 V29 保持连续，无需改动。