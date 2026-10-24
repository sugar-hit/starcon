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
  FinanceFilingCapabilities,
  FinanceFilingDraftDetail,
  FinanceFilingDraftPage,
  FinanceFilingExportPage,
  FinanceFilingOptions,
  FinanceFilingSource,
} from '../../../../../packages/api-contracts/src/index';
import {
  describeCoverage,
  draftStatusLabel,
  draftTitle,
  downloadFilingExport,
  fetchFilingCapabilities,
  fetchFilingDraft,
  fetchFilingDrafts,
  fetchFilingExports,
  fetchFilingOptions,
  filingTaxTypeLabel,
  formatAmount,
  formatCount,
  formatEffectiveWindow,
  formatExchangeRate,
  formatMoment,
  formatPeriod,
  formatRate,
  generateFilingDraft,
  unavailableSources,
} from './filing-draft-model';

/**
 * 12.6.1.2 VAT/IOSS 申报数据。
 *
 * <p>蓝图 12-finance.md：「按辖区与周期汇总申报所需数据： taxable 销售额、已收 VAT、IOSS 订单与
 * 代收金额、可抵扣进项，导出申报文件或对接申报接口。」输出：「申报底稿 + 汇总报表 + 可导出文件。」
 * 关系：「<b>本系统提供申报底稿，不替代税务申报动作</b>——申报主体与责任在用户，
 * 系统保证数据完整可核。」
 *
 * <p><b>页面围绕四件事组织</b>：
 *
 * <ol>
 *   <li><b>取数</b>（第一块）：选辖区 + 申报线 + 期间 + 周期，点「取数生成底稿」。
 *       辖区来自后端实时读到的<b>受控码表</b>（不是自由文本）；税率走 12.6.1.1 的
 *       「按生效时间取税率」，<b>命中不到就报错</b>。</li>
 *   <li><b>底稿与汇总</b>（第二块）：底稿列表 + 选中底稿的汇总表。
 *       汇总<b>按币种分组</b>，页面上<b>没有</b>跨币种总额。</li>
 *   <li><b>数据来源可用性</b>（第三块）：逐条说清「读到了 / 读不到」，
 *       读不到时把原因原样显示 —— <b>不是</b>「没有数据」。</li>
 *   <li><b>导出</b>（第四块）：导出 CSV / JSON <b>真文件</b>，并列出导出留痕
 *       （行数 / 字节数 / 内容哈希 / 导出时刻）。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没取过数」。
 */
