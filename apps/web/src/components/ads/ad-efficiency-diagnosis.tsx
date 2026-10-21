'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  actionAmountText,
  actionExecutable,
  actionModeLabel,
  actionTypeLabel,
  applyAdDiagnosisAction,
  dateText,
  diagnosisStatusLabel,
  emptyAdDiagnosisRuleInput,
  emptyStateText,
  fetchAdDiagnosisActions,
  fetchAdDiagnosisCapabilities,
  fetchAdDiagnosisRules,
  fetchAdDiagnosisRun,
  fetchAdDiagnosisRuns,
  fetchAdDiagnoses,
  initAdDiagnosisRuleDefaults,
  money,
  outcomeLabel,
  outcomeTone,
  quantity,
  ratioText,
  ruleTypeLabel,
  runAdDiagnosis,
  saveAdDiagnosisRule,
  severityLabel,
  severityTone,
  setAdDiagnosisRuleStatus,
  timestampText,
  unavailableText,
  validateAdDiagnosisRule,
} from './ad-efficiency-diagnosis-model';
import type {
  AdDiagnosisActionPage,
  AdDiagnosisActionRow,
  AdDiagnosisCapabilities,
  AdDiagnosisPage,
  AdDiagnosisRuleInput,
  AdDiagnosisRulePage,
  AdDiagnosisRunDetail,
  AdDiagnosisRunPage,
} from './ad-efficiency-diagnosis-model';

/**
 * 14.3.2.2 效率诊断。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **诊断要给动作而不是只报警**。每条结论都带「判定依据 + 可执行建议 + 建议动作」，
 *    而且预算 / 暂停类动作**现在就能点**（走 14.1.1.1 已交付的写路径，带上限、审计与幂等）。
 * 2. **「不可用」写清楚责任模块**。竞价调整待 14.2.1.1 交付后开放 —— 页面**不给**一个点了
 *    没用的按钮，而是把「为什么现在不能做、什么时候能做」写出来。
 * 3. **「不可比」绝不显示成 0**。销售额为 0 时 ACOS 是不可比（显示「不可比」），
 *    不是 0.00% —— 后者会让「烧了 210 块、零订单」看起来像效率极高。
 * 4. **「没产出结论」与「没跑」分开说**。创意衰退规则被跳过时，运行记录里写明
 *    「数据源未就绪」，而不是让它看起来像「跑了但没发现问题」。
 */
