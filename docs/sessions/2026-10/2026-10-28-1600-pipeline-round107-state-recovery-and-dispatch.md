# 2026-10-28 16:00 流水线第 107 轮 —— 状态恢复取证 + 两条车道派发

> 本轮**没有模块交付**（两条车道刚派出，尚未落地），因此**不占用 batch 号**、也不写 batch 交付说明。
> 本文件是 AGENTS.md 要求的「有结论的会话必须落 `docs/sessions/`」记录。

## 决策摘要

| 项 | 值 |
| --- | --- |
| 档位 | **reduced（降档）** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过 ⇒ `LANES` 4→**2**、节流闸 50→100 分钟 |
| 台账 | **410 / 465 已交付（88.2%）**，进行中 0，未开发 **55**；465 行、0 重号 |
| 闸 | 起手 **held / ageSeconds=9188 / stale=true**（owner PID 65556 已死）⇒ 按 TTL 判陈旧，**合法取闸** |
| 本轮回收 | **6 条陈旧车道全部 abandon**（5 条 ready + 1 条 running），详见第三节 |
| 本轮派发 | **1.5.2.1 服务与任务状态**（channel，V386）、**13.1.2.1 目标设定**（reporting，V387） |
| L2 | **未跑** —— 本轮无 ready 车道可并，无验证对象（且代码树与上游 L2 已绿的 `d70ce0e8` 逐字节相同，见第四节） |
| push | 已 push（**仅文档**：本文件；代码状态仍是 batch-104 L2 已绿的 `d70ce0e8`） |

## 一、起手发现：本地集成头落后上游 742 个提交

起手 `lane.mjs status` 报 `integrationHead: fea0cb25`、已交付 **287** —— 与上游台账对不上。
逐字取证：

```
git merge-base --is-ancestor HEAD upstream/integration/module-dev   # ⇒ 真（本地是上游祖先）
git rev-list --count HEAD..upstream/integration/module-dev        # ⇒ 742
git rev-list --count upstream/integration/module-dev..HEAD        # ⇒ 0（本地没有独有提交）
```

⇒ 本地是**纯落后**（fast-forward 即可，无分叉、无需人工裁决），远端最新提交 `e6f443ae`（10-26 21:45）。
`git log` 显示远端这段时间一直在正常推进（batch-99 ~ batch-104、main 同步第 77~82 轮），
**是本地这一侧停了**，不是上游停了。

**处置**：`git merge --ff-only upstream/integration/module-dev` ⇒ 集成头前进到 `e6f443ae`，
落后提交数 742 → 0。**本地零独有提交，所以快进不会丢任何东西。**

**教训（可复用）**：`lane.mjs status` 的「已交付数」是**读本地台账**得到的，
不是远端的真相。本地落后时它会安静地少报 —— 287 vs 410 差 123 个模块，
**光看 status 输出不会发现**。判定「本地是否落后」的最小充分命令是上面那三条
（`--is-ancestor` + 两个 `rev-list --count`），**不要用 status 的统计数字当真相**。

## 二、台账数字随之变了：287 → 410

快进后重跑 `sync.mjs`：

```
✓ 同步 18 卷 / 465 个 L4 模块
  已交付 410 · 进行中 0 · 未开发 55
```

台账完整性（并集驱动器的重复行问题曾在这里咬过人）：

```
grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' module-progress.md   # ⇒ 465（恒定）
grep -oE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' module-progress.md | sort | uniq -d   # ⇒ 空
```

分阶段（D1–D2 80/80、D3 40/40、D4 60/60、D5 52/52、D6 75/78、D7 103/155）。
`sync.mjs` 跑完 `git diff --stat` 为空 ⇒ **快进后的台账本来就是对的，本地那 287 是旧台账**。

## 三、回收：6 条陈旧车道必须 abandon，**不能 merge**

`.dev-agent/lanes.json` 停在 10-19（9 天前），有 **5 条 ready + 1 条 running**。
逐条取证后**全部 abandon**，理由逐条写进了状态文件。

### 3.1 根因：这些模块**上游已经交付了**，只是版本号不同

| 模块 | 车道分支的迁移 | 上游实际交付的迁移 | 上游台账 |
| --- | --- | --- | --- |
| 2.4.2.3 映射缺失队列 | channel `V189__mapping_gap_queue.sql` | channel `V191__listing_mapping_gap_queue.sql` | 已交付 |
| 11.2.3.3 供应商索赔 | trade `V184__trade_supplier_claim.sql` | trade `V188__supplier_claim.sql` | 已交付 |
| 4.4.2.1 商品认领 | channel `V188__listing_claim.sql` | channel `V190__listing_claim.sql` | 已交付 |
| 7.8.2.1 分摊结果查看 | warehouse `V190__warehouse_allocation_result.sql` | warehouse `V189__warehouse_allocation_result_view.sql` | 已交付 |
| 4.2.1.1 刊登编辑器 | channel `V191__listing_editor.sql` | channel `V194__listing_editor.sql` | 已交付 |
| 4.2.2.1 刊登模板 | （分支相对基线 **0 commit**，worker 从未提交） | channel `V195__listing_template.sql` | 已交付 |

