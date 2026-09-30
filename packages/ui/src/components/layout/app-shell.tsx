'use client';

import * as React from 'react';
import { AccountMenu } from '../common/account-menu';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { BookOpen, ChevronDown, Menu, Search, Sparkles, X, type LucideIcon } from 'lucide-react';
import { cn } from '../../lib/utils';
import {
  SYSTEMS,
  NAV_DESTINATIONS,
  getSystem,
  resolveFeatureFromPath,
  resolveSystemFromPath,
  type SystemId,
  type SystemDef,
  type NavItem,
} from '../../lib/nav-config';
import { getDomainSpec } from '../../lib/domain-spec';
import { Logo } from '../common/logo';
import { DomainPlaceholder } from '../common/domain-placeholder';
import { PageHeader } from '../common/page-header';
import { CommandPalette, type CommandPaletteGroup } from '../common/command-palette';
import { WorkspaceTabsProvider, useWorkspaceTabs } from '../../lib/workspace-tabs';
import { WorkspaceTabs } from './workspace-tabs';
import { Sheet, SheetContent, SheetTrigger, SheetClose, SheetTitle } from '../ui/sheet';

/**
 * AppShell — 应用 shell(侧栏 + header + tab 栏 + main)
 *
 * 集成:
 *   - WorkspaceTabsProvider / WorkspaceTabs:顶部 tab 栏
 *   - 页面内容由 Next 路由管理，Tab 高亮只跟随已提交的 pathname
 *   - Logo 点击 → 保留 tab + 跳工作台
 *   - 系统切换 → 打开默认页 tab
 *   - Cmd+K 命令面板
 *
 * 工作台特殊:
 *   - 工作台 (/) 不进 tab store — 仅 Logo / 关闭全部时显示
 */

