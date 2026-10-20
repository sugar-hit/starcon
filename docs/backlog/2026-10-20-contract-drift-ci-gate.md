# 契约漂移无人看守 —— 缺一个 `contracts:check` CI 闸

- 创建: 2026-10-20
- Owner: TBD（工具链 / 流水线侧）
- 优先级: **P1**
- 关联: `docs/sessions/2026-10/2026-10-20-batch-36-merge-4-lanes-and-l2.md` ·
  `docs/backlog/2026-10-13-contract-dto-name-collision.md` ·
  `tools/codegen/catalog-contracts.py` · `packages/api-contracts/`

## 背景

2026-10-20 批次 36 的 L2 第一次全量跑红，根因是 **D1**：

> catalog 侧 `CostCapabilities` 与 channel 侧 `CostCapabilities` **同名但字段结构不同**，
> 两者都 `export *` 进 `packages/api-contracts/src/index.ts` ⇒ TS2308「重复导出」。

追责链是：**两条车道各自在**自己那条分支**上跑 codegen 都是绿的**
（每条车道只看自己那一个服务的 spec，撞名在合流时才会同时出现），
直到 **L2 的 `next build` 才第一次把两条车道的产物放在一起编译** ⇒ 红。

**土壤在于：仓库里没有任何一道闸保证「入库的契约快照 = 后端当前 spec」。**

- `node tools/module-progress/sync.mjs --check` 检查的是**模块台账**（465 行进度表），**不是**契约；
- `pnpm contracts:generate`（= `tools/codegen/catalog-contracts.py`）**只生成不校验**，
  没人 CI 里跑它再 `git diff --exit-code`；
- `packages/api-contracts/src/*.openapi.json` 与 `packages/api-contracts/src/contracts/*.ts`
  是**多车道共用文件**，每条车道都可能在里面写东西，但没人核对整体一致性。

## 后果

1. 跨车道契约撞名/漂移**只能在 L2 才暴露** ⇒ 每批一次全量验证（1.5h+）被烧掉；
2. 撞名的处置成本高（本轮要走一条独立修复车道 + 一次全量重跑）；
3. 契约陈旧（后端加了端点但快照没重新生成）同样**无人发现** ——
   本轮就差点误报一次（复核后确认已被顺带刷新，见会话日志 §遗留问题 1）。

## 建议做法

### ① 加一个 `contracts:check` 脚本（最小可行）

```bash
# 1) 用后端当前 spec 重新生成到临时目录
# 2) 与入库快照逐字节比对
# 3) 不一致 ⇒ 非零退出并打印差异文件清单
```

关键点：**生成必须落在临时目录**，不能就地覆盖 —— 否则「校验」本身就变成了
「顺手把漂移抹掉」，CI 永远绿。

现成零件：

- `tools/codegen/catalog-contracts.py` 已支持 `--spec-dir`；
- 它对**跨服务同名 DTO** 会在**写出任何文件之前**就非零退出并点名（正是本轮 D1 被抓住的机制）——
  `contracts:check` 可以直接复用这个 gate。

### ② CI 里挂上

在 `.github/workflows/` 的 lint/typecheck 之后加一步 `contracts:check`：

- 需要 6 个服务的 openapi spec 齐备（`backend/target/openapi`），
  所以这一步**依赖后端构建产物**，或需要一条单独的轻量 codegen job；
- 若成本太高，退而求其次：**每次后端 DTO 变更的 PR 上跑**（按路径过滤 `backend/services/*/src/main/java/**`）。

### ③ 顺带把「谁可以写契约文件」写清楚

`packages/api-contracts/` 是**多车道共用文件**（README / `.gitattributes` 已有约定）。
建议在 `AGENTS.md` 与 `packages/api-contracts/README.md` 里补一句：

> 车道**只提交自己那个服务的** `*.openapi.json` + `contracts/<service>.ts`；
> `index.ts` 由 codegen 派生，**冲突时一律改名、不许写进 allowlist 接受覆盖**。

（`tools/codegen/contract-collision-allowlist.json` 至今保持 `{"allowed": []}` —— 这条要守住。）

## 判据

- 在**故意让一个服务的 spec 与快照不一致**的分支上跑 `contracts:check` ⇒ 必须非零退出并点名该服务；
- 在干净集成头上跑 ⇒ 必须退出 0 且**不产生任何工作区改动**。

## 不属本 backlog 的部分

- **别动 allowlist**：撞名一律改名。已有先例 —— 10.3.1.1 把 `TrackingEventRow` 改成
  `CarrierTrackingEventRow`，本轮把 channel 侧 `CostCapabilities` 改成 `LogisticsCostCapabilities`。
- **别在批次中途改**：这条属于工具链/流水线域，建议**单独派一条修复车道**，
  不要塞进任何一个正在跑的功能批次（批次 36 的教训：改集成头的验证对象会让 L2 失去意义）。
