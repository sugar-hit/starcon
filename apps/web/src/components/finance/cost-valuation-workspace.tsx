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
  FinanceCostValuationCapabilities,
  FinanceCostValuationMethodPage,
  FinanceCostValuationMethodRow,
  FinanceCostValuationOptions,
  FinanceCostValuationScopeOption,
  FinanceCostValuationScopeOptions,
  FinanceCostValuationSpecifiedCostRow,
  FinanceCostValuationTrialRow,
  FinanceCostValuationVersionComparison,
} from '../../../../../packages/api-contracts/src/index';
import {
  fetchCostValuationCapabilities,
  fetchCostValuationComparison,
  fetchCostValuationMethods,
  fetchCostValuationOptions,
  fetchCostValuationScopes,
  fetchCostValuationSpecifiedCosts,
  fetchCostValuationTrials,
  fetchCostValuationVersions,
  formatValuationMoney,
  formatValuationQuantity,
  recalculationStatusLabel,
  runCostValuationTrial,
  sortTrialResults,
  switchCostValuationMethod,
  upsertCostValuationSpecifiedCost,
  valuationDifferenceLabel,
  valuationEffectiveWindow,
  valuationMethodLabel,
  valuationScopeTypeLabel,
  valuationStatusLabel,
} from './cost-valuation-model';

/**
 * 12.4.1.1 计价方法配置。
 *
 * <p>蓝图 12-finance.md：「配置成本计算方法（先进先出/移动加权平均/批次成本/指定成本法），<b>按法人或品类设置</b>，<b>确定生效时间</b>。」输入：「计价方法选择；生效时间；适用范围。」输出：「生效的成本方法配置。」关系：「上游 = 用户会计政策决定；下游 = 12.4.1.2、13 利润。<b>系统提供选项与试算，不替用户决定政策</b>；<b>切换方法需重算并留版本对比</b>。」
 *
 * <p><b>页面围绕四件事组织</b>：
 *
 * <ol>
 *   <li><b>方法选项</b>（第一块）：四个计价方法连同各自的**口径说明**摆出来。
 *       系统**只提供选项，不替用户决定政策** —— 页面**不标任何「推荐方法」**，
 *       计价方法是租户自己的会计政策。</li>
 *   <li><b>计价方法配置</b>（第二块）：按法人 / 品类设置，带生效时间。
 *       切换方法 = **追加新版本**，旧版本被收口留作版本对比。</li>
 *   <li><b>版本对比</b>（第三块）：逐字段给出「切换前 / 切换后」的差异，
 *       并明确提示<b>需要 12.4.1.2 重算</b>（重算本身不在本模块）。</li>
 *   <li><b>指定成本 + 试算</b>（第四、五块）：指定成本法要用的单价（按 SKU + 币种），
 *       以及用**真实成本层**把四种方法的结果并排算出来供对比。</li>
 * </ol>
 *
 * <p><b>🔴 页面绝不显示任何「已重算」结论</b>：重算属 12.4.1.2，**未交付**。
 * 页面只显示「待重算（12.4.1.2）」这个**标记**。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有配置过计价方法」。
 */
