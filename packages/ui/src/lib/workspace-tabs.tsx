'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import type { SystemId } from './nav-config';

/**
 * WorkspaceTabs — 应用内 tab 栏(类似中国后台 ERP 标配)
 *
 * 当前范围:
 *   - 打开 / 激活 / 关闭 / 关闭其他 / 关闭全部 / 关闭左面 / 关闭右面
 *   - 钉住 (pinned) — 关闭其他时不关
 *   - 持久化(localStorage)
 *
 * 关闭语义(用户硬规则):
 *   - closeTab:不再自动 activate 邻接 tab
 *     关闭的是 active tab → router.push('/') + activeTabId=null(回到工作台)
 *     关闭的不是 active tab → 只关,不影响 active
 *   - closeAll:router.push('/') + tabs=[] + activeTabId=null
 *
 * 后续 TODO(暂不实现):
 *   - 拖拽排序
 *   - keep-alive 缓存(Next.js App Router 需要 react-activation 等)
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
  closeLeft: (id: string) => void;
  closeRight: (id: string) => void;
  closeAll: () => void;
}

const WorkspaceTabsContext = React.createContext<WorkspaceTabsContextValue | null>(null);

const STORAGE_KEY = 'starcon.workspace.tabs.v1';
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
  const router = useRouter();
  // SSR 用初始值,客户端 mount 后再 hydrate 真实数据
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
      const wasActive = activeTabIdRef.current === id;
      setTabs((current) => current.filter((t) => t.id !== id));
      // 用户硬规则:关闭 tab 就是关闭,不再自动 fallback
      // 关闭的是 active → 回到工作台
      if (wasActive) {
        setActiveTabId(null);
        router.push(HOME_HREF);
      }
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
    const closeLeft = (id: string) => {
      setTabs((current) => {
        const idx = current.findIndex((t) => t.id === id);
        if (idx < 0) return current;
        const next = current.slice(idx);
        // 关闭后,active 失效(关闭的是当前或左侧)
        return next;
      });
      // 关闭后 active 切到 id(如果它还在)
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
      router.push(HOME_HREF);
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