⇒ 这些模块在 10-19 之后被**重新交付**了（换了 Flyway 版本号、换了文件命名、
前端目录也重组过），**车道状态没跟上**。

### 3.2 🔴 并入会直接炸库：Flyway 版本撞号 + 同表重复建

逐个比对了「车道分支的迁移」与「上游的迁移」建了哪些表：

| 模块 | 撞版本号？ | 同表重复建？ |
| --- | --- | --- |
| 2.4.2.3 | 否 | **是** —— `listing_mapping_gap` |
| 11.2.3.3 | 否 | **是** —— `supplier_claim` / `supplier_claim_event` / `supplier_claim_negotiation` / `supplier_claim_settlement` |
| 4.4.2.1 | 否 | **是** —— `listing_claim` |
| 7.8.2.1 | 否 | 否（但会产出第二套重复实现） |
| 4.2.1.1 | **是** —— channel 服务里 **V191 两个文件**（`V191__listing_editor.sql` vs 上游 `V191__listing_mapping_gap_queue.sql`） | **是** —— `listing_editor_draft` |

⇒ **5 条里有 4 条会导致 Flyway 直接失败**（同服务同版本号两个迁移文件 / 同名表建两次），
第 5 条不炸库但会产出同一模块的第二套实现。
**merge-ready 若跑在这份状态上，L2 必然红，且红在 Flyway 而不是业务断言上。**

⇒ 正确处置是 **abandon（不是 merge）**，并把理由写进状态文件，
让下一个读状态的人知道「这些不是失败的车道，是已被取代的旧版本」。

### 3.3 `4.2.2.1` 的 running 状态是假的

`feat/4-2-2-1` 相对基线 `894d8ab` **0 个提交**，worktree 干净 ⇒ worker 从未产出任何东西；
而模块已由上游 `V195__listing_template.sql` 交付。⇒ abandon（未产出成果）。

### 3.4 `gc` 清理

`lane.mjs gc` 移除了 6 条车道的 worktree（`2.4.2.3` / `11.2.3.3` / `4.4.2.1` / `7.8.2.1` /
`4.2.1.1` / `4.2.2.1`）。

## 四、🔴 一个差点让本轮白跑的地雷：未跟踪文件挡住 merge-ready

`merge-ready` 的干净度检查是 `git status --porcelain`（**含未跟踪文件**），
不通过就 `exit 1`「主工作区不干净，拒绝切分支」。本轮起手 `git status` 有一条：

```
?? .tmp-gate-watch.sh
```

取证：内容是上一轮（14:05）留下的**只读轮询脚本**（等 dispatch 闸释放），
`pgrep -fl gate-watch` 无进程 ⇒ **孤儿文件**。
它会让下一轮 `merge-ready` 在「主工作区不干净」处直接退出，
**并入通道彻底堵死**（这正是 `merge-ready` 失败后必须立刻 `merge --abort` 的同一个坑的入口）。

处置：用可恢复删除（`mavis-trash`）移走，**不是 `rm`**。

⇒ **可复用的纪律**：调度者跑完一轮后要确认工作区**没有未跟踪残留**，
否则下一轮的并入会被一条孤儿文件卡死，而症状是「并入没动静」而不是「并入失败」。

## 五、容量闸与各道闸的实测结果

| 闸 | 实测 | 判定 |
| --- | --- | --- |
| 温度闸 `pmset -g therm` | `No thermal warning level has been recorded` | 通过 |
| 配额闸 running | 0（回收后） | 通过（配额 6） |
| PG 闸 | Testcontainers 标签容器 **0 个**；`docker ps` 只有常驻的 `starcon-mig-v2`（Up 3 weeks，非 Testcontainers） | 通过 |
| load 闸 | 4.27 / 4.02 / 3.83 → 4.06 / 3.98 / 3.82（30 秒三点采样，1min < 5min ⇒ 尖峰已过） | 通过（阈值 12） |
| 节流闸 | 距上批 **12750 分钟**（上批 10-19 20:05） | 通过 |
| 静默窗口 | 现在 16:36（窗口 20:30→08:00） | 通过 |
| 调度闸 | 起手 held / age 9188s > TTL 4200s / stale=true / owner PID 已死 | **陈旧闸，合法取闸** |

⚠️ 顺带修掉一个**孤儿 keepalive** 隐患：上一轮笔记警告过「`keepalive-r105.sh` 被 launchd 收养后
每 5 分钟 touch 闸目录 ⇒ `stale` 字段永远显示不陈旧」。本轮实测 `ps` 里**没有**任何
`keepalive` / `touch dispatch.gate` 进程 ⇒ 该隐患当前**未复现**，但机制仍在（陈旧的闸脚本被 launchd 收养）。
判持闸者是否还活着要看 **owner 时间戳 + `ps`**，不能只看 `stale` —— 本轮两者一致。

## 六、本轮派发（`plan --lanes 2`，降档后）

