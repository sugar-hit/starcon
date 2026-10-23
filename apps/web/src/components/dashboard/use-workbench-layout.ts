'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  buildWorkbenchLayoutPayload,
  defaultWorkbenchLayout,
  fetchWorkbenchLayout,
  moveWorkbenchCard,
  reorderWorkbenchCard,
  resetWorkbenchLayout,
  resolveWorkbenchLayout,
  saveWorkbenchLayout,
  setWorkbenchCardHidden,
  type WorkbenchLayoutState,
} from './workbench-layout-model';
import type { WorkbenchTabId } from './workbench-card-registry';

/**
 * 1.4.1.1 工作台布局 —— **可复用 hook**。
 *
 * <p>蓝图：「输入：拖拽/显隐操作；系统可选卡片清单。输出：个性化后的工作台布局；
 * 可重置为默认布局。」
 *
 * <p>三条纪律（与 1.4.1.2 的列表偏好 hook 同一套）：
 *
 * <ol>
 *   <li><b>读不到就按默认布局继续渲染</b>。布局是锦上添花：服务不可达、未登录、
 *       从没存过，都只该让工作台回到默认卡片区，**绝不能**报错或白屏。</li>
 *   <li><b>恢复时不写库</b>。刚进页面就把默认值存回去，会给「从没调过布局的人」也留一行；
 *       而那一行会**冻结**当时的卡片清单 —— 以后系统新加一张卡，老用户就再也看不到它了。
 *       所以只有**用户真的改了**什么才落库。</li>
 *   <li><b>保存失败不打断使用</b>。失败只在界面上留一条提示，布局照常用。</li>
 * </ol>
 */
export interface UseWorkbenchLayout {
  /** 当前布局（页面直接用它渲染）。 */
  state: WorkbenchLayoutState;
  /** 是否已完成「读回上次布局」（含失败降级）。 */
  ready: boolean;
  /** 服务端是否**真的**存过这一页的布局（false = 从没存过，用的是系统默认）。 */
  stored: boolean;
  /** 正在保存。 */
  saving: boolean;
  /** 布局相关的错误（读不到 / 存不上）。**不影响**页面渲染。 */
  error: string;
  /** 一次性提示（保存成功、重置完成）。 */
  notice: string;
  /** 整份替换顺序（拖拽 / 按钮都走这里）。 */
  setOrder: (order: string[]) => void;
  /** 上移 / 下移一张卡（键盘可达的排序动作）。 */
  moveCard: (key: string, offset: number) => void;
  /** 拖拽排序：把 from 挪到 to 的位置。 */
  reorderCard: (from: string, to: string) => void;
  /** 显示 / 藏起一张卡。 */
  setCardVisible: (key: string, visible: boolean) => void;
  /** 选择默认落地区分区。 */
  setDefaultTab: (tab: WorkbenchTabId) => void;
  /** 恢复默认布局（真的删掉服务端那一行）。 */
  reset: () => void;
}

/** 保存的去抖间隔：连点几下排序不会打一串请求。 */
const SAVE_DEBOUNCE_MS = 400;

function message(cause: unknown): string {
  if (cause instanceof Error && cause.message.trim() !== '') return cause.message;
  return '工作台布局暂时不可用，已按默认布局渲染';
}

export function useWorkbenchLayout(): UseWorkbenchLayout {
  const [state, setState] = useState<WorkbenchLayoutState>(() => defaultWorkbenchLayout());
  const [ready, setReady] = useState(false);
  const [stored, setStored] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  /** 用户**真的**改过东西吗？只有改过才落库（见纪律 ②）。 */
  const dirtyRef = useRef(false);

  // ------------------------------------------------------------------
  // 挂载时读回上次的布局
  // ------------------------------------------------------------------
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const view = await fetchWorkbenchLayout();
        if (cancelled) return;
        setState(resolveWorkbenchLayout(view.preference));
        setStored(view.stored);
      } catch (cause) {
        // 读不到就按默认布局继续渲染（纪律 ①）
        if (!cancelled) setError(message(cause));
      } finally {
        if (!cancelled) setReady(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // ------------------------------------------------------------------
  // 改了之后落库（去抖）
  // ------------------------------------------------------------------
  useEffect(() => {
    if (!ready || !dirtyRef.current) return;
    const timer = setTimeout(() => {
      setSaving(true);
      void (async () => {
        try {
          await saveWorkbenchLayout(buildWorkbenchLayoutPayload(state));
          setStored(true);
          setError('');
          setNotice('布局已保存');
        } catch (cause) {
          setError(message(cause));
        } finally {
          setSaving(false);
        }
      })();
    }, SAVE_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [state, ready]);

  // ------------------------------------------------------------------
  // 改动入口
  // ------------------------------------------------------------------
  const update = useCallback((patch: Partial<WorkbenchLayoutState>) => {
    dirtyRef.current = true;
    setNotice('');
    setState((previous) => ({ ...previous, ...patch }));
  }, []);

  const setOrder = useCallback(
    (order: string[]) => update({ order }),
    [update],
  );

  const moveCard = useCallback(
    (key: string, offset: number) => {
      dirtyRef.current = true;
      setNotice('');
      setState((previous) => ({
        ...previous,
        order: moveWorkbenchCard(previous.order, key, offset),
      }));
    },
    [],
  );

  const reorderCard = useCallback((from: string, to: string) => {
    dirtyRef.current = true;
    setNotice('');
    setState((previous) => ({
      ...previous,
      order: reorderWorkbenchCard(previous.order, from, to),
    }));
  }, []);

  const setCardVisible = useCallback((key: string, visible: boolean) => {
    dirtyRef.current = true;
    setNotice('');
    setState((previous) => ({
      ...previous,
      hidden: setWorkbenchCardHidden(previous.hidden, key, visible),
    }));
  }, []);

  const setDefaultTab = useCallback(
    (tab: WorkbenchTabId) => update({ defaultTab: tab }),
    [update],
  );

  const reset = useCallback(() => {
    // 重置 = 回到系统默认布局，并且服务端那一行也删掉。
    // dirty 置 false：重置后的默认值**不**再被当成「用户改过」写回去（纪律 ②）。
    dirtyRef.current = false;
    setState(defaultWorkbenchLayout());
    setStored(false);
    setSaving(true);
    void (async () => {
      try {
        await resetWorkbenchLayout();
        setError('');
        setNotice('已恢复为系统默认布局');
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
    notice,
    setOrder,
    moveCard,
    reorderCard,
    setCardVisible,
    setDefaultTab,
    reset,
  };
}
