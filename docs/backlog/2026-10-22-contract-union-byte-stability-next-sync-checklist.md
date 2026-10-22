# 下轮同步的契约逐字节校验清单（merge-union 修复落地后必做）

**优先级：P1 —— 不是 bug，是「修复落地那一轮同步」的验收清单。**

**状态：修复车道已提交 `e5cb2f11`（驱动修复 + 生成器 `sort_keys`）但**尚未落地到 `origin/main`**；
11 个契约产物待提交（第 2 笔）。落地后由 batch-62 流水线会话知会同步任务，
下一轮 `main → integration/module-dev` 同步必须按本清单逐项校验。**

> 本清单的数据全部是 **2026-10-22 14:3x–14:5x 实测**（`origin/integration/module-dev` = `75ef992a` 的树）。

---

## §0 ⭐ 硬前置：module-dev→main 的**次序约束**（做任何 module-dev→main PR 之前必查）

### 机理（已用最小复现**实证确认**，非推测）

git 执行 merge driver 时 **cwd = 工作树顶层**；驱动注册是**相对路径**：

```
merge.contract-union.driver = node tools/module-progress/merge-union.mjs %O %A %B
```

⇒ 那条相对路径解析到的是**合并发生瞬间工作树里的那份脚本**，即**当前检出分支（= main）**
的那份，**不是被合进来那份**。

**实证（最小复现，`/tmp/drivertest4`）**：main 上驱动是 V1、待合分支把驱动改成 V2
且两边都改了同一个待合并文件 ⇒ 触发真 3 方冲突 ⇒ 驱动被调用：

```
WHICH driver version ran?   DRIVER_VERSION=V1     ← main 的合并前副本
working-tree probe.mjs      DRIVER_VERSION=V2     ← 被合进来的那份（合并后才写进工作树）
cwd                         /private/tmp/drivertest4  ← 工作树顶层
```

⇒ **驱动跑的是旧版**；被合进来的新版是在驱动跑完之后才写进工作树的。

### 由此得出的**不可换序**的两 PR 拆法

| 顺序 | PR 内容 | 合并时驱动 | 结果 |
| --- | --- | --- | --- |
| **PR#1** | 驱动修复 + 生成器 `sort_keys` + `__pycache__` gitignore —— **不含任何契约产物** | main 旧驱动，但**无契约文件参与冲突** ⇒ 不触发 | ✅ main 拿到修好的驱动 |
| **PR#2** | 11 个重生成产物 | main **新**驱动（PR#1 已落地） | ✅ 冲突触发新驱动 ⇒ canonical 形态 |
| ❌ | 合成一个 PR（驱动 + 产物一起） | main **旧**驱动且契约文件参与冲突 | ❌ 产物被写回 JS 形态 ⇒ main 的 CI 继续红，且现象是「修复合进去了却没生效」—— 最难查的那种 |

### 机器可判的**前置断言**（PR#2 之前必跑）

```bash
# main 上必须已经是修好的驱动（含 json-format.py 路径）
git show origin/main:tools/module-progress/merge-union.mjs | grep -q 'json-format.py' \
  && echo "OK：main 已是修好的驱动 ⇒ 可以放 PR#2" \
  || echo "STOP：main 还没修 ⇒ 只放 PR#1，契约产物一律不放行"
```

⇒ **未通过 ⇒ 绝不放行任何契约产物。**

### 复核复现配方（任何人可重跑）

```bash
D=/tmp/drivertest; mkdir -p $D; cd $D; git init -q -b main .
git config user.email t@t.t; git config user.name t
printf 'merged.txt merge=probe\n' > .gitattributes
git config merge.probe.driver "node probe.mjs %O %A %B"
# probe.mjs 往日志写自己的版本号 + process.cwd()
printf 'line1\nBASE\nline3\n' > merged.txt && git add -A && git commit -qm base
git checkout -qb feat
# feat：改 probe.mjs 为 V2 + 把 merged.txt 改成 THEIRS
git checkout -q main
# main：把 merged.txt 改成 OURS（驱动仍是 V1）⇒ 真 3 方冲突
git merge feat && cat /tmp/drivertest/log.txt
```

⚠️ **坑**：`merged.txt` 只在一边改 ⇒ 那是「单边变更」，git 直接取另一边、**根本不触发驱动**；
必须**两边都改**才会触发。

## §1 修复的设计（读懂它，才能判断「修好了没有」）

`e5cb2f11` = 驱动修复 + 生成器 `sort_keys` + 新增 `tools/module-progress/json-format.py` + 驱动回归测试。

