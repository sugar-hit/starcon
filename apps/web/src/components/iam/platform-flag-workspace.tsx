'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { Plus, RefreshCw, Search } from 'lucide-react';
import type {
  PlatformFlagCapabilities,
  PlatformFlagEvaluationView,
  PlatformFlagExceptionView,
  PlatformFlagHistoryView,
  PlatformFlagSettingView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import { useIdentity } from '../iam/identity-shell';
import {
  ACTION_LABELS,
  FLAG_KIND_LABELS,
  LAYER_LABELS,
  announcementWindowState,
  describeEvaluation,
  formatPercent,
  fromLocalInputValue,
  groupByKind,
  platformSummary,
  reasonLabel,
  rolloutExplanation,
  toLocalInputValue,
  validateExceptionInput,
  validateSettingInput,
  type SettingDraft,
} from './platform-flag-model';

/**
 * 17.7.2.1 平台参数与开关。
 *
 * <p>🔴 **三层裁决必须看得见**：本页的每一行都把「平台全局值 / 租户例外 / 最终生效值」
 * 并列写出来，而不是只给一个「开 / 关」。平台关掉、平台单独禁用这个租户、
 * 这个租户没进灰度分桶 —— 三件事的处置完全不同，只给布尔值的话用户没法判断该怎么办。
 *
 * <p>🔴 **租户侧只读**：本页能改平台全局层的前提是持有
 * {@code identity.platform_flag.update}，而该权限码在迁移里**不默认授给任何角色**
 * （蓝图：17.7 平台运营不对租户开放）。没有权限时按钮不出现，而不是点了吃 403。
 *
 * <p>四种状态各有各的文案：加载中 / 整体加载失败 / 平台还没配任何开关 /
 * 局部读不到（全局配置读到了，但例外或判定读不到）—— 最后一种不是「重试就好」，
 * 它说明权限或下游有问题，必须单独说出来。
 */

/** 平台运营可管理的目标租户（子租户）形状。 */
type ChildTenant = { id: string; code: string; name: string; status: string };

export function PlatformFlagWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  /** 局部读不到：全局配置读到了，但例外 / 判定 / 子租户读不到。 */
  const [partial, setPartial] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const [settings, setSettings] = useState<PlatformFlagSettingView[]>([]);
  const [capabilities, setCapabilities] = useState<PlatformFlagCapabilities | null>(null);
  const [exceptions, setExceptions] = useState<PlatformFlagExceptionView[]>([]);
  const [evaluations, setEvaluations] = useState<PlatformFlagEvaluationView[]>([]);
  const [history, setHistory] = useState<PlatformFlagHistoryView[]>([]);
  const [childTenants, setChildTenants] = useState<ChildTenant[]>([]);

  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<PlatformFlagSettingView | null>(null);
  const [creatingException, setCreatingException] = useState(false);
  const [targetTenant, setTargetTenant] = useState('');

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const readable = can('identity.platform_flag.read');

  const load = useCallback(async () => {
    setError('');
    setPartial('');
    if (!readable) {
      // 没有读权限就不请求：空数组会被渲染成「平台还没有配置任何开关」，那是骗人。
      setReady(true);
      return;
    }
    // 全局配置、例外与判定分开取：前者读不到是整体失败，后者读不到是局部失败，
    // 两者的处置不同，文案也不能一样。
    const global = await Promise.allSettled([
      api<PlatformFlagSettingView[]>('identity/platform-flags/settings'),
      api<PlatformFlagCapabilities>('identity/platform-flags/capabilities'),
      api<PlatformFlagHistoryView[]>('identity/platform-flags/history'),
    ]);
    if (global[0].status === 'rejected') {
      setError(message(global[0].reason));
      setReady(true);
      return;
    }
    setSettings(global[0].value);
    setCapabilities(global[1].status === 'fulfilled' ? global[1].value : null);
    setHistory(global[2].status === 'fulfilled' ? global[2].value : []);

    const codes = global[0].value.map((row) => row.flagCode).join(',');
    const local = await Promise.allSettled([
      api<PlatformFlagExceptionView[]>('identity/platform-flags/exceptions'),
      codes
        ? api<PlatformFlagEvaluationView[]>(
            `identity/platform-flags/evaluate-batch?keys=${encodeURIComponent(codes)}`,
          )
        : Promise.resolve([] as PlatformFlagEvaluationView[]),
      api<ChildTenant[]>('identity/children'),
    ]);
    const problems: string[] = [];
    if (local[0].status === 'rejected') problems.push(`按租户禁用的例外数据（${message(local[0].reason)}）`);
    else setExceptions(local[0].value);
    if (local[1].status === 'rejected') problems.push(`功能可用性判定（${message(local[1].reason)}）`);
    else setEvaluations(local[1].value);
    if (local[2].status === 'rejected') problems.push(`可管理的子租户列表（${message(local[2].reason)}）`);
    else setChildTenants(local[2].value);
    setPartial(problems.join('；'));
    setReady(true);
  }, [readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const evaluationByCode = useMemo(() => {
    const map = new Map<string, PlatformFlagEvaluationView>();
    for (const row of evaluations) map.set(row.flagCode, row);
    return map;
  }, [evaluations]);

  const term = search.trim().toLowerCase();
  const visibleSettings = useMemo(
    () =>
      settings.filter((row) =>
        `${row.flagCode} ${row.name} ${row.flagKind}`.toLowerCase().includes(term),
      ),
    [settings, term],
  );

  async function releaseException(row: PlatformFlagExceptionView) {
    if (!window.confirm(`撤销「${row.flagName}」对本租户的禁用？撤销后本租户回到平台口径。`))
      return;
    setBusy(true);
    setError('');
    try {
      await api(
        `identity/platform-flags/exceptions/${row.id}?tenantId=${row.tenantId}`,
        'DELETE',
      );
      setNotice('禁用例外已撤销');
      await load();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载平台参数与开关…
      </p>
    );

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有平台参数查看权限，请联系平台运营开通。
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          平台开关与租户参数（业务参数）<b>分层</b>：平台全局值全平台唯一，租户只能在
          「被平台单独禁用」这一个方向上收紧，<b>不能覆盖平台开关</b>。
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <label className="relative flex items-center">
            <Search className="text-muted-foreground pointer-events-none absolute left-2.5 h-4 w-4" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索开关"
              aria-label="搜索开关"
              className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 w-48 rounded-md border pl-8 pr-2 outline-none"
            />
          </label>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        </div>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          平台参数加载失败：{error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}

      {partial && (
        <div
          role="status"
          className="border-warning/30 bg-warning/5 text-warning rounded-md border p-3 text-sm"
        >
          部分数据读不到：{partial}。其余内容照常显示，缺失的部分不能当作「没有」。
        </div>
      )}

      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}

      {/* ---------------- 能力声明 ---------------- */}
      {capabilities && (
        <section className="bg-card space-y-2 rounded-lg border p-4">
          <p className="text-copy-14 font-medium">能力声明</p>
          <ul className="space-y-1">
            {capabilities.dimensions.map((dimension) => (
              <li key={dimension.dimension} className="text-copy-12 flex flex-wrap items-center gap-2">
                <Badge tone={dimension.available ? 'success' : 'warning'}>
                  {dimension.available ? '已交付' : '未交付'}
                </Badge>
                <span className="font-medium">{dimension.dimension}</span>
                <span className="text-muted-foreground">{dimension.description}</span>
                {!dimension.available && (
                  <span className="text-muted-foreground">
                    归属模块：{dimension.ownerModule}
                    {dimension.impact ? ` · ${dimension.impact}` : ''}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ---------------- 平台全局层 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">平台全局层</h2>
          <p className="text-copy-12 text-muted-foreground">
            全平台唯一一份，不带租户维度。灰度放量用稳定哈希分桶，同一租户反复请求结果恒定。
          </p>
        </div>
        {visibleSettings.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              {settings.length === 0
                ? '平台还没有定义任何开关或参数，请联系平台运营在数据库迁移中补充'
                : '没有匹配当前搜索的开关'}
            </p>
          </div>
        ) : (
          groupByKind(visibleSettings).map((group) => (
            <div key={group.kind} className="space-y-2">
              <p className="text-copy-12 text-muted-foreground">{group.label}</p>
              <ul className="space-y-2">
                {group.rows.map((row) => {
                  const verdict = evaluationByCode.get(row.flagCode);
                  return (
                    <li key={row.flagCode} className="bg-card rounded-md border p-3">
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <div className="min-w-0">
                          <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                            {row.name}
                            <Badge tone={row.enabled ? 'success' : 'neutral'}>
                              {row.enabled ? '已启用' : '已关闭'}
                            </Badge>
                            {!row.configured && <Badge tone="warning">平台尚未配置</Badge>}
                          </p>
                          <p className="text-copy-12 text-muted-foreground mt-1">
                            <span className="font-mono">{row.flagCode}</span>
                            {row.note ? ` · ${row.note}` : ''}
                          </p>
                        </div>
                        <div className="text-right">
                          <p className="text-copy-14 font-medium">
                            {platformSummary({
                              flagKind: row.flagKind,
                              configured: row.configured,
                              enabled: row.enabled,
                              rolloutPercent: row.rolloutPercent,
                              rateLimitPerMinute: row.rateLimitPerMinute,
                              announcementTitle: row.announcementTitle,
                              announcementFrom: row.announcementFrom,
                              announcementTo: row.announcementTo,
                            })}
                          </p>
                          {can('identity.platform_flag.update') && (
                            <Button size="sm" variant="ghost" onClick={() => setEditing(row)}>
                              编辑
                            </Button>
                          )}
                        </div>
                      </div>

                      {/* ---------------- 三层裁决 ---------------- */}
                      {verdict && (
                        <dl className="border-border mt-3 grid gap-2 border-t pt-3 text-copy-12 md:grid-cols-3">
                          <div>
                            <dt className="text-muted-foreground">{LAYER_LABELS.platform}</dt>
                            <dd>
                              {platformSummary({
                                flagKind: row.flagKind,
                                configured: verdict.platformLayer.configured,
                                enabled: verdict.platformLayer.enabled,
                                rolloutPercent: verdict.platformLayer.rolloutPercent,
                                rateLimitPerMinute: verdict.platformLayer.rateLimitPerMinute,
                                announcementTitle: verdict.platformLayer.announcementTitle,
                                announcementFrom: verdict.platformLayer.announcementFrom,
                                announcementTo: verdict.platformLayer.announcementTo,
                              })}
                            </dd>
                          </div>
                          <div>
                            <dt className="text-muted-foreground">{LAYER_LABELS.tenant}</dt>
                            <dd>
                              {!verdict.tenantLayer.applicable
                                ? '本类不接受按租户禁用'
                                : verdict.tenantLayer.present
                                  ? `已禁用（${verdict.tenantLayer.reason ?? '未填原因'}）`
                                  : '无例外'}
                            </dd>
                          </div>
                          <div>
                            <dt className="text-muted-foreground">{LAYER_LABELS.effective}</dt>
                            <dd>
                              <Badge tone={verdict.effectiveLayer.enabled ? 'success' : 'danger'}>
                                {verdict.effectiveLayer.enabled ? '可用' : '不可用'}
                              </Badge>{' '}
                              {reasonLabel(verdict.effectiveLayer.reason)}
                            </dd>
                          </div>
                          {verdict.effectiveLayer.rolloutBucket !== null && (
                            <div className="text-muted-foreground md:col-span-3">
                              {rolloutExplanation({
                                rolloutBucket: verdict.effectiveLayer.rolloutBucket,
                                rolloutBasisPoints: verdict.effectiveLayer.rolloutBasisPoints,
                              })}
                            </div>
                          )}
                          {verdict.flagKind === 'ANNOUNCEMENT' && (
                            <div className="text-muted-foreground md:col-span-3">
                              公告状态：
                              {announcementWindowState(
                                verdict.platformLayer.announcementFrom,
                                verdict.platformLayer.announcementTo,
                              )}
                            </div>
                          )}
                        </dl>
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          ))
        )}
      </section>

      {/* ---------------- 按租户禁用 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">按租户禁用</h2>
          <p className="text-copy-12 text-muted-foreground">
            租户例外只能<b>收紧</b>：平台禁用的功能租户打不开，租户也开不了平台没给的东西。
          </p>
        </div>
        <div className="bg-card space-y-2 rounded-lg border p-4">
          <div className="grid gap-3 md:grid-cols-2">
            <Field label="目标租户" hint="平台运营可管理的子租户">
              <select
                value={targetTenant}
                onChange={(e) => setTargetTenant(e.target.value)}
                aria-label="选择目标租户"
                className={inputClass}
              >
                <option value="">当前租户（我自己）</option>
                {childTenants.map((child) => (
                  <option key={child.id} value={child.id}>
                    {child.code} · {child.name}
                  </option>
                ))}
              </select>
            </Field>
            {can('identity.platform_flag.create') && (
              <Button
                size="sm"
                variant="outline"
                disabled={busy || !targetTenant}
                onClick={() => setCreatingException(true)}
              >
                <Plus className="h-4 w-4" />
                新增禁用例外
              </Button>
            )}
          </div>
        </div>

        {exceptions.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              当前没有针对本租户的禁用例外
            </p>
          </div>
        ) : (
          <ul className="space-y-2">
            {exceptions.map((row) => (
              <li key={row.id} className="bg-card rounded-md border p-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                      {row.flagName}
                      <Badge tone="danger">{row.effect === 'DISABLED' ? '已禁用' : row.effect}</Badge>
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      {row.reason} · {row.createdAt}
                    </p>
                  </div>
                  {can('identity.platform_flag.delete') && (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={busy}
                      onClick={() => void releaseException(row)}
                    >
                      撤销
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ---------------- 变更历史 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">变更历史</h2>
          <p className="text-copy-12 text-muted-foreground">
            平台全局层只追加、不可修改。最近 {history.length} 条。
          </p>
        </div>
        {history.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">还没有平台参数变更记录</p>
          </div>
        ) : (
          <ol className="space-y-2">
            {history.map((row) => (
              <li key={row.id} className="bg-card rounded-md border p-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <p className="text-copy-13">
                    <span className="font-mono">{row.flagCode}</span>{' '}
                    {ACTION_LABELS[row.action] ?? row.action}
                    {row.beforeRolloutPercent !== null &&
                    row.afterRolloutPercent !== null && (
                      <span className="text-muted-foreground">
                        {' '}
                        放量 {formatPercent(row.beforeRolloutPercent)} →{' '}
                        {formatPercent(row.afterRolloutPercent)}
                      </span>
                    )}
                  </p>
                  <div className="text-copy-12 text-muted-foreground text-right">
                    <p>{row.operatorName ?? '—'}</p>
                    <p>{row.changedAt}</p>
                  </div>
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>

      {creatingException && (
        <ExceptionForm
          settings={settings}
          targetTenant={targetTenant}
          childTenants={childTenants}
          busy={busy}
          onCancel={() => setCreatingException(false)}
          onSubmit={async (flagCode, reason) => {
            setBusy(true);
            setError('');
            try {
              await api('identity/platform-flags/exceptions', 'POST', {
                flagCode,
                tenantId: targetTenant,
                reason,
              });
              setNotice('已新增按租户禁用例外');
              setCreatingException(false);
              await load();
            } catch (e) {
              setError(message(e));
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      {editing && (
        <SettingForm
          setting={editing}
          busy={busy}
          onCancel={() => setEditing(null)}
          onSubmit={async (draft) => {
            setBusy(true);
            setError('');
            try {
              await api(`identity/platform-flags/settings/${editing.flagCode}`, 'PUT', {
                enabled: draft.enabled,
                rolloutPercent: Number(draft.rolloutPercent),
                rateLimitPerMinute:
                  draft.rateLimitPerMinute.trim() === ''
                    ? null
                    : Number(draft.rateLimitPerMinute),
                announcementTitle: draft.announcementTitle.trim() || null,
                announcementBody: draft.announcementBody.trim() || null,
                announcementFrom: fromLocalInputValue(draft.announcementFrom),
                announcementTo: fromLocalInputValue(draft.announcementTo),
                note: draft.note,
              });
              setNotice('平台全局配置已更新');
              setEditing(null);
              await load();
            } catch (e) {
              setError(message(e));
            } finally {
              setBusy(false);
            }
          }}
        />
      )}
    </div>
  );
}

// ------------------------------------------------------------------
// 按租户禁用表单
// ------------------------------------------------------------------

function ExceptionForm({
  settings,
  targetTenant,
  childTenants,
  busy,
  onCancel,
  onSubmit,
}: {
  settings: PlatformFlagSettingView[];
  targetTenant: string;
  childTenants: ChildTenant[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (flagCode: string, reason: string) => Promise<void>;
}) {
  // 只有功能开关接受按租户禁用：限流阈值与公告禁用了等于放宽限制。
  const modules = settings.filter((row) => row.flagKind === 'MODULE');
  const [flagCode, setFlagCode] = useState(modules[0]?.flagCode ?? '');
  const [reason, setReason] = useState('');
  const selected = modules.find((row) => row.flagCode === flagCode);
  const problem = validateExceptionInput(selected?.flagKind ?? 'MODULE', reason);
  const target = childTenants.find((child) => child.id === targetTenant);

  return (
    <div className="bg-card space-y-3 rounded-lg border p-4">
      <p className="text-copy-14 font-medium">
        新增按租户禁用例外
        <span className="text-copy-12 text-muted-foreground ml-2">
          目标租户：{target ? `${target.code} · ${target.name}` : targetTenant}
        </span>
      </p>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="功能开关" hint="只有功能开关可以按租户禁用">
          <select
            value={flagCode}
            onChange={(e) => setFlagCode(e.target.value)}
            aria-label="选择功能开关"
            className={inputClass}
          >
            {modules.map((row) => (
              <option key={row.flagCode} value={row.flagCode}>
                {row.name} · {row.flagCode}
              </option>
            ))}
          </select>
        </Field>
        <Field label="禁用原因" hint="必填：事后没人说得清为什么这个租户没有这个功能">
          <input
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            className={inputClass}
          />
        </Field>
      </div>
      {modules.length === 0 && (
        <p role="alert" className="text-destructive text-copy-12">
          平台还没有可禁用的功能开关
        </p>
      )}
      {problem && modules.length > 0 && (
        <p role="alert" className="text-destructive text-copy-12">
          {problem}
        </p>
      )}
      <div className="flex items-center gap-2">
        <Button
          size="sm"
          disabled={busy || problem !== '' || modules.length === 0}
          onClick={() => void onSubmit(flagCode, reason.trim())}
        >
          确认禁用
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------
// 平台全局层编辑表单
// ------------------------------------------------------------------

function SettingForm({
  setting,
  busy,
  onCancel,
  onSubmit,
}: {
  setting: PlatformFlagSettingView;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (draft: SettingDraft) => Promise<void>;
}) {
  const [draft, setDraft] = useState<SettingDraft>({
    enabled: setting.enabled,
    rolloutPercent: String(setting.rolloutPercent ?? '0.00'),
    rateLimitPerMinute:
      setting.rateLimitPerMinute === null ? '' : String(setting.rateLimitPerMinute),
    announcementTitle: setting.announcementTitle ?? '',
    announcementBody: setting.announcementBody ?? '',
    announcementFrom: toLocalInputValue(setting.announcementFrom),
    announcementTo: toLocalInputValue(setting.announcementTo),
    note: '',
  });
  const problem = validateSettingInput(setting.flagKind, draft);

  return (
    <div className="bg-card space-y-3 rounded-lg border p-4">
      <p className="text-copy-14 font-medium">
        编辑 {setting.name}
        <span className="text-copy-12 text-muted-foreground ml-2 font-mono">
          {setting.flagCode} · {FLAG_KIND_LABELS[setting.flagKind] ?? setting.flagKind}
        </span>
      </p>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="平台开关" hint="功能总闸 / 公告是否发布">
          <select
            value={draft.enabled ? '1' : '0'}
            onChange={(e) => setDraft({ ...draft, enabled: e.target.value === '1' })}
            aria-label="平台开关"
            className={inputClass}
          >
            <option value="1">开启</option>
            <option value="0">关闭</option>
          </select>
        </Field>
        <Field label="灰度放量比例（%）" hint="同一租户按稳定哈希分桶，反复请求恒定">
          <input
            value={draft.rolloutPercent}
            onChange={(e) => setDraft({ ...draft, rolloutPercent: e.target.value })}
            className={inputClass}
          />
        </Field>
        {setting.flagKind === 'RATE_LIMIT' && (
          <Field label="限流阈值（次/分钟）" hint="必须显式给出，系统不给默认值">
            <input
              value={draft.rateLimitPerMinute}
              onChange={(e) => setDraft({ ...draft, rateLimitPerMinute: e.target.value })}
              className={inputClass}
            />
          </Field>
        )}
        {setting.flagKind === 'ANNOUNCEMENT' && (
          <>
            <Field label="公告标题">
              <input
                value={draft.announcementTitle}
                onChange={(e) => setDraft({ ...draft, announcementTitle: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="公告正文">
              <textarea
                value={draft.announcementBody}
                onChange={(e) => setDraft({ ...draft, announcementBody: e.target.value })}
                rows={3}
                className={inputClass}
              />
            </Field>
            <Field label="生效开始">
              <input
                type="datetime-local"
                value={draft.announcementFrom}
                onChange={(e) => setDraft({ ...draft, announcementFrom: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="生效结束">
              <input
                type="datetime-local"
                value={draft.announcementTo}
                onChange={(e) => setDraft({ ...draft, announcementTo: e.target.value })}
                className={inputClass}
              />
            </Field>
          </>
        )}
        <Field label="备注" hint="写清为什么改，事后才说得清">
          <input
            value={draft.note}
            onChange={(e) => setDraft({ ...draft, note: e.target.value })}
            className={inputClass}
          />
        </Field>
      </div>
      {problem && (
        <p role="alert" className="text-destructive text-copy-12">
          {problem}
        </p>
      )}
      <div className="flex items-center gap-2">
        <Button size="sm" disabled={busy || problem !== ''} onClick={() => void onSubmit(draft)}>
          保存
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------
// 小组件
// ------------------------------------------------------------------

const inputClass =
  'border-border bg-card text-copy-13 focus-visible:border-ring h-8 w-full rounded-md border px-2 outline-none';

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block space-y-1">
      <span className="text-copy-12 block font-medium">{label}</span>
      {children}
      {hint && <span className="text-copy-12 text-muted-foreground block">{hint}</span>}
    </label>
  );
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : '请求失败，请重试';
}
