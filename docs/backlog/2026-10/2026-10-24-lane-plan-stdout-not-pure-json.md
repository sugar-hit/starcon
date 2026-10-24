# `lane.mjs plan` 的 stdout **不是纯 JSON** ⇒ 解析失败后重跑会造成超编派发（2026-10-24 batch-85 踩坑）

**优先级：P1**（会真的多派车道，撞并发上限）

## 现象

2026-10-24 14:00 tick：

```bash
node tools/module-progress/lane.mjs plan --lanes 4 | python3 -c "import json,sys; json.load(sys.stdin)"
# → JSONDecodeError: Expecting value: line 1 column 1 (char 0)
```

调度者以为 plan 没生效，**又跑了一次** `plan --lanes 4` ⇒
第二次把第一次排出的 4 条当作「已占用」跳过（`lane.mjs` 里
`if (state.lanes[id] && ['running','ready'].includes(state.lanes[id].state)) continue;`），
**又排了 3 条新车道** ⇒ 合计 7 条 = 超出 `LANES=4`，且多占了 3 个 Flyway 版本号。

## 根因

`plan` 在往 stdout 打 JSON 之前，会把 `git worktree add` / `git branch` / `pnpm install` 的输出
直接混进同一条 stdout：

```
fatal: Needed a single revision
Preparing worktree (checking out 'feat/1-1-3-2')
...
{
  "ok": true,
  ...
}
```

⇒ **`plan` 的 stdout 前半段是子进程噪声，末尾才是 JSON。**

## 处置（本轮）

**没有手改 `lanes.json`**，用脚本自带的机制回退：

```bash
node tools/module-progress/lane.mjs abandon --lane 1.1.3.2 --reason "本轮调度名额已满(LANES=4)，调度器误触发二次 plan 造成的超编车道，本轮不派发，下轮重新排车"
node tools/module-progress/lane.mjs gc     # abandon 只改状态、不删目录；gc 才删 worktree
```

`abandon` 会把车道状态置 `abandoned`，其 `migrationVersion` 随即被 `nextFree()` 视为「可用」⇒ 版本号没有浪费。

## 后续待办

- [ ] `lane.mjs plan`：把 JSON 输出写到 **stderr** 或独立文件，stdout 只留 JSON（这样就能直接 `| jq`）。
- [ ] `lane.mjs plan`：加一条**幂等护栏** —— 若 `toDispatch.length + 已占用 running/ready 数 > --lanes`，
      直接报错退出而不是继续排车（把超编挡在脚本里，而不是靠调度者事后 abandon）。
- [ ] 调度侧纪律：**plan 解析失败时不要重跑**，先 `node tools/module-progress/lane.mjs status` 看当前车道状态。
