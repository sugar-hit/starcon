'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ChangeEvent } from 'react';
import {
  AlertTriangle,
  Coins,
  FileWarning,
  Layers,
  Package,
  RefreshCw,
  Scale,
} from 'lucide-react';
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
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import {
  allocationStatusLabel,
  allocationStatusTone,
  basisLabel,
  componentLabel,
  componentTone,
  createCostAllocationRule,
  createCostAllocation,
  deleteCostParameter,
  EMPTY_ALLOCATION_FILTERS,
  EMPTY_COST_FILTERS,
  fetchCostAllocationRules,
  fetchCostAllocations,
  fetchCostCapabilities,
  fetchCostChannelComparison,
  fetchCostOverview,
  fetchCostOverCostOrders,
  fetchCostParameters,
  formatAmount,
  formatQuantity,
  formatShare,
  isMissing,
  lockCostAllocation,
  missingComponentLabels,
  ruleStatusLabel,
  ruleStatusTone,
  saveCostParameter,
  sourceKindLabel,
  sourceKindTone,
  toIsoInstant,
  validateCostWindow,
} from './logistics-cost-analysis-model';
import type {
  CostAllocationPage,
  CostAllocationRulePage,
  CostCapabilities,
  CostChannelComparison,
  CostComponentAmount,
  CostCurrencyRow,
  CostOverview,
  CostOverCostOrderPage,
  LogisticsCostParameterPage,
  CostFilters,
  Tone,
} from './logistics-cost-analysis-model';

/**
 * 10.6.1.1 成本分析工作区。
 *
 * <p>四个区块按**现场判断顺序**排：
 * ① 能力与口径 → ② 成本报表（全口径五分量）→ ③ 渠道对比与超成本订单 → ④ 头程分摊明细。
 *
 * <p>🔴 三个「不假装」的地方：
 *
 * <ul>
 *   <li><b>缺的那一位显示「暂无数据」，不是 0</b>。页面上每个金额格都走
 *       {@link formatAmount} 这一个入口：{@code null} ⇒ 中性色 + 「暂无数据」，
 *       {@code 0} ⇒ 「0.00」。把「没人记这笔账」画成「这项成本不要钱」，
 *       正是蓝图加粗句要治的病。</li>
 *   <li><b>合计永远带着「我算了谁、没算谁」一起出现</b>：每个币种行都列
 *       {@code includedComponents} / {@code missingComponents}，
 *       缺的那几位在界面上**点名**（不是一个空数字）。</li>
 *   <li><b>参数与事实分色</b>：包材走「参数」口径（租户维护的每票包材成本），
 *       与尾程 / 头程 / 关税的「事实」用不同徽章颜色。</li>
 * </ul>
 */
