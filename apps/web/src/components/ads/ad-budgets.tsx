'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  applyAdBudgetTask,
  computeAdBudgetPlan,
  createAdBudgetTask,
  deltaMoney,
  emptyAdBudgetPlanInput,
  emptyAdBudgetPoolInput,
  emptyAdBudgetRuleInput,
  fetchAdBudgetCapabilities,
  fetchAdBudgetOptions,
  fetchAdBudgetPlan,
  fetchAdBudgetPlans,
  fetchAdBudgetPool,
  fetchAdBudgetPools,
  fetchAdBudgetRule,
  fetchAdBudgetRules,
  fetchAdBudgetTask,
  fetchAdBudgetTasks,
  guardrailText,
  money,
  planStatusLabel,
  ratioText,
  ratioTone,
  recommendationLabel,
  recommendationTone,
  ruleStatusLabel,
  saveAdBudgetPool,
  saveAdBudgetRule,
  skipReasonLabel,
  taskResultLabel,
  taskResultTone,
  taskStatusLabel,
  taskStatusTone,
  timestampText,
  validateAdBudgetPlan,
  validateAdBudgetPool,
  validateAdBudgetRule,
  weightSourceLabel,
  weightSourceTone,
} from './ad-budget-model';
import type {
  AdBudgetCapabilities,
  AdBudgetOptions,
  AdBudgetPlanDetail,
  AdBudgetPlanPage,
  AdBudgetPoolPage,
  AdBudgetPoolRow,
  AdBudgetRuleDetail,
  AdBudgetRulePage,
  AdBudgetTaskDetail,
  AdBudgetTaskPage,
} from './ad-budget-model';

/**
 * 14.2.2.1 预算分配。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **利润维度当前不可用，必须常驻说明**。上游 13.4 利润分析未交付，规则里的利润权重位
 *    **恒为 0**（DB 层 CHECK 钉死）。页面要让运营看见「现在只按销售目标与历史 ROAS 分配」，
 *    而不是让人以为「按利润分配」已经生效。
 * 2. **闲置预算与预算受限是两种相反的事实**，分开显示：闲置 = 有预算花不出去，
 *    受限 = 想花没预算。混成一个「预算利用率」数字就没人能回答「该给谁加钱」。
 * 3. **没参与分配的活动要看得见**（逐条列明原因），不静默丢掉。
 * 4. **「待执行」不是「已生效」**。执行任务只落**本地**预算并标记「待回写平台」
 *    （平台回写队列未交付），被护栏拒绝的条目照样列出来。
 */
