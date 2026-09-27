# 工作台时间带 v2：迷你地球 + 五大区域轮换

本地项目：`/Users/tommy/Project/starcon`。预览：http://localhost:3000。

## 已完成

- 上一版的"通用地球图标"换成**真实地理表达**：每格右上角一颗 56px 迷你地球，正交投影大陆轮廓 + 经纬网 + 赤道/本初子午线加重 + 城市定位点，球心按城市经纬度定位。
- 轮换格从 2 格扩到 **5 格**：北美 / 欧洲 / 东南亚 / 亚洲其他（不含中国与东南亚）/ 其他（南美、大洋洲、非洲）。北京固定第一格，共 6 格。
- 换城市时球体沿最短经度路径补间 900ms，五格按 160ms 依次启动形成波浪；定位点随投影飞入并放大。
- 换场叠加品牌色渐变横扫（560ms）+ 城市标签与日期行淡入上移（420ms）；时间数字不参与动画。
- 容器顶部加 brand 5% 向下渐变，整条带成为一个区块。
- 冒号呼吸：`<time>` 内把冒号拆成独立 `clock-colon`，2.6s ease-in-out 无限循环，透明度 0.42 ↔ 1；数字不参与动画。

## 陆地数据

- 数据源 Natural Earth 1:110m land（public domain），构建期一次性生成 `packages/ui/src/lib/world-land.ts`：46 个环 / 675 个点 / 9.7KB，Douglas-Peucker eps 1.2°，丢弃 2°² 以下小岛与周长 < 6° 的环。
- 运行时零外部请求，投影全在前端算。重新生成脚本见会话结论（一次性，未入库）。

## 关键 trade-off

- **每格迷你地球 vs 整块共享地球**：用户选了"每格一个迷你地球"。代价是整块不如共享大球沉浸（用户原话要求的"沉浸式"打了折），收益是六格互不遮挡、一眼看到六个市场。补偿手段是让六格朝向不同半球 + 区块顶部渐变，尽量做出"世界切片"的整体感。
- **投影输出坐标以球心为原点，渲染层用 `translate(50 50)` 平移**：`project()` 保持数学纯净可测，所有消费者共用同一坐标系。
- **补间逐帧改 SVG path 的 `d`，不经过 React 渲染**：六颗球同时转时不会每帧触发组件树重渲染；只有 pin 的 transform/opacity 也是 imperative。
- **卡片 key 用槽位序号而不是城市时区**：用 `key={zone}` 时换城市等于整格卸载重挂，迷你地球会在 mount 时直接落到终态，补间完全不播（这个 bug 真实发生过，E2E 已锁死）。文字换场动画改由内层元素自己带 `key={zone}` 触发。

## 过程中修掉的 4 个真实 bug

1. **路径缺 `M`/`L` 命令** —— 投影只吐裸坐标串，浏览器直接丢弃大陆与经纬网，球体只剩渐变圆。
2. **少一次平移** —— 投影以球心为原点输出，却直接画在 viewBox（0 0 100 100）里，全部堆到左上角再被圆盘裁掉。
3. **背面点串进同一条路径** —— 把背面点推到地平线后与正面部分形成自交环，nonzero 缠绕相互抵消，北美大陆中间被填没，只剩左上角一块。改成"只填可见段 + 切口落在地平线"（`filledRing`）。
4. **换城市不转场** —— 卡片 `key={zone}` 导致整格重挂，补间被跳过。

另外生成脚本里 Douglas-Peucker 的垂距函数把三个点角色写反（测的是弦端点而非待测点），简化结果是空数组；已修正。

## 结构

- `world-clock.ts`：`WorldCity`（含 lat/lon）、`HOME_CITY`、`REGION_POOLS`（5 个区域池）、`rotationCities` / `nextRotationCursor` / `visibleCities` / `clockParts`。
- `geo.ts`：`project`（正交投影 + 可选推至地平线）、`shortestLonDelta`、`angularDistance`、`greatArc`、`graticulePaths`、`landPaths`。全部纯函数。
- `mini-globe.tsx`：`MiniGlobe`，56px 球体，rAF 补间，imperative 更新 path。
- `world-clock-strip.tsx`：六格布局、1s 读秒、波浪错峰、渐变换场。
- `world-land.ts`：生成的陆地轮廓常量。

## 本轮修改文件

- `packages/ui/src/lib/world-land.ts`（新增，生成物）
- `packages/ui/src/lib/geo.ts`、`geo.test.ts`（新增）
- `packages/ui/src/lib/world-clock.ts`、`world-clock.test.ts`（重写）
- `packages/ui/src/lib/use-city-rotation.ts`（重写）
- `packages/ui/src/components/common/mini-globe.tsx`（新增）
- `packages/ui/src/components/common/world-clock-strip.tsx`（重写）
- `packages/ui/src/components/common/city-globe.tsx`（删除，被 mini-globe 取代）
- `packages/ui/src/components/index.ts`、`packages/ui/src/styles/tokens.css`
- `tests/e2e/world-clock.spec.ts`（重写，7 项）
- `docs/design/visual-spec.md`（V2.1.2 重写）

## 验证结果

- `pnpm test` 21/21（world-clock 10 + geo 10 + task-pages 1）。
- `pnpm exec playwright test tests/e2e/world-clock.spec.ts` 6/6：地球贴角/含大陆与经纬网/六格朝向互不相同/五格轮换北京不动/换场后 1.6s 内轮廓多帧变化/六格始终不撞城/动效时长与读秒不参与动画。
- `scroll-shell` + `navigation-v2` + `navigation-regression` 18/18，含"六格时钟基线一致"与 320–1920px 工作台回归。
- `pnpm lint`、`pnpm typecheck` 全绿。
- 浏览器实测：1440px 六格基线一致；390px 两列、长城市名换行不截断。

## 后续

- 陆地数据是构建期生成的静态常量；若要更高精度（如岛屿细节）只需调 eps 重生成。
- 区域池目前写死在 `REGION_POOLS`；后续接"按店铺所在市场排序"时替换池内容即可，hook 与组件无需改动。
- 若之后改回共享大地球，`geo.ts` 与 `world-land.ts` 可直接复用，投影层不用重写。
