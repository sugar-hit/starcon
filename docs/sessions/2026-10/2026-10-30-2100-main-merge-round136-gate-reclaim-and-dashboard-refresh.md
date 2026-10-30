# 第 136 轮 · 调度闸接管根治入 main + 看板刷新到 94.0%

> 时间：2026-10-30 20:46 → 21:05（Asia/Shanghai）
> 执行机：`/Users/tommy/Project/starcon`（本机是**只读复核 + 入库改动 + 看板发布**方，
> 模块开发由另一台机器 `/Users/guan/Projects/starcon` 承担）
> 分支：`integration/module-dev` → `main`（经 PR #69）

---

## 一、本轮动作（三件）

| # | 动作 | 结果 |
| --- | --- | --- |
| ① | 把「孤儿续期守护死锁根治」的两个提交推上远端 | `5b408ae9`（闸接管根治）+ `9c3fad9b`（能力地图刷新），已推 `upstream/integration/module-dev` |
| ② | 重建并原地发布交付进度看板 | 线上 **437/465 = 94.0%**（此前停在 431/92.7%） |
| ③ | 把集成分支经 PR 合入 `main` | PR #69 已合，`upstream/main` = `7852acdd`（双父） |

---

## 二、看板刷新（线上 URL 不变）

- 协议：`tools/progress-site/REFRESH.md`。**本机不跑 `refresh.sh`** ——
  它的第一步是 `git fetch origin main`，而本机 remote 叫 `upstream`、没有 `origin`，
  按协议会降级用本地工作树 ⇒ 发布出比远端更旧的数据。改为直接跑
  `build.mjs --docs-ref upstream/main` + `check.mjs --docs-ref upstream/main`。
- 数据自检：465 行无重号、437 个已交付全部有证据、465 个模块全部有职责描述 —— 全绿。
- 发布：原地更新 `node_id=454096010989704`，URL 不变。
- 线上校验：`index:200`，`data.json` 读到 `437/465 = 94%`，`sourceRef: upstream/main`。

---

## 三、PR #69 的闸门判定

main 基线本身就是红的（存量失败 4 个作业），判据是**「PR 的失败不得比基线更多」**，不是「全绿」。

闸工具结论：**新增失败作业无 / 新增失败用例无 / 结论：可以合并**（退出码 0）。

三个作业只能比到作业粒度，按协议逐个下钻，结论如下：

| 作业 | 下钻方式 | 基线（run `41054881386`） | PR 侧（run `41058598665`） | 判定 |
| --- | --- | --- | --- | --- |
| Lint | 比**不合规文件集合**（非文件数） | 1086 个 | 1085 个 | **净减 1、零新增**；少掉的是 `tools/module-progress/PIPELINE.md`（本轮改动顺手把它排版修合规了） |
| Security Scan | 比漏洞条数与严重度分布 | 10 项，1 low / 6 moderate / 2 high / 1 critical | 完全相同 | 两侧一致；本轮未动任何依赖文件 |
| Catalog Browser | 比失败根因链 | `reporting` 与 `channel` 均 `AccessDeniedException: /app/target` | 逐条相同 | 同一根因（CI 容器未设存储根目录环境变量），非本轮引入 |

Java 21 作业**跑到了测试阶段**（644 例 / 3 失败，都在 identity 模块），闸工具能比到用例粒度，
未判为新增失败 —— 说明这 3 个失败在 main 基线里同样存在。

---

## 四、合并形态与验证

- 形态：**真合并无冲突**（`git diff --name-only --diff-filter=U` 为空）。
- 定向验证三项全过：
  - 静态预检相对 main 基线的增量：`ADD COLUMN` 0 / `SET NOT NULL` 0 / `ADD CONSTRAINT.*CHECK` 0；
  - 台账行数恒为 465，模块 ID 无重复；
  - `sync.mjs --check` → 「台账与蓝图一致」。
- 另外跑了本次带进 main 的闸测试回归：`lane-gate.itest.mjs` 21 通过 0 失败、
  `lane-gate-reclaim.itest.mjs` 24 通过 0 失败。
