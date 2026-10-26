'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState } from '@starcon/ui';
import { BellRing, Clock, Plus, RefreshCw, Save, Trash2 } from 'lucide-react';
import type {
  NotificationPreferenceCapabilities,
  NotificationPreferenceResolution,
  NotificationPreferenceView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import {
  CHANNEL_LABELS,
  MAX_QUIET_WINDOWS,
  PREFERENCE_CHANNELS,
  buildPreferenceInput,
  describeTimezoneSource,
  describeWindow,
  groupTimezones,
  isValidWindowText,
  listTimezones,
  mandatorySummary,
  normalizeWindowText,
  quietStateOf,
  selectionOf,
  summarizePreference,
  toggleChannel,
  type ChannelSelection,
  type PreferenceChannel,
} from './notification-preference-model';

/**
 * 1.3.1.2 通知偏好（卷 01 · L3 1.3.1 消息与预警 → {@code /notifications/preferences}）。
 *
 * <p>蓝图原文：「用户按通知类型选择接收渠道（站内/邮件/企业 IM）与免打扰时段。
 * ……输出：偏好配置；供 1.3.2.2 推送时过滤。
 * ……**关键业务通知（超期发货、账务差异）不允许被完全关闭**，系统强制保留下不可关闭的底线项。」
 *
 * <p>🔴 **本页只做 1.3.1.2 这一格**：不把兄弟模块 1.3.1.1「通知列表」（收件箱）
 * 与 1.3.2.x「预警规则 / 外部渠道推送」包进来 —— 那几格各有自己的职责。
 *
 * <p>🔴 **底线项是服务端的事**：关键业务通知的「站内」渠道在界面上不可取消，
 * 但**真正的闸在服务端**（写入时 400、读取时兜底补回）——
 * 只把复选框置灰的话，绕过前端直接打接口就能把关键通知关掉。
 *
 * <p>🔴 **免打扰是「当地时刻 + 时区」语义**：时段是「当地几点到几点」，
 * 不是 UTC 时刻、也不是「距离现在几小时」。「现在算不算在免打扰时段里」由服务端按
 * 用户自己的时区判定（{@code /resolve}），前端不自己算 —— 算错了就会差几小时。
 *
 * <p>🔴 **本模块不实现推送**：外部渠道投递属于 1.3.2.2（未交付）。
 * 本页只负责把偏好配置存好并可读。
 */

/** 页面里的一条提示。 */
interface Notice {
  kind: 'error' | 'info';
  text: string;
}

/** 从 ApiError 里取出能直接显示的原因。 */
function messageOf(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  return '操作失败，请稍后重试';
}

export function NotificationPreferenceWorkspace() {
  const [capabilities, setCapabilities] =
    useState<NotificationPreferenceCapabilities | null>(null);
  const [view, setView] = useState<NotificationPreferenceView | null>(null);
  const [selection, setSelection] = useState<ChannelSelection>({});
  const [windows, setWindows] = useState<string[]>([]);
  const [timezone, setTimezone] = useState('');
  const [windowDraft, setWindowDraft] = useState('');
  const [quietNow, setQuietNow] = useState(false);
  const [quietWindows, setQuietWindows] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [notices, setNotices] = useState<Notice[]>([]);

  const notice = (kind: Notice['kind'], text: string) =>
    setNotices((current) => [...current.filter((item) => item.kind !== kind), { kind, text }]);

  /** 把服务端下发的生效值铺到表单上。 */
  const applyView = useCallback((current: NotificationPreferenceView) => {
    setView(current);
    setSelection(selectionOf(current));
    setWindows([...current.windows]);
    setTimezone(current.timezone);
  }, []);

  /**
   * 读一次「现在是不是免打扰时段」。
   *
   * <p>🔴 免打扰时段是**全局**的（不是每类通知各设一套），所以问一次就够。
   * 判定在服务端按用户自己的时区做 —— 前端自己算会差几个小时。
   */
  const refreshQuietState = useCallback(async (type: string) => {
    const resolution = await api<NotificationPreferenceResolution>(
      'identity/notification-preferences/resolve',
      'POST',
      { type },
    );
    const state = quietStateOf(resolution);
    setQuietNow(state.quietNow);
    setQuietWindows(state.quietWindows);
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setNotices([]);
    try {
      const [caps, current] = await Promise.all([
        api<NotificationPreferenceCapabilities>('identity/notification-preferences/capabilities'),
        api<NotificationPreferenceView>('identity/notification-preferences'),
      ]);
      setCapabilities(caps);
      applyView(current);
      const probe = caps.types[0]?.code ?? 'MESSAGE';
      await refreshQuietState(probe);
    } catch (error) {
      notice('error', messageOf(error));
    } finally {
      setLoading(false);
    }
  }, [applyView, refreshQuietState]);

  useEffect(() => {
    void load();
  }, [load]);

  const save = useCallback(async () => {
    setSaving(true);
    setNotices([]);
    try {
      const saved = await api<NotificationPreferenceView>(
        'identity/notification-preferences',
        'PUT',
        buildPreferenceInput(selection, windows, timezone),
      );
      applyView(saved);
      await refreshQuietState(capabilities?.types[0]?.code ?? 'MESSAGE');
      notice('info', '通知偏好已保存。');
    } catch (error) {
      notice('error', messageOf(error));
    } finally {
      setSaving(false);
    }
  }, [applyView, capabilities, refreshQuietState, selection, timezone, windows]);

  const reset = useCallback(async () => {
    setSaving(true);
    setNotices([]);
    try {
      const current = await api<NotificationPreferenceView>(
        'identity/notification-preferences',
        'DELETE',
      );
      applyView(current);
      await refreshQuietState(capabilities?.types[0]?.code ?? 'MESSAGE');
      notice('info', '已恢复默认通知偏好。');
    } catch (error) {
      notice('error', messageOf(error));
    } finally {
      setSaving(false);
    }
  }, [applyView, capabilities, refreshQuietState]);

  const summary = useMemo(() => (view ? summarizePreference(view) : null), [view]);
  const timezoneGroups = useMemo(
    () => groupTimezones(listTimezones(), view?.timezone ?? timezone),
    [timezone, view?.timezone],
  );

  const draftValid = isValidWindowText(windowDraft);
  const addWindow = () => {
    const normalized = normalizeWindowText(windowDraft);
    if (!normalized) {
      notice('error', '免打扰时段要写成「当地 22:00-08:00」这样的形状（跨午夜就对跨午夜）。');
      return;
    }
    if (windows.length >= MAX_QUIET_WINDOWS) {
      notice('error', `免打扰时段最多 ${MAX_QUIET_WINDOWS} 段。`);
      return;
    }
    if (windows.includes(normalized)) {
      notice('error', `免打扰时段重复：${normalized}`);
      return;
    }
    setWindows((current) => [...current, normalized].sort());
    setWindowDraft('');
  };

  if (loading) {
    return (
      <p className="text-copy-13 text-muted-foreground p-8 text-center">正在加载通知偏好…</p>
    );
  }

  if (!view) {
    return (
      <div className="space-y-4 p-4">
        {notices.map((item) => (
          <div
            key={item.kind}
            role="alert"
            className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
          >
            {item.text}
            <Button variant="link" onClick={() => void load()}>
              重试
            </Button>
          </div>
        ))}
        <EmptyState
          icon={<BellRing className="h-6 w-6" />}
          title="读不到通知偏好"
          description="偏好服务暂时没有响应。这不是「你没有偏好」，而是没读到 —— 重试一次。"
          action={
            <Button variant="outline" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              重试
            </Button>
          }
        />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          通知偏好决定<b>每一类通知走哪些渠道</b>（站内 / 邮件 / 企业 IM），
          以及<b>什么时段别来打扰我</b>。它是<b>个人设置</b>，按成员存放 ——
          同事的偏好与你无关，租户与数据范围全部由服务端按已认证的成员关系判定。
        </p>
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => void load()} disabled={saving}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          <Button variant="outline" size="sm" onClick={() => void reset()} disabled={saving}>
            <Trash2 className="h-4 w-4" />
            恢复默认
          </Button>
          <Button size="sm" onClick={() => void save()} disabled={saving}>
            <Save className="h-4 w-4" />
            {saving ? '保存中…' : '保存'}
          </Button>
        </div>
      </div>

      {notices.map((item) => (
        <p
          key={item.kind}
          role={item.kind === 'error' ? 'alert' : 'status'}
          className={
            item.kind === 'error'
              ? 'border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-copy-12'
              : 'border-warning/40 bg-warning/5 rounded-md border p-3 text-copy-12'
          }
        >
          {item.text}
        </p>
      ))}

      {view.fallbackReasons.length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-md border p-3 text-copy-12">
          <b>存下来的偏好有一部分没被采纳，已按下面这样兜底：</b>
          <ul className="mt-1 list-disc pl-5">
            {view.fallbackReasons.map((reason) => (
              <li key={reason}>{reason}</li>
            ))}
          </ul>
        </div>
      )}

      {/* ---------------- 概览 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-16 font-semibold">当前生效</h2>
        <div className="flex flex-wrap gap-2">
          <span className="rounded-md border px-3 py-2 text-copy-12">
            通知类型 <b className="ml-1">{summary?.typeCount ?? 0}</b> 类
          </span>
          <span className="rounded-md border px-3 py-2 text-copy-12">
            关键业务通知 <b className="ml-1">{summary?.mandatoryCount ?? 0}</b> 类
            <span className="text-muted-foreground ml-2">（不允许被完全关闭）</span>
          </span>
          <span className="rounded-md border px-3 py-2 text-copy-12">
            只留站内 <b className="ml-1">{summary?.inboxOnlyCount ?? 0}</b> 类
            <span className="text-muted-foreground ml-2">（外部渠道全关）</span>
          </span>
          <span className="rounded-md border px-3 py-2 text-copy-12">
            免打扰时段 <b className="ml-1">{summary?.quietWindowCount ?? 0}</b> 段
          </span>
          <span
            className={
              quietNow
                ? 'border-warning/40 bg-warning/5 rounded-md border px-3 py-2 text-copy-12'
                : 'rounded-md border px-3 py-2 text-copy-12'
            }
          >
            现在{' '}
            {quietNow ? (
              <Badge tone="warning">处于免打扰时段</Badge>
            ) : (
              <Badge tone="success">不在免打扰时段</Badge>
            )}
          </span>
        </div>
        <p className="text-copy-12 text-muted-foreground">
          免打扰时段按 <b>{view.timezone}</b> 计算（{describeTimezoneSource(view)}）。
          免打扰<b>只压制外部渠道</b>（邮件 / 企业 IM），
          站内通知照常进收件箱 —— 半夜一封邮件才是打扰。
          {view.stored ? (
            <>最后保存于 {view.updatedAt ? view.updatedAt.replace('T', ' ').slice(0, 19) : '—'}。</>
          ) : (
            <>你还没保存过通知偏好，当前显示的是<b>默认值</b>。</>
          )}
        </p>
      </section>

      {/* ---------------- 接收渠道 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-16 font-semibold">接收渠道（按通知类型）</h2>
        {view.types.length === 0 ? (
          <EmptyState
            icon={<BellRing className="h-6 w-6" />}
            title="没有可配置的通知类型"
            description="服务端下发的通知类型词表是空的 —— 刷新一次看看是不是服务还没起来。"
          />
        ) : (
          <div className="space-y-2">
            {view.types.map((setting) => {
              const checked = selection[setting.type] ?? [];
              return (
                <div key={setting.type} className="rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-13 font-medium">{setting.label}</span>
                    <span className="text-copy-12 text-muted-foreground">{setting.type}</span>
                    {setting.mandatory && (
                      <Badge tone="warning">关键业务通知 · 不可完全关闭</Badge>
                    )}
                  </div>
                  <div className="mt-2 flex flex-wrap gap-4">
                    {PREFERENCE_CHANNELS.map((code) => {
                      const isBaseline = setting.baselineChannel === code;
                      const isChecked = checked.includes(code);
                      const isLast = isChecked && checked.length <= 1;
                      const locked = isBaseline || isLast;
                      return (
                        <label
                          key={code}
                          className={
                            locked ? 'text-copy-12 text-muted-foreground flex items-center gap-1' : 'flex items-center gap-1 text-copy-12'
                          }
                        >
                          <input
                            type="checkbox"
                            checked={isChecked}
                            disabled={locked}
                            onChange={() =>
                              setSelection((current) =>
                                toggleChannel(current, setting.type, code, setting.baselineChannel),
                              )
                            }
                          />
                          {CHANNEL_LABELS[code]}
                          {isBaseline && (
                            <span className="text-muted-foreground">（底线渠道，不可关闭）</span>
                          )}
                          {!isBaseline && isLast && (
                            <span className="text-muted-foreground">
                              （渠道集合不能为空，至少留一个）
                            </span>
                          )}
                        </label>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>

      {/* ---------------- 免打扰时段 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-16 font-semibold">免打扰时段</h2>
        <p className="text-copy-12 text-muted-foreground">
          时段是<b>当地时间</b>（按上面那个时区算），写成「当地几点到几点」，
          例如 <b>22:00-08:00</b> 表示夜里十点到<b>次日</b>早上八点。
          时段里<b>不推外部渠道</b>，站内通知照常进收件箱。
        </p>

        <div className="rounded-md border p-3">
          <label className="text-copy-12 flex flex-wrap items-center gap-2">
            <span>时区</span>
            <select
              className="rounded-md border px-2 py-1 text-copy-12"
              value={timezone}
              onChange={(event) => setTimezone(event.target.value)}
            >
              {timezoneGroups.map((group) => (
                <optgroup key={group.region} label={group.region}>
                  {group.zones.map((zone) => (
                    <option key={zone} value={zone}>
                      {zone}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
            <span className="text-muted-foreground">{describeTimezoneSource(view)}</span>
          </label>
        </div>

        <ul className="space-y-1">
          {windows.length === 0 && (
            <li className="text-copy-12 text-muted-foreground">
              没有设置免打扰时段 —— 外部渠道随时都会推。
            </li>
          )}
          {windows.map((window) => (
            <li
              key={window}
              className="text-copy-12 flex flex-wrap items-center gap-2 rounded-md border p-2"
            >
              <Clock className="h-4 w-4" />
              <span className="font-medium">{window}</span>
              <span className="text-muted-foreground">{describeWindow(window)}</span>
              {quietWindows.includes(window) && <Badge tone="warning">现在正命中这一段</Badge>}
              <Button
                variant="ghost"
                size="sm"
                className="ml-auto"
                onClick={() => setWindows((current) => current.filter((item) => item !== window))}
              >
                <Trash2 className="h-4 w-4" />
                移除
              </Button>
            </li>
          ))}
        </ul>

        <div className="flex flex-wrap items-center gap-2">
          <input
            className="rounded-md border px-2 py-1 text-copy-12"
            placeholder="22:00-08:00"
            value={windowDraft}
            onChange={(event) => setWindowDraft(event.target.value)}
          />
          <Button variant="outline" size="sm" onClick={addWindow} disabled={!draftValid}>
            <Plus className="h-4 w-4" />
            添加时段
          </Button>
          {!draftValid && windowDraft.length > 0 && (
            <span className="text-copy-12 text-destructive">
              形状不对：要写成「当地 22:00-08:00」，起止不能相同。
            </span>
          )}
        </div>
      </section>

      {/* ---------------- 底线项与边界 ---------------- */}
      {capabilities && (
        <section className="space-y-2">
          <h2 className="text-copy-16 font-semibold">不可关闭的底线项</h2>
          <ul className="space-y-1">
            {mandatorySummary(capabilities).map((line) => (
              <li key={line} className="text-copy-12 rounded-md border p-2">
                {line}
              </li>
            ))}
          </ul>
          <p className="text-copy-12 text-muted-foreground">
            这条纪律落在<b>服务端</b>：写入时关掉底线渠道会被拒（400），
            读回来时底线渠道也一定在 —— 不只是把界面上的复选框置灰。
          </p>
          <ul className="space-y-1">
            {capabilities.boundaries.map((line) => (
              <li key={line} className="text-copy-12 text-muted-foreground">
                · {line}
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
