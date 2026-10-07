# 并行车道调度 16:40 tick —— stranded 车道解冲突 + 白名单插入点陷阱

## 进度

465 个 L4 模块：**67 已交付 / 0 进行中 / 398 未开发**（完成率 14.4%）。
本轮起手 64，回收 3 条车道（3.9.1.1、3.8.2.1、17.1.1.1）。

## 回收

| 车道 | 模块 | 结果 |
| --- | --- | --- |
| 3.9.1.1 | 重复 SKU 识别 | `merge-ready` 干净并入 `f3025ca` |
| 3.8.2.1 | 导出 | stranded 两轮，本轮手工解冲突后并入 `6bf8912` |
| 17.1.1.1 | 租户列表 | 并入 `4ed67d0` |

## 关键 trade-off 1：车道侧不一定是「新增的那一侧」

3.8.2.1 卡在 `conflict`，看 note 像是「两边各加一段、语义求并」。实际逐提交对比后发现：

- 车道侧 `api-proxy.ts` 相对**它自己的基线 `12d71b3` 是回退的** —— 丢了
  `report`（3.8.1.2 校验报告子树）与 `supplier-quotes`（3.6.2.1）。
- 照搬车道侧会静默放掉这两组端点的 404 保护，且不会有任何报错。

所以基底必须取集成头一侧，只补车道新增段。凡是手工解 `api-proxy.ts`，
**先数各段在 base / ours / theirs 三方的出现次数**再决定谁当基底，不要默认 ours 正确。

## 关键 trade-off 2：单行正则的插入点是外层闭合括号，不是 `$`

第一次按 `lastIndexOf('$')` 插入，把
`|export-sources|export-jobs(...)` 放到了 `^(...)` **之外**：

```
^(products|...|duplicate-candidates\/[0-9a-f-]+(?:\/decision)?) | export-sources | ... ?)$
```

`$` 前面那个 `)` 是外层分组的闭合括号，插在 `)` 之后 = 整份白名单失去锚点、
退化成前缀匹配。后果：`route.test.ts` 31 条挂 16 条，且**波及 unit-groups / boms /
parties 等与导出毫无关系的模块**（它们只是恰好先撞上未锚定的分支）。

判定方法（比数括号可靠）：

- 逐字符走一遍 group depth，插入后必须 `final depth == 0` 且 `min depth == 0`。
- 与基线做**全探针行为 diff**，允许的差异必须恰好只有新加的那几条路径。
  本轮实测差异 = 5 条 export 路径，其余 0 条。

## 关键 trade-off 3：台账并集驱动会系统性回退「已交付」行

本轮两次踩同一个坑：3.8.2.1 并入时回退 3.6.2.1 / 3.8.1.2 / 3.9.1.1；
17.1.1.1 并入时回退 3.8.2.1 / 3.9.1.1。`contract-union` 按三方文本合并取一侧，
不理解「这行语义上必须是已交付」。

这正是「合并不了 → 台账不更新 → plan 重排已交付模块 → 复用陈旧分支 → 必冲突」
自强化循环的入口，所以**每次并入后必须逐行核对台账，不能只看 `sync --check` 绿**。

## 顺带修掉的既有缺陷

`.gitignore` 只忽略 `.tmp/`（目录），落在仓库根的 `.tmp-*.txt` / `.tmp_*.txt`
是平级文件不被覆盖，`git add -A` 会扫进提交。本轮 merge 提交带进去 2 个，
HEAD 里还留着上一轮 tick 的 4 个。已补两条文件模式并 untrack 全部 6 个。

## 验证

- `pnpm typecheck` 3/3 绿；`pnpm test` 46 files / **790 tests** 全过
- `cd backend && ./mvnw -pl services/catalog -am verify` **exit 0**，
  Flyway `Successfully applied 35 migrations ... now at version v36`
- 契约 union 后无重复导出名；271 paths / 439 schemas；11 条 export 路径齐全
- catalog 迁移 35 个，无版本号冲突（V33 在仓库里本就空号，Flyway 不要求连续）
- `sync.mjs --check` 通过

## 后续待办

- 在跑：3.9.2.1（完整度评分，hb 26min，尚未提交）、3.9.2.2（僵尸主数据清理，后端已绿）
- 新派：3.9.1.2（SKU 合并，V41）、17.1.1.3（层级授权，V42，identity 服务）
- `plan` 把正在跑的 3.9.2.2 标成 `redispatched: 1` ——它有活跃 worker，
  疑似用 `sessionId == null` 判断「是否派发过」，而上一 tick 的会话结束后该字段失效。
  本轮已跳过未派发，但**下一轮若照抄 toDispatch 会双写同一个 worktree**，值得改脚本。
- 并发评估：实测 load average 11.36 / 10 核，**不建议提到 8**；Docker 11.67GB 只用了约 1.35GB，
  内存不是瓶颈，CPU 才是。