'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  FinanceBankReconCapabilities,
  FinanceBankReconOptions,
  FinanceBankReconReportRow,
  FinanceBankReconSessionRow,
  FinanceBankReconSessionPage,
  FinanceBankReconStatementLinePage,
  FinanceBankReconSystemLinePage,
  FinanceBankReconMatchPage,
  FinanceBankReconUnmatchedPage,
  FinanceBankReconClaimPage,
  FinanceBankReconRulePage,
  FinanceBankReconEventPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  BANK_RECON_OPTIONAL_COLUMNS,
  BANK_RECON_REQUIRED_COLUMNS,
  bankReconAdjustmentRows,
  bankReconCsvTemplate,
  bankReconDifferenceNote,
  bankReconDirectionLabel,
  bankReconDocumentTypeLabel,
  bankReconRuleTypeLabel,
  bankReconSideLabel,
  bankReconSourceTypeLabel,
  bankReconStatusLabel,
  bankReconUnmatchedStatusLabel,
  claimBankReconUnmatched,
  createBankReconRule,
  createBankReconSession,
  excludeBankReconUnmatched,
  fetchBankReconCapabilities,
  fetchBankReconClaims,
  fetchBankReconEvents,
  fetchBankReconMatches,
  fetchBankReconOptions,
  fetchBankReconReport,
  fetchBankReconRules,
  fetchBankReconSessions,
  fetchBankReconStatementLines,
  fetchBankReconSystemLines,
  fetchBankReconUnmatched,
  formatBankReconAmount,
  formatBankReconDate,
  formatBankReconMoney,
  formatBankReconTime,
  importBankReconStatement,
  reconcileBankReconSession,
  runBankReconMatch,
} from './bank-reconciliation-model';

/**
 * 12.7.1.2 银行对账。
 *
 * <p>蓝图 12-finance.md：「导入银行/支付平台流水与系统记录自动匹配，输出未匹配项，支持人工认领与说明，生成对账报告。」加粗句：「<b>未匹配项要能认领到具体单据</b>，否则余额永远平不了——这是财务日常最高频的工作。」
 *
 * <p><b>页面围绕四块内容组织</b>：
 *
 * <ol>
 *   <li><b>对账批次</b>：列表（真分页 + 真筛选）+ 建批次（账户 + 期间 + 对账单余额）；</li>
 *   <li><b>流水与匹配</b>：导入 CSV / JSON（真解析，**逐行告警必须看得见**）+ 跑自动匹配
 *       + 银行流水行 / 系统记录 / 匹配结果三张清单；</li>
 *   <li><b>未匹配清单</b>：🔴 认领到具体单据（收款单 / 付款单 / 结算单）或说明后排除，
 *       并给出**认领 / 说明的举证流水**；</li>
 *   <li><b>对账报告</b>：余额调节表 + 差额口径（差额为 0 才叫账平了）。</li>
 * </ol>
 *
 * <p><b>页面不做业务算术</b>：差额、调节后余额、匹配数都由服务端算好，页面只原样展示；
 * 「算不出」（服务端给 null）显示「—」而**不是 0**。
 *
 * <p>🔴 <b>不允许「金额相同即匹配」（卷 12 硬约束）</b>：匹配由**服务端**按租户自己的
 * 规则跑，页面**不**自己配对，也不提供「手动把这两行连起来」的按钮 ——
 * 连起来这件事要走「认领到具体单据」，才有据可查。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「还没建批次」与「筛选无结果」。
 */

/** 导入格式（受控词表，与服务端一致）。 */
const IMPORT_FORMATS = ['CSV', 'JSON'] as const;

/** 未匹配项的筛选状态。 */
const UNMATCHED_FILTERS = [
  { code: '', label: '全部' },
  { code: 'OPEN', label: '待处理' },
  { code: 'CLAIMED', label: '已认领' },
  { code: 'EXCLUDED', label: '已排除' },
] as const;

/** 未匹配项的筛选侧。 */
const SIDE_FILTERS = [
  { code: '', label: '两侧' },
  { code: 'BANK', label: '银行侧' },
  { code: 'SYSTEM', label: '系统侧' },
] as const;

/** 认领到的单据类型（受控词表）。 */
const DOCUMENT_TYPES = ['RECEIPT', 'PAYMENT_ORDER', 'SETTLEMENT', 'OTHER'] as const;