export function FilingDraftWorkspace() {
  const [caps, setCaps] = useState<FinanceFilingCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceFilingOptions | null>(null);
  const [drafts, setDrafts] = useState<FinanceFilingDraftPage | null>(null);
  const [exports, setExports] = useState<FinanceFilingExportPage | null>(null);
  const [selected, setSelected] = useState<FinanceFilingDraftDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  // 筛选
  const [jurisdictionFilter, setJurisdictionFilter] = useState('');
  const [taxTypeFilter, setTaxTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [keyword, setKeyword] = useState('');

  // 取数表单
  const [formJurisdiction, setFormJurisdiction] = useState('');
  const [formTaxType, setFormTaxType] = useState('VAT');
  const [formTaxCode, setFormTaxCode] = useState('STANDARD');
  const [formPeriodStart, setFormPeriodStart] = useState('');
  const [formPeriodEnd, setFormPeriodEnd] = useState('');
  const [formCycle, setFormCycle] = useState('MONTHLY');
  const [formBaseCurrency, setFormBaseCurrency] = useState('');
  const [formOrderSource, setFormOrderSource] = useState('');
  const [formNote, setFormNote] = useState('');

  /** 辖区选项（实时受控码表；美国辖区在本模块不可用，页面写明原因）。 */
  const jurisdictions = useMemo(() => options?.jurisdictions?.items ?? [], [options]);

  /** 可用于出申报底稿的辖区（🔴 美国州销售税归 channel 16.3.2.3）。 */
  const usableJurisdictions = useMemo(
    () => jurisdictions.filter((j) => j.usableForTaxRate),
    [jurisdictions],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, d, e] = await Promise.all([
        fetchFilingCapabilities(),
        fetchFilingOptions(),
        fetchFilingDrafts({
          jurisdictionCode: jurisdictionFilter || undefined,
          taxType: taxTypeFilter || undefined,
          status: statusFilter || undefined,
          keyword: keyword || undefined,
        }),
        fetchFilingExports(),
      ]);
      setCaps(c);
      setOptions(o);
      setDrafts(d);
      setExports(e);
    } catch (err) {
      setError(err instanceof Error ? err.message : '申报底稿加载失败');
    } finally {
      setLoading(false);
    }
  }, [jurisdictionFilter, taxTypeFilter, statusFilter, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDraft = useCallback(async (id: string) => {
    try {
      setSelected(await fetchFilingDraft(id));
      setActionError('');
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '底稿详情读取失败');
    }
  }, []);

  const submit = useCallback(async () => {
    if (!formJurisdiction) {
      setActionError('必须选择辖区（目的国/地区）：辖区码不是自由文本');
      return;
    }
    if (!formTaxCode.trim()) {
      setActionError('必须给出税码：不同税档是不同税率，取数时不能替你猜一个');
      return;
    }
    if (!formPeriodStart || !formPeriodEnd) {
      setActionError('必须给出覆盖期间的起止日：没说清期间的申报底稿没法拿去申报');
      return;
    }
    setBusy(true);
    setActionError('');
    setNotice('');
    try {
      const detail = await generateFilingDraft({
        jurisdictionCode: formJurisdiction,
        taxType: formTaxType,
        taxCode: formTaxCode.trim(),
        periodStart: formPeriodStart,
        periodEnd: formPeriodEnd,
        filingCycle: formCycle,
        baseCurrency: formBaseCurrency.trim() ? formBaseCurrency.trim().toUpperCase() : null,
        orderSource: formOrderSource.trim() ? formOrderSource.trim().toUpperCase() : null,
        note: formNote.trim() ? formNote.trim() : null,
      });
      setSelected(detail);
      setNotice(
        `已生成 ${draftTitle(detail.draft)} 的申报底稿（${detail.lines.length} 个币种分组）。` +
          '🔴 本系统提供**底稿**，不替代税务申报动作：申报主体与责任在用户，请自行核对后申报。',
      );
      await load();
    } catch (err) {
      // 🔴 命中不到税率 / 辖区不存在 / 上游读不到 —— 原样显示服务端的话。
      setActionError(err instanceof Error ? err.message : '取数失败');
    } finally {
      setBusy(false);
    }
  }, [
    formJurisdiction,
    formTaxType,
    formTaxCode,
    formPeriodStart,
    formPeriodEnd,
    formCycle,
    formBaseCurrency,
    formOrderSource,
    formNote,
    load,
  ]);

  const runExport = useCallback(
    async (id: string, format: 'CSV' | 'JSON') => {
      setActionError('');
      try {
        await downloadFilingExport(id, format);
        setNotice(`已导出 ${format} 文件（服务端已记一条导出留痕）。`);
        setExports(await fetchFilingExports());
      } catch (err) {
        setActionError(err instanceof Error ? err.message : '导出失败');
      }
    },
    [],
  );

  if (loading && !caps) {
    return (
      <div className="p-6">
        <p className="text-copy-13 text-muted-foreground">正在加载申报底稿…</p>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <div className="p-6">
        <Notice tone="danger" title="申报底稿加载失败">
          {error}
          <Button className="ml-3" onClick={() => void load()}>
            重试
          </Button>
        </Notice>
      </div>
    );
  }

  const missing = selected ? unavailableSources(selected.sources) : [];

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="VAT/IOSS 申报底稿"
        description="按辖区与周期汇总申报所需数据：应税销售额、已收 VAT、IOSS 订单与代收金额、可抵扣进项，产出申报底稿、汇总报表与可导出文件。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {/* 🔴 产品边界：本页不做「向税务机构提交申报」这个动作。 */}
      <Notice tone="info" title="本系统提供申报底稿，不替代税务申报动作">
        {caps?.productBoundary ??
          '本模块产出底稿 + 汇总报表 + 可导出文件，不向税务机构提交申报，也不产生任何「已申报 / 已受理」的结论。'}
        <br />
        {selected?.currencyNote ??
          '🔴 金额按币种分组呈现：不同币种绝不相加。折算只是附加信息，汇率不可得时不折算（绝不按 1:1 兜底）。'}
      </Notice>

      <div className="grid gap-3 md:grid-cols-4">
        <StatCard label="申报底稿总数" value={drafts?.total ?? 0} />
        <StatCard label="生效中的底稿" value={drafts?.liveCount ?? 0} />
        <StatCard label="可出底稿的辖区" value={usableJurisdictions.length} />
        <StatCard label="导出留痕" value={exports?.total ?? 0} />
      </div>

      {actionError ? (
        <Notice tone="danger" title="操作失败">
          <TriangleAlert className="mr-2 inline h-4 w-4" />
          {actionError}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {/* 1. 取数 */}
      <SectionCard
        title="取数生成底稿"
        description="辖区来自受控码表（不是自由文本）；税率按生效时间取（命中不到会直接报错）；订单税务数据实时从订单侧读，读不到时如实报读不到。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <label className="text-copy-12">
            <span className="text-muted-foreground">辖区（目的国/地区）</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={formJurisdiction}
              onChange={(e) => setFormJurisdiction(e.target.value)}
              aria-label="辖区"
            >
              <option value="">请选择辖区</option>
              {usableJurisdictions.map((j) => (
                <option key={j.code} value={j.code}>
                  {j.name || j.code}（{j.code}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">申报线</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={formTaxType}
              onChange={(e) => setFormTaxType(e.target.value)}
              aria-label="申报线"
            >
              {(options?.taxTypes ?? []).map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">税码</span>
            <Input
              className="mt-1"
              value={formTaxCode}
              onChange={(e) => setFormTaxCode(e.target.value)}
              placeholder="例如 STANDARD"
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">覆盖期间起（含）</span>
            <Input
              className="mt-1"
              type="date"
              value={formPeriodStart}
              onChange={(e) => setFormPeriodStart(e.target.value)}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">覆盖期间止（含）</span>
            <Input
              className="mt-1"
              type="date"
              value={formPeriodEnd}
              onChange={(e) => setFormPeriodEnd(e.target.value)}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">申报周期</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={formCycle}
              onChange={(e) => setFormCycle(e.target.value)}
              aria-label="申报周期"
            >
              {(options?.filingCycles ?? []).map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">折算目标币种（可空 = 不折算）</span>
            <Input
              className="mt-1"
              value={formBaseCurrency}
              onChange={(e) => setFormBaseCurrency(e.target.value)}
              placeholder="例如 EUR"
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">订单来源过滤（可空 = 全部）</span>
            <Input
              className="mt-1"
              value={formOrderSource}
              onChange={(e) => setFormOrderSource(e.target.value)}
              placeholder="例如 PLATFORM"
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">备注</span>
            <Input
              className="mt-1"
              value={formNote}
              onChange={(e) => setFormNote(e.target.value)}
              placeholder="可选"
            />
          </label>
        </div>
        <div className="mt-4 flex items-center gap-3">
          <Button onClick={() => void submit()} disabled={busy}>
            {busy ? '取数中…' : '取数生成底稿'}
          </Button>
          <span className="text-copy-12 text-muted-foreground">
            重新取数会把同一（辖区 × 申报线 × 期间 × 周期）的旧底稿置为「已被取代」，历史版本仍然查得到、导得出。
          </span>
        </div>
      </SectionCard>

      {/* 2. 底稿列表 */}
      <SectionCard
        title="申报底稿"
        description="底稿是不可改写的快照：辖区、期间、口径与覆盖情况在生成时定下。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={jurisdictionFilter}
              onChange={(e) => setJurisdictionFilter(e.target.value)}
              aria-label="按辖区筛选"
            >
              <option value="">全部辖区</option>
              {jurisdictions.map((j) => (
                <option key={j.code} value={j.code}>
                  {j.name || j.code}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={taxTypeFilter}
              onChange={(e) => setTaxTypeFilter(e.target.value)}
              aria-label="按申报线筛选"
            >
              <option value="">全部申报线</option>
              {(options?.taxTypes ?? []).map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              aria-label="按状态筛选"
            >
              <option value="">全部状态</option>
              {(options?.statuses ?? []).map((status) => (
                <option key={status} value={status}>
                  {draftStatusLabel(status)}
                </option>
              ))}
            </select>
            <Input
              className="h-9 w-48"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="搜索辖区 / 备注 / 口径"
              aria-label="搜索底稿"
            />
          </div>
        }
      >
        {!drafts || drafts.items.length === 0 ? (
          <EmptyState
            title={keyword || jurisdictionFilter || taxTypeFilter ? '没有匹配的底稿' : '还没取过数'}
            description={
              keyword || jurisdictionFilter || taxTypeFilter
                ? '换个筛选条件再试。'
                : '在上方选好辖区、申报线与覆盖期间，点「取数生成底稿」生成第一份底稿。'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3">辖区</th>
                  <th className="py-2 pr-3">申报线</th>
                  <th className="py-2 pr-3">覆盖期间</th>
                  <th className="py-2 pr-3">周期</th>
                  <th className="py-2 pr-3">状态</th>
                  <th className="py-2 pr-3">订单覆盖</th>
                  <th className="py-2 pr-3">生成时间</th>
                  <th className="py-2 pr-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {drafts.items.map((draft) => (
                  <tr key={draft.id} className="border-t">
                    <td className="py-2 pr-3">
                      {draft.jurisdictionName || draft.jurisdictionCode}
                      <span className="text-copy-12 text-muted-foreground ml-1">
                        {draft.jurisdictionCode}
                      </span>
                    </td>
                    <td className="py-2 pr-3">{filingTaxTypeLabel(draft.taxType)}</td>
                    <td className="py-2 pr-3">
                      {formatPeriod(draft.periodStart, draft.periodEnd)}
                    </td>
                    <td className="py-2 pr-3">{draft.filingCycleLabel}</td>
                    <td className="py-2 pr-3">
                      <Badge tone={draft.status === 'GENERATED' ? 'success' : 'neutral'}>
                        {draftStatusLabel(draft.status)}
                      </Badge>
                    </td>
                    <td className="py-2 pr-3 text-copy-12">
                      候选 {draft.orderCount} / 纳入 {draft.includedCount}
                      {draft.orderTruncated ? (
                        <span className="ml-1 text-destructive">🔴 被截断</span>
                      ) : null}
                      {draft.unmappedCount > 0 ? (
                        <span className="ml-1 text-muted-foreground">
                          归属不到 {draft.unmappedCount}
                        </span>
                      ) : null}
                    </td>
                    <td className="py-2 pr-3 text-copy-12">{formatMoment(draft.generatedAt)}</td>
                    <td className="py-2 pr-3">
                      <Button variant="ghost" onClick={() => void openDraft(draft.id)}>
                        查看
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 3. 底稿详情 + 汇总（按币种分组） */}
      {selected ? (
        <SectionCard
          title="底稿详情与汇总报表"
          description="汇总按币种分组；不同币种的金额绝不相加。"
          extra={
            <div className="flex items-center gap-2">
              <Button variant="outline" onClick={() => void runExport(selected.draft.id, 'CSV')}>
                <Download className="mr-2 h-4 w-4" />
                导出 CSV
              </Button>
              <Button variant="outline" onClick={() => void runExport(selected.draft.id, 'JSON')}>
                <Download className="mr-2 h-4 w-4" />
                导出 JSON
              </Button>
            </div>
          }
        >
          <div className="space-y-3">
            <div className="text-copy-13">
              <span className="text-muted-foreground">辖区：</span>
              {selected.draft.jurisdictionName || selected.draft.jurisdictionCode}（
              {selected.draft.jurisdictionCode}）
              <span className="text-muted-foreground ml-4">申报线：</span>
              {filingTaxTypeLabel(selected.draft.taxType)}
              <span className="text-muted-foreground ml-4">覆盖期间：</span>
              {formatPeriod(selected.draft.periodStart, selected.draft.periodEnd)}
              <span className="text-muted-foreground ml-4">生成时间：</span>
              {formatMoment(selected.draft.generatedAt)}
            </div>
            <div className="text-copy-12 text-muted-foreground">
              {describeCoverage(selected.draft)}
            </div>
            <div className="rounded-md border p-3 text-copy-12">
              <span className="text-muted-foreground">取数口径：</span>
              {selected.draft.generationBasis}
            </div>
          </div>

          {selected.lines.length === 0 ? (
            <EmptyState
              title="这份底稿没有任何币种分组"
              description="覆盖期间内没有归属到该辖区的订单（或订单税务数据读不到）。先看上面的「订单覆盖」与「取数口径」。"
            />
          ) : (
            <div className="mt-4 overflow-x-auto">
              <table className="text-copy-13 w-full text-left">
                <thead className="text-copy-12 text-muted-foreground">
                  <tr>
                    <th className="py-2 pr-3">币种</th>
                    <th className="py-2 pr-3">应税销售额</th>
                    <th className="py-2 pr-3">已收 VAT</th>
                    <th className="py-2 pr-3">IOSS 订单数</th>
                    <th className="py-2 pr-3">IOSS 代收</th>
                    <th className="py-2 pr-3">可抵扣进项</th>
                    <th className="py-2 pr-3">应缴</th>
                    <th className="py-2 pr-3">汇率</th>
                    <th className="py-2 pr-3">折算应缴</th>
                    <th className="py-2 pr-3">期间实际到账</th>
                    <th className="py-2 pr-3">订单数</th>
                  </tr>
                </thead>
                <tbody>
                  {selected.lines.map((line) => (
                    <tr key={line.id} className="border-t">
                      <td className="py-2 pr-3 font-medium">{line.currency}</td>
                      <td className="py-2 pr-3">{formatAmount(line.taxableSales)}</td>
                      <td className="py-2 pr-3">{formatAmount(line.vatCollected)}</td>
                      <td className="py-2 pr-3">{formatCount(line.iossOrderCount)}</td>
                      <td className="py-2 pr-3">{formatAmount(line.iossCollected)}</td>
                      <td className="py-2 pr-3">
                        {line.deductibleAvailable ? (
                          formatAmount(line.deductibleInput)
                        ) : (
                          <span className="text-muted-foreground" title={line.deductibleNote}>
                            读不到
                          </span>
                        )}
                      </td>
                      <td className="py-2 pr-3">{formatAmount(line.netPayable)}</td>
                      <td className="py-2 pr-3 text-copy-12">
                        {line.exchangeRateAvailable ? formatExchangeRate(line.exchangeRate) : '—'}
                        {line.exchangeRateAsOf ? (
                          <span className="text-muted-foreground ml-1">
                            @{line.exchangeRateAsOf}
                          </span>
                        ) : null}
                      </td>
                      <td className="py-2 pr-3">{formatAmount(line.convertedNetPayable)}</td>
                      <td className="py-2 pr-3">{formatAmount(line.receivedAmount)}</td>
                      <td className="py-2 pr-3">{formatCount(line.orderCount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* 税率版本（可核信息） */}
          {selected.lines[0] ? (
            <div className="mt-3 text-copy-12 text-muted-foreground">
              税率版本：税码 {selected.lines[0].taxCode} · 税率 {formatRate(selected.lines[0].taxRate)} ·{' '}
              {formatEffectiveWindow(
                selected.lines[0].taxRateEffectiveFrom,
                selected.lines[0].taxRateEffectiveTo,
              )}
            </div>
          ) : null}

          {/* 数据来源可用性 */}
          <div className="mt-4">
            <h3 className="text-copy-13 font-medium">数据来源</h3>
            {missing.length > 0 ? (
              <Notice tone="warning" title="有读不到的数据源">
                {missing.map((source: FinanceFilingSource) => (
                  <div key={source.key}>
                    🔴 {source.name}（{source.module}）：{source.detail}
                  </div>
                ))}
              </Notice>
            ) : null}
            <div className="mt-2 overflow-x-auto">
              <table className="text-copy-12 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-1 pr-3">来源</th>
                    <th className="py-1 pr-3">归属模块</th>
                    <th className="py-1 pr-3">可用</th>
                    <th className="py-1 pr-3">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {selected.sources.map((source) => (
                    <tr key={source.key} className="border-t">
                      <td className="py-1 pr-3">
                        {source.name}（{source.module}）
                      </td>
                      <td className="py-1 pr-3">{source.ownerService}</td>
                      <td className="py-1 pr-3">
                        {source.available ? (
                          <Badge tone="success">读到了</Badge>
                        ) : (
                          <Badge tone="warning">读不到</Badge>
                        )}
                      </td>
                      <td className="py-1 pr-3">{source.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </SectionCard>
      ) : null}

      {/* 4. 导出留痕 */}
      <SectionCard
        title="导出留痕"
        description="导出是取证动作：每次导出都记下行数、字节数与内容哈希（sha-256）。"
      >
        {!exports || exports.items.length === 0 ? (
          <EmptyState title="还没有导出过" description="在上方选中一份底稿后点「导出 CSV / JSON」，这里会留下导出记录。" />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3">文件</th>
                  <th className="py-2 pr-3">格式</th>
                  <th className="py-2 pr-3">底稿</th>
                  <th className="py-2 pr-3">行数</th>
                  <th className="py-2 pr-3">字节数</th>
                  <th className="py-2 pr-3">内容哈希</th>
                  <th className="py-2 pr-3">导出时刻</th>
                </tr>
              </thead>
              <tbody>
                {exports.items.map((item) => (
                  <tr key={item.id} className="border-t">
                    <td className="py-2 pr-3">{item.fileName}</td>
                    <td className="py-2 pr-3">{item.format}</td>
                    <td className="py-2 pr-3">
                      {item.draftJurisdictionCode} · {filingTaxTypeLabel(item.draftTaxType)} ·{' '}
                      {formatPeriod(item.draftPeriodStart, item.draftPeriodEnd)}
                    </td>
                    <td className="py-2 pr-3">{item.rowCount}</td>
                    <td className="py-2 pr-3">{item.byteSize}</td>
                    <td className="py-2 pr-3 text-copy-12 font-mono">{item.contentHash.slice(0, 16)}…</td>
                    <td className="py-2 pr-3 text-copy-12">{formatMoment(item.exportedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 5. 能力声明 */}
      {caps ? (
        <SectionCard
          title="能力声明"
          description="哪些上游真接通、哪些读不到、职责怎么切、哪些还没交付、归谁。"
        >
          <div className="grid gap-3 md:grid-cols-2">
            {caps.capabilities.map((capability) => (
              <div key={`${capability.module}-${capability.name}`} className="rounded-md border p-3">
                <div className="flex items-center justify-between">
                  <span className="text-copy-13 font-medium">{capability.name}</span>
                  <Badge tone={capability.available ? 'success' : 'warning'}>
                    {capability.available ? '已接通' : '未接通'}
                  </Badge>
                </div>
                <p className="text-copy-12 text-muted-foreground mt-1">依据：{capability.basis}</p>
                <p className="text-copy-12 mt-2">{capability.detail}</p>
              </div>
            ))}
          </div>
          <h3 className="text-copy-13 mt-4 font-medium">缺口与归属</h3>
          <div className="mt-2 overflow-x-auto">
            <table className="text-copy-12 w-full text-left">
              <thead className="text-muted-foreground">
                <tr>
                  <th className="py-1 pr-3">模块</th>
                  <th className="py-1 pr-3">名称</th>
                  <th className="py-1 pr-3">归属服务</th>
                  <th className="py-1 pr-3">说明</th>
                </tr>
              </thead>
              <tbody>
                {caps.gaps.map((gap) => (
                  <tr key={`${gap.module}-${gap.name}`} className="border-t">
                    <td className="py-1 pr-3">{gap.module}</td>
                    <td className="py-1 pr-3">{gap.name}</td>
                    <td className="py-1 pr-3">{gap.ownerService}</td>
                    <td className="py-1 pr-3">{gap.detail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      ) : null}
    </div>
  );
}