export function CostValuationWorkspace() {
  const [caps, setCaps] = useState<FinanceCostValuationCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceCostValuationOptions | null>(null);
  const [scopes, setScopes] = useState<FinanceCostValuationScopeOptions | null>(null);
  const [methods, setMethods] = useState<FinanceCostValuationMethodPage | null>(null);
  const [versions, setVersions] = useState<FinanceCostValuationMethodRow[]>([]);
  const [comparison, setComparison] = useState<FinanceCostValuationVersionComparison | null>(null);
  const [specifiedCosts, setSpecifiedCosts] = useState<FinanceCostValuationSpecifiedCostRow[]>([]);
  const [trials, setTrials] = useState<FinanceCostValuationTrialRow[]>([]);
  const [trial, setTrial] = useState<FinanceCostValuationTrialRow | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [scopeType, setScopeType] = useState('');
  const [scopeRefId, setScopeRefId] = useState('');
  const [methodFilter, setMethodFilter] = useState('');
  const [keyword, setKeyword] = useState('');

  // 切换方法
  const [formScopeType, setFormScopeType] = useState('LEGAL_ENTITY');
  const [formScopeRefId, setFormScopeRefId] = useState('');
  const [formMethod, setFormMethod] = useState('FIFO');
  const [formCurrency, setFormCurrency] = useState('USD');
  const [formEffectiveFrom, setFormEffectiveFrom] = useState('');
  const [formNote, setFormNote] = useState('');

  // 版本对比
  const [compareFrom, setCompareFrom] = useState('');
  const [compareTo, setCompareTo] = useState('');

  // 指定成本
  const [skuId, setSkuId] = useState('');
  const [skuCode, setSkuCode] = useState('');
  const [specifiedCurrency, setSpecifiedCurrency] = useState('USD');
  const [specifiedUnitCost, setSpecifiedUnitCost] = useState('');
  const [specifiedEffectiveFrom, setSpecifiedEffectiveFrom] = useState('');

  // 试算
  const [trialScopeType, setTrialScopeType] = useState('LEGAL_ENTITY');
  const [trialScopeRefId, setTrialScopeRefId] = useState('');
  const [trialSkuId, setTrialSkuId] = useState('');
  const [trialSkuCode, setTrialSkuCode] = useState('');
  const [trialCurrency, setTrialCurrency] = useState('USD');
  const [trialQuantity, setTrialQuantity] = useState('');
  const [trialBatchRef, setTrialBatchRef] = useState('');

  const scopeOptions: FinanceCostValuationScopeOption[] = useMemo(() => {
    if (!scopes) return [];
    return scopeType === 'CATEGORY' ? scopes.categories : scopes.legalEntities;
  }, [scopes, scopeType]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, s, m, sp, tr] = await Promise.all([
        fetchCostValuationCapabilities(),
        fetchCostValuationOptions(),
        fetchCostValuationScopes(),
        fetchCostValuationMethods({
          scopeType: scopeType || undefined,
          scopeRefId: scopeRefId || undefined,
          valuationMethod: methodFilter || undefined,
          keyword: keyword || undefined,
        }),
        fetchCostValuationSpecifiedCosts(),
        fetchCostValuationTrials(),
      ]);
      setCaps(c);
      setOptions(o);
      setScopes(s);
      setMethods(m);
      setSpecifiedCosts(sp.items);
      setTrials(tr.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载计价方法配置失败');
    } finally {
      setLoading(false);
    }
  }, [scopeType, scopeRefId, methodFilter, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  // 版本历史：选定范围后拉。
  const loadVersions = useCallback(async () => {
    if (!scopeType || !scopeRefId) {
      setVersions([]);
      setComparison(null);
      return;
    }
    try {
      const list = await fetchCostValuationVersions(scopeType, scopeRefId);
      setVersions(list);
      const newest = list[0];
      const previous = list[1];
      if (newest && previous) {
        setCompareFrom(String(previous.version));
        setCompareTo(String(newest.version));
      } else {
        setCompareFrom('');
        setCompareTo('');
        setComparison(null);
      }
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '加载版本历史失败');
    }
  }, [scopeType, scopeRefId]);

  useEffect(() => {
    void loadVersions();
  }, [loadVersions]);

  const runComparison = useCallback(async () => {
    if (!scopeType || !scopeRefId || !compareFrom || !compareTo) return;
    try {
      const result = await fetchCostValuationComparison(
        scopeType,
        scopeRefId,
        Number(compareFrom),
        Number(compareTo),
      );
      setComparison(result);
      setActionError('');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '版本对比失败');
    }
  }, [scopeType, scopeRefId, compareFrom, compareTo]);

  const submitMethod = useCallback(async () => {
    if (!formScopeRefId) {
      setActionError('必须选择一个计价范围（法人或品类）');
      return;
    }
    if (!formEffectiveFrom) {
      setActionError('必须给出生效时间：没有生效时间的计价方法等于没有');
      return;
    }
    try {
      const scope = scopeOptions.find((s) => s.refId === formScopeRefId);
      const row = await switchCostValuationMethod({
        scopeType: formScopeType,
        scopeRefId: formScopeRefId,
        scopeCode: scope?.code ?? '',
        scopeName: scope?.name ?? '',
        valuationMethod: formMethod,
        currency: formCurrency,
        effectiveFrom: new Date(formEffectiveFrom).toISOString(),
        note: formNote,
      });
      setActionError('');
      setNotice(
        `已登记「${valuationMethodLabel(row.valuationMethod)}」第 ${row.version} 版（${row.scopeName || row.scopeCode}），生效于 ${row.effectiveFrom.slice(0, 10)}。切换方法需要 12.4.1.2 重算，历史成本不会被追溯改写。`,
      );
      await load();
      await loadVersions();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记计价方法失败');
    }
  }, [formScopeType, formScopeRefId, formMethod, formCurrency, formEffectiveFrom, formNote, scopeOptions, load, loadVersions]);

  const submitSpecifiedCost = useCallback(async () => {
    if (!skuId) {
      setActionError('必须填写 SKU ID');
      return;
    }
    if (!specifiedUnitCost || Number(specifiedUnitCost) <= 0) {
      setActionError('指定单价必须大于 0：成本为 0 与「尚未维护」是两回事');
      return;
    }
    if (!specifiedEffectiveFrom) {
      setActionError('必须给出生效时间');
      return;
    }
    try {
      await upsertCostValuationSpecifiedCost({
        skuId,
        skuCode,
        currency: specifiedCurrency,
        unitCost: Number(specifiedUnitCost),
        effectiveFrom: new Date(specifiedEffectiveFrom).toISOString(),
        note: '',
      });
      setActionError('');
      setNotice('已维护指定成本单价（同 SKU + 币种已有生效值时自动追加新版本）。');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '维护指定成本失败');
    }
  }, [skuId, skuCode, specifiedCurrency, specifiedUnitCost, specifiedEffectiveFrom, load]);

  const submitTrial = useCallback(async () => {
    if (!trialScopeRefId) {
      setActionError('试算必须选择一个计价范围');
      return;
    }
    if (!trialSkuId) {
      setActionError('试算必须填写 SKU ID');
      return;
    }
    if (!trialQuantity || Number(trialQuantity) <= 0) {
      setActionError('试算数量必须大于 0');
      return;
    }
    try {
      const result = await runCostValuationTrial({
        scopeType: trialScopeType,
        scopeRefId: trialScopeRefId,
        skuId: trialSkuId,
        skuCode: trialSkuCode,
        currency: trialCurrency,
        issueQuantity: Number(trialQuantity),
        batchRef: trialBatchRef || null,
        effectiveAt: null,
      });
      setTrial(result);
      setActionError('');
      setNotice('试算完成：四种方法在同一批真实成本层上的结果见下方对比。');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '试算失败');
    }
  }, [trialScopeType, trialScopeRefId, trialSkuId, trialSkuCode, trialCurrency, trialQuantity, trialBatchRef, load]);

  if (loading && !caps) {
    return (
      <div className="p-6">
        <p className="text-copy-13 text-muted-foreground">正在加载计价方法配置…</p>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <div className="p-6">
        <Notice tone="danger" title="计价方法配置加载失败">
          {error}
          <Button className="ml-3" onClick={() => void load()}>
            重试
          </Button>
        </Notice>
      </div>
    );
  }

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="计价方法配置"
        description="配置成本计算方法（先进先出 / 移动加权平均 / 批次成本 / 指定成本法），按法人或品类设置，确定生效时间。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {/* 边界提示：系统提供选项与试算，不替用户决定政策；重算属 12.4.1.2。 */}
      <Notice tone="info" title="系统只提供选项与试算，不替用户决定计价政策">
        计价方法是租户自己的会计政策，本系统**不预置任何一行配置、也不标任何「推荐方法」**。
        切换方法会**追加一个新版本**并把旧版本收口留作对比 —— 历史成本不会被追溯改写；
        切换后需要 <strong>12.4.1.2 成本计算执行</strong> 重算，本模块只做标记，不执行重算。
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
        <StatCard label="生效中的配置" value={methods?.activeCount ?? 0} />
        <StatCard label="配置版本总数" value={methods?.total ?? 0} />
        <StatCard label="指定成本单价" value={specifiedCosts.length} />
        <StatCard label="试算记录" value={trials.length} />
      </div>

      {/* 1. 方法选项 */}
      <SectionCard
        title="可用的计价方法（系统提供选项）"
        description="四个方法的口径说明原样展示。系统不替用户选，落库的配置由租户自己决定。"
      >
        <div className="grid gap-3 md:grid-cols-2">
          {(options?.methods ?? []).map((option) => (
            <div key={option.value} className="rounded-md border p-3">
              <div className="flex items-center justify-between">
                <span className="text-copy-13 font-medium">{option.label}</span>
                <Badge tone="neutral">{option.value}</Badge>
              </div>
              <p className="mt-2 text-copy-12 text-muted-foreground">{option.basis}</p>
            </div>
          ))}
        </div>
      </SectionCard>

      {/* 2. 计价方法配置 */}
      <SectionCard
        title="计价方法配置"
        description="按法人或品类设置，带生效时间。切换方法 = 追加新版本（旧版本被取代并留作对比）。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
              value={scopeType}
              onChange={(e) => setScopeType(e.target.value)}
              aria-label="按范围类型筛选"
            >
              <option value="">全部范围类型</option>
              <option value="LEGAL_ENTITY">按法人</option>
              <option value="CATEGORY">按品类</option>
            </select>
            <Input
              className="w-40"
              placeholder="范围编码 / 名称"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              aria-label="按关键字筛选"
            />
          </div>
        }
      >
        {methods && methods.items.length === 0 ? (
          <EmptyState
            title="还没有配置过计价方法"
            description="计价方法是租户自己的会计政策，系统不预置任何一行。选好范围与生效时间后登记第一条配置。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">范围</th>
                  <th className="p-2">计价方法</th>
                  <th className="p-2">币种</th>
                  <th className="p-2">生效区间</th>
                  <th className="p-2">版本</th>
                  <th className="p-2">状态</th>
                  <th className="p-2">重算</th>
                </tr>
              </thead>
              <tbody>
                {(methods?.items ?? []).map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="p-2">
                      <div>{row.scopeName || row.scopeCode}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {valuationScopeTypeLabel(row.scopeType)} · {row.scopeCode}
                      </div>
                    </td>
                    <td className="p-2">{valuationMethodLabel(row.valuationMethod)}</td>
                    <td className="p-2">{row.currency}</td>
                    <td className="p-2">
                      {valuationEffectiveWindow(row.effectiveFrom, row.effectiveTo)}
                    </td>
                    <td className="p-2">第 {row.version} 版</td>
                    <td className="p-2">
                      <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                        {valuationStatusLabel(row.status)}
                      </Badge>
                    </td>
                    <td className="p-2">
                      <Badge tone={row.recalculationStatus === 'PENDING' ? 'warning' : 'neutral'}>
                        {recalculationStatusLabel(row.recalculationStatus)}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 登记 / 切换方法 */}
      <SectionCard
        title="登记 / 切换计价方法"
        description="同一范围已有生效配置时，本次登记会追加一个新版本并把旧版本收口。"
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
            <span className="text-muted-foreground">生效时间</span>
            <Input
              className="mt-1"
              type="datetime-local"
              value={formEffectiveFrom}
              onChange={(e) => setFormEffectiveFrom(e.target.value)}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">备注</span>
            <Input
              className="mt-1"
              value={formNote}
              onChange={(e) => setFormNote(e.target.value)}
              placeholder="例如：2026 财年政策调整"
            />
          </label>
        </div>
        <div className="mt-3 flex items-center gap-2">
          <Button onClick={() => void submitMethod()}>登记 / 切换</Button>
          {scopes?.legalEntityNote ? (
            <span className="text-copy-12 text-warning">法人清单：{scopes.legalEntityNote}</span>
          ) : null}
          {scopes?.categoryNote ? (
            <span className="text-copy-12 text-warning">品类清单：{scopes.categoryNote}</span>
          ) : null}
        </div>
      </SectionCard>

      {/* 3. 版本对比 */}
      <SectionCard
        title="版本对比"
        description="逐字段给出「切换前 / 切换后」的差异。对比的是配置本身，重算结果属 12.4.1.2。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-copy-12">
            <span className="text-muted-foreground">范围类型</span>
            <select
              className="border-control-border bg-card mt-1 h-9 rounded-md border px-2 text-copy-13"
              value={scopeType}
              onChange={(e) => {
                setScopeType(e.target.value);
                setScopeRefId('');
              }}
            >
              <option value="">全部</option>
              <option value="LEGAL_ENTITY">按法人</option>
              <option value="CATEGORY">按品类</option>
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">范围</span>
            <select
              className="border-control-border bg-card mt-1 h-9 rounded-md border px-2 text-copy-13"
              value={scopeRefId}
              onChange={(e) => setScopeRefId(e.target.value)}
            >
              <option value="">请选择</option>
              {(scopes?.legalEntities ?? []).map((s) => (
                <option key={s.refId} value={s.refId}>
                  {s.name || s.code}
                </option>
              ))}
              {(scopes?.categories ?? []).map((s) => (
                <option key={s.refId} value={s.refId}>
                  {s.name || s.code}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">旧版本</span>
            <select
              className="border-control-border bg-card mt-1 h-9 rounded-md border px-2 text-copy-13"
              value={compareFrom}
              onChange={(e) => setCompareFrom(e.target.value)}
            >
              <option value="">请选择</option>
              {versions.map((v) => (
                <option key={v.id} value={v.version}>
                  第 {v.version} 版（{valuationMethodLabel(v.valuationMethod)}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">新版本</span>
            <select
              className="border-control-border bg-card mt-1 h-9 rounded-md border px-2 text-copy-13"
              value={compareTo}
              onChange={(e) => setCompareTo(e.target.value)}
            >
              <option value="">请选择</option>
              {versions.map((v) => (
                <option key={v.id} value={v.version}>
                  第 {v.version} 版（{valuationMethodLabel(v.valuationMethod)}）
                </option>
              ))}
            </select>
          </label>
          <Button onClick={() => void runComparison()} disabled={!compareFrom || !compareTo}>
            对比
          </Button>
        </div>

        {comparison ? (
          <div className="mt-4 space-y-3">
            <Notice tone={comparison.methodChanged ? 'warning' : 'info'} title="对比结论">
              {comparison.recalculationNote}
            </Notice>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-copy-12 text-muted-foreground">
                  <tr>
                    <th className="p-2">字段</th>
                    <th className="p-2">切换前（第 {comparison.from.version} 版）</th>
                    <th className="p-2">切换后（第 {comparison.to.version} 版）</th>
                    <th className="p-2">是否变化</th>
                  </tr>
                </thead>
                <tbody>
                  {comparison.differences.map((d) => (
                    <tr key={d.field} className="border-t">
                      <td className="p-2">{d.fieldLabel}</td>
                      <td className="p-2">{d.fromValue || '—'}</td>
                      <td className="p-2">{d.toValue || '—'}</td>
                      <td className="p-2">
                        {d.changed ? (
                          <Badge tone="warning">已变化</Badge>
                        ) : (
                          <Badge tone="neutral">未变化</Badge>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ) : versions.length === 0 && scopeRefId ? (
          <p className="mt-3 text-copy-12 text-muted-foreground">该范围还没有配置版本。</p>
        ) : null}
      </SectionCard>

      {/* 4. 指定成本 */}
      <SectionCard
        title="指定成本单价"
        description="指定成本法按人工维护的单价结转，按 SKU + 币种维护，改价即追加新版本。"
      >
        <div className="grid gap-3 md:grid-cols-5">
          <label className="text-copy-12">
            <span className="text-muted-foreground">SKU ID</span>
            <Input className="mt-1" value={skuId} onChange={(e) => setSkuId(e.target.value)} />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">SKU 编码</span>
            <Input className="mt-1" value={skuCode} onChange={(e) => setSkuCode(e.target.value)} />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">币种</span>
            <Input
              className="mt-1"
              value={specifiedCurrency}
              onChange={(e) => setSpecifiedCurrency(e.target.value.toUpperCase())}
              maxLength={3}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">指定单价</span>
            <Input
              className="mt-1"
              value={specifiedUnitCost}
              onChange={(e) => setSpecifiedUnitCost(e.target.value)}
              placeholder="大于 0"
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">生效时间</span>
            <Input
              className="mt-1"
              type="datetime-local"
              value={specifiedEffectiveFrom}
              onChange={(e) => setSpecifiedEffectiveFrom(e.target.value)}
            />
          </label>
        </div>
        <Button className="mt-3" onClick={() => void submitSpecifiedCost()}>
          维护指定单价
        </Button>

        <div className="mt-4 overflow-x-auto">
          {specifiedCosts.length === 0 ? (
            <p className="text-copy-13 text-muted-foreground">
              还没有维护指定成本单价 —— 指定成本法在未维护单价的 SKU 上算不出结果。
            </p>
          ) : (
            <table className="w-full text-left text-copy-13">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">SKU</th>
                  <th className="p-2">币种</th>
                  <th className="p-2">指定单价</th>
                  <th className="p-2">生效区间</th>
                  <th className="p-2">版本</th>
                  <th className="p-2">状态</th>
                </tr>
              </thead>
              <tbody>
                {specifiedCosts.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="p-2">{row.skuCode || row.skuId}</td>
                    <td className="p-2">{row.currency}</td>
                    <td className="p-2">{formatValuationMoney(row.unitCost)}</td>
                    <td className="p-2">
                      {valuationEffectiveWindow(row.effectiveFrom, row.effectiveTo)}
                    </td>
                    <td className="p-2">第 {row.version} 版</td>
                    <td className="p-2">
                      <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                        {valuationStatusLabel(row.status)}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </SectionCard>

      {/* 5. 试算 */}
      <SectionCard
        title="试算：用真实成本层对比四种方法"
        description="成本层来自 catalog 5.2.1.1 的真实到货记录。试算只算给人看，不写任何成本事实，也不执行重算。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <label className="text-copy-12">
            <span className="text-muted-foreground">适用范围</span>
            <select
              className="border-control-border bg-card mt-1 h-9 w-full rounded-md border px-2 text-copy-13"
              value={trialScopeType}
              onChange={(e) => {
                setTrialScopeType(e.target.value);
                setTrialScopeRefId('');
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
              value={trialScopeRefId}
              onChange={(e) => setTrialScopeRefId(e.target.value)}
            >
              <option value="">请选择</option>
              {(
                trialScopeType === 'CATEGORY' ? scopes?.categories ?? [] : scopes?.legalEntities ?? []
              ).map((s) => (
                <option key={s.refId} value={s.refId}>
                  {s.name || s.code}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">批次（批次成本法用）</span>
            <Input
              className="mt-1"
              value={trialBatchRef}
              onChange={(e) => setTrialBatchRef(e.target.value)}
              placeholder="留空则批次法算不出结果"
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">SKU ID</span>
            <Input className="mt-1" value={trialSkuId} onChange={(e) => setTrialSkuId(e.target.value)} />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">SKU 编码</span>
            <Input className="mt-1" value={trialSkuCode} onChange={(e) => setTrialSkuCode(e.target.value)} />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">币种</span>
            <Input
              className="mt-1"
              value={trialCurrency}
              onChange={(e) => setTrialCurrency(e.target.value.toUpperCase())}
              maxLength={3}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">出库数量</span>
            <Input
              className="mt-1"
              value={trialQuantity}
              onChange={(e) => setTrialQuantity(e.target.value)}
              placeholder="要算多少的成本"
            />
          </label>
        </div>
        <Button className="mt-3" onClick={() => void submitTrial()}>
          发起试算
        </Button>

        {trial ? (
          <div className="mt-4 space-y-3">
            <Notice tone="info" title="试算口径">
              {trial.basisNote}
              {trial.sourceNote ? ` ${trial.sourceNote}` : ''}
            </Notice>
            <div className="grid gap-3 md:grid-cols-3">
              <StatCard label="可用数量" value={formatValuationQuantity(trial.availableQuantity)} />
              <StatCard label="参与计算的成本层" value={trial.layerCount} />
              <StatCard
                label="当前生效方法"
                value={trial.effectiveMethod ? valuationMethodLabel(trial.effectiveMethod) : '未配置'}
              />
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-copy-12 text-muted-foreground">
                  <tr>
                    <th className="p-2">计价方法</th>
                    <th className="p-2">单位成本</th>
                    <th className="p-2">成本金额</th>
                    <th className="p-2">覆盖 / 缺口</th>
                    <th className="p-2">与生效方法比较</th>
                    <th className="p-2">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {sortTrialResults(trial.results).map((r) => (
                    <tr key={r.valuationMethod} className="border-t">
                      <td className="p-2">{valuationMethodLabel(r.valuationMethod)}</td>
                      <td className="p-2">{formatValuationMoney(r.unitCost)}</td>
                      <td className="p-2">{formatValuationMoney(r.costAmount)}</td>
                      <td className="p-2">
                        {formatValuationQuantity(r.coveredQuantity)} /{' '}
                        {formatValuationQuantity(r.shortfallQuantity)}
                      </td>
                      <td className="p-2">{valuationDifferenceLabel(r.differenceToEffectiveMethod)}</td>
                      <td className="p-2">
                        {r.comparable ? r.basisNote : (
                          <span className="inline-flex items-center gap-1 text-warning">
                            <TriangleAlert className="h-3.5 w-3.5" />
                            {r.gapNote}
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ) : null}

        <div className="mt-4">
          <h3 className="text-copy-13 font-medium">历史试算</h3>
          {trials.length === 0 ? (
            <p className="mt-2 text-copy-13 text-muted-foreground">还没有试算记录。</p>
          ) : (
            <div className="mt-2 overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-copy-12 text-muted-foreground">
                  <tr>
                    <th className="p-2">SKU</th>
                    <th className="p-2">数量</th>
                    <th className="p-2">生效方法</th>
                    <th className="p-2">时间</th>
                  </tr>
                </thead>
                <tbody>
                  {trials.map((t) => (
                    <tr key={t.id} className="border-t">
                      <td className="p-2">{t.skuCode || t.skuId}</td>
                      <td className="p-2">{formatValuationQuantity(t.issueQuantity)}</td>
                      <td className="p-2">
                        {t.effectiveMethod ? valuationMethodLabel(t.effectiveMethod) : '未配置'}
                      </td>
                      <td className="p-2">{t.createdAt.slice(0, 19).replace('T', ' ')}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
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