export function BankReconciliationWorkspace() {
  const [capabilities, setCapabilities] = useState<FinanceBankReconCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceBankReconOptions | null>(null);
  const [sessions, setSessions] = useState<FinanceBankReconSessionPage | null>(null);
  const [selected, setSelected] = useState<FinanceBankReconSessionRow | null>(null);

  const [statementLines, setStatementLines] = useState<FinanceBankReconStatementLinePage | null>(
    null,
  );
  const [systemLines, setSystemLines] = useState<FinanceBankReconSystemLinePage | null>(null);
  const [matches, setMatches] = useState<FinanceBankReconMatchPage | null>(null);
  const [unmatched, setUnmatched] = useState<FinanceBankReconUnmatchedPage | null>(null);
  const [claims, setClaims] = useState<FinanceBankReconClaimPage | null>(null);
  const [events, setEvents] = useState<FinanceBankReconEventPage | null>(null);
  const [report, setReport] = useState<FinanceBankReconReportRow | null>(null);
  const [rules, setRules] = useState<FinanceBankReconRulePage | null>(null);

  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [accountFilter, setAccountFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageNo, setPageNo] = useState(0);
  const [unmatchedStatus, setUnmatchedStatus] = useState('');
  const [unmatchedSide, setUnmatchedSide] = useState('');

  // 建批次草稿
  const [draftAccountId, setDraftAccountId] = useState('');
  const [draftPeriodStart, setDraftPeriodStart] = useState('');
  const [draftPeriodEnd, setDraftPeriodEnd] = useState('');
  const [draftOpening, setDraftOpening] = useState('');
  const [draftClosing, setDraftClosing] = useState('');
  const [draftNote, setDraftNote] = useState('');

  // 导入草稿
  const [importFormat, setImportFormat] = useState<(typeof IMPORT_FORMATS)[number]>('CSV');
  const [importContent, setImportContent] = useState('');
  const [importWarnings, setImportWarnings] = useState<string[]>([]);

  // 认领草稿
  const [claimTarget, setClaimTarget] = useState<string | null>(null);
  const [claimDocumentType, setClaimDocumentType] = useState<(typeof DOCUMENT_TYPES)[number]>(
    'RECEIPT',
  );
  const [claimDocumentId, setClaimDocumentId] = useState('');
  const [claimDocumentNo, setClaimDocumentNo] = useState('');
  const [claimReason, setClaimReason] = useState('');

  const params = useMemo(
    () => ({
      ...(accountFilter ? { accountId: accountFilter } : {}),
      ...(statusFilter ? { status: statusFilter } : {}),
      ...(keyword ? { keyword } : {}),
    }),
    [accountFilter, statusFilter, keyword],
  );

  const loadSessions = useCallback(async () => {
    const page = await fetchBankReconSessions(params, pageNo, 20);
    setSessions(page);
  }, [params, pageNo]);

  const loadDetail = useCallback(async (sessionId: string) => {
    setDetailLoading(true);
    try {
      const [
        statementPage,
        systemPage,
        matchPage,
        unmatchedPage,
        claimPage,
        eventPage,
      ] = await Promise.all([
        fetchBankReconStatementLines(sessionId, 0, 20),
        fetchBankReconSystemLines(sessionId, 0, 20),
        fetchBankReconMatches(sessionId, 0, 20),
        fetchBankReconUnmatched(sessionId, unmatchedStatus, unmatchedSide, 0, 20),
        fetchBankReconClaims(sessionId, undefined, 0, 20),
        fetchBankReconEvents(sessionId, 0, 20),
      ]);
      setStatementLines(statementPage);
      setSystemLines(systemPage);
      setMatches(matchPage);
      setUnmatched(unmatchedPage);
      setClaims(claimPage);
      setEvents(eventPage);
      try {
        setReport(await fetchBankReconReport(sessionId));
      } catch {
        // 还没生成报告 —— 这是**正常状态**（不是错误）。
        setReport(null);
      }
    } finally {
      setDetailLoading(false);
    }
  }, [unmatchedStatus, unmatchedSide]);

  useEffect(() => {
    let alive = true;
    (async () => {
      setLoading(true);
      setError('');
      try {
        const [caps, opts, rulePage] = await Promise.all([
          fetchBankReconCapabilities(),
          fetchBankReconOptions(),
          fetchBankReconRules(0, 20),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
        setRules(rulePage);
        await loadSessions();
      } catch (e) {
        if (!alive) return;
        setError(e instanceof Error ? e.message : '加载失败');
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [loadSessions]);

  useEffect(() => {
    if (!selected) return;
    void loadDetail(selected.id);
  }, [selected, loadDetail]);

  // ---------------------------------------------------------------- 动作

  async function guard(action: () => Promise<void>) {
    setActionError('');
    setNotice('');
    try {
      await action();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    }
  }

  function selectSession(session: FinanceBankReconSessionRow) {
    setSelected(session);
    setReport(null);
    setImportWarnings([]);
    setClaimTarget(null);
  }

  function createSession() {
    return guard(async () => {
      const created = await createBankReconSession({
        accountId: draftAccountId,
        periodStart: draftPeriodStart,
        periodEnd: draftPeriodEnd,
        statementOpeningBalance: draftOpening === '' ? null : Number(draftOpening),
        statementClosingBalance: draftClosing === '' ? null : Number(draftClosing),
        sourceSystem: 'manual',
        note: draftNote,
      });
      setSelected(created);
      setNotice(`已建立对账批次 ${created.reconNo}`);
      setDraftAccountId('');
      setDraftPeriodStart('');
      setDraftPeriodEnd('');
      setDraftOpening('');
      setDraftClosing('');
      setDraftNote('');
      await loadSessions();
    });
  }

  function doImport() {
    if (!selected) return;
    return guard(async () => {
      const result = await importBankReconStatement(selected.id, {
        format: importFormat,
        content: importContent,
        statementOpeningBalance: null,
        statementClosingBalance: null,
      });
      setImportWarnings(result.warnings);
      setNotice(
        `导入完成：入账 ${result.importedCount} 行，跳过 ${result.skippedCount} 行` +
          (result.skippedCount > 0 ? '（逐条原因见下方告警）' : ''),
      );
      setImportContent('');
      await loadSessions();
      await loadDetail(selected.id);
    });
  }

  function doMatch() {
    if (!selected) return;
    return guard(async () => {
      const result = await runBankReconMatch(selected.id);
      setNotice(
        `匹配完成：匹配 ${result.matchedCount} 对，未匹配（银行侧 ${result.unmatchedBankCount} / 系统侧 ${result.unmatchedSystemCount}）`,
      );
      await loadSessions();
      await loadDetail(selected.id);
    });
  }

  function doReconcile() {
    if (!selected) return;
    return guard(async () => {
      const result = await reconcileBankReconSession(selected.id);
      setReport(result);
      setNotice('已生成对账报告并确认对账');
      await loadSessions();
      await loadDetail(selected.id);
    });
  }

  function doClaim() {
    if (!selected || !claimTarget) return;
    return guard(async () => {
      await claimBankReconUnmatched(selected.id, claimTarget, {
        documentType: claimDocumentType,
        documentId: claimDocumentId === '' ? null : claimDocumentId,
        documentNo: claimDocumentNo,
        reason: claimReason,
      });
      setNotice('已认领到具体单据');
      setClaimTarget(null);
      setClaimDocumentId('');
      setClaimDocumentNo('');
      setClaimReason('');
      await loadSessions();
      await loadDetail(selected.id);
    });
  }

  function doExclude(unmatchedId: string, reason: string) {
    if (!selected || reason.trim() === '') {
      setActionError('排除必须写理由');
      return Promise.resolve();
    }
    return guard(async () => {
      await excludeBankReconUnmatched(selected.id, unmatchedId, { reason });
      setNotice('已排除该未匹配项');
      await loadSessions();
      await loadDetail(selected.id);
    });
  }

  // ---------------------------------------------------------------- 渲染

  const accounts = options?.accounts ?? [];
  const adjustmentRows = report ? bankReconAdjustmentRows(report) : [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="银行对账"
        description="导入银行 / 支付平台流水，与我方收支记录自动匹配，输出未匹配清单并生成对账报告。"
        actions={
          <Button
            variant="ghost"
            onClick={() => {
              void loadSessions();
            }}
            aria-label="刷新对账批次"
          >
            <RefreshCw className="size-4" />
            刷新
          </Button>
        }
      />

      {error ? (
        <Notice tone="danger" title="加载失败">
          {error}
        </Notice>
      ) : null}
      {actionError ? (
        <Notice tone="danger" title="操作失败">
          {actionError}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {capabilities ? (
        <SectionCard title="能力声明" description="哪些真接通、哪些没交付、归谁">
          <ul className="space-y-2">
            {capabilities.capabilities.map((c) => (
              <li key={`${c.module}-${c.name}`} className="text-copy-13">
                <Badge tone={c.available ? 'success' : 'warning'}>
                  {c.module} {c.name}
                </Badge>
                <span className="text-muted-foreground ml-2">{c.basis}</span>
                <p className="text-muted-foreground mt-1">{c.detail}</p>
              </li>
            ))}
          </ul>
          {capabilities.downstreamGaps.length > 0 ? (
            <div className="mt-4 space-y-2">
              <p className="text-label-13">下游缺口</p>
              <ul className="space-y-2">
                {capabilities.downstreamGaps.map((g) => (
                  <li key={`${g.module}-${g.name}`} className="text-copy-13">
                    <Badge tone="warning">
                      {g.module} {g.name}
                    </Badge>
                    <p className="text-muted-foreground mt-1">{g.detail}</p>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </SectionCard>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-3">
        <SectionCard title="对账批次" description="一个资金账户 × 一个期间" className="lg:col-span-2">
          {loading ? (
            <p className="text-muted-foreground text-copy-13">加载中…</p>
          ) : sessions && sessions.items.length === 0 ? (
            <EmptyState
              title="还没有对账批次"
              description="先在右边建一个批次：选资金账户与对账期间，然后导入银行流水。"
            />
          ) : (
            <div className="space-y-3">
              <div className="flex flex-wrap items-end gap-2">
                <FormField id="recon-account" label="账户">
                  <Input
                    value={accountFilter}
                    onChange={(e) => {
                      setAccountFilter(e.target.value);
                      setPageNo(0);
                    }}
                    placeholder="按账户 id 过滤"
                  />
                </FormField>
                <FormField id="recon-status" label="状态">
                  <Input
                    value={statusFilter}
                    onChange={(e) => {
                      setStatusFilter(e.target.value);
                      setPageNo(0);
                    }}
                    placeholder="DRAFT / IMPORTED / MATCHED / RECONCILED"
                  />
                </FormField>
                <FormField id="recon-keyword" label="关键字">
                  <Input
                    value={keyword}
                    onChange={(e) => {
                      setKeyword(e.target.value);
                      setPageNo(0);
                    }}
                    placeholder="单号 / 账户名 / 备注"
                  />
                </FormField>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2 pr-3">对账单号</th>
                      <th className="py-2 pr-3">账户</th>
                      <th className="py-2 pr-3">期间</th>
                      <th className="py-2 pr-3">币种</th>
                      <th className="py-2 pr-3">流水</th>
                      <th className="py-2 pr-3">匹配</th>
                      <th className="py-2 pr-3">待处理</th>
                      <th className="py-2 pr-3">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sessions?.items.map((s) => (
                      <tr
                        key={s.id}
                        className="cursor-pointer border-b last:border-0"
                        onClick={() => selectSession(s)}
                      >
                        <td className="py-2 pr-3">{s.reconNo}</td>
                        <td className="py-2 pr-3">
                          {s.accountNo}
                          <span className="text-muted-foreground ml-1">{s.accountName}</span>
                        </td>
                        <td className="py-2 pr-3">
                          {formatBankReconDate(s.periodStart)} → {formatBankReconDate(s.periodEnd)}
                        </td>
                        <td className="py-2 pr-3">{s.currency}</td>
                        <td className="py-2 pr-3">{s.statementLineCount}</td>
                        <td className="py-2 pr-3">{s.matchedCount}</td>
                        <td className="py-2 pr-3">
                          {s.unmatchedBankCount + s.unmatchedSystemCount}
                        </td>
                        <td className="py-2 pr-3">
                          <Badge tone={s.status === 'RECONCILED' ? 'success' : 'neutral'}>
                            {bankReconStatusLabel(s.status, s.statusLabel)}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {sessions && sessions.total > 20 ? (
                <div className="flex items-center gap-2">
                  <Button
                    variant="ghost"
                    disabled={pageNo === 0}
                    onClick={() => setPageNo((p) => Math.max(0, p - 1))}
                  >
                    上一页
                  </Button>
                  <span className="text-copy-12 text-muted-foreground">
                    第 {pageNo + 1} 页 · 共 {sessions.total} 条
                  </span>
                  <Button
                    variant="ghost"
                    disabled={(pageNo + 1) * 20 >= sessions.total}
                    onClick={() => setPageNo((p) => p + 1)}
                  >
                    下一页
                  </Button>
                </div>
              ) : null}
            </div>
          )}
        </SectionCard>

        <SectionCard title="建对账批次" description="币种由服务端继承账户（单账户单币种）">
          <div className="space-y-3">
            <FormField id="new-account" label="资金账户" required>
              <Input
                value={draftAccountId}
                onChange={(e) => setDraftAccountId(e.target.value)}
                placeholder="账户 id"
                list="recon-account-options"
              />
            </FormField>
            <datalist id="recon-account-options">
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.accountNo} {a.accountName}
                </option>
              ))}
            </datalist>
            <p className="text-muted-foreground text-copy-12">
              可对账账户（来自 12.7.1.1 的真实账户档案）：
              {accounts.length === 0 ? '本租户还没有启用中的资金账户。' : ''}
            </p>
            <ul className="text-muted-foreground space-y-1 text-copy-12">
              {accounts.map((a) => (
                <li key={a.id}>
                  {a.accountNo} · {a.accountName} · {a.currency} · 当前余额{' '}
                  {formatBankReconMoney(a.currentBalance, a.currency)}
                </li>
              ))}
            </ul>
            <FormField id="new-period-start" label="期间开始" required>
              <Input
                type="date"
                value={draftPeriodStart}
                onChange={(e) => setDraftPeriodStart(e.target.value)}
              />
            </FormField>
            <FormField id="new-period-end" label="期间结束" required>
              <Input
                type="date"
                value={draftPeriodEnd}
                onChange={(e) => setDraftPeriodEnd(e.target.value)}
              />
            </FormField>
            <FormField id="new-opening" label="对账单期初余额">
              <Input
                value={draftOpening}
                onChange={(e) => setDraftOpening(e.target.value)}
                placeholder="银行账单上的期初余额"
              />
            </FormField>
            <FormField id="new-closing" label="对账单期末余额">
              <Input
                value={draftClosing}
                onChange={(e) => setDraftClosing(e.target.value)}
                placeholder="银行账单上的期末余额"
              />
            </FormField>
            <FormField id="new-note" label="备注">
              <Input value={draftNote} onChange={(e) => setDraftNote(e.target.value)} />
            </FormField>
            <Button
              onClick={() => void createSession()}
              disabled={!draftAccountId || !draftPeriodStart || !draftPeriodEnd}
            >
              建立批次
            </Button>
          </div>
        </SectionCard>
      </div>

      {selected ? (
        <>
          <SectionCard
            title={`批次 ${selected.reconNo}`}
            description={`账户 ${selected.accountNo} · ${formatBankReconDate(selected.periodStart)} → ${formatBankReconDate(selected.periodEnd)} · ${selected.currency}`}
            extra={
              <div className="flex flex-wrap gap-2">
                <Button variant="ghost" onClick={() => void doMatch()}>
                  跑自动匹配
                </Button>
                <Button variant="ghost" onClick={() => void doReconcile()}>
                  生成对账报告并确认
                </Button>
              </div>
            }
          >
            <div className="grid gap-3 sm:grid-cols-4">
              <StatCard label="银行流水行" value={String(selected.statementLineCount)} />
              <StatCard label="系统记录" value={String(selected.systemLineCount)} />
              <StatCard label="已匹配" value={String(selected.matchedCount)} />
              <StatCard
                label="待处理差异"
                value={String(selected.unmatchedBankCount + selected.unmatchedSystemCount)}
              />
            </div>
            <p className="text-muted-foreground mt-3 text-copy-12">
              状态：{bankReconStatusLabel(selected.status, selected.statusLabel)}；对账单期末余额{' '}
              {formatBankReconMoney(selected.statementClosingBalance, selected.currency)}；我方账面期末余额{' '}
              {selected.bookClosingBalance === null
                ? '—（读不到）'
                : formatBankReconMoney(selected.bookClosingBalance, selected.currency)}
            </p>
          </SectionCard>

          <div className="grid gap-4 lg:grid-cols-2">
            <SectionCard title="导入银行 / 支付平台流水" description="CSV / JSON 真解析，逐行告警">
              <div className="space-y-3">
                <FormField id="import-format" label="文件格式">
                  <Input
                    value={importFormat}
                    onChange={(e) =>
                      setImportFormat(e.target.value as (typeof IMPORT_FORMATS)[number])
                    }
                  />
                </FormField>
                <FormField
                  id="import-content"
                  label="文件内容"
                  hint={`必需的列：${BANK_RECON_REQUIRED_COLUMNS.join('、')}；可选的列：${BANK_RECON_OPTIONAL_COLUMNS.join('、')}。时间必须带时区偏移。`}
                  required
                >
                  <Input
                    value={importContent}
                    onChange={(e) => setImportContent(e.target.value)}
                    placeholder="把账单内容粘进来"
                  />
                </FormField>
                <details className="text-copy-12">
                  <summary className="cursor-pointer">导出模板</summary>
                  <pre className="bg-muted text-copy-12 mt-2 overflow-x-auto rounded p-2">
                    {bankReconCsvTemplate()}
                  </pre>
                </details>
                <Button onClick={() => void doImport()} disabled={importContent.trim() === ''}>
                  导入流水
                </Button>
                {importWarnings.length > 0 ? (
                  <Notice tone="warning" title={`${importWarnings.length} 行没有入账`}>
                    <ul className="list-disc pl-4">
                      {importWarnings.map((w) => (
                        <li key={w}>{w}</li>
                      ))}
                    </ul>
                  </Notice>
                ) : null}
              </div>
            </SectionCard>

            <SectionCard title="未匹配清单" description="🔴 认领到具体单据，否则余额永远平不了">
              <div className="flex flex-wrap items-end gap-2">
                <FormField id="unmatched-status" label="处理状态">
                  <Input
                    value={unmatchedStatus}
                    onChange={(e) => setUnmatchedStatus(e.target.value)}
                    placeholder="OPEN / CLAIMED / EXCLUDED"
                  />
                </FormField>
                <FormField id="unmatched-side" label="哪一侧">
                  <Input
                    value={unmatchedSide}
                    onChange={(e) => setUnmatchedSide(e.target.value)}
                    placeholder="BANK / SYSTEM"
                  />
                </FormField>
              </div>
              <div className="mt-2 flex flex-wrap gap-2 text-copy-12">
                {UNMATCHED_FILTERS.map((f) => (
                  <Button
                    key={f.code || 'all'}
                    variant={unmatchedStatus === f.code ? 'default' : 'ghost'}
                    onClick={() => setUnmatchedStatus(f.code)}
                  >
                    {f.label}
                  </Button>
                ))}
                {SIDE_FILTERS.map((f) => (
                  <Button
                    key={f.code || 'both'}
                    variant={unmatchedSide === f.code ? 'default' : 'ghost'}
                    onClick={() => setUnmatchedSide(f.code)}
                  >
                    {f.label}
                  </Button>
                ))}
              </div>
              {detailLoading ? (
                <p className="text-muted-foreground text-copy-13">加载中…</p>
              ) : unmatched && unmatched.items.length === 0 ? (
                <EmptyState
                  title="没有待处理的差异"
                  description="这个批次下的流水都配上系统记录了（或者已经被认领 / 排除）。"
                />
              ) : (
                <div className="mt-3 space-y-2">
                  {unmatched?.items.map((u) => (
                    <div key={u.id} className="border-b pb-2 last:border-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={u.side === 'BANK' ? 'info' : 'warning'}>
                          {bankReconSideLabel(u.side, u.sideLabel)}
                        </Badge>
                        <Badge
                          tone={u.status === 'OPEN' ? 'danger' : 'success'}
                        >
                          {bankReconUnmatchedStatusLabel(u.status, u.statusLabel)}
                        </Badge>
                        <span className="text-copy-13">
                          {formatBankReconMoney(u.amount, u.currency)} ·{' '}
                          {bankReconDirectionLabel(u.direction, u.directionLabel)}
                        </span>
                        <span className="text-muted-foreground text-copy-12">
                          {formatBankReconTime(u.occurredAt)}
                          {u.counterparty ? ` · ${u.counterparty}` : ''}
                          {u.externalReference ? ` · ${u.externalReference}` : ''}
                        </span>
                      </div>
                      {u.status === 'CLAIMED' ? (
                        <p className="text-copy-12 mt-1">
                          已认领到{' '}
                          {bankReconDocumentTypeLabel(
                            u.claimedDocumentType ?? '',
                            u.claimedDocumentTypeLabel,
                          )}{' '}
                          {u.claimedDocumentNo}：{u.claimReason}
                        </p>
                      ) : null}
                      {u.status === 'EXCLUDED' ? (
                        <p className="text-copy-12 mt-1">已排除：{u.excludeReason}</p>
                      ) : null}
                      {u.status === 'OPEN' ? (
                        <div className="mt-2 flex flex-wrap gap-2">
                          <Button
                            variant="ghost"
                            onClick={() => {
                              setClaimTarget(u.id);
                              setClaimDocumentId('');
                              setClaimDocumentNo('');
                              setClaimReason('');
                            }}
                          >
                            认领到具体单据
                          </Button>
                          <Button
                            variant="ghost"
                            onClick={() => {
                              const reason = window.prompt('排除理由（必填）');
                              if (reason) void doExclude(u.id, reason);
                            }}
                          >
                            说明后排除
                          </Button>
                        </div>
                      ) : null}
                      {claimTarget === u.id ? (
                        <div className="bg-muted mt-2 space-y-2 rounded p-2">
                          <FormField id="claim-type" label="认领到的单据类型" required>
                            <Input
                              value={claimDocumentType}
                              onChange={(e) =>
                                setClaimDocumentType(
                                  e.target.value as (typeof DOCUMENT_TYPES)[number],
                                )
                              }
                            />
                          </FormField>
                          <FormField
                            id="claim-id"
                            label="单据 id"
                            hint="收款单 / 付款单 / 结算单必须填**真实存在**的单据 id，否则服务端会拒绝。"
                          >
                            <Input
                              value={claimDocumentId}
                              onChange={(e) => setClaimDocumentId(e.target.value)}
                            />
                          </FormField>
                          <FormField id="claim-no" label="单据号" required>
                            <Input
                              value={claimDocumentNo}
                              onChange={(e) => setClaimDocumentNo(e.target.value)}
                            />
                          </FormField>
                          <FormField id="claim-reason" label="认领理由" required>
                            <Input
                              value={claimReason}
                              onChange={(e) => setClaimReason(e.target.value)}
                            />
                          </FormField>
                          <div className="flex gap-2">
                            <Button
                              onClick={() => void doClaim()}
                              disabled={claimReason.trim() === '' || claimDocumentNo.trim() === ''}
                            >
                              确认认领
                            </Button>
                            <Button variant="ghost" onClick={() => setClaimTarget(null)}>
                              取消
                            </Button>
                          </div>
                        </div>
                      ) : null}
                    </div>
                  ))}
                </div>
              )}
            </SectionCard>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <SectionCard title="匹配结果" description="每一条都说清「为什么这两行配成一对」">
              {matches && matches.items.length === 0 ? (
                <EmptyState title="还没有匹配结果" description="先导入流水，再跑一次自动匹配。" />
              ) : (
                <ul className="space-y-2">
                  {matches?.items.map((m) => (
                    <li key={m.id} className="border-b pb-2 last:border-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge>{bankReconRuleTypeLabel(m.ruleType, m.ruleTypeLabel)}</Badge>
                        <span className="text-copy-13">
                          {formatBankReconMoney(m.amount, m.currency)} ·{' '}
                          {bankReconDirectionLabel(m.direction)}
                        </span>
                        <span className="text-muted-foreground text-copy-12">
                          {formatBankReconTime(m.occurredAt)}
                        </span>
                      </div>
                      <p className="text-muted-foreground text-copy-12 mt-1">{m.matchBasis}</p>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>

            <SectionCard title="对账报告" description="余额调节表（差额为 0 才叫账平了）">
              {!report ? (
                <EmptyState
                  title="还没有生成对账报告"
                  description="跑完匹配、处理掉待处理差异之后，点「生成对账报告并确认」。"
                />
              ) : (
                <div className="space-y-3">
                  <div className="grid gap-3 sm:grid-cols-3">
                    <StatCard label="已匹配" value={String(report.matchedCount)} />
                    <StatCard
                      label="待处理差异"
                      value={String(report.unmatchedBankCount + report.unmatchedSystemCount)}
                    />
                    <StatCard
                      label="差额"
                      value={formatBankReconMoney(report.difference, report.currency)}
                    />
                  </div>
                  <table className="w-full text-copy-13">
                    <tbody>
                      {adjustmentRows.map((row) => (
                        <tr key={row.label} className="border-b last:border-0">
                          <td className="py-1.5 pr-3">
                            {row.sign} {row.label}
                          </td>
                          <td className="py-1.5 text-right">{row.amount}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <Notice tone="info" title="差额口径">
                    {bankReconDifferenceNote(report)}
                  </Notice>
                </div>
              )}
            </SectionCard>
          </div>

          <div className="grid gap-4 lg:grid-cols-3">
            <SectionCard title="银行流水行" description="外部事实，导入后不可改写">
              {statementLines && statementLines.items.length === 0 ? (
                <EmptyState title="还没有流水" description="导入银行账单后这里会列出逐行流水。" />
              ) : (
                <ul className="space-y-1">
                  {statementLines?.items.map((l) => (
                    <li key={l.id} className="border-b py-1.5 last:border-0">
                      <span className="text-copy-13">
                        {formatBankReconMoney(l.amount, l.currency)} ·{' '}
                        {bankReconDirectionLabel(l.direction, l.directionLabel)}
                      </span>
                      <span className="text-muted-foreground text-copy-12 ml-2">
                        {formatBankReconTime(l.occurredAt)} · {l.bankReference}
                        {l.counterparty ? ` · ${l.counterparty}` : ''}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>

            <SectionCard title="系统记录快照" description="来自 12.2 收付款与 12.3.1.2 结算明细行">
              {systemLines && systemLines.items.length === 0 ? (
                <EmptyState title="没有候选记录" description="这个期间内本币种没有可作为候选的系统记录。" />
              ) : (
                <ul className="space-y-1">
                  {systemLines?.items.map((l) => (
                    <li key={l.id} className="border-b py-1.5 last:border-0">
                      <span className="text-copy-13">
                        {formatBankReconMoney(l.amount, l.currency)} ·{' '}
                        {bankReconDirectionLabel(l.direction, l.directionLabel)}
                      </span>
                      <span className="text-muted-foreground text-copy-12 ml-2">
                        {bankReconSourceTypeLabel(l.sourceType, l.sourceTypeLabel)} · {l.sourceNo}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>

            <SectionCard title="认领 / 说明记录" description="append-only 举证">
              {claims && claims.items.length === 0 ? (
                <EmptyState title="还没有认领记录" description="认领或排除一条未匹配项后，这里会留下举证。" />
              ) : (
                <ul className="space-y-1">
                  {claims?.items.map((c) => (
                    <li key={c.id} className="border-b py-1.5 last:border-0">
                      <span className="text-copy-13">
                        {c.action === 'CLAIM'
                          ? `认领到 ${bankReconDocumentTypeLabel(
                              c.claimedDocumentType ?? '',
                              c.claimedDocumentTypeLabel,
                            )} ${c.claimedDocumentNo}`
                          : '排除'}
                      </span>
                      <span className="text-muted-foreground text-copy-12 ml-2">
                        {formatBankReconTime(c.claimedAt)} · {c.reason}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          </div>

          <SectionCard title="匹配规则" description="🔴 不允许「金额相同即匹配」：三类规则都至少组合两个独立维度">
            {rules && rules.items.length === 0 ? (
              <EmptyState
                title="还没有匹配规则"
                description="建立第一个对账批次时，服务端会写入三条内置默认规则。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2 pr-3">编码</th>
                      <th className="py-2 pr-3">名称</th>
                      <th className="py-2 pr-3">类型</th>
                      <th className="py-2 pr-3">优先级</th>
                      <th className="py-2 pr-3">日期窗口</th>
                      <th className="py-2 pr-3">金额容差</th>
                      <th className="py-2 pr-3">启用</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rules?.items.map((r) => (
                      <tr key={r.id} className="border-b last:border-0">
                        <td className="py-2 pr-3">{r.ruleCode}</td>
                        <td className="py-2 pr-3">{r.ruleName}</td>
                        <td className="py-2 pr-3">{bankReconRuleTypeLabel(r.ruleType, r.ruleTypeLabel)}</td>
                        <td className="py-2 pr-3">{r.priority}</td>
                        <td className="py-2 pr-3">{r.dateWindowDays} 天</td>
                        <td className="py-2 pr-3">{formatBankReconAmount(r.amountTolerance)}</td>
                        <td className="py-2 pr-3">{r.enabled ? '是' : '否'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <p className="text-muted-foreground mt-3 text-copy-12">
              规则由服务端按「单号 / 对手方 / 日期窗口」三类受控类型执行，
              日期窗口上限 31 天（窗口过大等于用「金额相同」冒充匹配）。
            </p>
          </SectionCard>

          <SectionCard title="事件流" description="append-only 举证：每一步都留痕">
            {events && events.items.length === 0 ? (
              <EmptyState title="还没有事件" description="建批次、导入、匹配、认领、确认都会在这里留痕。" />
            ) : (
              <ul className="space-y-1">
                {events?.items.map((e) => (
                  <li key={e.id} className="border-b py-1.5 last:border-0">
                    <span className="text-copy-13">{e.summary}</span>
                    <span className="text-muted-foreground text-copy-12 ml-2">
                      {formatBankReconTime(e.occurredAt)} · {e.eventType}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </>
      ) : (
        <EmptyState
          title="先选一个对账批次"
          description="点上面列表里的一行，就能看到它的流水、匹配结果、未匹配清单与对账报告。"
        />
      )}

      {actionError ? (
        <Notice tone="danger" title="操作失败">
          <TriangleAlert className="size-4" /> {actionError}
        </Notice>
      ) : null}
    </div>
  );
}
