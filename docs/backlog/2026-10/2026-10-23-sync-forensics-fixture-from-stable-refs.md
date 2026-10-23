# 同步轮取证夹具必须从稳定 ref 导出（+ 非空校验 + 阴性对照落点）

- 创建: 2026-10-23
- Owner: TBD（同步轮 / 取证工具维护者）
- 优先级: P1
- 关联: `docs/sessions/2026-10/2026-10-23-1535-main-sync-round52.md`、
  `tools/module-progress/SYNC-MAIN.md`、`tools/module-progress/merge-resolve.mjs`

## 背景

第 52 轮 main→集成头同步取证时，**14:02:56 有别的 actor 在同一 worktree 执行 `git reset --hard`**，
合并冲突态被撤销、`MERGE_HEAD` 被清。

本轮的取证夹具是用 `git show :2:<path>` / `git show :3:<path>`（从**冲突态的 index** 取两侧 blob）
导出的 —— index 一被 reset，这两个 stage 就不存在了，导出结果**变成 0 字节文件**，
而当轮的比较逻辑只看「内容是否相同」⇒ **两个空文件互相「一致」**，会得出假的「零差异」结论。

同一个脚本族里还踩到第二个坑：第 51 轮 `api-proxy.ts` 的**阴性对照**把篡改点放在了一个**注释**里，
而比较对象是 `pattern:` 正则字面量 ⇒ 篡改根本没进入被比较的实体，对照**没有触发**，
等于「对照通过」是假的。

⇒ 两个坑的共性：**取证脚本缺少「输入有效性」与「对照有效性」的自检**。

## 待办内容

- [ ] 同步轮的取证夹具一律从**稳定 ref** 导出（`git show HEAD:<path>` / `git show origin/main:<path>`），
      不再从冲突态 index（stage 2/3）导出。
- [ ] 导出后立即做**非空字节校验**：文件数正确 + 总字节数 > 0 + 单文件非 0 字节，否则**中止取证**。
- [ ] 阴性对照的篡改点必须落在**被比较的实体内部**（例如改 `pattern:` 正则字面量里的 token），
      且对照脚本必须**断言「篡改后判为不等」**（不等才算对照通过）。
- [ ] 把「导出 + 校验 + 对照」抽成一个可复用脚本（例如
      `tools/module-progress/sync-forensics.mjs`），避免每轮各写各的、各踩各的坑。

## 影响面

- 同步轮（`tools/module-progress/SYNC-MAIN.md`）：取证结论的可靠性直接决定「能不能机械取边」的判断。
- 裁决器（`tools/module-progress/merge-resolve.mjs`）：本轮实测退出码 1（判「需人工并入」），
  另有已知缺陷 `docs/backlog/2026-10-23-merge-resolve-revparse-stdout-echo-mislabels-add-add.md`
  （在 main 侧，尚未并入集成头）。
- 人工拍板环节：若取证是假的，「取 ours 零损失」的建议就会误导人工。

## 完成标准

- 任意一次同步轮取证都能打印「夹具来源 ref + 文件数 + 总字节数 + 阴性对照是否触发」四项，
  且这四项由脚本自己断言，不靠人眼。
- 人为把某个夹具文件清空 / 把篡改点挪到注释里时，脚本**当场失败**而不是给出「一致」。
