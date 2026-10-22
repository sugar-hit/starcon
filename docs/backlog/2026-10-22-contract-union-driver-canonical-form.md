# 契约合并驱动器必须产出「与 codegen 逐字节一致」的产物（2026-10-22 batch-63 落地）

**优先级：P1 —— 已修复，但有 4 条约束必须长期守住，否则同一个坑会再踩一次。**

**状态**：修复已落地于 `integration/module-dev`（batch-63，2026-10-22）。
关联清单（兄弟会话写的「下一轮同步逐项校验」清单）：
`docs/backlog/2026-10-22-contract-union-byte-stability-next-sync-checklist.md`
原始取证：`docs/backlog/2026-10-20-contract-drift-ci-gate.md`

---

## 一、这个缺陷是什么（一句话）

`tools/module-progress/merge-union.mjs` 这个 git merge driver 在并入契约文件时
**改写**了它们，写出的字节**不等于** codegen 的输出 ⇒ CI 的契约闸
（`pnpm contracts:generate` 后 `git diff --exit-code -- packages/api-contracts/src`）
恒红。

三个独立的红因（**只修一个都不够**）：

| # | 红因 | 现象 |
| --- | --- | --- |
| ① | 横幅被挪到**末行** | `merge-union.mjs:176` 把含横幅的 `o.head` 拼在导出之后 |
| ② | 类型序不是字典序 | `mergeEntries` 输出 = ours 插入序 + theirs 独有键追加；生成器是 `sorted(schemas.items())` |
| ③ | `.openapi.json` 形状不同 | driver 写 `JSON.stringify(a, null, 2)`（`0.000001`、无末尾换行）；生成器写 `json.dumps(..., indent=2)` + `'\n'`（`1e-06`、有末尾换行），且生成器原本**不排序**、键序继承输入 |

①②③ 合起来让 CI 长期红（`catalog-e2e` job）。

---

## 二、修复后的 4 条硬约束（**这 4 条是本条 backlog 的真正内容**）

### 约束 1：driver 与生成器必须**共用同一个序列化器**

生成器与 driver 是两个写入者，若各自实现序列化，形状迟早对不上。
本仓现在的做法是：**driver 不手搓 JSON 序列化**，而是把合并结果交给
`tools/module-progress/json-format.py`（内部就是生成器同款
`json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + '\n'`）。

⚠️ 为什么不能手搓：Python 的 `repr` 与 JS 的 `Number.toString` 在指数边界不同
（`1e-6` → Python `1e-06` / JS `0.000001`；`1e-8` → Python `1e-08` / JS `1e-8`），
**prettier 也不会帮忙**（实测 `1e-6` / `0.000001` / `1e-06` / `1e-8` 四个形态
喂 prettier **各自原样保留**）⇒ `JSON.stringify` 无解。

### 约束 2：生成器必须 `sort_keys=True`（键序不能继承输入）

否则输出随「输入 spec 的键序」变化：
- CI 的输入是 live `/v3/api-docs`（键序取决于服务返回顺序）；
- 本地闸的输入是 MockMvc 导出的快照；
两者若有一处键序不同 ⇒ **本地闸绿也可能是假绿**，CI 照样红。
`sort_keys=True` 让输出与输入键序**彻底解耦** ⇒ 本地绿 ⇒ CI 必绿。

### 约束 3：module-dev→main 的 PR **必须拆成两个，顺序不可换**

**机理**（这是最容易踩的一条）：驱动注册是**相对路径**
（`.git/config`：`merge.contract-union.driver = node tools/module-progress/merge-union.mjs %O %A %B`），
git 执行 merge driver 时 **cwd = 工作树顶层** ⇒ 跑的是**目标分支工作区里那份**驱动，
**不是被合进来那份**。

⇒ 合 main 时，**main 上没修之前，跑的仍然是 main 的旧驱动**。
而 11 个契约产物一旦参与冲突，旧驱动会把它们**重新写坏**（横幅回末行、键序再漂、
`0.000001`、无末尾换行）⇒ **现象是「修复合进去了却没生效」** —— 最难查的那种。

