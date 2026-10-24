'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, Search, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  DetailDrawer,
  DetailDrawerBody,
  DetailDrawerContent,
  DetailDrawerDescription,
  DetailDrawerHeader,
  DetailDrawerTitle,
  EmptyState,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  FinanceReportCapabilities,
  FinanceReportDrill,
  FinanceReportOptions,
  FinanceReportResult,
  FinanceReportRow,
  FinanceReportTemplateRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  createFinancialReportTemplate,
  disableFinancialReportTemplate,
  downloadFinancialReport,
  fetchBalanceSheet,
  fetchCashFlow,
  fetchFinancialReportCapabilities,
  fetchFinancialReportDrill,
  fetchFinancialReportOptions,
  fetchFinancialReportTemplates,
  fetchGeneralLedger,
  fetchIncomeStatement,
  fetchTrialBalance,
  formatLedgerEntryRef,
  formatReportAmount,
  formatReportDelta,
  formatReportMoney,
  isReportTotalRow,
  reportCheckSummary,
  reportCheckTone,
  reportLineTypeLabel,
  reportSectionLabel,
  reportTypeLabel,
} from './financial-report-model';

/**
 * 12.5.2.2 财务报表。
 *
 * <p>蓝图 12-finance.md：「<b>生成资产负债表、利润表、现金流量表、科目余额表、明细账</b>；支持期间对比、导出、钻取到凭证。」关系：「<b>报表数字必须能钻到凭证</b>，"报表与明细对不上"是财务系统的致命问题。」
 *
 * <p><b>页面围绕五张报表组织</b>，每张报表一栏：
 *
 * <ol>
 *   <li>切换报表类型 + 期间范围（本期 / 对比期）+ 币种 + （明细账）科目；</li>
 *   <li><b>按币种分组</b>渲染 —— USD 与 EUR 摆进不同分组，绝不相加；</li>
 *   <li>每张报表都带**机器自检**（借贷平衡 / 会计恒等式）：差额非 0 用**危险**语气标出；</li>
 *   <li>每一行都能<b>钻取到凭证</b>：点开抽屉看到真实凭证号、分录行号、对方科目与金额；</li>
 *   <li>导出 CSV / JSON（真内容），并显示本次导出的口径与留痕。</li>
 * </ol>
 *
 * <p><b>页面不做业务算术</b>：合计、变动额、自检差额都由服务端算好，页面只原样展示。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「这段时间没有任何已过账的凭证」。
 */

const REPORT_TYPES = [
  'BALANCE_SHEET',
  'INCOME_STATEMENT',
  'CASH_FLOW',
  'TRIAL_BALANCE',
  'GENERAL_LEDGER',
] as const;

type ReportType = (typeof REPORT_TYPES)[number];

