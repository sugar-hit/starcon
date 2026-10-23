# 2026-10-23 同批车道并发改「共享文件」⇒ 下一轮并入必然冲突

## 症状

2026-10-23 21:00 那轮（batch-78）派发的三条 finance 车道
（`12.1.1.2 应收账龄` / `12.1.2.1 应付单管理` / `12.2.1.1 收款单`）**同时**改了：

- `apps/web/src/lib/api-proxy.ts` —— 三条车道都按 12.1.1.1 的做法把新条目插在**数组末尾**同一个位置
- `apps/web/src/app/finance/page.tsx` —— 两条车道都改了同一段的 javadoc 与 `return` 块

⇒ 下一轮 `merge-ready` 走普通 `git merge --no-ff` 时**必然冲突**，
该车道会被标 `conflict`、整个模块交付被卡住一轮（`merge-ready` 冲突时会 `git merge --abort` 并把车道挪出 ready）。

## 根因：两个文件都不在 merge-union 驱动器的覆盖范围内

`.gitattributes` 只给这两个范围配了 `merge=contract-union` 驱动：

```
packages/api-contracts/src/**/*.ts          merge=contract-union
packages/api-contracts/src/*.openapi.json    merge=contract-union
docs/product/capability-blueprint/module-progress.md merge=contract-union
```

⇒ `apps/web/src/lib/api-proxy.ts` 与 `apps/web/src/app/finance/page.tsx` **走普通 git merge**，
没有自动求并能力。

⚠️ **不要给 `api-proxy.ts` 加 `merge=contract-union`**：那个驱动的非 JSON 兜底路径是
「按整行去重求并」，而 `api-proxy.ts` 里有大量**重复的结构行**（`  {` / `  },` / `    ],` 等），
整行去重会把这些行折叠掉、**产出语法坏掉的 .ts 文件**。比冲突更糟。

## 已确立的约定（本仓自己的先例，不是新规矩）

### 约定一：`api-proxy.ts` 的 `services[]` —— 同批多车道必须各占一个相距 600 行以上的锚点

`api-proxy.ts` 里 1.1.1.2 那条车道早就把规矩写在注释里了：

> 「插入锚点选在**数组最开头**（本文件第一个元素之前）… 同批其它车道的插入点是
> 「`prefix: 'identity/'` 之前」「`prefix: 'warehouse/'` 之前」与「数组末尾」；
> 本车道落在这里，**与它们的插入点相距 600 行以上，并入时 git 能干净求并**。」

⇒ **锚点必须按车道错开**。可用锚点（基线行号，随文件演进会漂移，按 prefix 认不要按行号写死）：

| 锚点 | 认法 |
| --- | --- |
| 数组最开头 | 第一个元素之前 |
| identity 组之前 | **第一个** `prefix: 'identity/',` 所在条目的 `{` 之前 |
| warehouse 组之前 | **第一个** `prefix: 'warehouse/',` 所在条目的 `{` 之前 |
| 数组末尾 | 最后一个元素之后（`];` 之前） |

⚠️ `identity/` 与 `warehouse/` 在文件里**各出现多次**，认准**第一个**。

⚠️ **顺序对同 prefix 的条目不影响正确性**：`resolve()` 对同一个 `prefix` 只返回 `{base: FINANCE_URL}`，
转发给后端的是**完整 path**（`${base}/${path}`），不是切片。
所以只要各条目的**首 token 互不相同、正则两两不相交**，它们在数组里的先后顺序无所谓。
（反例：两个条目 prefix 不同但 path 形状重叠时，先匹配的那个会**静默吃掉**另一个的路由 —— 这才是真正危险的顺序问题。）

### 约定二：模块页面靠 URL 直接可达，**不要去改别的模块的页面**

同服务多模块在本仓是**各走各的路由、模块页面各自独立**：

- `apps/web/src/app/warehouse/` 下有 12+ 个模块页面（`receipts` / `pick-tasks` / `outbound` / `parcels` …），
  **全部只靠 URL 直接访问**；`warehouse/page.tsx` 只是个 `DomainPlaceholder`，**不做导航**。
- 同批的 11.2.3.2 交付 `/aftersales/return-dispositions` 时，也**没有**去改
  `apps/web/src/app/aftersales/page.tsx`（那是 11.1 售后单模块的页面）。

⇒ 「模块页面只靠 URL 可达」**是既定形态，不是缺陷**。
新模块建自己的路由目录（如 `apps/web/src/app/finance/aging/`）就够了。

⚠️ 代价要说清楚：**页面在 UI 上不可达**（只能手敲 URL）。
若要解决，正确做法是**建一个该服务的独立索引页**（新文件，不动任何模块的页面），
而不是往别人的模块页面里塞链接。

## 本轮已做的处置（2026-10-23 21:1x）

- `12.2.1.1 收款单`（已落地）：撤回对 `finance/page.tsx` 的跨模块改动；`api-proxy` 条目**留在数组末尾**。
- `12.1.2.1 应付单管理`（在途）：撤回对 `finance/page.tsx` 的跨模块改动；
  `api-proxy` 条目移到「**第一个 `prefix: 'warehouse/'` 之前**」。
- `12.1.1.2 应收账龄`（在途）：`api-proxy` 条目移到「**第一个 `prefix: 'identity/'` 之前**」；
  不动 `finance/page.tsx`。

## 派发侧要改的东西（**待办**）

`tools/module-progress/lane.mjs` 的 `plan` 现在给所有车道**同一句**「锚点固定在 services[] 最末尾」的指引
（这是 12.1.1.1 当时的做法，在只有一条 finance 车道时没问题，多车道就撞）。
⇒ **待办**：让 `plan` 或派发模板按车道**自动分配错开的锚点**，而不是让每个 worker 自由发挥。
否则这个坑会每批复发一次。

## 另一条同源纪律

- **跨车道破坏形状不止「表结构」，还有「权限模型」**（见 batch-78 会话日志）：
  11.2.3.2 授了 `GRANT UPDATE(instruction_status)` + 放宽 CHECK + 加守卫触发器，
  把 11.2.3.1 钉的 `permission denied` 断言打失效了 —— 而静态预检的三种 grep 形状**抓不到**这类破坏。
- 同理，**共享文件的「写入形状」也是跨车道破坏面**：
  往共享文件里插内容时，要考虑别的车道会不会同时往同一处插。
