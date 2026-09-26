'use client';

import * as React from 'react';
import type { SystemId } from './nav-config';

/**
 * WorkspaceTabs — 应用内 tab 栏(类似中国后台 ERP 标配)
 *
 * 用途:
 *   - 顶部 tab 栏,像 Chrome 但在 App 内
 *   - 点 nav / ExternalLink / Cmd+K → 在 tab 栏加新条目
 *   - 切回旧 tab 不丢上下文(配合 Next.js 路由)
 *   - localStorage 持久化,刷新页面 tab 还在
 *
 * 当前范围:
 *   - 打开 / 激活 / 关闭 / 关闭其他 / 关闭全部
 *   - 钉住 (pinned) — 关闭时优先保留
 *   - 持久化(localStorage)
 *
 * 后续 TODO(暂不实现):
 *   - 拖拽排序
 *   - 右键菜单
 *   - keep-alive 缓存(Next.js App Router 需要 react-activation 等)
 *
 * 注意:Tab 不存 icon 组件引用 — 渲染时根据 href 从 nav-config 反查
 */

export interface WorkspaceTab {
  /** Tab id — 与 href 同值(后续 keep-alive 用) */
  id: string;
  /** 路由 */
  href: string;
  /** 显示标题 */
  label: string;
  /** 所属系统(用于排序 / 分组) */
  systemId: SystemId;
  /** 是否钉住 — 关闭其他时不关 */
  pinned?: boolean;
  /** 打开时间戳 */
  openedAt: number;
}

export interface WorkspaceTabsContextValue {
  tabs: WorkspaceTab[];
  activeTabId: string | null;
  /** 打开或激活:已存在则激活,否则添加 */
  openTab: (tab: Omit<WorkspaceTab, 'openedAt'>) => void;
  closeTab: (id: string) => void;
  activateTab: (id: string) => void;
  pinTab: (id: string, pinned?: boolean) => void;
  closeOthers: (id: string) => void;
  closeAll: () => void;
}

const WorkspaceTabsContext = React.createContext<WorkspaceTabsContextValue | null>(null);

const STORAGE_KEY = 'starcon.workspace.tabs.v1';

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
    // localStorage 可能满 / 禁用了,静默失败
  }
}

export interface WorkspaceTabsProviderProps {
  children: React.ReactNode;
  /** SSR/CSR 注水前的初始 tab(用于 hydration mismatch 保护) */
  initialTabs?: WorkspaceTab[];
  initialActiveTabId?: string | null;
}

export function WorkspaceTabsProvider({
  children,
  initialTabs = [],
  initialActiveTabId = null,
}: WorkspaceTabsProviderProps) {
  // SSR 用初始值,客户端 mount 后再 hydrate 真实数据
  const [tabs, setTabs] = React.useState<WorkspaceTab[]>(initialTabs);
  const [activeTabId, setActiveTabId] = React.useState<string | null>(initialActiveTabId);
  const hydratedRef = React.useRef(false);

  React.useEffect(() => {
    const persisted = readPersisted();
    if (persisted) {
      setTabs(persisted.tabs);
      setActiveTabId(persisted.activeTabId);
    }
    hydratedRef.current = true;
  }, []);

  // 持久化(debounced)
  React.useEffect(() => {
    if (!hydratedRef.current) return;
    const handle = window.setTimeout(() => {
      writePersisted({ tabs, activeTabId });
    }, 200);
    return () => window.clearTimeout(handle);
  }, [tabs, activeTabId]);

  const value = React.useMemo<WorkspaceTabsContextValue>(() => {
    const openTab = (incoming: Omit<WorkspaceTab, 'openedAt'>) => {
      const id = incoming.id;
      setTabs((current) => {
        const exists = current.find((t) => t.id === id);
        if (exists) return current;
        const next: WorkspaceTab = { ...incoming, openedAt: Date.now() };
        return [...current, next];
      });
      setActiveTabId(id);
    };
    const closeTab = (id: string) => {
      setTabs((current) => {
        const next = current.filter((t) => t.id !== id);
        setActiveTabId((currentActive) => {
          if (currentActive !== id) return currentActive;
          if (next.length === 0) return null;
          const removedIdx = current.findIndex((t) => t.id === id);
          const fallback = next[Math.min(removedIdx, next.length - 1)] ?? next[0];
          return fallback?.id ?? null;
        });
        return next;
      });
    };
    const activateTab = (id: string) => setActiveTabId(id);
    const pinTab = (id: string, pinned: boolean = true) => {
      setTabs((current) =>
        current.map((t) => (t.id === id ? { ...t, pinned } : t)),
      );
    };
    const closeOthers = (id: string) => {
      setTabs((current) => current.filter((t) => t.id === id || t.pinned));
      setActiveTabId(id);
    };
    const closeAll = () => {
      setTabs((current) => current.filter((t) => t.pinned));
      setActiveTabId((current) => {
        if (!current) return null;
        const isPinned = tabs.find((t) => t.id === current)?.pinned;
        return isPinned ? current : null;
      });
    };
    return { tabs, activeTabId, openTab, closeTab, activateTab, pinTab, closeOthers, closeAll };
  }, [tabs, activeTabId]);

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