- **单一真相源**：新增 `tools/module-progress/json-format.py` 只做一件事 ——
  `json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n"`，
  **与生成器的 `snapshot.write_text(...)` 完全同一个调用** ⇒ 数字怎么写、键怎么排、有没有末尾换行，
  全部由同一份 `json.dumps` 决定。
- **`.ts` 分支已改成纯 JS**（不依赖 python3）：
  - 横幅回到**首行**：`const head = o.banner.length ? o.banner : t.banner.length ? t.banner : b.banner;`
    然后 `writeFileSync(oursPath, \`${[...head, ...body].join('\n')}\n\`)`；
  - 类型按名排序：`const keys = [...byKey.keys()].sort();`
    （默认 `Array.prototype.sort()` = UTF-16 码元序，**等价 Python 的 `sorted()`**）。
- **`.json` 分支委托给 python3**：`spawnSync('python3', [JSON_FORMATTER], ...)`，
  其中 `JSON_FORMATTER = join(dirname(fileURLToPath(import.meta.url)), 'json-format.py')`
  ⇒ **相对驱动脚本自身**解析（与 §0 同属「合并瞬间的工作树副本」语义）。
- ⭐ **fail-safe**：python3 不可用或脚本失败 ⇒ **保留原文件内容不动**并打印
  「已保留原文件内容不动，本次合并不生效」⇒ **不会写坏**，只是本次合并不生效。
  ⇒ 下轮若看到这句 ⇒ **环境缺 python3**，不是逻辑 bug。

## §2 修复前基线（实测）

### 驱动改写造成的漂移量（canonical 生成物 vs 已提交）

| 文件                        | `.ts` 差异行 | `.json` 差异行 | 漂移成因                                             |
| --------------------------- | ------------ | -------------- | ---------------------------------------------------- |
| `identity.ts` / `.json`     | **0**        | **0**          | **从未被驱动改写**（没撞过契约冲突）⇒ 与 canonical 逐字节相同 |
| `catalog.ts`                | 2            | 10             | 仅横幅位置 + 浮点字面量 + 末尾换行                    |
| `warehouse.ts`              | 2            | 2              | 仅横幅位置 + 末尾换行                                |
| `channel.ts`                | 320          | **52 762**     | 横幅 + **类型序被打乱** + **json 键序被打乱**        |
| `crm.ts`                    | 160          | **10 672**     | 同上                                                 |
| `trade.ts`                  | 248          | **98 538**     | 同上                                                 |

### 四种漂移成因（下轮逐类核对）

1. **横幅从首行挪到末行** —— catalog / warehouse / channel / crm / trade 共 5 个；identity 未受影响。
2. **类型序被打乱** —— channel / crm / trade 共 3 个（生成器 `sorted(schemas.items())` vs 驱动插入序）。
3. **json 键序被打乱** —— channel / crm / trade（paths 710 / 91 / 789，键集合相同、顺序不同）。
4. **JS vs Python 序列化差异** —— 精确指纹：
   `1e-06`↔`0.000001`、`1.0`↔`1`、`0.0`↔`0`、**末尾换行**（生成器补，JS 不补）。
   ⇒ 这四个指纹是「JS `JSON.stringify` 产物」的**判定特征**。

### 已提交 json 的形态判定

已提交的 6 份 json **不是 Java 导出、也不是生成器产物，而是驱动的 JS 输出**（四个指纹逐项吻合）。
⇒ 与「identity 之所以好是因为从未撞过契约冲突 ⇒ 驱动从未改写它」完全自洽。

⇒ **修复落地后这 6 份 json 会一次性变成生成器形态** ⇒ 属**类别②**，**数量不变**。

### 规范字节（canonical shape）

- `.prettierignore` 第 4 行列了 `packages/api-contracts/`
  ⇒ `prettier --file-info` ⇒ `{"ignored": true, "inferredParser": null}`
  ⇒ **prettier 对契约产物恒为空操作** ⇒ **本地与 CI 同形，本地闸配方可信、不用改**。
- `.json` 规范形状 = `json.dumps(spec, ensure_ascii=False, indent=2, sort_keys=True)` + **末尾换行**。
- `.ts` 规范形状 = **横幅首行** + 每 `export type` 一行、**按名排序**、不折行。

### ⚠️ 已知缺口：`__pycache__` 未被 gitignore

`e5cb2f11` **没有** `.gitignore` 改动，而 `tools/codegen/test_catalog_contracts.py`
用 `importlib.util` 加载 `catalog-contracts.py` ⇒ 实测跑一次测试就生成
`tools/codegen/__pycache__/`，而它**不在 `.gitignore` 里** ⇒ `git status` 里出现
`?? tools/codegen/__pycache__/` ⇒ **脏工作区会挡下游 merge-ready**。