export function LogisticsCostAnalysisWorkspace() {
  const [capabilities, setCapabilities] = useState<CostCapabilities | null>(null);
  const [overview, setOverview] = useState<CostOverview | null>(null);
  const [comparison, setComparison] = useState<CostChannelComparison | null>(null);
  const [overCost, setOverCost] = useState<CostOverCostOrderPage | null>(null);
  const [rules, setRules] = useState<CostAllocationRulePage | null>(null);
  const [allocations, setAllocations] = useState<CostAllocationPage | null>(null);
  const [parameters, setParameters] = useState<LogisticsCostParameterPage | null>(null);
  const [filters, setFilters] = useState<CostFilters>(EMPTY_COST_FILTERS);
  const [allocationFilters, setAllocationFilters] = useState(EMPTY_ALLOCATION_FILTERS);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [parameterForm, setParameterForm] = useState({
    component: 'PACKAGING',
    currency: 'USD',
    amountPerWaybill: '',
    note: '',
  });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, report, channels, orders, rulePage, allocationPage, parameterPage] =
        await Promise.all([
          fetchCostCapabilities(),
          fetchCostOverview(filters),
          fetchCostChannelComparison(filters),
          fetchCostOverCostOrders(filters),
          fetchCostAllocationRules(0, 50),
          fetchCostAllocations(allocationFilters),
          fetchCostParameters(0, 50),
        ]);
      setCapabilities(caps);
      setOverview(report);
      setComparison(channels);
      setOverCost(orders);
      setRules(rulePage);
      setAllocations(allocationPage);
      setParameters(parameterPage);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '成本数据加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [filters, allocationFilters]);

  useEffect(() => {
    void load();
  }, [load]);

  const windowErrors = useMemo(() => validateCostWindow(filters), [filters]);

  const saveParameter = useCallback(async () => {
    const amount = Number(parameterForm.amountPerWaybill);
    if (!Number.isFinite(amount) || amount < 0) {
      setError('每票包材成本必须是不小于 0 的数字');
      return;
    }
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      await saveCostParameter({
        component: parameterForm.component,
        currency: parameterForm.currency.trim().toUpperCase(),
        amountPerWaybill: amount,
        note: parameterForm.note,
      });
      setNotice(`已保存 ${parameterForm.currency.toUpperCase()} 的包材成本参数`);
      setParameterForm((form) => ({ ...form, amountPerWaybill: '', note: '' }));
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '参数保存失败');
    } finally {
      setSaving(false);
    }
  }, [parameterForm, load]);

  const removeParameter = useCallback(
    async (id: string) => {
      setSaving(true);
      setError(null);
      setNotice(null);
      try {
        await deleteCostParameter(id);
        setNotice('已删除该币种的包材成本参数：报表里那一位会回到「暂无数据」');
        await load();
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : '参数删除失败');
      } finally {
        setSaving(false);
      }
    },
    [load],
  );

  const createRule = useCallback(async () => {
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const created = await createCostAllocationRule({
        ruleCode: 'FM-STD',
        ruleName: '标准头程分摊',
        allocationBasis: 'BY_QUANTITY',
        effectiveFrom: toIsoInstant(new Date().toISOString().slice(0, 10)) ?? new Date().toISOString(),
        effectiveTo: null,
        note: '工作区新建',
        activate: true,
      });
      setNotice(`已新建规则版本 v${created.version}（${created.statusLabel}）`);
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '规则新建失败');
    } finally {
      setSaving(false);
    }
  }, [load]);

  const lockAllocation = useCallback(
    async (id: string) => {
      setSaving(true);
      setError(null);
      setNotice(null);
      try {
        const locked = await lockCostAllocation(id, { costAsOf: null, note: '工作区结账' });
        setNotice(`分摊单 ${locked.allocationCode} 已结账（金额与结账时点从此不可再改）`);
        await load();
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : '结账失败');
      } finally {
        setSaving(false);
      }
    },
    [load],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="成本分析"
        description="10.6.1.1 成本分析 —— 真实成本 = 尾程 + 头程分摊 + 包材 + 关税 + 仓储费，报表全口径"
      />

      {error && (
        <Notice tone="danger" title="加载或操作失败">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="已处理">
          {notice}
        </Notice>
      )}

      {/* ① 能力与口径 */}
      <SectionCard title="① 能力与口径">
        {loading && !capabilities ? (
          <p className="text-copy-13 text-muted-foreground">正在加载能力声明…</p>
        ) : capabilities ? (
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
              {capabilities.components.map((component) => (
                <div key={component.component} className="rounded-lg border p-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-copy-13 text-foreground font-medium">
                      {component.displayName}
                    </span>
                    <div className="flex items-center gap-1">
                      <Badge tone={sourceKindTone(component.sourceKind)}>
                        {sourceKindLabel(component.sourceKind)}
                      </Badge>
                      <Badge tone={component.available ? 'success' : 'danger'}>
                        {component.available ? '有来源' : '暂无数据'}
                      </Badge>
                    </div>
                  </div>
                  <p className="text-label-12 text-muted-foreground mt-1 break-words">
                    {component.source || component.unavailableReason || '—'}
                  </p>
                  {component.ownerModule && (
                    <p className="text-label-12 text-muted-foreground mt-1">
                      归属模块：{component.ownerModule}
                    </p>
                  )}
                </div>
              ))}
            </div>
            <div className="rounded-lg border p-3">
              <p className="text-copy-13 text-foreground font-medium">成本口径</p>
              <p className="text-label-12 text-muted-foreground mt-1">{capabilities.basisNote}</p>
              <p className="text-label-12 text-muted-foreground mt-1">{capabilities.financeNote}</p>
              <ul className="text-label-12 text-muted-foreground mt-2 list-disc pl-4">
                {capabilities.notes.map((note) => (
                  <li key={note}>{note}</li>
                ))}
              </ul>
            </div>
          </div>
        ) : (
          <EmptyState title="能力声明暂不可用" description="请稍后重试" />
        )}
      </SectionCard>

      {/* 时间窗 */}
      <SectionCard title="时间窗">
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="cost-window-from" label="开始日期">
            <Input
              type="date"
              value={filters.dateFrom}
              onChange={(event: ChangeEvent<HTMLInputElement>) =>
                setFilters((f) => ({ ...f, dateFrom: event.target.value, page: 0 }))
              }
            />
          </FormField>
          <FormField id="cost-window-to" label="结束日期">
            <Input
              type="date"
              value={filters.dateTo}
              onChange={(event: ChangeEvent<HTMLInputElement>) =>
                setFilters((f) => ({ ...f, dateTo: event.target.value, page: 0 }))
              }
            />
          </FormField>
          <FormField id="cost-multiplier" label="超成本倍数" hint="单位重量运费 ÷ 同币种平均值">
            <Input
              value={filters.multiplier}
              onChange={(event: ChangeEvent<HTMLInputElement>) =>
                setFilters((f) => ({ ...f, multiplier: event.target.value, page: 0 }))
              }
            />
          </FormField>
          <div className="flex items-end">
            <Button variant="secondary" onClick={() => void load()}>
              <RefreshCw className="mr-1.5 h-4 w-4" />
              重新加载
            </Button>
          </div>
        </div>
        {windowErrors.length > 0 && (
          <Notice tone="warning" title="时间窗有问题">
            {windowErrors.join('；')}
          </Notice>
        )}
      </SectionCard>

      <Tabs defaultValue="report">
        <TabsList>
          <TabsTrigger value="report">② 成本报表</TabsTrigger>
          <TabsTrigger value="channels">③ 渠道对比与超成本</TabsTrigger>
          <TabsTrigger value="allocations">④ 头程分摊明细</TabsTrigger>
        </TabsList>

        {/* ② 成本报表 */}
        <TabsContent value="report">
          <div className="space-y-4">
            {loading && !overview ? (
              <p className="text-copy-13 text-muted-foreground">正在加载成本报表…</p>
            ) : overview ? (
              <>
                <div className="grid gap-3 md:grid-cols-4">
                  <StatCard
                    label="扫描运单"
                    value={String(overview.scannedWaybills)}
                    comparisonLabel={
                      overview.truncated ? '撞到扫描上限，统计不完整' : '本时间窗整窗扫描'
                    }
                  />
                  <StatCard
                    label="币种数"
                    value={String(overview.currencyRows.length)}
                    comparisonLabel="跨币种绝不相加"
                  />
                  <StatCard
                    label="口径覆盖"
                    value={overview.coverage.complete ? '全口径' : '不完整'}
                    comparisonLabel={
                      overview.coverage.missingComponents.length > 0
                        ? `缺：${missingComponentLabels(overview.coverage.missingComponents).join('、')}`
                        : '五个分量都有数'
                    }
                  />
                  <StatCard
                    label="合计口径"
                    value={overview.coverage.complete ? '含全部分量' : '仅已计入分量'}
                    comparisonLabel="缺项不计入合计"
                  />
                </div>

                <Notice
                  tone={overview.coverage.complete ? 'success' : 'warning'}
                  title="口径覆盖说明"
                >
                  {overview.coverage.note}
                </Notice>

                {/* 五个分量并排 —— 全口径在页面上必须一眼看全 */}
                <SectionCard title="五个成本分量（并排显示，缺的一位显示「暂无数据」而不是 0）">
                  <div className="space-y-3">
                    {overview.components.map((component: CostComponentAmount) => (
                      <div key={component.component} className="rounded-lg border p-3">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="text-copy-13 text-foreground font-medium">
                            {component.displayName}
                          </span>
                          <div className="flex items-center gap-1">
                            <Badge tone={sourceKindTone(component.sourceKind)}>
                              {sourceKindLabel(component.sourceKind)}
                            </Badge>
                            <Badge tone={componentTone(component)}>
                              {component.available ? '已计入' : '暂无数据'}
                            </Badge>
                          </div>
                        </div>
                        {component.byCurrency.length > 0 ? (
                          <div className="mt-2 flex flex-wrap gap-3">
                            {component.byCurrency.map((total) => (
                              <span key={total.currency} className="text-label-12 text-foreground">
                                {total.currency}{' '}
                                <span className="font-medium">
                                  {formatAmount(total.amount, total.currency)}
                                </span>{' '}
                                <span className="text-muted-foreground">（{total.count} 笔）</span>
                              </span>
                            ))}
                          </div>
                        ) : (
                          <p className="text-label-12 text-muted-foreground mt-2">
                            暂无数据 —— {component.unavailableReason || '没有可读来源'}
                            {component.ownerModule ? `（归属模块：${component.ownerModule}）` : ''}
                          </p>
                        )}
                        {component.estimatedByCurrency.length > 0 && (
                          <p className="text-label-12 text-muted-foreground mt-1">
                            估算（**不进合计**）：
                            {component.estimatedByCurrency
                              .map((total) => formatAmount(total.amount, total.currency))
                              .join('、')}
                          </p>
                        )}
                        {component.basis && (
                          <p className="text-label-12 text-muted-foreground mt-1">口径：{component.basis}</p>
                        )}
                        {component.impact && (
                          <p className="text-label-12 text-muted-foreground mt-1">影响：{component.impact}</p>
                        )}
                      </div>
                    ))}
                  </div>
                </SectionCard>

                {/* 逐币种合计：每一格都能是「暂无数据」 */}
                <SectionCard title="逐币种合计与占比（缺的那几位点名，不是一个 0）">
                  {overview.currencyRows.length === 0 ? (
                    <EmptyState
                      icon={<Scale className="h-6 w-6" />}
                      title="本时间窗没有任何成本数据"
                      description="上游读到了运单但没有登记运费；这不是「成本为 0」，是「还没有数」。"
                    />
                  ) : (
                    <div className="overflow-x-auto">
                      <table className="w-full text-left text-label-12">
                        <thead>
                          <tr className="text-muted-foreground">
                            <th className="py-2 pr-3">币种</th>
                            <th className="py-2 pr-3">尾程运费</th>
                            <th className="py-2 pr-3">头程分摊</th>
                            <th className="py-2 pr-3">关税</th>
                            <th className="py-2 pr-3">包材（参数）</th>
                            <th className="py-2 pr-3">合计</th>
                            <th className="py-2 pr-3">尾程占比</th>
                            <th className="py-2 pr-3">头程占比</th>
                            <th className="py-2 pr-3">关税占比</th>
                            <th className="py-2 pr-3">包材占比</th>
                            <th className="py-2 pr-3">没算谁</th>
                          </tr>
                        </thead>
                        <tbody>
                          {overview.currencyRows.map((row: CostCurrencyRow) => (
                            <tr key={row.currency} className="border-t">
                              <td className="py-2 pr-3 font-medium">{row.currency}</td>
                              <td className={cellTone(row.lastMileFreight)}>
                                {formatAmount(row.lastMileFreight)}
                              </td>
                              <td className={cellTone(row.firstMileAllocation)}>
                                {formatAmount(row.firstMileAllocation)}
                              </td>
                              <td className={cellTone(row.dutyAndTax)}>
                                {formatAmount(row.dutyAndTax)}
                              </td>
                              <td className={cellTone(row.packaging)}>
                                {formatAmount(row.packaging)}
                              </td>
                              <td className={`py-2 pr-3 font-medium ${cellTone(row.subtotal)}`}>
                                {formatAmount(row.subtotal, row.currency)}
                              </td>
                              <td className={cellTone(row.lastMileShare)}>
                                {formatShare(row.lastMileShare)}
                              </td>
                              <td className={cellTone(row.firstMileShare)}>
                                {formatShare(row.firstMileShare)}
                              </td>
                              <td className={cellTone(row.dutyAndTaxShare)}>
                                {formatShare(row.dutyAndTaxShare)}
                              </td>
                              <td className={cellTone(row.packagingShare)}>
                                {formatShare(row.packagingShare)}
                              </td>
                              <td className="py-2 pr-3 text-muted-foreground">
                                {row.missingComponents.length > 0
                                  ? missingComponentLabels(row.missingComponents).join('、')
                                  : '—'}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </SectionCard>

                <SectionCard title="单位成本（单均运费 / 单位重量运费）">
                  {overview.unitMetrics.length === 0 ? (
                    <EmptyState
                      icon={<Scale className="h-6 w-6" />}
                      title="还没有可统计的运单"
                      description="上游读到了运单但没有登记运费；分母为 0 时显示「暂无数据」，不显示 0。"
                    />
                  ) : (
                    <div className="overflow-x-auto">
                      <table className="w-full text-left text-label-12">
                        <thead>
                          <tr className="text-muted-foreground">
                            <th className="py-2 pr-3">币种</th>
                            <th className="py-2 pr-3">已登记运费</th>
                            <th className="py-2 pr-3">未登记运费</th>
                            <th className="py-2 pr-3">总毛重</th>
                            <th className="py-2 pr-3">单均运费</th>
                            <th className="py-2 pr-3">单位重量运费</th>
                          </tr>
                        </thead>
                        <tbody>
                          {overview.unitMetrics.map((metric) => (
                            <tr key={metric.currency} className="border-t">
                              <td className="py-2 pr-3 font-medium">{metric.currency}</td>
                              <td className={cellTone(metric.totalFreightAmount)}>
                                {formatAmount(metric.totalFreightAmount)}
                              </td>
                              <td className="py-2 pr-3">{metric.notRegisteredWaybills}</td>
                              <td className={cellTone(metric.totalWeightKg)}>
                                {formatQuantity(metric.totalWeightKg, 'KG')}
                              </td>
                              <td className={cellTone(metric.perOrderFreight)}>
                                {formatAmount(metric.perOrderFreight)}
                              </td>
                              <td className={cellTone(metric.perKgFreight)}>
                                {formatAmount(metric.perKgFreight)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </SectionCard>

                {/* 包材参数维护 */}
                <SectionCard title="包材成本参数（租户维护，报表按「每票成本 × 票数」算）">
                  <p className="text-label-12 text-muted-foreground mb-3">
                    包材在仓库里**没有成本列**（只有外形尺寸与自重），所以它走**参数**口径。
                    没有维护参数的币种，报表里那一位是「暂无数据」，不是 0。
                  </p>
                  <div className="grid gap-3 md:grid-cols-4">
                    <FormField id="cost-param-currency" label="币种">
                      <Input
                        value={parameterForm.currency}
                        onChange={(event: ChangeEvent<HTMLInputElement>) =>
                          setParameterForm((form) => ({ ...form, currency: event.target.value }))
                        }
                      />
                    </FormField>
                    <FormField id="cost-param-amount" label="每票包材成本">
                      <Input
                        value={parameterForm.amountPerWaybill}
                        placeholder="0.35"
                        onChange={(event: ChangeEvent<HTMLInputElement>) =>
                          setParameterForm((form) => ({
                            ...form,
                            amountPerWaybill: event.target.value,
                          }))
                        }
                      />
                    </FormField>
                    <FormField id="cost-param-note" label="备注">
                      <Input
                        value={parameterForm.note}
                        onChange={(event: ChangeEvent<HTMLInputElement>) =>
                          setParameterForm((form) => ({ ...form, note: event.target.value }))
                        }
                      />
                    </FormField>
                    <div className="flex items-end">
                      <Button disabled={saving} onClick={() => void saveParameter()}>
                        <Package className="mr-1.5 h-4 w-4" />
                        保存参数
                      </Button>
                    </div>
                  </div>
                  <div className="mt-3">
                    {parameters && parameters.items.length > 0 ? (
                      <ul className="space-y-2">
                        {parameters.items.map((parameter) => (
                          <li
                            key={parameter.id}
                            className="flex items-center justify-between gap-2 rounded border p-2"
                          >
                            <span className="text-label-12 text-foreground">
                              {parameter.currency} · {componentLabel(parameter.component)} ·{' '}
                              {formatAmount(parameter.amountPerWaybill)} / 票
                              {parameter.note ? ` · ${parameter.note}` : ''}
                            </span>
                            <Button
                              variant="secondary"
                              disabled={saving}
                              onClick={() => void removeParameter(parameter.id)}
                            >
                              删除
                            </Button>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p className="text-label-12 text-muted-foreground">
                        还没有维护任何包材成本参数 —— 报表里的包材位会显示「暂无数据」。
                      </p>
                    )}
                  </div>
                </SectionCard>
              </>
            ) : (
              <EmptyState
                icon={<AlertTriangle className="h-6 w-6" />}
                title="成本报表暂不可用"
                description={error ?? '请稍后重试'}
              />
            )}
          </div>
        </TabsContent>

        {/* ③ 渠道对比与超成本订单 */}
        <TabsContent value="channels">
          <div className="space-y-4">
            <SectionCard title="各渠道成本对比（逐币种，跨币种不相加）">
              {loading && !comparison ? (
                <p className="text-copy-13 text-muted-foreground">正在加载渠道对比…</p>
              ) : comparison && comparison.rows.length > 0 ? (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-label-12">
                    <thead>
                      <tr className="text-muted-foreground">
                        <th className="py-2 pr-3">承运商</th>
                        <th className="py-2 pr-3">渠道</th>
                        <th className="py-2 pr-3">票数</th>
                        <th className="py-2 pr-3">已登记 / 未登记</th>
                        <th className="py-2 pr-3">金额（逐币种）</th>
                        <th className="py-2 pr-3">单均运费</th>
                        <th className="py-2 pr-3">单位重量运费</th>
                      </tr>
                    </thead>
                    <tbody>
                      {comparison.rows.map((row) => (
                        <tr key={`${row.carrierCode}/${row.carrierChannel}`} className="border-t">
                          <td className="py-2 pr-3 font-medium">{row.carrierCode}</td>
                          <td className="py-2 pr-3">{row.carrierChannel}</td>
                          <td className="py-2 pr-3">{row.waybills}</td>
                          <td className="py-2 pr-3">
                            {row.freightRegistered} / {row.freightNotRegistered}
                          </td>
                          <td className="py-2 pr-3">
                            {row.byCurrency.length > 0
                              ? row.byCurrency
                                  .map(
                                    (metric) =>
                                      `${metric.currency} ${formatAmount(metric.amount)}`,
                                  )
                                  .join('、')
                              : '暂无数据（这一渠道的运费一票都没登记）'}
                          </td>
                          <td className="py-2 pr-3">
                            {row.byCurrency.length > 0
                              ? row.byCurrency
                                  .map((metric) => formatAmount(metric.perOrderFreight))
                                  .join('、')
                              : '暂无数据'}
                          </td>
                          <td className="py-2 pr-3">
                            {row.byCurrency.length > 0
                              ? row.byCurrency
                                  .map((metric) => formatAmount(metric.perKgFreight))
                                  .join('、')
                              : '暂无数据'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <EmptyState
                  icon={<Layers className="h-6 w-6" />}
                  title="本时间窗没有可对比的渠道"
                  description="没有运单就没有渠道成本 —— 这不是「各渠道成本都是 0」。"
                />
              )}
            </SectionCard>

            <SectionCard title="超成本订单（判据：单位重量运费超过同币种平均值）">
              {loading && !overCost ? (
                <p className="text-copy-13 text-muted-foreground">正在加载超成本订单…</p>
              ) : overCost ? (
                <>
                  <div className="mb-3 flex flex-wrap gap-2">
                    {overCost.thresholds.map((threshold) => (
                      <Badge key={threshold.currency} tone="info">
                        {threshold.currency}：平均 {formatAmount(threshold.averageUnitFreight)} / KG，
                        阈值 {formatAmount(threshold.thresholdUnitFreight)}（评估{' '}
                        {threshold.evaluatedWaybills} 票，越线 {threshold.overCostWaybills} 票）
                      </Badge>
                    ))}
                  </div>
                  {overCost.items.length === 0 ? (
                    <EmptyState
                      icon={<FileWarning className="h-6 w-6" />}
                      title="本时间窗没有超成本运单"
                      description="这是真实的空：上游读到了数据，但没有一票越线。"
                    />
                  ) : (
                    <div className="overflow-x-auto">
                      <table className="w-full text-left text-label-12">
                        <thead>
                          <tr className="text-muted-foreground">
                            <th className="py-2 pr-3">运单号</th>
                            <th className="py-2 pr-3">渠道</th>
                            <th className="py-2 pr-3">目的国</th>
                            <th className="py-2 pr-3">运费</th>
                            <th className="py-2 pr-3">毛重</th>
                            <th className="py-2 pr-3">单位重量运费</th>
                            <th className="py-2 pr-3">超出</th>
                            <th className="py-2 pr-3">理由</th>
                          </tr>
                        </thead>
                        <tbody>
                          {overCost.items.map((row) => (
                            <tr key={row.id} className="border-t">
                              <td className="py-2 pr-3 font-medium">{row.waybillNo}</td>
                              <td className="py-2 pr-3">
                                {row.carrierCode} / {row.carrierChannel}
                              </td>
                              <td className="py-2 pr-3">{row.destinationCountry}</td>
                              <td className="py-2 pr-3">
                                {formatAmount(row.freightAmount, row.currency)}
                              </td>
                              <td className={cellTone(row.grossWeightKg)}>
                                {formatQuantity(row.grossWeightKg, 'KG')}
                              </td>
                              <td className={cellTone(row.unitFreight)}>
                                {formatAmount(row.unitFreight)}
                              </td>
                              <td className={cellTone(row.excessAmount)}>
                                {formatAmount(row.excessAmount, row.currency)}
                              </td>
                              <td className="py-2 pr-3 text-muted-foreground">{row.excessReason}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </>
              ) : (
                <EmptyState title="超成本订单暂不可用" description="请稍后重试" />
              )}
            </SectionCard>
          </div>
        </TabsContent>

        {/* ④ 头程分摊明细 */}
        <TabsContent value="allocations">
          <div className="space-y-4">
            <SectionCard title="分摊规则版本（改版留历史版本，不就地覆盖）">
              <div className="mb-3 flex items-center justify-between">
                <p className="text-label-12 text-muted-foreground">
                  同一规则码只有一个现行版本；启用新版本时旧版本转「已退役」但**行还在**，
                  历史分摊单继续指向它当时用的那一版。
                </p>
                <Button disabled={saving} onClick={() => void createRule()}>
                  <Coins className="mr-1.5 h-4 w-4" />
                  新建规则版本
                </Button>
              </div>
              {loading && !rules ? (
                <p className="text-copy-13 text-muted-foreground">正在加载规则…</p>
              ) : rules && rules.items.length > 0 ? (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-label-12">
                    <thead>
                      <tr className="text-muted-foreground">
                        <th className="py-2 pr-3">规则</th>
                        <th className="py-2 pr-3">版本</th>
                        <th className="py-2 pr-3">分摊基准</th>
                        <th className="py-2 pr-3">状态</th>
                        <th className="py-2 pr-3">生效</th>
                        <th className="py-2 pr-3">已引用</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rules.items.map((rule) => (
                        <tr key={rule.id} className="border-t">
                          <td className="py-2 pr-3 font-medium">
                            {rule.ruleCode} · {rule.ruleName}
                          </td>
                          <td className="py-2 pr-3">v{rule.version}</td>
                          <td className="py-2 pr-3">{basisLabel(rule.allocationBasis)}</td>
                          <td className="py-2 pr-3">
                            <Badge tone={ruleStatusTone(rule.status)}>
                              {ruleStatusLabel(rule.status)}
                            </Badge>
                          </td>
                          <td className="py-2 pr-3">{rule.effectiveFrom.slice(0, 10)}</td>
                          <td className="py-2 pr-3">{rule.allocationCount} 张分摊单</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <EmptyState
                  icon={<Coins className="h-6 w-6" />}
                  title="还没有分摊规则"
                  description="分摊规则决定头程成本按什么基准拆到 SKU 行上；没有规则就没有分摊单。"
                />
              )}
            </SectionCard>

            <SectionCard title="分摊单与分摊明细">
              <div className="mb-3 flex items-center gap-2">
                <Input
                  className="max-w-[220px]"
                  placeholder="按状态筛选：COMPUTED / LOCKED / SUPERSEDED"
                  value={allocationFilters.status}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    setAllocationFilters((f) => ({ ...f, status: event.target.value, page: 0 }))
                  }
                />
                <Button variant="secondary" onClick={() => void load()}>
                  <RefreshCw className="mr-1.5 h-4 w-4" />
                  刷新
                </Button>
              </div>
              {loading && !allocations ? (
                <p className="text-copy-13 text-muted-foreground">正在加载分摊单…</p>
              ) : allocations && allocations.items.length > 0 ? (
                <>
                  <div className="mb-3 flex flex-wrap gap-2">
                    <Badge tone="info">已计算 {allocations.computedCount}</Badge>
                    <Badge tone="success">已结账 {allocations.lockedCount}</Badge>
                    <Badge tone="neutral">已被取代 {allocations.supersededCount}</Badge>
                    {allocations.allocatedByCurrency.map((total) => (
                      <Badge key={total.currency} tone="brand">
                        {total.currency} {formatAmount(total.amount)}
                      </Badge>
                    ))}
                  </div>
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-label-12">
                      <thead>
                        <tr className="text-muted-foreground">
                          <th className="py-2 pr-3">分摊单</th>
                          <th className="py-2 pr-3">来源发运单</th>
                          <th className="py-2 pr-3">规则</th>
                          <th className="py-2 pr-3">基准</th>
                          <th className="py-2 pr-3">币种</th>
                          <th className="py-2 pr-3">来源总额</th>
                          <th className="py-2 pr-3">已分摊</th>
                          <th className="py-2 pr-3">行数</th>
                          <th className="py-2 pr-3">结账时点</th>
                          <th className="py-2 pr-3">状态</th>
                          <th className="py-2 pr-3">操作</th>
                        </tr>
                      </thead>
                      <tbody>
                        {allocations.items.map((row) => (
                          <tr key={row.id} className="border-t">
                            <td className="py-2 pr-3 font-medium">{row.allocationCode}</td>
                            <td className="py-2 pr-3">
                              {row.sourceShipmentCode} · {row.sourceCarrierName}
                            </td>
                            <td className="py-2 pr-3">
                              {row.ruleCode} v{row.ruleVersion}
                            </td>
                            <td className="py-2 pr-3">{basisLabel(row.allocationBasis)}</td>
                            <td className="py-2 pr-3">{row.currency}</td>
                            <td className="py-2 pr-3">
                              {formatAmount(row.sourceTotalAmount, row.currency)}
                            </td>
                            <td className="py-2 pr-3">
                              {formatAmount(row.allocatedTotalAmount, row.currency)}
                            </td>
                            <td className="py-2 pr-3">{row.lineCount}</td>
                            <td className="py-2 pr-3">{row.costAsOf.slice(0, 10)}</td>
                            <td className="py-2 pr-3">
                              <Badge tone={allocationStatusTone(row.status)}>
                                {allocationStatusLabel(row.status)}
                              </Badge>
                            </td>
                            <td className="py-2 pr-3">
                              {row.status === 'COMPUTED' && (
                                <Button
                                  variant="secondary"
                                  disabled={saving}
                                  onClick={() => void lockAllocation(row.id)}
                                >
                                  结账
                                </Button>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </>
              ) : (
                <EmptyState
                  icon={<Layers className="h-6 w-6" />}
                  title="还没有分摊单"
                  description="头程发运单要先按规则版本分摊，成本才会进到岸成本报表里。"
                />
              )}
            </SectionCard>
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
}

/**
 * 金额格的配色：**取不到数**用中性色（一眼看出「这里没有数」），
 * **真的有数**（含 0）用前景色。
 */
function cellTone(value: number | null | undefined): string {
  return isMissing(value) ? 'text-muted-foreground' : 'text-foreground';
}
