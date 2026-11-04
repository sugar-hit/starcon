# channel 的 verify 单跑 40 分钟 ⇒ 它是后端矩阵的关键路径；另遇 GitHub Actions 作业「步骤全绿却不落账」

**优先级：P2 —— 不阻塞功能交付，但每个 PR 的 CI 关键路径都压在一个服务上，
而排查「CI 卡住」本身会消耗大量时间。**

发现于 2026-11-04 PR #76（Kubernetes 部署件）的新旧两轮 CI 观测。

---

## 一、后端矩阵的实际耗时（run 41869904295，2026-11-04）

`.github/workflows/ci.yml` 的后端作业已按服务拆成 9 路并行矩阵，
但**墙钟时间取决于最慢的那一路**，不是平均：

| 服务 | `mvnw verify` 耗时 |
| --- | --- |
| **channel** | **40m00s**（16:43:24 → 17:23:15） |
| catalog | 30m10s |
| warehouse | 28m45s |
| trade | 20m17s |
| finance | 8m17s |
| reporting | 7m28s |
| crm | 5m34s |
| identity | 5m14s |
| platform | 5m08s |

⇒ 矩阵墙钟 ≈ 40 分钟，**channel 一家占满**。第二名 catalog 差 10 分钟。
channel 是本仓最大的服务（22 个环境变量、9 个上游依赖、功能覆盖面最广），
所以这不是「某服务配置错了」，而是「最大的服务自然最慢」。

对比之下，最小的一路（platform 5m08s）只用了 1/8 的时间。

### 可查的方向（未验证，需实测）

- channel 的集成测试是否集中在少数几个真库用例上 —— 若能按业务域再切一刀
  （比如「凭证/授权」「同步」「OAuth」分文件并行），墙钟能压下来；
- 是否有 Testcontainers 用例在等**超时**而不是真在跑 ——
  同族已有先例：`docs/backlog/2026-10-2026-10-22-testcontainers-pg-container-leak.md`
  （PG 容器泄漏）与 `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`；
- `surefire` 是否配置了并行（`forkCount` / `reuseForks`）—— 现在是串行的。

⚠️ 拆分前先量清楚：**不要为了压时间而放宽断言或跳过用例**。
本仓已有的教训是「把测试拆细」和「把测试变松」必须分开记录、分开评估。

---

## 二、GitHub Actions 作业「所有步骤 success，但 job 永不落账」

同一轮 CI 里另一个独立问题，**与代码无关，但会让 PR 卡住**：

```
job 126714820311 (channel)
  status = in_progress      completed_at = null
  ├─ Set up job                          → success
  ├─ actions/checkout@v4                 → success
  ├─ actions/setup-java@v4               → success
  ├─ ./mvnw -B -ntp -fae verify -pl services/channel -am  → success  (40m)
  ├─ actions/upload-artifact@v4          → skipped
  ├─ Post actions/setup-java@v4          → success
  ├─ Post actions/checkout@v4            → success
  └─ Complete job                        → success  (17:23:16Z)
```

**所有步骤都是 success，包括最后一步 `Complete job`**，但 GitHub 侧
`status` 一直停在 `in_progress`、`completed_at` 一直为 `null`，持续 25 分钟以上。

### 为什么它卡住整条 PR

- 依赖它的 **Quality Gate**（`if: always()` + `needs:` 全部作业）因此一直 `pending`；
- PR 的 `mergeStateStatus` 停在 `UNSTABLE`，合不了；
- **`gh run rerun --job` 被拒**：`job 126714820311 cannot be rerun` ——
  作业还挂在 `in_progress`，GitHub 不允许重跑一个「没结束」的作业。

### 怎么分辨「真失败」与「记账滞留」

看 **steps 数组**，不要只看 job 的 `status`：

```sh
gh api repos/<owner>/<repo>/actions/jobs/<job-id> \
  --jq '.steps[] | "\(.status)\t\(.conclusion // "-")\t\(.name)"'
```

- 所有步骤 `completed` 且 `conclusion=success`（尤其含 `Complete job`）
  ⇒ **测试真的过了**，是 GitHub 的作业收尾没落账；
- 有步骤 `completed` 且 `conclusion=failure` ⇒ 真失败，去读那个步骤的日志。

### 有效的处置

取消滞留的 run、同步 main 触发一次干净的 run（本例实测有效）：

```sh
# 在独立 worktree 里把 main 合进 PR 分支（零冲突），推送后
# 并发组（cancel-in-progress）会取消旧 run、起新的
git merge origin/main --no-edit
git push origin <pr-branch>
```

代价是整轮重跑（约 40–50 分钟）。**不能靠 `--job` 重跑**（见上）。

### 这在本仓不是孤例

`gh run list` 里 `docs/readme-refresh` 与本 PR 各有若干 `cancelled` 的 run ——
说明这套 runner 的收尾不稳定是**反复出现**的，不是偶发一次。

⇒ **排查 CI 红灯前先确认 job 状态与 steps 结论是否一致**，别急着改代码。
把「记账滞留」误判成「测试失败」会引出一堆无谓的代码改动。

---

## 三、待办

- [ ] 量化 channel 的 40 分钟构成（哪些用例占大头、是否在等超时）
- [ ] 评估 channel 测试按业务域拆分 / `surefire` 并行的可行性
- [ ] 在 `docs/operations/` 里补一节「CI 作业滞留怎么辨」，
      让下一个人不必重新推一遍（本条 backlog 即是草稿）
- [ ] 观察 GitHub Actions 收尾滞留的复发频率，若仍频繁则考虑换 runner / 镜像
