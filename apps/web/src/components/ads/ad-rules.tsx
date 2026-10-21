'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  AdRuleCapabilities,
  AdRuleConditionRow,
  AdRuleDetail,
  AdRuleDispatchPage,
  AdRuleEffectPage,
  AdRuleExecutionPage,
  AdRuleHitPage,
  AdRuleMetricPage,
  AdRulePage,
  AdRuleRunPage,
  MetricInput,
  RuleConditionInput,
  RuleInput,
  actionLabel,
  cancelDispatch,
  emptyMetricInput,
  emptyRuleConditionInput,
  emptyRuleInput,
  evaluateEffects,
  fetchAdRuleCapabilities,
  fetchDispatches,
  fetchEffects,
  fetchExecutions,
  fetchHits,
  fetchMetrics,
  fetchRule,
  fetchRules,
  fetchRuns,
  freshnessText,
  metricLabel,
  money,
  operatorLabel,
  outcomeLabel,
  ratio,
  rejectReasonLabel,
  runEvaluation,
  saveMetric,
  saveRule,
  setRuleStatus,
  validateMetric,
  validateRule,
  verdictLabel,
} from './ad-rules-model';
import { ApiError } from '../inventory/api';

/**
 * 14.2.2.2 自动规则 工作区。
 *
 * <p>🔴 这一页的布局顺序是按「操作的因果链」排的，不是按数据表排的：
 * 指标 → 规则 → 评估运行 → 命中 / 执行 → 待下发 → 回溯。理由是：**规则没有指标就是空转**，
 * 所以指标登记放在最前面，而且规则行上直接显示「没有数据 / 没同步到 / 数据截至 X」。
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>上限是必填项，页面不提供「不限」</strong>。单次上限与总上限留空直接被拦下 ——
 *       在界面上留一个「无限额」的勾选框，等于重新开了一次「一次异常烧掉全部预算」的口子。</li>
 *   <li><strong>试运行与真实执行必须一眼分得开</strong>。规则行有「试运行中 / 真实执行」标记，
 *       执行记录把 <code>outcome</code> 直接翻成人话，并且<strong>被闸门拒绝的调整照样列出来</strong>，
 *       写清是被「单次上限 / 总上限 / 上下限保护」哪一道闸拦下来的。</li>
 *   <li><strong>「待下发」不是「已生效」</strong>。下游 = 平台广告 API，本模块不实现平台侧真实下发，
 *       所以待下发动作一律显示「待下发」并显式标注通道未交付。</li>
 * </ol>
 *
 * <p>🔴 页面**不做任何业务算术**：ACOS / ROAS / 调整幅度全部由服务端算好，页面只渲染。
 */