export function FinancialReportWorkspace() {
  const [caps, setCaps] = useState<FinanceReportCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceReportOptions | null>(null);
  const [templates, setTemplates] = useState<FinanceReportTemplateRow[]>([]);
  const [result, setResult] = useState<FinanceReportResult | null>(null);
  const [drill, setDrill] = useState<FinanceReportDrill | null>(null);
  const [drillRow, setDrillRow] = useState<FinanceReportRow | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [reportType, setReportType] = useState<ReportType>('BALANCE_SHEET');
  const [periodFromId, setPeriodFromId] = useState('');
  const [periodToId, setPeriodToId] = useState('');
  const [compareFromId, setCompareFromId] = useState('');
  const [compareToId, setCompareToId] = useState('');
  const [currency, setCurrency] = useState('');
  const [subjectCode, setSubjectCode] = useState('');

  // 模板登记草稿
  const [templateSubject, setTemplateSubject] = useState('');
  const [templateLineCode, setTemplateLineCode] = useState('');
  const [templateLineName, setTemplateLineName] = useState('');
  const [templateCashRole, setTemplateCashRole] = useState('');
  const [templateCashGroup, setTemplateCashGroup] = useState('');

  const periods = options?.periods ?? [];

  /** 期间下拉：默认选最近的一段（最后一期 ~ 最后一期）。 */
  const applyDefaultPeriods = useCallback((loaded: FinanceReportOptions) => {
    const latest = loaded.periods[0];
    if (!latest) return;
    setPeriodFromId((prev) => prev || latest.id);
    setPeriodToId((prev) => prev || latest.id);
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, t] = await Promise.all([
        fetchFinancialReportCapabilities(),
        fetchFinancialReportOptions(),
        fetchFinancialReportTemplates(),
      ]);
      setCaps(c);
      setOptions(o);
      setTemplates(t);
      applyDefaultPeriods(o);
    } catch (err) {
      setError(err instanceof Error ? err.message : '报表筛选项加载失败');
    } finally {
      setLoading(false);
    }
  }, [applyDefaultPeriods]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 取一张报表（页面不做业务算术，全是服务端算好的）。 */
  const loadReport = useCallback(async () => {
    if (!periodFromId || !periodToId) {
      setError('请先选择本期期间范围');
      return;
    }
    setLoading(true);
    setError('');
    try {
      const params = {
        periodFromId,
        periodToId,
        ...(compareFromId ? { comparePeriodFromId: compareFromId } : {}),
        ...(compareToId ? { comparePeriodToId: compareToId } : {}),
        ...(currency ? { currency } : {}),
        ...(reportType === 'GENERAL_LEDGER' && subjectCode ? { subjectCode } : {}),
      };
      const data =
        reportType === 'BALANCE_SHEET'
          ? await fetchBalanceSheet(params)
          : reportType === 'INCOME_STATEMENT'
            ? await fetchIncomeStatement(params)
            : reportType === 'CASH_FLOW'
              ? await fetchCashFlow(params)
              : reportType === 'TRIAL_BALANCE'
                ? await fetchTrialBalance(params)
                : await fetchGeneralLedger(params);
      setResult(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : '报表加载失败');
    } finally {
      setLoading(false);
    }
  }, [
    periodFromId,
    periodToId,
    compareFromId,
    compareToId,
    currency,
    subjectCode,
    reportType,
  ]);

  useEffect(() => {
    if (periodFromId && periodToId) void loadReport();
    // 期间与报表类型变了就重新取数。
    // loadReport 的依赖与下面这组筛选条件**完全一致**（useCallback），
    // 所以把它列进依赖既满足 exhaustive-deps，也不会多跑一轮。
  }, [
    loadReport,
    periodFromId,
    periodToId,
    compareFromId,
    compareToId,
    currency,
    subjectCode,
    reportType,
  ]);

  /** 钻取到凭证：走服务端给的**真实路径**对应的端点。 */
  const openDrill = useCallback(
    async (row: FinanceReportRow) => {
      setActionError('');
      try {
        const data = await fetchFinancialReportDrill({
          reportType: result?.reportType ?? reportType,
          lineCode: row.lineCode,
          ...(row.subjectCode ? { subjectCode: row.subjectCode } : {}),
          ...(row.currency ? { currency: row.currency } : {}),
          periodFromId,
          periodToId,
        });
        setDrill(data);
        setDrillRow(row);
      } catch (err) {
        setActionError(err instanceof Error ? err.message : '钻取失败');
      }
    },
    [result, reportType, periodFromId, periodToId],
  );

  /** 导出（真内容，服务端同时写一条留痕）。 */
  const runExport = useCallback(
    async (format: 'CSV' | 'JSON') => {
      if (!periodFromId || !periodToId) {
        setActionError('请先选择本期期间范围');
        return;
      }
      setActionError('');
      setNotice('');
      try {
        await downloadFinancialReport({
          reportType,
          format,
          periodFromId,
          periodToId,
          ...(compareFromId ? { comparePeriodFromId: compareFromId } : {}),
          ...(compareToId ? { comparePeriodToId: compareToId } : {}),
          ...(currency ? { currency } : {}),
          ...(reportType === 'GENERAL_LEDGER' && subjectCode ? { subjectCode } : {}),
        });
        setNotice(`已导出 ${reportTypeLabel(reportType)}（${format}），服务端已写一条导出留痕（含 SHA-256）`);
      } catch (err) {
        setActionError(err instanceof Error ? err.message : '导出失败');
      }
    },
    [
      reportType,
      periodFromId,
      periodToId,
      compareFromId,
      compareToId,
      currency,
      subjectCode,
    ],
  );

  /** 登记一条报表模板（系统不预置任何一行）。 */
  const runCreateTemplate = useCallback(async () => {
    setActionError('');
    setNotice('');
    try {
      await createFinancialReportTemplate({
        reportType,
        subjectCode: templateSubject,
        subjectName: '',
        lineCode: templateLineCode || templateSubject,
        lineName: templateLineName,
        section: '',
        cashFlowGroup: reportType === 'CASH_FLOW' ? templateCashGroup : null,
        cashRole: reportType === 'CASH_FLOW' ? templateCashRole : null,
        sortOrder: 0,
        enabled: true,
        note: '',
        expectedVersion: null,
      });
      const t = await fetchFinancialReportTemplates();
      setTemplates(t);
      setTemplateSubject('');
      setTemplateLineCode('');
      setTemplateLineName('');
      setNotice('报表模板已登记');
      await loadReport();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '登记报表模板失败');
    }
  }, [
    reportType,
    templateSubject,
    templateLineCode,
    templateLineName,
    templateCashRole,
    templateCashGroup,
    loadReport,
  ]);

  /** 停用一条报表模板（不删：历史口径要能复现）。 */
  const runDisableTemplate = useCallback(
    async (id: string) => {
      setActionError('');
      setNotice('');
      try {
        await disableFinancialReportTemplate(id);
        const t = await fetchFinancialReportTemplates();
        setTemplates(t);
        setNotice('报表模板已停用（历史报表口径仍可复现）');
        await loadReport();
      } catch (err) {
        setActionError(err instanceof Error ? err.message : '停用报表模板失败');
      }
    },
    [loadReport],
  );

  const unavailable = caps?.capabilities.filter((c) => !c.available) ?? [];
  const downstreamGaps = caps?.downstreamGaps.filter((g) => !g.available) ?? [];
  const basis = caps?.bases.find((b) => b.reportType === reportType);
  const groups = result?.currencyGroups ?? [];
  const hasRows = groups.some((g) => g.rows.length > 0 || g.ledgerEntries.length > 0);
  const emptyHint = useMemo(() => {
    if (!result) return '';
    if (result.currencyGroups.length === 0) return '这段时间范围内没有任何已过账的凭证。';
    if (!hasRows) return '这段时间范围内没有可显示的报表行（草稿与作废凭证不进报表）。';
    return '';
  }, [result, hasRows]);

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="财务报表"
        description="资产负债表、利润表、现金流量表、科目余额表、明细账；支持期间对比、导出、钻取到凭证"
        actions={
          <div className="flex gap-2">
            <Button variant="secondary" onClick={() => void runExport('CSV')} disabled={loading}>
              导出 CSV
            </Button>
            <Button variant="secondary" onClick={() => void runExport('JSON')} disabled={loading}>
              导出 JSON
            </Button>
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
          </div>
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
          {/* ---------- 口径（写死在服务端，随报表一起返回） ---------- */}
          {basis && (
            <SectionCard
              title={`${basis.reportTypeLabel} · 取数口径`}
              description="草稿 / 红冲 / 多币种三条口径由服务端判定，页面原样显示"
            >
              <ul className="text-muted-foreground space-y-1 text-sm">
                <li>草稿：{basis.draftPolicy}</li>
                <li>红冲：{basis.reversalPolicy}</li>
                <li>多币种：{basis.currencyPolicy}</li>
                <li>本表口径：{basis.detail}</li>
              </ul>
            </SectionCard>
          )}

          {/* ---------- 能力缺口 ---------- */}
          {unavailable.length > 0 && (
            <Notice tone="warning" title="当前需要租户自己配置的口径">
              {unavailable
                .map((c) => `${c.name}：${c.detail}`)
                .join('；')}
            </Notice>
          )}
          {downstreamGaps.length > 0 && (
            <Notice tone="info" title="未交付的下游">
              {downstreamGaps.map((g) => `${g.module} ${g.name}（${g.detail}）`).join('；')}
            </Notice>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-5">
            <StatCard label="本期凭证" value={result?.voucherCount ?? 0} unit="张" />
            <StatCard label="本期分录行" value={result?.lineCount ?? 0} unit="行" />
            <StatCard
              label="排除的草稿"
              value={result?.draftExcludedCount ?? 0}
              unit="张"
              comparisonLabel="草稿不是账"
            />
            <StatCard
              label="已红冲"
              value={result?.reversedVoucherCount ?? 0}
              unit="张"
              comparisonLabel="按净额抵消"
            />
            <StatCard label="币种分组" value={groups.length} unit="组" />
          </div>

          {/* ---------- 筛选 ---------- */}
          <SectionCard
            title="筛选"
            description="期间范围是一段期间序列（本月 / 本季 / 年初至今都是它）；填上对比期就并排给出变动额。"
          >
            <div className="grid gap-3 md:grid-cols-6">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={reportType}
                onChange={(e) => setReportType(e.target.value as ReportType)}
                aria-label="报表类型"
              >
                {REPORT_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {reportTypeLabel(t)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={periodFromId}
                onChange={(e) => setPeriodFromId(e.target.value)}
                aria-label="本期起始期间"
              >
                <option value="">本期起始期间</option>
                {periods.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.periodCode}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={periodToId}
                onChange={(e) => setPeriodToId(e.target.value)}
                aria-label="本期结束期间"
              >
                <option value="">本期结束期间</option>
                {periods.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.periodCode}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={compareFromId}
                onChange={(e) => setCompareFromId(e.target.value)}
                aria-label="对比期起始期间"
              >
                <option value="">对比期（可不填）</option>
                {periods.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.periodCode}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={compareToId}
                onChange={(e) => setCompareToId(e.target.value)}
                aria-label="对比期结束期间"
              >
                <option value="">对比期（可不填）</option>
                {periods.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.periodCode}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={currency}
                onChange={(e) => setCurrency(e.target.value)}
                aria-label="币种"
              >
                <option value="">全部币种（分组显示）</option>
                {(options?.currencies ?? []).map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
            {reportType === 'GENERAL_LEDGER' && (
              <div className="mt-3 grid gap-3 md:grid-cols-3">
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={subjectCode}
                  onChange={(e) => setSubjectCode(e.target.value)}
                  aria-label="科目"
                >
                  <option value="">选择科目（明细账逐笔展开一个科目）</option>
                  {(options?.subjects ?? []).map((s) => (
                    <option key={s.subjectCode} value={s.subjectCode}>
                      {s.subjectCode} {s.subjectName}
                    </option>
                  ))}
                </select>
              </div>
            )}
          </SectionCard>

          {/* ---------- 报表 ---------- */}
          <SectionCard
            title={result ? `${result.reportTypeLabel}（${result.periodLabel}）` : '报表'}
            description={
              result?.comparePeriodLabel
                ? `对比期 ${result.comparePeriodLabel} —— 两段期间同一套口径重算`
                : '填上对比期就并排给出变动额'
            }
            extra={
              <Button variant="ghost" onClick={() => void loadReport()} disabled={loading}>
                <RefreshCw className="mr-2 h-4 w-4" />
                重新取数
              </Button>
            }
          >
            {result?.notice && (
              <Notice tone="info" title="取数说明">
                {result.notice}
              </Notice>
            )}

            {!hasRows && emptyHint && (
              <EmptyState
                title={reportTypeLabel(reportType)}
                description={emptyHint}
                action={
                  <Button variant="secondary" onClick={() => void loadReport()}>
                    重新取数
                  </Button>
                }
              />
            )}

            {hasRows &&
              groups.map((group) => (
                <div key={group.currency} className="space-y-3">
                  <div className="flex items-center gap-2">
                    <Badge tone="neutral">{group.currency}</Badge>
                    <span className="text-muted-foreground text-sm">
                      {group.rows.length} 行 · 涉及 {group.totals.voucherCount} 张凭证
                    </span>
                  </div>

                  {group.checks.length > 0 && (
                    <div className="flex flex-wrap gap-2">
                      {group.checks.map((check) => (
                        <Badge
                          key={check.checkName}
                          tone={reportCheckTone(check) === 'danger' ? 'danger' : 'success'}
                        >
                          {reportCheckSummary(check)}
                        </Badge>
                      ))}
                    </div>
                  )}

                  {group.rows.length > 0 && (
                    <table className="text-copy-13 w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-2">报表行</th>
                          <th className="py-2">段落</th>
                          <th className="py-2">期初余额</th>
                          <th className="py-2">本期借</th>
                          <th className="py-2">本期贷</th>
                          <th className="py-2">期末余额</th>
                          {result?.comparePeriodLabel && (
                            <>
                              <th className="py-2">对比期期末</th>
                              <th className="py-2">期末变动</th>
                            </>
                          )}
                          <th className="py-2">凭证</th>
                          <th className="py-2">钻取</th>
                        </tr>
                      </thead>
                      <tbody>
                        {group.rows.map((row) => (
                          <tr key={row.rowKey} className="border-border border-t">
                            <td className="py-2">
                              <span className={isReportTotalRow(row) ? 'font-semibold' : 'font-medium'}>
                                {row.lineName}
                              </span>
                              {row.subjectCode ? (
                                <span className="text-muted-foreground ml-1 text-xs">
                                  {row.subjectCode}
                                </span>
                              ) : null}
                            </td>
                            <td className="text-muted-foreground py-2">
                              {row.sectionLabel || reportSectionLabel(row.section)}
                            </td>
                            <td className="py-2">{formatReportAmount(row.openingBalance)}</td>
                            <td className="py-2">{formatReportAmount(row.periodDebit)}</td>
                            <td className="py-2">{formatReportAmount(row.periodCredit)}</td>
                            <td className="py-2 font-medium">
                              {formatReportMoney(row.closingBalance, row.currency)}
                            </td>
                            {result?.comparePeriodLabel && (
                              <>
                                <td className="py-2">
                                  {formatReportAmount(row.comparisonClosingBalance)}
                                </td>
                                <td className="py-2">{formatReportDelta(row.deltaClosingBalance)}</td>
                              </>
                            )}
                            <td className="py-2">{row.voucherCount}</td>
                            <td className="py-2">
                              <Button variant="ghost" onClick={() => void openDrill(row)}>
                                <Search className="mr-2 h-4 w-4" />
                                钻取到凭证
                              </Button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}

                  {group.ledgerEntries.length > 0 && (
                    <table className="text-copy-13 w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-2">日期</th>
                          <th className="py-2">凭证 / 行</th>
                          <th className="py-2">摘要</th>
                          <th className="py-2">对方科目</th>
                          <th className="py-2">借</th>
                          <th className="py-2">贷</th>
                          <th className="py-2">逐笔余额</th>
                        </tr>
                      </thead>
                      <tbody>
                        {group.ledgerEntries.map((entry) => (
                          <tr key={entry.rowKey} className="border-border border-t">
                            <td className="py-2">{entry.entryDate}</td>
                            <td className="py-2">
                              {entry.voucherNo} · 行 {entry.lineNo} · {reportLineTypeLabel(entry.lineType)}
                            </td>
                            <td className="py-2">{entry.summary}</td>
                            <td className="py-2">{entry.counterpartSummary}</td>
                            <td className="py-2">{formatReportAmount(entry.debitAmount)}</td>
                            <td className="py-2">{formatReportAmount(entry.creditAmount)}</td>
                            <td className="py-2 font-medium">
                              {formatReportMoney(entry.runningBalance, entry.currency)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              ))}
          </SectionCard>

          {/* ---------- 报表模板 ---------- */}
          <SectionCard
            title="报表模板"
            description="系统不预置任何一行：模板把若干科目归并成一条报表行，并指定现金流量表的现金科目与活动类别。"
          >
            <div className="grid gap-3 md:grid-cols-5">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={templateSubject}
                onChange={(e) => setTemplateSubject(e.target.value)}
                aria-label="模板科目"
              >
                <option value="">科目</option>
                {(options?.subjects ?? []).map((s) => (
                  <option key={s.subjectCode} value={s.subjectCode}>
                    {s.subjectCode} {s.subjectName}
                  </option>
                ))}
              </select>
              <input
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={templateLineCode}
                onChange={(e) => setTemplateLineCode(e.target.value)}
                placeholder="报表行编码（留空＝用科目编码）"
                aria-label="报表行编码"
              />
              <input
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={templateLineName}
                onChange={(e) => setTemplateLineName(e.target.value)}
                placeholder="报表行名称"
                aria-label="报表行名称"
              />
              {reportType === 'CASH_FLOW' && (
                <>
                  <select
                    className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                    value={templateCashRole}
                    onChange={(e) => setTemplateCashRole(e.target.value)}
                    aria-label="现金角色"
                  >
                    <option value="">现金角色</option>
                    <option value="CASH">现金科目</option>
                    <option value="NON_CASH">对方科目</option>
                  </select>
                  <select
                    className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                    value={templateCashGroup}
                    onChange={(e) => setTemplateCashGroup(e.target.value)}
                    aria-label="活动类别"
                  >
                    <option value="">活动类别</option>
                    <option value="OPERATING">经营活动</option>
                    <option value="INVESTING">投资活动</option>
                    <option value="FINANCING">筹资活动</option>
                  </select>
                </>
              )}
              <Button variant="secondary" onClick={() => void runCreateTemplate()} disabled={!templateSubject}>
                登记模板
              </Button>
            </div>

            {templates.length > 0 && (
              <table className="text-copy-13 mt-3 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">报表</th>
                    <th className="py-2">科目</th>
                    <th className="py-2">报表行</th>
                    <th className="py-2">现金角色 / 活动</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {templates.map((t) => (
                    <tr key={t.id} className="border-border border-t">
                      <td className="py-2">{t.reportTypeLabel}</td>
                      <td className="py-2">{t.subjectCode}</td>
                      <td className="py-2">
                        {t.lineName || t.lineCode}
                        {t.section ? ` · ${t.section}` : ''}
                      </td>
                      <td className="py-2">
                        {t.cashRoleLabel || '—'}
                        {t.cashFlowGroupLabel ? ` / ${t.cashFlowGroupLabel}` : ''}
                      </td>
                      <td className="py-2">{t.enabled ? '启用' : '停用'}</td>
                      <td className="py-2">
                        {t.enabled && (
                          <Button variant="ghost" onClick={() => void runDisableTemplate(t.id)}>
                            停用
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </>
      )}

      {/* ---------- 钻取抽屉 ---------- */}
      <DetailDrawer open={drill !== null} onOpenChange={(open) => !open && setDrill(null)}>
        <DetailDrawerContent width="xl">
          <DetailDrawerHeader>
            <DetailDrawerTitle>
              钻取到凭证{drillRow ? `：${drillRow.lineName}` : ''}
            </DetailDrawerTitle>
            <DetailDrawerDescription>
              {drill
                ? `${drill.reportTypeLabel} · ${drill.periodLabel} · ${drill.currency} · 借 ${formatReportAmount(
                    drill.debitTotal,
                  )} / 贷 ${formatReportAmount(drill.creditTotal)} / 净额 ${formatReportAmount(
                    drill.netTotal,
                  )}`
                : '构成这一行的真实凭证与分录行'}
            </DetailDrawerDescription>
          </DetailDrawerHeader>
          <DetailDrawerBody>
            {drill && (
              <>
                {drillRow && (
                  <p className="text-muted-foreground mb-3 text-xs">
                    钻取路径：{drillRow.drillPath}
                  </p>
                )}
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">日期</th>
                      <th className="py-2">凭证</th>
                      <th className="py-2">行</th>
                      <th className="py-2">科目</th>
                      <th className="py-2">摘要</th>
                      <th className="py-2">金额</th>
                    </tr>
                  </thead>
                  <tbody>
                    {drill.entries.map((entry) => (
                      <tr key={`${entry.voucherId}-${entry.lineId}`} className="border-border border-t">
                        <td className="py-2">{entry.voucherDate}</td>
                        <td className="py-2">{entry.voucherNo}</td>
                        <td className="py-2">
                          {entry.lineNo} · {reportLineTypeLabel(entry.lineType)}
                        </td>
                        <td className="py-2">
                          {entry.subjectCode} {entry.subjectName}
                        </td>
                        <td className="py-2">{entry.summary}</td>
                        <td className="py-2 font-medium">
                          {formatReportMoney(entry.amount, entry.currency)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {drill.entries.length === 0 && (
                  <EmptyState title="没有可钻取的凭证" description="这一行本期没有发生额。" />
                )}
              </>
            )}
          </DetailDrawerBody>
        </DetailDrawerContent>
      </DetailDrawer>
    </div>
  );
}