export function AdEfficiencyDiagnosisWorkspace() {
  const [capabilities, setCapabilities] = useState<AdDiagnosisCapabilities | null>(null);
  const [rules, setRules] = useState<AdDiagnosisRulePage | null>(null);
  const [runs, setRuns] = useState<AdDiagnosisRunPage | null>(null);
  const [runDetail, setRunDetail] = useState<AdDiagnosisRunDetail | null>(null);
  const [diagnoses, setDiagnoses] = useState<AdDiagnosisPage | null>(null);
  const [actions, setActions] = useState<AdDiagnosisActionPage | null>(null);

  const [ruleType, setRuleType] = useState('');
  const [severity, setSeverity] = useState('');
  const [actionMode, setActionMode] = useState('');

  const [ruleInput, setRuleInput] = useState<AdDiagnosisRuleInput>(emptyAdDiagnosisRuleInput);
  const [ruleErrors, setRuleErrors] = useState<string[]>([]);
  const [editing, setEditing] = useState(false);

  const [applyTarget, setApplyTarget] = useState<AdDiagnosisActionRow | null>(null);
  const [applyDryRun, setApplyDryRun] = useState(true);
  const [applyReason, setApplyReason] = useState('');
  const [applyResult, setApplyResult] = useState<string>('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [rulePage, runPage, diagnosisPage, actionPage] = await Promise.all([
        fetchAdDiagnosisRules(undefined, undefined, 0, 100),
        fetchAdDiagnosisRuns(0, 20),
        fetchAdDiagnoses({
          ruleType: ruleType || undefined,
          severity: severity || undefined,
          page: 0,
          size: 50,
        }),
        fetchAdDiagnosisActions({ actionMode: actionMode || undefined, page: 0, size: 100 }),
      ]);
      setRules(rulePage);
      setRuns(runPage);
      setDiagnoses(diagnosisPage);
      setActions(actionPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [ruleType, severity, actionMode]);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const caps = await fetchAdDiagnosisCapabilities();
        if (alive) setCapabilities(caps);
      } catch (e) {
        if (alive) setError(errorText(e));
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const undelivered = useMemo(
    () => (capabilities?.dimensions ?? []).filter((d) => !d.available),
    [capabilities],
  );

  async function onInitDefaults() {
    setBusy(true);
    setError('');
    setApplyResult('');
    try {
      const result = await initAdDiagnosisRuleDefaults();
      setApplyResult(
        `已初始化 ${result.created} 条默认规则（本租户原有 ${result.existing} 条，未被覆盖）。${result.note}`,
      );
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onToggleRule(id: string, enabled: boolean) {
    setBusy(true);
    setError('');
    try {
      await setAdDiagnosisRuleStatus(id, enabled ? 'ENABLED' : 'DISABLED');
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onSaveRule() {
    const validation = validateAdDiagnosisRule(ruleInput);
    setRuleErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      await saveAdDiagnosisRule(ruleInput);
      setEditing(false);
      setRuleInput(emptyAdDiagnosisRuleInput);
      setRuleErrors([]);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onRun() {
    setBusy(true);
    setError('');
    setApplyResult('');
    try {
      const detail = await runAdDiagnosis(null, true);
      setRunDetail(detail);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onShowRun(id: string) {
    setBusy(true);
    setError('');
    try {
      setRunDetail(await fetchAdDiagnosisRun(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onApply() {
    if (!applyTarget) return;
    setBusy(true);
    setError('');
    try {
      const result = await applyAdDiagnosisAction(applyTarget.id, applyDryRun, applyReason.trim());
      setApplyResult(
        `${outcomeLabel(result.outcome)}：${result.message}${result.guardrailNote ? `（${result.guardrailNote}）` : ''}`,
      );
      setApplyTarget(null);
      setApplyReason('');
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const emptyText = emptyStateText(diagnoses?.emptyReason ?? null, diagnoses?.emptyNote ?? '');

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">效率诊断</h1>
        <p className="text-muted-foreground max-w-4xl text-sm">
          诊断投放效率问题：高花费零转化、ACOS 长期超标、预算受限、竞价过低曝光不足、创意衰退。
          <strong>每条结论都带判定依据与可执行建议</strong>，预算 / 暂停类建议可以一键调整
          （带上限、审计与幂等）；竞价类建议要等 14.2.1.1 竞价管理交付后才开放。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
      {applyResult ? <p className="text-sm">{applyResult}</p> : null}

      {/* ================= 能力声明 ================= */}
      <Section title="能力声明">
        {loading && !capabilities ? (
          <p className="text-muted-foreground text-sm">正在加载能力声明…</p>
        ) : (
          <>
            <ul className="flex flex-col gap-1 text-sm">
              {(capabilities?.dimensions ?? []).map((d) => (
                <li key={d.dimension}>
                  <span className={d.available ? 'text-emerald-600' : 'text-amber-600'}>
                    {d.available ? '● 已交付' : '○ 未交付'}
                  </span>
                  <span className="ml-2 font-medium">{d.description}</span>
                  {!d.available ? (
                    <span className="text-muted-foreground ml-2 text-xs">
                      责任模块：{d.ownerModule}；{d.impact}
                    </span>
                  ) : (
                    <span className="text-muted-foreground ml-2 text-xs">来源：{d.source}</span>
                  )}
                </li>
              ))}
            </ul>
            {undelivered.length > 0 ? (
              <p className="text-amber-600 text-xs">
                🔴 上面标「未交付」的段落在本页**不可用**：{undelivered.map((d) => d.dimension).join('、')}
                。页面不给一个点了没用的按钮 —— 不可用的建议动作会写明责任模块与影响。
              </p>
            ) : null}
            {capabilities ? (
              <>
                <p className="text-muted-foreground text-xs">{capabilities.ruleDefinitionNote}</p>
                <p className="text-muted-foreground text-xs">{capabilities.metricDefinitionNote}</p>
                <p className="text-muted-foreground text-xs">{capabilities.actionNote}</p>
                <p className="text-muted-foreground text-xs">{capabilities.guardrailNote}</p>
                <p className="text-muted-foreground text-xs">{capabilities.upstreamNote}</p>
                <p className="text-muted-foreground text-xs">{capabilities.boundaryNote}</p>
              </>
            ) : null}
          </>
        )}
      </Section>

      {/* ================= 诊断规则 ================= */}
      <Section title="诊断规则（阈值可配）">
        <div className="flex flex-wrap items-end gap-2">
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            disabled={busy}
            onClick={() => void onInitDefaults()}
          >
            初始化默认规则
          </button>
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            disabled={busy}
            onClick={() => {
              setEditing(!editing);
              setRuleErrors([]);
            }}
          >
            {editing ? '收起规则表单' : '登记 / 修改规则'}
          </button>
        </div>

        {editing ? (
          <div className="flex flex-col gap-2">
            <div className="grid grid-cols-1 gap-2 md:grid-cols-3">
              <label className="flex flex-col text-xs">
                规则编码
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.ruleCode}
                  onChange={(e) => setRuleInput({ ...ruleInput, ruleCode: e.target.value })}
                />
              </label>
              <label className="flex flex-col text-xs">
                判定口径
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.ruleType}
                  onChange={(e) => setRuleInput({ ...ruleInput, ruleType: e.target.value })}
                >
                  {(capabilities?.ruleTypes ?? []).map((t) => (
                    <option key={t} value={t}>
                      {ruleTypeLabel(t)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col text-xs">
                严重度
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.severity}
                  onChange={(e) => setRuleInput({ ...ruleInput, severity: e.target.value })}
                >
                  {(capabilities?.severities ?? []).map((s) => (
                    <option key={s} value={s}>
                      {severityLabel(s)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col text-xs">
                观察窗口（天）
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.lookbackDays}
                  onChange={(e) => setRuleInput({ ...ruleInput, lookbackDays: e.target.value })}
                />
              </label>
              <label className="flex flex-col text-xs">
                最少需要的天数
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.minMetricDays}
                  onChange={(e) => setRuleInput({ ...ruleInput, minMetricDays: e.target.value })}
                />
              </label>
              <label className="flex flex-col text-xs">
                建议动作
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.actionType}
                  onChange={(e) => setRuleInput({ ...ruleInput, actionType: e.target.value })}
                >
                  {(capabilities?.actionTypes ?? []).map((a) => (
                    <option key={a} value={a}>
                      {actionTypeLabel(a)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col text-xs">
                金额门槛（高花费零转化用）
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.thresholdAmount}
                  onChange={(e) => setRuleInput({ ...ruleInput, thresholdAmount: e.target.value })}
                />
              </label>
              <label className="flex flex-col text-xs">
                目标 ACOS（ACOS 超标用）
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.thresholdRatio}
                  onChange={(e) => setRuleInput({ ...ruleInput, thresholdRatio: e.target.value })}
                />
              </label>
              <label className="flex flex-col text-xs">
                超标天数下限（ACOS 超标用）
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.minOverTargetDays}
                  onChange={(e) => setRuleInput({ ...ruleInput, minOverTargetDays: e.target.value })}
                />
              </label>
              <label className="flex flex-col text-xs">
                预算利用率下限（预算受限用）
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.budgetUtilizationRatio}
                  onChange={(e) =>
                    setRuleInput({ ...ruleInput, budgetUtilizationRatio: e.target.value })
                  }
                />
              </label>
              <label className="flex flex-col text-xs">
                曝光相对下限（曝光不足用）
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.deliveryRatio}
                  onChange={(e) => setRuleInput({ ...ruleInput, deliveryRatio: e.target.value })}
                />
              </label>
              <label className="flex flex-col text-xs">
                建议幅度（%，调预算用）
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleInput.actionPercent}
                  onChange={(e) => setRuleInput({ ...ruleInput, actionPercent: e.target.value })}
                />
              </label>
            </div>
            <label className="flex items-center gap-2 text-xs">
              <input
                type="checkbox"
                checked={ruleInput.dryRun}
                onChange={(e) => setRuleInput({ ...ruleInput, dryRun: e.target.checked })}
              />
              规则级试运行（开启 ⇒ 这条规则只出建议、不落地）
            </label>
            <label className="flex items-center gap-2 text-xs">
              <input
                type="checkbox"
                checked={ruleInput.dataSourceReady}
                onChange={(e) => setRuleInput({ ...ruleInput, dataSourceReady: e.target.checked })}
              />
              数据源已就绪（创意衰退必须不勾）
            </label>
            <div className="flex flex-col gap-1">
              {ruleErrors.map((e) => (
                <p key={e} className="text-destructive text-xs">
                  {e}
                </p>
              ))}
            </div>
            <div className="flex gap-2">
              <button
                type="button"
                className="rounded border px-3 py-1 text-sm"
                disabled={busy}
                onClick={() => void onSaveRule()}
              >
                保存规则
              </button>
              <button
                type="button"
                className="rounded border px-3 py-1 text-sm"
                disabled={busy}
                onClick={() => {
                  setEditing(false);
                  setRuleErrors([]);
                }}
              >
                取消
              </button>
            </div>
          </div>
        ) : null}

        {loading && !rules ? (
          <p className="text-muted-foreground text-sm">正在加载诊断规则…</p>
        ) : rules && rules.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有诊断规则 —— 点「初始化默认规则」建一套，或逐条登记。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">判定口径</th>
                <th className="py-1">规则</th>
                <th className="py-1">严重度</th>
                <th className="py-1">窗口 / 最少天数</th>
                <th className="py-1">阈值</th>
                <th className="py-1">建议动作</th>
                <th className="py-1">状态</th>
                <th className="py-1" />
              </tr>
            </thead>
            <tbody>
              {(rules?.items ?? []).map((rule) => (
                <tr key={rule.id} className="border-t">
                  <td className="py-1">{ruleTypeLabel(rule.ruleType)}</td>
                  <td className="py-1">
                    {rule.ruleName || rule.ruleCode}
                    {rule.dataSourceReady ? null : (
                      <span className="text-amber-600 ml-2 text-xs">数据源未就绪</span>
                    )}
                  </td>
                  <td className={`py-1 ${severityTone(rule.severity)}`}>
                    {severityLabel(rule.severity)}
                  </td>
                  <td className="py-1">
                    {rule.lookbackDays} 天 / {rule.minMetricDays} 天
                  </td>
                  <td className="py-1 text-xs">
                    {rule.thresholdAmount !== null ? `金额 ≥ ${money(rule.thresholdAmount)}` : null}
                    {rule.thresholdRatio !== null ? `ACOS 目标 ${ratioText(rule.thresholdRatio)}` : null}
                    {rule.budgetUtilizationRatio !== null
                      ? `利用率 ≥ ${ratioText(rule.budgetUtilizationRatio)}`
                      : null}
                    {rule.deliveryRatio !== null
                      ? `曝光 ≥ ${ratioText(rule.deliveryRatio)} 中位数`
                      : null}
                    {rule.minOverTargetDays > 0 ? `超标 ≥ ${rule.minOverTargetDays} 天` : null}
                  </td>
                  <td className="py-1">
                    {actionTypeLabel(rule.actionType)}
                    {rule.dryRun ? <span className="text-muted-foreground ml-1 text-xs">试运行</span> : null}
                  </td>
                  <td className="py-1">{rule.enabled ? '启用' : '停用'}</td>
                  <td className="py-1 text-right">
                    <button
                      type="button"
                      className="rounded border px-2 py-1 text-xs"
                      disabled={busy}
                      onClick={() => void onToggleRule(rule.id, !rule.enabled)}
                    >
                      {rule.enabled ? '停用' : '启用'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      {/* ================= 诊断运行 ================= */}
      <Section title="诊断运行">
        <div className="flex flex-wrap items-end gap-2">
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            disabled={busy}
            onClick={() => void onRun()}
          >
            跑一次诊断
          </button>
          {runs && runs.items.length > 0 ? (
            <select
              className="rounded border px-2 py-1 text-sm"
              value=""
              onChange={(e) => {
                if (e.target.value) void onShowRun(e.target.value);
              }}
            >
              <option value="">查看某次运行…</option>
              {runs.items.map((r) => (
                <option key={r.id} value={r.id}>
                  {timestampText(r.createdAt)} · {r.diagnosisCount} 条结论 · 跳过 {r.skippedRuleCount} 条规则
                </option>
              ))}
            </select>
          ) : null}
        </div>

        {runDetail ? (
          <div className="flex flex-col gap-2">
            <p className="text-sm">
              本次运行：窗口 {dateText(runDetail.run.windowFrom)} ~ {dateText(runDetail.run.windowTo)}，
              评估 {runDetail.run.enabledRuleCount} 条规则（跳过 {runDetail.run.skippedRuleCount} 条），
              产出 <strong>{runDetail.run.diagnosisCount}</strong> 条结论，数据截至{' '}
              {runDetail.run.dataAsOf ? timestampText(runDetail.run.dataAsOf) : '—'}。
            </p>
            {runDetail.run.message ? (
              <p className="text-amber-600 text-xs">{runDetail.run.message}</p>
            ) : null}
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">规则</th>
                  <th className="py-1">是否评估</th>
                  <th className="py-1">命中</th>
                  <th className="py-1">说明</th>
                </tr>
              </thead>
              <tbody>
                {runDetail.ruleResults.map((r) => (
                  <tr key={r.id} className="border-t">
                    <td className="py-1">{ruleTypeLabel(r.ruleType)}</td>
                    <td className={`py-1 ${r.evaluated ? '' : 'text-amber-600'}`}>
                      {r.evaluated ? '已评估' : '未评估'}
                    </td>
                    <td className="py-1">{r.matchedCampaignCount}</td>
                    <td className="text-muted-foreground py-1 text-xs">
                      {r.skipReason || r.noMatchReason || '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Section>

      {/* ================= 诊断清单 ================= */}
      <Section title="诊断清单">
        <div className="flex flex-wrap gap-2">
          <select
            className="rounded border px-2 py-1 text-sm"
            value={ruleType}
            onChange={(e) => setRuleType(e.target.value)}
          >
            <option value="">全部判定口径</option>
            {(capabilities?.ruleTypes ?? []).map((t) => (
              <option key={t} value={t}>
                {ruleTypeLabel(t)}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={severity}
            onChange={(e) => setSeverity(e.target.value)}
          >
            <option value="">全部严重度</option>
            {(capabilities?.severities ?? []).map((s) => (
              <option key={s} value={s}>
                {severityLabel(s)}
              </option>
            ))}
          </select>
        </div>

        {loading && !diagnoses ? (
          <p className="text-muted-foreground text-sm">正在加载诊断结论…</p>
        ) : diagnoses && diagnoses.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">{emptyText}</p>
        ) : (
          <ul className="flex flex-col gap-3">
            {(diagnoses?.items ?? []).map((d) => (
              <li key={d.id} className="rounded border p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className={`font-medium ${severityTone(d.severity)}`}>
                    {severityLabel(d.severity)}
                  </span>
                  <span className="font-medium">{d.title}</span>
                  <span className="text-muted-foreground text-xs">
                    {ruleTypeLabel(d.ruleType)} · {d.platformCode} / {d.shopCode} ·{' '}
                    {diagnosisStatusLabel(d.status)}
                  </span>
                </div>
                <p className="mt-1 text-xs">
                  🔴 判定依据：{d.evidence}
                </p>
                <p className="mt-1 text-xs">💡 建议：{d.recommendation}</p>
                <p className="text-muted-foreground mt-1 text-xs">
                  观察窗口 {dateText(d.windowFrom)} ~ {dateText(d.windowTo)}（{d.metricDays} 天有数据）·
                  曝光 {quantity(d.impressions)} · 点击 {quantity(d.clicks)} · 订单{' '}
                  {quantity(d.orders)} · 消耗 {money(d.spend)} · 销售额 {money(d.sales)} · ACOS{' '}
                  {ratioText(d.acos)} · ROAS {ratioText(d.roas)} · 日预算 {money(d.dailyBudget)} ·
                  预算利用率 {ratioText(d.budgetUtilization)} · 曝光相对倍数{' '}
                  {ratioText(d.deliveryRatio)}
                  {d.overTargetDays > 0 ? ` · 超标 ${d.overTargetDays} 天` : null}
                </p>
              </li>
            ))}
          </ul>
        )}
      </Section>

      {/* ================= 建议动作与一键调整 ================= */}
      <Section title="建议动作与一键调整">
        <div className="flex flex-wrap gap-2">
          <select
            className="rounded border px-2 py-1 text-sm"
            value={actionMode}
            onChange={(e) => setActionMode(e.target.value)}
          >
            <option value="">全部动作模式</option>
            {(capabilities?.actionModes ?? []).map((m) => (
              <option key={m} value={m}>
                {actionModeLabel(m)}
              </option>
            ))}
          </select>
        </div>

        {loading && !actions ? (
          <p className="text-muted-foreground text-sm">正在加载建议动作…</p>
        ) : actions && actions.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有建议动作 —— 先跑一次诊断。</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {(actions?.items ?? []).map((a) => (
              <li key={a.id} className="flex flex-wrap items-center gap-2 rounded border p-2 text-sm">
                <span className="font-medium">{actionTypeLabel(a.actionType)}</span>
                <span className="text-muted-foreground text-xs">{a.campaignName}</span>
                <span className={`text-xs ${outcomeTone(a.outcome)}`}>{outcomeLabel(a.outcome)}</span>
                <span className="text-muted-foreground text-xs">
                  {actionModeLabel(a.actionMode)}
                  {a.actionMode === 'UNAVAILABLE' ? ` · ${unavailableText(a)}` : null}
                  {a.actionMode === 'NAVIGATE' ? ` · ${unavailableText(a)}` : null}
                </span>
                {a.beforeValue !== null || a.afterValue !== null ? (
                  <span className="text-muted-foreground text-xs">
                    预算 {money(a.beforeValue)} → {money(a.afterValue)}
                    {a.actionPercent !== null ? `（${actionAmountText(a.actionPercent, a.actionAmount)}）` : null}
                  </span>
                ) : null}
                {a.message ? <span className="text-muted-foreground text-xs">{a.message}</span> : null}
                {a.guardrailNote ? (
                  <span className="text-amber-600 text-xs">{a.guardrailNote}</span>
                ) : null}
                {actionExecutable(a) ? (
                  <button
                    type="button"
                    className="ml-auto rounded border px-2 py-1 text-xs"
                    disabled={busy}
                    onClick={() => {
                      setApplyTarget(a);
                      setApplyDryRun(true);
                      setApplyResult('');
                    }}
                  >
                    一键调整
                  </button>
                ) : (
                  <span className="text-muted-foreground ml-auto text-xs">{unavailableText(a)}</span>
                )}
              </li>
            ))}
          </ul>
        )}

        {applyTarget ? (
          <div className="flex flex-col gap-2 rounded border p-3">
            <p className="text-sm">
              对「{applyTarget.campaignName}」执行
              <strong>{actionTypeLabel(applyTarget.actionType)}</strong>
              ：预算 {money(applyTarget.beforeValue)} → {money(applyTarget.afterValue)}。
            </p>
            <label className="flex items-center gap-2 text-xs">
              <input
                type="checkbox"
                checked={applyDryRun}
                onChange={(e) => setApplyDryRun(e.target.checked)}
              />
              试运行（只算出「本来会变成多少」，不真的调）
            </label>
            <label className="flex flex-col text-xs">
              操作理由（写进审计）
              <input
                className="rounded border px-2 py-1 text-sm"
                value={applyReason}
                onChange={(e) => setApplyReason(e.target.value)}
              />
            </label>
            <p className="text-muted-foreground text-xs">
              🔴 真实执行会走 14.1.1.1 已交付的写路径：单次 / 总额 / 单活动预算上限越限
              ⇒ **拒绝并留痕**（不静默截断）；已执行过的动作重复提交 ⇒ 跳过。
              本次改动只落本地并标记「待回写平台」。
            </p>
            <div className="flex gap-2">
              <button
                type="button"
                className="rounded border px-3 py-1 text-sm"
                disabled={busy}
                onClick={() => void onApply()}
              >
                {applyDryRun ? '试运行（只算不调）' : '确认执行'}
              </button>
              <button
                type="button"
                className="rounded border px-3 py-1 text-sm"
                disabled={busy}
                onClick={() => setApplyTarget(null)}
              >
                取消
              </button>
            </div>
          </div>
        ) : null}
      </Section>
    </main>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded border p-4">
      <h2 className="font-medium">{title}</h2>
      {children}
    </section>
  );
}