⇒ **`__pycache__` gitignore 条目必须随 PR#1 一起进**（实测：`git check-ignore` 无命中）。

## §3 本清单要区分的两类「契约文件变化」

| 类别 | 含义 | 判定 | 标注写法 |
| --- | --- | --- | --- |
| **① 驱动改写产生的漂移** | 合并时驱动重写契约文件导致的字节漂移 | **修复后不应再发生** | 回归 ⇒ 要查 |
| **② 修复引入的一次性排版变更** | `.ts` 类型序 + json 键序变为字典序 + 浮点字面量 + 末尾换行 | **预期**，一次性 | 非回归 ⇒ 标注「预期的一次性排版变更（类别②）」 |

⇒ **下一轮汇报必须写明属于哪一类**，否则会被当成回归再查一遍。

## §4 下轮同步的判据

### 判据 A —— 乱序输入必须产出与打乱前**逐字节相同**的生成物

打乱已提交 json 的 `paths` 键序 → 喂 `--spec-dir` → 生成物必须与打乱前逐字节相同。

### 判据 B —— ⭐ 必须**先重新生成**再比空

```bash
pnpm contracts:generate
git diff --exit-code -- packages/api-contracts/src
```

⚠️ **顺序不能省**：单独跑 `git diff --exit-code` 只证明「合并没改东西」，
**不证明「已提交文件是 canonical 的」**。

⚠️ **只修 `.ts` 不够**：6 个 `.ts` 即使逐字节相同，3 份 json 的键序仍会让这条非空
⇒ catalog-e2e 依旧红。⇒ **`.ts` 与 `.json` 必须一起为空。**

### 判据 B' —— CI 里已经有这道闸（不要重复造）

`.github/workflows/ci.yml:140-141` 现在就是：

```yaml
- run: pnpm contracts:generate
- run: git diff --exit-code -- packages/api-contracts/src
```

⇒ `docs/backlog/2026-10-20-contract-drift-ci-gate.md`（含其**标题**）里
「缺一个 `contracts:check` CI 闸」的**前提已过时**（闸存在，只是内联步骤而非命名脚本）。
⇒ 下轮**不要**再把它当「缺失的闸」上报；真正缺的是**驱动侧不产生漂移**。

### 判据 C —— 逐字节三项不变量（逐个断言）

| # | 不变量 | 检查方式 | 期望 |
| --- | --- | --- | --- |
| C1 | **横幅归首行** | `head -1 packages/api-contracts/src/contracts/*.ts` | 每个文件首行 = `// Generated by tools/codegen/catalog-contracts.py from Spring OpenAPI. DO NOT EDIT.` |
| C2 | **类型按名排序** | 抽出 `export type/interface/const <Name>` 的 Name 列表与 `sort` 后逐字节比 | 完全相等 |
| C3 | **json 键序字典序** | `Object.keys(paths)` 与 `[...keys].sort()` 比；`components.schemas` 同理 | 完全相等 |

### 判据 D —— 求并幂等

同一对输入连续求并两次，两次产物必须逐字节相同（禁止「第一次排序、第二次又换序」）。

### 判据 E —— ⭐ canonical 逐字节一致（离线可跑，最强）

```bash
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi --out-dir /tmp/canon_contracts --no-prettier
diff -r /tmp/canon_contracts packages/api-contracts/src
```

⇒ **期望：`diff -r` 完全无输出**（比判据 B 更强：B 比工作区 vs HEAD，E 比 **canonical vs HEAD**）。

#### ⚠️ 判据 E 的前置条件（不满足会**假绿**）

`backend/target/openapi/` **不入库**（`.gitignore:101` 的 `backend/**/target/`；
实测 `git ls-files backend/target/` = **0**）⇒ 它只是**某次 `mvnw verify` 跑契约测试时的产物**，
且 6 份快照**随 reactor 推进逐个写出**（实测 13:13:24 identity → 13:55:05 crm，跨 42 分钟）。

⇒ **前置：本轮 `mvnw verify` 必须已跑完**，且快照来自**当前 HEAD 的后端代码**。机械判定（两条都要过）：

```bash
ls backend/target/openapi/*.openapi.json | wc -l        # == 6，且每份 mtime 晚于本轮 merge commit 的提交时间
git diff --name-only <生成快照时的 commit> HEAD -- backend/services/        # 必须为空
git diff --name-only <生成快照时的 commit> HEAD -- packages/api-contracts/ # 必须为空
```

