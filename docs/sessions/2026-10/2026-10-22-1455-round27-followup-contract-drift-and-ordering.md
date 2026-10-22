# 2026-10-22 14:55 集成头同步 main 第 27 轮（续）—— 契约漂移取证、§0 次序约束发现、归属更正

## 摘要

第 27 轮同步任务在完成「合并落地 + L2 全绿 + 推送」之后的**续作**：
与 batch-62 流水线会话往返取证，定位出 **merge-union 驱动污染契约产物**的根因与一条会决定成败的
**次序硬约束**，并把下一轮同步的验收判据固化成清单
`docs/backlog/2026-10-22-contract-union-byte-stability-next-sync-checklist.md`。

**本续作期间未再执行任何合并**（`origin/main` 在本续作期间未推进到需要合并的新内容），
**只产出文档与取证结论**。

---

## 一、⚠️ 归属更正（按事实记）

14:00 流水线 tick 提出「持闸期间有兄弟会话往集成头落文档」这一协议观察，
并**一度以为那两次提交是 batch-62 流水线会话写的**。核 author 后确认：

```
9c24313b | yuntong guan | docs(backlog): 下轮同步的契约逐字节校验清单 …
e328e9c2 | yuntong guan | docs(backlog): 契约逐字节校验清单 v4 …
```

⇒ **这两个 commit 是本会话（第 27 轮同步会话）写的**，不是 batch-62 流水线会话。
batch-62 流水线会话在集成头上的最后一次提交是 `4d85131f`（4.5.2.1 会话日志补记），此后只做复验。

⇒ **协议观察本身成立**（持闸期间确实有兄弟会话改了集成头），
**但对象是本会话** ⇒ 本会话在此明确记录：**是本会话在 tick 持闸期间落了文档提交**。

**本会话在集成头上落过的全部提交**（供追溯）：

| commit     | 内容                                            |
| ---------- | ----------------------------------------------- |
| `bc575ca8` | 第 25 轮会话记录 + merge-abort backlog          |
| `df742203` | 第 27 轮会话记录（合并落地那轮）                |
| `9c24313b` | 契约逐字节校验清单 v3                           |
| `e328e9c2` | 契约逐字节校验清单 v4（§0 次序约束）            |
| `2d39fa4b` | 契约逐字节校验清单 v5（修判据 E 前置 + 判据 G） |

⇒ **教训**：持闸是**跨会话互斥**，不只对流水线内部有效。
下轮同步取闸后，在闸释放前**不应**向集成头提交任何东西；
需要留痕的取证结论先写 `/tmp`，闸释放后再一次性提交。

## 二、根因（源码级）

`tools/module-progress/merge-union.mjs` 会在合并时**改写契约文件**，两个缺陷：

1. **横幅被挪到末行**（第 176 行）：
   `writeFileSync(oursPath, \`${merged.map(([, v]) => v).join('\n')}\n${o.head.join('\n')}\`)`——`o.head` 被追加在最后。
2. **不做任何排序**：`.ts` 用 Map 插入序写出；`.json` 是 `JSON.stringify(a, null, 2)`。
   全文件 `grep -nE 'sort|sort_keys|localeCompare'` ⇒ **零命中**。

⇒ 第 176 行在 `origin/main` 上**逐字节相同** ⇒ 缺陷先于双方，**不是任何一轮同步引入的回归**。

## 三、实测漂移量（canonical 生成物 vs 已提交）

用 L2 跑出的活 spec（`backend/target/openapi/*.openapi.json`）喂生成器到临时目录再逐字节比：

| 文件        | `.ts` 差异行 | `.json` 差异行 | 成因                                         |
| ----------- | ------------ | -------------- | -------------------------------------------- |
| `identity`  | **0**        | **0**          | **从未被驱动改写** ⇒ 与 canonical 逐字节相同 |
| `catalog`   | 2            | 10             | 横幅 + 浮点字面量 + 末尾换行                 |
| `warehouse` | 2            | 2              | 横幅 + 末尾换行                              |
| `channel`   | 320          | **52 762**     | 横幅 + 类型序 + **json 键序**                |
| `crm`       | 160          | **10 672**     | 同上                                         |
| `trade`     | 248          | **98 538**     | 同上                                         |

