'use client';

import type { ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Calculator, RefreshCw, TriangleAlert } from 'lucide-react';
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
  DropshipLossAlertPage,
  DropshipLossAlertRow,
  DropshipMarginCapabilities,
  DropshipMarginFactPage,
  DropshipMarginReport,
  DropshipPayableTaskPage,
  DropshipPayableTaskRow,
  DropshipReconciliationDetail,
  DropshipReconciliationPage,
  DropshipReconciliationRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  ALERT_STATUSES,
  LOSS_VERDICTS,
  PAYABLE_PENDING_HINT,
  REPORT_GROUP_BYS,
  STATEMENT_STATUSES,
  UNDETERMINED_HINT,
  alertCounters,
  alertStatusLabel,
  basisBadge,
  buildDropshipReconciliation,
  changeDropshipReconciliationStatus,
  evaluateDropshipLossAlerts,
  evaluateDropshipMarginFacts,
  fetchDropshipLossAlerts,
  fetchDropshipMarginCapabilities,
  fetchDropshipMarginFacts,
  fetchDropshipMarginReport,
  fetchDropshipPayableTasks,
  fetchDropshipReconciliation,
  fetchDropshipReconciliations,
  groupByLabel,
  groupTotals,
  lossVerdictBadge,
  lossVerdictLabel,
  moneyText,
  payableTaskBadge,
  reconciliationBuckets,
  spreadCells,
  statementStatusLabel,
  skuGroupNote,
} from './dropship-margin-model';
import { ApiError } from '../../lib/api';

const PAGE_SIZE = 20;

/** 能力/口径区块与报表共用的错误文案提取。 */
function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : String(error);
}

/** 表单字段外壳（与 9.6.1.x 工作区一致：本页大量是 select，不复用只吃 Input 的 FormField）。 */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-2">
      <span className="text-label-13 text-foreground block">{label}</span>
      {children}
    </div>
  );
}

function selectClass(): string {
  return 'w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground';
}

/** 亏损结论徽章：danger / ok / unknown 三态，「无法判定」刻意不是 warning。 */
function VerdictBadgeView({ verdict }: { verdict: string }) {
  const badge = lossVerdictBadge(verdict);
  return (
    <Badge tone={badge.tone === 'ok' ? 'success' : badge.tone === 'danger' ? 'danger' : 'neutral'}>
      {badge.label}
    </Badge>
  );
}

/**
 * 9.6.2.1 代发差价与对账工作区。
 *
 * <p>蓝图：「管理代发业务的成本与结算：代发采购成本（供应商结算价 + 供应商运费）、
 * 我方售价、差价毛利；按周期与供应商对账，识别亏损代发单（售价低于代发成本+平台费）
 * 并预警。输出：代发差价与毛利报表（按店铺/供应商/SKU）；供应商对账单（→ 12 应付）；
 * 亏损单预警。」
 *
 * <p><b>区块顺序按「会不会亏钱」排</b>：亏损预警 → 差价明细 → 毛利报表 →
 * 供应商对账与应付待办 → 费率与汇率登记。费率登记放最后不是不重要，
 * 而是**它是补救动作**：前四块回答「亏了多少」，最后一块回答「怎么才能判得准」。
 *
 * <p><b>本工作区最要紧的两件事</b>：
 * <ol>
 *   <li>「无法判定」在界面上<b>长得和「不亏」不一样</b>（unknown 色调 + 明写原因），
 *       含平台费的差价是 null 时显示「—」而不是 0.00。</li>
 *   <li>代发口径逐行标注「代发 · 采购差价口径」，报表合计<b>按币种分开</b>，
 *       不给一个跨币种的假数。</li>
 * </ol>
 */
