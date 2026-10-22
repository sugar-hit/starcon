# 2026-10-23 02:15 集成分支并入 main（第 38 轮 · 零净变更复核）

## 摘要

「`integration/module-dev` → `main`」同步任务第 38 轮。

**结果：零净变更，本轮没有开同步 PR。**
集成分支与主分支的内容差异在解完冲突后**归零** —— 合并结果的落地树哈希与 `main` 的树哈希
**逐字节相同**，落地的 Pull Request 文件差异为空。

本轮的实际产出有两项：

1. 把「纯排版差异」的取证配方跑通并**修正了一处配方缺陷**（见「决策 / 结论」第 2 条）。
2. 复核确认主分支相对集成分支**没有待合入的新交付**，功能进度台账两侧逐字节未变。

## 决策 / 结论

### 1. 「有没有新内容」仍以落地树哈希为准，结论为零净变更

- `upstream/main` tip = `5b642caf`，`upstream/integration/module-dev` tip = `f36ac759`。
- 两侧树差异报 **30 个文件**（412 增 / 640 删），看上去像一次实增量。
- 但逐文件核对后，这 30 个文件的差异**全部不是内容差异**：

| 类别                     | 文件数 | 核对结论                                                               |
| ------------------------ | ------ | ---------------------------------------------------------------------- |
| 两侧都存在，差异只在排版 | 29     | 经格式归一化后**逐字节相同**（A=29）；主分支侧已是规范态（B=29 / C=0） |
| 只在主分支侧存在         | 1      | 主分支独有内容，合并动作不改变它                                       |

- 冲突面 **29 个文件全部是 add/add**（相对共同祖先，两侧各自新增同一批文件）。
- 解冲突后 `git write-tree` = `b1768109…`，与 `git rev-parse upstream/main^{tree}` **完全相同**。

⇒ **主分支没有任何集成分支尚未包含的内容。** 判定依据是落地树哈希，不是树差异：
树差异是双向的，既含「集成分支有、主分支没有」，也含「主分支有、集成分支没有」，
后一部分不该被合入动作改变。

⚠️ `merge-resolve.mjs` 把这 29 个文件**全部**判成「并入（人工）」——
它比对的是文件的原始字节，规范化过的字节必然对不上原始内容 ⇒ **该工具在纯排版场景会全量误报**，
它的结论必须与格式归一化取证**交叉验证**后才能采信。

### 2. 修正了格式归一化取证配方的一处缺陷（本轮新发现）

配方要求「用格式归一化把两侧的排版差异吃掉，再比内容」，本轮首次按配方落地就撞上一个**配方本身的缺陷**：

- 归一化插件是 ESM 包，**没有 default 导出**；
- 按「取 default」的写法会把 `undefined` 传给格式化器，报
  `TypeError: Cannot read properties of undefined (reading 'languages')`；
- **正确写法是把模块命名空间整体传入**（命名空间上直接挂着 `parsers` / `printers` / `options`）。

配方还要求「配置要显式传全」（`filepath` 指向仓库内真实路径、插件显式传入、
以及 `.prettierrc` 里的 printWidth / singleQuote / trailingComma 等）——
这两点本轮都验证有效，缺任一项都会让比对结果失去意义。

⚠️ 归一化取证**只对排版差异有效**。若两侧内容真的不同，
归一化后仍会不同（A 计数器下降）⇒ 计数器本身就是「有没有内容差异」的判据。

### 3. 冲突面专项验证全绿

- 冲突面测试（CRM 评价回复白名单 + 关怀提醒 + 消息发送模型）：**3 文件 / 42 例全通过**。
- 白名单代理路由测试（`api-proxy` 并集 + 商品/渠道路由）：**3 文件 / 288 例全通过**。
- 白名单源文件 `apps/web/src/lib/api-proxy.ts` **不在两侧差异清单内**（两侧逐字节相同）
  ⇒ 本轮没有白名单累积型冲突面，不存在两层 alternation 求并的问题。

### 4. 迁移静态预检的命中项全部是存量形态

预检命中的迁移脚本（`ADD COLUMN` 无默认值、`SET NOT NULL`、`ADD CONSTRAINT ... CHECK`）
**没有任何一个在两侧差异清单内** —— 两侧逐字节相同 ⇒ 是仓库既有形态，与本轮合入无关。

### 5. 台账两侧逐字节未变

- 台账行数 **465**，模块 ID **无重复**，摘要行**恰好 1 条**。
- 台账与功能蓝图一致性自检**通过**。
- 台账「已交付」两侧同为 **328** ⇒ 集成分支没有新增交付，主分支也不落后。

## 关键 trade-off / 讨论

- **为什么不开一个「同步进度」的 PR？** 因为它会是**空 PR** ——
  落地树与主分支相同，文件差异为空，只会给主分支添一条什么也没带的提交。
  零变更轮的产出应当放在**核验结论**上，而不是一条无内容的同步提交。
- **为什么不跑本地全量 L2？** 本轮冲突解法**未触碰后端业务代码或迁移脚本**
  （两侧差异清单里没有任何 `backend/` 文件），且定向验证全绿
  ⇒ 按协议走定向级别，全量闸交给远端 CI。
- **本轮的取证成本为什么值得付？** 树差异报 30 个文件、640 处删除，
  单看统计数字会误判成「有大增量要合」⇒ 必须逐文件核对到字节级才能定性。

## 涉及文件

- `apps/web/src/app/api/crm/review-replies-allowlist.test.ts`
- `apps/web/src/app/crm/{care-reminders,review-replies}/page.tsx`
- `apps/web/src/components/account-health/{account-health-model.ts,account-health-workspace.tsx}`
- `apps/web/src/components/crm/{care-reminder,message-send,review-reply,customer-contact,negative-review}-*`
- `docs/sessions/2026-10/2026-10-22-*.md`（12 篇会话记录）
- `docs/product/capability-blueprint/module-progress.md` —— 核验对象，本轮两侧未变

## 后续待办

1. **修 `tools/module-progress/ci-gate.mjs` 的 run 定位**（第 37 轮已记录，仍未修）：
   把 `gh run list --branch main` / `gh run list --commit <sha>` 换成
   「不带过滤器的列表 + 客户端筛选」（基线 = `headBranch === 'main'` 且 `conclusion !== null` 的最新一条；
   PR run = `headSha` 匹配且 `event === 'pull_request'`）。
   **不修则每轮都会以退出码 2 假阻塞。**
2. **给台账合并补一条断言**：`grep -cE '^\| 已交付 '` 必须 == 1，
   且其值 == 集成侧的值（台账一致性自检覆盖不到摘要块）。
3. **主分支与集成分支的排版不对称依然存在**（主分支侧是规范态、集成分支侧是原始态），
   根治要靠「把规范化后的字节回流到集成分支」，超出本任务授权范围 ⇒ **每轮都要重新取证**。

## 关联

- 第 37 轮记录：`docs/sessions/2026-10/2026-10-23-0020-main-sync-round37-batch67-delivery.md`
- 第 32 轮判据修正：`docs/sessions/2026-10/2026-10-22-main-sync-zero-change-merge.md`
- 任务书：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`
