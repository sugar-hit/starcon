'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { Bell, Menu, Search, Sparkles, X } from 'lucide-react';
import { cn } from '../../lib/utils';
import {
  SYSTEMS,
  TOP_LEVEL_ITEMS,
  getSystem,
  resolveSystemFromPath,
  type SystemId,
  type SystemDef,
  type NavItem,
} from '../../lib/nav-config';
import { Logo } from '../common/logo';
import { Avatar } from '../common/avatar';
import { Segmented } from '../common/segmented';
import { PageHeader } from '../common/page-header';
import { Sheet, SheetContent, SheetTrigger, SheetClose } from '../ui/sheet';

/**
 * 当前选中 system（默认由 pathname 推断，可由 props 覆盖）
 */
function useCurrentSystem(override?: SystemId): SystemDef {
  const pathname = usePathname();
  const inferred = resolveSystemFromPath(pathname);
  return override ? getSystem(override) : inferred;
}

/**
 * 通用导航项渲染 — 用于左侧 + 移动端抽屉
 */
function NavItemLink({
  item,
  active,
  onNavigate,
}: {
  item: NavItem;
  active: boolean;
  onNavigate?: () => void;
}) {
  const Icon = item.icon;
  return (
    <Link
      href={item.href}
      onClick={onNavigate}
      className={cn(
        'group flex h-7 items-center gap-2 rounded-md px-2 text-copy-12 transition-colors duration-base ease-out-expo',
        active
          ? 'bg-sidebar-accent font-medium text-foreground'
          : 'text-muted-foreground hover:bg-sidebar-accent/50 hover:text-foreground'
      )}
      title={item.description}
    >
      <Icon
        className={cn(
          'h-3.5 w-3.5 shrink-0 transition-opacity',
          active
            ? 'text-sidebar-primary opacity-100'
            : 'opacity-70 group-hover:opacity-100'
        )}
      />
      {item.label}
    </Link>
  );
}

/**
 * 左侧导航列表
 *   - 顶部固定区：工作台 + AI Copilot（不属于任何 system）
 *   - 分隔后：当前 system 的 domain 列表
 */
function NavList({ system, onNavigate }: { system: SystemDef; onNavigate?: () => void }) {
  const pathname = usePathname();

  return (
    <nav className="flex flex-1 flex-col gap-4 px-2 py-3">
      {/* 顶层固定项 — 工作台 / AI Copilot */}
      <ul className="space-y-1">
        {TOP_LEVEL_ITEMS.map((item) => (
          <li key={item.href}>
            <NavItemLink
              item={item}
              active={pathname === item.href || pathname.startsWith(item.href + '/')}
              onNavigate={onNavigate}
            />
          </li>
        ))}
      </ul>

      {/* 当前系统下的功能 */}
      {system.items.length > 0 && (
        <>
          <div className="mx-2 border-t border-sidebar-border" />
          <div className="px-1">
            <div className="px-2 pb-1.5 pt-1 text-label-12 uppercase tracking-wider text-sidebar-muted">
              {system.label}
            </div>
            <ul className="space-y-1">
              {system.items.map((item) => (
                <li key={item.href}>
                  <NavItemLink
                    item={item}
                    active={pathname === item.href || pathname.startsWith(item.href + '/')}
                    onNavigate={onNavigate}
                  />
                </li>
              ))}
            </ul>
          </div>
        </>
      )}
    </nav>
  );
}

export interface AppShellProps {
  /** 当前 system（可选 — 默认由 pathname 推断） */
  system?: SystemId;
  /** 当前页面标题（不带系统前缀 — 见用户硬规则 #1） */
  title?: string;
  /** 顶栏右侧操作区 */
  actions?: React.ReactNode;
  children: React.ReactNode;
}

/**
 * AppShell v3 — 三层架构简化版
 *
 *   顶部 Header  → 系统切换器（3 个真实系统，不含"全部"）
 *   新 PageHeader → 当前页 title（不带系统前缀）
 *   左侧 Aside  → 顶部固定项（工作台/AI Copilot）+ 当前系统下的功能
 *
 * 设计文档: docs/design/visual-spec.md § 2
 */
