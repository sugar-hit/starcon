# 并行车道 `plan` 盲区：只看上游交付，不看所属微服务是否存在，导致整队注定 BLOCK

- 创建: 2026-10-07 22:52
- Owner: TBD
- 优先级: **P0**（阻塞 D4 阶段约 60 模块的自动推进）
- 关联: `tools/module-progress/lane.mjs` 的 `pickModules` / `plan`；模块卷 09 / 11；
  `feat/bootstrap-trade-service`；`docs/product/capability-blueprint/README.md:118-124`

## 背景

`lane.mjs plan` 挑车道的判据只有两条：

1. 蓝图点名的上游模块已 `已交付`
2. 与已有车道处于不同的 L3 锚点

**它不检查「该模块所属的微服务目录是否存在于集成头」。** 而 D4 阶段（卷 09 订单履约、
卷 10 物流与头程、卷 11 售后与退货）按 `README.md:118-124` 的服务归属表，主责服务
都需要 `trade`（11 还需 `finance`）：

| 卷 | 服务归属（README.md） | 服务是否已在集成头 |
| -- | ---------------------- | ------------------ |
| 09 订单履约 | trade + channel | ❌ trade 缺 |
| 10 物流与头程 | channel + trade + warehouse | ❌ trade 缺 |
| 11 售后与退货 | trade + finance + warehouse | ❌ trade / finance 缺 |

集成头 `integration/module-dev`（`e44c62c`）下 `backend/services/` 只有
`catalog` / `channel` / `identity` / `warehouse`，**没有 `trade`，也没有 `finance`**。

于是任何落到卷 09/11 的车道，在依赖自检那一步必然 BLOCK，白烧一个车道位 + 一个
Flyway 版本号。2026-10-07 22:52 一轮实测：`plan --lanes 2` 派出
`9.3.2.2`（V84）+ `11.4.2.2`（V85），两条都因所属服务不存在而注定 BLOCK，
只能 abandon 回收。

## 缓解现状

服务引导车道 `feat/bootstrap-trade-service`（worktree `.worktrees/trade-bootstrap`）
正在把 `services/trade` 骨架落进集成头，落地后卷 09/10/11 的前置条件才满足。
在那之前，本流水线的调度纪律是：**派发前逐条核对 `toDispatch` 模块所属服务是否存在，
不存在则 `lane.mjs abandon --lane <ID> --reason "所属服务不存在"` 回收车位。**

## 待办内容

- [ ] 让 `services/trade` 骨架（`feat/bootstrap-trade-service`）真正并入集成头，
      使卷 09/10/11 的服务前置条件成立。
- [ ] 在 `lane.mjs plan`/`pickModules` 增加「所属服务目录存在」判据：模块所属卷若映射到
      尚未创建的微服务，则不排该车道（而非排出去让子任务 BLOCK），避免白烧车道位与版本号。
- [ ] 为卷 11/12 依赖的 `services/finance` 安排引导车道（当前完全缺失）。

## 影响面

- D4 阶段 60 模块中，卷 09（trade）+ 卷 11（trade/finance）+ 卷 12（finance）合计大量模块
  在 `services/trade` / `services/finance` 落地前**都无法自动推进**。
- 每次 `plan` 都可能反复挑中注定 BLOCK 的卷 09/11 模块 → 反复建/弃 worktree，
  调度噪声高但净产出为零。
- 不影响已在跑的 warehouse / catalog / identity 车道的正常交付。

## 完成标准

- [ ] `git ls-tree integration/module-dev backend/services/` 能看到 `trade`（及 `finance`）。
- [ ] `plan` 对「所属服务不存在」的模块不再派出车道（能自动跳过卷 09/11/12 直到服务就绪）。
- [ ] 卷 09 任一模块能被 `plan` 正常派出并成功 land，不再因服务缺失而 BLOCK。