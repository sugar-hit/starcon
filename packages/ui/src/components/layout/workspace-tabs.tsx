'use client';

import * as React from 'react';
import { ChevronLeft, ChevronRight, X } from 'lucide-react';
import { cn } from '../../lib/utils';
import {
  getWorkspaceTabOverflow,
  type WorkspaceTabOverflow,
} from '../../lib/workspace-tab-overflow';
import { Popover, PopoverAnchor, PopoverContent } from '../ui/popover';
import { useWorkspaceTabs, type WorkspaceTab } from '../../lib/workspace-tabs';

export function WorkspaceTabs() {
  const {
    tabs,
    activeTabId,
    activateTab,
    closeTab,
    closeOthers,
    closeLeft,
    closeRight,
    closeAll,
    reorderTabs,
    navigatingTo,
  } = useWorkspaceTabs();
  const [menu, setMenu] = React.useState<string | null>(null);
  const tabListRef = React.useRef<HTMLDivElement>(null);
  const [overflow, setOverflow] = React.useState<WorkspaceTabOverflow>({
    canScrollBack: false,
    canScrollForward: false,
  });
  const businessTabs = tabs.filter((tab) => (tab.surface ?? 'business') === 'business');
  const accountTabs = tabs.filter((tab) => tab.surface === 'account');
  const activeTab = tabs.find((tab) => tab.id === activeTabId) ?? tabs[0];
  const activeSurface = activeTab?.surface ?? 'business';
  const groupTabs = tabs.filter((tab) => (tab.surface ?? 'business') === activeSurface);
  const groupIndex = groupTabs.findIndex((tab) => tab.id === activeTab?.id);
  const previousTab = groupIndex > 0 ? groupTabs[groupIndex - 1] : undefined;
  const nextTab = groupIndex >= 0 ? groupTabs[groupIndex + 1] : undefined;

  const updateOverflow = React.useCallback(() => {
    const tabList = tabListRef.current;
    if (!tabList) return;

    const nextOverflow = getWorkspaceTabOverflow(
      tabList.scrollLeft,
      tabList.scrollWidth,
      tabList.clientWidth,
    );
    setOverflow((current) =>
      current.canScrollBack === nextOverflow.canScrollBack &&
      current.canScrollForward === nextOverflow.canScrollForward
        ? current
        : nextOverflow,
    );
  }, []);

  React.useEffect(() => {
    tabListRef.current
      ?.querySelector<HTMLElement>('[data-tab-active="true"]')
      ?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [activeTabId]);

  React.useEffect(() => {
    const tabList = tabListRef.current;
    if (!tabList) return;

    updateOverflow();
    tabList.addEventListener('scroll', updateOverflow, { passive: true });
    window.addEventListener('resize', updateOverflow);
    const resizeObserver =
      typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(updateOverflow);
    resizeObserver?.observe(tabList);

    return () => {
      tabList.removeEventListener('scroll', updateOverflow);
      window.removeEventListener('resize', updateOverflow);
      resizeObserver?.disconnect();
    };
  }, [tabs.length, updateOverflow]);

  const renderTab = (tab: WorkspaceTab, peers: WorkspaceTab[]) => {
    const globalIndex = tabs.findIndex((item) => item.id === tab.id);
    const peerIndex = peers.findIndex((item) => item.id === tab.id);
    const hasClosableLeft = peers.slice(0, peerIndex).some((item) => item.id !== '/');
    const hasClosableRight = peers.slice(peerIndex + 1).some((item) => item.id !== '/');

    return (
      <Popover
        key={tab.id}
        open={menu === tab.id}
        onOpenChange={(open) => setMenu(open ? tab.id : null)}
      >
        <PopoverAnchor asChild>
          <div
            data-tab-id={tab.id}
            data-tab-surface={tab.surface ?? 'business'}
            data-tab-active={tab.id === activeTabId}
            draggable={tab.id !== '/'}
            onDragStart={(event) => event.dataTransfer.setData('text/plain', tab.id)}
            onDragOver={(event) => event.preventDefault()}
            onDrop={(event) => {
              event.preventDefault();
              const fromId = event.dataTransfer.getData('text/plain');
              const fromTab = tabs.find((item) => item.id === fromId);
              if ((fromTab?.surface ?? 'business') === (tab.surface ?? 'business')) {
                reorderTabs(fromId, tab.id);
              }
            }}
            onContextMenu={(event) => {
              event.preventDefault();
              setMenu(tab.id);
            }}
            onAuxClick={(event) => {
              if (event.button === 1) {
                event.preventDefault();
                closeTab(tab.id);
              }
            }}
            className={cn(
              'text-copy-13 relative flex h-9 shrink-0 items-center rounded-md border',
              tab.id === '/' && 'sticky left-0 z-30',
              tab.id === activeTabId
                ? 'border-brand bg-brand-soft text-brand shadow-xs'
                : 'border-border bg-card text-foreground hover:border-control-border hover:bg-background',
            )}
          >
            <button
              type="button"
              role="tab"
              aria-selected={tab.id === activeTabId}
              className="focus-visible:ring-brand max-w-48 truncate rounded-md px-3 py-2 font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset"
              onClick={() => activateTab(tab.id)}
              onKeyDown={(event) => {
                if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
                  event.preventDefault();
                  setMenu(tab.id);
                }
                const next =
                  event.key === 'Home'
                    ? peers[0]
                    : event.key === 'End'
                      ? peers[peers.length - 1]
                      : event.key === 'ArrowLeft'
                        ? peers[(peerIndex - 1 + peers.length) % peers.length]
                        : event.key === 'ArrowRight'
                          ? peers[(peerIndex + 1) % peers.length]
                          : null;
                if (next) {
                  event.preventDefault();
                  activateTab(next.id);
                  const buttons = event.currentTarget
                    .closest('[role="tablist"]')
                    ?.querySelectorAll<HTMLButtonElement>('[role="tab"]');
                  buttons?.[peers.indexOf(next)]?.focus();
                }
              }}
            >
              {tab.label}
            </button>
            {tab.id !== '/' && (
              <button
                type="button"
                aria-label={`关闭 ${tab.label}`}
                className="hover:bg-muted focus-visible:ring-brand mr-1 rounded p-1.5 focus-visible:outline-none focus-visible:ring-2"
                onClick={() => closeTab(tab.id)}
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>
        </PopoverAnchor>
        <PopoverContent
          align="start"
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            document.querySelector<HTMLElement>('[data-tab-active="true"] [role="tab"]')?.focus();
          }}
        >
          <div
            role="menu"
            aria-label={`${tab.label} 操作`}
            className="flex flex-col"
            onKeyDown={(event) => {
              const items = Array.from(
                event.currentTarget.querySelectorAll<HTMLButtonElement>(
                  '[role="menuitem"]:not(:disabled)',
                ),
              );
              const index = items.indexOf(document.activeElement as HTMLButtonElement);
              const next =
                event.key === 'ArrowDown'
                  ? (index + 1) % items.length
                  : event.key === 'ArrowUp'
                    ? (index - 1 + items.length) % items.length
                    : event.key === 'Home'
                      ? 0
                      : event.key === 'End'
                        ? items.length - 1
                        : -1;
              if (next >= 0) {
                event.preventDefault();
                items[next]?.focus();
              }
            }}
          >
            {[
              { label: '关闭当前', action: () => closeTab(tab.id), disabled: tab.id === '/' },
              {
                label: '关闭其他',
                action: () => closeOthers(tab.id),
                disabled: tabs.length <= (tab.id === '/' ? 1 : 2),
              },
              { label: '关闭左侧', action: () => closeLeft(tab.id), disabled: !hasClosableLeft },
              {
                label: '关闭右侧',
                action: () => closeRight(tab.id),
                disabled: !hasClosableRight,
              },
              { label: '关闭全部', action: closeAll, disabled: tabs.length === 1 },
            ].map(({ label, action, disabled }) => (
              <button
                type="button"
                role="menuitem"
                key={`${globalIndex}-${label}`}
                disabled={disabled}
                className="hover:bg-muted rounded px-3 py-1.5 text-left disabled:opacity-40"
                onClick={() => {
                  setMenu(null);
                  action();
                }}
              >
                {label}
              </button>
            ))}
          </div>
        </PopoverContent>
      </Popover>
    );
  };

  return (
    <div className="border-border bg-background relative z-20 flex h-12 min-w-0 shrink-0 items-center border-b px-4 md:px-6">
      <div className="relative min-w-0 flex-1">
        <div
          ref={tabListRef}
          role="tablist"
          aria-label="已打开的页面"
          className="scrollbar-none flex min-w-0 scroll-pl-20 items-center gap-1.5 overflow-x-auto pr-2"
        >
          {businessTabs.map((tab) => renderTab(tab, businessTabs))}
        </div>
        <div
          aria-hidden="true"
          className={cn(
            'from-background pointer-events-none absolute inset-y-0 left-0 z-20 w-6 bg-gradient-to-r to-transparent transition-opacity',
            overflow.canScrollBack ? 'opacity-100' : 'opacity-0',
          )}
        />
        <div
          aria-hidden="true"
          className={cn(
            'from-background pointer-events-none absolute inset-y-0 right-0 z-20 w-8 bg-gradient-to-l to-transparent transition-opacity',
            overflow.canScrollForward ? 'opacity-100' : 'opacity-0',
          )}
        />
      </div>
      {accountTabs.length > 0 && (
        <div
          role="tablist"
          aria-label="账户页面"
          className="border-border ml-3 flex shrink-0 items-center gap-1.5 border-l pl-3"
        >
          {accountTabs.map((tab) => renderTab(tab, accountTabs))}
        </div>
      )}
      <div
        className="border-border ml-3 flex shrink-0 items-center gap-0.5 border-l pl-2"
        role="group"
        aria-label={`${activeSurface === 'account' ? '账户' : '业务'} Tab 切换`}
      >
        <button
          type="button"
          aria-label="当前分组的上一个 Tab"
          disabled={!previousTab}
          className="text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-brand flex h-7 w-7 items-center justify-center rounded-md transition-colors disabled:cursor-default disabled:opacity-25 disabled:hover:bg-transparent"
          onClick={() => previousTab && activateTab(previousTab.id)}
        >
          <ChevronLeft className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          aria-label="当前分组的下一个 Tab"
          disabled={!nextTab}
          className="text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-brand flex h-7 w-7 items-center justify-center rounded-md transition-colors disabled:cursor-default disabled:opacity-25 disabled:hover:bg-transparent"
          onClick={() => nextTab && activateTab(nextTab.id)}
        >
          <ChevronRight className="h-3.5 w-3.5" />
        </button>
      </div>
      {navigatingTo && (
        <div className="bg-brand/15 pointer-events-none absolute inset-x-0 bottom-0 h-0.5 overflow-hidden">
          <span className="bg-brand block h-full w-1/3 animate-[tab-route-progress_900ms_ease-in-out_infinite]" />
        </div>
      )}
    </div>
  );
}