- **未跑本地全量 L2** —— 依据协议第 5.1 节：本次增量是工具与文档，
  全量闸由远端 CI 承担；本机处于发热敏感状态。

合并后核验：

- `upstream/main` = `7852acdd`，**双父**（父1 `7be9a62e` / 父2 `5428050b`）；
- main 的树与 `upstream/integration/module-dev` **逐字节相同**（零差异）；
- 集成分支头已是 main 的祖先；
- 双向同步模拟冲突数 **0**；
- main 台账：已交付 **437/465 = 94.0%**，未开发 28。

---

## 五、本轮遇到的两个坑

1. **`git worktree add --force -B` 不能移动被别的 worktree 占用的分支。**
   `.worktrees/main-merge-204536`（10-24 残留）仍占着本地 `chore/sync-module-dev`，
   强制移动被 git 直接拒绝。改用「detached 建 worktree + 独立本地分支名 + 推到远端同名分支」
   绕开，既不删任何人的残留状态，也不受分支占用影响。
2. **GitHub 日志接口会瞬时限流。** 拉基线 Security Scan 日志时返回
   `ServerBusy: Egress is over the account limit`，正文是 XML 错误而不是日志。**重试一次就好**，
   但要把「拉到的是错误页」和「日志为空」区分开 —— 否则会误判成「基线没有失败」。

---

## 六、仍未解除的阻塞（需要人工在活执行方机器上操作）

### 6.1 `TOKEN_HORIZON` 延期只在**本机**生效

`.dev-agent/PIPELINE.local.md` 被 `.gitignore` 忽略，不随 git 传递；
活执行方在另一台机器，读的是**它自己的**那份本地文件，仓库里没有任何兜底能覆盖它。

本机已改为 `TOKEN_HORIZON=2026-11-30T00:00:00+0800`（原值 `2026-10-25` 已过期）。
**活机器上必须执行同一行修改**，否则流水线会一直停在降档模式：

```bash
# 在活执行方机器上，把 .dev-agent/PIPELINE.local.md 里
# TOKEN_HORIZON=2026-10-25T00:00:00+0800
# 改成
TOKEN_HORIZON=2026-11-30T00:00:00+0800
```

效果：恢复高强度模式（`LANES=4`、`THROTTLE_MIN=50`），吞吐回到日均约 35 个模块。
协议明写「降档是自动的，不要问、也不要自作主张改回高强度」⇒ 这个值只能由人工改。

### 6.2 本机三个定时任务全部处于暂停状态

本机的「集成分支并入 main」「并行车道」「465 模块持续开发」三个任务都是 `enabled: false`。
本机是**废弃副本**，真正的执行方在另一台机器。若要让本机接管，需要先确认活机器的
调度器已停，否则两套安装会指向同一批 `feat/<id>` 分支名 ⇒ 双写与 Flyway 撞号。

---

## 七、遗留项（本轮未动，供后续轮次认领）

- 流水线 L2 闸**不跑 `prettier --check`**，每批都带格式债进 main（存量 1086 → 本轮后 1085）。
  修法是给 L2 加 `prettier --check`，属另一轮改动。
- CI workflow 未设 `REPORTING_EXPORT_STORAGE_ROOT` / `CREDENTIAL_STORAGE_ROOT`，
  `reporting` 与 `channel` 在 CI 容器里都起不来。
- `.worktrees/main-merge-204536`（10-24 残留）仍占着本地 `chore/sync-module-dev` 分支，
  里面有 6 天前一次未提交完的合并状态。本轮**没有删它**（可能是别人的进行中状态），
  但它会让「直接 checkout 同名分支」失败，后续轮次沿用本轮绕开方式即可。
- 活机器 round-135 记录的另外几项未决（`vitest.config.ts` 并发上界与文件总数守恒断言、
  `gc` 改用 `lane.worktree`、`vitest` 静默漏跑文件、Docker VM 常驻高 CPU）本轮未涉及。
