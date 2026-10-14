# 2026-10-11 契约平铺覆盖：加碰撞闸门 + 拆掉最后一颗有损重名

## 结论

`docs/backlog/2026-10/2026-10-10-contract-flat-overwrite-and-nav-gaps.md` 里的 **建议方向 1 已落地**：
codegen 从「静默覆盖」改成「分级判定 + 响亮失败」。同时把当时唯一那条有损重名
（`DownstreamGap`，trade 静默顶掉 warehouse）也拆掉了。

- 闸门测试 11 条全绿，已挂进 CI `test` job（`pnpm test:codegen`）
- trade 后端 155 例全绿（`./mvnw -pl services/trade -am test`）
- 前端 `turbo run typecheck --force` 真跑一遍（0 cached）零错误
- 契约生成幂等，重跑不产生新 diff，满足 CI 的 `git diff --exit-code`

## 做了什么

### 1. 闸门（`tools/codegen/catalog-contracts.py`）

写出任何文件**之前**扫跨服务重名，分三级：

| 情况                     | 行为                                        |
| ------------------------ | ------------------------------------------- |
| 结构完全相同             | `note` 提示，不拦（没有东西被吃掉）         |
| 结构不同且不在 allowlist | 打印冲突明细 → **非零退出，一个文件都不写** |
| 结构不同但在 allowlist   | `ALLOW` 提示，说明保留的是哪一版            |

allowlist 是 `tools/codegen/contract-collision-allowlist.json`（当前为空），
entry 落在 code review 的 diff 里；里面已经没有重名的条目每次跑会提示可删，防腐烂。

### 2. 拆雷

trade `ProcessingModels.ProcessingCapabilities.DownstreamGap` → **`ProcessingDownstreamGap`**。
warehouse 的同名类型恢复成它本来的 5 字段形状；`ProcessingCapabilities.downstreamGaps`
改指 `Array<ProcessingDownstreamGap>`，前端 `processing-order-workspace.tsx:203` 读的
`gap.ownerModule` 仍在，typecheck 无变化。

命名沿用同文件 `SalesOrderDownstreamGap` 已有的约定（带模块前缀），并在 javadoc 里
记下了这次撞名的来龙去脉。

## 关键 trade-off

### 1. 为什么「结构相同」不拦

同名的两份如果逐字节相同，合并**没有东西被吃掉**，拦它只是噪音。
但它仍然是潜在风险：谁改了一边，它立刻升级成字段级覆盖 —— 所以每次跑都打 `note`，
让「这份定义现在有两处」这件事保持可见。

### 2. 为什么中止时不写「半个 catalog.ts」

早退发生在落盘之前。spec 快照 + `catalog.ts` 要么全写要么全不写：
留一个被截断的 `catalog.ts` 比明确失败难查得多。
为此把 `load_specs` 拆成纯读取，与落盘彻底分开。

### 3. 报告的覆盖方向

`all_schemas.update()` 按 `SERVICES` 顺序逐个覆盖，所以**后跑到的赢**。
报告必须说清「谁凭空多出字段 / 谁独有字段会消失」，而不是笼统说「有冲突」。

## 踩过的坑（已写进测试）

**1. 报告的覆盖方向写反了。** 第一版拿 `holders[0]` 当基准，
报「trade 会丢 `ownerModule`」——实际是 trade 排在 warehouse **之后**，
trade 赢，warehouse 那份被顶掉。已提交到 `catalog.ts` 的旧内容佐证了这一点。
现在 `describe()` 以 `holders[-1]` 为赢家，并且 `collisions()` 显式按 `SERVICES` 顺序遍历，
不依赖 dict 插入顺序这种隐含前提。

**2. 护栏必须先证明自己会红。** 同一组 fixture 对照旧脚本跑：
旧脚本 exit 0、照写不误、warehouse 的 2 字段类型被静默替换成 trade 的 3 字段版本。
所以「exit 1 + 不落盘」这条断言是真护栏。
（第一次标定本身还翻车了：拿 `--out-dir` 去喂旧脚本，它不认识这个参数，argparse 直接 exit 2 ——
那是我的探针错，不是旧行为。改用目录布局把它的输出重定向到临时区才复现出真实行为。）

**3. 测试会抓到我自己的错。** `test_describe_reports_the_winner_and_the_vanishing_fields`
第一次跑是红的，原因是**断言写错**（我以为「凭空多出」那行属于赢家），
代码是对的。这类红要先判「是代码错还是断言错」，别直接去改产品代码。

**4. `pnpm typecheck` 的 `FULL TURBO` 不算证据。** turbo 命中缓存时只回放日志，
根本没跑 tsc。验证契约改动必须 `--force`，否则「全绿」只是缓存没失效。

## 文件清单

| 文件                                                                      | 变更                                                                                            |
| ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `tools/codegen/catalog-contracts.py`                                      | 拆 `load_specs` / `collisions` / `describe` / `gate`；加闸门；新增 `--out-dir`、`--no-prettier` |
| `tools/codegen/contract-collision-allowlist.json`                         | 新增（空清单，机制占位）                                                                        |
| `tools/codegen/test_catalog_contracts.py`                                 | 新增 11 条护栏测试                                                                              |
| `backend/services/trade/.../ProcessingModels.java`                        | `DownstreamGap` → `ProcessingDownstreamGap` + javadoc 记因                                      |
| `backend/services/trade/.../ProcessingOrderService.java`                  | 4 处构造点同步改名                                                                              |
| `packages/api-contracts/src/catalog.ts`                                   | 生成物：`DownstreamGap` 恢复 5 字段；新增 `ProcessingDownstreamGap`                             |
| `packages/api-contracts/src/trade.openapi.json`                           | 生成物                                                                                          |
| `package.json` / `.github/workflows/ci.yml`                               | 新增 `test:codegen` 并挂进 CI `test` job                                                        |
| `docs/backlog/2026-10/2026-10-10-contract-flat-overwrite-and-nav-gaps.md` | 第一节标记已解决                                                                                |

## 后续待办

- [ ] **建议方向 2（根治）**：`catalog.ts` 按服务分文件，由 `index.ts` 再导出。
      彻底消除平铺冲突，但牵动所有 import，留给独立车道。
- [ ] `Dimension` 在 warehouse / trade 两处定义完全相同。现在每次跑打 `note`；
      哪天有人改了一边就会自动升级成拦截。也可以主动给它加模块前缀收敛掉。
- [ ] 同文件里的另外两节**未动**：导航未注册（`/crm/knowledge`、`/aftersales/knowledge`）、
      catalog 既有 flaky 测试（`ZombieCandidateIntegrationTest`）。
- [ ] 观察项：`pnpm format:check` 在本仓库对 489 个文件报红（含 README.md、turbo.json 等
      本轮未触碰的文件），CI 的 `lint` job 会跑它。属既有状态，本轮未扩大也未修。
