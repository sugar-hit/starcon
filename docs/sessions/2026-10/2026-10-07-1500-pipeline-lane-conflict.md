# 15:00 轮 — 3.8.1.1 并入冲突根因定位与解冲突车道派发

## 决策摘要

本轮 `merge-ready` 无车道可并（`merged: []`），但发现 **3.8.1.1「导入向导」车道卡在 `conflict` 状态**：
代码已全部写完并 land 成功（tip `28a845a`），却**没能进集成头**。

实证后定位根因：**车道基线过旧 + 冲突文件不在并集驱动覆盖范围内**，两者叠加。

## 关键 trade-off

### 1. 冲突根因不是并集驱动的键冲突

`apps/web/src/lib/api-proxy.ts` 是**唯一**冲突文件。它不是生成物，而是手写的
**一整行巨型正则**（`/^(products...|import-jobs...|translations...)$/`，数百字符单行），
外加配套的注释块。

车道基线 `fa8dee8` 落后集成头 **8 个提交**。期间 3.6.1.1 并入了 `parties(...)` 段，
本车道加了 `import-templates|import-field-mappings|import-jobs` 三段 —— **同一行**。

`.gitattributes` 只给三个路径注册了 `merge=contract-union`：

```
packages/api-contracts/src/*.ts
packages/api-contracts/src/*.openapi.json
docs/product/capability-blueprint/module-progress.md
```

`api-proxy.ts` **不在其中**，所以走普通 git 文本合并。单行无法逐块合并 → 冲突。

与记忆中「并集驱动双边比文件」的盲点**不是同一个问题**：这次驱动根本没参与，
是覆盖范围缺口 + 陈旧基线。若强行把 `api-proxy.ts` 加进 `.gitattributes`，
需要为「巨型单行正则」写新的按键解析逻辑（键不是类型名/路径/模块 ID），
风险远高于手工解这一行 —— 本轮选择手工解，不改驱动。

### 2. 迁移版本号：保留 V29，不改成台账里的 V32

车道台账因重新 plan 把 3.8.1.1 的 `migrationVersion` 改写为 `V32`，
但磁盘上实际文件是 `V29__import_wizard.sql`。

集成头当前最高为 `V28__physical_safety_attributes.sql`，**V29 空缺**。
并行中的 3.6.1.2 占 V30、3.6.3.1 占 V31。

**决定保留 V29**：连续、无撞号；改成 V32 会在集成头留 V29 空洞，
而空洞会让后续车道误判可用版本。台账字段与磁盘不一致属调度器记账问题，
不在本轮擅自改 `lane.mjs` 记账逻辑。

### 3. 冲突解法：两边都留

已实证：集成头有 `parties(?:`、车道有 `import-jobs(?:`，**各有对方没有的内容**，
不是同一处被改坏 → 正确解是合并成一个同时含两段的正则，不是二选一。
这一行是安全边界（决定哪些后端路径被前端放行），少一段 = 该组端点 404，
正则语法写错 = 整份白名单崩掉。因此任务书要求解完必须 grep 计数 4 段全在 +
跑全量测试证明白名单仍工作。

## 本轮动作

| 动作             | 结果                                                                            |
| ---------------- | ------------------------------------------------------------------------------- |
| `sync.mjs`       | 465 模块 · 已交付 59 · 进行中 0 · 未开发 406                                    |
| `gate acquire`   | ok                                                                              |
| `merge-ready`    | `merged: []`                                                                    |
| `gc`             | `removed: []`                                                                   |
| `plan --lanes 3` | toDispatch 含 3.8.1.1（newlyPlanned）                                           |
| 派发             | 1 条：3.8.1.1 解冲突收尾（`bg_d93d21d3`）                                       |
| 跳过重派         | 3.6.3.1 / 3.6.1.2 —— 心跳 1–2 分钟内、`stale: false`、worktree 有真实未提交产出 |
| `gate release`   | ok                                                                              |

## 资源实测

- CPU 10 核 / 内存 24GB，Docker 上限 11.67GiB
- load average 3.19 / 3.46 / 3.11
- 常驻容器实际占用：mig-v2 41.62MiB、catalog-dev-catalog-1 423.9MiB、
  channel-1 428.9MiB、identity-1 407.5MiB ≈ **1.3GiB**
- 宿主机 vm_stat：free 141990 页 ×16KB ≈ **2.2GB free**

Docker 常驻占用仅 1.3GB / 11.67GB，富余很大；瓶颈在宿主机内存 free 2.2GB。
本轮只有 3 条车道在跑（2 正常开发 + 1 解冲突），远未压满。

**结论：暂不提并发到 8。** 真实约束是宿主机 free 内存 2.2GB，
而非 Docker 的 11.7GB 上限；6 条车道时单条验证峰值（JVM + Testcontainers PG16 +
next build）会同时挤占宿主机内存。建议等 6 车道满载跑一轮、记录 peak 后再评估。

## 后续待办

1. **等 3.8.1.1 解冲突车道 land** → 下一轮 `merge-ready` 才能把它并进集成头
2. **`.gitattributes` 覆盖缺口**：`api-proxy.ts` 这类「单行巨型正则 + 注释块」文件
   是并行车道的结构性冲突源。后续要么给驱动加一个「按注释块 + 正则分段求并」的模式，
   要么把这类白名单从单行正则重构成多行数组（一次性重构，之后冲突自然消失）
3. **`lane.mjs` 记账不一致**：3.8.1.1 台账 `migrationVersion=V32` 与磁盘 `V29` 不符，
   重新 plan 会覆盖已有落盘迁移的号。建议 plan 复用已存在车道时**以磁盘实际文件名为准**。
4. **3 条车道心跳监控**：若某条 `ageMinutes > 90` 且 `stale: true`，按调度纪律回收重派。
