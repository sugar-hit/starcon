# 18:20 tick（续）—— 回收 17.5.3.2，并修掉台账损坏的根因

接 18:00 tick。两条车道都落地了，本轮把它们并进集成头，并修掉一个
**40 分钟内损坏三次**的编排脚本 bug。交付数 75 → 78。

## 一、17.5.3.2 并入：唯一的冲突是 api-proxy 白名单

`merge-ready` 冲突 `apps/web/src/lib/api-proxy.ts`。判为**真冲突**（不是级联假象）：
两条车道改的是同一个 identity 段放行正则 —— 17.5.1.1 加了 `business-parameters`，
17.5.3.2 加了 `operation-log`，而**车道侧那份把 `business-parameters` 丢了**。

按并集解：保留 HEAD 的 `business-parameters`，把车道侧的
`operation-log(?:\/(?:export|trace\/[0-9a-zA-Z-]+|[0-9a-f-]+))?` 插到 `audit` 之后，
并校验 `business-parameters / operation-log / onboardings / offboarding` 四个锚点同时在位。

**解冲突时我自己踩了一个坑**：脚本替换了 `theirs` 却没删 `ours`，
结果同一段里留下**两条正则**。typecheck 会立刻报出来，但肉眼扫 diff 很难发现 ——
合并完必须 `grep -n '^      /\^'` 数一遍条数。

## 二、交叉验证：V49 与 V47/V48/V50 能不能共存

17.5.3.2 建在 `185298c` 上，**不含 V47/V48/V50**，它自己的 1205 项绿灯
根本没覆盖那三道新约束。所以并入后按纪律回头重跑既有模块：

```
./mvnw verify -pl services/identity -am   → Tests run: 147, Failures: 0  BUILD SUCCESS
pnpm test                                  → 56 文件 / 953 项全绿
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build → ✓
```

147 项里有 15 项 `BusinessParameterIntegrationTest`（V48）与入职路径（V45/V47/V50），
全绿 —— 跨车道约束确实没互相踩。

## 三、根因：merge-union 的行级兜底按**整行文本**去重

台账里同一模块的两行只在状态与证据上不同：

```
| 17.3.2.1 | 角色分配 | ... | 已交付 | identity V47 ...（264 字）|
| 17.3.2.1 | 角色分配 | ... | 未开发 | —                          |
```

整行文本不同 → 两行都留下 → `sync.mjs` 的 `map.set` 后写覆盖，
而 `theirs` 拼在 `ours` 后面 → 占位行赢 → **已交付模块被读成未开发**
→ 下一轮 `plan` 重派 → 撞已占用的 Flyway 版本号（V47/V48）。

**40 分钟内因此损坏三次**（并入 17.5.1.1 一次、17.5.3.2 两次），
其中 17.3.2.1 与 17.5.1.1 差点被判未开发重派。

### 为什么一直没被发现

现有集成测试 `merge-union.itest.mjs` 构造的正是这个场景
（lane-a 把 3.5.2.2 改成已交付、lane-b 留着未开发），但断言只写了
「台账保住 3.5.2.2 已交付」这种**存在性**检查 —— 从不验「坏行没了没」、
也从不验行数。两条都留着，断言照样绿。

### 改法

兜底段落改成：台账行按**模块 ID** 求并集，胜负规则与人工修复、与 `sync.mjs`
读取口径一致 —— 证据非 `—` 优先 > 已交付>进行中>未开发 > 证据更长；
非台账行仍按整行去重，行为不变。

补三条回归断言：**无重复 ID** / **无未开发占位行残留** / **行数等于模块数**。
`node tools/module-progress/merge-union.itest.mjs` 14 项全绿（含新增 3 项）。

## 四、附带修掉的两个小问题

1. **17.5.3.2 漏了导航入口**。它接了路由 `/org/operation-log/page.tsx`
   与 api-proxy 放行，但 `destinations` 数组里没有 `operation-log` 项、
   `visible()` 里也没有对应权限分支 —— 页面只能手敲 URL 到达。
   少了 `visible()` 分支会落到 `canManage` 兜底，而 `AdministrationKind`
   里根本没有 `'operation-log'`，有权限的人反而看不到入口
   （与 17.2.2.2 的 `hierarchy` 漏判是同一类）。已补，typecheck + 953 项全绿。
2. 仓库根目录残留的 `.tmp-ledger-backup.md`（持闸实例留下的安全备份，
   未跟踪）会让 `merge-ready` 的「工作区必须无未跟踪文件」检查直接失败。
   内容已进 `e582b88`，先复制到 `.tmp/` 校验一致，再用可恢复删除清掉原件。

## 五、本轮提交

| commit | 内容 |
|---|---|
| `60cba04` | merge(17.5.3.2)：操作日志并入集成头（白名单取并集） |
| `231aa03` | 台账去重修复（第三次事故） |
| `9fbdab2` | **fix(pipeline)**：台账并集按模块 ID 求并集 + 补 3 条回归断言 |
| `42f5fb3` | sync 刷新派生计数（含一处长期没更新的第二汇总块） |
| `013d074` | 补上操作日志缺失的导航入口 |

## 六、留给下一轮

1. **`merge-ready` 之后必须数行数**：`grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' module-progress.md`
   应恒为 465，不等就是并集又出问题了。脚本已修，但这一条断言值得每次都跑。
2. `7.1.2.1` 的 base 是 `409ff79`，**不含 V50（`bd599cf`）**。
   它落地并入后，入职写入路径是否仍成立要重跑一次 —— 与本轮同一种交叉验证。
3. 解完冲突提交前 `grep -n '^      /\^'` 数一遍 api-proxy 正则条数。