'use client';

import * as React from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { Pin, X, XCircle } from 'lucide-react';
import { cn } from '../../lib/utils';
import {
  TOP_LEVEL_ITEMS,
  SYSTEMS,
  resolveSystemFromPath,
  type NavItem,
} from '../../lib/nav-config';
import {
  useWorkspaceTabs,
  type WorkspaceTab,
} from '../../lib/workspace-tabs';

/**
 * WorkspaceTabs — 顶部应用内 tab 栏
 *
 * 设计:
 *   - 紧贴 header 下方独立一行(h-10)
 *   - 横向溢出滚动
 *   - 钉住的 tab 排前面(pin 图标 + 不可关闭)
 *   - active tab: bg-card + 底部 brand 边
 *   - hover 显示关闭 / pin 按钮
 *   - 后续 TODO:右键菜单 / 拖拽排序
 */

function findNavItemByHref(href: string): NavItem | null {
  const all: NavItem[] = [
    ...TOP_LEVEL_ITEMS,
    ...SYSTEMS.flatMap((s) => s.items),
  ];
  return all.find((it) => it.href === href) ?? null;
}

function WorkspaceTabsImpl() {
  const pathname = usePathname();
  const router = useRouter();
  const { tabs, activeTabId, activateTab, closeTab, pinTab, closeOthers, closeAll, openTab } =
    useWorkspaceTabs();

  // 同步 active tab → url(确保刷新或外部跳转时 tab 也对齐)
  React.useEffect(() => {
    if (!tabs.length) return;
    const matched = tabs.find((t) => t.href === pathname || pathname.startsWith(t.href + '/'));
    if (matched && matched.id !== activeTabId) {
      activateTab(matched.id);
    } else if (!matched && pathname === '/') {
      // 工作台是兜底,不强加 tab
    } else if (!matched && tabs.length > 0) {
      // path 不在 tabs 里(直接改地址栏),尝试找最近一个
      // 不强制打开 — 用户可能在调试
    }
  }, [pathname, tabs, activeTabId, activateTab]);

  // 如果当前 pathname 不在 tabs 里,但用户访问了 — 自动加 tab
  // 例:刷新页面 / 直接打 URL
  React.useEffect(() => {
    if (pathname === '/styleguide') return; // styleguide 不加 tab
    if (pathname === '/') return; // 工作台不加 tab
    const exists = tabs.find((t) => t.href === pathname);
    if (exists) return;
    const item = findNavItemByHref(pathname);
    if (!item) return;
    const system = resolveSystemFromPath(pathname);
    openTab({
      id: pathname,
      href: pathname,
      label: item.label,
      systemId: system.id,
    });
    // 只在 pathname 变化时触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname]);

  if (tabs.length === 0) return null;

  // 钉住的 tab 排前面
  const sorted = [...tabs].sort((a, b) => {
    if (Boolean(a.pinned) !== Boolean(b.pinned)) return a.pinned ? -1 : 1;
    return a.openedAt - b.openedAt;
  });

  return (
    <div
      role="tablist"
      aria-label="已打开的页面"
      className="border-border bg-background sticky top-16 z-20 flex h-10 shrink-0 items-center gap-1 overflow-x-auto border-b px-3 backdrop-blur md:px-4"
    >
      {sorted.map((tab) => (
        <WorkspaceTabItem
          key={tab.id}
          tab={tab}
          active={tab.id === activeTabId}
          onActivate={() => {
            activateTab(tab.id);
            router.push(tab.href);
          }}
          onClose={(e) => {
            e.stopPropagation();
            e.preventDefault();
            closeTab(tab.id);
            // 如果关闭的是当前 active,跳到相邻
            if (tab.id === activeTabId) {
              const next = tabs.filter((t) => t.id !== tab.id);
              const fallback = next[next.length - 1];
              if (fallback) router.push(fallback.href);
            }
          }}
          onPin={(e) => {
            e.stopPropagation();
            e.preventDefault();
            pinTab(tab.id, !tab.pinned);
          }}
          onContextMenu={(e) => {
            e.preventDefault();
            // TODO: 弹出右键菜单(关闭其他 / 关闭全部)
            // 临时方案:在 console 暴露
            // eslint-disable-next-line no-console
            console.info('tab context menu', tab.id);
          }}
        />
      ))}
      <div className="ml-auto flex items-center gap-1 pl-2">
        {tabs.length > 1 && (
          <button
            type="button"
            onClick={() => closeOthers(activeTabId ?? '')}
            className="text-copy-12 text-muted-foreground hover:bg-muted hover:text-foreground rounded px-2 py-1"
            aria-label="关闭其他 tab"
            title="关闭其他 tab"
          >
            关闭其他
          </button>
        )}
        {tabs.length > 0 && (
          <button
            type="button"
            onClick={closeAll}
            className="text-copy-12 text-muted-foreground hover:bg-muted hover:text-foreground rounded px-2 py-1"
            aria-label="关闭全部 tab"
            title="关闭全部 tab"
          >
            <XCircle className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
    </div>
  );
}

interface WorkspaceTabItemProps {
  tab: WorkspaceTab;
  active: boolean;
  onActivate: () => void;
  onClose: (e: React.MouseEvent) => void;
  onPin: (e: React.MouseEvent) => void;
  onContextMenu: (e: React.MouseEvent) => void;
}

function WorkspaceTabItem({
  tab,
  active,
  onActivate,
  onClose,
  onPin,
  onContextMenu,
}: WorkspaceTabItemProps) {
  const item = findNavItemByHref(tab.href);
  const Icon = item?.icon;
  return (
    <div
      role="tab"
      aria-selected={active}
      onClick={onActivate}
      onContextMenu={onContextMenu}
      onAuxClick={(e) => {
        // 中键关闭
        if (e.button === 1 && !tab.pinned) {
          e.preventDefault();
          onClose(e as unknown as React.MouseEvent);
        }
      }}
      className={cn(
        'group flex h-8 cursor-pointer items-center gap-2 rounded-md px-2.5 text-copy-13 transition-colors',
        'shrink-0',
        active
          ? 'bg-card text-foreground shadow-xs'
          : 'text-muted-foreground hover:bg-muted/60 hover:text-foreground',
        tab.pinned && 'border-brand/30 border',
      )}
    >
      {tab.pinned ? (
        <button
          type="button"
          onClick={onPin}
          className="text-brand -ml-1 rounded p-0.5"
          aria-label="取消钉住"
          title="取消钉住"
        >
          <Pin className="h-3 w-3" />
        </button>
      ) : (
        Icon && <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      )}
      <span className="max-w-[160px] truncate font-medium">{tab.label}</span>
      {!tab.pinned && (
        <button
          type="button"
          onClick={onClose}
          className={cn(
            'rounded p-0.5 opacity-0 transition-opacity group-hover:opacity-100',
            'hover:bg-muted-foreground/20',
            active && 'opacity-100',
          )}
          aria-label={`关闭 ${tab.label}`}
          title="关闭 (中键也可关闭)"
        >
          <X className="h-3 w-3" />
        </button>
      )}
    </div>
  );
}

export const WorkspaceTabs = WorkspaceTabsImpl;