⚠️ **拿旧快照跑判据 E 会假绿**：旧快照比当前 spec 少 path，本该「更容易对不上」；
但若旧快照恰好与旧已提交文件同源，就会被误判成「已 canonical」。

#### 判据 E 与 CI 等价吗？—— 等价（构造性论证，下轮不必重推）

`OpenApiContractTest`（每个服务一份，如
`backend/services/identity/src/test/java/com/starcon/identity/OpenApiContractTest.java`）：

```java
@AutoConfigureMockMvc
class OpenApiContractTest {
  @Test void exportContract() {
    var response = http.perform(get("/v3/api-docs"))...;      // 真实 Spring 应用 + MockMvc
    Files.writeString(directory.resolve("identity.openapi.json"), response.getContentAsString());
  }
}
```

⇒ 同一端点、同一套 SpringDoc、同一批 controller；唯一差异是传输层与 `servers[0].url`，
而后者被生成器 `load_specs` 钉成同源值（`'/'` + `'Same-origin service base'`）⇒ **被归一化掉**。
⇒ `sort_keys` 落地后连排序依赖也去掉 ⇒ **判据 E 与 CI 等价，不需要额外起服务。**

### 判据 F —— ⭐ 本轮 merge 改了哪些契约文件（= 驱动本轮改写面）

```bash
git diff --name-only <PRE> <本轮 merge commit> -- packages/api-contracts/
```

**实测实例**（第 27 轮 merge `2f784133`，PRE = `c9ce37af`）：

```
packages/api-contracts/src/catalog.openapi.json   | 10 +++++-----
packages/api-contracts/src/contracts/catalog.ts    |  2 +-
packages/api-contracts/src/contracts/warehouse.ts  |  2 +-
packages/api-contracts/src/warehouse.openapi.json  |  2 +-
4 files changed, 8 insertions(+), 8 deletions(-)
```

⇒ **驱动只改「本轮 merge 触及到的」契约文件**；channel / crm / trade **不在本轮触及集里**、
所以没被本轮改写 —— 它们的大漂移**来自更早的车道合并**，不是本轮引入。
⇒ **下轮判读漂移来源时，先跑判据 F 定位改写面，再区分本轮引入 vs 历史遗留。**

## §5 下一轮同步的汇报义务

1. 明确写出契约文件的变化属于**类别①（回归）还是类别②（预期的一次性排版变更）**。
2. 若属类别②，列出具体变了哪几个文件、**导出声明数与 `paths` 数是否不变**。
   ⇒ **数量不变** 是「排版变更」与「内容丢失」的**分界线**：数量若变了 ⇒ 是内容丢失 ⇒ **回归**，要查。
   （本次实测的基线数字：导出声明 158/863/1348/1077/1717/159；`paths` 数 112/494/518/710/91/789。）
3. 判据 A / B / B' / C1-C3 / D / E / F 各组的实际结果**逐条列出**。
4. ⭐ **显式声明旧 backlog 的过时前提**：
   `docs/backlog/2026-10-20-contract-drift-ci-gate.md` 的标题与 §① 里
   「缺一个 `contracts:check` CI 闸」的前提**已由本清单（判据 B'）取代**。
   ⇒ 该声明写进**下轮同步的会话日志**，不要写进旧文件正文
   （main 上有该文件的变体，改正文会**再撞一次 add/add**；
   若将来要改正文，**只能在 module-dev→main 方向落**，那边 main 已有该文件 ⇒ 不会 add/add）。
5. 报出**判据 F 的清单**（本轮 merge 改了哪些契约文件），用它区分「本轮引入」与「历史遗留」。
6. ⭐ 报出 **§0 的前置断言结果**（main 是否已是修好的驱动）—— 这是 module-dev→main 能否放行的前提。

## §6 相关链接

- `docs/backlog/2026-10-20-contract-drift-ci-gate.md`
  （「merge driver 把 codegen 横幅挪走了」的原始取证，经第 27 轮复核**仍成立**：
  `bdceadbd` 首行是横幅 ✅，`52f5ebeb` 首行是 `export type ...`、横幅在末行 ❌；
  ⚠️ 其中「缺 `contracts:check` 闸」的前提**已过时**，见判据 B' 与汇报义务第 4 条）
- 修复车道：`fix/contract-union-driver`（worktree `.worktrees/tooling.contract-union`，
  由 14:00 流水线 tick `mvs_629b82e64bd04f6e87cd5f5975eeb503` 派出）；
  修复 commit `e5cb2f11`（**尚未**到 `origin/main`）
