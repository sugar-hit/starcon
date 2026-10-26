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
  FinanceCostCalculationCapabilities,
  FinanceCostCalculationEntryRow,
  FinanceCostCalculationLayerRow,
  FinanceCostCalculationOptions,
  FinanceCostCalculationRunDetail,
  FinanceCostCalculationRunPage,
  FinanceCostCalculationRunRow,
  FinanceCostCalculationVarianceComparison,
} from '../../../../../packages/api-contracts/src/index';
import {
  costCalculationBusinessTypeLabel,
  costCalculationCoverageLabel,
  costCalculationDifferenceLabel,
  costCalculationEntryStatusLabel,
  costCalculationPeriodLabel,
  costCalculationReasonLabel,
  costCalculationRunStatusLabel,
  fetchCostCalculationCapabilities,
  fetchCostCalculationComparison,
  fetchCostCalculationOptions,
  fetchCostCalculationRun,
  fetchCostCalculationRuns,
  formatCostCalculationMoney,
  formatCostCalculationQuantity,
  recalculateCostCalculation,
  runCostCalculation,
  sortCostCalculationEntries,
} from './cost-calculation-model';
import { fetchCostValuationScopes } from './cost-valuation-model';
import type { FinanceCostValuationScopeOptions } from '../../../../../packages/api-contracts/src/index';

/**
 * 12.4.1.2 成本计算执行。
 *
 * <p>蓝图 12-finance.md：「执行成本计算：对销售出库、发货、平台仓转出计算应结转成本，生成成本层与成本分录；支持按期间批量重算与差异对比。」关系：「上游 = 7 库存流水、12.4.1.1；下游 = 13 利润、12.5.2 凭证。<b>数量账在 warehouse、价值账在 finance</b>——这是两个服务的职责划分，重算不能写库存。」
 *
 * <p><b>页面围绕三件事组织</b>：
 *
 * <ol>
 *   <li><b>执行成本计算</b>（第一块）：选范围 + 计价方法 + 期间，跑一次算账。
 *       出库数量来自 warehouse 的真实库存流水（只读），成本在本服务算 ——
 *       页面明确写出这条边界，免得看的人以为「重算会改库存」。</li>
 *   <li><b>成本计算批次</b>（第二块）：每次计算的表头 —— 期间 / 方法 / 版本 / 汇总 / 缺口数。
 *       点开看该批次的<b>成本层</b>与<b>成本分录</b>。</li>
 *   <li><b>重算与差异对比</b>（第三、四块）：重算<b>另起批次</b>（旧批次原样保留），
 *       差异对比逐 SKU 给出两次批次的差额。</li>
 * </ol>
 *
 * <p><b>🔴 页面绝不显示 0 冒充「算不出来」</b>：有缺口的分录行不给单价，
 * 并在行上标出缺口数量与原因。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有算过成本」。
 */
