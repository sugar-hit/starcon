# `prettier --check` 对被 gitignore 的路径**一个文件都不查**，却报「All matched files use Prettier code style!」

发现：2026-10-23 05:36（第 44 轮「集成分支并入 main」期间）。发现人：并入主分支的执行者。
影响范围：任何用 Prettier CLI 判「哪一侧是规范态」的取证动作 —— 尤其冲突裁决。

## 现象

第 44 轮合并命中 **38 个冲突文件**，两侧内容只差排版。
裁决必须回答一个问题：**哪一侧是 prettier 规范态**（取规范态那一侧 ⇒ 零新增格式债）。

先用了 CLI：

```bash
git show upstream/main:$F > .tmp/sides/main-account-health-model.ts
git show upstream/integration/module-dev:$F > .tmp/sides/head-account-health-model.ts

$ pnpm exec prettier --check .tmp/sides/main-account-health-model.ts
Checking formatting...
All matched files use Prettier code style!          # ← 「合规」

$ pnpm exec prettier --check .tmp/sides/head-account-health-model.ts
Checking formatting...
All matched files use Prettier code style!          # ← 「也合规」？
```

两侧都「合规」，**据此得出的结论是错的**：实际是主分支侧合规、集成分支侧不合规。
正确方向一旦搞反，38 个文件会**全部取错边**，把集成分支的原始排版带回主分支
⇒ `format:check` 的失败文件数**比基线多 38 个** ⇒ 直接被合并闸拦下。

## 根因：Prettier 3 的默认 `--ignore-path` 含 `.gitignore`

```
$ pnpm exec prettier --help | grep -A3 'ignore-path'
  --ignore-path <path>     Path to a file with patterns describing files to ignore.
                           Multiple values are accepted.
                           Defaults to [.gitignore, .prettierignore].
```

`.tmp/` 在仓库 `.gitignore` 第 18 行 ⇒ 落在 `.tmp/` 下的文件**被默认忽略**。

被忽略的文件对 `--check` 而言等于**不存在**：Prettier 遍历完 0 个文件，
于是打印「All matched files use Prettier code style!」（字面为真：匹配到的文件都合规，
因为匹配到的文件数是 0）并以 **退出码 0** 结束。

**「通过」与「没查」在输出上完全无法区分** —— 这就是假绿。

隔离实验（同一个文件，只改 ignore 规则）：

```bash
$ printf 'const x   =    1\nconst  y=2\n' > .tmp/sides/definitely-unformatted.ts

$ pnpm exec prettier --check .tmp/sides/definitely-unformatted.ts
Checking formatting...
All matched files use Prettier code style!                 # ← 假绿（0 个文件被查）

$ pnpm exec prettier --check --ignore-path .prettierignore .tmp/sides/definitely-unformatted.ts
Checking formatting...
[warn] .tmp/sides/definitely-unformatted.ts
[warn] Code style issues found in the above file. Run Prettier with --write to fix.
```

同一个明显未格式化的文件，默认规则下报「合规」，只读 `.prettierignore` 时如实报「不合规」。
⇒ **差异来自 ignore 规则，不是来自文件内容。**

还有第二个坑：**对被忽略的文件，CLI 的格式化输出等于原文**。
`prettier <被忽略的文件>` 会把原文原样打到 stdout，于是
「归一化结果 vs 原文」恒等 ⇒ 看起来像「本来就是规范态」。

```bash
$ pnpm exec prettier .tmp/sides/head-raw.ts > .tmp/sides/head.norm.ts
$ cmp -s .tmp/sides/head.norm.ts .tmp/sides/head-raw.ts && echo "没变"
没变          # ← 不是「本来就规范」，是「压根没格式化」
```

## 影响面（以及不影响什么）

**受影响**：任何把 CLI 输出当作「该文件是否合规」的判据的取证动作。
冲突裁决、格式债统计、CI 失败文件数比对，都可能被它带偏。

**不受影响**：

- 仓库自身的 `pnpm format:check` —— 它 glob 的是 `**/*.{ts,tsx,...}`，
  命中的是**已跟踪文件**，不会被 `.gitignore` 挡掉 ⇒ CI 的判定是真的。
- **Node API**（`prettier.format(code, { filepath })`）—— API 不做 ignore 过滤 ⇒ 结论可靠。

本轮最终结论来自 Node API：主分支侧 **38/38 是规范态**，集成分支侧 **38/38 不是**，
两侧经归一化后**逐字节相同** ⇒ 取主分支侧。

## 修复

判「某一侧是不是规范态」时，**不要用 CLI 的 `--check`**，改用 Node API：

```js
const prettier = (await import('prettier')).default;
const cfg = await prettier.resolveConfig(realPath, { useCache: false });
const normalized = await prettier.format(code, { ...cfg, filepath: realPath });
const isCanonical = normalized === code;
```

要点：

1. **`filepath` 必须是仓库内的真实路径**（决定 parser 与配置），**不能**是 `.tmp/` 下的临时路径。
2. `resolveConfig` 的 `printWidth` / `singleQuote` 等要与 CI 一致，
   否则「规范态」的标准会与 `format:check` 不同源。
3. 若必须用 CLI，**显式指定 `--ignore-path .prettierignore`**，
   并确认输出里出现了**文件名**（被忽略的文件不会出现在输出里）。

⚠️ 另一种更隐蔽的用法：`prettier --stdin-filepath <仓库内真实路径> < file`。
它绕过 ignore 规则（本轮实测可用），输出的是**真实归一化结果** —— 可作 CLI 侧的交叉验证。

## 待办

| 编号 | 待办                                                                                      | 责任   | 备注                                               |
| ---- | ----------------------------------------------------------------------------------------- | ------ | -------------------------------------------------- |
| L-05 | 冲突取证脚本统一改用 **Node API** 判规范态，禁止用 CLI `--check` 判临时目录里的文件       | 并入者 | 本轮差点据此取错 38 个文件的边                     |
| L-06 | 取证脚本的临时产物**不要落在被 gitignore 的目录**下，或落盘后显式用 `--ignore-path` 复核  | 并入者 | 落在 `.tmp/` 下会让 CLI 静默跳过                   |
| L-07 | 工具输出里「被忽略的文件」与「已检查且合规的文件」要能区分（打印被检查的文件数 / 文件名） | 并入者 | 假绿的本质是「0 个文件被查」与「全部合规」输出相同 |

## 相关

- `docs/sessions/2026-10/2026-10-23-0536-main-sync-round44-zero-net-change.md` —— 本条的发现现场。
- `docs/backlog/2026-10-23-merge-resolve-revparse-stdout-echo-mislabels-add-add.md` —— 同一条合并链路的另一个「工具输出骗人」缺陷。