export function AppShell({ system, title = '工作台', actions, children }: AppShellProps) {
  const router = useRouter();
  const currentSystem = useCurrentSystem(system);

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      {/* === 桌面端侧栏（≥md） ===
          sticky top-0 + h-screen + overflow-y-auto：侧栏固定在视窗顶部，
          主区滚动时侧栏不滚；侧栏自身只在 nav 溢出时出滚动条 */}
      <aside className="hidden md:sticky md:top-0 md:h-screen md:flex md:w-60 md:shrink-0 md:flex-col md:border-r md:border-sidebar-border md:overflow-y-auto md:self-start">
        <div className="flex h-12 shrink-0 items-center border-b border-sidebar-border px-5">
          <Logo size="sm" />
        </div>
        <NavList system={currentSystem} />
        <div className="shrink-0 border-t border-sidebar-border px-5 py-3 text-label-12 text-muted-foreground">
          © 2026 starcon
        </div>
      </aside>

      {/* === 右侧主体 === */}
      <div className="flex min-w-0 flex-1 flex-col">
        {/* 顶栏 — 系统切换器（不再显示 title，title 移到 PageHeader） */}
        <header className="sticky top-0 z-30 flex h-16 items-center gap-4 border-b border-border bg-background/95 px-4 backdrop-blur md:px-6">
          {/* 左：移动端 Logo（桌面端侧栏已显示） */}
          <div className="md:hidden">
            <Logo size="sm" />
          </div>

          {/* 中：系统切换器 — 只 3 个真实系统，不含"全部" */}
          <div className="flex flex-1 items-center md:flex-none">
            <Segmented
              value={currentSystem.id}
              onChange={(v) => {
                const target = SYSTEMS.find((s) => s.id === v);
                if (target) router.push(target.defaultHref);
              }}
              size="md"
              options={SYSTEMS.map((s) => ({
                value: s.id,
                label: s.label,
              }))}
              className="max-w-full overflow-x-auto"
            />
          </div>

          {/* 中右：搜索（≥xl 才显示 kbd 提示） */}
          <div className="hidden flex-1 md:flex md:justify-center">
            <div className="relative w-full max-w-sm">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <input
                placeholder="搜索订单 / 商品 / 客户"
                className="h-9 w-full rounded-md border border-border bg-card pl-9 pr-12 text-copy-14 placeholder:text-muted-foreground/70 focus:border-foreground/40 focus:outline-none"
              />
              <kbd className="pointer-events-none absolute right-3 top-1/2 hidden -translate-y-1/2 items-center gap-0.5 rounded border border-border bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground xl:inline-flex">
                ⌘K
              </kbd>
            </div>
          </div>

          {/* 右侧操作 */}
          <div className="ml-auto flex items-center gap-2">
            {actions}
            {/* AI Copilot 入口 */}
            <Link
              href="/copilot"
              className="hidden h-9 items-center gap-1.5 rounded-md px-2.5 text-label-13 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground md:inline-flex"
              aria-label="AI Copilot"
            >
              <Sparkles className="h-4 w-4" />
              AI Copilot
            </Link>
            {/* bell + avatar 仅桌面端显示 */}
            <button
              type="button"
              className="hidden h-9 w-9 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground md:flex"
              aria-label="通知"
            >
              <Bell className="h-4 w-4" />
            </button>
            <button
              type="button"
              className="hidden items-center gap-2 rounded-md p-0.5 text-muted-foreground transition-colors hover:bg-muted md:flex"
              aria-label="用户菜单"
            >
              <Avatar size="sm" />
            </button>

            {/* 移动端 hamburger — 在右上角（见用户硬规则 #3） */}
            <div className="md:hidden">
              <Sheet>
                <SheetTrigger asChild>
                  <button
                    type="button"
                    className="flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                    aria-label="菜单"
                  >
                    <Menu className="h-5 w-5" />
                  </button>
                </SheetTrigger>
                <SheetContent side="right" className="w-72 p-0">
                  {/* 抽屉头部：X 在左（贴近入口），Logo 在右（贴近入口方向） */}
                  <div className="flex h-14 items-center justify-between border-b border-border px-4">
                    <SheetClose className="rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground">
                      <X className="h-4 w-4" />
                    </SheetClose>
                    <Logo size="sm" />
                  </div>
                  {/* 移动端系统切换 */}
                  <div className="border-b border-border px-4 py-3">
                    <Segmented
                      value={currentSystem.id}
                      onChange={(v) => {
                        const target = SYSTEMS.find((s) => s.id === v);
                        if (target) router.push(target.defaultHref);
                      }}
                      size="sm"
                      options={SYSTEMS.map((s) => ({ value: s.id, label: s.label }))}
                    />
                  </div>
                  <NavList system={currentSystem} />
                  <div className="border-t border-sidebar-border px-5 py-3 text-label-12 text-muted-foreground">
                    © 2026 starcon
                  </div>
                </SheetContent>
              </Sheet>
            </div>
          </div>
        </header>

        {/* 主区 */}
        <main className="flex-1 overflow-x-hidden">
          <PageHeader title={title} />
          <div className="mx-auto w-full max-w-7xl space-y-6 px-5 py-6">{children}</div>
        </main>
      </div>
    </div>
  );
}