四种成因：① 横幅末行（5 个文件）；② 类型序被打乱（channel/crm/trade）；
③ json 键序被打乱（同上三个）；④ JS↔Python 序列化差异
（`1e-06`↔`0.000001`、`1.0`↔`1`、`0.0`↔`0`、末尾换行）—— 后四个是「JS 产物」的判定指纹。

## 四、§0 次序硬约束（已用最小复现实证，非推测）

git 执行 merge driver 时 **cwd = 工作树顶层**，驱动注册是相对路径
⇒ 解析到的是**合并瞬间工作树里那份**（= 当前检出分支 main 的版本），**不是被合进来那份**。

最小复现（`/tmp/drivertest4`）：main 驱动 V1、待合分支驱动 V2 且两边都改了同一文件：

```
WHICH driver version ran?   DRIVER_VERSION=V1     ← main 的合并前副本
working-tree probe.mjs      DRIVER_VERSION=V2     ← 被合进来的那份（驱动跑完后才写进工作树）
cwd                         /private/tmp/drivertest4  ← 工作树顶层
```

⇒ **module-dev→main 必须拆成两个 PR，顺序不可换**：
**PR#1**（驱动修复 + 生成器 `sort_keys` + `__pycache__` gitignore，**不含任何契约产物**）先落地 main，
**PR#2**（11 个重生成产物）再走。
⇒ 合成一个 PR ⇒ 11 个契约文件全部参与冲突 ⇒ 旧驱动把**全部**写回 JS 形态
⇒ main 的 CI 继续红，现象是「修复合进去了却没生效」——最难查的那种。

**守卫断言（机器可判）**：

```bash
git show origin/main:tools/module-progress/merge-union.mjs | grep -q 'json-format.py' \
  && echo "OK：可放 PR#2" || echo "STOP：只能放 PR#1"
```

**当前实测（2026-10-22 14:5x）：`STOP`** —— 驱动修复与 11 个产物**都已在 `origin/integration/module-dev`**，
**都还不在 `origin/main`**；module-dev vs main 契约差异面 = **11 个文件**，
`11 files changed, 347473 insertions(+), 347473 deletions(-)`（增删相等 ⇒ 纯重排零丢失）。

## 五、清单的两次自我纠错

1. **判据 E 的前置条件写错了（v4 → v5 修正）**：
   v4 把「快照生成以来 `backend/services/` 与 `packages/api-contracts/` 都未变过」列为两条前置 —— **错**。
   `packages/api-contracts/` 是判据 E 的**测量目标**，不是前置；
   只有**后端代码变了**才会让快照里的 spec 过期、让判据 E 失去意义。
   ⇒ 前置收敛为：① 快照 6 份齐全且 mtime 晚于本轮 merge commit；② `backend/services/` 未变。
   ⇒ 假绿风险也随之改写：**只有 backend 变过时判据 E 才可能假绿**。

2. **新增判据 G —— 回归测试必须显式执行**：
   实测确认 `*.itest.mjs` **CI 不跑、L2 不跑、任何 script 都不跑**
   （vitest include 只有 `apps/**` 与 `packages/**` 的 `*.test.ts`；
   `lane.mjs:927` 对 `merge-union.itest.mjs` 的引用是**未跟踪文件清理白名单**，不是执行点）
   ⇒ 仓库 13 个 itest「形同虚设」⇒ 下轮必须显式跑。
   ⇒ 本轮实跑：itest「全部通过」exit 0、codegen 17 例 OK exit 0。

## 六、两条新发现的**验证盲区**（L2 层的）

本续作在核对「为什么回归测试形同虚设」时，顺带查出 L2 层的两个盲区：