export function DropshipMarginWorkspace() {
  const [caps, setCaps] = useState<DropshipMarginCapabilities | null>(null);
  const [alerts, setAlerts] = useState<DropshipLossAlertPage | null>(null);
  const [facts, setFacts] = useState<DropshipMarginFactPage | null>(null);
  const [report, setReport] = useState<DropshipMarginReport | null>(null);
  const [statements, setStatements] = useState<DropshipReconciliationPage | null>(null);
  const [detail, setDetail] = useState<DropshipReconciliationDetail | null>(null);
  const [tasks, setTasks] = useState<DropshipPayableTaskPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  // 周期端点由用户显式给出，**不从墙上时钟推导**（对账是按周期分片的）
  const [periodStart, setPeriodStart] = useState('2026-01-01T00:00:00+08:00');
  const [periodEnd, setPeriodEnd] = useState('2027-01-01T00:00:00+08:00');
  const [groupBy, setGroupBy] = useState('SUPPLIER');
  const [supplierFilter, setSupplierFilter] = useState('');
  const [verdictFilter, setVerdictFilter] = useState('');
  const [alertKindFilter, setAlertKindFilter] = useState('');
  const [alertStatusFilter, setAlertStatusFilter] = useState('');
  const [statementStatusFilter, setStatementStatusFilter] = useState('');
  const [page, setPage] = useState(0);

  // 建对账单用的一次性表单
  const [stmtSupplier, setStmtSupplier] = useState('');
  const [stmtCurrency, setStmtCurrency] = useState('USD');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsNext, alertsNext, factsNext, reportNext, statementsNext, tasksNext] =
        await Promise.all([
          fetchDropshipMarginCapabilities(),
          fetchDropshipLossAlerts({
            alertKind: alertKindFilter,
            status: alertStatusFilter,
            page: 0,
            size: 50,
          }),
          fetchDropshipMarginFacts({
            supplierCode: supplierFilter,
            lossVerdict: verdictFilter,
            page,
            size: PAGE_SIZE,
          }),
          fetchDropshipMarginReport({ groupBy, periodStart, periodEnd, supplierCode: supplierFilter }),
          fetchDropshipReconciliations({
            status: statementStatusFilter,
            page: 0,
            size: 50,
          }),
          fetchDropshipPayableTasks({ page: 0, size: 50 }),
        ]);
      setCaps(capsNext);
      setAlerts(alertsNext);
      setFacts(factsNext);
      setReport(reportNext);
      setStatements(statementsNext);
      setTasks(tasksNext);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [
    alertKindFilter,
    alertStatusFilter,
    groupBy,
    page,
    periodEnd,
    periodStart,
    statementStatusFilter,
    supplierFilter,
    verdictFilter,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  const run = async (key: string, work: () => Promise<string>) => {
    setBusy(key);
    setError(null);
    setNote(null);
    try {
      setNote(await work());
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(null);
    }
  };

  const totals = useMemo(() => (report ? groupTotals(report) : []), [report]);
  const counters = alerts ? alertCounters(alerts) : null;
  const unavailable = (caps?.dimensions ?? []).filter((d) => !d.available);

  return (
    <div className="space-y-6">
      <PageHeader
        title="代发差价与对账"
        description="代发采购成本（供应商结算价 + 供应商运费）、我方售价、差价毛利；按周期与供应商对账，识别亏损代发单并预警。"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="请求失败">
          {error}
        </Notice>
      )}
      {note && <Notice tone="success" title="操作结果">{note}</Notice>}

      {/* ============================================================ 亏损预警 */}
      <SectionCard
        title="亏损单预警"
        description="售价低于代发成本（+ 已登记平台费）即为亏损；平台费未登记的单单独成「无法判定」桶，不并进不亏。"
      >
        <div className="mb-3 flex justify-end">
          <Button
            variant="default"
            disabled={busy === 'evaluate-alerts'}
            onClick={() =>
              void run('evaluate-alerts', async () => {
                const r = await evaluateDropshipLossAlerts({ periodStart, periodEnd });
                return `跑完一轮检出：扫了 ${r.scannedOrders} 条差价事实，检出 ${r.computed} 条预警`;
              })
            }
          >
            <Calculator className="mr-2 h-4 w-4" />
            跑一轮检出
          </Button>
        </div>
        <div className="grid gap-4 md:grid-cols-2">
          <StatCard label="已确认亏损" value={counters?.lossConfirmed ?? 0} />
          <StatCard label="平台费未登记 · 无法判定" value={counters?.undetermined ?? 0} />
        </div>
        {counters && <Notice tone="info" title="两类预警分开计数">{counters.note}</Notice>}

        <div className="mt-4 grid gap-3 md:grid-cols-2">
          <Field label="结论类型">
            <select
              className={selectClass()}
              value={alertKindFilter}
              onChange={(e) => setAlertKindFilter(e.target.value)}
            >
              <option value="">全部</option>
              {LOSS_VERDICTS.map((v) => (
                <option key={v} value={v}>
                  {lossVerdictLabel(v)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="处置状态">
            <select
              className={selectClass()}
              value={alertStatusFilter}
              onChange={(e) => setAlertStatusFilter(e.target.value)}
            >
              <option value="">全部</option>
              {ALERT_STATUSES.map((v) => (
                <option key={v} value={v}>
                  {alertStatusLabel(v)}
                </option>
              ))}
            </select>
          </Field>
        </div>

        {loading ? (
          <div className="py-8 text-center text-sm text-muted">加载中…</div>
        ) : !alerts || alerts.items.length === 0 ? (
          <EmptyState
            title="当前没有预警"
            description="这是一条业务结论（此刻确实没有判定出问题），不是查不到。先跑一轮检出，再看差价明细。"
          />
        ) : (
          <ul className="mt-4 space-y-3">
            {alerts.items.map((row: DropshipLossAlertRow) => (
              <AlertRow
                key={row.id}
                row={row}
                busy={busy === `alert-${row.id}`}
                onAcknowledge={(resolved) =>
                  void run(`alert-${row.id}`, async () => {
                    await acknowledge(row.id, resolved);
                    return resolved ? '已处置并结案' : '已确认待处置';
                  })
                }
              />
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ============================================================ 差价明细 */}
      <SectionCard
        title="差价毛利明细"
        description="每一行的差价都能从它自己的构成项复算：售价 − 采购结算价 − 供应商运费 − 平台费。"
      >
        <div className="mb-3 flex justify-end">
          <Button
            variant="default"
            disabled={busy === 'evaluate-facts'}
            onClick={() =>
              void run('evaluate-facts', async () => {
                const r = await evaluateDropshipMarginFacts({ periodStart, periodEnd });
                return (
                  `跑完一轮计算：扫了 ${r.scannedOrders} 张代发单，计算 ${r.computed} 张，` +
                  `跳过成本未落定 ${r.skipped} 张；确认亏损 ${r.lossConfirmed} 张、` +
                  `无法判定 ${r.undetermined} 张`
                );
              })
            }
          >
            <Calculator className="mr-2 h-4 w-4" />
            跑一轮计算
          </Button>
        </div>
        <div className="grid gap-3 md:grid-cols-3">
          <Field label="周期起（含）">
            <Input value={periodStart} onChange={(e) => setPeriodStart(e.target.value)} />
          </Field>
          <Field label="周期止（不含）">
            <Input value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
          </Field>
          <Field label="供应商">
            <Input
              value={supplierFilter}
              placeholder="留空为全部"
              onChange={(e) => setSupplierFilter(e.target.value)}
            />
          </Field>
        </div>

        {loading ? (
          <div className="py-8 text-center text-sm text-muted">加载中…</div>
        ) : !facts || facts.items.length === 0 ? (
          <EmptyState
            title="还没有差价数据"
            description="先跑一轮计算。成本未落定（供应商还没回执）的代发单会被跳过并如实报出数量。"
          />
        ) : (
          <>
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-muted">
                    <th className="px-2 py-2">代发单</th>
                    <th className="px-2 py-2">口径</th>
                    <th className="px-2 py-2 text-right">售价</th>
                    <th className="px-2 py-2 text-right">代发成本</th>
                    <th className="px-2 py-2 text-right">不含平台费差价</th>
                    <th className="px-2 py-2 text-right">含平台费差价</th>
                    <th className="px-2 py-2">结论</th>
                  </tr>
                </thead>
                <tbody>
                  {facts.items.map((row) => {
                    const cells = spreadCells(row);
                    return (
                      <tr key={row.id} className="border-t border-border">
                        <td className="px-2 py-2">
                          <div>{row.orderNo}</div>
                          <div className="text-xs text-muted">{row.supplierCode}</div>
                        </td>
                        <td className="px-2 py-2">
                          <Badge tone="neutral">{basisBadge(row)}</Badge>
                        </td>
                        <td className="px-2 py-2 text-right">
                          {moneyText(row.sellAmount, row.currencyCode)}
                        </td>
                        <td className="px-2 py-2 text-right">
                          {moneyText(row.dropshipCost, row.currencyCode)}
                        </td>
                        <td className="px-2 py-2 text-right">
                          {moneyText(cells.spreadBeforeFee, row.currencyCode)}
                        </td>
                        <td className="px-2 py-2 text-right">
                          {/*
                            平台费未登记时这里是「—」而不是 0.00：
                            「差价算不出来」与「差价刚好是 0」必须长得不一样。
                          */}
                          {cells.spreadAfterFee === null ? (
                            <span
                              className="text-muted"
                              title={cells.note}
                            >
                              —（无法判定）
                            </span>
                          ) : (
                            moneyText(cells.spreadAfterFee, row.currencyCode)
                          )}
                        </td>
                        <td className="px-2 py-2">
                          <VerdictBadgeView verdict={row.lossVerdict} />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {facts.total > PAGE_SIZE && (
              <div className="mt-4 flex items-center gap-2">
                <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
                  上一页
                </Button>
                <span className="text-sm text-muted">
                  第 {page + 1} 页 / 共 {Math.ceil(facts.total / PAGE_SIZE)} 页（{facts.total} 行）
                </span>
                <Button
                  variant="secondary"
                  disabled={(page + 1) * PAGE_SIZE >= facts.total}
                  onClick={() => setPage(page + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </>
        )}
      </SectionCard>

      {/* ============================================================ 毛利报表 */}
      <SectionCard
        title="差价与毛利报表"
        description="按店铺 / 供应商 / SKU / 代发单分组；金额按币种分开，跨币种不相加。"
      >
        <div className="mb-3 flex justify-end">
          <Field label="">
            <select
              className={selectClass()}
              value={groupBy}
              onChange={(e) => setGroupBy(e.target.value)}
            >
              {REPORT_GROUP_BYS.map((v) => (
                <option key={v} value={v}>
                  {groupByLabel(v)}
                </option>
              ))}
            </select>
          </Field>
        </div>
        {loading ? (
          <div className="py-8 text-center text-sm text-muted">加载中…</div>
        ) : !report || report.groups.length === 0 ? (
          <EmptyState title="本周期没有可汇总的差价数据" description="换一个周期区间，或先跑一轮计算。" />
        ) : (
          <>
            <Notice tone="info" title="口径隔离">{report.basisNote}</Notice>
            <Notice tone="warning" title="平台费口径">{report.feeNote}</Notice>
            <div className="mt-4 grid gap-4 md:grid-cols-2">
              {totals.map((t) => (
                <StatCard
                  key={t.currencyCode}
                  label={`合计（${t.currencyCode}）`}
                  value={`${moneyText(t.sellAmount, t.currencyCode)} · 差价 ${
                    t.spreadAfterFee === null
                      ? '—（含未登记行）'
                      : moneyText(t.spreadAfterFee, t.currencyCode)
                  }`}
                />
              ))}
            </div>
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-muted">
                    <th className="px-2 py-2">{groupByLabel(report.groupBy)}</th>
                    <th className="px-2 py-2">币种</th>
                    <th className="px-2 py-2 text-right">单数</th>
                    <th className="px-2 py-2 text-right">售价</th>
                    <th className="px-2 py-2 text-right">采购结算价</th>
                    <th className="px-2 py-2 text-right">供应商运费</th>
                    <th className="px-2 py-2 text-right">代发成本</th>
                    <th className="px-2 py-2 text-right">不含平台费差价</th>
                    <th className="px-2 py-2 text-right">含平台费差价</th>
                    <th className="px-2 py-2 text-right">亏损 / 无法判定</th>
                  </tr>
                </thead>
                <tbody>
                  {report.groups.map((group) => (
                    <tr key={`${group.groupKey}:${group.currencyCode}`} className="border-t border-border">
                      <td className="px-2 py-2">
                        <div>{group.groupLabel}</div>
                        <Badge tone="neutral">{basisBadge(group)}</Badge>
                        {group.groupBy === 'SKU' && (
                          <div className="mt-1 text-xs text-muted">{skuGroupNote(group)}</div>
                        )}
                      </td>
                      <td className="px-2 py-2">{group.currencyCode}</td>
                      <td className="px-2 py-2 text-right">{group.orderCount}</td>
                      <td className="px-2 py-2 text-right">
                        {moneyText(group.sellAmount, group.currencyCode)}
                      </td>
                      <td className="px-2 py-2 text-right">
                        {moneyText(group.purchaseAmount, group.currencyCode)}
                      </td>
                      <td className="px-2 py-2 text-right">
                        {moneyText(group.supplierShippingAmount, group.currencyCode)}
                      </td>
                      <td className="px-2 py-2 text-right">
                        {moneyText(group.dropshipCost, group.currencyCode)}
                      </td>
                      <td className="px-2 py-2 text-right">
                        {moneyText(group.spreadBeforeFee, group.currencyCode)}
                      </td>
                      <td className="px-2 py-2 text-right">
                        {group.spreadAfterFee === null ? (
                          <span className="text-muted">—（无法判定）</span>
                        ) : (
                          moneyText(group.spreadAfterFee, group.currencyCode)
                        )}
                      </td>
                      <td className="px-2 py-2 text-right">
                        {group.lossConfirmedOrderCount} / {group.undeterminedOrderCount}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Notice tone="info" title="币种口径">{report.currencyNote}</Notice>
          </>
        )}
      </SectionCard>

      {/* ============================================================ 对账与应付 */}
      <SectionCard
        title="供应商对账与应付待办"
        description="对账行的口径是「采购结算价 + 供应商运费」；明细行是对账时刻的冻结快照，事后改费率改不动它。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <Field label="供应商编码">
            <Input value={stmtSupplier} onChange={(e) => setStmtSupplier(e.target.value)} />
          </Field>
          <Field label="结算币种">
            <Input value={stmtCurrency} onChange={(e) => setStmtCurrency(e.target.value)} />
          </Field>
          <Field label="状态筛选">
            <select
              className={selectClass()}
              value={statementStatusFilter}
              onChange={(e) => setStatementStatusFilter(e.target.value)}
            >
              <option value="">全部</option>
              {STATEMENT_STATUSES.map((v) => (
                <option key={v} value={v}>
                  {statementStatusLabel(v)}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <div className="mt-3 flex gap-2">
          <Button
            variant="default"
            disabled={busy === 'build' || stmtSupplier.trim() === ''}
            onClick={() =>
              void run('build', async () => {
                const row = await buildDropshipReconciliation({
                  supplierCode: stmtSupplier.trim(),
                  currencyCode: stmtCurrency.trim().toUpperCase(),
                  periodStart,
                  periodEnd,
                  cycleKind: '',
                  note: '',
                });
                await openStatement(row.id);
                return `已生成对账单 ${row.statementNo}`;
              })
            }
          >
            生成本期对账单
          </Button>
          <span className="self-center text-xs text-muted">
            周期端点由上面显式给出，不由「上个月」这类相对词推导
          </span>
        </div>

        <div className="mt-4 grid gap-4 md:grid-cols-2">
          <div>
            <h3 className="text-label-13 text-foreground mb-2">对账单列表</h3>
            {loading ? (
              <div className="py-6 text-center text-sm text-muted">加载中…</div>
            ) : !statements || statements.items.length === 0 ? (
              <EmptyState title="还没有对账单" description="选好供应商与结算币种后生成本期对账单。" />
            ) : (
              <ul className="space-y-2">
                {statements.items.map((row: DropshipReconciliationRow) => {
                  const buckets = reconciliationBuckets(row);
                  return (
                    <li key={row.id}>
                      <button
                        type="button"
                        className="w-full rounded-md border border-border px-3 py-2 text-left hover:bg-surface-muted"
                        onClick={() => void run(`open-${row.id}`, () => openStatement(row.id))}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-sm font-medium">{row.statementNo}</span>
                          <Badge tone="neutral">{statementStatusLabel(row.status)}</Badge>
                        </div>
                        <div className="text-xs text-muted">
                          {row.supplierCode} · {row.currencyCode} · {buckets.included.payableText}
                        </div>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          <div>
            <h3 className="text-label-13 text-foreground mb-2">应付待办（12.1.2.1）</h3>
            {loading ? (
              <div className="py-6 text-center text-sm text-muted">加载中…</div>
            ) : !tasks || tasks.items.length === 0 ? (
              <EmptyState title="没有应付待办" description={`生成对账单后会落一条${PAYABLE_PENDING_HINT}的待办。`} />
            ) : (
              <ul className="space-y-2">
                {tasks.items.map((task: DropshipPayableTaskRow) => {
                  const badge = payableTaskBadge(task);
                  return (
                    <li key={task.id} className="rounded-md border border-border px-3 py-2">
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-sm font-medium">{task.statementNo}</span>
                        <Badge
                          tone={
                            badge.tone === 'done'
                              ? 'success'
                              : badge.tone === 'failed'
                                ? 'danger'
                                : 'neutral'
                          }
                        >
                          {badge.label}
                        </Badge>
                      </div>
                      <div className="text-xs text-muted">
                        {moneyText(task.payableAmount, task.payableCurrencyCode)} · {badge.hint}
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>

        {detail && <StatementDetail detail={detail} onOpen={openStatement} busy={busy} run={run} />}
      </SectionCard>

      {/* ============================================================ 能力与缺口 */}
      <SectionCard title="能力与上下游缺口" description="未交付的能力一律显式标为不可用并点名归属，绝不画成已完成。">
        {loading ? (
          <div className="py-8 text-center text-sm text-muted">加载中…</div>
        ) : !caps ? (
          <EmptyState title="能力声明不可用" description="后端未返回能力声明。" />
        ) : (
          <>
            <Notice tone="info" title="本模块能力自述">{caps.summary}</Notice>
            <Notice tone="warning" title="利润口径隔离">{caps.basisNote}</Notice>
            <ul className="mt-4 space-y-2">
              {unavailable.map((dim) => (
                <li key={dim.code} className="rounded-md border border-border px-3 py-2">
                  <div className="flex items-center gap-2">
                    <Badge tone="warning">不可用</Badge>
                    <span className="text-sm font-medium">{dim.name}</span>
                    {dim.ownerModule && (
                      <span className="text-xs text-muted">归属 {dim.ownerModule}</span>
                    )}
                  </div>
                  {dim.note && <p className="mt-1 text-xs text-muted">{dim.note}</p>}
                </li>
              ))}
            </ul>
          </>
        )}
      </SectionCard>

      <p className="text-xs text-muted">
        提示：{UNDETERMINED_HINT}。把平台费按 0 算会同时漏判真实亏损、并把所有代发单染成亏损。
      </p>
    </div>
  );

  async function openStatement(statementId: string): Promise<string> {
    const next = await fetchDropshipReconciliation(statementId);
    setDetail(next);
    return `已打开对账单 ${next.statement.statementNo}`;
  }

  async function acknowledge(alertId: string, resolved: boolean): Promise<void> {
    const noteText = window.prompt(
      resolved ? '处置说明（必填）：已确认亏损的处置动作是什么？' : '处置说明（必填）：打算怎么处理？',
    );
    if (!noteText || noteText.trim() === '') return;
    const { acknowledgeDropshipLossAlert } = await import('./dropship-margin-model');
    await acknowledgeDropshipLossAlert(alertId, { resolutionNote: noteText.trim(), resolved });
  }
}

/** 一条预警：带判据原文，而不是只给一个红色标签。 */
function AlertRow({
  row,
  busy,
  onAcknowledge,
}: {
  row: DropshipLossAlertRow;
  busy: boolean;
  onAcknowledge: (resolved: boolean) => void;
}) {
  const badge = lossVerdictBadge(row.alertKind);
  return (
    <li className="rounded-md border border-border px-3 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <VerdictBadgeView verdict={row.alertKind} />
        <span className="text-sm font-medium">{row.orderNo}</span>
        <span className="text-xs text-muted">
          {row.supplierCode} · {row.shopCode || '未登记店铺'} · {row.currencyCode}
        </span>
        <Badge tone="neutral">{alertStatusLabel(row.status)}</Badge>
        <Badge tone="neutral">代发 · 采购差价口径</Badge>
      </div>
      <p className="mt-2 text-xs text-muted">{badge.hint}</p>
      <p className="mt-1 text-xs text-foreground-muted">{row.ruleEvidence}</p>
      {row.status === 'OPEN' && (
        <div className="mt-2 flex gap-2">
          <Button variant="secondary" disabled={busy} onClick={() => onAcknowledge(false)}>
            标记已确认待处置
          </Button>
          <Button variant="secondary" disabled={busy} onClick={() => onAcknowledge(true)}>
            处置并结案
          </Button>
        </div>
      )}
    </li>
  );
}

/** 对账单详情：三个桶分开显示，合计不会被未登记项污染。 */
function StatementDetail({
  detail,
  onOpen,
  busy,
  run,
}: {
  detail: DropshipReconciliationDetail;
  onOpen: (id: string) => Promise<string>;
  busy: string | null;
  run: (key: string, work: () => Promise<string>) => Promise<void>;
}) {
  const row = detail.statement;
  const buckets = reconciliationBuckets(row);
  return (
    <div className="mt-6 rounded-md border border-border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-medium">{row.statementNo}</h3>
        <Badge tone="neutral">{statementStatusLabel(row.status)}</Badge>
        <Badge tone="neutral">{basisBadge(row)}</Badge>
      </div>
      <p className="mt-1 text-xs text-muted">
        {row.supplierCode} · 周期 {row.periodStart} ~ {row.periodEnd} · 结算币种 {row.currencyCode}
      </p>

      <div className="mt-3 grid gap-3 md:grid-cols-3">
        <StatCard label="计入合计 · 应付供应商" value={buckets.included.payableText} />
        <StatCard
          label="计入合计 · 平台费"
          value={buckets.included.feeText}
        />
        <StatCard label="计入合计 · 单数" value={buckets.included.orderCount} />
      </div>

      <Notice tone="warning" title="汇率未登记桶">{buckets.fxUnset.note}</Notice>
      <Notice tone="warning" title="平台费未登记桶">{buckets.feeUnregistered.note}</Notice>
      <Notice tone="info" title="应付单下游状态">{detail.taskNote}</Notice>

      {row.ruleEvidence && <p className="mt-2 text-xs text-muted">{row.ruleEvidence}</p>}

      <div className="mt-3 flex gap-2">
        {row.status === 'DRAFT' && (
          <Button
            variant="secondary"
            disabled={busy === `status-${row.id}`}
            onClick={() =>
              void run(`status-${row.id}`, async () => {
                await changeDropshipReconciliationStatus(row.id, { status: 'FINALIZED', disputeNote: '' });
                return `对账单 ${row.statementNo} 已定稿`;
              })
            }
          >
            定稿
          </Button>
        )}
        {(row.status === 'FINALIZED' || row.status === 'DISPUTED') && (
          <Button
            variant="secondary"
            disabled={busy === `dispute-${row.id}`}
            onClick={() => {
              const text = window.prompt('争议内容（必填）：供应商对这单的异议是什么？');
              if (!text || text.trim() === '') return;
              void run(`dispute-${row.id}`, async () => {
                await changeDropshipReconciliationStatus(row.id, {
                  status: 'DISPUTED',
                  disputeNote: text.trim(),
                });
                return `对账单 ${row.statementNo} 已标记争议`;
              });
            }}
          >
            标记争议
          </Button>
        )}
        <Button
          variant="secondary"
          disabled={busy === `reload-${row.id}`}
          onClick={() => void run(`reload-${row.id}`, () => onOpen(row.id))}
        >
          重新载入
        </Button>
      </div>

      <div className="mt-4 overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-muted">
              <th className="px-2 py-2">代发单</th>
              <th className="px-2 py-2">原币</th>
              <th className="px-2 py-2 text-right">售价</th>
              <th className="px-2 py-2 text-right">采购结算价</th>
              <th className="px-2 py-2 text-right">供应商运费</th>
              <th className="px-2 py-2 text-right">应付（结算币）</th>
              <th className="px-2 py-2">折算</th>
              <th className="px-2 py-2">计入合计</th>
            </tr>
          </thead>
          <tbody>
            {detail.lines.map((line) => (
              <tr key={line.id} className="border-t border-border">
                <td className="px-2 py-2">{line.orderNo}</td>
                <td className="px-2 py-2">{line.originalCurrencyCode}</td>
                <td className="px-2 py-2 text-right">
                  {moneyText(line.sellAmount, line.originalCurrencyCode)}
                </td>
                <td className="px-2 py-2 text-right">
                  {moneyText(line.purchaseAmount, line.originalCurrencyCode)}
                </td>
                <td className="px-2 py-2 text-right">
                  {moneyText(line.supplierShippingAmount, line.originalCurrencyCode)}
                </td>
                <td className="px-2 py-2 text-right">
                  {line.payableInStatement === null ? (
                    <span className="text-muted">—（未登记）</span>
                  ) : (
                    moneyText(line.payableInStatement, line.statementCurrencyCode)
                  )}
                </td>
                <td className="px-2 py-2">
                  {line.fxStatus === 'UNSET' ? (
                    <Badge tone="warning">未登记，未折算</Badge>
                  ) : line.fxRateValue ? (
                    <span className="text-xs text-muted">× {line.fxRateValue}</span>
                  ) : (
                    <span className="text-xs text-muted">同币</span>
                  )}
                </td>
                <td className="px-2 py-2">
                  {line.inTotals ? '是' : <span className="text-muted">否（进未登记桶）</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
