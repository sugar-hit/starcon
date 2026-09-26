'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { BookOpen, Menu, Search, X, type LucideIcon } from 'lucide-react';
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
import { PageHeader } from '../common/page-header';
import { CommandPalette, type CommandPaletteGroup } from '../common/command-palette';
import {
  WorkspaceTabsProvider,
  useWorkspaceTabs,
} from '../../lib/workspace-tabs';
import { WorkspaceTabs } from './workspace-tabs';
import { Sheet, SheetContent, SheetTrigger, SheetClose, SheetTitle } from '../ui/sheet';

function NavItemLink({
  item,
  active,
  onNavigate,
}: {
  item: NavItem;
  active: boolean;
  onNavigate?: (() => void) | undefined;
}) {
  const router = useRouter();
  const { openTab } = useWorkspaceTabs();
  const pathname = usePathname();
  const system = resolveSystemFromPath(pathname);
  const Icon = item.icon;
  return (
    <Link
      href={item.href}
      onClick={(e) => {
        // 中键 / Cmd+Ctrl 点击 — 浏览器原生新 tab,不动 store
        if (e.metaKey || e.ctrlKey || e.button === 1) return;
        e.preventDefault();
        onNavigate?.();
        openTab({
          id: item.href,
          href: item.href,
          label: item.label,
          systemId: system.id,
        });
        router.push(item.href);
      }}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'text-copy-14 flex min-h-9 items-center gap-2.5 rounded-md px-3 py-1.5 transition-colors md:min-h-8',
        active
          ? 'bg-brand-soft text-brand font-semibold'
          : 'text-muted-foreground hover:bg-muted hover:text-foreground',
      )}
    >
      <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
      {item.label}
    </Link>
  );
}

function NavList({
  system,
  onNavigate,
}: {
  system: SystemDef;
  onNavigate?: (() => void) | undefined;
}) {
  const pathname = usePathname();
  const render = (item: NavItem) => (
    <li key={item.href}>
      <NavItemLink
        item={item}
        active={
          pathname === item.href || (item.href !== '/' && pathname.startsWith(item.href + '/'))
        }
        onNavigate={onNavigate}
      />
    </li>
  );
  return (
    <nav aria-label="功能导航" className="flex flex-1 flex-col gap-5 px-3 py-5">
      <ul className="space-y-1">{TOP_LEVEL_ITEMS.map(render)}</ul>
      <div>
        <p className="text-label-12 text-muted-foreground mb-2 px-3">{system.label}系统</p>
        <ul className="space-y-1">{system.items.map(render)}</ul>
      </div>
    </nav>
  );
}

function SystemSwitcher({
  currentSystem,
  onNavigate,
  variant = 'desktop',
}: {
  currentSystem: SystemDef;
  onNavigate?: (() => void) | undefined;
  variant?: 'desktop' | 'sheet';
}) {
  const router = useRouter();
  const { openTab } = useWorkspaceTabs();
  return (
    <nav
      aria-label="系统导航"
      className={cn('flex items-center gap-1', variant === 'sheet' && 'w-full')}
    >
      {SYSTEMS.map((s) => {
        const isActive = currentSystem.id === s.id;
        return (
          <Link
            key={s.id}
            href={s.defaultHref}
            onClick={(e) => {
              if (e.metaKey || e.ctrlKey || e.button === 1) return;
              e.preventDefault();
              onNavigate?.();
              // 切系统:打开该系统默认页 tab
              const defaultItem = s.items[0];
              if (defaultItem) {
                openTab({
                  id: s.defaultHref,
                  href: s.defaultHref,
                  label: defaultItem.label,
                  systemId: s.id,
                });
                router.push(s.defaultHref);
              }
            }}
            aria-current={isActive ? 'true' : undefined}
            className={cn(
              'text-label-14 whitespace-nowrap rounded-md px-3 py-2',
              isActive
                ? 'bg-card text-foreground shadow-xs font-semibold'
                : 'text-muted-foreground hover:bg-card',
            )}
          >
            {s.label}
          </Link>
        );
      })}
    </nav>
  );
}

export interface AppShellProps {
  system?: SystemId;
  title?: string;
  description?: string;
  actions?: React.ReactNode;
  children: React.ReactNode;
}

