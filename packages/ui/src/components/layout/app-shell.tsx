'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import {
  Bell,
  Boxes,
  CreditCard,
  LayoutDashboard,
  LogOut,
  Menu,
  Package,
  PackageOpen,
  Search,
  Settings,
  Truck,
  X,
} from 'lucide-react';
import { cn } from '../../lib/utils';
import { Logo } from '../common/logo';
import { Avatar } from '../common/avatar';
import { Sheet, SheetContent, SheetTrigger, SheetClose } from '../ui/sheet';

/**
 * Nav items — ERP 8 组（来自 docs/design/visual-spec.md § 2.1）
 */
const navItems = [
  {
    group: '工作台',
    items: [{ label: '工作台', href: '/', icon: LayoutDashboard }],
  },
  {
    group: '业务',
    items: [
      { label: '订单', href: '/orders', icon: Package },
      { label: '库存', href: '/inventory', icon: Boxes },
      { label: '商品', href: '/products', icon: PackageOpen },
      { label: '履约', href: '/fulfillment', icon: Truck },
    ],
  },
  {
    group: '财务与数据',
    items: [
      { label: '财务对账', href: '/finance', icon: CreditCard },
      { label: '数据分析', href: '/analytics', icon: Boxes },
    ],
  },
  {
    group: '系统',
    items: [{ label: '设置', href: '/settings', icon: Settings }],
  },
];

function NavList({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();

  return (
    <nav className="flex flex-1 flex-col gap-6 px-3 py-2 overflow-y-auto scrollbar-thin">
      {navItems.map((group) => (
        <div key={group.group} className="space-y-1">
          <div className="px-2.5 pb-1.5 text-label-12 uppercase tracking-wider text-sidebar-muted">
            {group.group}
          </div>
          <ul className="space-y-0.5">
            {group.items.map((item) => {
              const Icon = item.icon;
              const active = pathname === item.href;
              return (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    onClick={onNavigate}
                    className={cn(
                      'group flex h-8 items-center gap-2.5 rounded-md px-2.5 text-copy-14 transition-colors duration-base ease-out-expo',
                      active
                        ? 'bg-sidebar-accent font-medium text-foreground'
                        : 'text-muted-foreground hover:bg-sidebar-accent/50 hover:text-foreground'
                    )}
                  >
                    <Icon
                      className={cn(
                        'h-4 w-4 shrink-0 transition-opacity',
                        active ? 'text-sidebar-primary opacity-100' : 'opacity-70 group-hover:opacity-100'
                      )}
                    />
                    {item.label}
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );
}

export interface AppShellProps {
  /** 当前页面 title（不要带 group 前缀 — 见用户硬规则 #1） */
  title?: string;
  /** 可选：顶栏右侧操作区 */
  actions?: React.ReactNode;
  children: React.ReactNode;
}

/**
 * AppShell — 桌面端：左栏 + 顶栏 + 主区
 * 移动端：hamburger 在右上角 → 抽屉从右滑出
 *
 * 设计文档: docs/design/visual-spec.md § 2
 */
export function AppShell({ title = '工作台', actions, children }: AppShellProps) {
  return (
    <div className="flex min-h-screen bg-background text-foreground">
      {/* === 桌面端侧栏（≥md） === */}
      <aside className="hidden md:flex md:w-60 md:shrink-0 md:flex-col md:border-r md:border-sidebar-border">
        <div className="flex h-14 items-center px-5">
          <Logo size="sm" />
        </div>
        <NavList />
        <div className="border-t border-sidebar-border px-5 py-3 text-label-12 text-muted-foreground">
          © 2026 starcon
        </div>
      </aside>

      {/* === 右侧主体 === */}
      <div className="flex min-w-0 flex-1 flex-col">
        {/* 顶栏 — 56px，背景 background（非纯白），底部 1px border */}
        <header className="sticky top-0 z-30 flex h-14 items-center gap-4 border-b border-border bg-background/95 px-4 backdrop-blur md:px-6">
          {/* 左侧：当前页 title（不要 group 前缀 — 见用户硬规则 #1） */}
          <h1 className="text-h-16 text-foreground tracking-tight truncate">{title}</h1>

          {/* 中央：搜索（≥xl 才显示 kbd 提示） */}
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
                  <NavList />
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
          <div className="mx-auto w-full max-w-7xl space-y-6 p-4 md:p-6 lg:p-8">{children}</div>
        </main>
      </div>
    </div>
  );
}