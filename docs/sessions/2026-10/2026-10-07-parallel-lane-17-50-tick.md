# 17:50 tick —— 一次「提交了旧暂存区」的自我事故 + merge-ready 中途换车道

本轮回收 17.2.2.2、17.2.1.2、17.2.2.3 三条，交付数 72 → 75。调度闸已释放，工作区干净。

## 事故一：typecheck 过了，但集成头编译不过

我在 resolve `identity-workspace.tsx` 时改了两轮：

1. 改 WorkspaceKind（错的版本）→ `git add` 暂存
2. typecheck 失败 → 改回正确版本（**只在工作区，没重新 add**）
3. typecheck 通过 → 跑测试 → `git commit`

第 3 步的 `git commit` **不带 `-a`**，提交的是**暂存区里第 1 步那份错的**。
于是集成头 `e9cc004` 带着 `WorkspaceKind = ... | 'hierarchy'`，
`AdministrationPanel` 的 `kind` 只吃 `AdministrationKind` —— **TS2322，集成头编译不过**。

**规则**：resolve 冲突期间只要在验证之后又动过文件，commit 前必须重新 `git add`；
或者干脆用 `git commit -a`。更稳的做法是**验证通过后立刻 `git status --porcelain`
确认工作区干净** —— 那一行本可以在我提交前就暴露问题。

（发现后已用 `git commit --amend` 修正，未 push，历史无残留。）

## 事故二：以为在并 A，其实一直在并 B

我核对完台账、准备并 17.2.2.3，但**没有重新确认 `MERGE_HEAD`**。
而 17.2.1.2 的 worker 恰好在这几分钟里调用了 `land`，于是 `merge-ready`
把我那条 `ready` 放进队列后，先处理了 17.2.1.2，冲突也全是 17.2.1.2 的。

**信号其实很明显**：解出来的白名单分支是 `department-leaders`（17.2.1.2 的东西），
不是 `offboarding`（17.2.2.3 的东西）。我是靠「这个分支名不属于我要并的车道」发现的。

**规则**：`merge-ready` 之后、动手解冲突之前，先 `git rev-parse MERGE_HEAD`，
与 `lanes.json` 里目标车道的 `headSha` 对一遍。`merge-ready` 会把**当时所有** ready 车道一起处理，
不止你打算并的那一条。

## identity-workspace 的 filter：连续三次被重写

`17.2.2.2`（成员开通）、`17.2.1.2`（部门负责人）、`17.2.2.3`（离职与停用）
三条车道都从同一个陈旧基线分叉，都**重写了同一段内联 filter**，
每条都只带自己那一个权限码分支，看不见已并入的两条：

| 车道     | 它加的             | 它漏掉的                       |
| -------- | ------------------ | ------------------------------ |
| 17.2.2.2 | onboarding         | hierarchy                      |
| 17.2.1.2 | department-leaders | hierarchy、onboarding          |
| 17.2.2.3 | offboarding        | department-leaders、onboarding |

**最后一次取 `visible()` helper 作为唯一入口**并把 offboarding 补进去，
现在 tenants / audit / hierarchy / department-leaders / offboarding / onboarding
六条集中在一处。下次再有人改这里，应该**只改 `visible()`**，不要再写内联 filter。

配套的类型纪律：`WorkspaceKind` **不加** 'hierarchy' / 'department-leaders' / 'offboarding' ——
这三个都只是导航目的地（点进去跳独立页面），不是 `AdministrationPanel` 的 kind。
加进去就是 TS2322。

## 求并脚本的两个固化改动

`.tmp/analyse3way.js` / `.tmp/resolve-allowlist.js` / `.tmp/resolve-region.js` 三个脚本已落地，
关键改动是 **BASE_FILE 环境变量**：引入 merge base 才能分辨

- 「车道改宽了这条分支」→ 取 theirs
- 「车道压根没碰这条，只是 ours 改宽了」→ **保留 ours**

没有 base 时两者都表现为「theirs 有、ours 没有」，按首词配对会误替换
（`legal-entities/{id}` 与 `legal-entities/references` 首词相同），
产物仍是合法正则、**不报任何错**。这个坑我上一个 tick 写进文档了，本轮还是踩了 ——
说明该修的是工具，光写文档没用。

## 验证习惯

这轮探针又两次把同一个路径同时列进「必须不变」和「新增放行」两张表，
导致断言自己打自己。最终改成**单值断言**：
「范围外变化数 == 0 且泄漏数 == 0」，并对关键关键词做**存在性硬断言**
（offboarding / department-leaders / hierarchy-authorizations / onboarding /
accounting-books / legal-entities / audit / tenants 逐个检查）。
「保留 ours 写错」在语法上完全合法，只有存在性断言能挡住。

另外：白名单要和**控制器真实端点表**逐条比对，不要凭印象写探针 ——
我一度以为 `/reclaim` 没放行是漏洞，实际路由叫 `/reclaim-items/{id}/reclaim`。
