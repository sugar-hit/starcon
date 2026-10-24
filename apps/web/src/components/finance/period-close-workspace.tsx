'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
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
  FinancePeriodCloseCapabilities,
  FinancePeriodCloseCheckItemPage,
  FinancePeriodCloseChecklistDetail,
  FinancePeriodCloseChecklistPage,
  FinancePeriodCloseExecutionDetail,
  FinancePeriodCloseExecutionPage,
  FinancePeriodCloseOptions,
} from '../../../../../packages/api-contracts/src/index';
import {
  carryForwardPeriodCloseCheck,
  fetchPeriodCloseCapabilities,
  fetchPeriodCloseCheckItems,
  fetchPeriodCloseChecklist,
  fetchPeriodCloseChecklists,
  fetchPeriodCloseExecution,
  fetchPeriodCloseExecutions,
  fetchPeriodCloseOptions,
  periodCloseBlockingSummary,
  periodCloseCarryForwardGuard,
  periodCloseCheckCodeLabel,
  periodCloseClassificationLabel,
  periodCloseClassificationTone,
  periodCloseCurrencyGroupText,
  periodCloseExecutionStatusLabel,
  periodCloseExecutionStatusTone,
  periodCloseItemText,
  periodCloseOutcomeText,
  periodCloseOutcomeTone,
  periodCloseResultLabel,
  periodCloseResultTone,
  runPeriodCloseChecklist,
  submitPeriodClose,
  syncPeriodCloseApproval,
  type PeriodCloseSubmitReport,
} from './period-close-model';

/**
 * 12.5.1.3 关账。
 *
 * <p>蓝图 12-finance.md：「期末关账流程：检查未处理事项（未对账差异、未结转成本、未核销款项、
 * 未过账凭证），按已批准清单区分阻断项与可结转未清项后关闭期间；禁止向已关闭期间录入业务凭证。」
 * 输入：「关账检查清单结果；关账确认。」输出：「关账完成记录；期间锁定。」
 * 关系：「上游 = 12.5.1.2；下游 = 12.5.2.2 报表。**关账检查清单是防错最后一道关**：
 * 阻断项未解决不允许关账；正常未到期应收应付不要求清零，例外与结转须保留依据。」
 *
 * <p><b>页面围绕五条纪律组织</b>：
 *
 * <ol>
 *   <li><b>检查项真的查了数据</b>：每一项显示条数、分类、**分类依据**与可钻取明细 ——
 *       不是四个勾选框。</li>
 *   <li><b>不可判定就说不可判定</b>：上游 12.3.2.1 / 12.3.2.2 未交付 ⇒ 「未对账差异」
 *       显示为「不可判定」（黄色），**绝不**显示成「通过」。</li>
 *   <li><b>阻断项拦得住关账</b>：被阻断时列出**每一条**阻断原因与依据，期间仍然开放。</li>
 *   <li><b>例外与结转留依据</b>：把阻断项下调为可结转未清项必须写依据，写了才让提交；
 *       已经下调过的项，依据永远显示在界面上。</li>
 *   <li><b>多币种绝不相加</b>：金额按币种一行一个，**不产出**跨币种的「合计」。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「还没跑过清单」与「没有匹配的结果」。
 */
