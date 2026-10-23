'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Download, RefreshCw, TriangleAlert } from 'lucide-react';
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
  FinanceVoucherCapabilities,
  FinanceVoucherDetail,
  FinanceVoucherLineRow,
  FinanceVoucherMappingRow,
  FinanceVoucherOptions,
  FinanceVoucherPage,
  FinanceVoucherPeriodRow,
  FinanceVoucherRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  closeVoucherPeriod,
  createVoucher,
  createVoucherMapping,
  createVoucherPeriod,
  fetchVoucher,
  fetchVoucherCapabilities,
  fetchVoucherExport,
  fetchVoucherMappings,
  fetchVoucherOptions,
  fetchVoucherPeriods,
  fetchVouchers,
  formatVoucherAmount,
  formatVoucherMoney,
  formatVoucherPeriod,
  formatVoucherQuantity,
  generateVoucher,
  postVoucher,
  reverseVoucher,
  voidVoucher,
  voucherEditable,
  voucherEventTypeLabel,
  voucherLineTypeLabel,
  voucherPeriodStatusLabel,
  voucherReversible,
  voucherSourceTypeLabel,
  voucherStatusLabel,
  voucherStatusTone,
  voucherVoidable,
} from './voucher-model';

/**
 * 12.5.2.1 凭证管理。
 *
 * <p>蓝图 12-finance.md：「<b>生成与管理会计凭证</b>：由业务事件自动生成或手工录入，支持过账、校验（至少两行、金额有效、期间开放、借贷平衡）与红冲；已过账凭证不得反过账后改写，导出标准凭证文件。」关系：「<b>过账后不可改行，只能红冲</b>；过账事务要锁凭证头并在数据库层防护。」
 *
 * <p><b>页面围绕四件事组织</b>：
 *
 * <ol>
 *   <li><b>会计期间</b>（第一块）：「期间开放」是过账闸门，页面必须让人看得见当前哪些期间开着、
 *       哪些关了，以及关的依据。</li>
 *   <li><b>业务事件映射</b>（第二块）：自动生成凭证要借哪个科目、贷哪个科目，
 *       由租户自己登记（系统<b>不预置任何一行</b>）。</li>
 *   <li><b>凭证列表</b>（第三块）：真分页 + 真筛选 + 按币种分开的汇总。</li>
 *   <li><b>凭证工作区</b>（第四、五块）：手工录入 / 自动生成 + 详情（分录行 + 审计留痕）+
 *       过账 / 红冲 / 作废 + 导出。</li>
 * </ol>
 *
 * <p>🔴 <b>页面绝不提供「编辑已过账凭证」的入口</b>：蓝图写死「已过账凭证不得反过账后改写」。
 * 要改写已过账的凭证，只能<b>红冲</b>（生成一张反向凭证），页面把这两件事分开呈现。
 *
 * <p>🔴 <b>科目不是下拉框</b>：12.5.1.1 会计科目未交付 ⇒ 科目就是两个文本框（编码 + 名称），
 * 页面明确标出「当前不校验科目是否存在」，不假装它是可选的。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有凭证」。
 */

/** 手工录入时的分录行草稿。 */
interface DraftLine {
  lineType: 'DEBIT' | 'CREDIT';
  subjectCode: string;
  subjectName: string;
  summary: string;
  amount: string;
}

/** 业务事件类型（自动生成的四类，与蓝图「发货、收货、收付款、费用计提」一一对应）。 */
const SOURCE_TYPES: { key: string; label: string; kinds: { key: string; label: string }[] }[] = [
  { key: 'SHIPMENT', label: '发货', kinds: [{ key: 'SALE', label: '销售发货' }] },
  { key: 'RECEIPT', label: '收货', kinds: [{ key: 'PURCHASE', label: '采购收货' }] },
  {
    key: 'PAYMENT',
    label: '收付款',
    kinds: [
      { key: 'PAYMENT', label: '付款' },
      { key: 'RECEIPT', label: '收款' },
    ],
  },
  {
    key: 'EXPENSE_ACCRUAL',
    label: '费用计提',
    kinds: [{ key: 'ACCRUAL', label: '费用计提' }],
  },
];