export function CostCalculationWorkspace() {
  const [caps, setCaps] = useState<FinanceCostCalculationCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceCostCalculationOptions | null>(null);
  const [scopes, setScopes] = useState<FinanceCostValuationScopeOptions | null>(null);
  const [runs, setRuns] = useState<FinanceCostCalculationRunPage | null>(null);
  const [detail, setDetail] = useState<FinanceCostCalculationRunDetail | null>(null);
  const [comparison, setComparison] = useState<FinanceCostCalculationVarianceComparison | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [methodFilter, setMethodFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');

  // 执行成本计算
  const [formScopeType, setFormScopeType] = useState('LEGAL_ENTITY');
  const [formScopeRefId, setFormScopeRefId] = useState('');
  const [formMethod, setFormMethod] = useState('FIFO');
  const [formCurrency, setFormCurrency] = useState('USD');
  const [formPeriodFrom, setFormPeriodFrom] = useState('');
  const [formPeriodTo, setFormPeriodTo] = useState('');
  const [formBatchRef, setFormBatchRef] = useState('');

  // 差异对比
  const [compareFrom, setCompareFrom] = useState('');
  const [compareTo, setCompareTo] = useState('');

  const scopeOptions = useMemo(() => {
    if (!scopes) return [];
    return formScopeType === 'CATEGORY' ? scopes.categories : scopes.legalEntities;
  }, [scopes, formScopeType]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, s, r] = await Promise.all([
        fetchCostCalculationCapabilities(),
        fetchCostCalculationOptions(),
        fetchCostValuationScopes(),
        fetchCostCalculationRuns({
          valuationMethod: methodFilter || undefined,
          status: statusFilter || undefined,
        }),
      ]);
      setCaps(c);
      setOptions(o);
      setScopes(s);
      setRuns(r);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载成本计算批次失败');
    } finally {
      setLoading(false);
    }
  }, [methodFilter, statusFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (id: string) => {
    try {
      const result = await fetchCostCalculationRun(id);
      setDetail(result);
      setActionError('');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '加载批次明细失败');
    }
  }, []);

  const submitCalculation = useCallback(async () => {
    if (!formScopeRefId) {
      setActionError('必须选择一个计价范围（法人或品类）');
      return;
    }
    if (!formPeriodFrom || !formPeriodTo) {
      setActionError('必须给出计算期间（起止都要）');
      return;
    }
    try {
      const scope = scopeOptions.find((s) => s.refId === formScopeRefId);
      const row = await runCostCalculation({
        scopeType: formScopeType,
        scopeRefId: formScopeRefId,
        scopeCode: scope?.code ?? '',
        scopeName: scope?.name ?? '',
        valuationMethod: formMethod,
        currency: formCurrency,
        periodFrom: new Date(formPeriodFrom).toISOString(),
        periodTo: new Date(formPeriodTo).toISOString(),
        batchRef: formBatchRef || null,
        reason: null,
        note: null,
      });
      setActionError('');
      setNotice(
        `已完成第 ${row.id.slice(0, 8)} 号批次的成本计算：结转 ${formatCostCalculationMoney(
          row.totalCostAmount,
        )} ${row.currency}` +
          (row.incompleteEntryCount > 0
            ? `，其中 ${row.incompleteEntryCount} 笔出库存在缺口（缺口 ${formatCostCalculationQuantity(
                row.totalShortfallQuantity,
              )}）—— 缺口必须看得见，别把它当成完整成本。`
            : '。'),
      );
      await load();
      await openDetail(row.id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '执行成本计算失败');
    }
  }, [
    formScopeType,
    formScopeRefId,
    formMethod,
    formCurrency,
    formPeriodFrom,
    formPeriodTo,
    formBatchRef,
    scopeOptions,
    load,
    openDetail,
  ]);

  const submitRecalculate = useCallback(
    async (id: string) => {
      try {
        const row = await recalculateCostCalculation(id);
        setActionError('');
        setNotice(
          `已重算出新批次（第 ${row.id.slice(0, 8)} 号）：方法 ${row.valuationMethodLabel}，` +
            `结转 ${formatCostCalculationMoney(row.totalCostAmount)} ${row.currency}。` +
            '原批次的数字**原样保留**（历史即证据），两个批次可做差异对比。',
        );
        await load();
        await openDetail(row.id);
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '重算失败');
      }
    },
    [load, openDetail],
  );

  const submitComparison = useCallback(async () => {
    if (!compareFrom || !compareTo) {
      setActionError('请选择要对比的两个批次');
      return;
    }
    try {
      const result = await fetchCostCalculationComparison(compareFrom, compareTo);
      setComparison(result);
      setActionError('');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '差异对比失败');
    }
  }, [compareFrom, compareTo]);

  if (loading && !caps) {
    return (
      <div className="p-6">
        <p className="text-copy-13 text-muted-foreground">正在加载成本计算批次…</p>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <div className="p-6">
        <Notice tone="danger" title="成本计算批次加载失败">
          {error}
          <Button className="ml-3" onClick={() => void load()}>
            重试
          </Button>
        </Notice>
      </div>
    );
  }

  const runItems = runs?.items ?? [];

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="成本计算执行"
        description="对销售出库、发货、平台仓转出计算应结转成本，生成成本层与成本分录；支持按期间重算与差异对比。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {/* 边界提示：数量账在 warehouse、价值账在 finance。 */}
      <Notice tone="info" title="数量账在 warehouse，价值账在 finance">
        出库数量实时读 warehouse 的库存流水（<strong>只读</strong>），
        成本（应结转多少钱）在本服务算并落库。
        <strong>重算不会改库存</strong> —— 重算改的是「这批货应结转多少成本」，不是「仓库里还剩多少」。
      </Notice>

      {actionError ? (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      <div className="grid gap-4 md:grid-cols-4">
        <StatCard label="计算批次" value={runs?.total ?? 0} />
        <StatCard label="已完成批次" value={runs?.completedCount ?? 0} />
        <StatCard
          label="最近批次结转金额"
          value={formatCostCalculationMoney(runItems[0]?.totalCostAmount ?? null)}
        />
        <StatCard label="最近批次缺口条数" value={runItems[0]?.incompleteEntryCount ?? 0} />
      </div>

      {/* 1. 执行成本计算 */}
      <SectionCard
        title="执行成本计算"
        description="选范围、计价方法与期间，跑一次算账。计价方法默认取 12.4.1.1 在期间起点生效的那一版；显式指定则按指定的算。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <label className="text-copy-12">
            <span className="text-muted-foreground">适用范围</span>
            <select
              className="border-control-border bg-card mt-1 h-9 w-full rounded-md border px-2 text-copy-13"
              value={formScopeType}
              onChange={(e) => {
                setFormScopeType(e.target.value);
                setFormScopeRefId('');
              }}
            >
              <option value="LEGAL_ENTITY">按法人</option>
              <option value="CATEGORY">按品类</option>
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">范围</span>
            <select
              className="border-control-border bg-card mt-1 h-9 w-full rounded-md border px-2 text-copy-13"
              value={formScopeRefId}
              onChange={(e) => setFormScopeRefId(e.target.value)}
            >
              <option value="">请选择</option>
              {scopeOptions.map((s) => (
                <option key={s.refId} value={s.refId}>
                  {s.name || s.code}（{s.code}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">计价方法</span>
            <select
              className="border-control-border bg-card mt-1 h-9 w-full rounded-md border px-2 text-copy-13"
              value={formMethod}
              onChange={(e) => setFormMethod(e.target.value)}
            >
              {(options?.methods ?? []).map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">计价币种</span>
            <Input
              className="mt-1"
              value={formCurrency}
              onChange={(e) => setFormCurrency(e.target.value.toUpperCase())}
              maxLength={3}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">期间起（含）</span>
            <Input
              className="mt-1"
              type="datetime-local"
              value={formPeriodFrom}
              onChange={(e) => setFormPeriodFrom(e.target.value)}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">期间止（不含）</span>
            <Input
              className="mt-1"
              type="datetime-local"
              value={formPeriodTo}
              onChange={(e) => setFormPeriodTo(e.target.value)}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">批次（批次成本法用）</span>
            <Input
              className="mt-1"
              value={formBatchRef}
              onChange={(e) => setFormBatchRef(e.target.value)}
              placeholder="留空则批次法算不出结果"
            />
          </label>
        </div>
        <div className="mt-3 flex items-center gap-2">
          <Button onClick={() => void submitCalculation()}>执行成本计算</Button>
          {scopes?.legalEntityNote ? (
            <span className="text-copy-12 text-warning">法人清单：{scopes.legalEntityNote}</span>
          ) : null}
          {scopes?.categoryNote ? (
            <span className="text-copy-12 text-warning">品类清单：{scopes.categoryNote}</span>
          ) : null}
        </div>
      </SectionCard>

      {/* 2. 计算批次 */}
      <SectionCard
        title="成本计算批次"
        description="每次计算一行。重算会另起一个批次，原批次的数字原样保留。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
              value={methodFilter}
              onChange={(e) => setMethodFilter(e.target.value)}
              aria-label="按计价方法筛选"
            >
              <option value="">全部方法</option>
              {(options?.methods ?? []).map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              aria-label="按状态筛选"
            >
              <option value="">全部状态</option>
              {(options?.statuses ?? []).map((s) => (
                <option key={s} value={s}>
                  {costCalculationRunStatusLabel(s)}
                </option>
              ))}
            </select>
          </div>
        }
      >
        {runItems.length === 0 ? (
          <EmptyState
            title="还没有算过成本"
            description="选好范围、计价方法与期间后跑一次算账。计算不会改库存，只写价值。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">批次</th>
                  <th className="p-2">期间</th>
                  <th className="p-2">方法</th>
                  <th className="p-2">结转金额</th>
                  <th className="p-2">覆盖 / 缺口</th>
                  <th className="p-2">状态</th>
                  <th className="p-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {runItems.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="p-2">
                      <div>{row.scopeName || row.scopeCode || '—'}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {row.id.slice(0, 8)} · {costCalculationReasonLabel(row.recalculationReason)}
                      </div>
                    </td>
                    <td className="p-2">{costCalculationPeriodLabel(row.periodFrom, row.periodTo)}</td>
                    <td className="p-2">
                      {row.valuationMethodLabel}
                      {row.valuationMethodVersion ? (
                        <span className="text-copy-12 text-muted-foreground">
                          {' '}
                          第 {row.valuationMethodVersion} 版
                        </span>
                      ) : null}
                    </td>
                    <td className="p-2">
                      {formatCostCalculationMoney(row.totalCostAmount)} {row.currency}
                    </td>
                    <td className="p-2">
                      {costCalculationCoverageLabel(
                        row.totalCoveredQuantity,
                        row.totalQuantity,
                      )}
                      {row.incompleteEntryCount > 0 ? (
                        <span className="ml-1 inline-flex items-center gap-1 text-warning">
                          <TriangleAlert className="h-3.5 w-3.5" />
                          {row.incompleteEntryCount} 笔有缺口
                        </span>
                      ) : null}
                    </td>
                    <td className="p-2">
                      <Badge tone={row.status === 'COMPLETED' ? 'success' : 'neutral'}>
                        {costCalculationRunStatusLabel(row.status)}
                      </Badge>
                    </td>
                    <td className="p-2">
                      <div className="flex flex-wrap gap-2">
                        <Button variant="outline" onClick={() => void openDetail(row.id)}>
                          明细
                        </Button>
                        <Button
                          variant="outline"
                          onClick={() => void submitRecalculate(row.id)}
                          disabled={row.status !== 'COMPLETED'}
                        >
                          重算
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 3. 批次明细 */}
      {detail ? (
        <SectionCard
          title={`批次明细：${detail.run.scopeName || detail.run.scopeCode} · ${costCalculationPeriodLabel(
            detail.run.periodFrom,
            detail.run.periodTo,
          )}`}
          description="上半部分是成本层（按批次扣减的口径），下半部分是成本分录（按出库记账的口径）。"
        >
          <Notice tone="info" title="口径与来源">
            {detail.run.basisNote}
            {detail.run.sourceNote ? ` ${detail.run.sourceNote}` : ''}
          </Notice>

          <div className="mt-4">
            <h3 className="text-copy-13 font-medium">成本层（{detail.layers.length} 层）</h3>
            {detail.layers.length === 0 ? (
              <p className="mt-2 text-copy-13 text-muted-foreground">
                这个批次没有消耗任何成本层 —— 要么期间内没有出库，要么成本层不足。
              </p>
            ) : (
              <div className="mt-2 overflow-x-auto">
                <table className="w-full text-left text-copy-13">
                  <thead className="text-copy-12 text-muted-foreground">
                    <tr>
                      <th className="p-2">SKU</th>
                      <th className="p-2">来源批次</th>
                      <th className="p-2">到货日</th>
                      <th className="p-2">可消耗数量</th>
                      <th className="p-2">采用单价</th>
                      <th className="p-2">实际消耗</th>
                      <th className="p-2">消耗金额</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.layers.map((layer: FinanceCostCalculationLayerRow) => (
                      <tr key={layer.id} className="border-t">
                        <td className="p-2">{layer.skuCode || layer.skuId}</td>
                        <td className="p-2">{layer.lotRef || '—'}</td>
                        <td className="p-2">{layer.receivedDate ?? '—'}</td>
                        <td className="p-2">{formatCostCalculationQuantity(layer.layerQuantity)}</td>
                        <td className="p-2">{formatCostCalculationMoney(layer.unitCost)}</td>
                        <td className="p-2">{formatCostCalculationQuantity(layer.consumedQuantity)}</td>
                        <td className="p-2">{formatCostCalculationMoney(layer.consumedAmount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="mt-4">
            <h3 className="text-copy-13 font-medium">
              成本分录（{detail.entries.length} 条 · 有缺口的排在最后）
            </h3>
            {detail.entries.length === 0 ? (
              <p className="mt-2 text-copy-13 text-muted-foreground">
                这个期间内没有出库（销售出库 / 发货 / 平台仓转出），所以没有成本分录。
              </p>
            ) : (
              <div className="mt-2 overflow-x-auto">
                <table className="w-full text-left text-copy-13">
                  <thead className="text-copy-12 text-muted-foreground">
                    <tr>
                      <th className="p-2">出库时间</th>
                      <th className="p-2">业务类型</th>
                      <th className="p-2">SKU</th>
                      <th className="p-2">出库 / 覆盖 / 缺口</th>
                      <th className="p-2">单价</th>
                      <th className="p-2">结转金额</th>
                      <th className="p-2">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sortCostCalculationEntries(detail.entries).map(
                      (entry: FinanceCostCalculationEntryRow) => (
                        <tr key={entry.id} className="border-t">
                          <td className="p-2">
                            {entry.occurredAt.slice(0, 19).replace('T', ' ')}
                          </td>
                          <td className="p-2">{costCalculationBusinessTypeLabel(entry.businessType)}</td>
                          <td className="p-2">{entry.skuCode || entry.skuId}</td>
                          <td className="p-2">
                            {formatCostCalculationQuantity(entry.issueQuantity)} /{' '}
                            {formatCostCalculationQuantity(entry.coveredQuantity)} /{' '}
                            {formatCostCalculationQuantity(entry.shortfallQuantity)}
                          </td>
                          <td className="p-2">{formatCostCalculationMoney(entry.unitCost)}</td>
                          <td className="p-2">
                            {formatCostCalculationMoney(entry.costAmount)} {entry.currency}
                          </td>
                          <td className="p-2">
                            {entry.status === 'INCOMPLETE' ? (
                              <span className="inline-flex items-center gap-1 text-warning">
                                <TriangleAlert className="h-3.5 w-3.5" />
                                {costCalculationEntryStatusLabel(entry.status)}
                                {entry.gapNote ? `：${entry.gapNote}` : ''}
                              </span>
                            ) : (
                              <Badge tone="success">
                                {costCalculationEntryStatusLabel(entry.status)}
                              </Badge>
                            )}
                          </td>
                        </tr>
                      ),
                    )}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </SectionCard>
      ) : null}

      {/* 4. 差异对比 */}
      <SectionCard
        title="差异对比（两次计算批次之间）"
        description="通常是切换计价方法后重算的同一段期间。逐 SKU 对比出库量与结转金额。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-copy-12">
            <span className="text-muted-foreground">旧批次</span>
            <select
              className="border-control-border bg-card mt-1 h-9 rounded-md border px-2 text-copy-13"
              value={compareFrom}
              onChange={(e) => setCompareFrom(e.target.value)}
            >
              <option value="">请选择</option>
              {runItems.map((row: FinanceCostCalculationRunRow) => (
                <option key={row.id} value={row.id}>
                  {row.id.slice(0, 8)} · {row.valuationMethodLabel} ·{' '}
                  {costCalculationPeriodLabel(row.periodFrom, row.periodTo)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">新批次</span>
            <select
              className="border-control-border bg-card mt-1 h-9 rounded-md border px-2 text-copy-13"
              value={compareTo}
              onChange={(e) => setCompareTo(e.target.value)}
            >
              <option value="">请选择</option>
              {runItems.map((row: FinanceCostCalculationRunRow) => (
                <option key={row.id} value={row.id}>
                  {row.id.slice(0, 8)} · {row.valuationMethodLabel} ·{' '}
                  {costCalculationPeriodLabel(row.periodFrom, row.periodTo)}
                </option>
              ))}
            </select>
          </label>
          <Button onClick={() => void submitComparison()} disabled={!compareFrom || !compareTo}>
            对比
          </Button>
        </div>

        {comparison ? (
          <div className="mt-4 space-y-3">
            <Notice tone="info" title="对比结论">
              {comparison.note}
            </Notice>
            <div className="grid gap-3 md:grid-cols-4">
              <StatCard
                label="旧批次结转金额"
                value={formatCostCalculationMoney(comparison.fromTotalCostAmount)}
              />
              <StatCard
                label="新批次结转金额"
                value={formatCostCalculationMoney(comparison.toTotalCostAmount)}
              />
              <StatCard label="差额" value={costCalculationDifferenceLabel(comparison.totalCostAmountDifference)} />
              <StatCard label="对比 SKU 数" value={comparison.comparedSkuCount} />
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-copy-12 text-muted-foreground">
                  <tr>
                    <th className="p-2">SKU</th>
                    <th className="p-2">旧：数量 / 金额</th>
                    <th className="p-2">新：数量 / 金额</th>
                    <th className="p-2">数量差</th>
                    <th className="p-2">金额差</th>
                    <th className="p-2">备注</th>
                  </tr>
                </thead>
                <tbody>
                  {comparison.rows.map((row) => (
                    <tr key={row.skuId} className="border-t">
                      <td className="p-2">{row.skuCode || row.skuId}</td>
                      <td className="p-2">
                        {formatCostCalculationQuantity(row.fromQuantity)} /{' '}
                        {formatCostCalculationMoney(row.fromCostAmount)}
                      </td>
                      <td className="p-2">
                        {formatCostCalculationQuantity(row.toQuantity)} /{' '}
                        {formatCostCalculationMoney(row.toCostAmount)}
                      </td>
                      <td className="p-2">
                        {formatCostCalculationQuantity(row.quantityDifference)}
                      </td>
                      <td className="p-2">
                        {costCalculationDifferenceLabel(row.costAmountDifference)}
                      </td>
                      <td className="p-2">
                        {row.onlyInFrom ? '只在旧批次里' : row.onlyInTo ? '只在新批次里' : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ) : runItems.length === 0 ? (
          <p className="mt-3 text-copy-12 text-muted-foreground">还没有批次可比。</p>
        ) : null}
      </SectionCard>

      {/* 能力声明 */}
      <SectionCard title="能力声明" description="哪些上游真接通、哪些还没交付、归谁。">
        <div className="space-y-2">
          {(caps?.capabilities ?? []).map((c) => (
            <div key={`${c.module}-${c.name}`} className="border-t pt-2">
              <div className="flex items-center gap-2">
                <Badge tone={c.available ? 'success' : 'danger'}>{c.available ? '已交付' : '未交付'}</Badge>
                <span className="text-copy-13 font-medium">{c.name}</span>
              </div>
              <p className="mt-1 text-copy-12 text-muted-foreground">{c.detail}</p>
            </div>
          ))}
          {(caps?.downstreamGaps ?? []).map((g) => (
            <div key={`${g.module}-${g.name}`} className="border-t pt-2">
              <div className="flex items-center gap-2">
                <Badge tone="warning">下游缺口</Badge>
                <span className="text-copy-13 font-medium">
                  {g.module} {g.name}
                </span>
              </div>
              <p className="mt-1 text-copy-12 text-muted-foreground">{g.detail}</p>
            </div>
          ))}
        </div>
      </SectionCard>
    </div>
  );
}