function NavItemLink({
  item,
  active,
  onNavigate,
}: {
  item: NavItem;
  active: boolean;
  onNavigate?: (() => void) | undefined;
}) {
  const { openTab } = useWorkspaceTabs();
  const system = resolveSystemFromPath(item.href);
  return (
    <Link
      href={item.href}
      onClick={(e) => {
        if (e.metaKey || e.ctrlKey || e.button === 1) return;
        e.preventDefault();
        onNavigate?.();
        openTab({
          id: item.href,
          href: item.href,
          label: item.label,
          systemId: system.id,
        });
      }}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'text-copy-14 flex min-h-9 items-center gap-2.5 rounded-md px-3 py-1.5 transition-colors md:min-h-8',
        active
          ? 'bg-brand-soft text-brand font-semibold'
          : 'text-muted-foreground hover:bg-muted hover:text-foreground',
      )}
    >
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
  const [expanded, setExpanded] = React.useState<Record<string, boolean>>({});
  React.useEffect(() => {
    const parent = system.items.find(
      (item) => pathname === item.href || pathname.startsWith(item.href + '/'),
    );
    if (parent) setExpanded((state) => ({ ...state, [parent.href]: true }));
  }, [pathname, system]);
  const idPrefix = React.useId();
  const render = (item: NavItem) => {
    const Icon = item.icon;
    const open = expanded[item.href] ?? false;
    const groupId = `${idPrefix}-${item.href.slice(1)}`;
    return (
      <li key={item.href}>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={groupId}
          onClick={() => setExpanded((state) => ({ ...state, [item.href]: !open }))}
          className="text-copy-14 hover:bg-muted flex min-h-9 w-full items-center gap-2.5 rounded-md px-3 py-2 text-left font-medium"
        >
          <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
          <span className="flex-1">{item.label}</span>
          <ChevronDown className={cn('h-3.5 w-3.5 transition-transform', !open && '-rotate-90')} />
        </button>
        <ul
          id={groupId}
          hidden={!open}
          className="border-border ml-5 space-y-0.5 border-l py-1 pl-2"
        >
          {item.children?.map((child) => (
            <li key={child.href}>
              <NavItemLink
                item={{ ...child, icon: item.icon }}
                active={pathname === child.href}
                onNavigate={onNavigate}
              />
            </li>
          ))}
        </ul>
      </li>
    );
  };
  return (
    <nav aria-label="功能导航" className="flex flex-1 flex-col gap-5 px-3 py-5">
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
              const defaultItem = s.items[0]?.children?.[0];
              if (defaultItem) {
                openTab({
                  id: s.defaultHref,
                  href: s.defaultHref,
                  label: defaultItem.label,
                  systemId: s.id,
                });
              }
            }}
            aria-current={isActive ? 'true' : undefined}
            className={cn(
              'text-label-14 flex-1 whitespace-nowrap rounded-md px-3 py-2 text-center md:min-w-12 md:px-2 lg:min-w-20 lg:px-5',
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

/**
 * LogoLink — 点击 Logo → 保留所有 tab + 跳工作台
 * (用户硬规则:工作台不进 tab,只在 Logo / 关闭全部时出现)
 */
function LogoLink() {
  const { activateTab } = useWorkspaceTabs();
  return (
    <Link
      href="/"
      aria-label="Starcon 工作台"
      onClick={(e) => {
        if (e.metaKey || e.ctrlKey || e.button === 1) return;
        e.preventDefault();
        activateTab(null);
      }}
      className="border-border flex h-16 shrink-0 items-center border-b px-5"
    >
      <Logo size="sm" />
    </Link>
  );
}

export interface AppShellProps {
  system?: SystemId;
  account?: React.ReactNode;
  children: React.ReactNode;
}

function AppShellInner({ system, children, account }: AppShellProps) {
  const { activateTab } = useWorkspaceTabs();
  const pathname = usePathname();
  const mainRef = React.useRef<HTMLElement>(null);
  React.useEffect(() => {
    mainRef.current?.scrollTo({ top: 0, left: 0 });
  }, [pathname]);
  const routeSystem = system ? getSystem(system) : resolveSystemFromPath(pathname);
  const lastSystemRef = React.useRef(routeSystem);
  // 工作台是跨系统页面。切回工作台时保留用户刚才所在的系统导航，避免顶部系统菜单
  // 和左侧功能菜单因为 `/` 的默认路由归属而跳回开发系统。
  if (pathname !== '/') lastSystemRef.current = routeSystem;
  const currentSystem = pathname === '/' ? lastSystemRef.current : routeSystem;
  /**
   * 推导当前页的 PageHeader(标题 + 描述)
   *
   * 数据流:pathname → resolveFeatureFromPath → nav-config NavItem
   *        → 若有对应 domain-spec,优先用 spec.purpose(更面向用户的描述);
   *          否则 fallback 到 nav-config 的短描述
   *
   * 例外:
   *   - /styleguide:硬编码 "设计系统"
   *   - 404 / 未匹配路径:不渲染 PageHeader(由 EmptyState 承担视觉权重)
   */
  const pageHeaderMeta = React.useMemo(() => {
    if (pathname === '/') return null;
    if (pathname === '/styleguide') {
      return {
        title: '设计系统',
        description: 'Starcon 的共同语言。从商业数据,到每一次操作。',
      };
    }
    const feature = resolveFeatureFromPath(pathname, currentSystem);
    if (!feature) return null;
    // feature.href 形如 "/products" → domain id 为 "products"
    const spec = getDomainSpec(feature.href.replace(/^\//, ''));
    return {
      title: feature.label,
      description: feature.description ?? spec?.purpose ?? '',
    };
  }, [pathname, currentSystem]);
  const [copilotOpen, setCopilotOpen] = React.useState(false);
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
      const desc = subLabel ? `${subLabel} · ${it.description ?? ''}` : it.description;
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
        ...NAV_DESTINATIONS.map((it) =>
          buildItem(it, 'nav', resolveSystemFromPath(it.href).label + '系统'),
        ),
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

  const systemsDesktop = <SystemSwitcher currentSystem={currentSystem} variant="desktop" />;
  const systemsSheet = (
    <SystemSwitcher
      currentSystem={currentSystem}
      variant="sheet"
      onNavigate={() => setMenuOpen(false)}
    />
  );
  return (
    <div className="bg-background text-foreground flex h-dvh min-h-0 overflow-hidden">
      <a
        href="#main-content"
        className="focus:bg-card sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[100] focus:rounded focus:p-3"
      >
        跳到主要内容
      </a>
      <aside className="md:border-border hidden md:flex md:h-full md:w-56 md:shrink-0 md:flex-col md:border-r">
        <LogoLink />
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
          <NavList system={currentSystem} />
        </div>
        <div className="border-border space-y-3 border-t p-4">
          <Link
            href="/styleguide"
            aria-current={pathname === '/styleguide' ? 'page' : undefined}
            className="text-copy-13 text-muted-foreground hover:text-brand flex items-center gap-2"
          >
            <BookOpen className="h-4 w-4" />
            设计系统 <span className="text-label-12 ml-auto">2.0</span>
          </Link>
          <div className="border-border text-muted-foreground border-t pt-3" aria-label="版权信息">
            <p className="whitespace-nowrap text-[10px] font-semibold uppercase leading-4 tracking-wide">
              pioneering innovation<span className="text-brand">.</span>
            </p>
            <p className="text-copy-12 mt-2 leading-relaxed">湃宁科技（深圳）有限责任公司</p>
            <p className="text-copy-12 mt-1">© {new Date().getFullYear()} · All rights reserved.</p>
          </div>
        </div>
      </aside>
      <div className="flex h-full min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        <header className="border-border shell-header relative z-30 flex h-16 shrink-0 items-center gap-3 border-b px-4 backdrop-blur md:px-6">
          <Link
            href="/"
            className="md:hidden"
            aria-label="Starcon 工作台"
            onClick={(event) => {
              if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
              event.preventDefault();
              activateTab(null);
            }}
          >
            <Logo size="sm" markOnly />
          </Link>
          <div className="bg-muted/70 hidden rounded-lg p-0.5 md:block">{systemsDesktop}</div>
          <span className="text-label-14 hidden sm:inline md:hidden">
            {currentSystem.label}系统
          </span>
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
          <Sheet open={copilotOpen} onOpenChange={setCopilotOpen}>
            <SheetTrigger asChild>
              <button
                type="button"
                aria-label="AI Copilot"
                className="hover:bg-muted flex h-9 shrink-0 items-center gap-2 rounded-md px-2"
              >
                <Sparkles className="h-4 w-4" />
                <span className="hidden lg:inline">AI Copilot</span>
              </button>
            </SheetTrigger>
            <SheetContent
              aria-describedby={undefined}
              overlayClassName="copilot-backdrop"
              className="copilot-panel flex flex-col overflow-hidden"
            >
              <div className="border-border flex shrink-0 items-center justify-between border-b px-6 py-4">
                <SheetTitle>AI Copilot</SheetTitle>
                <SheetClose aria-label="关闭 AI Copilot" className="hover:bg-muted rounded-md p-2">
                  <X className="h-4 w-4" />
                </SheetClose>
              </div>
              <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-4 sm:p-6">
                <DomainPlaceholder spec={getDomainSpec('copilot')!} />
              </div>
            </SheetContent>
          </Sheet>
          {account ?? <AccountMenu />}
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
        <main
          ref={mainRef}
          id="main-content"
          tabIndex={-1}
          className="min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain"
        >
          <div className="w-full space-y-4 px-4 pb-10 pt-4 md:px-6">
            {pageHeaderMeta && (
              <PageHeader title={pageHeaderMeta.title} description={pageHeaderMeta.description} />
            )}
            {children}
          </div>
        </main>
      </div>
      <CommandPalette open={searchOpen} onOpenChange={setSearchOpen} groups={searchGroups} />
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