export function VoucherWorkspace() {
  const [caps, setCaps] = useState<FinanceVoucherCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceVoucherOptions | null>(null);
  const [periods, setPeriods] = useState<FinanceVoucherPeriodRow[]>([]);
  const [mappings, setMappings] = useState<FinanceVoucherMappingRow[]>([]);
  const [page, setPage] = useState<FinanceVoucherPage | null>(null);
  const [detail, setDetail] = useState<FinanceVoucherDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [status, setStatus] = useState('');
  const [currency, setCurrency] = useState('');
  const [periodFilter, setPeriodFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 手工录入
  const [draftPeriodId, setDraftPeriodId] = useState('');
  const [draftDate, setDraftDate] = useState('');
  const [draftDescription, setDraftDescription] = useState('');
  const [draftCurrency, setDraftCurrency] = useState('USD');
  const [draftLines, setDraftLines] = useState<DraftLine[]>([
    { lineType: 'DEBIT', subjectCode: '', subjectName: '', summary: '', amount: '' },
    { lineType: 'CREDIT', subjectCode: '', subjectName: '', summary: '', amount: '' },
  ]);

  // 自动生成
  const [genSourceType, setGenSourceType] = useState('SHIPMENT');
  const [genEventKind, setGenEventKind] = useState('SALE');
  const [genSourceId, setGenSourceId] = useState('');
  const [genPeriodId, setGenPeriodId] = useState('');

  // 导出
  const [exportFormat, setExportFormat] = useState('CSV');
  const [exportPeriodId, setExportPeriodId] = useState('');
  const [exportContent, setExportContent] = useState('');

  // 期间
  const [periodCode, setPeriodCode] = useState('');
  const [periodStart, setPeriodStart] = useState('');
  const [periodEnd, setPeriodEnd] = useState('');
  const [periodCurrency, setPeriodCurrency] = useState('USD');

  // 映射
  const [mapSourceType, setMapSourceType] = useState('SHIPMENT');
  const [mapEventKind, setMapEventKind] = useState('SALE');
  const [mapDebitCode, setMapDebitCode] = useState('');
  const [mapDebitName, setMapDebitName] = useState('');
  const [mapCreditCode, setMapCreditCode] = useState('');
  const [mapCreditName, setMapCreditName] = useState('');

  const params = useMemo(
    () => ({
      ...(status ? { status } : {}),
      ...(currency ? { currency } : {}),
      ...(periodFilter ? { periodId: periodFilter } : {}),
      ...(keyword ? { keyword } : {}),
    }),
    [status, currency, periodFilter, keyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, p, m, v] = await Promise.all([
        fetchVoucherCapabilities(),
        fetchVoucherOptions(),
        fetchVoucherPeriods(),
        fetchVoucherMappings(),
        fetchVouchers(params, currentPage, 50),
      ]);
      setCaps(c);
      setOptions(o);
      setPeriods(p);
      setMappings(m);
      setPage(v);
    } catch (err) {
      setError(err instanceof Error ? err.message : '凭证加载失败');
    } finally {
      setLoading(false);
    }
  }, [params, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  const openPeriods = useMemo(() => periods.filter((p) => p.status === 'OPEN'), [periods]);
  /**
   * 契约里 {@code FinanceVoucherRow} 是可空的（SpringDoc 会把「被可空字段引用」的记录标成可空），
   * 所以列表与详情都要先滤掉 null —— **不滤的话 TypeScript 会一直报 `possibly null`**，
   * 而这里滤掉正是运行时该做的：服务端不会返回空的凭证行。
   */
  const rows = useMemo(
    () => (page?.items ?? []).filter((row): row is NonNullable<typeof row> => row !== null),
    [page],
  );
  const voucher = detail?.voucher ?? null;
  const currentKinds = useMemo(
    () => SOURCE_TYPES.find((t) => t.key === genSourceType)?.kinds ?? [],
    [genSourceType],
  );
  const currentMapKinds = useMemo(
    () => SOURCE_TYPES.find((t) => t.key === mapSourceType)?.kinds ?? [],
    [mapSourceType],
  );

  async function openDetail(id: string) {
    setActionError('');
    try {
      setDetail(await fetchVoucher(id));
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '凭证详情加载失败');
    }
  }

  async function submitDraft() {
    setActionError('');
    setNotice('');
    try {
      const result = await createVoucher({
        sourceType: 'MANUAL',
        sourceId: null,
        sourceNo: '',
        periodId: draftPeriodId || null,
        voucherDate: draftDate || null,
        description: draftDescription,
        currency: draftCurrency,
        note: '',
        expectedVersion: null,
        lines: draftLines.map((line) => ({
          lineType: line.lineType,
          subjectCode: line.subjectCode,
          subjectName: line.subjectName,
          summary: line.summary,
          amount: Number(line.amount),
          currency: draftCurrency,
          quantity: null,
          auxiliaryType: 'NONE',
          auxiliaryId: null,
          auxiliaryName: '',
        })),
      });
      setNotice(result.message);
      setDraftDescription('');
      setDraftLines([
        { lineType: 'DEBIT', subjectCode: '', subjectName: '', summary: '', amount: '' },
        { lineType: 'CREDIT', subjectCode: '', subjectName: '', summary: '', amount: '' },
      ]);
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '凭证录入失败');
    }
  }

  async function runPost(id: string, version: number) {
    setActionError('');
    setNotice('');
    try {
      const result = await postVoucher(id, { expectedVersion: version, note: null });
      setNotice(result.message);
      await load();
      await openDetail(id);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '过账失败');
    }
  }

  async function runReverse(id: string, version: number, reason: string) {
    setActionError('');
    setNotice('');
    try {
      const result = await reverseVoucher(id, {
        expectedVersion: version,
        reason,
        voucherDate: null,
        note: null,
      });
      setNotice(result.message);
      await load();
      await openDetail(id);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '红冲失败');
    }
  }

  async function runVoid(id: string, version: number, reason: string) {
    setActionError('');
    setNotice('');
    try {
      const result = await voidVoucher(id, { expectedVersion: version, reason });
      setNotice(result.message);
      await load();
      await openDetail(id);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '作废失败');
    }
  }

  async function runGenerate() {
    setActionError('');
    setNotice('');
    try {
      const result = await generateVoucher({
        sourceType: genSourceType,
        eventKind: genEventKind,
        sourceId: genSourceId,
        periodId: genPeriodId || null,
        voucherDate: null,
        description: null,
        note: null,
      });
      setNotice(result.message);
      setGenSourceId('');
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '自动生成失败');
    }
  }

  async function runExport() {
    setActionError('');
    setNotice('');
    try {
      const content = await fetchVoucherExport({
        format: exportFormat,
        ...(exportPeriodId ? { periodId: exportPeriodId } : {}),
      });
      setExportContent(content);
      setNotice('已导出下面这份标准凭证文件（只含已过账的凭证）');
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '导出失败');
    }
  }

  async function runCreatePeriod() {
    setActionError('');
    setNotice('');
    try {
      await createVoucherPeriod({
        periodCode,
        periodStart,
        periodEnd,
        baseCurrency: periodCurrency,
        note: '',
      });
      setNotice(`已登记会计期间 ${periodCode}`);
      setPeriodCode('');
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '登记会计期间失败');
    }
  }

  async function runClosePeriod(period: FinanceVoucherPeriodRow) {
    setActionError('');
    setNotice('');
    const reason = window.prompt(`关闭会计期间 ${period.periodCode} 的原因？`);
    if (!reason) return;
    try {
      await closeVoucherPeriod(period.id, { reason, expectedVersion: period.version });
      setNotice(`会计期间 ${period.periodCode} 已关闭`);
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '关闭会计期间失败');
    }
  }

  async function runCreateMapping() {
    setActionError('');
    setNotice('');
    try {
      await createVoucherMapping({
        sourceType: mapSourceType,
        eventKind: mapEventKind,
        debitSubjectCode: mapDebitCode,
        debitSubjectName: mapDebitName,
        creditSubjectCode: mapCreditCode,
        creditSubjectName: mapCreditName,
        enabled: true,
        note: '',
      });
      setNotice('已登记业务事件映射');
      setMapDebitCode('');
      setMapDebitName('');
      setMapCreditCode('');
      setMapCreditName('');
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '登记映射失败');
    }
  }

  if (loading && !page) {
    return (
      <PageHeader
        title="凭证管理"
        description="12.5.2.1 · 生成与管理会计凭证，过账后只能红冲"
      />
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="凭证管理"
        description="12.5.2.1 · 生成与管理会计凭证，过账后只能红冲"
        actions={
          <Button variant="ghost" onClick={() => void load()} aria-label="刷新">
            <RefreshCw className="size-4" aria-hidden />
            刷新
          </Button>
        }
      />

      {error ? (
        <Notice tone="danger" title="加载失败">
          <span className="inline-flex items-center gap-1">
            <TriangleAlert className="size-4" aria-hidden />
            {error}
          </span>
        </Notice>
      ) : null}

      {notice ? <Notice tone="success" title="操作完成">{notice}</Notice> : null}
      {actionError ? (
        <Notice tone="danger" title="操作失败">
          {actionError}
        </Notice>
      ) : null}

      {/* 🔴 三条边界必须在页面上说清楚，否则界面会开始骗人。 */}
      <Notice tone="info" title="三条边界">
        <ul className="list-disc space-y-1 pl-5">
          <li>
            <b>已过账的凭证不可改</b>：分录行冻结，要改写只能<b>红冲</b>（生成一张反向凭证）。
          </li>
          <li>
            <b>科目当前是软引用</b>：12.5.1.1 会计科目未交付，科目编码与名称是手填的，
            <b>本页不会替你核对科目是否存在</b>。
          </li>
          <li>
            <b>会计期间只能关、不能重开</b>：重开已关期间是高风险操作且必须审批留痕，
            属 12.5.1.2（未交付）。
          </li>
        </ul>
      </Notice>

      {/* ---------------- 1. 会计期间 ---------------- */}
      <SectionCard title="会计期间" description="「期间开放」是过账闸门：期间关了就过不了账">
        <div className="space-y-4">
          {periods.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">期间</th>
                    <th className="py-2 pr-3">区间</th>
                    <th className="py-2 pr-3">本位币</th>
                    <th className="py-2 pr-3">状态</th>
                    <th className="py-2 pr-3">关闭依据</th>
                    <th className="py-2 pr-3" />
                  </tr>
                </thead>
                <tbody>
                  {periods.map((period) => (
                    <tr key={period.id} className="border-b last:border-0">
                      <td className="py-2 pr-3 font-mono text-xs">{period.periodCode}</td>
                      <td className="py-2 pr-3 text-xs">
                        {formatVoucherPeriod(period.periodStart, period.periodEnd)}
                      </td>
                      <td className="py-2 pr-3">{period.baseCurrency}</td>
                      <td className="py-2 pr-3">
                        <Badge tone={period.status === 'OPEN' ? 'success' : 'neutral'}>
                          {voucherPeriodStatusLabel(period.status)}
                        </Badge>
                      </td>
                      <td className="py-2 pr-3 text-xs text-muted-foreground">
                        {period.closedReason || '—'}
                      </td>
                      <td className="py-2 pr-3 text-right">
                        {period.status === 'OPEN' ? (
                          <Button variant="ghost" onClick={() => void runClosePeriod(period)}>
                            关闭
                          </Button>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <EmptyState
              title="还没有登记会计期间"
              description="过账前必须有一个开放的期间 —— 先登记一个"
            />
          )}

          <div className="grid gap-2 sm:grid-cols-5">
            <Input
              placeholder="期间编码，如 2026-10"
              value={periodCode}
              onChange={(e) => setPeriodCode(e.target.value)}
            />
            <Input
              placeholder="开始日 2026-10-01"
              value={periodStart}
              onChange={(e) => setPeriodStart(e.target.value)}
            />
            <Input
              placeholder="结束日 2026-10-31"
              value={periodEnd}
              onChange={(e) => setPeriodEnd(e.target.value)}
            />
            <Input
              placeholder="本位币 USD"
              value={periodCurrency}
              onChange={(e) => setPeriodCurrency(e.target.value)}
            />
            <Button variant="secondary" onClick={() => void runCreatePeriod()}>
              登记期间
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---------------- 2. 业务事件映射 ---------------- */}
      <SectionCard
        title="业务事件映射"
        description="业务事件 → 借贷科目：自动生成凭证要借哪个科目、贷哪个科目"
      >
        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">
            会计科目体系是<b>租户自定</b>的，系统<b>不预置任何一行映射</b>，也绝不内置科目常量。
            没有登记映射 ⇒ 自动生成生成不出凭证（服务端会如实报错）。
          </p>
          {mappings.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">业务事件</th>
                    <th className="py-2 pr-3">细分</th>
                    <th className="py-2 pr-3">借方科目</th>
                    <th className="py-2 pr-3">贷方科目</th>
                    <th className="py-2 pr-3">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {mappings.map((mapping) => (
                    <tr key={mapping.id} className="border-b last:border-0">
                      <td className="py-2 pr-3">{voucherSourceTypeLabel(mapping.sourceType)}</td>
                      <td className="py-2 pr-3 font-mono text-xs">{mapping.eventKind}</td>
                      <td className="py-2 pr-3">
                        <span className="font-mono text-xs">{mapping.debitSubjectCode}</span>
                        <span className="ml-2 text-muted-foreground">{mapping.debitSubjectName}</span>
                      </td>
                      <td className="py-2 pr-3">
                        <span className="font-mono text-xs">{mapping.creditSubjectCode}</span>
                        <span className="ml-2 text-muted-foreground">{mapping.creditSubjectName}</span>
                      </td>
                      <td className="py-2 pr-3">
                        {mapping.enabled ? (
                          <Badge tone="success">启用</Badge>
                        ) : (
                          <Badge tone="neutral">停用</Badge>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <EmptyState
              title="还没有登记任何映射"
              description="自动生成凭证需要先登记借贷科目"
            />
          )}

          <div className="grid gap-2 sm:grid-cols-6">
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={mapSourceType}
              onChange={(e) => {
                setMapSourceType(e.target.value);
                const kinds =
                  SOURCE_TYPES.find((t) => t.key === e.target.value)?.kinds ?? [];
                setMapEventKind(kinds[0]?.key ?? '');
              }}
            >
              {SOURCE_TYPES.map((type) => (
                <option key={type.key} value={type.key}>
                  {type.label}
                </option>
              ))}
            </select>
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={mapEventKind}
              onChange={(e) => setMapEventKind(e.target.value)}
            >
              {currentMapKinds.map((kind) => (
                <option key={kind.key} value={kind.key}>
                  {kind.label}
                </option>
              ))}
            </select>
            <Input
              placeholder="借方科目编码"
              value={mapDebitCode}
              onChange={(e) => setMapDebitCode(e.target.value)}
            />
            <Input
              placeholder="借方科目名称"
              value={mapDebitName}
              onChange={(e) => setMapDebitName(e.target.value)}
            />
            <Input
              placeholder="贷方科目编码"
              value={mapCreditCode}
              onChange={(e) => setMapCreditCode(e.target.value)}
            />
            <Input
              placeholder="贷方科目名称"
              value={mapCreditName}
              onChange={(e) => setMapCreditName(e.target.value)}
            />
          </div>
          <Button variant="secondary" onClick={() => void runCreateMapping()}>
            登记映射
          </Button>
        </div>
      </SectionCard>

      {/* ---------------- 3. 凭证列表 ---------------- */}
      <SectionCard title="凭证" description="真分页 + 真筛选 + 按币种分开的汇总">
        <div className="space-y-4">
          <div className="grid gap-2 sm:grid-cols-4">
            <Input
              placeholder="按凭证号 / 来源单号 / 摘要搜索"
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setCurrentPage(0);
              }}
            />
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setCurrentPage(0);
              }}
            >
              <option value="">全部状态</option>
              {(options?.statuses ?? []).map((value) => (
                <option key={value} value={value}>
                  {voucherStatusLabel(value)}
                </option>
              ))}
            </select>
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={currency}
              onChange={(e) => {
                setCurrency(e.target.value);
                setCurrentPage(0);
              }}
            >
              <option value="">全部币种</option>
              {(options?.currencies ?? []).map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={periodFilter}
              onChange={(e) => {
                setPeriodFilter(e.target.value);
                setCurrentPage(0);
              }}
            >
              <option value="">全部期间</option>
              {(options?.periods ?? []).map((period) => (
                <option key={period.id} value={period.id}>
                  {period.name}
                </option>
              ))}
            </select>
          </div>

          {page ? (
            <div className="grid gap-3 sm:grid-cols-4">
              <StatCard label="凭证张数" value={page.total} />
              <StatCard label="草稿" value={page.draftCount} />
              <StatCard label="已过账" value={page.postedCount} />
              <StatCard
                label="币种"
                value={page.summaryByCurrency.map((s) => s.currency).join(' / ') || '—'}
              />
            </div>
          ) : null}

          {page && page.summaryByCurrency.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">币种</th>
                    <th className="py-2 pr-3">借方合计</th>
                    <th className="py-2 pr-3">贷方合计</th>
                    <th className="py-2 pr-3">张数</th>
                    <th className="py-2 pr-3">草稿</th>
                    <th className="py-2 pr-3">已过账</th>
                    <th className="py-2 pr-3">已红冲</th>
                  </tr>
                </thead>
                <tbody>
                  {page.summaryByCurrency.map((summary) => (
                    <tr key={summary.currency} className="border-b last:border-0">
                      <td className="py-2 pr-3 font-medium">{summary.currency}</td>
                      <td className="py-2 pr-3 font-mono text-xs">
                        {formatVoucherAmount(summary.totalDebit)}
                      </td>
                      <td className="py-2 pr-3 font-mono text-xs">
                        {formatVoucherAmount(summary.totalCredit)}
                      </td>
                      <td className="py-2 pr-3">{summary.count}</td>
                      <td className="py-2 pr-3">{summary.draftCount}</td>
                      <td className="py-2 pr-3">{summary.postedCount}</td>
                      <td className="py-2 pr-3">{summary.reversedCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}

          {rows.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">凭证号</th>
                    <th className="py-2 pr-3">期间</th>
                    <th className="py-2 pr-3">业务日期</th>
                    <th className="py-2 pr-3">摘要</th>
                    <th className="py-2 pr-3">来源</th>
                    <th className="py-2 pr-3">借方合计</th>
                    <th className="py-2 pr-3">贷方合计</th>
                    <th className="py-2 pr-3">行数</th>
                    <th className="py-2 pr-3">状态</th>
                    <th className="py-2 pr-3" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id} className="border-b last:border-0">
                      <td className="py-2 pr-3 font-mono text-xs">{row.voucherNo}</td>
                      <td className="py-2 pr-3">{row.periodCode}</td>
                      <td className="py-2 pr-3">{row.voucherDate}</td>
                      <td className="py-2 pr-3">{row.description}</td>
                      <td className="py-2 pr-3">
                        {voucherSourceTypeLabel(row.sourceType)}
                        {row.sourceNo ? (
                          <span className="ml-1 font-mono text-xs text-muted-foreground">
                            {row.sourceNo}
                          </span>
                        ) : null}
                      </td>
                      <td className="py-2 pr-3 font-mono text-xs">
                        {formatVoucherMoney(row.totalDebit, row.currency)}
                      </td>
                      <td className="py-2 pr-3 font-mono text-xs">
                        {formatVoucherMoney(row.totalCredit, row.currency)}
                      </td>
                      <td className="py-2 pr-3">{row.lineCount}</td>
                      <td className="py-2 pr-3">
                        <Badge tone={voucherStatusTone(row.status)}>
                          {voucherStatusLabel(row.status)}
                        </Badge>
                      </td>
                      <td className="py-2 pr-3 text-right">
                        <Button variant="ghost" onClick={() => void openDetail(row.id)}>
                          详情
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <EmptyState
              title={
                keyword || status || currency || periodFilter
                  ? '没有符合条件的凭证'
                  : '还没有凭证'
              }
              description={
                keyword || status || currency || periodFilter
                  ? '换个筛选条件试试'
                  : '从下面手工录入一张，或由业务事件自动生成'
              }
            />
          )}

          {page && page.total > page.size ? (
            <div className="flex items-center justify-between text-sm">
              <span className="text-muted-foreground">
                第 {page.page + 1} / {Math.max(1, Math.ceil(page.total / page.size))} 页 · 共 {page.total} 张
              </span>
              <div className="flex gap-2">
                <Button
                  variant="ghost"
                  disabled={page.page <= 0}
                  onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="ghost"
                  disabled={page.page + 1 >= Math.ceil(page.total / page.size)}
                  onClick={() => setCurrentPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          ) : null}
        </div>
      </SectionCard>

      {/* ---------------- 4. 手工录入 ---------------- */}
      <SectionCard title="手工录入凭证" description="录一张草稿：至少两行，借贷必须相等才能过账">
        <div className="space-y-4">
          <div className="grid gap-2 sm:grid-cols-4">
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={draftPeriodId}
              onChange={(e) => setDraftPeriodId(e.target.value)}
            >
              <option value="">选择会计期间</option>
              {openPeriods.map((period) => (
                <option key={period.id} value={period.id}>
                  {period.periodCode}（{period.baseCurrency}）
                </option>
              ))}
            </select>
            <Input
              placeholder="业务日期 2026-10-20"
              value={draftDate}
              onChange={(e) => setDraftDate(e.target.value)}
            />
            <Input
              placeholder="币种 USD"
              value={draftCurrency}
              onChange={(e) => setDraftCurrency(e.target.value)}
            />
            <Input
              placeholder="凭证摘要"
              value={draftDescription}
              onChange={(e) => setDraftDescription(e.target.value)}
            />
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">方向</th>
                  <th className="py-2 pr-3">科目编码</th>
                  <th className="py-2 pr-3">科目名称</th>
                  <th className="py-2 pr-3">摘要</th>
                  <th className="py-2 pr-3">金额</th>
                  <th className="py-2 pr-3" />
                </tr>
              </thead>
              <tbody>
                {draftLines.map((line, index) => (
                  <tr key={index} className="border-b last:border-0">
                    <td className="py-2 pr-3">
                      <select
                        className="h-9 rounded-md border border-input bg-background px-2 text-sm"
                        value={line.lineType}
                        onChange={(e) =>
                          setDraftLines((lines) =>
                            lines.map((l, i) =>
                              i === index
                                ? { ...l, lineType: e.target.value as 'DEBIT' | 'CREDIT' }
                                : l,
                            ),
                          )
                        }
                      >
                        <option value="DEBIT">借</option>
                        <option value="CREDIT">贷</option>
                      </select>
                    </td>
                    <td className="py-2 pr-3">
                      <Input
                        placeholder="科目编码"
                        value={line.subjectCode}
                        onChange={(e) =>
                          setDraftLines((lines) =>
                            lines.map((l, i) =>
                              i === index ? { ...l, subjectCode: e.target.value } : l,
                            ),
                          )
                        }
                      />
                    </td>
                    <td className="py-2 pr-3">
                      <Input
                        placeholder="科目名称"
                        value={line.subjectName}
                        onChange={(e) =>
                          setDraftLines((lines) =>
                            lines.map((l, i) =>
                              i === index ? { ...l, subjectName: e.target.value } : l,
                            ),
                          )
                        }
                      />
                    </td>
                    <td className="py-2 pr-3">
                      <Input
                        placeholder="行摘要"
                        value={line.summary}
                        onChange={(e) =>
                          setDraftLines((lines) =>
                            lines.map((l, i) => (i === index ? { ...l, summary: e.target.value } : l)),
                          )
                        }
                      />
                    </td>
                    <td className="py-2 pr-3">
                      <Input
                        placeholder="金额"
                        value={line.amount}
                        onChange={(e) =>
                          setDraftLines((lines) =>
                            lines.map((l, i) => (i === index ? { ...l, amount: e.target.value } : l)),
                          )
                        }
                      />
                    </td>
                    <td className="py-2 pr-3 text-right">
                      <Button
                        variant="ghost"
                        onClick={() =>
                          setDraftLines((lines) => lines.filter((_, i) => i !== index))
                        }
                      >
                        删行
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex gap-2">
            <Button
              variant="ghost"
              onClick={() =>
                setDraftLines((lines) => [
                  ...lines,
                  { lineType: 'DEBIT', subjectCode: '', subjectName: '', summary: '', amount: '' },
                ])
              }
            >
              加一行
            </Button>
            <Button variant="secondary" onClick={() => void submitDraft()}>
              保存为草稿
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---------------- 5. 自动生成 ---------------- */}
      <SectionCard title="由业务事件自动生成" description="发货 / 收货 / 收付款 / 费用计提 → 草稿凭证">
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">
            金额、币种、来源单号都取自<b>真实业务事实</b>；借贷科目来自上面登记的映射。
            生成的是<b>草稿</b>，仍要过账才进账。
          </p>
          <div className="grid gap-2 sm:grid-cols-4">
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={genSourceType}
              onChange={(e) => {
                setGenSourceType(e.target.value);
                const kinds = SOURCE_TYPES.find((t) => t.key === e.target.value)?.kinds ?? [];
                setGenEventKind(kinds[0]?.key ?? '');
              }}
            >
              {SOURCE_TYPES.map((type) => (
                <option key={type.key} value={type.key}>
                  {type.label}
                </option>
              ))}
            </select>
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={genEventKind}
              onChange={(e) => setGenEventKind(e.target.value)}
            >
              {currentKinds.map((kind) => (
                <option key={kind.key} value={kind.key}>
                  {kind.label}
                </option>
              ))}
            </select>
            <Input
              placeholder="业务事实 id"
              value={genSourceId}
              onChange={(e) => setGenSourceId(e.target.value)}
            />
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={genPeriodId}
              onChange={(e) => setGenPeriodId(e.target.value)}
            >
              <option value="">选择会计期间</option>
              {openPeriods.map((period) => (
                <option key={period.id} value={period.id}>
                  {period.periodCode}（{period.baseCurrency}）
                </option>
              ))}
            </select>
          </div>
          <Button variant="secondary" onClick={() => void runGenerate()}>
            生成草稿凭证
          </Button>
        </div>
      </SectionCard>

      {/* ---------------- 6. 导出 ---------------- */}
      <SectionCard title="导出标准凭证文件" description="只导已过账的凭证（草稿不是账）">
        <div className="space-y-3">
          <div className="grid gap-2 sm:grid-cols-3">
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={exportFormat}
              onChange={(e) => setExportFormat(e.target.value)}
            >
              <option value="CSV">CSV</option>
              <option value="JSON">JSON</option>
            </select>
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={exportPeriodId}
              onChange={(e) => setExportPeriodId(e.target.value)}
            >
              <option value="">全部期间</option>
              {periods.map((period) => (
                <option key={period.id} value={period.id}>
                  {period.periodCode}
                </option>
              ))}
            </select>
            <Button variant="secondary" onClick={() => void runExport()}>
              <Download className="size-4" aria-hidden />
              导出
            </Button>
          </div>
          {exportContent ? (
            <pre className="max-h-96 overflow-auto rounded-md border bg-muted/40 p-3 text-xs">
              {exportContent}
            </pre>
          ) : null}
        </div>
      </SectionCard>

      {/* ---------------- 7. 凭证详情 ---------------- */}
      {detail && voucher ? (
        <SectionCard title={`凭证详情 · ${voucher.voucherNo}`} description="分录行 + 审计留痕">
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={voucherStatusTone(voucher.status)}>
                {voucherStatusLabel(voucher.status)}
              </Badge>
              <span className="text-sm text-muted-foreground">
                {voucher.periodCode} · {voucher.voucherDate} · {voucher.description}
              </span>
              <span className="font-mono text-sm">
                {formatVoucherMoney(voucher.totalDebit, voucher.currency)}
              </span>
              {voucher.reversalVoucherId ? (
                <span className="text-xs text-muted-foreground">
                  已被一张红冲凭证冲掉（原凭证的原始事实一字未改）
                </span>
              ) : null}
            </div>

            {detail.lines.length ? (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left text-xs text-muted-foreground">
                      <th className="py-2 pr-3">行</th>
                      <th className="py-2 pr-3">方向</th>
                      <th className="py-2 pr-3">科目编码</th>
                      <th className="py-2 pr-3">科目名称</th>
                      <th className="py-2 pr-3">摘要</th>
                      <th className="py-2 pr-3">金额</th>
                      <th className="py-2 pr-3">数量</th>
                      <th className="py-2 pr-3">辅助核算</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.lines.map((line: FinanceVoucherLineRow) => (
                      <tr key={line.id} className="border-b last:border-0">
                        <td className="py-2 pr-3">{line.lineNo}</td>
                        <td className="py-2 pr-3">{voucherLineTypeLabel(line.lineType)}</td>
                        <td className="py-2 pr-3 font-mono text-xs">{line.subjectCode}</td>
                        <td className="py-2 pr-3">{line.subjectName}</td>
                        <td className="py-2 pr-3">{line.summary}</td>
                        <td className="py-2 pr-3 font-mono text-xs">
                          {formatVoucherMoney(line.amount, line.currency)}
                        </td>
                        <td className="py-2 pr-3 font-mono text-xs">
                          {formatVoucherQuantity(line.quantity)}
                        </td>
                        <td className="py-2 pr-3 text-xs text-muted-foreground">
                          {line.auxiliaryName || '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <EmptyState title="这张凭证还没有分录行" description="草稿可以直接改，过账后就冻结了" />
            )}

            {/* 动作：草稿可过账 / 可作废；已过账只能红冲。 */}
            <div className="flex flex-wrap gap-2">
              {voucherEditable(voucher) ? (
                <>
                  <Button
                    variant="secondary"
                    onClick={() => void runPost(voucher.id, voucher.version)}
                  >
                    过账
                  </Button>
                  <Button
                    variant="ghost"
                    onClick={() => {
                      const reason = window.prompt('作废这张草稿的原因？');
                      if (reason) {
                        void runVoid(voucher.id, voucher.version, reason);
                      }
                    }}
                  >
                    作废
                  </Button>
                </>
              ) : null}
              {voucherReversible(voucher) ? (
                <Button
                  variant="destructive"
                  onClick={() => {
                    const reason = window.prompt('红冲这张凭证的原因？（必填）');
                    if (reason) {
                      void runReverse(voucher.id, voucher.version, reason);
                    }
                  }}
                >
                  红冲（生成反向凭证）
                </Button>
              ) : null}
              {!voucherEditable(voucher) && !voucherReversible(voucher) ? (
                <span className="text-sm text-muted-foreground">
                  这张凭证已是终态（{voucherStatusLabel(voucher.status)}），没有可执行的动作。
                </span>
              ) : null}
            </div>

            {detail.events.length ? (
              <div>
                <h3 className="mb-2 text-sm font-medium">审计留痕</h3>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left text-xs text-muted-foreground">
                        <th className="py-2 pr-3">事件</th>
                        <th className="py-2 pr-3">前 → 后</th>
                        <th className="py-2 pr-3">说明</th>
                        <th className="py-2 pr-3">时刻</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.events.map((event) => (
                        <tr key={event.id} className="border-b last:border-0">
                          <td className="py-2 pr-3">{voucherEventTypeLabel(event.eventType)}</td>
                          <td className="py-2 pr-3 text-xs">
                            {event.fromStatus || '—'} → {event.toStatus || '—'}
                          </td>
                          <td className="py-2 pr-3 text-xs">
                            {event.reason}
                            {event.detail ? ` · ${event.detail}` : ''}
                          </td>
                          <td className="py-2 pr-3 text-xs text-muted-foreground">
                            {event.occurredAt}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : null}
          </div>
        </SectionCard>
      ) : null}

      {caps ? (
        <p className="text-xs text-muted-foreground">
          能力声明：{caps.capabilities.length} 项已接通，
          {caps.downstreamGaps.length} 项上游缺口（12.5.1.1 会计科目、12.5.1.2 会计期间、
          12.5.2.2 财务报表）尚未交付。
        </p>
      ) : null}
    </div>
  );
}
