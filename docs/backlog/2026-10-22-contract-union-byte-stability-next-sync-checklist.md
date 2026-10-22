# 下轮同步的契约逐字节校验清单（merge-union 修复落地后必做）

**优先级：P1 —— 不是 bug，是「修复落地那一轮同步」的验收清单。**

**状态：等 `fix/contract-union-driver` 车道落地。落地后由 batch-62 流水线会话知会同步任务，
下一轮 `main → integration/module-dev` 同步必须按本清单逐项校验。**

> 本清单的数据全部是 **2026-10-22 14:3x 实测**（`origin/integration/module-dev` = `850852d9` 的树），
> 测量方法：用 L2 跑出来的活 spec（`backend/target/openapi/*.openapi.json`）
> 喂生成器到临时目录，再与已提交文件逐字节比。

---

## 一、缺陷根因（源码级）

`merge-union` 驱动会**改写契约文件**，两个缺陷：

| 缺陷 | 位置（`tools/module-progress/merge-union.mjs`） | 源码 |
| --- | --- | --- |
| ① 横幅挪到**末行** | 第 176 行 | `writeFileSync(oursPath, \`${merged.map(([, v]) => v).join('\n')}\n${o.head.join('\n')}\`)` —— `o.head`（含横幅）追加在**最后** |
| ② **不做任何排序** | `.ts` 分支第 176 行 / `.json` 分支第 215 行 | `.ts` 用 **Map 插入序**写出；`.json` 是 `JSON.stringify(a, null, 2)` 且 `Object.fromEntries(merged.map(...))` 保留插入序 |

全文件 `grep -nE 'sort|sort_keys|localeCompare'` ⇒ **零命中**。

⚠️ 第 176 行在 `origin/main` 上**逐字节相同** ⇒ 缺陷先于双方，**不会被记成同步任务的回归**。
⚠️ 驱动注册在**仓库本地 `.git/config`**（`merge.contract-union.driver = node tools/module-progress/merge-union.mjs %O %A %B`），
worktree 共享 ⇒ **module-dev→main 方向也走同一个驱动**
⇒ **排序约束：先合入驱动修复，再做 module-dev→main 的 PR。**

**生成器侧只差一行**：`.ts` 的规范形状（横幅首行 + 按名排序）**本来就是既有行为**
（`render_types()` = `text=GENERATED` + `for name,schema in sorted(schemas.items())`）；
只有 `.json` 的 `sort_keys=True` 还没加（`catalog-contracts.py:201` 现为
`json.dumps(spec,ensure_ascii=False,indent=2)+'\n'`）⇒ **真正的破坏者只有 driver 一侧。**

## 二、修复前基线（实测）

### 驱动改写造成的漂移量（canonical 生成物 vs 已提交）

| 文件                        | `.ts` 差异行 | `.json` 差异行 | 漂移成因                                             |
| --------------------------- | ------------ | -------------- | ---------------------------------------------------- |
| `identity.ts` / `.json`     | **0**        | **0**          | **从未被驱动改写**（没撞过契约冲突）⇒ 与 canonical 逐字节相同 |
| `catalog.ts`                | 2            | 10             | 仅横幅位置 + 浮点字面量 + 末尾换行                    |
| `warehouse.ts`              | 2            | 2              | 仅横幅位置 + 末尾换行                                |
| `channel.ts`                | 320          | **52 762**     | 横幅 + **类型序被打乱** + **json 键序被打乱**        |
| `crm.ts`                    | 160          | **10 672**     | 同上                                                 |
| `trade.ts`                  | 248          | **98 538**     | 同上                                                 |

⇒ **驱动漂移的四种成因**（下轮逐类核对）：

1. **横幅从首行挪到末行** —— catalog / warehouse / channel / crm / trade 共 5 个文件；identity 未受影响。
2. **类型序被打乱** —— channel / crm / trade 共 3 个文件（生成器 `render_types` 用 `sorted(schemas.items())`，
   驱动用 Map 插入序）。catalog / warehouse 只差横幅，类型序本来就已排序。
3. **json 键序被打乱** —— channel / crm / trade（路径数 710 / 91 / 789，键集合相同、顺序不同）。
4. **JS vs Python 的序列化差异** —— 精确指纹：
   `1e-06`↔`0.000001`、`1.0`↔`1`、`0.0`↔`0`、**末尾换行**（生成器 `+'\n'`，驱动没有）。
   ⇒ 这四个指纹是「JS `JSON.stringify` 产物」的**判定特征**。