export function PeriodCloseWorkspace() {
  const [caps, setCaps] = useState<FinancePeriodCloseCapabilities | null>(null);
  const [options, setOptions] = useState<FinancePeriodCloseOptions | null>(null);
  const [checklists, setChecklists] = useState<FinancePeriodCloseChecklistPage | null>(null);
  const [detail, setDetail] = useState<FinancePeriodCloseChecklistDetail | null>(null);
  const [items, setItems] = useState<FinancePeriodCloseCheckItemPage | null>(null);
  const [executions, setExecutions] = useState<FinancePeriodCloseExecutionPage | null>(null);
  const [execution, setExecution] = useState<FinancePeriodCloseExecutionDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 跑清单
  const [periodId, setPeriodId] = useState('');
  const [checklistNote, setChecklistNote] = useState('');

  // 钻取与例外
  const [drillCheckId, setDrillCheckId] = useState('');
  const [exceptionTarget, setExceptionTarget] = useState('');
  const [exceptionBasis, setExceptionBasis] = useState('');

  // 提交关账
  const [closeReason, setCloseReason] = useState('');
  const [carryforwardBasis, setCarryforwardBasis] = useState('');
  const [submitReport, setSubmitReport] = useState<PeriodCloseSubmitReport | null>(null);

  const openPeriods = useMemo(
    () => (options?.periods ?? []).filter((period) => period.status === 'OPEN'),
    [options],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [capsData, optionsData, checklistData, executionData] = await Promise.all([
        fetchPeriodCloseCapabilities(),
        fetchPeriodCloseOptions(),
        fetchPeriodCloseChecklists({ page: 0, size: 50 }),
        fetchPeriodCloseExecutions({ page: 0, size: 50 }),
      ]);
      setCaps(capsData);
      setOptions(optionsData);
      setChecklists(checklistData);
      setExecutions(executionData);
    } catch (e) {
      setError(e instanceof Error ? e.message : '关账工作区加载失败');
    } finally {
      setLoading(false);
    }
    // ⚠️ **刻意不依赖 periodId / openPeriods**：
    //   load 会 setOptions ⇒ openPeriods 换引用 ⇒ load 换引用 ⇒ effect 重跑 ⇒ 又 setOptions ……
    //   那是一个**无限渲染循环**。默认期间的选择交给下面那个独立的 effect。
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // 首次拿到选项后，若还没选期间，默认选第一个开放中的期间。
  useEffect(() => {
    if (!periodId && openPeriods.length > 0) setPeriodId(openPeriods[0]!.id);
  }, [periodId, openPeriods]);

  async function openChecklist(id: string) {
    setActionError('');
    setDrillCheckId('');
    setExceptionTarget('');
    setExceptionBasis('');
    setItems(null);
    try {
      const loaded = await fetchPeriodCloseChecklist(id);
      setDetail(loaded);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '检查清单加载失败');
    }
  }

  async function runChecklist() {
    setActionError('');
    setNotice('');
    if (!periodId) {
      setActionError('先选一个开放中的会计期间');
      return;
    }
    try {
      const loaded = await runPeriodCloseChecklist(periodId, checklistNote.trim());
      setDetail(loaded);
      setChecklistNote('');
      setNotice(`已跑完检查清单 ${loaded.checklist.checklistNo}（第 ${loaded.checklist.runNo} 次）`);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '跑检查清单失败');
    }
  }

  async function drill(checkId: string) {
    setActionError('');
    setExceptionTarget('');
    setExceptionBasis('');
    setDrillCheckId(checkId);
    try {
      const page = await fetchPeriodCloseCheckItems(detail?.checklist.id ?? '', {
        checkId,
        page: 0,
        size: 50,
      });
      setItems(page);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '明细加载失败');
    }
  }

  async function carryForward(checkId: string) {
    setActionError('');
    setNotice('');
    if (!exceptionBasis.trim()) {
      setActionError('下调为可结转未清项必须写明例外依据（蓝图：例外与结转须保留依据）');
      return;
    }
    try {
      await carryForwardPeriodCloseCheck(detail?.checklist.id ?? '', checkId, exceptionBasis.trim());
      setNotice('已下调为可结转未清项，例外依据已落库');
      setExceptionTarget('');
      setExceptionBasis('');
      await openChecklist(detail?.checklist.id ?? '');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '下调失败');
    }
  }

  async function submitClose() {
    setActionError('');
    setNotice('');
    if (!detail) {
      setActionError('先跑一份检查清单（关账必须按已批准的清单走）');
      return;
    }
    try {
      const report = await submitPeriodClose({
        checklistId: detail.checklist.id,
        reason: closeReason.trim(),
        carryforwardBasis: carryforwardBasis.trim(),
      });
      setSubmitReport(report);
      if (report.outcome === 'PENDING_APPROVAL') {
        setNotice('关账申请已提交审批（审批通过前期间仍然开放）');
      } else if (report.outcome === 'BLOCKED') {
        setNotice('关账被阻断项拦下，期间仍然开放');
      }
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '提交关账失败');
    }
  }

  async function openExecution(id: string) {
    setActionError('');
    try {
      setExecution(await fetchPeriodCloseExecution(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '关账执行记录加载失败');
    }
  }

  async function syncApproval(id: string) {
    setActionError('');
    setNotice('');
    try {
      const detailLoaded = await syncPeriodCloseApproval(id);
      setExecution(detailLoaded);
      setNotice(
        detailLoaded.execution.status === 'CLOSED'
          ? '审批已通过，关账已执行：该期间已锁定、禁止过账'
          : `关账执行状态：${periodCloseExecutionStatusLabel(detailLoaded.execution.status)}`,
      );
      await load();
    } catch (e) {
      setActionError(
        e instanceof Error
          ? `${e.message}（审批不可达时期间保持开放，不会假装审批通过）`
          : '同步审批失败',
      );
    }
  }

  const gaps = caps?.downstreamGaps.filter((gap) => !gap.available) ?? [];
  const checks = detail?.checks ?? [];
  const blockingChecks = checks.filter((check) => check.classification === 'BLOCKING');
  const exceptionTargetCheck = checks.find((check) => check.id === exceptionTarget) ?? null;

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="期末关账"
        description="检查未对账差异 / 未结转成本 / 未核销款项 / 未过账凭证，按已批准清单区分阻断项与可结转未清项后关闭期间"
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
          {gaps.length > 0 && (
            <Notice tone="warning" title="未交付的上游 / 下游">
              {gaps.map((gap) => `${gap.module} ${gap.name}：${gap.detail}`).join('；')}
            </Notice>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard
              label="阻断项（未解决不许关账）"
              value={detail?.blockingCount ?? 0}
              unit="项"
              polarity="negative"
            />
            <StatCard
              label="可结转未清项"
              value={detail?.carryforwardCount ?? 0}
              unit="项"
              polarity="neutral"
            />
            <StatCard
              label="不可判定（上游未交付）"
              value={detail?.notDeterminableCount ?? 0}
              unit="项"
              polarity="neutral"
            />
            <StatCard label="通过" value={detail?.passedCount ?? 0} unit="项" polarity="positive" />
          </div>

          {/* ---------- 跑一份检查清单 ---------- */}
          <SectionCard
            title="跑一次关账检查清单"
            description="清单会真查数据：未结转成本查成本批次、未核销款项查应收 / 收款 / 结算、未过账凭证查草稿凭证。清单只追加 —— 重跑一次多一份证据，已跑过的那份一个字都不改。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={periodId}
                aria-label="要检查的会计期间"
                onChange={(e) => setPeriodId(e.target.value)}
              >
                <option value="">选择开放中的期间</option>
                {openPeriods.map((period) => (
                  <option key={period.id} value={period.id}>
                    {period.name}
                  </option>
                ))}
              </select>
              <Input
                value={checklistNote}
                aria-label="清单备注"
                placeholder="备注（可选）"
                onChange={(e) => setChecklistNote(e.target.value)}
              />
              <Button onClick={() => void runChecklist()} disabled={loading || !periodId}>
                跑清单
              </Button>
            </div>
            {openPeriods.length === 0 && (
              <p className="text-muted-foreground mt-2 text-sm">
                当前没有开放中的会计期间 —— 关账的前提是先有开放期间（12.5.1.2 会计期间）。
              </p>
            )}
          </SectionCard>

          {/* ---------- 检查清单列表 ---------- */}
          <SectionCard
            title="检查清单"
            description="每次运行生成一份清单（run_no 递增）。关账时按其中一份执行 —— 事后能回答「这期关账时看到的未处理事项到底是哪几条」。"
          >
            {!checklists || checklists.items.length === 0 ? (
              <EmptyState
                title="还没有跑过检查清单"
                description="关账是防错最后一道关 —— 先跑一份清单，才谈得上下单关账。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">清单编号</th>
                    <th className="py-2">期间</th>
                    <th className="py-2">次数</th>
                    <th className="py-2">完成时刻</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {checklists.items.map((item) => (
                    <tr key={item.id} className="border-border border-t">
                      <td className="py-2 font-medium">{item.checklistNo}</td>
                      <td className="py-2">
                        {item.periodCode}（{item.periodStart} ~ {item.periodEnd}）
                      </td>
                      <td className="py-2">第 {item.runNo} 次</td>
                      <td className="py-2">{item.completedAt}</td>
                      <td className="py-2">
                        <Button variant="secondary" onClick={() => void openChecklist(item.id)}>
                          查看
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 检查清单详情 ---------- */}
          {detail && (
            <SectionCard
              title={`检查清单详情 · ${detail.checklist.checklistNo}`}
              description={`账期 ${detail.checklist.periodCode}（${detail.checklist.periodStart} ~ ${detail.checklist.periodEnd}）· 本位币 ${detail.checklist.baseCurrency} · ${detail.periodLockNote}`}
            >
              <div className="space-y-4">
                {checks.map((check) => {
                  const guard = periodCloseCarryForwardGuard(check);
                  return (
                    <div key={check.id} className="border-border rounded-md border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">
                          {periodCloseCheckCodeLabel(check.checkCode)}
                        </span>
                        <Badge tone={periodCloseResultTone(check.result)}>
                          {periodCloseResultLabel(check.result)}
                        </Badge>
                        <Badge tone={periodCloseClassificationTone(check.classification)}>
                          {periodCloseClassificationLabel(check.classification)}
                        </Badge>
                        <span className="text-muted-foreground text-copy-12">
                          规则判定：{periodCloseClassificationLabel(check.ruleClassification)} ·
                          未处理 {check.itemCount} 条 · 数据源 {check.dataSource}
                        </span>
                      </div>
                      <p className="text-copy-13 mt-2">依据：{check.classificationBasis}</p>
                      {check.exceptionBasis && (
                        <p className="text-copy-13 mt-1">
                          例外依据：{check.exceptionBasis}
                          {check.exceptionAt ? `（${check.exceptionAt}）` : ''}
                        </p>
                      )}
                      {check.amounts.length > 0 && (
                        <p className="text-copy-13 mt-1">
                          金额（按币种）：
                          {check.amounts
                            .map((amount) => `${amount.currency} ${amount.amount}（${amount.itemCount} 条）`)
                            .join('；')}
                        </p>
                      )}
                      <div className="mt-2 flex flex-wrap gap-2">
                        <Button
                          variant="secondary"
                          onClick={() => void drill(check.id)}
                          disabled={check.itemCount === 0}
                        >
                          查看明细（{check.itemCount}）
                        </Button>
                        <Button
                          variant="secondary"
                          onClick={() => {
                            setExceptionTarget(check.id);
                            setExceptionBasis('');
                          }}
                          disabled={!guard.allowed}
                          title={guard.reason}
                        >
                          下调为可结转未清项
                        </Button>
                      </div>
                      {!guard.allowed && (
                        <p className="text-muted-foreground mt-1 text-copy-12">{guard.reason}</p>
                      )}
                    </div>
                  );
                })}
              </div>

              {exceptionTargetCheck && (
                <div className="border-border mt-4 space-y-2 rounded-md border p-3">
                  <p className="text-copy-13">
                    把「{periodCloseCheckCodeLabel(exceptionTargetCheck.checkCode)}」下调为可结转未清项 ——
                    <b>必须写明例外依据</b>（蓝图：例外与结转须保留依据；依据会落库，事后查得到）。
                  </p>
                  <Input
                    value={exceptionBasis}
                    aria-label="例外依据"
                    placeholder="例：本期无存货业务，成本已由 12.4.1.2 手工结转"
                    onChange={(e) => setExceptionBasis(e.target.value)}
                  />
                  <Button onClick={() => void carryForward(exceptionTargetCheck.id)}>
                    确认下调
                  </Button>
                </div>
              )}

              {drillCheckId && (
                <div className="mt-4">
                  <p className="text-label-13 mb-2">可钻取明细</p>
                  {!items || items.items.length === 0 ? (
                    <p className="text-muted-foreground text-sm">
                      这一项没有未处理明细（{checks.find((c) => c.id === drillCheckId)?.classificationBasis}）
                    </p>
                  ) : (
                    <table className="text-copy-13 w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-2">来源</th>
                          <th className="py-2">单号</th>
                          <th className="py-2">币种</th>
                          <th className="py-2">金额</th>
                          <th className="py-2">为什么算未处理</th>
                        </tr>
                      </thead>
                      <tbody>
                        {items.items.map((item) => (
                          <tr key={item.id} className="border-border border-t">
                            <td className="py-2">
                              {item.sourceModule} · {item.sourceType}
                            </td>
                            <td className="py-2">{item.sourceNo || '—'}</td>
                            <td className="py-2">{item.currency}</td>
                            <td className="py-2">{item.amount}</td>
                            <td className="py-2">{item.reason}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                  <p className="text-muted-foreground mt-2 text-copy-12">
                    {periodCloseItemText({
                      sourceModule: '12.5.2.1',
                      sourceType: 'VOUCHER',
                      sourceNo: 'V-001',
                      currency: 'USD',
                      amount: 100,
                      reason: '凭证仍为草稿（未过账）',
                    })}{' '}
                    —— 明细共 {items?.total ?? 0} 条。
                  </p>
                </div>
              )}

              {/* ---------- 金额按币种分组 ---------- */}
              <div className="mt-4">
                <p className="text-label-13">本期未处理金额（按币种）</p>
                <p className="text-muted-foreground text-copy-12">
                  🔴 多币种绝不相加 —— 这里一行一种币种，<b>不产出</b>跨币种的「合计」。
                </p>
                {detail.currencyGroups.length === 0 ? (
                  <p className="text-muted-foreground mt-2 text-sm">本期没有未处理金额。</p>
                ) : (
                  <ul className="mt-2 space-y-1">
                    {detail.currencyGroups.map((group) => (
                      <li key={group.currency} className="text-copy-13">
                        {periodCloseCurrencyGroupText(group)}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </SectionCard>
          )}

          {/* ---------- 提交关账 ---------- */}
          <SectionCard
            title="提交关账"
            description="关账是不可逆的期末操作 ⇒ 走 17 审批留痕。阻断项未解决 ⇒ 拒绝关账；正常未到期应收应付不要求清零，但例外与结转必须写明依据。"
          >
            <div className="space-y-3">
              <Input
                value={closeReason}
                aria-label="关账原因"
                placeholder="关账原因（写给审计看的一句话）"
                onChange={(e) => setCloseReason(e.target.value)}
              />
              {detail && detail.carryforwardCount > 0 && (
                <Input
                  value={carryforwardBasis}
                  aria-label="结转依据"
                  placeholder={`结转依据（本期有 ${detail.carryforwardCount} 项可结转未清项，必填）`}
                  onChange={(e) => setCarryforwardBasis(e.target.value)}
                />
              )}
              <Button
                onClick={() => void submitClose()}
                disabled={!detail || blockingChecks.length > 0 || !closeReason.trim()}
              >
                提交关账
              </Button>
              {blockingChecks.length > 0 && (
                <Notice tone="danger" title="还有阻断项未解决，不允许关账">
                  {periodCloseBlockingSummary(
                    blockingChecks.map((check) => ({
                      checkName: periodCloseCheckCodeLabel(check.checkCode),
                      itemCount: check.itemCount,
                      basis: check.classificationBasis,
                    })),
                  )}
                  <div className="mt-2">
                    确需结转的项，请在上方用「下调为可结转未清项」写明例外依据。
                  </div>
                </Notice>
              )}
              {submitReport && (
                <Notice
                  tone={periodCloseOutcomeTone(submitReport.outcome) === 'danger' ? 'danger' : 'info'}
                  title={periodCloseOutcomeText(submitReport.outcome)}
                >
                  {submitReport.message}
                  {submitReport.blockingReasons.length > 0 && (
                    <ul className="mt-2 list-disc pl-5">
                      {submitReport.blockingReasons.map((reason) => (
                        <li key={reason.checkCode}>
                          {reason.checkName}（{reason.itemCount} 条）：{reason.basis}
                        </li>
                      ))}
                    </ul>
                  )}
                </Notice>
              )}
            </div>
          </SectionCard>

          {/* ---------- 关账执行记录 ---------- */}
          <SectionCard
            title="关账执行记录"
            description="每一次关账执行都留一条：按哪份清单关的、当时有几项阻断 / 几项结转、结转依据、审批实例是哪一条（可钻取）。"
          >
            {!executions || executions.items.length === 0 ? (
              <EmptyState title="还没有关账执行记录" description="提交关账后，这里会出现可钻取的记录。" />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">关账单号</th>
                    <th className="py-2">期间</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">阻断 / 结转 / 不可判定</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {executions.items.map((item) => (
                    <tr key={item.id} className="border-border border-t">
                      <td className="py-2 font-medium">{item.closeNo}</td>
                      <td className="py-2">{item.periodCode}</td>
                      <td className="py-2">
                        <Badge tone={periodCloseExecutionStatusTone(item.status)}>
                          {periodCloseExecutionStatusLabel(item.status)}
                        </Badge>
                      </td>
                      <td className="py-2">
                        {item.blockingCount} / {item.carryforwardCount} / {item.notDeterminableCount}
                      </td>
                      <td className="py-2">
                        <div className="flex flex-wrap gap-2">
                          <Button variant="secondary" onClick={() => void openExecution(item.id)}>
                            详情
                          </Button>
                          <Button
                            variant="secondary"
                            onClick={() => void syncApproval(item.id)}
                            disabled={item.status === 'CLOSED'}
                          >
                            同步审批结果
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {execution && (
              <div className="border-border mt-4 space-y-2 rounded-md border p-3">
                <p className="text-label-13">
                  {execution.execution.closeNo} ·{' '}
                  {periodCloseExecutionStatusLabel(execution.execution.status)}
                </p>
                <p className="text-copy-13">关账原因：{execution.execution.reason || '—'}</p>
                <p className="text-copy-13">
                  结转依据：{execution.execution.carryforwardBasis || '—'}
                </p>
                {execution.execution.lockNote && (
                  <p className="text-copy-13">🔴 {execution.execution.lockNote}</p>
                )}
                <p className="text-label-13 mt-2">审批链路</p>
                {execution.approvals.length === 0 ? (
                  <p className="text-muted-foreground text-copy-13">
                    还没有同步审批结果（同步后这里会出现 identity 侧的逐节点动作）。
                  </p>
                ) : (
                  <ul className="space-y-1">
                    {execution.approvals.map((approval) => (
                      <li key={approval.id} className="text-copy-13">
                        {approval.action} · {approval.nodeName} · {approval.actorName} ·{' '}
                        {approval.comment || '—'} · {approval.decidedAt}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </SectionCard>
        </>
      )}
    </div>
  );
}
