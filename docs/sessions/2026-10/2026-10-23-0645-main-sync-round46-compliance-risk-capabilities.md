# 2026-10-23: 集成分支并入 main（第 46 轮）—— 合规风控 4 项能力交付

## 摘要

把 `integration/module-dev` 通过 Pull Request 合入 `main`，本轮带来 **16 合规与风控系统**的
4 项新交付：检测与预警（16.4.1.2）、黑名单（16.5.1.1）、登录与访问安全（16.5.2.1）、
PII 管理（16.6.1.1）。合并形态为「真合并 + 39 个冲突」，全部冲突经独立取证后取集成分支侧，
合并结果经校验等于「集成分支内容 + 仅 main 独有的 13 篇文档」，两侧内容零丢失。

## 决策 / 结论

1. **39 个冲突全部取集成分支侧**，依据不是判定器的标签，而是两条独立取证：
   - `git ls-tree` 确认这 39 个文件在 merge-base 里**不存在**（add/add 属实，
     判定器把 `content` 与「必须人工并入」是 `git rev-parse` 回显 bug 造成的误报）；
   - **内容超集比对**：22 个 TS/TSX 用 AST 指纹（归一化 Tailwind 类名顺序）、
     16 篇会话纪要用规范化文本多重集，**main 独有内容均为 0** ⇒ 取集成分支侧不丢任何东西。
2. **白名单文件取集成分支侧**：工具的三件套自检（集合包含 / 有序子序列 / 零删除）全成立，
   条目 164 → 168；另用真 proxy 断言 HTTP 状态码（放行 = 转发、未知路径仍 404）双向验证。
3. **台账重新生成**：合并驱动的行级兜底把 main 的旧汇总块（已交付 336）留下了，
   与 465 条模块行不自洽。跑一次 `sync.mjs` 重新生成后，台账与源分支**逐字节相同**、
   `sync.mjs --check` 转绿 ⇒ 重新生成无损。
4. **55 个文件的格式债在合并结果里格式化掉**，并逐个证明语义无损（AST 指纹 / 规范化文本）。
   本次合入**未引入任何新的 Prettier 失败**（新增集合为空）。
5. **契约产物与台账保持与源分支逐字节相同**，一个字节都没动。
6. **未跑本地后端全量 L2**：合并结果的后端树与集成分支**逐字节相同**，
   且冲突解决未触及任何后端文件；本地后端构建需要 Docker + PostgreSQL 跑 jOOQ codegen，
   属发热高峰，全量闸由 CI 的远端 runner 承担。

## 涉及文件

- 冲突解法涉及：22 个 `apps/web/src/components/**` 与 `apps/web/src/app/**` 源文件、
  16 篇 `docs/sessions/2026-10/*.md`、`apps/web/src/lib/api-proxy.ts`（白名单）。
- 后端新增（与源分支一致，未冲突）：`backend/services/channel/src/main/java/com/starcon/channel/`
  下的 AccessSecurity / BuyerBlacklist / IpDetection / PiiAccess 四组控制器、模型、规则与端口。
- 新增迁移：`backend/services/channel/src/main/resources/db/migration/`
  `V317__channel_ip_detection.sql`、`V318__channel_buyer_blacklist.sql`、
  `V319__channel_access_security.sql`、`V320__channel_pii_access.sql`。
- 契约：`packages/api-contracts/src/contracts/channel.ts`、
  `packages/api-contracts/src/channel.openapi.json`（与源分支逐字节相同）。
- 台账：`docs/product/capability-blueprint/module-progress.md`（465 行，重新生成后与源分支逐字节相同）。
- 本轮补充的结论：
  `docs/backlog/2026-10-23-merge-resolve-revparse-stdout-echo-mislabels-add-add.md`。

## 关键 trade-off / 讨论

- **「取一边」的依据换成内容超集，而不是 blob 是否出现在历史里**。
  在 squash 合入的仓库里，main 侧的文件是上一次 squash 合并的解冲突产物，
  天然不在集成分支的历史里 —— 用它当判据会把「线性演进」误判成「真分叉」。
  判据换成语义内容比对后，结论既可证又不依赖 git 历史形状。
- **格式化 vs 保持与源分支逐字节相同**。前端源码可以格式化（用 AST 指纹证明无损即可）；
  契约产物与台账必须与源分支逐字节相同（CI 用 `contracts:generate` + `git diff --exit-code` 校验）。
  本轮因此让合并结果与源分支在前端源码上有 55 个文件的格式差异，这是**有证据的偏离**，不是静默删内容。
- **markdown 的「内容丢失」判定要归一化表格竖线前后的空白**，否则 Prettier 给单元格补的对齐空格
  会被读成丢行（本轮踩过一次，已归一化后复核为 0 丢失）。

## 后续待办

- `merge-resolve.mjs` 的 `blobOf` 改用 `git ls-tree` 并断言返回值是 40 位 hash；
  `classifyFile()` 的 `kind` 判定加自检；判定器吞掉 stderr 噪声。
  详见 `docs/backlog/2026-10-23-merge-resolve-revparse-stdout-echo-mislabels-add-add.md` 的 L-01 ~ L-04。
- 协议 §4.2 补一句：判定器的类型标签只作参考，最终解法以「归一化取证 + AST 指纹」为准。
- 集成分支持续制造 Prettier 格式债（车道写完不跑格式化），需在流水线侧收口。
  详见 `docs/backlog/2026-10-23-integration-branch-formatting-debt-manufactures-sync-conflicts.md`。

## 关联

- `docs/backlog/2026-10-23-merge-resolve-revparse-stdout-echo-mislabels-add-add.md`
- `docs/backlog/2026-10-23-integration-branch-formatting-debt-manufactures-sync-conflicts.md`
- `docs/sessions/2026-10/2026-10-23-batch-70-ip-detection-blacklist-access-security-pii.md` —— 本轮 4 项交付的功能侧记录
- `docs/product/capability-blueprint/module-progress.md` —— 功能进度台账
