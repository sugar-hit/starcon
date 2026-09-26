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
 * 完整功能:
 *   - 显示 / 激活 / 关闭(×)
 *   - 每个 tab 操作菜单(··· 或右键):
 *     钉住 / 关闭其他 / 关闭左面所有 / 关闭右面所有
 *   - 拖拽排序(HTML5 DnD)
 *   - 中键关闭
 *
 * 关闭语义(用户硬规则):
 *   - 关闭 active tab → 自动回工作台(URL '/')
 *   - 关闭非 active → 当前 active 不变
 *
 * URL 严格同步(修复之前 tab 显示错位 bug):
 *   - pathname === '/' → activeTabId 永远 null(工作台不进 tab)
 *   - pathname === '/styleguide' → 不参与 tab 系统
 *   - pathname 是 nav item 但不在 tabs → 自动加 tab
 *   - pathname 是 nav item 且在 tabs → 激活
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
  const { tabs, activeTabId, hydrated, activateTab, openTab } = useWorkspaceTabs();
  /**
   * 防止 closeTab 的 race condition 误重开 tab
   *
   * closeTab 顺序:setTabs(filter) → setActiveTabId(null) → router.replace('/')
   * router.replace 是异步的,React 提交 state 后 pathname 还没变,
   * 此时 sync effect 看 pathname 仍是 /orders + tabs=[] → 又把 /orders 加回去
   *
   * 用 prevPathRef 守住:只有在 pathname 真正变化时才跑同步逻辑,
   * tabs / activeTabId 变化触发的 effect 不再误开 tab
   */
  const prevPathRef = React.useRef<string | null>(null);

  // URL → tab store 严格同步(单一真相源)
  // 等 hydrated=true 后才跑,避免 closeTab 后 router.replace 异步触发 sync 误开 tab
  React.useEffect(() => {
    if (!hydrated) return;
    // pathname 没变 → 不跑同步(关闭 tab 时 tabs 变了但 pathname 没变,避免误重开)
    if (prevPathRef.current === pathname) return;
    prevPathRef.current = pathname;
    // 工作台:tab 永远不 active,也不加 tab
    if (pathname === '/') {
      if (activeTabId !== null) activateTab(null);
      return;
    }
    // styleguide:不参与 tab store
    if (pathname === '/styleguide') return;

    const exists = tabs.find((t) => t.href === pathname);
    if (!exists) {
      const item = findNavItemByHref(pathname);
      if (item) {
        const system = resolveSystemFromPath(pathname);
        openTab({
          id: pathname,
          href: pathname,
          label: item.label,
          systemId: system.id,
        });
      }
      return;
    }
    if (activeTabId !== pathname) {
      activateTab(pathname);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname, tabs, activeTabId, hydrated]);

  if (tabs.length === 0) return null;

  // 钉住排前 + 按 order 排
  const sorted = [...tabs].sort((a, b) => {
    if (Boolean(a.pinned) !== Boolean(b.pinned)) return a.pinned ? -1 : 1;
    return a.order - b.order;
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
  const {
    closeTab,
    pinTab,
    closeOthers,
    closeLeft,
    closeRight,
    reorderTabs,
  } = useWorkspaceTabs();
  const item = findNavItemByHref(tab.href);
  const Icon = item?.icon;

  const [draggingOver, setDraggingOver] = React.useState<'left' | 'right' | null>(null);

  const handleClose = (e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    closeTab(tab.id);
  };

  const handleAuxClick = (e: React.MouseEvent) => {
    if (e.button === 1 && !tab.pinned) {
      e.preventDefault();
      closeTab(tab.id);
    }
  };

  // 拖拽(DnD)
  const handleDragStart = (e: React.DragEvent) => {
    if (tab.pinned) {
      e.preventDefault();
      return;
    }
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', tab.id);
  };
  const handleDragOver = (e: React.DragEvent) => {
    if (tab.pinned) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const midpoint = rect.left + rect.width / 2;
    setDraggingOver(e.clientX < midpoint ? 'left' : 'right');
  };
  const handleDragLeave = () => setDraggingOver(null);
  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    const fromId = e.dataTransfer.getData('text/plain');
    setDraggingOver(null);
    if (!fromId || fromId === tab.id) return;
    // 简化:drop 在左半 = 拖到当前 tab 之前;右半 = 之后
    if (draggingOver === 'left') {
      reorderTabs(fromId, tab.id);
    } else {
      // 右半:拖到 tab 之后的位置(把 fromId 移到 tab 之后)
      // reorderTabs 把 fromId 放到 toId 位置;如果 toId 是 fromId 的下一个,会互换
      // 所以我们要"挪到 tab 之后" → 找到 tab 之后下一个非 pinned tab,否则 reorder
      const tabs = Array.from(document.querySelectorAll('[data-tab-id]')) as HTMLElement[];
      const myIdx = tabs.findIndex((el) => el.dataset.tabId === tab.id);
      const nextEl = tabs[myIdx + 1];
      if (nextEl && nextEl.dataset.tabId !== fromId) {
        reorderTabs(fromId, nextEl.dataset.tabId ?? '');
      }
    }
  };
  const handleDragEnd = () => setDraggingOver(null);

  return (
    <div
      role="tab"
      aria-selected={active}
      data-tab-id={tab.id}
      draggable={!tab.pinned}
      onDragStart={handleDragStart}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
      onDragEnd={handleDragEnd}
      onClick={onActivate}
      onAuxClick={handleAuxClick}
      className={cn(
        'group relative flex h-8 cursor-pointer items-center gap-1.5 rounded-md pl-2.5 pr-1 text-copy-13 transition-colors',
        'shrink-0',
        active
          ? 'bg-card text-foreground shadow-xs'
          : 'text-muted-foreground hover:bg-muted/60 hover:text-foreground',
        tab.pinned && 'border-brand/30 border',
        draggingOver === 'left' && 'before:bg-brand before:absolute before:left-0 before:top-1 before:bottom-1 before:w-0.5 before:rounded',
        draggingOver === 'right' && 'after:bg-brand after:absolute after:right-0 after:top-1 after:bottom-1 after:w-0.5 after:rounded',
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
              onContextMenu={(e) => e.preventDefault()}
              className={cn(
                'rounded p-0.5 opacity-0 transition-opacity group-hover:opacity-100',
                'hover:bg-muted-foreground/20',
                active && 'opacity-100',
              )}
              aria-label={`${tab.label} 操作菜单`}
              title="关闭其他 / 左面 / 右面 / 钉住 (右键也行)"
            >
              <MoreHorizontal className="h-3 w-3" />
            </button>
          </PopoverTrigger>
          <TabActionsMenu
            tab={tab}
            onPin={() => pinTab(tab.id, !tab.pinned)}
            onCloseOthers={() => closeOthers(tab.id)}
            onCloseLeft={() => closeLeft(tab.id)}
            onCloseRight={() => closeRight(tab.id)}
            onRefresh={() => router.refresh()}
          />
        </Popover>
      </div>
    </div>
  );
}

interface TabActionsMenuProps {
  tab: WorkspaceTab;
  onPin: () => void;
  onCloseOthers: () => void;
  onCloseLeft: () => void;
  onCloseRight: () => void;
  onRefresh: () => void;
}

function TabActionsMenu({
  tab,
  onPin,
  onCloseOthers,
  onCloseLeft,
  onCloseRight,
  onRefresh,
}: TabActionsMenuProps) {
  return (
    <PopoverContent align="start" sideOffset={6}>
      <div className="flex flex-col gap-0.5" onClick={(e) => e.stopPropagation()}>
        <MenuButton onClick={onPin}>{tab.pinned ? '取消钉住' : '钉住'}</MenuButton>
        <MenuButton onClick={onRefresh}>刷新</MenuButton>
        <MenuButton onClick={onCloseOthers}>关闭其他</MenuButton>
        <MenuButton onClick={onCloseLeft}>关闭左面所有</MenuButton>
        <MenuButton onClick={onCloseRight}>关闭右面所有</MenuButton>
      </div>
    </PopoverContent>
  );
}

function MenuButton({
  children,
  onClick,
}: {
  children: React.ReactNode;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="hover:bg-muted rounded px-2.5 py-1.5 text-left"
    >
      {children}
    </button>
  );
}

export const WorkspaceTabs = WorkspaceTabsImpl;