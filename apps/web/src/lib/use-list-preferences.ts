'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  buildPreferencePayload,
  fetchListPreference,
  resetListPreference,
  resolveListPreferences,
  saveListPreference,
  type ListPreferenceOptions,
  type ListPreferenceState,
} from './list-preferences-model';

/**
 * 1.4.1.2 列表与表格偏好 —— 各列表页共用的**可复用 hook**。
 *
 * <p>蓝图：「输入：用户在列表页的列显隐/排序/分页操作；`user_preference`。
 * 输出：下次进入该页时恢复上次的视图状态。」
 *
 * <p>本 hook 把这条链路的**全部**语义收在一处，各列表页只声明三件事：
 * 我有哪些列、我能按什么排序、哪些筛选条件可以记（白名单）。
 *
 * <p>三条纪律：
 *
 * <ol>
 *   <li><b>读不到就按默认值继续渲染</b>。偏好是锦上添花：服务不可达、未登录、没有记录，
 *       都只该让列表页回到默认视图，**绝不能**报错或白屏。</li>
 *   <li><b>恢复时不写库</b>。刚进页面就把默认值存回去，会让「用户从没调过的页面」也留下一行，
 *       而那一行会**冻结**当时的默认列集 —— 以后这一页加了新列，老用户就再也看不到它了。
 *       所以只有**用户真的改了**什么才落库。</li>
 *   <li><b>保存失败不打断使用</b>。失败只在界面上留一条提示，视图照常用。</li>
 * </ol>
 */

export interface UseListPreferences {
  /** 恢复出来的视图状态（页面直接用它渲染）。 */
  state: ListPreferenceState;
  /** 是否已完成「恢复上次状态」（含失败降级）。 */
  ready: boolean;
  /** 服务端是否**真的**存过这一页的偏好（false = 从没存过，用的是默认值）。 */
  stored: boolean;
  /** 正在保存。 */
  saving: boolean;
  /** 偏好相关的错误（读不到 / 存不上）。**不影响**列表本身渲染。 */
  error: string;
  setSortKey: (key: string) => void;
  setPageSize: (size: number) => void;
  setColumns: (columns: string[]) => void;
  toggleColumn: (key: string, visible: boolean) => void;
  setFilter: (key: string, value: string) => void;
  /** 恢复默认（删掉服务端那一行，回到页面默认视图）。 */
  reset: () => void;
}

/** 保存的去抖间隔：连点几下排序不会打一串请求。 */
const SAVE_DEBOUNCE_MS = 400;

function message(cause: unknown): string {
  if (cause instanceof Error && cause.message.trim() !== '') return cause.message;
  return '列表偏好暂时不可用，已按默认视图渲染';
}

export function useListPreferences(options: ListPreferenceOptions): UseListPreferences {
  const [state, setState] = useState<ListPreferenceState>(() =>
    resolveListPreferences(null, options),
  );
  const [ready, setReady] = useState(false);
  const [stored, setStored] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  // options 由调用方每次渲染新建；用 ref 留住「最新一份」，免得把对象塞进依赖数组。
  const optionsRef = useRef(options);
  useEffect(() => {
    optionsRef.current = options;
  }, [options]);

  /** 用户**真的**改过东西吗？只有改过才落库（见纪律 ②）。 */
  const dirtyRef = useRef(false);

  // ------------------------------------------------------------------
  // 挂载时读回上次的视图状态
  // ------------------------------------------------------------------
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const view = await fetchListPreference(options.pageKey);
        if (cancelled) return;
        setState(resolveListPreferences(view.preference, optionsRef.current));
        setStored(view.stored);
      } catch (cause) {
        // 读不到就按默认值继续渲染（纪律 ①）
        if (!cancelled) setError(message(cause));
      } finally {
        if (!cancelled) setReady(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [options.pageKey]);

  // ------------------------------------------------------------------
  // 改了之后落库（去抖）
  // ------------------------------------------------------------------
  const persist = useCallback(async (next: ListPreferenceState) => {
    setSaving(true);
    try {
      await saveListPreference(
        optionsRef.current.pageKey,
        buildPreferencePayload(next, optionsRef.current),
      );
      setStored(true);
      setError('');
    } catch (cause) {
      setError(message(cause));
    } finally {
      setSaving(false);
    }
  }, []);

  useEffect(() => {
    if (!ready || !dirtyRef.current) return;
    const timer = setTimeout(() => {
      void persist(state);
    }, SAVE_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [state, ready, persist]);

  // ------------------------------------------------------------------
  // 改动入口
  // ------------------------------------------------------------------
  const update = useCallback((patch: Partial<ListPreferenceState>) => {
    dirtyRef.current = true;
    setState((previous) => ({ ...previous, ...patch }));
  }, []);

  const setSortKey = useCallback(
    (key: string) => update({ sortKey: key }),
    [update],
  );
  const setPageSize = useCallback(
    (size: number) => update({ pageSize: size }),
    [update],
  );
  const setColumns = useCallback(
    (columns: string[]) => update({ columns }),
    [update],
  );

  const toggleColumn = useCallback((key: string, visible: boolean) => {
    dirtyRef.current = true;
    setState((previous) => {
      if (!visible) {
        return { ...previous, columns: previous.columns.filter((c) => c !== key) };
      }
      // 打开一列时放回它在页面默认列序里的位置，而不是追加到末尾
      const order = optionsRef.current.columns.map((c) => c.key);
      const visibleNow = previous.columns.filter((c) => c !== key);
      const next = order.filter(
        (c) => c === key || visibleNow.includes(c),
      );
      return { ...previous, columns: next };
    });
  }, []);

  const setFilter = useCallback((key: string, value: string) => {
    dirtyRef.current = true;
    setState((previous) => ({ ...previous, filters: { ...previous.filters, [key]: value } }));
  }, []);

  const reset = useCallback(() => {
    // 重置 = 回到页面默认视图，并且服务端那一行也删掉。
    // dirty 置 false：重置后的默认值**不**再被当成「用户改过」写回去（纪律 ②）。
    dirtyRef.current = false;
    setState(resolveListPreferences(null, optionsRef.current));
    setStored(false);
    setSaving(true);
    void (async () => {
      try {
        await resetListPreference(optionsRef.current.pageKey);
        setError('');
      } catch (cause) {
        setError(message(cause));
      } finally {
        setSaving(false);
      }
    })();
  }, []);

  return {
    state,
    ready,
    stored,
    saving,
    error,
    setSortKey,
    setPageSize,
    setColumns,
    toggleColumn,
    setFilter,
    reset,
  };
}