function AppShellInner({
  system,
  title = '工作台',
  description,
  actions,
  children,
}: AppShellProps) {
  const pathname = usePathname();
  const currentSystem = system ? getSystem(system) : resolveSystemFromPath(pathname);
  const [searchOpen, setSearchOpen] = React.useState(false);
  const [menuOpen, setMenuOpen] = React.useState(false);
  React.useEffect(() => {
    function handleKey(event: KeyboardEvent) {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setSearchOpen((open) => !open);
      }
    }
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, []);

  const searchGroups: CommandPaletteGroup[] = React.useMemo(() => {
    const buildItem = (
      it: { label: string; href: string; description?: string; icon: LucideIcon },
      prefix: string,
      subLabel?: string,
    ) => {
      const desc = subLabel
        ? `${subLabel} · ${it.description ?? ''}`
        : it.description;
      return {
        id: `${prefix}-${it.href}`,
        label: it.label,
        ...(desc ? { description: desc } : {}),
        href: it.href,
        icon: it.icon,
      };
    };
    const navItems: CommandPaletteGroup = {
      heading: '功能导航',
      items: [
        ...TOP_LEVEL_ITEMS.map((it) => buildItem(it, 'nav')),
        ...SYSTEMS.flatMap((s) => s.items.map((it) => buildItem(it, 'nav', `${s.label}系统`))),
        {
          id: 'nav-styleguide',
          label: '设计系统',
          description: '组件与视觉规范',
          href: '/styleguide',
          icon: BookOpen,
        },
      ],
    };
    const businessObjects: CommandPaletteGroup = {
      heading: '业务对象',
      items: [
        {
          id: 'biz-pending',
          label: '订单 / SKU / 客户 搜索',
          description: '按单号、SKU、客户名跨模块索引',
          icon: Search,
          disabled: true,
          disabledReason: '业务数据接入后启用 — 实施进度见各 domain-spec',
        },
      ],
    };
    const actions: CommandPaletteGroup = {
      heading: '系统动作',
      items: [
        {
          id: 'action-search',
          label: '搜索功能或命令',
          description: '在此输入关键词跨模块跳转',
          icon: Search,
          shortcut: '⌘ K',
          onSelect: () => setSearchOpen(true),
        },
      ],
    };
    return [navItems, businessObjects, actions];
  }, []);

  const systemsDesktop = (
    <SystemSwitcher
      currentSystem={currentSystem}
      variant="desktop"
    />
  );
  const systemsSheet = (
    <SystemSwitcher
      currentSystem={currentSystem}
      variant="sheet"
      onNavigate={() => setMenuOpen(false)}
    />
  );
  return (
    <div className="bg-background text-foreground flex min-h-screen">
      <a
        href="#main-content"
        className="focus:bg-card sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[100] focus:rounded focus:p-3"
      >
        跳到主要内容
      </a>
      <aside className="md:border-border hidden md:sticky md:top-0 md:flex md:h-screen md:w-56 md:shrink-0 md:flex-col md:self-start md:overflow-y-auto md:border-r">
        <Link
          href="/"
          aria-label="Starcon 工作台"
          className="border-border flex h-16 shrink-0 items-center border-b px-5"
        >
          <Logo size="sm" />
        </Link>
        <NavList system={currentSystem} />
        <div className="border-border space-y-3 border-t p-4">
          <Link
            href="/styleguide"
            aria-current={pathname === '/styleguide' ? 'page' : undefined}
            className="text-copy-13 text-muted-foreground hover:text-brand flex items-center gap-2"
          >
            <BookOpen className="h-4 w-4" />
            设计系统 <span className="text-label-12 ml-auto">2.0</span>
          </Link>
          <div className="text-copy-13 flex items-center gap-2">
            <span className="border-border bg-card text-label-12 flex h-7 w-7 items-center justify-center rounded-md border">
              S
            </span>
            <span>Starcon 演示空间</span>
          </div>
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="border-border bg-background/95 sticky top-0 z-30 flex h-16 shrink-0 items-center gap-3 border-b px-4 backdrop-blur md:px-6">
          <Link href="/" className="md:hidden" aria-label="Starcon 工作台">
            <Logo size="sm" markOnly />
          </Link>
          <div className="bg-muted/70 hidden rounded-lg p-0.5 md:block">{systemsDesktop}</div>
          <span className="text-label-14 md:hidden">{currentSystem.label}系统</span>
          <button
            type="button"
            onClick={() => setSearchOpen(true)}
            className="border-border bg-card text-copy-13 text-muted-foreground hover:border-control-border ml-auto flex h-9 items-center gap-2 rounded-md border px-3"
            aria-label="搜索功能"
          >
            <Search className="h-4 w-4" />
            <span className="hidden lg:inline">搜索功能</span>
            <kbd className="border-border text-label-12 hidden rounded border px-1 xl:inline">
              ⌘ K
            </kbd>
          </button>
          <span className="border-border bg-card text-label-12 text-muted-foreground hidden rounded border px-2 py-1 sm:inline">
            演示环境
          </span>
          <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
            <SheetTrigger asChild>
              <button
                type="button"
                aria-label="菜单"
                className="hover:bg-muted flex h-10 w-10 items-center justify-center rounded-md md:hidden"
              >
                <Menu className="h-5 w-5" />
              </button>
            </SheetTrigger>
            <SheetContent
              side="right"
              aria-describedby={undefined}
              className="w-80 max-w-[calc(100vw-2rem)] overflow-y-auto"
            >
              <SheetTitle className="sr-only">功能导航</SheetTitle>
              <div className="border-border flex h-16 items-center justify-between border-b px-4">
                <SheetClose
                  aria-label="关闭菜单"
                  className="hover:bg-muted flex h-9 w-9 items-center justify-center rounded-md"
                >
                  <X className="h-4 w-4" />
                </SheetClose>
                <Logo size="sm" />
              </div>
              <div className="border-border border-b p-3">{systemsSheet}</div>
              <NavList system={currentSystem} onNavigate={() => setMenuOpen(false)} />
              <Link
                href="/styleguide"
                onClick={() => setMenuOpen(false)}
                className="text-copy-14 text-brand m-5 block"
              >
                设计系统
              </Link>
            </SheetContent>
          </Sheet>
        </header>
        <WorkspaceTabs />
        <main id="main-content" tabIndex={-1} className="min-w-0 flex-1">
          <PageHeader title={title} {...(description ? { description } : {})} actions={actions} />
          <div className="mx-auto w-full max-w-7xl space-y-6 px-4 pb-10 pt-6 md:px-6">
            {children}
          </div>
        </main>
      </div>
      <CommandPalette
        open={searchOpen}
        onOpenChange={setSearchOpen}
        groups={searchGroups}
      />
    </div>
  );
}

export function AppShell(props: AppShellProps) {
  return (
    <WorkspaceTabsProvider>
      <AppShellInner {...props} />
    </WorkspaceTabsProvider>
  );
}