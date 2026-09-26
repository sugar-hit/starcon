'use client';

import * as React from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { MoreHorizontal, Pin, X } from 'lucide-react';
import { cn } from '../../lib/utils';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '../ui/popover';
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
 *   - active tab: bg-card + 边框 + 当前页的 icon 在文字前
 *   - hover 显示关闭按钮 / `···` 操作菜单
 *
 * 关闭行为(用户硬规则):
 *   - 关闭 tab 就是关闭,不再 fallback 到邻接
 *   - 关闭 active tab → 自动回到工作台(/)
 *   - 关闭非 active tab → 当前 active 不变
 *
 * Tab 操作菜单(右键 / hover `···` 触发):
 *   - 关闭其他 / 关闭左面 / 关闭右面 / 钉住切换
 *
 * 后续 TODO:
 *   - 拖拽排序
 *   - keep-alive 缓存
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
  const { tabs, activeTabId, activateTab, openTab } = useWorkspaceTabs();

  // 同步 active tab → url(确保刷新或外部跳转时 tab 也对齐)
  React.useEffect(() => {
    if (!tabs.length) return;
    const matched = tabs.find((t) => t.href === pathname || pathname.startsWith(t.href + '/'));
    if (matched && matched.id !== activeTabId) {
      activateTab(matched.id);
    }
  }, [pathname, tabs, activeTabId, activateTab]);

  // 如果当前 pathname 不在 tabs 里,但用户访问了 — 自动加 tab
  // 例:刷新页面 / 直接打 URL
  React.useEffect(() => {
    if (pathname === '/styleguide') return;
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
          onActivate={() => activateTab(tab.id)}
        />
      ))}
    </div>
  );
}

interface WorkspaceTabItemProps {
  tab: WorkspaceTab;
  active: boolean;
  onActivate: () => void;
}

function WorkspaceTabItem({ tab, active, onActivate }: WorkspaceTabItemProps) {
  const router = useRouter();
  const { closeTab, pinTab, closeOthers, closeLeft, closeRight } = useWorkspaceTabs();
  const item = findNavItemByHref(tab.href);
  const Icon = item?.icon;

  const handleActivate = () => {
    onActivate();
    router.push(tab.href);
  };

  const handleClose = (e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    closeTab(tab.id);
    // store 内 closeTab 已处理 active 跳转逻辑
  };

  return (
    <div
      role="tab"
      aria-selected={active}
      onClick={handleActivate}
      onAuxClick={(e) => {
        // 中键关闭
        if (e.button === 1 && !tab.pinned) {
          e.preventDefault();
          closeTab(tab.id);
        }
      }}
      className={cn(
        'group flex h-8 cursor-pointer items-center gap-1.5 rounded-md pl-2.5 pr-1 text-copy-13 transition-colors',
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
          onClick={(e) => {
            e.stopPropagation();
            pinTab(tab.id, !tab.pinned);
          }}
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

      <div className="flex items-center gap-0.5">
        {!tab.pinned && (
          <button
            type="button"
            onClick={handleClose}
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
        <Popover>
          <PopoverTrigger asChild>
            <button
              type="button"
              onClick={(e) => e.stopPropagation()}
              className={cn(
                'rounded p-0.5 opacity-0 transition-opacity group-hover:opacity-100',
                'hover:bg-muted-foreground/20',
                active && 'opacity-100',
              )}
              aria-label={`${tab.label} 操作菜单`}
              title="关闭其他 / 左面 / 右面 / 钉住"
            >
              <MoreHorizontal className="h-3 w-3" />
            </button>
          </PopoverTrigger>
          <PopoverContent align="start" sideOffset={6}>
            <div className="flex flex-col gap-0.5" onClick={(e) => e.stopPropagation()}>
              <button
                type="button"
                onClick={() => {
                  pinTab(tab.id, !tab.pinned);
                }}
                className="hover:bg-muted rounded px-2.5 py-1.5 text-left"
              >
                {tab.pinned ? '取消钉住' : '钉住'}
              </button>
              <button
                type="button"
                onClick={() => {
                  closeOthers(tab.id);
                }}
                className="hover:bg-muted rounded px-2.5 py-1.5 text-left"
              >
                关闭其他
              </button>
              <button
                type="button"
                onClick={() => {
                  closeLeft(tab.id);
                }}
                className="hover:bg-muted rounded px-2.5 py-1.5 text-left"
              >
                关闭左面所有
              </button>
              <button
                type="button"
                onClick={() => {
                  closeRight(tab.id);
                }}
                className="hover:bg-muted rounded px-2.5 py-1.5 text-left"
              >
                关闭右面所有
              </button>
            </div>
          </PopoverContent>
        </Popover>
      </div>
    </div>
  );
}

export const WorkspaceTabs = WorkspaceTabsImpl;