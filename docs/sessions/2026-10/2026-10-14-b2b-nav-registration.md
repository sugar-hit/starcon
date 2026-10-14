# 2026-10-14 · 9.7.1.1 批发订单导航登记改为按约定入 TASK_PAGES

## 一句话

9.7.1.1 直改 `nav-config.ts` 加了一条 `href: '/orders/b2b'` 的顶层导航项，
但二级导航是**从 `IMPLEMENTED_TASK_PAGES` 按 `domain` 派生**的，
`href.slice(1)` 得到 `orders/b2b` 匹配不到任何**域级** domain ⇒ `children` 为空 ⇒
`nav-routes.test.ts` / `task-pages.test.ts` 两红。
改法反过来：撤掉 `nav-config.ts` 的直改，改为在 `TASK_PAGES` 登记
`domain: 'orders'` 的任务页，它自动成为「运营 / 订单中心」的二级项。

## 根因

`packages/ui/src/lib/nav-config.ts` 里二级导航是派生的：

```ts
children: IMPLEMENTED_TASK_PAGES.filter(
  (page) => page.domain === item.href.slice(1) && !isAccountDomain(page.domain),
),
```

约定写在 `task-pages.ts` 的注释里：**只登记任务页，不需要改共享的导航常量文件**。
9.7.1.1 绕过约定直接加顶层项，于是

- 该项的 `children` 需要 `domain === 'orders/b2b'` —— 没有任何任务页是这种**域级** domain；
- `children` 为空 ⇒ `NAV_DESTINATIONS`（从 children 扁平化而来）里没有这个入口；
- 触发两条断言：「每个导航分组都有子页面」与「业务系统分组不出现空子页」。

**车道自带缺陷，不是合并引入的**（车道自己分支单跑同样红）。

## 改法

| 文件                                | 改动                                                                                             |
| ----------------------------------- | ------------------------------------------------------------------------------------------------ |
| `packages/ui/src/lib/nav-config.ts` | 删掉 9.7.1.1 加的那 6 行 `href: '/orders/b2b'` 顶层项，其余一字未动（与 `6b5e3fea^` 逐字节一致） |
| `packages/ui/src/lib/task-pages.ts` | 在 `/orders` 与 `/orders/review` 之间按 href 排序新增一条任务页                                  |

新登记条目：`domain: 'orders'`（域级，与 `/orders`、`/orders/review` 一致）、
`implemented: true`（页面 `apps/web/src/app/orders/b2b/page.tsx` 真实存在，
由 `nav-routes.test.ts` 对照文件系统校验）。
`fields` / `workflow` 取自 9.7.1.1 真实交付的
`b2b-order-workspace.tsx` 表头（订单号 / 客户分级 / 收款方式 / 订单总额 /
已确认应收 / 账期到期日 / 开票需求）。

> 途中踩到一条没写在注释里的约定：`task-pages.test.ts:22` 断言
> `workflow` **恰好 3 步**。最初写了 4 步导致该用例转红，
> 按邻近条目风格压成 3 步（试算阶梯价与起订量 → 发货确认账期应收 → 按账期开票对账），
> **没有改断言**。

**刻意不做**（避免「让测试变绿」的错误方向）：

- 不把 `domain` 写成 `orders/b2b` —— 那会破坏域级约定；
- 不改 `nav-routes.test.ts` / `task-pages.test.ts` 的断言；
- 不顺手 `prettier --write` `nav-config.ts`：该文件的格式告警在**改动前的基线上就存在**
  （已用 `git stash` 复核），属无关重构，不在本车道范围。

## 影响面

- 只动两个文件，无后端迁移、无契约、无 API 变更。
- 导航形态从「顶层分组项」变为「订单中心的二级项」——与仓库其它已交付页面
  （`/warehouse/facilities`、`/inventory/reservation` 等）一致。

## 验证

| 检查                                             | 结果                                                                |
| ------------------------------------------------ | ------------------------------------------------------------------- |
| 改前 `nav-routes.test.ts` + `task-pages.test.ts` | `2 failed \| 15 passed (17)`                                        |
| 改后 同上                                        | `2 passed \| 17 passed (17)`                                        |
| `pnpm test:unit packages/ui`                     | `6 passed (6)` 文件 / `45 passed (45)`                              |
| `pnpm typecheck`                                 | `3 successful, 3 total`                                             |
| `pnpm lint`                                      | `3 successful, 3 total`（`@starcon/web` 的 react-hooks 告警为既有） |
| `prettier --check task-pages.ts`                 | 通过                                                                |

**核心验收点：`/orders/b2b` 真的进了 `NAV_DESTINATIONS`**（不只是测试变绿）。
一次性脚本 `.tmp/verify-b2b-nav.ts`（gitignored）导入 `NAV_DESTINATIONS` 打印：

```
按 domain=orders 解析到的子项:
  - /orders | 全部订单
  - /orders/b2b | 批发订单
NAV_DESTINATIONS 含 /orders/b2b : 是
```

## 遗留

- `nav-config.ts` 的 prettier 格式告警是既有问题，未在本次修（见上）。
- 9.7.1.1 自述的未完成项（9.3.1.1 履约未接入、12.1.1 应收核销 / 12.6.2 发票 /
  12.1.2.2 资金计划未交付）不在本车道范围，页面上以 capabilities 显式报出。
