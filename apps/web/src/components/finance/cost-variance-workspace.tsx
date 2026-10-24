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
  FinanceCostVarianceBaselineRow,
  FinanceCostVarianceCapabilities,
  FinanceCostVarianceOptions,
  FinanceCostVarianceReportDetail,
  FinanceCostVarianceReportPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  costVarianceBaselineStatusLabel,
  costVarianceBaselineTypeLabel,
  costVarianceComparabilityLabel,
  costVarianceDimensionLabel,
  costVarianceDirectionLabel,
  costVarianceDriverLabel,
  costVariancePeriodLabel,
  costVariancePrimaryDriverLabel,
  costVarianceReportStatusLabel,
  costVarianceSeverityLabel,
  fetchCostVarianceBaselines,
  fetchCostVarianceCapabilities,
  fetchCostVarianceOptions,
  fetchCostVarianceReport,
  fetchCostVarianceReports,
  formatCostVarianceMoney,
  formatCostVarianceQuantity,
  formatCostVarianceShare,
  runCostVarianceAnalysis,
  sortCostVarianceDrivers,
  upsertCostVarianceBaseline,
} from './cost-variance-model';
import { fetchCostValuationScopes } from './cost-valuation-model';
import type { FinanceCostValuationScopeOptions } from '../../../../../packages/api-contracts/src/index';

/**
 * 12.4.1.3 成本差异分析。
 *
 * <p>蓝图 12-finance.md：「分析成本差异：实际成本 vs 预算/标准成本、采购价波动、头程成本波动、损耗差异、汇率影响，定位利润波动主因。」关系：「上游 = 12.4.1.2；下游 = 13 分析、6 采购（价格谈判依据）。<b>利润下滑时先看成本差异</b>，比看销售额更能定位问题。」
 *
 * <p><b>页面围绕三件事组织</b>：
 *
 * <ol>
 *   <li><b>标准成本 / 预算基线</b>（第一块）：本仓此前**没有**标准成本表 ——
 *       没有基线，「实际 vs 标准成本」这一类差异就<b>不可计算</b>。
 *       基线按 SKU / 类目 + 币种 + 生效区间版本化，<b>改基线 = 追加新版本</b>。</li>
 *   <li><b>执行差异分析</b>（第二块）：选范围 + 期间 + 报表币种 + 基线类型，跑一次分析。
 *       基数是 12.4.1.2 <b>已经算出来</b>的应结转金额。</li>
 *   <li><b>报表与归因</b>（第三块）：按<b>币种分组</b>呈现，
 *       每组给出五类差异的贡献排名与<b>主因</b>，外加该组的异常提示。</li>
 * </ol>
 *
 * <p><b>🔴 页面绝不显示 0 冒充「算不出来」</b>：不可比的差异显示「—」，
 * 并把原因（无基线 / 币种不一致 / 上游不可得）原样写出来。
 *
 * <p><b>🔴 多币种绝不相加</b>：每个币种一张卡片，页面<b>不提供</b>「全部差异合计」。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有分析过」。
 */