| 顺序 | PR 内容 | 合并时跑的驱动 | 结果 |
| --- | --- | --- | --- |
| **PR#1** | 驱动修复 + 生成器 `sort_keys` —— **不含任何契约产物** | main 的旧驱动，但**没有契约文件参与冲突** ⇒ 驱动不触发 | ✅ main 拿到修好的驱动 |
| **PR#2** | 11 个重生成产物 | main 的**新**驱动 | ✅ 触发新驱动 ⇒ 产出 canonical 形态 |
| ❌ 合成一个 PR | 驱动 + 产物一起 | main 的**旧**驱动，且契约文件参与冲突 | ❌ 产物被重新写坏 ⇒ CI 继续红 |

⚠️ 同样的道理在 **module-dev 侧**也成立 —— 所以本流水线本轮就是
**先并「驱动修复分支」、再并「重生成产物分支」**（两次 merge 分开）。

### 约束 4：**main 侧必须实跑一次契约闸**，本地无法代验

本轮 L2 只覆盖 module-dev 侧。main 侧要真绿，必须在 PR 合并后于 main 上实跑：

```bash
pnpm contracts:generate && git diff --exit-code -- packages/api-contracts/src
```

⚠️ 这条**本地验不了**（CI 用 live spec，本地闸用 MockMvc 快照）⇒
只能由 PR 后的 CI / 实跑确认。**不要在汇报里把 main 侧写成「已绿」**，
除非真的跑过。

---

## 三、顺手修掉的两个仓库卫生缺口（同一批落地）

| 缺口 | 后果 | 处理 |
| --- | --- | --- |
| `.gitignore` **没有** `__pycache__/` | 跑一次 `pnpm test:codegen` 就留下未跟踪目录 ⇒ `lane.mjs land` 的「工作区必须干净」闸会拒绝 ⇒ 任何 agent 都可能误提交字节码 | 补 `.gitignore` 条目（独立 `chore` commit） |
| `*.itest.mjs` **既不在 CI 里跑、也不在流水线 L2 里跑** | 回归测试写了也没人执行 ⇒ 形同虚设 | 本轮 L2 **显式**跑了 `node tools/module-progress/merge-union.itest.mjs`；**长期需要有人把它接进 CI 或 L2**（**未做**，见下） |

⚠️ 前者是本轮**已做**的；后者是**已识别但未做**的待办 ——
`*` 不在 CI 里跑意味着这批回归测试的保护网只在本机。

---

## 四、后续待办（未完成，别当已完成）

1. **把 `tools/module-progress/*.itest.mjs` 接进 CI 或流水线 L2**（当前无人自动执行）。
2. **main 侧实跑契约闸**（见约束 4），并把结果写进下一轮同步汇报。
3. **module-dev→main 拆两个 PR**（见约束 3），PR#1 不得含契约产物。
4. 三条协议建议待用户定夺（**尚未改动协议文件**）：
   「同一 worktree 禁止并发构建」「释放闸前必须做完所有写操作」「`lane.mjs merge-ready` 加闸校验」。

---

## 五、判据（留给下一次核对用）

- **判据 A**：把某份 json 的 `paths` / `components.schemas` 键序**打乱**后喂 `--spec-dir`，
  生成物必须与打乱前**逐字节相同**（证明已与输入键序解耦）。
- **判据 B**：`pnpm contracts:generate` 后 `git diff --exit-code -- packages/api-contracts/src` 为空
  —— **6 个 `.ts` + 6 个 `.json` 一起为空**，只验 `.ts` 不够。
- **判据 C**（三项不变量）：C1 横幅归首行；C2 类型按名升序；C3 json 键序字典序（`paths` 与 `components.schemas` 都查）。
- **判据 D**：幂等 —— 同一对输入连续求并两次，两次产物**逐字节相同**。
- **判据 E（往返一致）**：`json-format.py` 处理任一已提交 json ⇒ 输出必须与
  生成器对同一份 spec 的输出**逐字节相同**。

⚠️ 跑这些判据时：`--out-dir` **必须在仓库内**（如 `.tmp/xxx`）——
prettier 按**文件位置**解析配置，out-dir 在仓库外会退回默认配置（printWidth 80）而改排版。
⚠️ 「已提交的 json 为什么是 `0.000001` + 无末尾换行」：**它们是 driver 的产物**
（`JSON.stringify` 的指纹），不是生成器产物、也不是 Java 导出 ——
这与「identity 之所以好是因为从未被 driver 改写」完全自洽。
