# 15:15 轮 — 两条车道交付并入成功，api-proxy.ts 成为结构性冲突源

| 项   | 值                                                        |
| ---- | --------------------------------------------------------- |
| 进度 | 465 总数 / **60 已交付** / 0 进行中 / 405 未开发（12.9%） |
| 并入 | **3.6.3.1 地址与联系人** → `33428da`（唯一成功）          |
| 冲突 | 3.8.1.1、3.6.1.2 均卡 `apps/web/src/lib/api-proxy.ts`     |
| 闸   | acquire / release 正常；`merge --abort` 后主工作区干净    |

## 三条车道全部交付并 land

| 车道    | 模块         | 迁移 | commit    | 验证                               |
| ------- | ------------ | ---- | --------- | ---------------------------------- |
| 3.6.1.2 | 角色档案     | V30  | `7443ae7` | catalog 869 例 0 失败；前端 673 例 |
| 3.6.3.1 | 地址与联系人 | V31  | `6719e76` | catalog 864 例；前端 667 例        |
| 3.8.1.1 | 导入向导     | V29  | `3884232` | 15:00 轮解冲突车道收尾             |

三条 lane 状态均为 `ready`、worktree 干净。3.8.1.1 是 15:00 轮那条
解冲突车道（`bg_d93d21d3`）的产出，15:05 解完 `api-proxy.ts` 冲突并 land。

## 根因：`api-proxy.ts` 是并行车道的结构性冲突源

三条车道**全都**改了 `apps/web/src/lib/api-proxy.ts`，而且都改在同一处 ——
`parties(...)` 那一段所在的**单行巨型正则**。该文件不在 `.gitattributes` 的
`merge=contract-union` 覆盖范围内（只覆盖 `packages/api-contracts/src/*.ts`、
`*.openapi.json`、台账），走普通 git 文本合并；单行无法逐块合并 → 必冲突。

实测三条车道各自往这一行加的内容：

- 3.8.1.1：`import-templates|import-field-mappings|import-jobs`
- 3.6.1.2：`role-profiles` + `(?:supplier|customer|carrier)-profile`
- 3.6.3.1：`addresses(...)` + `contacts(...)`

**每一段都是安全边界**：少一段 = 该组端点前端 404；正则语法写错 = 整份白名单崩掉。
所以这条冲突不能靠「二选一」解，必须合成一个同时含三段的正则。

## 本轮处置：不擅自解冲突

`merge-ready` 退出码 2，把 3.8.1.1 与 3.6.1.2 标成 conflict。
按调度纪律**没有自己挑解法**，只做了状态清理：

1. `merge --abort` 解除主工作区的 `MERGE_HEAD` 与 UU 文件，恢复干净
2. 实证 3.6.3.1 的并入成果**未受影响**（`git merge-base --is-ancestor 6719e76` = YES，
   集成头 `33428da`）
3. 闸已 release

冲突文件与原因已记入本文件，交下一轮专门处理。

## 我自己踩的一个坑：merge-tree 干跑污染主工作区

为预判冲突先跑了 `git merge-tree --write-tree`，结果**主工作区两个 tracked 生成物被改写**
（`catalog.ts` / `catalog.openapi.json`，15:12），
导致 `merge-ready` 以「主工作区不干净」直接拒绝。

根因：`merge=contract-union` 驱动的实现是
`writeFileSync(oursPath, ...)` —— **它真的往 `%O`（工作区文件）写**，
所以 `merge-tree` 这种「只算不落盘」的干跑在这里并��安全。

处置：`git checkout --` 还原两个文件 + 删掉自己的 `.tmp-tree.txt`，确认工作区干净后再真合并。
**以后在这个仓库不要用 `merge-tree` 预判冲突**，它会写工作区。

## 顺带发现：worktree 基线 provisioning 有缺陷

3.6.3.1 的 worker 报告：worktree 分支实际建在 `020f5b6`，落后集成头 **22 个提交**
且不含上游 V27（`party` 表），而 lane 记录里 `baseSha` 写的是 `36a871e`。
worker 用 `git merge --ff-only`（当时 HEAD 0 ahead，不会丢工作）对齐后才开工。

这是**调度侧缺陷**：`plan` 记录的意图与 worktree 实际 checkout 的基线不一致。
本轮它自愈了，但下一次未必这么幸运。

## 后续待办

1. **`api-proxy.ts` 冲突需专门一轮**：把三条车道各自新增的段合成同一个正则，
   解完必须 grep 计数确认 `import-jobs` / `role-profiles` /
   `(?:supplier|customer|carrier)-profile` / `addresses` / `contacts` 五段全在，
   并跑 `route.test.ts` 证明白名单仍工作
2. **根治方案**：把白名单从单行正则重构成多行数组或独立常量 —— 一次性重构，
   之后这类冲突自然消失。或给并集驱动加「按注释块 + 正则分段求并」模式。
   重构优于加驱动模式（后者要为非类型名/路径的键写新解析逻辑，风险高）
3. **`plan` 的 baseSha provisioning**：复用已存在车道时应以磁盘实际分支位置为准，
   并在 base 落后集成头时自动快进
4. **`.tmp-tree.txt`** 这类自己的临时文件应写在 `.tmp/` 下（AGENTS.md 落盘纪律）