1. **turbo 缓存不覆盖 `packages/api-contracts/`** ⇒ 缓存会骗人。
   实测：`packages/api-contracts/` **没有 package.json** ⇒ **不是 turbo 包**；
   `turbo.json` 各 task 的 `inputs` 是 `["src/**", "package.json", ...]`（**按包作用域**）；
   `globalDependencies` 只有 `["**/.env.*local", "tsconfig.base.json"]`；
   `turbo run typecheck` 的 scope 是 `@starcon/config-tailwind, @starcon/ui, @starcon/web`。
   ⇒ **改契约不会让 turbo 任何 task 的缓存失效** ⇒ `pnpm typecheck/lint/test` 可整段 replay 旧缓存。
   ⚠️ 且协议里**并不存在**「契约类变更强制 `--force` typecheck」这条规则 ——
   `PIPELINE.md` / `PIPELINE_CRON_PROMPT.md` / `.dev-agent/l2-run.sh` 里 `--force` **一次都没出现**；
   三处 `force` 字样全在说「不要 force 清掉别人持有的调度闸」（与 turbo 无关）。

2. **集成头的 L2 从不验证契约是否 canonical**。
   `.dev-agent/l2-run.sh` 四步实测 = `mvnw verify` / `pnpm typecheck && pnpm lint && pnpm test`（**无 `--force`**）
   / `Next build` / `sync --check` ⇒ **四步里没有 `contracts:generate`**。
   ⇒ canonical 闸目前**只存在于 CI**（`ci.yml:140-141`）⇒
   **契约类变更在集成头上是「无闸区」**，流水线本地拦不住。
   ⇒ 判据 I 就是为补这道闸而设。

## 七、⚠️ 一条给所有写 `.md` 的人的教训

**CI 的 `Lint` job 跑 `pnpm format:check`（`.github/workflows/ci.yml:26`），
覆盖 `**/*.md`** ⇒ **文档也是格式闸门**，新增/修改 `.md` 若不是 prettier-clean，
**CI 直接红**，不只是「格式债」。

⇒ 本会话写的清单文件（`...byte-stability-next-sync-checklist.md`）就踩了这个坑：
`pnpm exec prettier --check` 报 `[warn] Code style issues found`。
⇒ **以后每次更新该清单，先 `prettier --write` 再提交。**
（prettier 会把紧凑表格重排成对齐表格 ⇒ 属纯排版，但会放大 diff，
是否为此单独起一个 commit 自行判断。）

### ⚠️ 补充（15:5x 实测）：`Lint` 早已是红的，判据要看**差集**

`pnpm format:check` 全仓实测 **620 个文件不合 Prettier**（多为历史 `.tsx` / `.test.ts`）
⇒ **`Lint` job 长期红，这是存量债**，不是本轮合入造成的。

而近期三个提交（`937e2430` / `ef45304a` / `50bc8264`）改动的每一个源文件
**prettier 逐文件全过** ⇒ **本轮新增格式债 = 0**。

⇒ ⚠️ **汇报口径**：报「**本轮改动文件的 prettier 失败数 = 0**」，
**不要**报「仓库总数 620」—— 报总数会被当成回归查一遍。
（这条已写成清单 §2「已知缺口五」与汇报义务第 11 条。）

⇒ 本会话清单文件的 prettier 修复**已由自执行脚本完成**（提交前 `prettier --write`），
**不需要再找 14:00 tick 补这一步**。

## 八、本续作的边界

- **未执行任何合并**（`origin/main` 期间未推进到需合并的新内容）。
- **未改任何业务代码**；仓库里的契约产物一个字节都没被本会话改过
  （生成器只写到 `/tmp/canon_contracts` / `/tmp/canon_e`）。
- 跑 codegen 测试产生的 `tools/codegen/__pycache__/` **每次都清掉了**（它未被 gitignore，
  会脏工作区、挡下游 `merge-ready`）。
- 闸由 14:00 tick 持有期间，本会话**未向集成头提交任何东西**（取证写 `/tmp`）。

## 九、后续待办

1. **等 `origin/main` 上的驱动修复落地**（守卫断言转 OK）⇒ 才能放 PR#2（11 个产物）。
2. 下轮同步按清单八组判据 **A / B / B' / C1-C3 / D / E / F / G** 逐条执行并出逐条结果。
3. `__pycache__` 的 gitignore 条目必须随 PR#1 一起进（实测仍未进集成头）。
4. `*.itest.mjs` 长期需要接进 CI 或 L2（**未做**）—— 否则判据 G 每轮都只能靠人工显式跑。
