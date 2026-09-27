'use client';

import * as React from 'react';
import { usePathname } from 'next/navigation';
import { ChevronsLeft, ChevronsRight, X } from 'lucide-react';
import { cn } from '../../lib/utils';
import { Popover, PopoverAnchor, PopoverContent } from '../ui/popover';
import { NAV_DESTINATIONS, resolveSystemFromPath } from '../../lib/nav-config';
import { useWorkspaceTabs } from '../../lib/workspace-tabs';

export function WorkspaceTabs() {
  const pathname = usePathname();
  const {
    tabs,
    activeTabId,
    hydrated,
    openTab,
    activateTab,
    closeTab,
    closeOthers,
    closeLeft,
    closeRight,
    closeAll,
    reorderTabs,
  } = useWorkspaceTabs();
  const previousPath = React.useRef<string | null>(null);
  const [menu, setMenu] = React.useState<string | null>(null);
  React.useEffect(() => {
    if (!hydrated || previousPath.current === pathname) return;
    previousPath.current = pathname;
    const item = NAV_DESTINATIONS.find((item) => item.href === pathname);
    if (item && !tabs.some((tab) => tab.href === pathname)) {
      openTab({
        id: pathname,
        href: pathname,
        label: item.label,
        systemId: resolveSystemFromPath(pathname).id,
      });
    }
  }, [pathname, hydrated, tabs, openTab]);

  React.useEffect(() => {
    document
      .querySelector<HTMLElement>('[data-tab-active="true"]')
      ?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [activeTabId]);
  if (!tabs.length) return null;

  return (
    <div className="border-border bg-background relative z-20 flex h-10 min-w-0 shrink-0 items-center border-b px-4 md:px-6">
      <div
        role="tablist"
        aria-label="已打开的页面"
        className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto"
      >
        {tabs.map((tab, index) => (
          <Popover
            key={tab.id}
            open={menu === tab.id}
            onOpenChange={(open) => setMenu(open ? tab.id : null)}
          >
            <PopoverAnchor asChild>
              <div
                data-tab-id={tab.id}
                data-tab-active={tab.id === activeTabId}
                draggable
                onDragStart={(event) => event.dataTransfer.setData('text/plain', tab.id)}
                onDragOver={(event) => event.preventDefault()}
                onDrop={(event) => {
                  event.preventDefault();
                  reorderTabs(event.dataTransfer.getData('text/plain'), tab.id);
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
                  'text-copy-13 flex h-8 shrink-0 items-center rounded-md',
                  tab.id === activeTabId
                    ? 'bg-card text-foreground shadow-xs'
                    : 'text-muted-foreground hover:bg-muted',
                )}
              >
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab.id === activeTabId}
                  className="max-w-40 truncate rounded-md px-2 py-1.5 font-medium"
                  onClick={() => activateTab(tab.id)}
                  onKeyDown={(event) => {
                    if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
                      event.preventDefault();
                      setMenu(tab.id);
                    }
                    const next =
                      event.key === 'Home'
                        ? tabs[0]
                        : event.key === 'End'
                          ? tabs[tabs.length - 1]
                          : event.key === 'ArrowLeft'
                            ? tabs[(index - 1 + tabs.length) % tabs.length]
                            : event.key === 'ArrowRight'
                              ? tabs[(index + 1) % tabs.length]
                              : null;
                    if (next) {
                      event.preventDefault();
                      activateTab(next.id);
                      const buttons = event.currentTarget
                        .closest('[role="tablist"]')
                        ?.querySelectorAll<HTMLButtonElement>('[role="tab"]');
                      buttons?.[tabs.indexOf(next)]?.focus();
                    }
                  }}
                >
                  {tab.label}
                </button>
                <button
                  type="button"
                  aria-label={`关闭 ${tab.label}`}
                  className="hover:bg-muted mr-1 rounded p-1 focus-visible:opacity-100"
                  onClick={() => closeTab(tab.id)}
                >
                  <X className="h-3 w-3" />
                </button>
              </div>
            </PopoverAnchor>
            <PopoverContent
              align="start"
              onCloseAutoFocus={(event) => {
                event.preventDefault();
                document
                  .querySelector<HTMLElement>('[data-tab-active="true"] [role="tab"]')
                  ?.focus();
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
                  { label: '关闭当前', action: () => closeTab(tab.id), disabled: false },
                  {
                    label: '关闭其他',
                    action: () => closeOthers(tab.id),
                    disabled: tabs.length === 1,
                  },
                  { label: '关闭左侧', action: () => closeLeft(tab.id), disabled: index === 0 },
                  {
                    label: '关闭右侧',
                    action: () => closeRight(tab.id),
                    disabled: index === tabs.length - 1,
                  },
                  { label: '关闭全部', action: closeAll, disabled: false },
                ].map(({ label, action, disabled }) => (
                  <button
                    type="button"
                    role="menuitem"
                    key={label}
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
        ))}
      </div>
      <button
        type="button"
        aria-label="跳到最左 Tab"
        className="hover:bg-muted ml-1 shrink-0 rounded p-1"
        onClick={() => tabs[0] && activateTab(tabs[0].id)}
      >
        <ChevronsLeft className="h-4 w-4" />
      </button>
      <button
        type="button"
        aria-label="跳到最右 Tab"
        className="hover:bg-muted shrink-0 rounded p-1"
        onClick={() => tabs[tabs.length - 1] && activateTab(tabs[tabs.length - 1]!.id)}
      >
        <ChevronsRight className="h-4 w-4" />
      </button>
    </div>
  );
}
