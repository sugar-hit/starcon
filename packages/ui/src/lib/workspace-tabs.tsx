'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import type { SystemId } from './nav-config';

/**
 * WorkspaceTabs — 应用内 tab 栏(中国后台 ERP 标配)
 *
 * 完整功能:
 *   - 打开 / 激活 / 关闭
 *   - 关闭其他 / 关闭左面所有 / 关闭右面所有 / 关闭全部
 *   - 钉住 (pinned) — 关闭其他时不关
 *   - 拖拽排序 (reorder)
 *   - localStorage 持久化(刷新页面 tab 还在)
 *
 * 关闭语义(用户硬规则):
 *   - closeTab:不再自动 fallback 到邻接 tab
 *     关闭 active → router.replace('/') + activeTabId=null(回工作台)
 *     关闭非 active → 不影响 active
 *   - closeAll:清空非 pinned + router.replace('/') + activeTabId=null
 *   - clearAll:彻底清空所有 tab(Logo 用) + router.replace('/')
 *
 * 不实现(用户明确排除):
 *   - 多 monitor 拖出 tab(workspace 模式 B)
 */

export interface WorkspaceTab {
  id: string;
  href: string;
  label: string;
  systemId: SystemId;
  pinned?: boolean;
  /** 拖拽排序用的序列 */
  order: number;
}

export interface WorkspaceTabsContextValue {
  tabs: WorkspaceTab[];
  activeTabId: string | null;
  openTab: (tab: Omit<WorkspaceTab, 'order'>) => void;
  closeTab: (id: string) => void;
  activateTab: (id: string | null) => void;
  pinTab: (id: string, pinned?: boolean) => void;
  closeOthers: (id: string) => void;
  closeLeft: (id: string) => void;
  closeRight: (id: string) => void;
  closeAll: () => void;
  /** 彻底清空(含 pinned)— Logo 点击用 */
  clearAll: () => void;
  /** 拖拽重排:把 fromId 拖到 toId 位置 */
  reorderTabs: (fromId: string, toId: string) => void;
}

const WorkspaceTabsContext = React.createContext<WorkspaceTabsContextValue | null>(null);

const STORAGE_KEY = 'starcon.workspace.tabs.v2';
const HOME_HREF = '/';

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
    return data;
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

export interface WorkspaceTabsProviderProps {
  children: React.ReactNode;
  initialTabs?: WorkspaceTab[];
  initialActiveTabId?: string | null;
}

export function WorkspaceTabsProvider({
  children,
  initialTabs = [],
  initialActiveTabId = null,
}: WorkspaceTabsProviderProps) {
  const router = useRouter();
  const [tabs, setTabs] = React.useState<WorkspaceTab[]>(initialTabs);
  const [activeTabId, setActiveTabId] = React.useState<string | null>(initialActiveTabId);
  const hydratedRef = React.useRef(false);
  const activeTabIdRef = React.useRef<string | null>(initialActiveTabId);
  activeTabIdRef.current = activeTabId;

  React.useEffect(() => {
    const persisted = readPersisted();
    if (persisted) {
      setTabs(persisted.tabs);
      setActiveTabId(persisted.activeTabId);
    }
    hydratedRef.current = true;
  }, []);

  React.useEffect(() => {
    if (!hydratedRef.current) return;
    const handle = window.setTimeout(() => {
      writePersisted({ tabs, activeTabId });
    }, 200);
    return () => window.clearTimeout(handle);
  }, [tabs, activeTabId]);

  const value = React.useMemo<WorkspaceTabsContextValue>(() => {
    const nextOrder = (current: WorkspaceTab[]) =>
      current.length === 0 ? 0 : Math.max(...current.map((t) => t.order)) + 1;

    const openTab = (incoming: Omit<WorkspaceTab, 'order'>) => {
      const id = incoming.id;
      setTabs((current) => {
        const exists = current.find((t) => t.id === id);
        if (exists) return current;
        const next: WorkspaceTab = { ...incoming, order: nextOrder(current) };
        return [...current, next];
      });
      setActiveTabId(id);
    };
    const closeTab = (id: string) => {
      const wasActive = activeTabIdRef.current === id;
      setTabs((current) => current.filter((t) => t.id !== id));
      // 用户硬规则:关闭 tab 就是关闭,不再自动 fallback 到邻接
      // 关闭的是 active → 回工作台(用 replace,避免 history 堆叠)
      if (wasActive) {
        setActiveTabId(null);
        router.replace(HOME_HREF);
      }
    };
    const activateTab = (id: string | null) => {
      if (id === null) {
        setActiveTabId(null);
        return;
      }
      setActiveTabId(id);
    };
    const pinTab = (id: string, pinned: boolean = true) => {
      setTabs((current) =>
        current.map((t) => (t.id === id ? { ...t, pinned } : t)),
      );
    };
    const closeOthers = (id: string) => {
      setTabs((current) => current.filter((t) => t.id === id || t.pinned));
      setActiveTabId(id);
    };
    const closeLeft = (id: string) => {
      setTabs((current) => {
        const idx = current.findIndex((t) => t.id === id);
        if (idx < 0) return current;
        return current.slice(idx);
      });
      setActiveTabId(id);
    };
    const closeRight = (id: string) => {
      setTabs((current) => {
        const idx = current.findIndex((t) => t.id === id);
        if (idx < 0) return current;
        return current.slice(0, idx + 1);
      });
      setActiveTabId(id);
    };
    const closeAll = () => {
      setTabs((current) => current.filter((t) => t.pinned));
      setActiveTabId(null);
      router.replace(HOME_HREF);
    };
    const clearAll = () => {
      setTabs([]);
      setActiveTabId(null);
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
        return next;
      });
    };

    return {
      tabs,
      activeTabId,
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
  }, [tabs, activeTabId, router]);

  return (
    <WorkspaceTabsContext.Provider value={value}>{children}</WorkspaceTabsContext.Provider>
  );
}

export function useWorkspaceTabs(): WorkspaceTabsContextValue {
  const ctx = React.useContext(WorkspaceTabsContext);
  if (!ctx) {
    throw new Error('useWorkspaceTabs must be used within WorkspaceTabsProvider');
  }
  return ctx;
}