### 已提交 json 的形态判定

已提交的 6 份 json **不是 Java 导出、也不是生成器产物，而是驱动的 JS 输出**
（上述四个指纹逐项吻合）。
⇒ 与「identity 之所以好是因为从未撞过契约冲突 ⇒ 驱动从未改写它」**完全自洽**。

⇒ **修复落地后这 6 份 json 会一次性变成生成器形态**（`1e-06` + 末尾换行 + 字典序）—— 属**类别②**，
**数量不变**。

### 规范字节（canonical shape）

- `.prettierignore` 第 4 行列了 `packages/api-contracts/`
  ⇒ `prettier --file-info` ⇒ `{"ignored": true, "inferredParser": null}`
  ⇒ **prettier 对契约产物恒为空操作** ⇒ **本地与 CI 同形，本地闸配方可信、不用改**。
- `.json` 规范形状 = `json.dumps(spec, ensure_ascii=False, indent=2, sort_keys=True)` + **末尾换行**
  （数组展开、浮点 `1e-06`）—— ⚠️ `sort_keys=True` **由修复车道补上**（当前生成器还没有）。
- `.ts` 规范形状 = **横幅首行** + 每 `export type` 一行、**按名排序**、不折行（生成器既有行为）。

### ⭐ 判据 F —— 本轮 merge 到底改了哪些契约文件（直接指出驱动改写面）

```bash
git diff --name-only <PRE> <本轮 merge commit> -- packages/api-contracts/
```

⇒ 这个清单就是**驱动本轮改写过的文件**。

**实测实例**（第 27 轮 merge `2f784133`，PRE = `c9ce37af`）：

```
packages/api-contracts/src/catalog.openapi.json   | 10 +++++-----
packages/api-contracts/src/contracts/catalog.ts    |  2 +-
packages/api-contracts/src/contracts/warehouse.ts  |  2 +-
packages/api-contracts/src/warehouse.openapi.json  |  2 +-
4 files changed, 8 insertions(+), 8 deletions(-)
```

⇒ **驱动只改了「本轮 merge 触及到的」契约文件**：
catalog / warehouse 被改（横幅 + 浮点 + 换行，各 2 行），
而 channel / crm / trade **不在本轮触及集里**、所以没被本轮改写 ——
它们的大漂移**来自更早的车道合并**，不是本轮引入。
⇒ **下轮判读漂移来源时，先跑判据 F 定位「本轮改写面」，再区分本轮引入 vs 历史遗留。**

## 三、本清单要区分的两类「契约文件变化」

| 类别 | 含义 | 判定 | 标注写法 |
| --- | --- | --- | --- |
| **① 驱动改写产生的漂移** | 合并时驱动重写契约文件导致的字节漂移 | **修复后不应再发生** | 回归 ⇒ 要查 |
| **② 修复引入的一次性排版变更** | `.ts` 类型序 + json 键序变为字典序 + 浮点字面量 + 末尾换行 | **预期**，一次性 | 非回归 ⇒ 标注「预期的一次性排版变更（类别②）」 |

⇒ **下一轮汇报必须写明属于哪一类**，否则会被当成回归再查一遍。

## 四、下轮同步的判据

### 判据 A —— 乱序输入必须产出与打乱前**逐字节相同**的生成物

打乱已提交 json 的 `paths` 键序 → 喂 `--spec-dir` → 生成物必须与打乱前逐字节相同。
⇒ 证明「键序不是生成器决定的」这个前提已被修掉。

### 判据 B —— ⭐ 必须**先重新生成**再比空

```bash
pnpm contracts:generate
git diff --exit-code -- packages/api-contracts/src
```

⚠️ **顺序不能省**：单独跑 `git diff --exit-code` 只证明「合并没改东西」，
**不证明「已提交文件是 canonical 的」** —— 必须**先生成再比**，才是 canonical 闸。

⚠️ **只修 `.ts` 不够**：即使 6 个 `.ts` 修到逐字节相同，
3 份 json 的键序仍会让这条非空 ⇒ catalog-e2e 依旧红。⇒ **`.ts` 与 `.json` 必须一起为空。**

### 判据 B' —— CI 里已经有这道闸（不要重复造）

`.github/workflows/ci.yml:140-141` 现在就是：

```yaml
- run: pnpm contracts:generate
- run: git diff --exit-code -- packages/api-contracts/src
```

