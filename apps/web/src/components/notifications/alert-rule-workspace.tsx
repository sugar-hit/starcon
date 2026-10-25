'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader } from '@starcon/ui';
import { AlertTriangle, BellRing, Plus, RefreshCw, RotateCcw, Trash2 } from 'lucide-react';
import type {
  AlertRuleCapabilities,
  AlertRuleEffective,
  AlertRuleHitHistory,
  AlertRuleList,
  AlertRuleSaveRequest,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import {
  CHANGE_BASIS_LABELS,
  COMPARISON_LABELS,
  OBJECT_SCOPE_LABELS,
  OBJECT_TYPE_LABELS,
  TIME_WINDOW_LABELS,
  TIMEZONE_SOURCE_LABELS,
  buildSaveInput,
  canDelete,
  canReset,
  draftOf,
  emptyDraft,
  isValidThresholdText,
  metricAvailable,
  metricName,
  resolutionLabel,
  scopePhrase,
  splitByOrigin,
  summarizeRules,
  thresholdPhrase,
  validateDraft,
  windowPhrase,
  type AlertRuleDraft,
} from './alert-rule-model';

/**
 * 1.3.2.1 预警规则（卷 01 · L3 1.3.2 预警 → {@code /notifications/rules}）。
 *
 * <p>蓝图原文：「用户自定义阈值预警（某 SKU 库存低于 N 天、某店铺销售额环比跌超 X%、
 * 应收超期 Y 天）。系统预置跨境常用规则集，用户可改参数或关闭。……
 * <b>规则引擎本体在 18 系统</b>，此处只做配置界面。」
 *
 * <p>🔴 <b>本页面只做 1.3.2.1 这一格</b> —— 不把兄弟模块 1.3.2.2「外部渠道推送」
 * （凭据不落库、只看 secret manager 引用）一起包进来，那一格有它自己的职责。
 *
 * <p>🔴 <b>命中历史在引擎接通前是空的，页面必须说清「为什么空」</b>：
 * 规则引擎在 18 系统（18.3 业务规则引擎），那一格尚未交付 ⇒ 没有求值、没有命中记录。
 * 页面显示「未接通 + 归属系统」，**不显示假数据、不显示空白** ——
 * 「引擎未接通」与「本租户还没命中过」是两件完全不同的事，混成一句话，
 * 运营就会以为「平台还没配规则」而去找错方向。
 *
 * <p>🔴 <b>阈值全程用字符串</b>：契约里阈值就是十进制文本，前端不碰浮点 ——
 * 告警线偏一点就是漏报或误报。
 *
 * <p>页面分五块：能力与边界、预置规则、自定义规则、命中历史、以及新建规则的表单。
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

/** 正在编辑的那一条规则（null = 没在编辑）。 */
interface Editing {
  rule: AlertRuleEffective;
  draft: AlertRuleDraft;
}

export function AlertRuleWorkspace() {
  const [capabilities, setCapabilities] = useState<AlertRuleCapabilities | null>(null);
  const [list, setList] = useState<AlertRuleList | null>(null);
  const [hits, setHits] = useState<AlertRuleHitHistory | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [creating, setCreating] = useState<AlertRuleDraft | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [notices, setNotices] = useState<Notice[]>([]);

  const notice = (kind: Notice['kind'], text: string) =>
    setNotices((current) => [...current.filter((item) => item.kind !== kind), { kind, text }]);

  const load = useCallback(async () => {
    setLoading(true);
    setNotices([]);
    try {
      const [caps, current, history] = await Promise.all([
        api<AlertRuleCapabilities>('identity/alert-rules/capabilities'),
        api<AlertRuleList>('identity/alert-rules'),
        api<AlertRuleHitHistory>('identity/alert-rules/hits'),
      ]);
      setCapabilities(caps);
      setList(current);
      setHits(history);
    } catch (error) {
      notice('error', messageOf(error));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const vocabulary = capabilities?.vocabulary ?? null;
  const rules = list?.rules ?? [];
  const summary = useMemo(() => summarizeRules(rules), [rules]);
  const { presets, customs } = useMemo(() => splitByOrigin(rules), [rules]);

  /** 改一条规则（逐字段覆盖：给了的覆盖，没给的保持）。 */
  const saveEdit = useCallback(async () => {
    if (!editing) return;
    const reason = validateDraft(editing.draft, vocabulary);
    if (reason) {
      notice('error', reason);
      return;
    }
    setSaving(true);
    setNotices([]);
    try {
      await api<AlertRuleEffective>(
        `identity/alert-rules/${editing.rule.id}`,
        'PUT',
        buildSaveInput(editing.draft),
      );
      setEditing(null);
      await load();
      notice('info', '规则已保存。');
    } catch (error) {
      notice('error', messageOf(error));
    } finally {
      setSaving(false);
    }
  }, [editing, load, notice, vocabulary]);

  /** 新建自定义规则。 */
  const create = useCallback(async () => {
    if (!creating) return;
    const reason = validateDraft(creating, vocabulary);
    if (reason) {
      notice('error', reason);
      return;
    }
    setSaving(true);
    setNotices([]);
    try {
      await api<AlertRuleEffective>('identity/alert-rules', 'POST', buildSaveInput(creating));
      setCreating(null);
      await load();
      notice('info', '规则已创建。');
    } catch (error) {
      notice('error', messageOf(error));
    } finally {
      setSaving(false);
    }
  }, [creating, load, notice, vocabulary]);

  /** 关闭 / 重新启用。 */
  const toggle = useCallback(
    async (rule: AlertRuleEffective, enabled: boolean) => {
      setSaving(true);
      setNotices([]);
      try {
        await api<AlertRuleEffective>(
          `identity/alert-rules/${rule.id}/${enabled ? 'enable' : 'disable'}`,
          'POST',
        );
        await load();
        notice('info', enabled ? '规则已启用。' : '规则已关闭。');
      } catch (error) {
        notice('error', messageOf(error));
      } finally {
        setSaving(false);
      }
    },
    [load, notice],
  );

  /** 恢复预置默认值。 */
  const reset = useCallback(
    async (rule: AlertRuleEffective) => {
      setSaving(true);
      setNotices([]);
      try {
        await api<void>(`identity/alert-rules/${rule.id}/reset`, 'POST');
        await load();
        notice('info', '已恢复到预置默认值。');
      } catch (error) {
        notice('error', messageOf(error));
      } finally {
        setSaving(false);
      }
    },
    [load, notice],
  );

  /** 删除自定义规则（预置规则服务端也会拒 —— 界面不提供这个入口）。 */
  const remove = useCallback(
    async (rule: AlertRuleEffective) => {
      setSaving(true);
      setNotices([]);
      try {
        await api<void>(`identity/alert-rules/${rule.id}`, 'DELETE');
        await load();
        notice('info', '规则已删除。');
      } catch (error) {
        notice('error', messageOf(error));
      } finally {
        setSaving(false);
      }
    },
    [load, notice],
  );

  // ==========================================================================
  // 渲染
  // ==========================================================================

  return (
    <div className="space-y-6">
      <PageHeader
        title="预警规则"
        description="用户自定义阈值预警。系统预置跨境常用规则集，可改参数或关闭。"
        actions={
          <>
            <Button variant="ghost" size="sm" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-1.5 h-4 w-4" />
              刷新
            </Button>
            <Button
              size="sm"
              onClick={() => {
                setCreating(emptyDraft());
                setEditing(null);
              }}
              disabled={creating !== null}
            >
              <Plus className="mr-1.5 h-4 w-4" />
              新建规则
            </Button>
          </>
        }
      />

      {notices.map((item) => (
        <div
          key={item.kind}
          className={
            item.kind === 'error'
              ? 'text-destructive bg-destructive-soft rounded-md px-3 py-2 text-copy-13'
              : 'text-success bg-success-soft rounded-md px-3 py-2 text-copy-13'
          }
        >
          {item.text}
        </div>
      ))}

      {loading && !list ? (
        <div className="text-muted-foreground px-1 py-10 text-center text-copy-13">正在读取规则…</div>
      ) : null}

      {!loading && !list ? (
        <EmptyState
          icon={<AlertTriangle className="h-7 w-7" />}
          title="读取规则失败"
          description="没有拿到规则清单。请检查网络后重试；若是权限问题，请让管理员授予「预警规则查看」权限。"
          action={
            <Button variant="outline" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      ) : null}

      {list ? (
        <>
          {/* ------------------------------------------------ 能力与边界 */}
          <section className="space-y-2">
            <h2 className="text-copy-14 text-foreground font-medium">能力与边界</h2>
            <div className="text-muted-foreground space-y-1 text-copy-13">
              <div>
                共 {summary.total} 条生效规则（预置 {summary.presetCount} 条 · 自定义{' '}
                {summary.customCount} 条），其中 {summary.customizedCount} 条被本租户改过、
                {summary.disabledCount} 条已关闭。
              </div>
              <div>
                🔴 规则求值在 <b>{capabilities?.engineOwnerModule ?? '18 系统'}</b> ——
                {capabilities?.engineAvailable
                  ? '已接通，改完阈值即可看到命中记录。'
                  : '尚未接通，所以本页只做配置：改完阈值不会立刻出现命中记录，这是预期行为，不是故障。'}
              </div>
              {(capabilities?.boundaries ?? []).map((item) => (
                <div key={item}>· {item}</div>
              ))}
            </div>
          </section>

          {/* ------------------------------------------------ 预置规则 */}
          <section className="space-y-2">
            <div className="flex items-center gap-2">
              <h2 className="text-copy-14 text-foreground font-medium">预置规则</h2>
              <Badge tone="info">系统预置 · 可改参数或关闭</Badge>
            </div>
            {presets.length === 0 ? (
              <EmptyState
                icon={<BellRing className="h-7 w-7" />}
                title="没有可用的预置规则"
                description="预置规则集由系统提供。若这里为空，说明当前后端版本尚未登记预置规则。"
              />
            ) : (
              <ul className="divide-border divide-y rounded-md border">
                {presets.map((rule) => (
                  <li key={rule.ruleCode} className="space-y-2 px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">{rule.displayName}</span>
                      {rule.customized ? <Badge tone="warning">已被本租户改过</Badge> : null}
                      {!rule.enabled ? <Badge tone="neutral">已关闭</Badge> : null}
                      {metricAvailable(vocabulary, rule.metricCode) ? null : (
                        <Badge tone="warning">度量未接通</Badge>
                      )}
                    </div>
                    <div className="text-muted-foreground text-copy-13 space-y-0.5">
                      <div>
                        {OBJECT_TYPE_LABELS[rule.objectType] ?? rule.objectType} ·{' '}
                        {metricName(vocabulary, rule.metricCode)} {thresholdPhrase(rule)} ·{' '}
                        {windowPhrase(rule)} · {scopePhrase(rule)}
                      </div>
                      <div>
                        时间窗按 <b>{rule.timezone}</b> 计算（
                        {TIMEZONE_SOURCE_LABELS[rule.timezoneSource] ?? rule.timezoneSource}）
                        {rule.upstreamModule ? ` · 上游：${rule.upstreamSystem} ${rule.upstreamModule}` : ''}
                      </div>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => {
                          setEditing({ rule, draft: draftOf(rule) });
                          setCreating(null);
                        }}
                        disabled={saving}
                      >
                        改参数
                      </Button>
                      {rule.enabled ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => void toggle(rule, false)}
                          disabled={saving}
                        >
                          关闭
                        </Button>
                      ) : (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => void toggle(rule, true)}
                          disabled={saving}
                        >
                          重新启用
                        </Button>
                      )}
                      {canReset(rule) && rule.customized ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => void reset(rule)}
                          disabled={saving}
                        >
                          <RotateCcw className="mr-1.5 h-4 w-4" />
                          恢复默认
                        </Button>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {/* ------------------------------------------------ 自定义规则 */}
          <section className="space-y-2">
            <h2 className="text-copy-14 text-foreground font-medium">自定义规则</h2>
            {customs.length === 0 ? (
              <EmptyState
                icon={<BellRing className="h-7 w-7" />}
                title="本租户还没有自定义规则"
                description="预置规则覆盖的是「跨境常用」的几条线。要盯自己的对象，自己建一条。"
                action={
                  <Button onClick={() => {
                    setCreating(emptyDraft());
                    setEditing(null);
                  }}>
                    <Plus className="mr-1.5 h-4 w-4" />
                    新建规则
                  </Button>
                }
              />
            ) : (
              <ul className="divide-border divide-y rounded-md border">
                {customs.map((rule) => (
                  <li key={rule.ruleCode} className="space-y-2 px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">{rule.displayName}</span>
                      <Badge tone="primary">自定义</Badge>
                      {!rule.enabled ? <Badge tone="neutral">已关闭</Badge> : null}
                    </div>
                    <div className="text-muted-foreground text-copy-13 space-y-0.5">
                      <div>
                        {OBJECT_TYPE_LABELS[rule.objectType] ?? rule.objectType} ·{' '}
                        {metricName(vocabulary, rule.metricCode)} {thresholdPhrase(rule)} ·{' '}
                        {windowPhrase(rule)} · {scopePhrase(rule)}
                      </div>
                      <div>
                        时间窗按 <b>{rule.timezone}</b> 计算（
                        {TIMEZONE_SOURCE_LABELS[rule.timezoneSource] ?? rule.timezoneSource}）
                      </div>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => {
                          setEditing({ rule, draft: draftOf(rule) });
                          setCreating(null);
                        }}
                        disabled={saving}
                      >
                        编辑
                      </Button>
                      {rule.enabled ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => void toggle(rule, false)}
                          disabled={saving}
                        >
                          关闭
                        </Button>
                      ) : (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => void toggle(rule, true)}
                          disabled={saving}
                        >
                          重新启用
                        </Button>
                      )}
                      {canDelete(rule) ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => void remove(rule)}
                          disabled={saving}
                        >
                          <Trash2 className="mr-1.5 h-4 w-4" />
                          删除
                        </Button>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {/* ------------------------------------------------ 命中历史 */}
          <section className="space-y-2">
            <h2 className="text-copy-14 text-foreground font-medium">命中历史</h2>
            {hits && !hits.engineAvailable ? (
              <div className="space-y-2 rounded-md border border-dashed px-4 py-6">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone="warning">规则引擎未接通</Badge>
                  <span className="text-copy-13 text-muted-foreground">
                    归 {hits.engineOwnerModule} 负责
                  </span>
                </div>
                <p className="text-muted-foreground text-copy-13">{hits.engineNote}</p>
                <p className="text-muted-foreground text-copy-13">
                  所以这里显示的是<b>「引擎未接通」</b>，不是「0 次命中」—— 两件事不一样：
                  引擎没接通时，一次命中都没被判过。
                </p>
              </div>
            ) : null}
            {hits && hits.engineAvailable ? (
              hits.hits.length === 0 ? (
                <EmptyState
                  icon={<BellRing className="h-7 w-7" />}
                  title="还没有命中记录"
                  description="规则已接通，但目前还没有任何规则命中过。"
                />
              ) : (
                <ul className="divide-border divide-y rounded-md border">
                  {hits.hits.map((hit) => (
                    <li key={hit.id} className="text-copy-13 space-y-0.5 px-4 py-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={hit.resolution === 'HIT' ? 'danger' : 'neutral'}>
                          {resolutionLabel(hit.resolution)}
                        </Badge>
                        <span className="text-foreground font-medium">{hit.ruleCode}</span>
                      </div>
                      <div className="text-muted-foreground">
                        {OBJECT_TYPE_LABELS[hit.objectType] ?? hit.objectType}
                        {hit.objectRef ? ` ${hit.objectRef}` : ''} · 观测值 {hit.observedValue ?? '—'}
                        {hit.baseValue ? ` · 基期 ${hit.baseValue}` : ''} · 阈值 {hit.threshold}
                      </div>
                      {hit.reason ? (
                        <div className="text-muted-foreground">原因：{hit.reason}</div>
                      ) : null}
                    </li>
                  ))}
                </ul>
              )
            ) : null}
          </section>

          {/* ------------------------------------------------ 编辑 / 新建表单 */}
          {editing ? (
            <RuleForm
              title={`编辑：${editing.rule.displayName}`}
              draft={editing.draft}
              vocabulary={vocabulary}
              saving={saving}
              onChange={(draft) => setEditing({ ...editing, draft })}
              onSubmit={() => void saveEdit()}
              onCancel={() => setEditing(null)}
            />
          ) : null}

          {creating ? (
            <RuleForm
              title="新建自定义规则"
              draft={creating}
              vocabulary={vocabulary}
              saving={saving}
              onChange={(draft) => setCreating(draft)}
              onSubmit={() => void create()}
              onCancel={() => setCreating(null)}
            />
          ) : null}
        </>
      ) : null}
    </div>
  );
}

/** 规则表单（编辑与新建共用一份）。 */
function RuleForm({
  title,
  draft,
  vocabulary,
  saving,
  onChange,
  onSubmit,
  onCancel,
}: {
  title: string;
  draft: AlertRuleDraft;
  vocabulary: AlertRuleCapabilities['vocabulary'] | null;
  saving: boolean;
  onChange: (draft: AlertRuleDraft) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  const reason = validateDraft(draft, vocabulary);
  const set = (patch: Partial<AlertRuleDraft>) => onChange({ ...draft, ...patch });

  return (
    <section className="space-y-3 rounded-md border p-4">
      <h3 className="text-copy-14 text-foreground font-medium">{title}</h3>
      <div className="grid gap-3 md:grid-cols-2">
        <label className="space-y-1">
          <span className="text-copy-13 text-muted-foreground">规则名</span>
          <Input
            value={draft.name}
            onChange={(event) => set({ name: event.target.value })}
            placeholder="例如：主力 SKU 补货线"
          />
        </label>
        <label className="space-y-1">
          <span className="text-copy-13 text-muted-foreground">对象类型</span>
          <select
            className="border-input bg-background h-9 w-full rounded-md border px-3 text-copy-13"
            value={draft.objectType}
            onChange={(event) => set({ objectType: event.target.value })}
          >
            <option value="">请选择</option>
            {(vocabulary?.objectTypes ?? []).map((code) => (
              <option key={code} value={code}>
                {OBJECT_TYPE_LABELS[code] ?? code}
              </option>
            ))}
          </select>
        </label>
        <label className="space-y-1">
          <span className="text-copy-13 text-muted-foreground">度量</span>
          <select
            className="border-input bg-background h-9 w-full rounded-md border px-3 text-copy-13"
            value={draft.metricCode}
            onChange={(event) => set({ metricCode: event.target.value })}
          >
            <option value="">请选择</option>
            {(vocabulary?.metrics ?? []).map((metric) => (
              <option key={metric.code} value={metric.code}>
                {metric.displayName}
                {metric.available ? '' : '（上游未接通）'}
              </option>
            ))}
          </select>
        </label>
        <label className="space-y-1">
          <span className="text-copy-13 text-muted-foreground">
            阈值（十进制文本，不带正负号）
          </span>
          <Input
            value={draft.threshold}
            onChange={(event) => set({ threshold: event.target.value })}
            placeholder="例如：14 或 20.5"
            inputMode="decimal"
          />
          {!isValidThresholdText(draft.threshold) && draft.threshold ? (
            <span className="text-destructive text-label-12">阈值格式不合法</span>
          ) : null}
        </label>
        <label className="space-y-1">
          <span className="text-copy-13 text-muted-foreground">时间窗</span>
          <select
            className="border-input bg-background h-9 w-full rounded-md border px-3 text-copy-13"
            value={draft.timeWindow}
            onChange={(event) => set({ timeWindow: event.target.value })}
          >
            {(vocabulary?.timeWindows ?? []).map((code) => (
              <option key={code} value={code}>
                {TIME_WINDOW_LABELS[code] ?? code}
              </option>
            ))}
          </select>
        </label>
        <label className="space-y-1">
          <span className="text-copy-13 text-muted-foreground">比较方向</span>
          <select
            className="border-input bg-background h-9 w-full rounded-md border px-3 text-copy-13"
            value={draft.comparison}
            onChange={(event) => set({ comparison: event.target.value })}
          >
            {(vocabulary?.comparisons ?? []).map((code) => (
              <option key={code} value={code}>
                {COMPARISON_LABELS[code] ?? code}
              </option>
            ))}
          </select>
        </label>
        <label className="space-y-1">
          <span className="text-copy-13 text-muted-foreground">比较基准</span>
          <select
            className="border-input bg-background h-9 w-full rounded-md border px-3 text-copy-13"
            value={draft.changeBasis}
            onChange={(event) => set({ changeBasis: event.target.value })}
          >
            {(vocabulary?.changeBases ?? []).map((code) => (
              <option key={code} value={code}>
                {CHANGE_BASIS_LABELS[code] ?? code}
              </option>
            ))}
          </select>
        </label>
        <label className="space-y-1">
          <span className="text-copy-13 text-muted-foreground">对象范围</span>
          <select
            className="border-input bg-background h-9 w-full rounded-md border px-3 text-copy-13"
            value={draft.objectScope}
            onChange={(event) => set({ objectScope: event.target.value })}
          >
            {(vocabulary?.objectScopes ?? []).map((code) => (
              <option key={code} value={code}>
                {OBJECT_SCOPE_LABELS[code] ?? code}
              </option>
            ))}
          </select>
        </label>
        {draft.objectScope === 'SPECIFIC' ? (
          <label className="space-y-1">
            <span className="text-copy-13 text-muted-foreground">对象标识</span>
            <Input
              value={draft.objectRef}
              onChange={(event) => set({ objectRef: event.target.value })}
              placeholder="上游系统里的对象编码"
            />
          </label>
        ) : null}
        <label className="space-y-1">
          <span className="text-copy-13 text-muted-foreground">
            时间窗时区（IANA 区域 id，留空继承你的展示时区）
          </span>
          <Input
            value={draft.timezone}
            onChange={(event) => set({ timezone: event.target.value })}
            placeholder="例如：Asia/Shanghai"
          />
        </label>
      </div>
      {reason ? <p className="text-destructive text-copy-13">{reason}</p> : null}
      <div className="flex gap-2">
        <Button size="sm" onClick={onSubmit} disabled={saving || reason !== null}>
          保存
        </Button>
        <Button variant="ghost" size="sm" onClick={onCancel} disabled={saving}>
          取消
        </Button>
      </div>
    </section>
  );
}