export function CostVarianceWorkspace() {
  const [caps, setCaps] = useState<FinanceCostVarianceCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceCostVarianceOptions | null>(null);
  const [scopes, setScopes] = useState<FinanceCostValuationScopeOptions | null>(null);
  const [baselines, setBaselines] = useState<FinanceCostVarianceBaselineRow[]>([]);
  const [reports, setReports] = useState<FinanceCostVarianceReportPage | null>(null);
  const [detail, setDetail] = useState<FinanceCostVarianceReportDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 基线维护
  const [baselineType, setBaselineType] = useState('STANDARD_COST');
  const [baselineDimension, setBaselineDimension] = useState('SKU');
  const [baselineRefId, setBaselineRefId] = useState('');
  const [baselineCode, setBaselineCode] = useState('');
  const [baselineName, setBaselineName] = useState('');
  const [baselineCurrency, setBaselineCurrency] = useState('USD');
  const [baselineUnitCost, setBaselineUnitCost] = useState('');
  const [baselineEffectiveFrom, setBaselineEffectiveFrom] = useState('');

  // 执行差异分析
  const [formScopeType, setFormScopeType] = useState('LEGAL_ENTITY');
  const [formScopeRefId, setFormScopeRefId] = useState('');
  const [formPeriodFrom, setFormPeriodFrom] = useState('');
  const [formPeriodTo, setFormPeriodTo] = useState('');
  const [formReportingCurrency, setFormReportingCurrency] = useState('USD');
  const [formBaselineType, setFormBaselineType] = useState('STANDARD_COST');
  const [formDimension, setFormDimension] = useState('SKU');

  const scopeOptions = useMemo(() => {
    if (!scopes) return [];
    return formScopeType === 'CATEGORY' ? scopes.categories : scopes.legalEntities;
  }, [scopes, formScopeType]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, s, b, r] = await Promise.all([
        fetchCostVarianceCapabilities(),
        fetchCostVarianceOptions(),
        fetchCostValuationScopes(),
        fetchCostVarianceBaselines(),
        fetchCostVarianceReports(),
      ]);
      setCaps(c);
      setOptions(o);
      setScopes(s);
      setBaselines(b.items);
      setReports(r);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载成本差异分析失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (id: string) => {
    try {
      const result = await fetchCostVarianceReport(id);
      setDetail(result);
      setActionError('');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '加载报表明细失败');
    }
  }, []);

  const submitBaseline = useCallback(async () => {
    if (baselineDimension === 'SKU' && !baselineRefId) {
      setActionError('按 SKU 建基线必须给出 SKU id（dimensionRefId）');
      return;
    }
    if (baselineDimension === 'CATEGORY' && !baselineCode) {
      setActionError('按类目建基线必须给出类目编码（dimensionCode）');
      return;
    }
    if (!baselineCurrency || baselineCurrency.length !== 3) {
      setActionError('基线币种必须是三位大写字母（ISO 4217）');
      return;
    }
    const unitCost = Number(baselineUnitCost);
    if (!baselineUnitCost || Number.isNaN(unitCost) || unitCost <= 0) {
      setActionError('基线单位成本必须大于 0（写成 0 等于「这个 SKU 白送」）');
      return;
    }
    if (!baselineEffectiveFrom) {
      setActionError('必须给出基线生效时间');
      return;
    }
    try {
      const row = await upsertCostVarianceBaseline({
        baselineType,
        dimension: baselineDimension,
        dimensionRefId: baselineDimension === 'SKU' ? baselineRefId : null,
        dimensionCode: baselineCode || null,
        dimensionName: baselineName || null,
        currency: baselineCurrency.toUpperCase(),
        unitCost,
        effectiveFrom: new Date(baselineEffectiveFrom).toISOString(),
        note: null,
      });
      setActionError('');
      setNotice(
        `已登记第 ${row.version} 版${costVarianceBaselineTypeLabel(row.baselineType)}：` +
          `${row.dimensionCode || row.dimensionName || '（未命名）'}，` +
          `单价 ${formatCostVarianceMoney(row.unitCost)} ${row.currency}，` +
          `生效区间 ${costVariancePeriodLabel(row.effectiveFrom, row.effectiveTo)}。` +
          '旧版本**未被改写**（历史差异分析仍按当时那版算）。',
      );
      setBaselineUnitCost('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记基线失败');
    }
  }, [
    baselineType,
    baselineDimension,
    baselineRefId,
    baselineCode,
    baselineName,
    baselineCurrency,
    baselineUnitCost,
    baselineEffectiveFrom,
    load,
  ]);

  const submitAnalysis = useCallback(async () => {
    if (!formScopeRefId) {
      setActionError('必须选择一个计价范围（法人或品类）');
      return;
    }
    if (!formPeriodFrom || !formPeriodTo) {
      setActionError('必须给出分析期间（起止都要）');
      return;
    }
    try {
      const result = await runCostVarianceAnalysis({
        scopeType: formScopeType,
        scopeRefId: formScopeRefId,
        scopeCode: null,
        scopeName: null,
        periodFrom: new Date(formPeriodFrom).toISOString(),
        periodTo: new Date(formPeriodTo).toISOString(),
        reportingCurrency: formReportingCurrency.toUpperCase(),
        baselineType: formBaselineType,
        dimension: formDimension,
        note: null,
      });
      setDetail(result);
      setActionError('');
      setNotice(
        `已生成差异分析报表（${result.currencyGroups.length} 个币种分组）：` +
          result.currencyGroups
            .map((g) => `${g.currency} 主因 ${costVariancePrimaryDriverLabel(g.drivers) || '无'}`)
            .join('；') +
          '。',
      );
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '执行差异分析失败');
    }
  }, [
    formScopeType,
    formScopeRefId,
    formPeriodFrom,
    formPeriodTo,
    formReportingCurrency,
    formBaselineType,
    formDimension,
    load,
  ]);

  if (loading && !caps) {
    return (
      <div className="p-6">
        <p className="text-copy-13 text-muted-foreground">正在加载成本差异分析…</p>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <div className="p-6">
        <Notice tone="danger" title="成本差异分析加载失败">
          {error}
          <Button className="ml-3" onClick={() => void load()}>
            重试
          </Button>
        </Notice>
      </div>
    );
  }

  const reportItems = reports?.items ?? [];
  const groups = detail?.currencyGroups ?? [];

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="成本差异分析"
        description="把 12.4.1.2 算出来的应结转金额拆成五类差异：实际 vs 预算/标准成本、采购价波动、头程成本波动、损耗差异、汇率影响；定位利润波动主因。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {/* 边界提示：基数来自 12.4.1.2，多币种不相加，无基线不可比。 */}
      <Notice tone="info" title="三个读数纪律">
        <ul className="list-disc space-y-1 pl-5">
          <li>
            <strong>基数是 12.4.1.2 已经算出来的应结转金额</strong>
            —— 本模块不重算一遍成本，也不改库存。
          </li>
          <li>
            <strong>多币种绝不相加</strong> —— 每个币种一张卡片，页面没有「全部差异合计」这一格。
          </li>
          <li>
            <strong>无基线 ≠ 差异为 0</strong> —— 缺基线 / 币种不一致 / 上游不可得时，
            那一格显示「—」并写明原因。
          </li>
        </ul>
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
        <StatCard label="标准成本 / 预算基线" value={baselines.length} />
        <StatCard label="差异分析报表" value={reports?.total ?? 0} />
        <StatCard label="最近报表币种数" value={detail ? detail.currencyGroups.length : 0} />
        <StatCard label="最近报表异常提示" value={detail?.alertCount ?? 0} />
      </div>

      {/* 1. 基线维护 */}
      <SectionCard
        title="标准成本 / 预算基线"
        description="本仓此前没有标准成本表 —— 没有基线，「实际 vs 预算/标准成本」这一类差异就不可计算。基线按 SKU 或类目 + 币种 + 生效区间版本化：改基线 = 追加新版本，旧版本不被改写。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <label className="text-copy-12">
            <span className="text-muted-foreground">基线类型</span>
            <select
              className="border-control-border bg-card mt-1 h-9 w-full rounded-md border px-2 text-copy-13"
              value={baselineType}
              onChange={(e) => setBaselineType(e.target.value)}
            >
              <option value="STANDARD_COST">标准成本</option>
              <option value="BUDGET">预算</option>
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">维度</span>
            <select
              className="border-control-border bg-card mt-1 h-9 w-full rounded-md border px-2 text-copy-13"
              value={baselineDimension}
              onChange={(e) => setBaselineDimension(e.target.value)}
            >
              <option value="SKU">按 SKU</option>
              <option value="CATEGORY">按类目</option>
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">
              {baselineDimension === 'SKU' ? 'SKU id' : '类目编码'}
            </span>
            <Input
              className="mt-1"
              value={baselineDimension === 'SKU' ? baselineRefId : baselineCode}
              onChange={(e) =>
                baselineDimension === 'SKU'
                  ? setBaselineRefId(e.target.value)
                  : setBaselineCode(e.target.value)
              }
              placeholder={baselineDimension === 'SKU' ? 'UUID' : '例如 CATEGORY-001'}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">名称</span>
            <Input
              className="mt-1"
              value={baselineName}
              onChange={(e) => setBaselineName(e.target.value)}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">币种</span>
            <Input
              className="mt-1"
              value={baselineCurrency}
              onChange={(e) => setBaselineCurrency(e.target.value.toUpperCase())}
              maxLength={3}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">单位成本</span>
            <Input
              className="mt-1"
              value={baselineUnitCost}
              onChange={(e) => setBaselineUnitCost(e.target.value)}
              placeholder="大于 0"
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">生效起点</span>
            <Input
              className="mt-1"
              type="datetime-local"
              value={baselineEffectiveFrom}
              onChange={(e) => setBaselineEffectiveFrom(e.target.value)}
            />
          </label>
        </div>
        <div className="mt-3 flex items-center gap-2">
          <Button onClick={() => void submitBaseline()}>登记 / 切换基线</Button>
          <span className="text-copy-12 text-muted-foreground">
            切换 = 追加新版本；旧版本被收口并置为「已被接替」，历史差异分析仍按当时那版算。
          </span>
        </div>

        <div className="mt-4 overflow-x-auto">
          {baselines.length === 0 ? (
            <EmptyState
              title="还没有登记基线"
              description="没有基线，「实际 vs 预算/标准成本」这一类差异不可计算（**不是 0**）。先登记一版标准成本。"
            />
          ) : (
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3">对象</th>
                  <th className="py-2 pr-3">类型</th>
                  <th className="py-2 pr-3">币种</th>
                  <th className="py-2 pr-3">单位成本</th>
                  <th className="py-2 pr-3">版本</th>
                  <th className="py-2 pr-3">生效区间</th>
                  <th className="py-2 pr-3">状态</th>
                </tr>
              </thead>
              <tbody>
                {baselines.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-2 pr-3">
                      {row.dimensionCode || row.dimensionName || '—'}
                      <span className="ml-1 text-muted-foreground">
                        {costVarianceDimensionLabel(row.dimension)}
                      </span>
                    </td>
                    <td className="py-2 pr-3">{costVarianceBaselineTypeLabel(row.baselineType)}</td>
                    <td className="py-2 pr-3">{row.currency}</td>
                    <td className="py-2 pr-3">{formatCostVarianceMoney(row.unitCost)}</td>
                    <td className="py-2 pr-3">v{row.version}</td>
                    <td className="py-2 pr-3">
                      {costVariancePeriodLabel(row.effectiveFrom, row.effectiveTo)}
                    </td>
                    <td className="py-2 pr-3">
                      <Badge
                        tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}
                      >
                        {costVarianceBaselineStatusLabel(row.status)}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </SectionCard>

      {/* 2. 执行差异分析 */}
      <SectionCard
        title="执行差异分析"
        description="基数取 12.4.1.2 已完成的成本计算批次；期间是左闭右开的（起含、止不含）。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <label className="text-copy-12">
            <span className="text-muted-foreground">计价范围</span>
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
            <span className="text-muted-foreground">报表币种（汇率影响折算到它）</span>
            <Input
              className="mt-1"
              value={formReportingCurrency}
              onChange={(e) => setFormReportingCurrency(e.target.value.toUpperCase())}
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
            <span className="text-muted-foreground">基线类型</span>
            <select
              className="border-control-border bg-card mt-1 h-9 w-full rounded-md border px-2 text-copy-13"
              value={formBaselineType}
              onChange={(e) => setFormBaselineType(e.target.value)}
            >
              <option value="STANDARD_COST">标准成本</option>
              <option value="BUDGET">预算</option>
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">基线维度</span>
            <select
              className="border-control-border bg-card mt-1 h-9 w-full rounded-md border px-2 text-copy-13"
              value={formDimension}
              onChange={(e) => setFormDimension(e.target.value)}
            >
              <option value="SKU">按 SKU</option>
              <option value="CATEGORY">按类目</option>
            </select>
          </label>
        </div>
        <div className="mt-3 flex items-center gap-2">
          <Button onClick={() => void submitAnalysis()}>执行差异分析</Button>
          <span className="text-copy-12 text-muted-foreground">
            没有已完成的成本计算批次时会被明确拒绝 —— 没有基数就没有差异可言。
          </span>
        </div>
      </SectionCard>

      {/* 3. 报表与归因 */}
      <SectionCard
        title="差异分析报表"
        description="按币种分组呈现：五类差异各自可看，给出贡献排名与主因。汇率影响是总差异的构成部分，其余三类是独立观察到的驱动因素 —— 五类不可相加。"
      >
        {reportItems.length === 0 ? (
          <EmptyState
            title="还没有差异分析报表"
            description="选好范围、期间与报表币种后跑一次分析。报表一经生成不可改写 —— 历史即证据。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3">期间</th>
                  <th className="py-2 pr-3">范围</th>
                  <th className="py-2 pr-3">计价方法快照</th>
                  <th className="py-2 pr-3">币种</th>
                  <th className="py-2 pr-3">SKU</th>
                  <th className="py-2 pr-3">可比 / 无基线</th>
                  <th className="py-2 pr-3">异常提示</th>
                  <th className="py-2 pr-3">状态</th>
                  <th className="py-2 pr-3" />
                </tr>
              </thead>
              <tbody>
                {reportItems.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-2 pr-3">
                      {costVariancePeriodLabel(row.periodFrom, row.periodTo)}
                    </td>
                    <td className="py-2 pr-3">{row.scopeName || row.scopeCode || '—'}</td>
                    <td className="py-2 pr-3">
                      {row.valuationMethod} v{row.valuationMethodVersion}
                    </td>
                    <td className="py-2 pr-3">{row.currencies || '—'}</td>
                    <td className="py-2 pr-3">{row.skuCount}</td>
                    <td className="py-2 pr-3">
                      {row.comparableSkuCount} / {row.noBaselineSkuCount}
                    </td>
                    <td className="py-2 pr-3">{row.alertCount}</td>
                    <td className="py-2 pr-3">
                      <Badge tone={row.status === 'COMPLETED' ? 'success' : 'warning'}>
                        {costVarianceReportStatusLabel(row.status)}
                      </Badge>
                    </td>
                    <td className="py-2 pr-3">
                      <Button size="sm" onClick={() => void openDetail(row.id)}>
                        打开
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {detail ? (
        <SectionCard
          title="报表明细（按币种分组）"
          description={`基数来自 12.4.1.2 的 ${detail.report.runCount} 个批次；计价方法取批次行上的快照（${detail.report.valuationMethod} v${detail.report.valuationMethodVersion}）。`}
        >
          <Notice tone="info" title="口径说明">
            {detail.report.basisNote}
            <br />
            {detail.report.sourceNote}
            <br />
            {detail.note}
          </Notice>

          {groups.length === 0 ? (
            <EmptyState
              title="这张报表没有可展示的币种分组"
              description="请检查该范围该期间是否有已完成的成本计算批次。"
            />
          ) : (
            <div className="space-y-4">
              {groups.map((group) => (
                <div key={group.currency} className="border-control-border rounded-md border p-3">
                  <div className="flex flex-wrap items-baseline gap-3">
                    <span className="text-copy-15 font-medium">{group.currency}</span>
                    <span className="text-copy-12 text-muted-foreground">
                      实际 {formatCostVarianceMoney(group.actualCostAmount)} / 标准{' '}
                      {formatCostVarianceMoney(group.standardCostAmount)} / 总差异{' '}
                      {formatCostVarianceMoney(group.totalVarianceAmount)} / 汇率影响{' '}
                      {formatCostVarianceMoney(group.fxImpactAmount)}
                    </span>
                    <span className="text-copy-12 text-muted-foreground">
                      {costVarianceDirectionLabel(group.totalVarianceAmount)}
                    </span>
                    <Badge tone="neutral">
                      主因：{costVariancePrimaryDriverLabel(group.drivers) || '无'}
                    </Badge>
                  </div>
                  <p className="mt-1 text-copy-12 text-muted-foreground">{group.note}</p>

                  <div className="mt-3 overflow-x-auto">
                    <table className="w-full text-left text-copy-13">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-1 pr-3">排名</th>
                          <th className="py-1 pr-3">差异类别</th>
                          <th className="py-1 pr-3">金额</th>
                          <th className="py-1 pr-3">占比</th>
                          <th className="py-1 pr-3">可比性</th>
                          <th className="py-1 pr-3">口径 / 原因</th>
                        </tr>
                      </thead>
                      <tbody>
                        {sortCostVarianceDrivers(group.drivers).map((driver) => (
                          <tr key={`${group.currency}-${driver.driver}`} className="border-t">
                            <td className="py-1 pr-3">
                              {driver.rank === null ? '—' : `#${driver.rank}`}
                              {driver.isPrimary ? ' ★主因' : ''}
                            </td>
                            <td className="py-1 pr-3">
                              {driver.driverLabel ?? costVarianceDriverLabel(driver.driver)}
                            </td>
                            <td className="py-1 pr-3">{formatCostVarianceMoney(driver.amount)}</td>
                            <td className="py-1 pr-3">{formatCostVarianceShare(driver.contributionShare)}</td>
                            <td className="py-1 pr-3">
                              {driver.comparability === 'COMPARABLE' ? (
                                <Badge tone="success">
                                  {costVarianceComparabilityLabel(driver.comparability)}
                                </Badge>
                              ) : (
                                <Badge tone="warning">
                                  <TriangleAlert className="mr-1 h-3 w-3" />
                                  {costVarianceComparabilityLabel(driver.comparability)}
                                </Badge>
                              )}
                            </td>
                            <td className="py-1 pr-3 text-muted-foreground">
                              {driver.comparableReason || driver.basis}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>

                  {group.alerts.length > 0 ? (
                    <div className="mt-3">
                      <p className="text-copy-12 text-muted-foreground">异常提示</p>
                      <ul className="mt-1 space-y-1">
                        {group.alerts.map((alert) => (
                          <li key={alert.id} className="text-copy-12">
                            <Badge
                              tone={
                                alert.severity === 'CRITICAL'
                                  ? 'danger'
                                  : alert.severity === 'WARNING'
                                    ? 'warning'
                                    : 'neutral'
                              }
                            >
                              {costVarianceSeverityLabel(alert.severity)}
                            </Badge>
                            <span className="ml-2">{alert.message}</span>
                          </li>
                        ))}
                      </ul>
                    </div>
                  ) : null}
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      ) : null}

      {/* 4. 能力声明 */}
      {caps ? (
        <SectionCard title="能力声明" description="哪些上游真接通、哪些还没交付、归谁。">
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3">模块</th>
                  <th className="py-2 pr-3">能力</th>
                  <th className="py-2 pr-3">状态</th>
                  <th className="py-2 pr-3">依据</th>
                  <th className="py-2 pr-3">说明</th>
                </tr>
              </thead>
              <tbody>
                {caps.capabilities.map((c) => (
                  <tr key={`${c.module}-${c.name}`} className="border-t">
                    <td className="py-2 pr-3">{c.module}</td>
                    <td className="py-2 pr-3">{c.name}</td>
                    <td className="py-2 pr-3">
                      <Badge tone={c.available ? 'success' : 'danger'}>
                        {c.available ? '已交付' : '未交付'}
                      </Badge>
                    </td>
                    <td className="py-2 pr-3 text-muted-foreground">{c.basis}</td>
                    <td className="py-2 pr-3">{c.detail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {caps.downstreamGaps.length > 0 ? (
            <ul className="mt-3 space-y-1">
              {caps.downstreamGaps.map((g) => (
                <li key={`${g.module}-${g.name}`} className="text-copy-12">
                  <Badge tone="warning">下游缺口</Badge>
                  <span className="ml-2">
                    {g.module} {g.name}（{g.ownerService}）：{g.detail}
                  </span>
                </li>
              ))}
            </ul>
          ) : null}
        </SectionCard>
      ) : null}

      {options ? null : <p className="text-copy-12 text-muted-foreground">筛选项未加载</p>}
    </div>
  );
}