export function AdBudgetWorkspace() {
  const [capabilities, setCapabilities] = useState<AdBudgetCapabilities | null>(null);
  const [options, setOptions] = useState<AdBudgetOptions | null>(null);

  const [rules, setRules] = useState<AdBudgetRulePage | null>(null);
  const [ruleDetail, setRuleDetail] = useState<AdBudgetRuleDetail | null>(null);
  const [ruleInput, setRuleInput] = useState(emptyAdBudgetRuleInput);
  const [ruleErrors, setRuleErrors] = useState<string[]>([]);

  const [pools, setPools] = useState<AdBudgetPoolPage | null>(null);
  const [poolDetail, setPoolDetail] = useState<AdBudgetPoolRow | null>(null);
  const [poolInput, setPoolInput] = useState(emptyAdBudgetPoolInput);
  const [poolErrors, setPoolErrors] = useState<string[]>([]);

  const [plans, setPlans] = useState<AdBudgetPlanPage | null>(null);
  const [planDetail, setPlanDetail] = useState<AdBudgetPlanDetail | null>(null);
  const [planInput, setPlanInput] = useState(emptyAdBudgetPlanInput);
  const [planErrors, setPlanErrors] = useState<string[]>([]);

  const [tasks, setTasks] = useState<AdBudgetTaskPage | null>(null);
  const [taskDetail, setTaskDetail] = useState<AdBudgetTaskDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [rulePage, poolPage, planPage, taskPage] = await Promise.all([
        fetchAdBudgetRules({ page: 0, size: 50 }),
        fetchAdBudgetPools({ page: 0, size: 50 }),
        fetchAdBudgetPlans({ page: 0, size: 50 }),
        fetchAdBudgetTasks({ page: 0, size: 50 }),
      ]);
      setRules(rulePage);
      setPools(poolPage);
      setPlans(planPage);
      setTasks(taskPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts] = await Promise.all([
          fetchAdBudgetCapabilities(),
          fetchAdBudgetOptions(),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
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

  const guardrails = options?.guardrails ?? capabilities?.guardrails ?? null;
  const profitDimension = useMemo(
    () => capabilities?.dimensions.find((d) => d.dimension === 'profit_weight') ?? null,
    [capabilities],
  );

  async function onSaveRule() {
    const validation = validateAdBudgetRule(ruleInput);
    setRuleErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const weights = [
        {
          weightSource: 'SALES_TARGET',
          weight: Number(ruleInput.salesTargetWeight || '0'),
          note: '销售目标权重',
        },
        {
          weightSource: 'ROAS',
          weight: Number(ruleInput.roasWeight || '0'),
          note: '历史 ROAS 权重',
        },
        {
          weightSource: 'PROFIT',
          weight: Number(ruleInput.profitWeight || '0'),
          note: '利润权重位：13.4 利润分析未交付，当前恒为 0',
        },
      ];
      const targets =
        ruleInput.targetCampaignId.trim() === '' && ruleInput.targetSalesTarget.trim() === ''
          ? []
          : [
              {
                campaignId: ruleInput.targetCampaignId.trim(),
                salesTarget: Number(ruleInput.targetSalesTarget),
                note: '',
              },
            ];
      const detail = await saveAdBudgetRule({
        ruleCode: ruleInput.ruleCode.trim(),
        ruleName: ruleInput.ruleName.trim(),
        currency: ruleInput.currency.trim().toUpperCase(),
        scopeShopCode: ruleInput.scopeShopCode.trim() || undefined,
        minCampaignBudget:
          ruleInput.minCampaignBudget.trim() === ''
            ? undefined
            : Number(ruleInput.minCampaignBudget),
        maxCampaignBudget:
          ruleInput.maxCampaignBudget.trim() === ''
            ? undefined
            : Number(ruleInput.maxCampaignBudget),
        constrainedUtilization: Number(ruleInput.constrainedUtilization),
        idleUtilization: Number(ruleInput.idleUtilization),
        weights,
        targets,
        note: '',
      });
      setRuleDetail(detail);
      setRuleInput(emptyAdBudgetRuleInput);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onSavePool() {
    const validation = validateAdBudgetPool(poolInput);
    setPoolErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const pool = await saveAdBudgetPool({
        poolCode: poolInput.poolCode.trim(),
        poolName: poolInput.poolName.trim(),
        currency: poolInput.currency.trim().toUpperCase(),
        totalAmount: Number(poolInput.totalAmount),
        periodStart: poolInput.periodStart,
        periodEnd: poolInput.periodEnd,
        ruleId: poolInput.ruleId.trim(),
        note: poolInput.note.trim(),
      });
      setPoolDetail(pool);
      setPoolInput(emptyAdBudgetPoolInput);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onComputePlan() {
    const validation = validateAdBudgetPlan(planInput);
    setPlanErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const detail = await computeAdBudgetPlan({
        poolId: planInput.poolId.trim(),
        windowStart: planInput.windowStart,
        windowEnd: planInput.windowEnd,
        note: planInput.note.trim(),
      });
      setPlanDetail(detail);
      setPlanInput(emptyAdBudgetPlanInput);
      await load();
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
      setRuleDetail(await fetchAdBudgetRule(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onOpenPool(id: string) {
    setBusy(true);
    setError('');
    try {
      setPoolDetail(await fetchAdBudgetPool(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onOpenPlan(id: string) {
    setBusy(true);
    setError('');
    try {
      setPlanDetail(await fetchAdBudgetPlan(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onOpenTask(id: string) {
    setBusy(true);
    setError('');
    try {
      setTaskDetail(await fetchAdBudgetTask(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onCreateTask(planId: string) {
    setBusy(true);
    setError('');
    try {
      const detail = await createAdBudgetTask({ planId, reason: '预算分配执行任务' });
      setTaskDetail(detail);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onApplyTask(id: string) {
    setBusy(true);
    setError('');
    try {
      const detail = await applyAdBudgetTask(id);
      setTaskDetail(detail);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">广告预算分配</h1>
        <p className="text-muted-foreground max-w-4xl text-sm">
          跨店铺 / 跨活动的预算分配与再平衡：按<strong>销售目标</strong>与
          <strong>历史 ROAS</strong> 分配预算，识别<strong>预算受限</strong>（想花没预算）与
          <strong>闲置预算</strong>（有预算花不出去）。输出三样：分配方案、再平衡建议、执行任务。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      {/* ================= 🔴 利润维度当前不可用（常驻） ================= */}
      <Section title="能力声明">
        {capabilities && profitDimension && !profitDimension.available ? (
          <p className="rounded border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
            <strong>🔴 利润维度当前不可用</strong>：{profitDimension.description}
            <br />
            责任模块：<strong>{profitDimension.ownerModule}</strong>
            <br />
            影响：{profitDimension.impact}
          </p>
        ) : null}
        {capabilities ? (
          <>
            <p className="text-muted-foreground text-sm">{capabilities.allocationNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.utilizationNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.currencyNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.boundaryNote}</p>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">维度</th>
                  <th className="py-1">是否已交付</th>
                  <th className="py-1">来源</th>
                  <th className="py-1">责任模块</th>
                  <th className="py-1">不交付的影响</th>
                </tr>
              </thead>
              <tbody>
                {capabilities.dimensions.map((d) => (
                  <tr key={d.dimension} className="border-t">
                    <td className="py-1">{d.dimension}</td>
                    <td className={`py-1 ${d.available ? 'text-emerald-600' : 'text-amber-600'}`}>
                      {d.available ? '已交付' : '未交付'}
                    </td>
                    <td className="py-1 text-xs">{d.source || '—'}</td>
                    <td className="py-1 text-xs">{d.ownerModule || '—'}</td>
                    <td className="py-1 text-xs">{d.impact || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground text-xs">{guardrailText(guardrails)}</p>
          </>
        ) : (
          <p className="text-muted-foreground text-sm">正在加载能力声明…</p>
        )}
      </Section>

      {/* ================= 分配规则 ================= */}
      <Section title="分配规则">
        {loading && !rules ? (
          <p className="text-muted-foreground text-sm">正在加载分配规则…</p>
        ) : rules && rules.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有分配规则。分配规则决定「按什么分」：销售目标权重、历史 ROAS 权重，
            以及<b>当前不可用</b>的利润权重位。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">编码 / 名称</th>
                <th className="py-1">币种</th>
                <th className="py-1">权重位</th>
                <th className="py-1">销售目标条数</th>
                <th className="py-1">受限 / 闲置阈值</th>
                <th className="py-1">状态</th>
                <th className="py-1">操作</th>
              </tr>
            </thead>
            <tbody>
              {(rules?.items ?? []).map((rule) => (
                <tr key={rule.id} className="border-t">
                  <td className="py-1">
                    {rule.ruleCode} / {rule.ruleName}
                  </td>
                  <td className="py-1">{rule.currency}</td>
                  <td className="py-1 text-xs">
                    {rule.weights.map((w) => (
                      <span key={w.weightSource} className={`mr-2 ${weightSourceTone(w.weightSource)}`}>
                        {weightSourceLabel(w.weightSource)} {w.weight}
                      </span>
                    ))}
                  </td>
                  <td className="py-1">{rule.targetCount}</td>
                  <td className="py-1 text-xs">
                    {ratioText(rule.constrainedUtilization)} / {ratioText(rule.idleUtilization)}
                  </td>
                  <td className="py-1">{ruleStatusLabel(rule.status)}</td>
                  <td className="py-1">
                    <button
                      type="button"
                      className="underline"
                      onClick={() => void onOpenRule(rule.id)}
                    >
                      详情
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {ruleDetail ? (
          <div className="rounded border p-3 text-sm">
            <p>
              <strong>{ruleDetail.rule.ruleCode}</strong> · {ruleDetail.rule.ruleName} ·{' '}
              {ruleDetail.rule.currency}
            </p>
            <p className="text-muted-foreground text-xs">
              {ruleDetail.rule.weights.map((w) => (
                <span key={w.weightSource} className={`mr-3 ${weightSourceTone(w.weightSource)}`}>
                  {weightSourceLabel(w.weightSource)} = {w.weight}
                  {w.note ? `（${w.note}）` : ''}
                </span>
              ))}
            </p>
            {ruleDetail.targets.length === 0 ? (
              <p className="text-muted-foreground text-xs">这条规则还没有登记销售目标。</p>
            ) : (
              <table className="w-full text-xs">
                <thead>
                  <tr className="text-muted-foreground text-left">
                    <th className="py-1">活动</th>
                    <th className="py-1">平台 / 店铺</th>
                    <th className="py-1">销售目标</th>
                    <th className="py-1">下限 / 上限</th>
                  </tr>
                </thead>
                <tbody>
                  {ruleDetail.targets.map((t) => (
                    <tr key={t.id} className="border-t">
                      <td className="py-1">{t.campaignName}</td>
                      <td className="py-1">
                        {t.platformCode} / {t.shopCode}
                      </td>
                      <td className="py-1">{money(t.salesTarget, ruleDetail.rule.currency)}</td>
                      <td className="py-1">
                        {money(t.minBudget)} / {money(t.maxBudget)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        ) : null}

        <div className="grid gap-2 md:grid-cols-3">
          <Field label="规则编码">
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
          <Field label="币种（一个规则一个币种，不折算）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.currency}
              onChange={(e) => setRuleInput({ ...ruleInput, currency: e.target.value })}
            />
          </Field>
          <Field label="销售目标权重">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.salesTargetWeight}
              onChange={(e) => setRuleInput({ ...ruleInput, salesTargetWeight: e.target.value })}
            />
          </Field>
          <Field label="历史 ROAS 权重">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.roasWeight}
              onChange={(e) => setRuleInput({ ...ruleInput, roasWeight: e.target.value })}
            />
          </Field>
          <Field label="利润权重（🔴 13.4 未交付，当前不可用）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.profitWeight}
              onChange={(e) => setRuleInput({ ...ruleInput, profitWeight: e.target.value })}
            />
          </Field>
          <Field label="预算受限阈值（利用率 ≥）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.constrainedUtilization}
              onChange={(e) => setRuleInput({ ...ruleInput, constrainedUtilization: e.target.value })}
            />
          </Field>
          <Field label="闲置阈值（利用率 &lt;）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.idleUtilization}
              onChange={(e) => setRuleInput({ ...ruleInput, idleUtilization: e.target.value })}
            />
          </Field>
          <Field label="限定店铺编码（可空 = 不限）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.scopeShopCode}
              onChange={(e) => setRuleInput({ ...ruleInput, scopeShopCode: e.target.value })}
            />
          </Field>
          <Field label="销售目标 · 活动 id（可空）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.targetCampaignId}
              onChange={(e) => setRuleInput({ ...ruleInput, targetCampaignId: e.target.value })}
            />
          </Field>
          <Field label="销售目标 · 目标额（可空）">
            <input
              className="rounded border px-2 py-1"
              value={ruleInput.targetSalesTarget}
              onChange={(e) => setRuleInput({ ...ruleInput, targetSalesTarget: e.target.value })}
            />
          </Field>
        </div>
        {ruleErrors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {ruleErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        <button
          type="button"
          className="w-fit rounded border px-3 py-1 text-sm"
          disabled={busy}
          onClick={() => void onSaveRule()}
        >
          登记分配规则
        </button>
      </Section>

      {/* ================= 预算池 ================= */}
      <Section title="预算池（预算总额）">
        {loading && !pools ? (
          <p className="text-muted-foreground text-sm">正在加载预算池…</p>
        ) : pools && pools.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有预算池。</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">编码 / 名称</th>
                <th className="py-1">币种</th>
                <th className="py-1">预算总额</th>
                <th className="py-1">周期</th>
                <th className="py-1">分配规则</th>
                <th className="py-1">状态</th>
                <th className="py-1">操作</th>
              </tr>
            </thead>
            <tbody>
              {(pools?.items ?? []).map((pool) => (
                <tr key={pool.id} className="border-t">
                  <td className="py-1">
                    {pool.poolCode} / {pool.poolName}
                  </td>
                  <td className="py-1">{pool.currency}</td>
                  <td className="py-1">{money(pool.totalAmount, pool.currency)}</td>
                  <td className="py-1 text-xs">
                    {pool.periodStart} ~ {pool.periodEnd}
                  </td>
                  <td className="py-1 text-xs">{pool.ruleName}</td>
                  <td className="py-1">{pool.status}</td>
                  <td className="py-1">
                    <button
                      type="button"
                      className="underline"
                      onClick={() => void onOpenPool(pool.id)}
                    >
                      详情
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {poolDetail ? (
          <p className="rounded border p-3 text-sm">
            <strong>{poolDetail.poolName}</strong> · 总额 {money(poolDetail.totalAmount, poolDetail.currency)} ·{' '}
            {poolDetail.periodStart} ~ {poolDetail.periodEnd} · 规则 {poolDetail.ruleName}
          </p>
        ) : null}

        <div className="grid gap-2 md:grid-cols-3">
          <Field label="预算池编码">
            <input
              className="rounded border px-2 py-1"
              value={poolInput.poolCode}
              onChange={(e) => setPoolInput({ ...poolInput, poolCode: e.target.value })}
            />
          </Field>
          <Field label="预算池名称">
            <input
              className="rounded border px-2 py-1"
              value={poolInput.poolName}
              onChange={(e) => setPoolInput({ ...poolInput, poolName: e.target.value })}
            />
          </Field>
          <Field label="币种">
            <input
              className="rounded border px-2 py-1"
              value={poolInput.currency}
              onChange={(e) => setPoolInput({ ...poolInput, currency: e.target.value })}
            />
          </Field>
          <Field label="预算总额">
            <input
              className="rounded border px-2 py-1"
              value={poolInput.totalAmount}
              onChange={(e) => setPoolInput({ ...poolInput, totalAmount: e.target.value })}
            />
          </Field>
          <Field label="周期开始">
            <input
              type="date"
              className="rounded border px-2 py-1"
              value={poolInput.periodStart}
              onChange={(e) => setPoolInput({ ...poolInput, periodStart: e.target.value })}
            />
          </Field>
          <Field label="周期结束">
            <input
              type="date"
              className="rounded border px-2 py-1"
              value={poolInput.periodEnd}
              onChange={(e) => setPoolInput({ ...poolInput, periodEnd: e.target.value })}
            />
          </Field>
          <Field label="分配规则 id">
            <select
              className="rounded border px-2 py-1"
              value={poolInput.ruleId}
              onChange={(e) => setPoolInput({ ...poolInput, ruleId: e.target.value })}
            >
              <option value="">请选择</option>
              {(rules?.items ?? []).map((r) => (
                <option key={r.id} value={r.id}>
                  {r.ruleCode} / {r.ruleName}（{r.currency}）
                </option>
              ))}
            </select>
          </Field>
        </div>
        {poolErrors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {poolErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        <button
          type="button"
          className="w-fit rounded border px-3 py-1 text-sm"
          disabled={busy}
          onClick={() => void onSavePool()}
        >
          登记预算池
        </button>
      </Section>

      {/* ================= 分配方案 ================= */}
      <Section title="分配方案">
        <div className="grid gap-2 md:grid-cols-4">
          <Field label="预算池">
            <select
              className="rounded border px-2 py-1"
              value={planInput.poolId}
              onChange={(e) => setPlanInput({ ...planInput, poolId: e.target.value })}
            >
              <option value="">请选择</option>
              {(pools?.items ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.poolName}（{money(p.totalAmount, p.currency)}）
                </option>
              ))}
            </select>
          </Field>
          <Field label="统计窗口开始（显式给出，不从墙上时钟推导）">
            <input
              type="date"
              className="rounded border px-2 py-1"
              value={planInput.windowStart}
              onChange={(e) => setPlanInput({ ...planInput, windowStart: e.target.value })}
            />
          </Field>
          <Field label="统计窗口结束">
            <input
              type="date"
              className="rounded border px-2 py-1"
              value={planInput.windowEnd}
              onChange={(e) => setPlanInput({ ...planInput, windowEnd: e.target.value })}
            />
          </Field>
          <Field label="备注">
            <input
              className="rounded border px-2 py-1"
              value={planInput.note}
              onChange={(e) => setPlanInput({ ...planInput, note: e.target.value })}
            />
          </Field>
        </div>
        {options?.metricFrom ? (
          <p className="text-muted-foreground text-xs">
            本租户可用的指标日期区间：{options.metricFrom} ~ {options.metricTo ?? '—'}
            （可用它预填统计窗口；窗口必须显式给出，不从墙上时钟推导）
          </p>
        ) : null}
        {planErrors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {planErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        <button
          type="button"
          className="w-fit rounded border px-3 py-1 text-sm"
          disabled={busy}
          onClick={() => void onComputePlan()}
        >
          计算分配方案
        </button>

        {loading && !plans ? (
          <p className="text-muted-foreground text-sm">正在加载分配方案…</p>
        ) : plans && plans.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有分配方案。先登记预算池，再按池与规则计算一份。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">预算池</th>
                <th className="py-1">状态</th>
                <th className="py-1">总额</th>
                <th className="py-1">已分配</th>
                <th className="py-1">未分配</th>
                <th className="py-1">闲置预算</th>
                <th className="py-1">受限缺口</th>
                <th className="py-1">活动数</th>
                <th className="py-1">窗口</th>
                <th className="py-1">操作</th>
              </tr>
            </thead>
            <tbody>
              {(plans?.items ?? []).map((plan) => (
                <tr key={plan.id} className="border-t">
                  <td className="py-1">{plan.poolName}</td>
                  <td className="py-1">{planStatusLabel(plan.status)}</td>
                  <td className="py-1">{money(plan.totalAmount, plan.currency)}</td>
                  <td className="py-1">{money(plan.allocatedAmount, plan.currency)}</td>
                  <td className="py-1">{money(plan.unallocatedAmount, plan.currency)}</td>
                  <td className="py-1">{money(plan.idleBudget, plan.currency)}</td>
                  <td className="py-1">{money(plan.constrainedShortfall, plan.currency)}</td>
                  <td className="py-1">
                    {plan.campaignCount}（未纳入 {plan.skippedCount}）
                  </td>
                  <td className="py-1 text-xs">
                    {plan.windowStart} ~ {plan.windowEnd}
                  </td>
                  <td className="py-1">
                    <button
                      type="button"
                      className="underline"
                      onClick={() => void onOpenPlan(plan.id)}
                    >
                      详情
                    </button>
                    <button
                      type="button"
                      className="ml-2 underline"
                      disabled={busy}
                      onClick={() => void onCreateTask(plan.id)}
                    >
                      生成执行任务
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {planDetail ? (
          <div className="flex flex-col gap-3 rounded border p-3">
            <p className="text-sm">
              <strong>{planDetail.plan.poolName}</strong> · 总额{' '}
              {money(planDetail.plan.totalAmount, planDetail.plan.currency)} · 已分配{' '}
              {money(planDetail.plan.allocatedAmount, planDetail.plan.currency)} · 未分配{' '}
              {money(planDetail.plan.unallocatedAmount, planDetail.plan.currency)} · 释放额{' '}
              {money(planDetail.plan.releasedAmount, planDetail.plan.currency)} · 闲置预算{' '}
              {money(planDetail.plan.idleBudget, planDetail.plan.currency)} · 受限缺口{' '}
              {money(planDetail.plan.constrainedShortfall, planDetail.plan.currency)}
            </p>

            <h3 className="text-sm font-medium">分配明细（逐活动冻结快照）</h3>
            {planDetail.lines.length === 0 ? (
              <p className="text-muted-foreground text-sm">这份方案没有分配明细。</p>
            ) : (
              <table className="w-full text-xs">
                <thead>
                  <tr className="text-muted-foreground text-left">
                    <th className="py-1">活动</th>
                    <th className="py-1">平台 / 店铺</th>
                    <th className="py-1">当前预算</th>
                    <th className="py-1">日均消耗</th>
                    <th className="py-1">利用率</th>
                    <th className="py-1">ROAS</th>
                    <th className="py-1">权重</th>
                    <th className="py-1">分配额</th>
                    <th className="py-1">增减</th>
                    <th className="py-1">判定</th>
                  </tr>
                </thead>
                <tbody>
                  {planDetail.lines.map((line) => (
                    <tr key={line.id} className="border-t">
                      <td className="py-1">{line.campaignName}</td>
                      <td className="py-1">
                        {line.platformCode} / {line.shopCode}
                      </td>
                      <td className="py-1">{money(line.currentBudget, line.currency)}</td>
                      <td className="py-1">{money(line.dailyAvgSpend, line.currency)}</td>
                      <td className={`py-1 ${ratioTone(line.utilization)}`}>
                        {ratioText(line.utilization)}
                      </td>
                      <td className={`py-1 ${ratioTone(line.roas)}`}>{ratioText(line.roas, false)}</td>
                      <td className="py-1">{ratioText(line.weight, false)}</td>
                      <td className="py-1">{money(line.allocatedBudget, line.currency)}</td>
                      <td className="py-1">{deltaMoney(line.budgetDelta, line.currency)}</td>
                      <td className={`py-1 ${recommendationTone(line.recommendation)}`}>
                        {recommendationLabel(line.recommendation)}
                        {line.budgetConstrained ? ' · 预算受限' : ''}
                        {line.idleBudget ? ' · 闲置' : ''}
                        <span className="block text-muted-foreground">{line.reason}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <h3 className="text-sm font-medium">未纳入分配的活动（逐条列明原因）</h3>
            {planDetail.skipped.length === 0 ? (
              <p className="text-muted-foreground text-sm">没有被排除的活动。</p>
            ) : (
              <table className="w-full text-xs">
                <thead>
                  <tr className="text-muted-foreground text-left">
                    <th className="py-1">活动</th>
                    <th className="py-1">平台 / 店铺</th>
                    <th className="py-1">币种</th>
                    <th className="py-1">当前预算</th>
                    <th className="py-1">原因</th>
                  </tr>
                </thead>
                <tbody>
                  {planDetail.skipped.map((row) => (
                    <tr key={row.id} className="border-t">
                      <td className="py-1">{row.campaignName}</td>
                      <td className="py-1">
                        {row.platformCode} / {row.shopCode}
                      </td>
                      <td className="py-1">{row.currency}</td>
                      <td className="py-1">{money(row.currentBudget, row.currency)}</td>
                      <td className="py-1">
                        {skipReasonLabel(row.skipReason)}
                        <span className="block text-muted-foreground">{row.reason}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <h3 className="text-sm font-medium">再平衡建议（总额不变，只改分布）</h3>
            {planDetail.rebalances.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                没有可挪的闲置预算（要么没有闲置的活动，要么没有预算受限的活动）。
              </p>
            ) : (
              <table className="w-full text-xs">
                <thead>
                  <tr className="text-muted-foreground text-left">
                    <th className="py-1">从</th>
                    <th className="py-1">到</th>
                    <th className="py-1">建议调整额</th>
                    <th className="py-1">依据</th>
                  </tr>
                </thead>
                <tbody>
                  {planDetail.rebalances.map((rb) => (
                    <tr key={rb.id} className="border-t">
                      <td className="py-1">{rb.fromCampaignName}</td>
                      <td className="py-1">{rb.toCampaignName}</td>
                      <td className="py-1">{money(rb.amount, planDetail.plan.currency)}</td>
                      <td className="py-1 text-muted-foreground">{rb.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        ) : null}
      </Section>

      {/* ================= 执行任务 ================= */}
      <Section title="执行任务">
        {loading && !tasks ? (
          <p className="text-muted-foreground text-sm">正在加载执行任务…</p>
        ) : tasks && tasks.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有执行任务。在一份分配方案上点「生成执行任务」即可。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">预算池</th>
                <th className="py-1">状态</th>
                <th className="py-1">逐条结果</th>
                <th className="py-1">执行时刻</th>
                <th className="py-1">操作</th>
              </tr>
            </thead>
            <tbody>
              {(tasks?.items ?? []).map((task) => (
                <tr key={task.id} className="border-t">
                  <td className="py-1">{task.poolName}</td>
                  <td className={`py-1 ${taskStatusTone(task.status)}`}>
                    {taskStatusLabel(task.status)}
                  </td>
                  <td className="py-1 text-xs">
                    共 {task.lineCount} 条：生效 {task.appliedCount} · 被拒 {task.rejectedCount} · 跳过{' '}
                    {task.skippedCount}
                  </td>
                  <td className="py-1 text-xs">{timestampText(task.appliedAt)}</td>
                  <td className="py-1">
                    <button
                      type="button"
                      className="underline"
                      onClick={() => void onOpenTask(task.id)}
                    >
                      详情
                    </button>
                    <button
                      type="button"
                      className="ml-2 underline"
                      disabled={busy || task.status !== 'PENDING'}
                      onClick={() => void onApplyTask(task.id)}
                    >
                      执行
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {taskDetail ? (
          <div className="flex flex-col gap-2 rounded border p-3">
            <p className="text-sm">
              <strong>{taskDetail.task.poolName}</strong> ·{' '}
              <span className={taskStatusTone(taskDetail.task.status)}>
                {taskStatusLabel(taskDetail.task.status)}
              </span>{' '}
              · {taskDetail.note}
            </p>
            {taskDetail.lines.length === 0 ? (
              <p className="text-muted-foreground text-sm">这个任务没有明细。</p>
            ) : (
              <table className="w-full text-xs">
                <thead>
                  <tr className="text-muted-foreground text-left">
                    <th className="py-1">活动</th>
                    <th className="py-1">变更前</th>
                    <th className="py-1">目标</th>
                    <th className="py-1">实际生效</th>
                    <th className="py-1">增减</th>
                    <th className="py-1">结果</th>
                    <th className="py-1">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {taskDetail.lines.map((line) => (
                    <tr key={line.id} className="border-t">
                      <td className="py-1">{line.campaignName}</td>
                      <td className="py-1">{money(line.beforeBudget)}</td>
                      <td className="py-1">{money(line.targetBudget)}</td>
                      <td className="py-1">{money(line.appliedBudget)}</td>
                      <td className="py-1">{deltaMoney(line.budgetDelta)}</td>
                      <td className={`py-1 ${taskResultTone(line.result)}`}>
                        {taskResultLabel(line.result)}
                      </td>
                      <td className="py-1 text-muted-foreground">{line.message || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
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

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-muted-foreground text-xs">{label}</span>
      {children}
    </label>
  );
}
