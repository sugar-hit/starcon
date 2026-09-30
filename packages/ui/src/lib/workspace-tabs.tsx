'use client';

import * as React from 'react';
import { usePathname, useRouter } from 'next/navigation';
import {
  ACCOUNT_DESTINATIONS,
  NAV_DESTINATIONS,
  resolveSystemFromPath,
  type SystemId,
} from './nav-config';

/** Ordered tabs persist; the committed pathname alone determines selection. */
export interface WorkspaceTab {
  id: string;
  href: string;
  label: string;
  systemId: SystemId;
  pinned?: boolean;
  surface?: 'business' | 'account';
  /** 拖拽排序用的序列 */
  order: number;
}

export interface WorkspaceTabsContextValue {
  tabs: WorkspaceTab[];
  activeTabId: string | null;
  /** 客户端 hydration 完成(localStorage 读完后)— sync 逻辑需等此值变 true 再跑 */
  hydrated: boolean;
  navigatingTo: string | null;
  openTab: (tab: Omit<WorkspaceTab, 'order'>) => void;
  closeTab: (id: string) => void;
  activateTab: (id: string | null) => void;
  pinTab: (id: string, pinned?: boolean) => void;
  closeOthers: (id: string) => void;
  closeLeft: (id: string) => void;
  closeRight: (id: string) => void;
  closeAll: () => void;
  /** 彻底清空（仅显式关闭全部使用；Logo 使用 activateTab(null)） */
  clearAll: () => void;
  /** 拖拽重排:把 fromId 拖到 toId 位置 */
  reorderTabs: (fromId: string, toId: string) => void;
}

const WorkspaceTabsContext = React.createContext<WorkspaceTabsContextValue | null>(null);

const STORAGE_KEY = 'starcon.workspace.tabs.v2';
const HOME_HREF = '/';
const HOME_TAB: WorkspaceTab = {
  id: '/',
  href: '/',
  label: '工作台',
  systemId: 'dev',
  order: -1,
  pinned: true,
  surface: 'business',
};

function destination(pathname: string) {
  const business = NAV_DESTINATIONS.find((item) => item.href === pathname);
  if (business) return { ...business, surface: 'business' as const };
  const account = ACCOUNT_DESTINATIONS.find((item) => item.href === pathname);
  return account ? { ...account, surface: 'account' as const } : undefined;
}

interface PersistedShape {
  tabs: WorkspaceTab[];
  activeTabId: string | null;
}

function readPersisted(): PersistedShape | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const data = JSON.parse(raw) as PersistedShape;
    if (!Array.isArray(data?.tabs)) return null;
    const tabs = data.tabs.flatMap((tab) => {
      const item = destination(tab?.href);
      return item
        ? [
            {
              id: item.href,
              href: item.href,
              label: item.label,
              systemId: resolveSystemFromPath(item.href).id,
              surface: item.surface,
              order: 0,
            },
          ]
        : [];
    });
    return {
      tabs: [...new Map(tabs.map((tab) => [tab.href, tab])).values()].map((tab, order) => ({
        ...tab,
        order,
      })),
      activeTabId: null,
    };
  } catch {
    return null;
  }
}

function writePersisted(value: PersistedShape) {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
  } catch {
    /* quota / disabled — silent */
  }
}

/** Register during render so the route and its tab are committed together. */
function withRouteTab(tabs: WorkspaceTab[], pathname: string): WorkspaceTab[] {
  const item = destination(pathname);
  if (!item || tabs.some((tab) => tab.href === pathname)) return tabs;
  return [
    ...tabs,
    {
      id: item.href,
      href: item.href,
      label: item.label,
      systemId: resolveSystemFromPath(item.href).id,
      surface: item.surface,
      order: tabs.length,
    },
  ];
}

export interface WorkspaceTabsProviderProps {
  children: React.ReactNode;
  initialTabs?: WorkspaceTab[];
  initialActiveTabId?: string | null;
}

