'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  FinancePaymentBatchPage,
  FinancePaymentForecast,
  FinancePaymentMilestoneRuleRow,
  FinancePaymentPlanCapabilities,
  FinancePaymentPlanDetail,
  FinancePaymentPlanOptions,
  FinancePaymentPlanPage,
  FinancePaymentPlanScheduleResult,
  FinancePaymentReminderPage,
  FinancePaymentReminderRow,
  FinancePaymentTermRuleRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  acknowledgePaymentReminder,
  cancelPaymentPlan,
  createPaymentTermRule,
  dismissPaymentReminder,
  fetchPaymentBatches,
  fetchPaymentForecast,
  fetchPaymentMilestoneRules,
  fetchPaymentPlanCapabilities,
  fetchPaymentPlanOptions,
  fetchPaymentPlans,
  fetchPaymentReminders,
  fetchPaymentTermRules,
  forecastNetHint,
  formatMilestoneRatio,
  formatPaymentAmount,
  formatPaymentMoney,
  generatePaymentPlans,
  paymentConditionHint,
  paymentConditionLabel,
  paymentExecutionLabel,
  paymentPlanDateHint,
  paymentPlanEventLabel,
  paymentPlanStatusLabel,
  paymentPlanStatusTone,
  reminderPushLabel,
  reminderStatusLabel,
  reminderStatusTone,
  reminderTypeLabel,
  reminderTypeTone,
  scanPaymentReminders,
  schedulePaymentPlans,
  termBaseKindLabel,
} from './payment-plan-model';

/**
 * 12.1.2.2 付款计划。
 *
 * <p>蓝图 12-finance.md：「按付款条件（账期、里程碑、到期日）生成付款计划与到期提醒，
 * 支持按供应商与到期窗口批量安排付款。」输出：「付款计划表 + 到期提醒 + 资金需求预测。」
 *
 * <p><b>页面围绕那两句加粗句组织</b>：
 *
 * <ol>
 *   <li>「按付款条件（<b>账期、里程碑、到期日</b>）」⇒ 页面第一块是**条件与规则**：
 *       账期规则是租户自己维护的（系统不预置任何一行），里程碑规则带比例；
 *       两种条件都能配、能选，页面把「这条计划是按什么算出来的」显示出来。</li>
 *   <li>「<b>支持按供应商与到期窗口批量安排付款</b>」⇒ 页面有**批量安排**这一段：
 *       选供应商 + 应付到期窗口 + 付款窗口 + 条件，一次落库一批，按币种自动分批。</li>
 *   <li>「<b>付款计划与资金预测联动</b>」⇒ 页面有**资金需求预测**这一段：
 *       按币种 × 到期窗口显示流入 / 流出 / 净额，净流出的窗口标成现金流断裂风险。
 *       计划一改，刷新后预测当场跟着变。</li>
 * </ol>
 *
 * <p><b>页面刻意没有的两个东西</b>：
 *
 * <ol>
 *   <li><b>任何「执行付款」「标记已付」的按钮</b>：付款属 12.2.2.1（未交付）。
 *       页面里的「已安排」只表示这笔钱被排进了某个付款窗口，<b>不是</b>钱已经付出去了。</li>
 *   <li><b>任何「已通知 / 已发送」的显示</b>：推送通道属 1.3（未交付），
 *       提醒只显示真实的处理状态（待处理 / 已确认 / 已忽略）。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有付款计划」。
 */