| 车道 | 名称 | 阶段 | L3 | 落地服务 | 迁移 | 分支 / worktree |
| --- | --- | --- | --- | --- | --- | --- |
| `1.5.2.1` | 服务与任务状态 | D7 | 1.5.2 | channel | `V386` | `feat/1-5-2-1` |
| `13.1.2.1` | 目标设定 | D7 | 13.1.2 | reporting | `V387` | `feat/13-1-2-1` |

`serviceBlocked` 为**空**（⇒ 不是服务缺口堵死）；两条车道 **L3 锚点互不相同**、互不依赖。
V386/V387 在上游**未被占用**（上游最大 V385），且与上游 10-26 第 105 轮分配的版本号一致 ⇒ 分配无冲突。

### 6.1 沿用上游第 105 轮的取证结论（不重复调查）

上游 batch-104 的会话记录里已经把这**两个模块**的取证做完并写进任务书，
本轮直接沿用（每条都重新在本机复核过）：

- **`1.5.2.1`**：channel 拥有 30+ 张任务/运行类表，`sync_run` 的列
  （`status` / `started_at` / `finished_at` / `duration_ms` / `trigger_source`）够用；
  `sync_failure.reason` 的值天然分「业务/配置」与「系统/外部」两类
  ⇒ **本模块的命门就是把这两类分开呈现**。
  ⇒ 卷 01 红线：只读聚合、零写端点；**是否建表按事实判断，不要为了用掉 V386 造派生表**
  （先例：1.5.1.1 主动不占用 V338、1.5.1.2 主动不占用 V375）。
  ⇒ 卷 18 整卷未开发 ⇒ 规则任务如实报未接通 + 归属模块号，不造表顶替。
- **`13.1.2.1`**：指标码**已登记**（租户级 12 个 + 店铺级 `STORE_*`）⇒ 只引用码、不重写公式。
  ⇒ 蓝图点名的「广告 ROI」**没有 `AD_ROI` 码**、「库存周转」**没有周转码**
  ⇒ **不许自造公式、不许拿 `INVENTORY_VALUE` 冒充库存周转**。
  ⇒ 归属对象在 identity ⇒ 走端口 + 记快照，reporting 不跨库查表。

### 6.2 两条车道都会改 `api-proxy.ts` ⇒ 锚点必须错开

任务书里明确写了「插入锚点必须与对方错开」（`13.1.2.1` 建议选 `services[]` 数组末尾
收尾 `];` 之前），并要求**两层 alternation 都求并**、**跑真 proxy 断言 HTTP 状态码**
（拦截=404、放行=转发）**并同时断言反向用例**（未知路径仍 404）——
不看眼、不用 grep + 自写正则（该方法已连续骗过多版）。

## 七、待办与风险

1. **两条车道在途**：下一轮回收 `ready` → 跑 L2 全量 → 写 batch 说明 → commit → push。
2. **本地集成头落后问题已修，但成因未定位**：为什么本地会落后 742 个提交？
   `git reflog` 显示本地最后一次活动是 10-24 05:12（切到 `chore/sync-module-dev`），
   而远端一路推进到 10-26 21:45 ⇒ **10-24 之后本机这一侧就没有再同步过**。
   本轮起手 `gate status` 的 owner 时间戳是 **10-28 13:50**（= 今天），
   也就是说**今天早些时候有实例动过闸**，但 `.dev-agent/lanes.json` 却停在 10-19
   ⇒ **状态文件与闸不是同一套**。这一条**留给下一轮取证**，本轮不猜。
3. **`.dev-agent/PIPELINE.local.md` 缺 `TOKEN_HORIZON` 键**：本机这份配置（10-19 21:45 写）
   没有该键，按 `PIPELINE_CRON_PROMPT.md` 第 0.5 步的读法会得到 `MODE=full` / `LANES=4`。
   但上游 10-24 ~ 10-26 的**每一轮**都记着 `TOKEN_HORIZON=2026-10-25` 已过 ⇒ 降档 / `LANES=2`。
   ⇒ **本轮按上游最近三轮的实际档位走（reduced / 2 条）**，但**本机配置缺键**这件事
   要有人补 —— 否则下轮会读到 `LANES=4` 就多派一轮，与温度/噪音约束相悖。
   本轮**没有自行改这个本地文件**（它是用户改的、且不进版本库）。
4. **`SILENT_WINDOW` / `PROJECT_ROOT` / `PUSH_REMOTE` 三个键在本机配置里也缺**。
   本轮按 `QUIET_FROM=20:30` / `QUIET_TO=08:00` 与实际仓库根推导，未硬编码绝对路径。
5. **CI 在 `integration/module-dev` 上是红的**（最近三次 `Integration/module dev` 均 failure）。
   本轮未改任何代码（代码树与上游 L2 已绿的 `d70ce0e8` 逐字节相同），
   所以那是**上游既有状态**，不归本轮；但下一轮 L2 跑全量时要把它当基线看，
   **别把上游带进来的红当成本轮车道造的**。