⇒ `docs/backlog/2026-10-20-contract-drift-ci-gate.md` §① 说的「缺一个 `contracts:check` CI 闸」
**前提已过时**（闸存在，只是以 CI 内联步骤的形式，不是命名脚本）。
⇒ 下轮**不要**再把它当「缺失的闸」上报；真正缺的是**驱动侧不产生漂移**（修复车道在做的事）。
⇒ 旧文件的**标题**「契约漂移无人看守 —— 缺一个 `contracts:check` CI 闸」同样过时。

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
python3 tools/codegen/catalog-contracts.py \
  --spec-dir backend/target/openapi --out-dir /tmp/canon_contracts --no-prettier
diff -r /tmp/canon_contracts packages/api-contracts/src
```

⇒ **期望：`diff -r` 完全无输出**（比判据 B 更强：B 比工作区 vs HEAD，E 比的是 **canonical vs HEAD**）。

#### ⚠️ 判据 E 的前置条件（必须先满足，否则会**假绿**）

`backend/target/openapi/` **不入库**（`.gitignore:101` 的 `backend/**/target/`；
实测 `git ls-files backend/target/` = **0** 个文件）⇒ 它只是**某次 `mvnw verify`
跑契约测试时的产物**，而且 6 份快照是**随 reactor 推进逐个写出的**（实测时间戳
13:13:24 identity → 13:55:05 crm，跨 42 分钟）。

⇒ **前置：本轮 `mvnw verify` 必须已跑完**，且快照来自**当前 HEAD 的后端代码**。
机械判定（二选一，都要过）：

```bash
# (1) 快照齐全且都在本轮 verify 窗口内
ls backend/target/openapi/*.openapi.json | wc -l        # == 6
# 每个快照 mtime 晚于本轮 merge commit 的提交时间
# (2) 快照生成以来，后端源码与契约产物都没再变过
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

⇒ 它打的是**同一个端点 `/v3/api-docs`、同一套 SpringDoc、同一批 controller**，
与 CI 活服务的 `/v3/api-docs` **同源**；唯一差异是传输层（MockMvc vs 真 HTTP）
与 `servers[0].url`，而后者被生成器 `load_specs` 钉成同源值
（`'/'` + `'Same-origin service base'`）⇒ **被归一化掉**。
⇒ 路径集合与排序同源；`sort_keys` 落地后**连排序依赖也去掉**
⇒ **判据 E 与 CI 等价，不需要额外起服务。**

## 五、下一轮同步的汇报义务

1. 明确写出契约文件的变化属于**类别①（回归）还是类别②（预期的一次性排版变更）**。
2. 若属类别②，列出具体变了哪几个文件、**导出声明数与 `paths` 数是否不变**。
   ⇒ **数量不变** 是「排版变更」与「内容丢失」的**分界线**：数量若变了 ⇒ 是内容丢失 ⇒ **回归**，要查。
   （本次实测的基线数字：导出声明 158/863/1348/1077/1717/159；
   `paths` 数 112/494/518/710/91/789。）
3. 判据 A / B / B' / C1-C3 / D / E / F 各组的实际结果**逐条列出**。
4. ⭐ **显式声明旧 backlog 的过时前提**：
   `docs/backlog/2026-10-20-contract-drift-ci-gate.md` 的标题与 §① 里
   「缺一个 `contracts:check` CI 闸」的前提**已由本清单（判据 B'）取代**。
   ⇒ 该声明写进**下轮同步的会话日志**，不要写进旧文件正文
   （main 上有该文件的变体，改正文会**再撞一次 add/add**；
   若将来要改正文，**只能在 module-dev→main 方向落**，那边 main 已有该文件 ⇒ 不会 add/add）。
5. 报出**判据 F 的清单**（本轮 merge 改了哪些契约文件），用它区分「本轮引入」与「历史遗留」。

## 六、相关链接

- `docs/backlog/2026-10-20-contract-drift-ci-gate.md`
  （含「merge driver 把 codegen 横幅挪走了」的原始取证，经第 27 轮复核**仍成立**：
  `bdceadbd` 首行是横幅 ✅，`52f5ebeb` 首行是 `export type ...`、横幅在末行 ❌；
  ⚠️ 其中「缺 `contracts:check` 闸」的前提**已过时**，见判据 B' 与汇报义务第 4 条）
- 修复车道：`fix/contract-union-driver`（worktree `.worktrees/tooling.contract-union`，
  由 14:00 流水线 tick `mvs_629b82e64bd04f6e87cd5f5975eeb503` 派出）
