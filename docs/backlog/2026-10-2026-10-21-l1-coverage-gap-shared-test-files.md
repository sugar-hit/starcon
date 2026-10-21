# L1 收窄到「本模块」之后：共享文件的**兄弟测试文件**落在过滤范围外 ⇒ 跨模块回归漏到 L2 才爆（2026-10-21 取证）

**优先级：P1。协议把 L1 收窄到「只跑本模块的测试」是对的（省 75% 机器时间），但收窄后有一个系统性盲区。**

## 现象

2026-10-20 23:00 tick 的 L2 第二轮，前端测试阶段红了**唯一 1 条**：

```
FAIL apps/web/src/app/pricing/cost/cost-composition-page.test.ts > 分区块约定 >
     未交付的兄弟分区写明「未交付」并说明口径差异
AssertionError: expected true to be false
 ❯ apps/web/src/app/pricing/cost/cost-composition-page.test.ts:57:31
 Test Files  1 failed | 327 passed (328)
      Tests  1 failed | 5152 passed (5153)
```

**根因**：5.2.1.2「到岸成本」这条车道把 `cost-composition-model.ts` 里
`COST_SECTIONS` 的 5.2.1.2 分区 `delivered` 翻成了 `true`（它确实已交付），
但 **5.2.1.1 交付时留下的** `apps/web/src/app/pricing/cost/cost-composition-page.test.ts`
里那条用例**硬编码**「5.2.1.2 是未交付分区」⇒ 断言必红。

本车道当时**更新了兄弟测试文件** `apps/web/src/components/pricing/cost-composition-model.test.ts`
（改成「5.2.1.2 到岸成本已交付」），**漏掉了 `apps/web/src/app/pricing/cost/cost-composition-page.test.ts`**。

**为什么会漏**：本车道 L1 的前端过滤范围是

```
pnpm vitest run apps/web/src/components/pricing apps/web/src/app/api
```

而漏掉的文件在 **`apps/web/src/app/pricing/cost/`** ——
**既不在 `components/` 下，也不在 `app/api/` 下** ⇒ **L1 根本没跑到它**。

⇒ 本仓的前端测试按「目录」分布，**同一功能点的测试文件可能散在 `components/` 与 `app/<路由>/` 两处**，
而「按模块收窄」的过滤很容易只覆盖其中一处。

## 为什么这条不能靠「扩大 L1 范围」解决

协议明令 L1 只跑本模块自己的测试（整服务测试里 95% 与你无关，同批 N 条同服务车道各跑一遍就是 N 倍重复）。
把 L1 扩大到「本模块涉及的所有目录」是可行的，但**边界比想象的模糊**：
「本模块」既包括它自己新增的文件，也包括**它改动到的共享文件所在的所有测试目录**。

## 建议的两条（**待决**）

1. **任务书里加一条硬性自检**（**成本最低，建议立刻采纳**）：
   > 改了任何**共享**文件（如 `components/pricing/*-model.ts`、共享 `COST_SECTIONS` 之类的分区数组）时，
   > 必须 `grep -rl "<该共享符号名>" apps/web/src --include='*.test.ts'`
   > 找出**所有**引用它的测试文件，并把它们全部纳入 L1 过滤范围。
   > 共享文件的测试散落在别的目录时，**特别容易漏**。
2. **把「共享文件的符号 → 测试文件清单」变成可脚本化的检查**：
   例如在 `tools/module-progress/` 加一个 `shared-symbol-tests.mjs`，
   给定改动过的共享文件，列出所有引用它的测试文件，产出建议的 L1 过滤范围。
   （与「并入冲突」那条 backlog 是同一个方向：**共享文件的依赖要么用驱动器求并，要么用脚本列全**，靠人记是记不住的。）

## 本轮处置

已派一条**只修这一个测试文件**的修复车道（`fix/5-2-1-2-cost-page-assert`），
并把那条用例改写成**数据驱动的不变量**（不再硬编码「哪个兄弟模块还没交付」），
这样 5.2.1.3「成本分摊」下一轮并入时不会再红一次。
