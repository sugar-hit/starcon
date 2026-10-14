# 2026-09-26 WorkspaceTabs URL 严格同步 — tab 闪一下消失 bug

## 问题

点侧栏 "定价中心" 时,新 tab 出现一瞬间就被冲掉,只剩 `商品中心` 一个 tab。修完发现还有两个相关 bug:

1. 直接访问 `/orders` (地址栏粘贴 / 刷新) 后,tab 栏 active 与 URL 不一致
2. 关闭 active tab 后,被关闭的 tab 立刻又被自动加回去 (race condition)

## 根因

`WorkspaceTabsProvider` 被每个 `page.tsx` 用 `<AppShell>` 包装,所以路由切换时 Provider 整个被卸载 → useState 重置为 `[]` → 异步读 localStorage 时把刚 `openTab` 的状态冲掉。

附带问题:`WorkspaceTabsImpl` 的 URL→tab 同步 effect 只依赖 `[pathname]`,hydration 后 tabs 变了不会重跑,所以刷新后 active 与 URL 错位。

附带问题:`closeTab` 的执行顺序 `setTabs(filter) → setActiveTabId(null) → router.replace('/')`,router.replace 是异步的,React 提交 state 后 pathname 还没变,此时同步 effect 看 pathname=/orders + tabs=[] 又把 tab 加回去。

## 修复

### 1. AppShell 提到 RootLayout

```diff
- // 每个 page.tsx 都包一层
- <AppShell title="..." description="...">
-   {content}
- </AppShell>

+ // apps/web/src/app/layout.tsx
+ <AppShell>{children}</AppShell>
```

Provider 跨路由不再重建,tab 状态在内存中持续。

### 2. AppShell 自动推导 PageHeader

从 nav-config 推导,description 优先用 `domain-spec.purpose`,fallback 到 `feature.description`。特殊路径:`/styleguide` 硬编码,未匹配路径 (404) 不渲染。

```ts
const pageHeaderMeta = React.useMemo(() => {
  if (pathname === '/styleguide') return { title: '设计系统', description: '...' };
  const feature = resolveFeatureFromPath(pathname, currentSystem);
  if (!feature) return null;
  const spec = getDomainSpec(feature.href.replace(/^\//, ''));
  return { title: feature.label, description: spec?.purpose ?? feature.description ?? '' };
}, [pathname, currentSystem]);
```

### 3. URL 同步逻辑加 prevPathRef 守门

```ts
const prevPathRef = React.useRef<string | null>(null);

React.useEffect(() => {
  if (!hydrated) return;
  if (prevPathRef.current === pathname) return; // ← 关键
  prevPathRef.current = pathname;
  // ...openTab / activateTab...
}, [pathname, tabs, activeTabId, hydrated]);
```

`hydrated` 来自 context,保证 hydration 完成后才跑同步。`prevPathRef` 防止 `closeTab` 后 pathname 还没变时误重开 tab。

## 验证

四个场景全部通过:

1. **Nav click**: `/products` → 点 "定价中心" → tabs `[商品中心, 定价中心]`,2 秒后仍稳定 (原 bug)
2. **Direct nav + refresh**: 直接 `/orders` → 自动加 tab;刷新后 tab 保留
3. **Close active**: 关闭 `/orders` → URL `/`,tabs `[]`,localStorage 持久化为空
4. **Keep-alive**: scroll=200 在 tabs 间切换保留

## 文件清单

- `packages/ui/src/components/layout/app-shell.tsx` — RootLayout 级别,自动推导 PageHeader
- `packages/ui/src/components/layout/workspace-tabs.tsx` — 加 prevPathRef 守门
- `packages/ui/src/lib/workspace-tabs.tsx` — 暴露 `hydrated` 状态
- `apps/web/src/app/layout.tsx` — 单一 AppShell 实例
- 20 个 `apps/web/src/app/**/page.tsx` — 移除 AppShell 包装,内容直渲

## 后续

- (无)