export function AdRulesWorkspace() {
  const [capabilities, setCapabilities] = useState<AdRuleCapabilities | null>(null);
  const [rules, setRules] = useState<AdRulePage | null>(null);
  const [ruleDetail, setRuleDetail] = useState<AdRuleDetail | null>(null);
  const [metrics, setMetrics] = useState<AdRuleMetricPage | null>(null);
  const [runs, setRuns] = useState<AdRuleRunPage | null>(null);
  const [hits, setHits] = useState<AdRuleHitPage | null>(null);
  const [executions, setExecutions] = useState<AdRuleExecutionPage | null>(null);
  const [dispatches, setDispatches] = useState<AdRuleDispatchPage | null>(null);
  const [effects, setEffects] = useState<AdRuleEffectPage | null>(null);

  const [ruleInput, setRuleInput] = useState<RuleInput>(emptyRuleInput);
  const [ruleErrors, setRuleErrors] = useState<string[]>([]);
  const [metricInput, setMetricInput] = useState<MetricInput>(emptyMetricInput);
  const [metricErrors, setMetricErrors] = useState<string[]>([]);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const loadAll = useCallback(async () => {
    try {
      const [caps, rulePage, metricPage, runPage, hitPage, executionPage, dispatchPage, effectPage] =
        await Promise.all([
          fetchAdRuleCapabilities(),
          fetchRules({ size: 20 }),
          fetchMetrics({ size: 20 }),
          fetchRuns({ size: 10 }),
          fetchHits({ size: 20 }),
          fetchExecutions({ size: 20 }),
          fetchDispatches({ size: 20 }),
          fetchEffects({ size: 20 }),
        ]);
      setCapabilities(caps);
      setRules(rulePage);
      setMetrics(metricPage);
      setRuns(runPage);
      setHits(hitPage);
      setExecutions(executionPage);
      setDispatches(dispatchPage);
      setEffects(effectPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  // ---- 指标登记（本模块的显式写入口）----

  async function onSaveMetric() {
    const validation = validateMetric(metricInput);
    setMetricErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      await saveMetric(metricInput);
      setMetricInput(emptyMetricInput);
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  // ---- 规则登记 ----

  async function onSaveRule() {
    const validation = validateRule(ruleInput);
    setRuleErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const saved = await saveRule(ruleInput);
      setRuleInput(emptyRuleInput);
      setRuleErrors([]);
      await loadAll();
      setRuleDetail(await fetchRule(saved.rule.id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onOpenRule(id: string) {
    setBusy(true);
    setError('');
    try {
      setRuleDetail(await fetchRule(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onToggleStatus(id: string, current: string) {
    setBusy(true);
    setError('');
    try {
      await setRuleStatus(id, current === 'ACTIVE' ? 'PAUSED' : 'ACTIVE');
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  // ---- 评估运行 ----

  async function onRun(runMode: string) {
    setBusy(true);
    setError('');
    try {
      await runEvaluation(runMode);
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  // ---- 待下发动作 / 效果回溯 ----

  async function onCancelDispatch(id: string) {
    setBusy(true);
    setError('');
    try {
      await cancelDispatch(id, '运营在待下发队列里撤销');
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onEvaluateEffects() {
    setBusy(true);
    setError('');
    try {
      await evaluateEffects();
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">自动规则</h1>
        <p className="text-sm text-muted-foreground max-w-4xl">
          按条件自动调整预算 / 竞价 / 启停：ACOS 超标降价、ROAS 达标加预算、消耗完毕暂停、花费无转化停投。
          <strong>上限与试运行是必填项</strong> —— 没有上限的自动调价一次异常就能烧掉大量预算。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
      {loading ? <p className="text-sm text-muted-foreground">正在加载规则、指标与执行记录…</p> : null}

      {/* ================= 能力声明 ================= */}
      {capabilities ? (
        <Section title="能力声明（未交付的部分显式标出，不含糊过去）">
          <ul className="flex flex-col gap-2 text-sm">
            {capabilities.dimensions.map((dimension) => (
              <li key={dimension.dimension} className="flex flex-col">
                <span>
                  {dimension.available ? '✅' : '⛔'} {dimension.description}
                </span>
                {!dimension.available ? (
                  <span className="text-xs text-muted-foreground">
                    责任模块：{dimension.ownerModule} —— {dimension.impact}
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
          <p className="text-xs text-muted-foreground">{capabilities.guardNote}</p>
          <p className="text-xs text-muted-foreground">{capabilities.dryRunNote}</p>
          <p className="text-xs text-muted-foreground">{capabilities.priorityNote}</p>
          <p className="text-xs text-muted-foreground">{capabilities.dispatchNote}</p>
          <p className="text-xs text-muted-foreground">{capabilities.upstreamNote}</p>
        </Section>
      ) : null}

      {/* ================= 规则 ================= */}
      <Section title="自动规则">
        <div className="flex flex-wrap items-end gap-2">
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            disabled={busy}
            onClick={() => void onRun('DRY_RUN')}
          >
            跑一次（试运行）
          </button>
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            disabled={busy}
            onClick={() => void onRun('APPLY')}
          >
            跑一次（真实执行）
          </button>
          <span className="text-xs text-muted-foreground">
            真实执行也**不会**把开着试运行的规则推成真执行 —— 取更严的那个。
          </span>
        </div>

        {!loading && (rules?.items.length ?? 0) === 0 ? (
          <p className="text-sm text-muted-foreground">
            还没有规则。先在下面登记一条 —— 上限与试运行是必填的。
          </p>
        ) : null}

        {(rules?.items ?? []).map((rule) => (
          <article key={rule.id} className="rounded border p-3 flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="font-medium">{rule.ruleName}</h3>
              <code className="text-xs text-muted-foreground">{rule.ruleCode}</code>
              {/* 🔴 试运行与真实执行必须一眼分得开 */}
              <span className="rounded border px-2 py-0.5 text-xs">
                {rule.dryRun ? '🔬 试运行中' : '⚡ 真实执行'}
              </span>
              <span className="rounded border px-2 py-0.5 text-xs">{rule.status}</span>
              <span className="rounded border px-2 py-0.5 text-xs">优先级 {rule.priority}</span>
            </div>
            <p className="text-xs text-muted-foreground">
              {rule.platformCode} · {rule.shopCode} · {rule.targetType} · 外部活动{' '}
              <code>{rule.externalCampaignId || '（全部）'}</code>
              {rule.externalKeywordId ? (
                <>
                  {' '}
                  · 外部关键词 <code>{rule.externalKeywordId}</code>
                </>
              ) : null}
            </p>
            <dl className="grid grid-cols-2 md:grid-cols-4 gap-2 text-sm">
              <Stat label="动作" value={actionLabel(rule.actionType)} />
              <Stat label="调整幅度口径" value={rule.adjustmentMode} />
              <Stat label="调整幅度" value={money(rule.adjustmentValue)} />
              <Stat label="条件条数" value={String(rule.conditionCount)} />
              <Stat label="单次上限（金额）" value={money(rule.singleAdjustCapAmount)} />
              <Stat label="单次上限（比例）" value={`${money(rule.singleAdjustCapPercent)}%`} />
              <Stat label="总上限（金额）" value={money(rule.totalCapAmount)} />
              <Stat
                label="总上限（次数 / 窗口）"
                value={`${rule.totalCapCount} 次 / ${rule.totalCapWindowHours} 小时`}
              />
              <Stat label="预算下限" value={money(rule.minBudget)} />
              <Stat label="预算上限" value={money(rule.maxBudget)} />
              <Stat label="出价下限" value={money(rule.minBid)} />
              <Stat label="出价上限" value={money(rule.maxBid)} />
            </dl>
            {/* 🔴 「没有数据」与「没同步到」在界面上分开显示 */}
            <p className="text-xs text-muted-foreground">{freshnessText(rule)}</p>
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                className="rounded border px-2 py-1 text-xs"
                disabled={busy}
                onClick={() => void onOpenRule(rule.id)}
              >
                看条件与最近一次评估
              </button>
              <button
                type="button"
                className="rounded border px-2 py-1 text-xs"
                disabled={busy}
                onClick={() => void onToggleStatus(rule.id, rule.status)}
              >
                {rule.status === 'ACTIVE' ? '停用' : '启用'}
              </button>
            </div>
          </article>
        ))}
      </Section>

      {/* ================= 规则详情 ================= */}
      {ruleDetail ? (
        <Section title={`规则详情：${ruleDetail.rule.ruleName}`}>
          <h3 className="font-medium text-sm">条件（AND 语义：全部成立才算命中）</h3>
          {(ruleDetail.conditions.length ?? 0) === 0 ? (
            <p className="text-sm text-muted-foreground">这条规则还没有条件。</p>
          ) : (
            <ul className="flex flex-col gap-1 text-sm">
              {ruleDetail.conditions.map((condition) => (
                <li key={condition.id}>
                  {metricLabel(condition.metric)} {operatorLabel(condition.operator)}{' '}
                  {condition.thresholdValue !== null && condition.thresholdValue !== undefined
                    ? condition.thresholdValue.toFixed(6)
                    : money(condition.thresholdAmount)}
                </li>
              ))}
            </ul>
          )}
          <h3 className="font-medium text-sm">最近一次评估</h3>
          {ruleDetail.lastRun ? (
            <dl className="grid grid-cols-2 md:grid-cols-4 gap-2 text-sm">
              <Stat label="运行口径" value={ruleDetail.lastRun.runMode} />
              <Stat label="命中" value={String(ruleDetail.lastRun.hitCount)} />
              <Stat label="被选中执行" value={String(ruleDetail.lastRun.selectedCount)} />
              <Stat label="已执行" value={String(ruleDetail.lastRun.appliedCount)} />
              <Stat label="试运行" value={String(ruleDetail.lastRun.dryRunCount)} />
              <Stat label="被闸门拒绝" value={String(ruleDetail.lastRun.rejectedCount)} />
            </dl>
          ) : (
            <p className="text-sm text-muted-foreground">这条规则还没有被评估过。</p>
          )}
        </Section>
      ) : null}

      {/* ================= 命中记录 ================= */}
      <Section title="规则命中记录（未命中的也留痕，写清差在哪）">
        {!loading && (hits?.items.length ?? 0) === 0 ? (
          <p className="text-sm text-muted-foreground">还没有命中记录 —— 先跑一次评估。</p>
        ) : null}
        {(hits?.items ?? []).map((hit) => (
          <article key={hit.id} className="rounded border p-3 flex flex-col gap-1 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{hit.ruleName}</span>
              {hit.matched ? (
                <span className="rounded border px-2 py-0.5 text-xs">命中</span>
              ) : (
                <span className="rounded border px-2 py-0.5 text-xs text-muted-foreground">未命中</span>
              )}
              {hit.selected ? (
                <span className="rounded border px-2 py-0.5 text-xs">被选中执行</span>
              ) : null}
              <span className="text-xs text-muted-foreground">
                {hit.platformCode} · {hit.shopCode} · 外部活动 <code>{hit.externalCampaignId}</code>
              </span>
            </div>
            <p className="text-xs text-muted-foreground">{hit.hitReason}</p>
            {hit.selectionNote ? (
              <p className="text-xs text-muted-foreground">{hit.selectionNote}</p>
            ) : null}
            <p className="text-xs text-muted-foreground">
              当时指标：花费 {money(hit.spendSnapshot)} · 销售额 {money(hit.revenueSnapshot)} · ACOS{' '}
              {ratio(hit.acosSnapshot)} · ROAS {ratio(hit.roasSnapshot)} · 数据截至 {hit.metricsAsOf}
            </p>
          </article>
        ))}
      </Section>

      {/* ================= 调整执行记录 ================= */}
      <Section title="调整执行记录（试运行 / 已执行 / 被闸门拒绝）">
        {!loading && (executions?.items.length ?? 0) === 0 ? (
          <p className="text-sm text-muted-foreground">还没有执行记录 —— 先跑一次评估。</p>
        ) : null}
        {(executions?.items ?? []).map((execution) => (
          <article key={execution.id} className="rounded border p-3 flex flex-col gap-1 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{execution.ruleName}</span>
              <span className="rounded border px-2 py-0.5 text-xs">
                {outcomeLabel(execution.outcome)}
              </span>
              {execution.rejectReason ? (
                <span className="rounded border px-2 py-0.5 text-xs text-destructive">
                  {rejectReasonLabel(execution.rejectReason)}
                </span>
              ) : null}
            </div>
            <p className="text-xs text-muted-foreground">
              {actionLabel(execution.actionType)} ·{' '}
              {execution.beforeStatus || execution.afterStatus ? (
                <>
                  状态 {execution.beforeStatus ?? '—'} → {execution.afterStatus ?? '—'}
                </>
              ) : (
                <>
                  值 {money(execution.beforeValue)} → {money(execution.afterValue)}
                  {execution.appliedDelta !== null && execution.appliedDelta !== undefined
                    ? `（幅度 ${money(execution.appliedDelta)}）`
                    : '（未调整）'}
                </>
              )}
            </p>
            {/* 🔴 逐项闸门结论：被哪一道闸拦下来的要看得见 */}
            <p className="text-xs text-muted-foreground">
              上限闸门：{execution.capCheck} · 上下限闸门：{execution.boundCheck} · 窗口内已用{' '}
              {money(execution.windowUsedAmount)} / {execution.windowUsedCount} 次
            </p>
            {execution.rejectDetail ? (
              <p className="text-xs text-destructive">{execution.rejectDetail}</p>
            ) : null}
          </article>
        ))}
      </Section>

      {/* ================= 待下发动作 ================= */}
      <Section title="待下发动作（下游 = 平台广告 API；本模块不实现真实下发）">
        {!loading && (dispatches?.items.length ?? 0) === 0 ? (
          <p className="text-sm text-muted-foreground">队列是空的 —— 没有待下发的调整。</p>
        ) : null}
        {(dispatches?.items ?? []).map((dispatch) => (
          <article key={dispatch.id} className="rounded border p-3 flex flex-col gap-1 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{dispatch.ruleName}</span>
              {/* 🔴 「待下发」不是「已生效」 */}
              <span className="rounded border px-2 py-0.5 text-xs">{dispatch.dispatchStatus}</span>
              <span className="text-xs text-muted-foreground">
                {dispatch.platformCode} · {dispatch.shopCode} · 外部活动{' '}
                <code>{dispatch.externalCampaignId}</code>
              </span>
            </div>
            <p className="text-xs text-muted-foreground">
              动作：{actionLabel(dispatch.actionType)}
            </p>
            <details>
              <summary className="text-xs cursor-pointer">下发载荷</summary>
              <pre className="text-xs bg-muted/40 rounded p-2 overflow-auto">
                {JSON.stringify(dispatch.actionPayload, null, 2)}
              </pre>
            </details>
            {dispatch.dispatchStatus === 'PENDING' ? (
              <button
                type="button"
                className="rounded border px-2 py-1 text-xs self-start"
                disabled={busy}
                onClick={() => void onCancelDispatch(dispatch.id)}
              >
                撤销这条待下发
              </button>
            ) : null}
          </article>
        ))}
      </Section>

      {/* ================= 效果回溯 ================= */}
      <Section title="效果回溯（这条规则到底管不管用）">
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            disabled={busy}
            onClick={() => void onEvaluateEffects()}
          >
            回溯一次
          </button>
          <span className="text-xs text-muted-foreground">
            「数据不足」是**合法结论**：执行后还没有更新的指标时，先等数据到齐再回溯一次。
          </span>
        </div>
        {!loading && (effects?.items.length ?? 0) === 0 ? (
          <p className="text-sm text-muted-foreground">还没有回溯记录 —— 先跑一次真实执行再回溯。</p>
        ) : null}
        {(effects?.items ?? []).map((effect) => (
          <article key={effect.id} className="rounded border p-3 flex flex-col gap-1 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{effect.ruleName}</span>
              <span className="rounded border px-2 py-0.5 text-xs">
                {verdictLabel(effect.verdict)}
              </span>
            </div>
            <p className="text-xs text-muted-foreground">
              ACOS {ratio(effect.beforeAcos)} → {ratio(effect.afterAcos)} · ROAS {ratio(effect.beforeRoas)} →{' '}
              {ratio(effect.afterRoas)} · 花费 {money(effect.beforeSpend)} → {money(effect.afterSpend)} · 销售额{' '}
              {money(effect.beforeRevenue)} → {money(effect.afterRevenue)}
            </p>
            <p className="text-xs text-muted-foreground">{effect.verdictNote}</p>
          </article>
        ))}
      </Section>

      {/* ================= 指标登记 ================= */}
      <Section title="登记广告表现指标（上游 14.3 报表未开发，数据从这里进来）">
        <p className="text-xs text-muted-foreground">
          规则靠**外部引用**定位广告对象，指标由本模块自持。数据截至时间必填 —— 没有它就分不清「没有数据」与「没同步到」。
        </p>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          <Field label="平台 id">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.platformId}
              onChange={(e) => setMetricInput({ ...metricInput, platformId: e.target.value })}
            />
          </Field>
          <Field label="店铺编码">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.shopCode}
              onChange={(e) => setMetricInput({ ...metricInput, shopCode: e.target.value })}
            />
          </Field>
          <Field label="对象层级">
            <select
              className="rounded border px-2 py-1"
              value={metricInput.metricScope}
              onChange={(e) => setMetricInput({ ...metricInput, metricScope: e.target.value })}
            >
              <option value="CAMPAIGN">活动</option>
              <option value="KEYWORD">关键词</option>
            </select>
          </Field>
          <Field label="外部活动 id">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.externalCampaignId}
              onChange={(e) =>
                setMetricInput({ ...metricInput, externalCampaignId: e.target.value })
              }
            />
          </Field>
          <Field label="外部关键词 id（关键词级必填）">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.externalKeywordId}
              onChange={(e) =>
                setMetricInput({ ...metricInput, externalKeywordId: e.target.value })
              }
            />
          </Field>
          <Field label="币种">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.currency}
              onChange={(e) => setMetricInput({ ...metricInput, currency: e.target.value })}
            />
          </Field>
          <Field label="当前投放状态">
            <select
              className="rounded border px-2 py-1"
              value={metricInput.deliveryStatus}
              onChange={(e) => setMetricInput({ ...metricInput, deliveryStatus: e.target.value })}
            >
              <option value="ENABLED">投放中</option>
              <option value="PAUSED">已暂停</option>
              <option value="ENDED">已结束</option>
            </select>
          </Field>
          <Field label="当前预算">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.currentBudget}
              onChange={(e) => setMetricInput({ ...metricInput, currentBudget: e.target.value })}
            />
          </Field>
          <Field label="当前出价">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.currentBid}
              onChange={(e) => setMetricInput({ ...metricInput, currentBid: e.target.value })}
            />
          </Field>
          <Field label="展示数">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.impressions}
              onChange={(e) => setMetricInput({ ...metricInput, impressions: e.target.value })}
            />
          </Field>
          <Field label="点击数">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.clicks}
              onChange={(e) => setMetricInput({ ...metricInput, clicks: e.target.value })}
            />
          </Field>
          <Field label="转化数">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.conversions}
              onChange={(e) => setMetricInput({ ...metricInput, conversions: e.target.value })}
            />
          </Field>
          <Field label="花费">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.spend}
              onChange={(e) => setMetricInput({ ...metricInput, spend: e.target.value })}
            />
          </Field>
          <Field label="归因销售额">
            <input
              className="rounded border px-2 py-1"
              value={metricInput.attributedRevenue}
              onChange={(e) => setMetricInput({ ...metricInput, attributedRevenue: e.target.value })}
            />
          </Field>
          <Field label="数据截至时间（必填）">
            <input
              type="datetime-local"
              className="rounded border px-2 py-1"
              value={metricInput.metricsAsOf}
              onChange={(e) => setMetricInput({ ...metricInput, metricsAsOf: e.target.value })}
            />
          </Field>
          <Field label="统计窗口开始">
            <input
              type="datetime-local"
              className="rounded border px-2 py-1"
              value={metricInput.windowStart}
              onChange={(e) => setMetricInput({ ...metricInput, windowStart: e.target.value })}
            />
          </Field>
          <Field label="统计窗口结束">
            <input
              type="datetime-local"
              className="rounded border px-2 py-1"
              value={metricInput.windowEnd}
              onChange={(e) => setMetricInput({ ...metricInput, windowEnd: e.target.value })}
            />
          </Field>
        </div>
        {metricErrors.length > 0 ? (
          <ul className="text-destructive text-sm flex flex-col gap-1">
            {metricErrors.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        ) : null}
        <button
          type="button"
          className="rounded border px-3 py-1 text-sm self-start"
          disabled={busy}
          onClick={() => void onSaveMetric()}
        >
          登记指标
        </button>
        {!loading && (metrics?.items.length ?? 0) === 0 ? (
          <p className="text-sm text-muted-foreground">
            还没有指标数据 —— 规则现在没有可判定的数据，跑起来只会「没有数据」，不会误判。
          </p>
        ) : null}
        {(metrics?.items ?? []).map((metric) => (
          <article key={metric.id} className="rounded border p-3 flex flex-col gap-1 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{metric.platformCode}</span>
              <span className="text-xs text-muted-foreground">
                {metric.shopCode} · {metric.metricScope} · 外部活动{' '}
                <code>{metric.externalCampaignId}</code>
              </span>
              <span className="rounded border px-2 py-0.5 text-xs">{metric.deliveryStatus}</span>
            </div>
            <dl className="grid grid-cols-2 md:grid-cols-4 gap-2 text-sm">
              <Stat label="花费" value={money(metric.spend)} />
              <Stat label="归因销售额" value={money(metric.attributedRevenue)} />
              <Stat label="ACOS" value={ratio(metric.acos)} />
              <Stat label="ROAS" value={ratio(metric.roas)} />
              <Stat label="转化数" value={String(metric.conversions ?? 0)} />
              <Stat label="当前预算" value={money(metric.currentBudget)} />
              <Stat label="当前出价" value={money(metric.currentBid)} />
              <Stat label="数据截至" value={metric.metricsAsOf} />
            </dl>
          </article>
        ))}
      </Section>

      {/* ================= 规则登记 ================= */}
      <Section title="登记自动规则（上限与试运行是必填项）">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          <Field label="规则编码（租户内唯一）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.ruleCode}
              onChange={(e) => setRuleInput({ ...ruleInput, ruleCode: e.target.value })}
            />
          </Field>
          <Field label="规则名称">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.ruleName}
              onChange={(e) => setRuleInput({ ...ruleInput, ruleName: e.target.value })}
            />
          </Field>
          <Field label="平台 id">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.platformId}
              onChange={(e) => setRuleInput({ ...ruleInput, platformId: e.target.value })}
            />
          </Field>
          <Field label="店铺编码">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.shopCode}
              onChange={(e) => setRuleInput({ ...ruleInput, shopCode: e.target.value })}
            />
          </Field>
          <Field label="对象层级">
            <select
              className="rounded border px-2 py-1"
              value={ruleInput.targetType}
              onChange={(e) => setRuleInput({ ...ruleInput, targetType: e.target.value })}
            >
              <option value="CAMPAIGN">活动</option>
              <option value="KEYWORD">关键词</option>
            </select>
          </Field>
          <Field label="外部活动 id">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.externalCampaignId}
              onChange={(e) =>
                setRuleInput({ ...ruleInput, externalCampaignId: e.target.value })
              }
            />
          </Field>
          <Field label="外部关键词 id（关键词级必填）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.externalKeywordId}
              onChange={(e) =>
                setRuleInput({ ...ruleInput, externalKeywordId: e.target.value })
              }
            />
          </Field>
          <Field label="动作">
            <select
              className="rounded border px-2 py-1"
              value={ruleInput.actionType}
              onChange={(e) => setRuleInput({ ...ruleInput, actionType: e.target.value })}
            >
              <option value="DECREASE_BID">降低出价</option>
              <option value="INCREASE_BID">提高出价</option>
              <option value="DECREASE_BUDGET">降低预算</option>
              <option value="INCREASE_BUDGET">提高预算</option>
              <option value="PAUSE">暂停投放</option>
              <option value="RESUME">恢复投放</option>
            </select>
          </Field>
          <Field label="调整幅度口径">
            <select
              className="rounded border px-2 py-1"
              value={ruleInput.adjustmentMode}
              onChange={(e) => setRuleInput({ ...ruleInput, adjustmentMode: e.target.value })}
            >
              <option value="ABSOLUTE">绝对值</option>
              <option value="PERCENT">相对当前值的百分比</option>
            </select>
          </Field>
          <Field label="调整幅度（启停动作填 0）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.adjustmentValue}
              onChange={(e) => setRuleInput({ ...ruleInput, adjustmentValue: e.target.value })}
            />
          </Field>
          <Field label="🔴 单次调整金额上限（必填，> 0）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.singleAdjustCapAmount}
              onChange={(e) =>
                setRuleInput({ ...ruleInput, singleAdjustCapAmount: e.target.value })
              }
            />
          </Field>
          <Field label="🔴 单次调整比例上限（0 = 不启用）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.singleAdjustCapPercent}
              onChange={(e) =>
                setRuleInput({ ...ruleInput, singleAdjustCapPercent: e.target.value })
              }
            />
          </Field>
          <Field label="🔴 总上限：窗口内累计金额（必填，> 0）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.totalCapAmount}
              onChange={(e) => setRuleInput({ ...ruleInput, totalCapAmount: e.target.value })}
            />
          </Field>
          <Field label="🔴 总上限：窗口内累计次数（必填，≥ 1）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.totalCapCount}
              onChange={(e) => setRuleInput({ ...ruleInput, totalCapCount: e.target.value })}
            />
          </Field>
          <Field label="🔴 累计窗口长度（小时，必填，≥ 1）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.totalCapWindowHours}
              onChange={(e) => setRuleInput({ ...ruleInput, totalCapWindowHours: e.target.value })}
            />
          </Field>
          <Field label="预算下限保护">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.minBudget}
              onChange={(e) => setRuleInput({ ...ruleInput, minBudget: e.target.value })}
            />
          </Field>
          <Field label="预算上限保护">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.maxBudget}
              onChange={(e) => setRuleInput({ ...ruleInput, maxBudget: e.target.value })}
            />
          </Field>
          <Field label="出价下限保护">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.minBid}
              onChange={(e) => setRuleInput({ ...ruleInput, minBid: e.target.value })}
            />
          </Field>
          <Field label="出价上限保护">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.maxBid}
              onChange={(e) => setRuleInput({ ...ruleInput, maxBid: e.target.value })}
            />
          </Field>
          <Field label="优先级（数字小 = 优先高）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.priority}
              onChange={(e) => setRuleInput({ ...ruleInput, priority: e.target.value })}
            />
          </Field>
          <Field label="评估窗口长度（小时）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.evaluationWindowHours}
              onChange={(e) => setRuleInput({ ...ruleInput, evaluationWindowHours: e.target.value })}
            />
          </Field>
          <Field label="状态">
            <select
              className="rounded border px-2 py-1"
              value={ruleInput.status}
              onChange={(e) => setRuleInput({ ...ruleInput, status: e.target.value })}
            >
              <option value="ACTIVE">启用</option>
              <option value="PAUSED">停用</option>
              <option value="ARCHIVED">归档</option>
            </select>
          </Field>
        </div>

        <h3 className="font-medium text-sm mt-2">条件（AND 语义：全部成立才算命中）</h3>
        {ruleInput.conditions.map((condition, index) => (
          <div key={index} className="flex flex-wrap items-end gap-2">
            <Field label="指标口径">
              <select
                className="rounded border px-2 py-1"
                value={condition.metric}
                onChange={(e) =>
                  updateCondition(ruleInput, setRuleInput, index, { metric: e.target.value })
                }
              >
                <option value="ACOS">ACOS</option>
                <option value="ROAS">ROAS</option>
                <option value="SPEND">花费</option>
                <option value="REVENUE">归因销售额</option>
                <option value="CONVERSIONS">转化数</option>
                <option value="CLICKS">点击数</option>
                <option value="IMPRESSIONS">展示数</option>
              </select>
            </Field>
            <Field label="比较">
              <select
                className="rounded border px-2 py-1"
                value={condition.operator}
                onChange={(e) =>
                  updateCondition(ruleInput, setRuleInput, index, { operator: e.target.value })
                }
              >
                <option value="GT">大于</option>
                <option value="GTE">大于等于</option>
                <option value="LT">小于</option>
                <option value="LTE">小于等于</option>
                <option value="EQ">等于</option>
              </select>
            </Field>
            <Field label="比率 / 计数阈值">
              <input
                className="rounded border px-2 py-1"
                value={condition.thresholdValue}
                onChange={(e) =>
                  updateCondition(ruleInput, setRuleInput, index, {
                    thresholdValue: e.target.value,
                  })
                }
              />
            </Field>
            <Field label="金额阈值">
              <input
                className="rounded border px-2 py-1"
                value={condition.thresholdAmount}
                onChange={(e) =>
                  updateCondition(ruleInput, setRuleInput, index, {
                    thresholdAmount: e.target.value,
                  })
                }
              />
            </Field>
            <button
              type="button"
              className="rounded border px-2 py-1 text-xs"
              disabled={ruleInput.conditions.length <= 1}
              onClick={() => setRuleInput({ ...ruleInput, conditions: ruleInput.conditions.filter((_, i) => i !== index) })}
            >
              删除这条条件
            </button>
          </div>
        ))}
        <button
          type="button"
          className="rounded border px-2 py-1 text-xs self-start"
          onClick={() =>
            setRuleInput({
              ...ruleInput,
              conditions: [...ruleInput.conditions, { ...emptyRuleConditionInput }],
            })
          }
        >
          加一条条件
        </button>

        {/* 🔴 试运行开关：默认开，且界面上明确写出它的含义 */}
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={ruleInput.dryRun}
            onChange={(e) => setRuleInput({ ...ruleInput, dryRun: e.target.checked })}
          />
          <span>
            试运行（勾上 = 只匹配、只算出「本来会变成多少」，**不产生实际调整**）
          </span>
        </label>

        {ruleErrors.length > 0 ? (
          <ul className="text-destructive text-sm flex flex-col gap-1">
            {ruleErrors.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        ) : null}
        <button
          type="button"
          className="rounded border px-3 py-1 text-sm self-start"
          disabled={busy}
          onClick={() => void onSaveRule()}
        >
          登记规则
        </button>
      </Section>
    </main>
  );
}

function updateCondition(
  input: RuleInput,
  setInput: (value: RuleInput) => void,
  index: number,
  patch: Partial<RuleConditionInput>,
) {
  const conditions = input.conditions.map((condition, i) =>
    i === index ? { ...condition, ...patch } : condition,
  );
  setInput({ ...input, conditions });
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded border p-4 flex flex-col gap-3">
      <h2 className="font-medium">{title}</h2>
      {children}
    </section>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span>{label}</span>
      {children}
    </label>
  );
}

function errorText(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return '请求失败，请稍后重试';
}
