# 工作台时间带：城市标记 + 末两格轮换

本地项目：`/Users/tommy/Project/starcon`。预览：http://localhost:3000。

## 已完成

- 工作台六格时间带每格右上角新增城市标记：18px 圆角方块，`brand/10` 底 + `brand` 细网格地球线稿 + `brand/15` 内描边。取参考图的球体网格观感，按本项目令牌压低对比，不引入 emoji 国旗或图片资源。
- 标记与城市标签同行、贴右上角；标签在窄屏可换行，不截断国家名。
- 末两格改为自动轮换全球主要城市：14 城池（池首为伊斯坦布尔、罗马），每 6 秒一次换场，走完一整圈 14×6s。
- 换场动效 420ms 淡入 + 3px 上移，只作用于城市标签与日期/偏移行；时间数字不参与动画，读秒即时准确。首屏不播入场动效。
- 时间带从首页内联代码抽成 `@starcon/ui` 的 `WorldClockStrip`，首页只留标题行与该组件。

## 关键 trade-off：轮换用共享游标，不用错峰

最初实现是每格一个独立游标、两格错开 3 秒切换，让"轮换感"更有节奏。实测发现**两格会短暂显示同一城市**：两个游标同速 +1、相位差半个周期时，`cursor_0 - cursor_1` 会在 0/1 之间摆动，有 3 秒窗口两格同城（截图复现：两格同时显示"意大利 罗马"）。

改为**两格共用一个游标、同一次换场**，取池中相邻两个城市：任意时刻六格城市必然互不重复，代码也少一半。单测 `never lets the two rotating slots and the fixed cities collide` 与 E2E `all six clocks stay distinct while rotating`（连续 14 秒采样）分别锁住纯逻辑和运行时行为。

代价：两格每 6 秒同时换，不再错峰。判断是"一次协调的换场"比"两格先后跳"更稳，也更符合数据带的克制调性。

## 结构

- `world-clock.ts`：`HOME_CITY` / `PRIMARY_CITIES`（纽约·伦敦·东京，固定）/ `ROTATING_CITY_POOL`（14 城）/ `rotationCities(cursor, slots?)` / `nextRotationCursor` / `visibleCities` / `clockParts`。
- `useCityRotation(intervalMs?)`：单一 `setInterval` 推进共享游标，附带 `switches` 计数供 UI 判断是否播换场动效。
- `CityGlobe`：纯 SVG 地球标记，`aria-hidden`，装饰性。
- `WorldClockStrip`：六格渲染 + 1s 读秒 + 轮换编排；卡片带 `data-rotating` / `data-switches` / `data-clock-label` 供 E2E 观测。

## 本轮修改文件

所有相对路径均以 `/Users/tommy/Project/starcon` 为根：

- `packages/ui/src/lib/world-clock.ts`：拆分固定城市与轮换池，新增游标纯函数。
- `packages/ui/src/lib/use-city-rotation.ts`：轮换 hook（新增）。
- `packages/ui/src/components/common/city-globe.tsx`：城市标记（新增）。
- `packages/ui/src/components/common/world-clock-strip.tsx`：时间带组件（新增）。
- `packages/ui/src/styles/tokens.css`：`clock-swap-in` 关键帧与工具类。
- `packages/ui/src/components/index.ts`：导出新组件与 hook。
- `packages/ui/src/lib/world-clock.test.ts`：8 项单测，覆盖时区、池去重、游标循环、任意时刻不撞城。
- `apps/web/src/app/page.tsx`：改用 `WorldClockStrip`。
- `tests/e2e/world-clock.spec.ts`：4 项 E2E（新增）。
- `docs/design/visual-spec.md`：V2.1.2 城市标记与轮换时间带。

## 验证结果

- `pnpm test` 9/9 通过（world-clock 8 + task-pages 1）。
- `pnpm exec playwright test tests/e2e/world-clock.spec.ts` 4/4 通过；`scroll-shell` 与 `navigation-v2` 中既有六格基线断言保持通过。
- `pnpm lint`、`pnpm typecheck` 通过。
- 浏览器实测：1440px 六格基线 y 一致；末两格 6s 换城、换场瞬间标签与日期行半透明而时间数字清晰；390px 两列布局标签不截断。
- `prefers-reduced-motion` 由 tokens.css 全局规则关闭动画，内容照常切换。

## 后续

- 轮换池目前是静态常量，后续接真实配置（按店铺所在市场排序）时替换 `ROTATING_CITY_POOL` 即可，hook 与组件无需改动。
- 标记目前统一为地球线稿；若后续要按国家显示国旗，需要先定国旗资源与跨平台一致方案（emoji 在不同系统渲染差异大）。