export function PaymentPlanWorkspace() {
  const [caps, setCaps] = useState<FinancePaymentPlanCapabilities | null>(null);
  const [options, setOptions] = useState<FinancePaymentPlanOptions | null>(null);
  const [page, setPage] = useState<FinancePaymentPlanPage | null>(null);
  const [detail, setDetail] = useState<FinancePaymentPlanDetail | null>(null);
  const [batches, setBatches] = useState<
    import('../../../../../packages/api-contracts/src/index').FinancePaymentBatchPage | null
  >(null);
  const [forecast, setForecast] = useState<FinancePaymentForecast | null>(null);
  const [reminders, setReminders] = useState<FinancePaymentReminderPage | null>(null);
  const [termRules, setTermRules] = useState<FinancePaymentTermRuleRow[]>([]);
  const [milestoneRules, setMilestoneRules] = useState<FinancePaymentMilestoneRuleRow[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [status, setStatus] = useState('');
  const [paymentCondition, setPaymentCondition] = useState('');
  const [currency, setCurrency] = useState('');
  const [counterpartyRef, setCounterpartyRef] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 批量安排
  const [scheduleSupplier, setScheduleSupplier] = useState('');
  const [scheduleDueFrom, setScheduleDueFrom] = useState('');
  const [scheduleDueTo, setScheduleDueTo] = useState('');
  const [scheduleWindowStart, setScheduleWindowStart] = useState('');
  const [scheduleWindowEnd, setScheduleWindowEnd] = useState('');
  const [scheduleCondition, setScheduleCondition] = useState('DUE_DATE');
  const [scheduleTermRuleId, setScheduleTermRuleId] = useState('');
  const [scheduleMilestoneRuleId, setScheduleMilestoneRuleId] = useState('');

  // 生成
  const [generateCondition, setGenerateCondition] = useState('DUE_DATE');
  const [generateTermRuleId, setGenerateTermRuleId] = useState('');
  const [generateMilestoneRuleId, setGenerateMilestoneRuleId] = useState('');

  // 账期规则登记
  const [ruleName, setRuleName] = useState('');
  const [ruleCurrency, setRuleCurrency] = useState('USD');
  const [ruleBaseKind, setRuleBaseKind] = useState('CONFIRMED_DATE');
  const [ruleTermDays, setRuleTermDays] = useState('30');

  // 提醒筛选
  const [reminderStatus, setReminderStatus] = useState('');
  const [reminderType, setReminderType] = useState('');

  /**
   * 筛选参数。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const params = {
    ...(status ? { status } : {}),
    ...(paymentCondition ? { paymentCondition } : {}),
    ...(currency ? { currency } : {}),
    ...(counterpartyRef ? { counterpartyRef } : {}),
    ...(overdueOnly ? { overdueOnly } : {}),
    ...(keyword ? { keyword } : {}),
    page: currentPage,
    size: 50,
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, p, b, f, r, tr, mr] = await Promise.all([
        fetchPaymentPlanCapabilities(),
        fetchPaymentPlanOptions(),
        fetchPaymentPlans(params),
        fetchPaymentBatches({ ...(counterpartyRef ? { counterpartyRef } : {}) }, 0, 50),
        fetchPaymentForecast({ windowDays: 7 }),
        fetchPaymentReminders({
          ...(reminderStatus ? { status: reminderStatus } : {}),
          ...(reminderType ? { reminderType } : {}),
          page: 0,
          size: 50,
        }),
        fetchPaymentTermRules(),
        fetchPaymentMilestoneRules(),
      ]);
      setCaps(c);
      setOptions(o);
      setPage(p);
      setBatches(b);
      setForecast(f);
      setReminders(r);
      setTermRules(tr);
      setMilestoneRules(mr);
    } catch (e) {
      setError(e instanceof Error ? e.message : '付款计划加载失败');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    status,
    paymentCondition,
    currency,
    counterpartyRef,
    overdueOnly,
    keyword,
    currentPage,
    reminderStatus,
    reminderType,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 把一次动作的结果说清楚：**成功了多少、跳过多少、为什么跳过**。 */
  function reportSchedule(result: FinancePaymentPlanScheduleResult, what: string) {
    const parts = [`${what}：新建 ${result.planned} 条`];
    if (result.rearranged > 0) parts.push(`改期 ${result.rearranged} 条`);
    if (result.skipped > 0) parts.push(`跳过 ${result.skipped} 条`);
    if (result.batchCount > 0) parts.push(`分成 ${result.batchCount} 个批次（按币种分批）`);
    setNotice(parts.join('，'));
    setActionError('');
    if (result.warnings.length > 0) {
      setActionError(result.warnings.join('；'));
    }
  }

  async function runSchedule() {
    setActionError('');
    setNotice('');
    try {
      const result = await schedulePaymentPlans({
        counterpartyRef: scheduleSupplier,
        dueFrom: scheduleDueFrom,
        dueTo: scheduleDueTo,
        paymentWindowStart: scheduleWindowStart,
        paymentWindowEnd: scheduleWindowEnd,
        paymentCondition: scheduleCondition as 'TERM' | 'MILESTONE' | 'DUE_DATE',
        termRuleId: scheduleCondition === 'TERM' ? scheduleTermRuleId : null,
        milestoneRuleId: scheduleCondition === 'MILESTONE' ? scheduleMilestoneRuleId : null,
        note: '批量安排',
      });
      reportSchedule(result, '批量安排付款');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '批量安排失败');
    }
  }

  async function runGenerate() {
    setActionError('');
    setNotice('');
    try {
      const result = await generatePaymentPlans({
        paymentCondition: generateCondition as 'TERM' | 'MILESTONE' | 'DUE_DATE',
        termRuleId: generateCondition === 'TERM' ? generateTermRuleId : null,
        milestoneRuleId: generateCondition === 'MILESTONE' ? generateMilestoneRuleId : null,
        counterpartyRef: null,
        dueFrom: null,
        dueTo: null,
        note: '生成计划',
      });
      reportSchedule(result, '生成付款计划');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '生成付款计划失败');
    }
  }

  async function openDetail(id: string) {
    setActionError('');
    try {
      const { fetchPaymentPlan } = await import('./payment-plan-model');
      setDetail(await fetchPaymentPlan(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '付款计划详情加载失败');
    }
  }

  async function runCancel(id: string) {
    const reason = window.prompt('取消原因（必填）');
    if (reason === null || reason.trim() === '') return;
    setActionError('');
    try {
      await cancelPaymentPlan(id, reason.trim());
      setNotice('计划已取消（取消不是删除：痕迹留着）');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '取消失败');
    }
  }

  async function runScan() {
    setActionError('');
    try {
      const result = await scanPaymentReminders({ horizonDays: 30 });
      setNotice(
        `扫描完成：新建 ${result.created} 条、更新 ${result.updated} 条、失效 ${result.dismissed} 条、未变 ${result.unchanged} 条`,
      );
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '扫描到期提醒失败');
    }
  }

  async function runAcknowledge(id: string) {
    setActionError('');
    try {
      await acknowledgePaymentReminder(id);
      setNotice('提醒已确认');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '确认提醒失败');
    }
  }

  async function runDismiss(id: string) {
    const reason = window.prompt('忽略原因（必填）');
    if (reason === null || reason.trim() === '') return;
    setActionError('');
    try {
      await dismissPaymentReminder(id, reason.trim());
      setNotice('提醒已忽略');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '忽略提醒失败');
    }
  }

  async function runCreateTermRule() {
    setActionError('');
    try {
      await createPaymentTermRule({
        name: ruleName,
        counterpartyRef: null,
        currency: ruleCurrency,
        baseKind: ruleBaseKind as 'CONFIRMED_DATE' | 'DUE_DATE',
        termDays: Number(ruleTermDays),
        enabled: true,
        note: '页面登记',
      });
      setRuleName('');
      setNotice('账期规则已登记');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记账期规则失败');
    }
  }

  const gaps = caps?.downstreamGaps ?? [];
  const gapWindows = forecast?.totalsByCurrency.reduce((sum, t) => sum + t.gapWindowCount, 0) ?? 0;
  const hasFilter = Boolean(
    status || paymentCondition || currency || counterpartyRef || overdueOnly || keyword,
  );

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="付款计划"
        description="按付款条件（账期 / 里程碑 / 到期日）生成付款计划与到期提醒，按供应商与到期窗口批量安排付款，并给出资金需求预测"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}
      {actionError && (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已完成">
          {notice}
        </Notice>
      )}

      {loading && <p className="text-muted-foreground text-sm">加载中…</p>}

      {!loading && !error && (
        <>
          {/* ---------- 下游缺口（未交付的模块说清楚归谁） ---------- */}
          {gaps.length > 0 && (
            <Notice tone="warning" title="未交付的下游">
              {gaps.map((gap) => `${gap.module} ${gap.name}（${gap.detail}）`).join('；')}
            </Notice>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard label="在册付款计划" value={page?.total ?? 0} unit="条" />
            <StatCard
              label="计划付款日已过"
              value={page?.overdueCount ?? 0}
              unit="条"
              comparisonLabel="按 UTC 日历日判定"
            />
            <StatCard label="待处理到期提醒" value={reminders?.pendingCount ?? 0} unit="条" />
            <StatCard
              label="资金断裂风险窗口"
              value={gapWindows}
              unit="个"
              comparisonLabel="净流出（流入 − 流出 < 0）"
            />
          </div>

          {/* ---------- 付款条件与规则 ---------- */}
          <SectionCard
            title="付款条件与规则"
            description="三种条件是三种算法：到期日直接取应付单的到期日；账期 = 起算基准日 + 账期规则的天数；里程碑按比例拆成多条。账期与里程碑规则由租户自己维护 —— 系统不预置任何一行。"
          >
            <div className="space-y-4">
              <div>
                <p className="text-copy-13 mb-2 font-medium">账期规则（{termRules.length} 条）</p>
                {termRules.length === 0 ? (
                  <p className="text-muted-foreground text-copy-13">
                    还没有账期规则 ⇒ <b>账期条件排不出计划</b>。请先登记一条。
                  </p>
                ) : (
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2">名称</th>
                        <th className="py-2">币种</th>
                        <th className="py-2">起算基准</th>
                        <th className="py-2">账期</th>
                        <th className="py-2">状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      {termRules.map((rule) => (
                        <tr key={rule.id} className="border-border border-t">
                          <td className="py-2 font-medium">{rule.name}</td>
                          <td className="py-2">{rule.currency ?? '全部'}</td>
                          <td className="py-2">{termBaseKindLabel(rule.baseKind)}</td>
                          <td className="py-2">{rule.termDays} 天</td>
                          <td className="py-2">
                            <Badge tone={rule.enabled ? 'success' : 'neutral'}>
                              {rule.enabled ? '已启用' : '已禁用'}
                            </Badge>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                <div className="mt-3 grid gap-2 md:grid-cols-4">
                  <Input
                    value={ruleName}
                    aria-label="账期规则名称"
                    placeholder="规则名称"
                    onChange={(e) => setRuleName(e.target.value)}
                  />
                  <Input
                    value={ruleCurrency}
                    aria-label="账期规则币种"
                    placeholder="币种（USD）"
                    onChange={(e) => setRuleCurrency(e.target.value)}
                  />
                  <select
                    className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                    value={ruleBaseKind}
                    aria-label="账期起算基准"
                    onChange={(e) => setRuleBaseKind(e.target.value)}
                  >
                    <option value="CONFIRMED_DATE">从确认日起算</option>
                    <option value="DUE_DATE">从到期日起算</option>
                  </select>
                  <Input
                    value={ruleTermDays}
                    aria-label="账期天数"
                    placeholder="账期天数"
                    onChange={(e) => setRuleTermDays(e.target.value)}
                  />
                </div>
                <div className="mt-2">
                  <Button onClick={() => void runCreateTermRule()} disabled={loading}>
                    登记账期规则
                  </Button>
                </div>
              </div>

              <div>
                <p className="text-copy-13 mb-2 font-medium">
                  里程碑规则（{milestoneRules.length} 套）
                </p>
                {milestoneRules.length === 0 ? (
                  <p className="text-muted-foreground text-copy-13">
                    还没有里程碑规则 ⇒ <b>里程碑条件排不出计划</b>。
                  </p>
                ) : (
                  <div className="space-y-3">
                    {milestoneRules.map((rule) => (
                      <div key={rule.id} className="border-border rounded-md border p-3">
                        <p className="text-copy-13 font-medium">
                          {rule.name}
                          <span className="text-muted-foreground ml-2">
                            {rule.currency ?? '全部币种'} · {rule.enabled ? '已启用' : '已禁用'}
                          </span>
                        </p>
                        <ul className="text-copy-13 mt-2 space-y-1">
                          {rule.milestones.map((milestone) => (
                            <li key={milestone.id}>
                              {milestone.seq}. {milestone.name} ——{' '}
                              {milestone.triggerDate
                                ? `触发日 ${milestone.triggerDate}`
                                : `确认日 + ${milestone.offsetDays} 天`}
                              ，比例 {formatMilestoneRatio(milestone.ratio)}
                            </li>
                          ))}
                        </ul>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </SectionCard>

          {/* ---------- 批量安排付款 ---------- */}
          <SectionCard
            title="按供应商与到期窗口批量安排付款"
            description="安排 = 把应付排进某个付款窗口 / 批次，**不是执行付款**（付款属 12.2.2.1，未交付）。一批 = 一个币种 ⇒ 跨币种的钱不会被加进同一个金额。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={scheduleSupplier}
                aria-label="供应商"
                onChange={(e) => setScheduleSupplier(e.target.value)}
              >
                <option value="">选择供应商</option>
                {(options?.counterparties ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
              <Input
                type="date"
                value={scheduleDueFrom}
                aria-label="应付到期窗口起"
                onChange={(e) => setScheduleDueFrom(e.target.value)}
              />
              <Input
                type="date"
                value={scheduleDueTo}
                aria-label="应付到期窗口止"
                onChange={(e) => setScheduleDueTo(e.target.value)}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={scheduleCondition}
                aria-label="付款条件"
                onChange={(e) => setScheduleCondition(e.target.value)}
              >
                <option value="DUE_DATE">到期日</option>
                <option value="TERM">账期</option>
                <option value="MILESTONE">里程碑</option>
              </select>
              <Input
                type="date"
                value={scheduleWindowStart}
                aria-label="付款窗口起"
                onChange={(e) => setScheduleWindowStart(e.target.value)}
              />
              <Input
                type="date"
                value={scheduleWindowEnd}
                aria-label="付款窗口止"
                onChange={(e) => setScheduleWindowEnd(e.target.value)}
              />
              {scheduleCondition === 'TERM' && (
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={scheduleTermRuleId}
                  aria-label="账期规则"
                  onChange={(e) => setScheduleTermRuleId(e.target.value)}
                >
                  <option value="">自动选（最具体优先）</option>
                  {termRules.map((rule) => (
                    <option key={rule.id} value={rule.id}>
                      {rule.name}
                    </option>
                  ))}
                </select>
              )}
              {scheduleCondition === 'MILESTONE' && (
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={scheduleMilestoneRuleId}
                  aria-label="里程碑规则"
                  onChange={(e) => setScheduleMilestoneRuleId(e.target.value)}
                >
                  <option value="">自动选（最具体优先）</option>
                  {milestoneRules.map((rule) => (
                    <option key={rule.id} value={rule.id}>
                      {rule.name}
                    </option>
                  ))}
                </select>
              )}
            </div>
            <p className="text-muted-foreground text-copy-13 mt-2">
              条件日落在付款窗口之外时，计划日会被压进窗口，并在计划上标出「条件日 → 计划日」。
            </p>
            <div className="mt-3">
              <Button onClick={() => void runSchedule()} disabled={loading}>
                批量安排付款
              </Button>
            </div>
          </SectionCard>

          {/* ---------- 生成付款计划 ---------- */}
          <SectionCard
            title="生成付款计划"
            description="只生成计划、不排批次。已排过的同一张应付不会被生成第二条。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={generateCondition}
                aria-label="生成付款条件"
                onChange={(e) => setGenerateCondition(e.target.value)}
              >
                <option value="DUE_DATE">到期日</option>
                <option value="TERM">账期</option>
                <option value="MILESTONE">里程碑</option>
              </select>
              {generateCondition === 'TERM' && (
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={generateTermRuleId}
                  aria-label="生成账期规则"
                  onChange={(e) => setGenerateTermRuleId(e.target.value)}
                >
                  <option value="">自动选（最具体优先）</option>
                  {termRules.map((rule) => (
                    <option key={rule.id} value={rule.id}>
                      {rule.name}
                    </option>
                  ))}
                </select>
              )}
              {generateCondition === 'MILESTONE' && (
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={generateMilestoneRuleId}
                  aria-label="生成里程碑规则"
                  onChange={(e) => setGenerateMilestoneRuleId(e.target.value)}
                >
                  <option value="">自动选（最具体优先）</option>
                  {milestoneRules.map((rule) => (
                    <option key={rule.id} value={rule.id}>
                      {rule.name}
                    </option>
                  ))}
                </select>
              )}
            </div>
            <div className="mt-3">
              <Button onClick={() => void runGenerate()} disabled={loading}>
                生成付款计划
              </Button>
            </div>
          </SectionCard>

          {/* ---------- 付款计划表 ---------- */}
          <SectionCard
            title="付款计划表"
            description="汇总覆盖整个筛选结果集，不是当前页。金额按币种分开 —— USD 与 EUR 加起来不是任何一个金额。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={status}
                aria-label="计划状态"
                onChange={(e) => {
                  setStatus(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部状态</option>
                {(options?.statuses ?? []).map((s) => (
                  <option key={s} value={s}>
                    {paymentPlanStatusLabel(s)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={paymentCondition}
                aria-label="付款条件筛选"
                onChange={(e) => {
                  setPaymentCondition(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部条件</option>
                {(options?.conditions ?? []).map((c) => (
                  <option key={c} value={c}>
                    {paymentConditionLabel(c)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={currency}
                aria-label="币种筛选"
                onChange={(e) => {
                  setCurrency(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部币种</option>
                {(options?.currencies ?? []).map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
              <Input
                value={keyword}
                aria-label="关键字"
                placeholder="计划号 / 应付单号 / 供应商"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setCurrentPage(0);
                }}
              />
            </div>

            {page && page.summaryByCurrency.length > 0 && (
              <div className="mt-3 flex flex-wrap gap-2">
                {page.summaryByCurrency.map((summary) => (
                  <Badge key={summary.currency} tone="info">
                    {summary.currency}：计划 {formatPaymentAmount(summary.plannedAmount)} ·{' '}
                    {summary.count} 条（已安排 {summary.scheduledCount}）
                  </Badge>
                ))}
              </div>
            )}

            {!page || page.items.length === 0 ? (
              <EmptyState
                title={hasFilter ? '没有符合条件的付款计划' : '还没有付款计划'}
                description={
                  hasFilter
                    ? '换个筛选条件再试 —— 汇总覆盖的是整个筛选结果集，翻页不会改变它。'
                    : '先在上面登记账期 / 里程碑规则，再用「生成付款计划」或「批量安排付款」。'
                }
              />
            ) : (
              <table className="text-copy-13 mt-3 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">计划号</th>
                    <th className="py-2">条件</th>
                    <th className="py-2">计划付款日</th>
                    <th className="py-2">金额</th>
                    <th className="py-2">应付单</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((plan) => (
                    <tr key={plan.id} className="border-border border-t">
                      <td className="py-2 font-medium">{plan.planNo}</td>
                      <td className="py-2">
                        <Badge tone="info">{paymentConditionLabel(plan.paymentCondition)}</Badge>
                        <span className="text-muted-foreground ml-2">
                          {paymentConditionHint(plan.paymentCondition)}
                        </span>
                      </td>
                      <td className="py-2">{paymentPlanDateHint(plan)}</td>
                      <td className="py-2">
                        {formatPaymentMoney(plan.plannedAmount, plan.currency)}
                        {plan.milestoneName && (
                          <span className="text-muted-foreground ml-2">{plan.milestoneName}</span>
                        )}
                      </td>
                      <td className="py-2">{plan.payableNo}</td>
                      <td className="py-2">
                        <Badge tone={paymentPlanStatusTone(plan.status)}>
                          {paymentPlanStatusLabel(plan.status)}
                        </Badge>
                        <span className="text-muted-foreground ml-2">
                          {paymentExecutionLabel(
                            plan.executionAvailable,
                            plan.executionOwnerModule,
                          )}
                        </span>
                      </td>
                      <td className="py-2">
                        <div className="flex gap-2">
                          <Button
                            variant="secondary"
                            onClick={() => void openDetail(plan.id)}
                            disabled={loading}
                          >
                            详情
                          </Button>
                          {plan.status !== 'CANCELLED' && (
                            <Button
                              variant="secondary"
                              onClick={() => void runCancel(plan.id)}
                              disabled={loading}
                            >
                              取消
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {page && page.total > page.size && (
              <div className="mt-3 flex items-center gap-3">
                <Button
                  variant="secondary"
                  disabled={currentPage === 0}
                  onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-copy-13">
                  第 {currentPage + 1} 页 / 共 {page.total} 条
                </span>
                <Button
                  variant="secondary"
                  disabled={(currentPage + 1) * page.size >= page.total}
                  onClick={() => setCurrentPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </SectionCard>

          {/* ---------- 计划详情 ---------- */}
          {detail && (
            <SectionCard
              title={`计划详情 ${detail.plan.planNo}`}
              description="事件流是「当时发生了什么」的唯一出处。"
            >
              <div className="space-y-3">
                <p className="text-copy-13">
                  条件：{paymentConditionLabel(detail.plan.paymentCondition)}（
                  {paymentConditionHint(detail.plan.paymentCondition)}）
                </p>
                <p className="text-copy-13">{paymentPlanDateHint(detail.plan)}</p>
                <p className="text-copy-13">
                  金额：{formatPaymentMoney(detail.plan.plannedAmount, detail.plan.currency)} ·
                  应付未付余额快照 {formatPaymentAmount(detail.plan.unpaidSnapshot)} · 当前未付余额{' '}
                  {formatPaymentAmount(detail.plan.currentUnpaid)}
                </p>
                {detail.plan.executionAvailable === false && (
                  <Notice tone="warning" title="付款执行不可用">
                    {paymentExecutionLabel(
                      detail.plan.executionAvailable,
                      detail.plan.executionOwnerModule,
                    )}
                  </Notice>
                )}
                {detail.reminder && (
                  <p className="text-copy-13">
                    当前提醒：{reminderTypeLabel(detail.reminder.reminderType)} ·{' '}
                    {reminderStatusLabel(detail.reminder.status)} ·{' '}
                    {reminderPushLabel(detail.reminder.pushStatus)}
                  </p>
                )}
                <ul className="text-copy-13 space-y-1">
                  {detail.events.map((event) => (
                    <li key={event.id}>
                      {paymentPlanEventLabel(event.eventType)}
                      {event.reason && (
                        <span className="text-muted-foreground"> · {event.reason}</span>
                      )}
                    </li>
                  ))}
                </ul>
                <Button variant="secondary" onClick={() => setDetail(null)}>
                  关闭
                </Button>
              </div>
            </SectionCard>
          )}

          {/* ---------- 付款批次 ---------- */}
          <SectionCard
            title="付款批次"
            description="批次 = 一次「按供应商 + 到期窗口把付款排进某个付款窗口」的结果。金额与笔数由服务端从计划派生。"
          >
            {!batches || batches.items.length === 0 ? (
              <EmptyState
                title="还没有付款批次"
                description="用上面的「批量安排付款」排一批就会出现在这里。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">批次号</th>
                    <th className="py-2">供应商</th>
                    <th className="py-2">应付到期窗口</th>
                    <th className="py-2">付款窗口</th>
                    <th className="py-2">币种</th>
                    <th className="py-2">计划金额</th>
                    <th className="py-2">笔数</th>
                  </tr>
                </thead>
                <tbody>
                  {batches.items.map((batch) => (
                    <tr key={batch.id} className="border-border border-t">
                      <td className="py-2 font-medium">{batch.batchNo}</td>
                      <td className="py-2">{batch.counterpartyName || '—'}</td>
                      <td className="py-2">
                        {batch.dueWindowStart} ~ {batch.dueWindowEnd}
                      </td>
                      <td className="py-2">
                        {batch.paymentWindowStart} ~ {batch.paymentWindowEnd}
                      </td>
                      <td className="py-2">{batch.currency}</td>
                      <td className="py-2">{formatPaymentAmount(batch.plannedAmount)}</td>
                      <td className="py-2">{batch.planCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 资金需求预测 ---------- */}
          <SectionCard
            title="资金需求预测"
            description="按币种 × 到期窗口实时聚合：流出 = 付款计划；流入 = 应收单的未核销余额。计划一改，预测当场跟着变。"
          >
            {!forecast || forecast.windows.length === 0 ? (
              <EmptyState
                title="没有可预测的窗口"
                description="当前区间里既没有在册付款计划，也没有未核销应收。"
              />
            ) : (
              <>
                <div className="mb-3 flex flex-wrap gap-2">
                  {forecast.totalsByCurrency.map((total) => (
                    <Badge key={total.currency} tone={total.netAmount < 0 ? 'danger' : 'success'}>
                      {total.currency}：流出 {formatPaymentAmount(total.outflowAmount)} · 流入{' '}
                      {formatPaymentAmount(total.inflowAmount)} · 净额{' '}
                      {forecastNetHint(total.netAmount, total.netAmount < 0)}
                    </Badge>
                  ))}
                </div>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">币种</th>
                      <th className="py-2">窗口</th>
                      <th className="py-2">计划笔数</th>
                      <th className="py-2">流出</th>
                      <th className="py-2">流入</th>
                      <th className="py-2">净额</th>
                    </tr>
                  </thead>
                  <tbody>
                    {forecast.windows.map((w) => (
                      <tr key={`${w.currency}-${w.windowStart}`} className="border-border border-t">
                        <td className="py-2">{w.currency}</td>
                        <td className="py-2">
                          {w.windowStart} ~ {w.windowEnd}
                        </td>
                        <td className="py-2">{w.planCount}</td>
                        <td className="py-2">{formatPaymentAmount(w.outflowAmount)}</td>
                        <td className="py-2">{formatPaymentAmount(w.inflowAmount)}</td>
                        <td className="py-2">
                          <Badge tone={w.gapFlag ? 'danger' : 'success'}>
                            {forecastNetHint(w.netAmount, w.gapFlag)}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </SectionCard>

          {/* ---------- 到期提醒 ---------- */}
          <SectionCard
            title="到期提醒"
            description="提醒条目与到期判定都是真的；推送通道属 1.3（未交付）⇒ 本模块不推送，也不显示任何「已通知」。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={reminderStatus}
                aria-label="提醒状态筛选"
                onChange={(e) => setReminderStatus(e.target.value)}
              >
                <option value="">全部处理状态</option>
                <option value="PENDING">待处理</option>
                <option value="ACKNOWLEDGED">已确认</option>
                <option value="DISMISSED">已忽略</option>
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={reminderType}
                aria-label="提醒类型筛选"
                onChange={(e) => setReminderType(e.target.value)}
              >
                <option value="">全部提醒类型</option>
                <option value="UPCOMING">即将到期</option>
                <option value="DUE">今日到期</option>
                <option value="OVERDUE">已逾期</option>
              </select>
              <div className="flex items-end">
                <Button variant="secondary" onClick={() => void runScan()} disabled={loading}>
                  扫描到期提醒
                </Button>
              </div>
            </div>

            {!reminders || reminders.items.length === 0 ? (
              <EmptyState
                title="没有到期提醒"
                description="点「扫描到期提醒」按当前日期重算一遍 —— 判定与提醒条目都是真的。"
              />
            ) : (
              <table className="text-copy-13 mt-3 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">提醒</th>
                    <th className="py-2">类型</th>
                    <th className="py-2">提醒日</th>
                    <th className="py-2">应付到期日</th>
                    <th className="py-2">金额</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">推送</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {reminders.items.map((reminder: FinancePaymentReminderRow) => (
                    <tr key={reminder.id} className="border-border border-t">
                      <td className="py-2 font-medium">{reminder.planNo}</td>
                      <td className="py-2">
                        <Badge tone={reminderTypeTone(reminder.reminderType)}>
                          {reminderTypeLabel(reminder.reminderType)}
                        </Badge>
                      </td>
                      <td className="py-2">{reminder.remindDate}</td>
                      <td className="py-2">{reminder.dueDate}</td>
                      <td className="py-2">
                        {formatPaymentMoney(reminder.amount, reminder.currency)}
                      </td>
                      <td className="py-2">
                        <Badge tone={reminderStatusTone(reminder.status)}>
                          {reminderStatusLabel(reminder.status)}
                        </Badge>
                      </td>
                      <td className="text-muted-foreground py-2">
                        {reminderPushLabel(reminder.pushStatus)}
                      </td>
                      <td className="py-2">
                        <div className="flex gap-2">
                          {reminder.status === 'PENDING' && (
                            <>
                              <Button
                                variant="secondary"
                                onClick={() => void runAcknowledge(reminder.id)}
                                disabled={loading}
                              >
                                确认
                              </Button>
                              <Button
                                variant="secondary"
                                onClick={() => void runDismiss(reminder.id)}
                                disabled={loading}
                              >
                                忽略
                              </Button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </>
      )}
    </div>
  );
}
