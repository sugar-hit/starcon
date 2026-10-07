'use client';
import { useCallback, useEffect, useState } from 'react';
import { History, Plus, RefreshCw, ShieldCheck, Trash2, Workflow } from 'lucide-react';
import { Button } from '@starcon/ui';
import type {
  Access,
  LifecycleRule,
  LifecycleView,
  MetricInfo,
  RuleCondition,
  RuleInput,
  RulePreview,
  TransitionInput,
} from '../../../../../packages/api-contracts/src/catalog';
import { api, ApiError } from './api';
import { control, Editor, Field } from './fields';
import {
  lifecycleOperators as operators,
  ruleTargets,
  ruleDraftValid,
  statusLabel,
  transitionAllowed,
} from './lifecycle-model';
import { canAccessRecord } from '../iam/administration-model';

function timestamp(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}

/** 商品详情里的生命周期段：状态流转、变更历史时间线、批量规则管理。 */
export function SpuLifecycle({
  spuId,
  spuCode,
  version,
  session,
  onChanged,
  onError,
}: {
  spuId: string;
  spuCode: string;
  version: number;
  session: Access;
  onChanged: () => Promise<void>;
  onError: (e: unknown) => void;
}) {
  const can = (action: string) => canAccessRecord(session, `catalog.spu.${action}`);
  const [view, setView] = useState<LifecycleView | null>(null);
  const [metrics, setMetrics] = useState<MetricInfo[]>([]);
  const [rules, setRules] = useState<LifecycleRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [target, setTarget] = useState('');
  const [reason, setReason] = useState('');
  const [editing, setEditing] = useState<LifecycleRule | 'new' | null>(null);
  const [preview, setPreview] = useState<RulePreview | null>(null);
  const [previewFor, setPreviewFor] = useState('');
  const [confirming, setConfirming] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setFailed(false);
    setError('');
    try {
      const [next, allMetrics, allRules] = await Promise.all([
        api<LifecycleView>(`catalog/products/${spuId}/lifecycle`),
        api<MetricInfo[]>('catalog/lifecycle/metrics'),
        api<LifecycleRule[]>('catalog/lifecycle/rules'),
      ]);
      setView(next);
      setMetrics(allMetrics);
      setRules(allRules);
      setTarget('');
      setReason('');
      setConfirming('');
    } catch (e) {
      setFailed(true);
      setError(e instanceof Error ? e.message : '加载生命周期失败');
      onError(e);
    } finally {
      setLoading(false);
    }
  }, [onError, spuId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function applyTransition() {
    if (!target) return;
    setBusy(true);
    try {
      const input: TransitionInput = { toStatus: target, reason, occurredAt: null, version };
      await api<unknown>(`catalog/products/${spuId}/lifecycle`, 'POST', input);
      setNotice('状态已流转，变更记录已写入历史');
      await Promise.all([load(), onChanged()]);
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  async function removeRule(rule: LifecycleRule) {
    if (!window.confirm(`确认删除批量规则「${rule.name}」？`)) return;
    setBusy(true);
    try {
      await api(`catalog/lifecycle/rules/${rule.id}?version=${rule.version}`, 'DELETE');
      setNotice('批量规则已删除');
      await load();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  async function saveRule(input: RuleInput, id?: string) {
    setBusy(true);
    try {
      await api(
        id ? `catalog/lifecycle/rules/${id}` : 'catalog/lifecycle/rules',
        id ? 'PUT' : 'POST',
        input,
      );
      setNotice('批量规则已保存');
      setEditing(null);
      await load();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  async function runRule(rule: LifecycleRule) {
    setBusy(true);
    try {
      const result = await api<{ appliedCount: number; matchedCount: number }>(
        `catalog/lifecycle/rules/${rule.id}/run`,
        'POST',
        { ruleId: rule.id, reason: null, occurredAt: null, limit: 200 },
      );
      setNotice(
        `规则「${rule.name}」匹配 ${result.matchedCount} 个商品，已流转 ${result.appliedCount} 个`,
      );
      await Promise.all([load(), onChanged()]);
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  async function loadPreview(rule: LifecycleRule) {
    setBusy(true);
    setPreviewFor(rule.id);
    setPreview(null);
    setConfirming('');
    try {
      setPreview(await api<RulePreview>(`catalog/lifecycle/rules/${rule.id}/preview?limit=100`));
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  if (loading)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载生命周期…
      </p>
    );
  if (failed || !view)
    return (
      <div className="space-y-3">
        <p role="alert" className="text-destructive text-sm">
          {error || '生命周期加载失败'}
        </p>
        <Button variant="outline" onClick={() => void load()}>
          <RefreshCw className="h-4 w-4" />
          重新加载
        </Button>
      </div>
    );

  const available = metrics.filter((m) => m.available);
  const unavailable = metrics.filter((m) => !m.available);
  const chosen = view.transitions.find((t) => t.toStatus === target);
  const needsReason = chosen?.reasonRequired ?? true;
  const executable = transitionAllowed(view.transitions, target, reason);

  return (
    <div className="space-y-5">
      <div className="bg-card space-y-4 rounded-lg border p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <ShieldCheck className="h-4 w-4" />
            <p className="font-medium">生命周期</p>
            <span className="rounded border px-2 py-1 text-xs">
              {view.statusLabel || statusLabel(view.status) || view.status}
            </span>
          </div>
          {can('update') && (
            <Button variant="ghost" size="sm" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              刷新
            </Button>
          )}
        </div>
        {notice && (
          <p role="status" className="text-success text-sm">
            {notice}
          </p>
        )}
        {view.transitions.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            商品已停产，属于终态，不能再流转。如需重新销售，请新建 SPU。
          </p>
        ) : can('update') ? (
          <div className="grid gap-3 sm:grid-cols-3">
            <Field label="目标状态">
              <select
                className={control}
                value={target}
                onChange={(e) => setTarget(e.target.value)}
              >
                <option value="">请选择合法流转</option>
                {view.transitions.map((t) => (
                  <option key={t.toStatus} value={t.toStatus}>
                    {statusLabel(t.toStatus) ?? t.toStatus} · {t.note}
                  </option>
                ))}
              </select>
            </Field>
            <Field
              label="变更原因"
              hint={
                chosen?.requiresActiveSku
                  ? '该状态要求至少一个已启用且必填资料齐备的 SKU。'
                  : '原因会写入变更历史，最多 500 字。'
              }
            >
              <input
                className={control}
                maxLength={500}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder={needsReason ? '必填，例如：断货停售' : '可选'}
              />
            </Field>
            <div className="flex items-end">
              <Button disabled={busy || !executable} onClick={() => void applyTransition()}>
                {busy ? '提交中…' : '执行流转'}
              </Button>
            </div>
          </div>
        ) : (
          <p className="text-muted-foreground text-sm">
            当前角色只能查看状态与历史，流转需要商品档案的更新权限。
          </p>
        )}
      </div>

      <div className="bg-card rounded-lg border p-4">
        <p className="mb-3 flex items-center gap-2 font-medium">
          <History className="h-4 w-4" />
          变更历史（{view.history.length}）
        </p>
        {view.history.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            尚无状态变更记录。每次流转都会记录操作人、业务时间、系统时间与原因。
          </p>
        ) : (
          <ol className="space-y-3">
            {view.history.map((e) => (
              <li key={e.id} className="border-l-2 pl-3">
                <p className="text-sm">
                  <span className="font-medium">
                    {statusLabel(e.fromStatus) ?? e.fromStatus} →{' '}
                    {statusLabel(e.toStatus) ?? e.toStatus}
                  </span>
                  <span className="text-muted-foreground ml-2 text-xs">
                    {e.source === 'RULE' ? '批量规则' : '人工操作'} · 业务时间{' '}
                    {timestamp(e.occurredAt)} · 记录于 {timestamp(e.createdAt)}
                  </span>
                </p>
                <p className="text-muted-foreground text-sm">{e.reason || '未填写原因'}</p>
              </li>
            ))}
          </ol>
        )}
      </div>

      <div className="bg-card rounded-lg border p-4">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <p className="flex items-center gap-2 font-medium">
            <Workflow className="h-4 w-4" />
            批量流转规则（{rules.length}）
          </p>
          {can('create') && (
            <Button size="sm" onClick={() => setEditing('new')} disabled={available.length === 0}>
              <Plus className="h-4 w-4" />
              新建规则
            </Button>
          )}
        </div>
        {unavailable.length > 0 && (
          <p className="text-muted-foreground mb-3 text-xs">
            尚未接入的上游投影：{unavailable.map((m) => m.note).join('；')}。
            规则引用这些指标时会被明确拒绝执行，不会用 0 顶替未知值。
          </p>
        )}
        {rules.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            暂无批量规则。可先用 catalog 自有指标（如已启用 SKU 数、资料齐备 SKU 数）建立规则；
            库存与销量类规则需等 07 库存、13 分析接入投影后才能执行。
          </p>
        ) : (
          <ul className="space-y-3">
            {rules.map((rule) => (
              <li key={rule.id} className="border-t pt-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div>
                    <p className="text-sm font-medium">
                      {rule.name}
                      <span className="text-muted-foreground ml-2 text-xs">
                        {rule.code} · 目标 {statusLabel(rule.targetStatus) ?? rule.targetStatus}
                        {rule.enabled ? '' : ' · 已停用'}
                      </span>
                    </p>
                    <p className="text-muted-foreground text-xs">{rule.description}</p>
                    <ul className="text-muted-foreground mt-1 text-xs">
                      {rule.conditions.map((c) => (
                        <li key={`${rule.id}-${c.metric}-${c.operator}`}>
                          {c.label ?? c.metric}{' '}
                          {operators.find((o) => o.code === c.operator)?.label ?? c.operator}{' '}
                          {c.threshold}
                        </li>
                      ))}
                    </ul>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {can('read') && (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => void loadPreview(rule)}
                      >
                        试算
                      </Button>
                    )}
                    {can('update') && (
                      <>
                        <Button size="sm" variant="ghost" onClick={() => setEditing(rule)}>
                          编辑
                        </Button>
                        {rule.enabled && (
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={busy || previewFor !== rule.id || !preview}
                            onClick={() =>
                              setConfirming(
                                `确认按规则「${rule.name}」批量流转 ${preview?.matchedCount ?? 0} 个商品？`,
                              )
                            }
                          >
                            执行
                          </Button>
                        )}
                      </>
                    )}
                    {can('delete') && (
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() => void removeRule(rule)}
                      >
                        <Trash2 className="h-4 w-4" />
                        删除
                      </Button>
                    )}
                  </div>
                </div>
                {previewFor === rule.id && !preview && busy && (
                  <p role="status" className="text-muted-foreground mt-2 text-xs">
                    正在试算…
                  </p>
                )}
                {previewFor === rule.id && preview && (
                  <div className="bg-muted/40 mt-2 rounded-md p-3 text-xs">
                    <p>
                      扫描 {preview.scanned} 个候选商品，命中 {preview.matchedCount} 个
                      {preview.hasMore ? '（结果已截断，可用游标继续扫描）' : ''}。
                    </p>
                    <ul className="mt-1 space-y-0.5">
                      {preview.matches.map((m) => (
                        <li key={m.spuId}>
                          {m.name}（{m.code}）· 当前{' '}
                          {statusLabel(m.currentStatus) ?? m.currentStatus}
                        </li>
                      ))}
                    </ul>
                    {confirming && (
                      <div className="mt-2 flex flex-wrap items-center gap-2">
                        <span>{confirming}</span>
                        <Button
                          size="sm"
                          disabled={busy}
                          onClick={() => {
                            setConfirming('');
                            void runRule(rule);
                          }}
                        >
                          确认执行
                        </Button>
                      </div>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {editing && (
        <RuleEditor
          metrics={metrics}
          {...(editing !== 'new' ? { rule: editing } : {})}
          close={() => setEditing(null)}
          save={(input, id) => saveRule(input, id)}
          busy={busy}
        />
      )}
    </div>
  );
}

function RuleEditor({
  rule,
  metrics,
  close,
  save,
  busy,
}: {
  rule?: LifecycleRule;
  metrics: MetricInfo[];
  close: () => void;
  save: (input: RuleInput, id?: string) => Promise<void>;
  busy: boolean;
}) {
  const usable = metrics.filter((m) => m.available);
  const [form, setForm] = useState<RuleInput>({
    code: rule?.code ?? '',
    name: rule?.name ?? '',
    description: rule?.description ?? '',
    enabled: rule?.enabled ?? true,
    targetStatus: rule?.targetStatus ?? 'SUSPENDED',
    reasonTemplate: rule?.reasonTemplate ?? '',
    version: rule?.version ?? 0,
    conditions: rule?.conditions.length
      ? rule.conditions.map((c: RuleCondition) => ({ ...c }))
      : [{ metric: usable[0]?.code ?? 'SKU_COUNT', operator: 'GTE', threshold: 1, label: null }],
  });
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  function patchCondition(index: number, patch: Partial<RuleInput['conditions'][number]>) {
    setForm({
      ...form,
      conditions: form.conditions.map((c, i) => (i === index ? { ...c, ...patch } : c)),
    });
  }

  async function submit() {
    const problem = ruleDraftValid(form);
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError('');
    try {
      await save(
        {
          ...form,
          conditions: form.conditions.map((c) => ({
            metric: c.metric,
            operator: c.operator,
            threshold: Number(c.threshold),
            label: null,
          })),
        },
        rule?.id,
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '保存失败，请检查输入');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Editor
      title={rule ? '编辑批量规则' : '新建批量规则'}
      description="规则按合法转换矩阵批量流转商品；引用未接入的上游投影会被明确拒绝执行。"
      close={close}
    >
      <div className="max-h-[70vh] space-y-4 overflow-y-auto">
        {error && (
          <p role="alert" className="text-destructive text-sm">
            {error}
          </p>
        )}
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="规则编码">
            <input
              required
              maxLength={64}
              className={control}
              value={form.code}
              onChange={(e) => setForm({ ...form, code: e.target.value })}
              placeholder="例如 STOP-LOW-DEMAND"
            />
          </Field>
          <Field label="规则名称">
            <input
              required
              maxLength={100}
              className={control}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
            />
          </Field>
          <Field label="目标状态">
            <select
              className={control}
              value={form.targetStatus}
              onChange={(e) => setForm({ ...form, targetStatus: e.target.value })}
            >
              {ruleTargets.map((t) => (
                <option key={t} value={t}>
                  {statusLabel(t)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="变更原因模板" hint="规则执行时写入变更历史；执行前也可单独指定原因。">
            <input
              className={control}
              maxLength={500}
              value={form.reasonTemplate}
              onChange={(e) => setForm({ ...form, reasonTemplate: e.target.value })}
            />
          </Field>
        </div>
        <Field label="规则说明">
          <textarea
            className={control}
            rows={2}
            maxLength={2000}
            value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
          />
        </Field>
        <Field label="判定条件" hint="所有条件同时满足才算命中。阈值最多 13 位整数、6 位小数。">
          <div className="space-y-2">
            {form.conditions.map((c, i) => (
              <div key={i} className="flex flex-wrap items-center gap-2">
                <select
                  aria-label="指标"
                  className={`${control} max-w-56`}
                  value={c.metric}
                  onChange={(e) => patchCondition(i, { metric: e.target.value })}
                >
                  {metrics.map((m) => (
                    <option key={m.code} value={m.code} disabled={!m.available}>
                      {m.label}
                      {m.available ? '' : '（未接入）'}
                    </option>
                  ))}
                </select>
                <select
                  aria-label="比较方式"
                  className={`${control} max-w-32`}
                  value={c.operator}
                  onChange={(e) => patchCondition(i, { operator: e.target.value })}
                >
                  {operators.map((o) => (
                    <option key={o.code} value={o.code}>
                      {o.label}
                    </option>
                  ))}
                </select>
                <input
                  aria-label="阈值"
                  className={`${control} max-w-32`}
                  inputMode="decimal"
                  value={String(c.threshold)}
                  onChange={(e) => patchCondition(i, { threshold: Number(e.target.value) })}
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={form.conditions.length === 1}
                  onClick={() =>
                    setForm({
                      ...form,
                      conditions: form.conditions.filter((_, index) => index !== i),
                    })
                  }
                >
                  移除
                </Button>
                {metrics.find((m) => m.code === c.metric)?.available === false && (
                  <span className="text-muted-foreground w-full text-xs">
                    {metrics.find((m) => m.code === c.metric)?.note}
                  </span>
                )}
              </div>
            ))}
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={form.conditions.length >= 20}
              onClick={() =>
                setForm({
                  ...form,
                  conditions: [
                    ...form.conditions,
                    {
                      metric: usable[0]?.code ?? 'SKU_COUNT',
                      operator: 'GTE',
                      threshold: 1,
                      label: null,
                    },
                  ],
                })
              }
            >
              <Plus className="h-4 w-4" />
              添加条件
            </Button>
          </div>
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={form.enabled}
            onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          />
          启用该规则
        </label>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={close} disabled={busy || saving}>
            取消
          </Button>
          <Button disabled={busy || saving} onClick={() => void submit()}>
            {saving ? '保存中…' : '保存'}
          </Button>
        </div>
      </div>
    </Editor>
  );
}
