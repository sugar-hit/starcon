'use client';

import { useCallback, useEffect, useSyncExternalStore } from 'react';
import {
  DEFAULT_LOCALE,
  DEFAULT_TIMEZONE,
  fetchAccountLocale,
  resetAccountLocale,
  saveAccountLocale,
} from './account-locale-model';

/**
 * 1.4.2.1 语言与时区 —— 全站共用的**展示时区**来源。
 *
 * <p>蓝图：「界面语言（中文/英文等）与展示时区，影响时间显示与日期分组，**不改变存储时区**。」
 * 这个 hook 就是「展示时区」这件事在应用层的落点：任何页面想知道「现在该按哪个时区显示时间」，
 * 都问它，不要各自去读一遍偏好。
 *
 * <p>用**模块级 store**（{@code useSyncExternalStore}）而不是 React Context，理由有两个：
 *
 * <ol>
 *   <li><b>不用改根布局</b>：Context 要在根上包一个 Provider，而根布局是多车道共用的文件；
 *       模块级 store 谁用谁订阅，零侵入。</li>
 *   <li><b>只读一次</b>：偏好在整个会话里基本不变，模块级 store 保证多个页面订阅的是同一份快照，
 *       不会各自发一遍请求。</li>
 * </ol>
 *
 * <p>三条纪律（与 1.4.1.2 的偏好 hook 同源）：
 *
 * <ul>
 *   <li><b>读不到就按默认值继续渲染</b>：偏好是锦上添花，服务不可达、未登录都不该让页面崩。</li>
 *   <li><b>写失败不打断使用</b>：失败只在界面上留一条提示，页面照常用。</li>
 *   <li><b>重置 = 删掉服务端那一行</b>，回到默认，而不是把默认值写回去。</li>
 * </ul>
 */

export interface AccountLocaleState {
  /** 是否已完成首次读取（含失败降级）。 */
  ready: boolean;
  /** 服务端是否真的存过这一页（false = 从没存过，用的是默认值）。 */
  stored: boolean;
  /** 当前生效的界面语言。 */
  locale: string;
  /** 当前生效的展示时区。 */
  timezone: string;
  /** 平台默认界面语言。 */
  defaultLocale: string;
  /** 平台默认展示时区。 */
  defaultTimezone: string;
  /** 发生了回落时的原因（空 = 没回落）。 */
  fallbackReason: string;
  /** 读 / 写相关的错误。 */
  error: string;
  /** 最近一次操作的结果提示。 */
  notice: string;
  /** 正在保存 / 重置。 */
  saving: boolean;
}

export interface UseAccountLocale extends AccountLocaleState {
  /** 重新读一次。 */
  reload: () => Promise<void>;
  /** 保存（两个字段都可以只给一个）。返回是否成功。 */
  save: (next: { locale?: string; timezone?: string }) => Promise<boolean>;
  /** 恢复默认（删掉服务端那一行）。返回是否成功。 */
  reset: () => Promise<boolean>;
}

const initialState: AccountLocaleState = {
  ready: false,
  stored: false,
  locale: DEFAULT_LOCALE,
  timezone: DEFAULT_TIMEZONE,
  defaultLocale: DEFAULT_LOCALE,
  defaultTimezone: DEFAULT_TIMEZONE,
  fallbackReason: '',
  error: '',
  notice: '',
  saving: false,
};

let state: AccountLocaleState = initialState;
const listeners = new Set<() => void>();
let loading: Promise<void> | null = null;

function emit(patch: Partial<AccountLocaleState>) {
  state = { ...state, ...patch };
  for (const listener of [...listeners]) listener();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function getSnapshot(): AccountLocaleState {
  return state;
}

function message(cause: unknown): string {
  if (cause instanceof Error && cause.message.trim() !== '') return cause.message;
  return '语言与时区设置暂时不可用，已按默认时区显示';
}

/** 读一次偏好。并发调用共享同一个请求。 */
async function ensureLoaded(): Promise<void> {
  if (loading) return loading;
  loading = (async () => {
    try {
      const view = await fetchAccountLocale();
      emit({
        ready: true,
        stored: view.stored,
        locale: view.locale || DEFAULT_LOCALE,
        timezone: view.timezone || DEFAULT_TIMEZONE,
        defaultLocale: view.defaultLocale || DEFAULT_LOCALE,
        defaultTimezone: view.defaultTimezone || DEFAULT_TIMEZONE,
        fallbackReason: view.fallbackReason ?? '',
        error: '',
      });
    } catch (cause) {
      // 读不到就按默认继续渲染（纪律 ①）：时区偏好坏了，不该让整站显示不出来。
      emit({ ready: true, error: message(cause) });
    }
  })();
  return loading;
}

/**
 * 订阅当前的展示时区与界面语言。
 *
 * <p>页面只要读 {@code timezone} 就能按用户选的时区显示时间，不必自己知道偏好存在哪。
 */
export function useAccountLocale(): UseAccountLocale {
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);

  useEffect(() => {
    void ensureLoaded();
  }, []);

  const reload = useCallback(async () => {
    loading = null;
    await ensureLoaded();
  }, []);

  const save = useCallback(async (next: { locale?: string; timezone?: string }) => {
    emit({ saving: true, error: '', notice: '' });
    try {
      const view = await saveAccountLocale(next);
      emit({
        stored: view.stored,
        locale: view.locale || DEFAULT_LOCALE,
        timezone: view.timezone || DEFAULT_TIMEZONE,
        fallbackReason: view.fallbackReason ?? '',
        notice: '已保存',
      });
      return true;
    } catch (cause) {
      emit({ error: message(cause) });
      return false;
    } finally {
      emit({ saving: false });
    }
  }, []);

  const reset = useCallback(async () => {
    emit({ saving: true, error: '', notice: '' });
    try {
      await resetAccountLocale();
      emit({
        stored: false,
        locale: DEFAULT_LOCALE,
        timezone: DEFAULT_TIMEZONE,
        fallbackReason: '',
        notice: '已恢复默认',
      });
      return true;
    } catch (cause) {
      emit({ error: message(cause) });
      return false;
    } finally {
      emit({ saving: false });
    }
  }, []);

  return { ...snapshot, reload, save, reset };
}

/**
 * 只要「按哪个时区显示时间」这一个答案时的轻量入口。
 *
 * <p>返回的永远是**可渲染**的时区：存下来的值万一不可渲染（理论上服务端已经拦过，
 * 但客户端不该假设服务端永远是对的），这里兜一层。
 */
export function useDisplayTimeZone(): string {
  const { timezone } = useAccountLocale();
  return timezone;
}