export function WorkspaceTabsProvider({ children, initialTabs = [] }: WorkspaceTabsProviderProps) {
  const router = useRouter();
  const pathname = usePathname();
  const [tabs, setTabs] = React.useState<WorkspaceTab[]>(() =>
    withRouteTab(
      initialTabs.filter((tab) => tab.id !== HOME_HREF && tab.href !== HOME_HREF),
      pathname,
    ),
  );
  const [registeredPath, setRegisteredPath] = React.useState(pathname);
  const [navigatingTo, setNavigatingTo] = React.useState<string | null>(null);
  // Only a new pathname registers a tab; closing on the same route must not reopen it.
  if (registeredPath !== pathname) {
    setRegisteredPath(pathname);
    setNavigatingTo(null);
    setTabs((current) => withRouteTab(current, pathname));
  }
  const activeTabId =
    pathname === HOME_HREF ? HOME_HREF : (tabs.find((tab) => tab.href === pathname)?.id ?? null);
  const [hydrated, setHydrated] = React.useState(false);
  const activeTabIdRef = React.useRef<string | null>(null);
  activeTabIdRef.current = activeTabId;

  React.useEffect(() => {
    const persisted = readPersisted();
    if (persisted) {
      setTabs((current) =>
        [
          ...persisted.tabs,
          ...current.filter((tab) => !persisted.tabs.some((saved) => saved.href === tab.href)),
        ].map((tab, order) => ({ ...tab, order })),
      );
    }
    setHydrated(true);
  }, []);

  React.useEffect(() => {
    if (!hydrated) return;
    writePersisted({ tabs, activeTabId });
  }, [tabs, activeTabId, hydrated]);

  const value = React.useMemo<WorkspaceTabsContextValue>(() => {
    const openTab = (incoming: Omit<WorkspaceTab, 'order'>) => {
      if (incoming.href === HOME_HREF) {
        if (pathname !== HOME_HREF) setNavigatingTo(HOME_HREF);
        router.push(HOME_HREF);
        return;
      }
      // Keep the committed page and tab paired. The destination tab is registered from
      // pathname during the route commit, at the same boundary as the new page/skeleton.
      if (pathname !== incoming.href) {
        setNavigatingTo(incoming.href);
        router.push(incoming.href);
      }
    };
    const closeTab = (id: string) => {
      if (id === HOME_HREF) return;
      const wasActive = activeTabIdRef.current === id;
      setTabs((current) => current.filter((t) => t.id !== id));
      // 用户硬规则:关闭 tab 就是关闭,不再自动 fallback 到邻接
      // 关闭的是 active → 回工作台(用 replace,避免 history 堆叠)
      if (wasActive) {
        router.replace(HOME_HREF);
      }
    };
    const activateTab = (id: string | null) => {
      const tab = tabs.find((item) => item.id === id);
      const href = id === null || id === HOME_HREF ? HOME_HREF : tab?.href;
      if (href && href !== pathname) {
        setNavigatingTo(href);
        router.push(href);
      }
    };
    const pinTab = (id: string, pinned: boolean = true) => {
      setTabs((current) => current.map((t) => (t.id === id ? { ...t, pinned } : t)));
    };
    const closeOthers = (id: string) => {
      // Home is implicit and permanent. From home, close every closable tab;
      // from any other tab, preserve only that tab plus implicit home.
      setTabs((current) => (id === HOME_HREF ? [] : current.filter((tab) => tab.id === id)));
      activateTab(id);
    };
    const closeLeft = (id: string) => {
      setTabs((current) => {
        const target = current.find((tab) => tab.id === id);
        if (!target) return current;
        const peers = current.filter((tab) => tab.surface === target.surface);
        const idx = peers.findIndex((tab) => tab.id === id);
        if (idx < 0) return current;
        const removed = new Set(peers.slice(0, idx).map((tab) => tab.id));
        return current.filter((tab) => !removed.has(tab.id));
      });
      activateTab(id);
    };
    const closeRight = (id: string) => {
      if (id === HOME_HREF) {
        setTabs([]);
        router.replace(HOME_HREF);
        return;
      }
      setTabs((current) => {
        const target = current.find((tab) => tab.id === id);
        if (!target) return current;
        const peers = current.filter((tab) => tab.surface === target.surface);
        const idx = peers.findIndex((tab) => tab.id === id);
        if (idx < 0) return current;
        const removed = new Set(peers.slice(idx + 1).map((tab) => tab.id));
        return current.filter((tab) => !removed.has(tab.id));
      });
      activateTab(id);
    };
    const closeAll = () => {
      setTabs([]);
      router.replace(HOME_HREF);
    };
    const clearAll = () => {
      setTabs([]);
      router.replace(HOME_HREF);
    };
    const reorderTabs = (fromId: string, toId: string) => {
      if (fromId === toId) return;
      setTabs((current) => {
        const fromIdx = current.findIndex((t) => t.id === fromId);
        const toIdx = current.findIndex((t) => t.id === toId);
        if (fromIdx < 0 || toIdx < 0) return current;
        const next = [...current];
        const [moved] = next.splice(fromIdx, 1);
        if (!moved) return current;
        next.splice(toIdx, 0, moved);
        return next.map((tab, order) => ({ ...tab, order }));
      });
    };

    return {
      tabs: [
        HOME_TAB,
        ...tabs.filter((tab) => tab.surface !== 'account'),
        ...tabs.filter((tab) => tab.surface === 'account'),
      ],
      activeTabId,
      hydrated,
      navigatingTo,
      openTab,
      closeTab,
      activateTab,
      pinTab,
      closeOthers,
      closeLeft,
      closeRight,
      closeAll,
      clearAll,
      reorderTabs,
    };
  }, [tabs, activeTabId, hydrated, navigatingTo, router, pathname]);

  return <WorkspaceTabsContext.Provider value={value}>{children}</WorkspaceTabsContext.Provider>;
}

export function useWorkspaceTabs(): WorkspaceTabsContextValue {
  const ctx = React.useContext(WorkspaceTabsContext);
  if (!ctx) {
    throw new Error('useWorkspaceTabs must be used within WorkspaceTabsProvider');
  }
  return ctx;
}
