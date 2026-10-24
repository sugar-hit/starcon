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
  FinanceTaxBindingPage,
  FinanceTaxBindingRow,
  FinanceTaxJurisdictionOptions,
  FinanceTaxRateCapabilities,
  FinanceTaxRateComparison,
  FinanceTaxRateOptions,
  FinanceTaxRatePage,
  FinanceTaxRateRow,
  FinanceTaxResolution,
  FinanceTaxShopOptions,
  FinanceTaxStateRateRules,
} from '../../../../../packages/api-contracts/src/index';
import {
  fetchStateSalesTax,
  fetchTaxBindingVersions,
  fetchTaxBindings,
  fetchTaxJurisdictions,
  fetchTaxRateCapabilities,
  fetchTaxRateComparison,
  fetchTaxRateOptions,
  fetchTaxRateVersions,
  fetchTaxRates,
  fetchTaxShops,
  formatTaxRate,
  jurisdictionLabel,
  resolveTaxRate,
  resolvedByLabel,
  taxEffectiveMoment,
  taxEffectiveWindow,
  taxRateDecimal,
  taxRateStatusLabel,
  taxRegimeLabel,
  taxTypeLabel,
  upsertTaxBinding,
  upsertTaxRate,
} from './tax-rate-model';

/**
 * 12.6.1.1 税率配置。
 *
 * <p>蓝图 12-finance.md：「维护税率配置：目的国/地区的销售税率、VAT 税率、IOSS 适用税率、
 * 代扣代缴税；支持按店铺站点绑定默认税率与税码。」关系：「上游 = 2 店铺站点、16 合规；
 * 下游 = 9 订单（税额）、12.6.1.2 申报。<b>税率必须带生效时间</b>，历史订单用当时税率，
 * 不能被新政策追溯修改。」
 *
 * <p><b>页面围绕五件事组织</b>：
 *
 * <ol>
 *   <li><b>税种选项</b>（第一块）：四个税种连同各自的<b>口径说明</b>摆出来。
 *       系统**只提供选项，不替用户决定税率** —— 税率是租户自己的税务事实，
 *       页面**零预置税率**，也**不标任何「推荐税率」**。</li>
 *   <li><b>税率记录</b>（第二块）：按 税种 × 辖区 × 税码 维护，带生效时间。
 *       改税率 = <b>追加新版本</b>，旧版本被收口留作版本对比。</li>
 *   <li><b>版本对比</b>（第三块）：逐字段给出「换税率前 / 换税率后」的差异。</li>
 *   <li><b>按生效时间取税率</b>（第四块）：给定订单日期 + 店铺站点 + 辖区，
 *       唯一命中一条生效税率；<b>命中不到就如实报「该时点无生效税率」</b>。</li>
 *   <li><b>店铺站点绑定</b>（第五块）：把店铺站点绑到一条税档（默认税率与税码）上，
 *       换绑同样不追溯。</li>
 * </ol>
 *
 * <p><b>🔴 州销售税归 channel 16.3.2.3</b>：第六块只<b>只读引用</b> channel 的规则，
 * 并标明来源模块；channel 读不到时显示「读不到」，**不是**「没有州销售税」。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有录过税率」。
 */
export function TaxRateWorkspace() {
  const [caps, setCaps] = useState<FinanceTaxRateCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceTaxRateOptions | null>(null);
  const [jurisdictions, setJurisdictions] = useState<FinanceTaxJurisdictionOptions | null>(null);
  const [shops, setShops] = useState<FinanceTaxShopOptions | null>(null);
  const [rates, setRates] = useState<FinanceTaxRatePage | null>(null);
  const [bindings, setBindings] = useState<FinanceTaxBindingPage | null>(null);
  const [versions, setVersions] = useState<FinanceTaxRateRow[]>([]);
  const [comparison, setComparison] = useState<FinanceTaxRateComparison | null>(null);
  const [bindingVersions, setBindingVersions] = useState<FinanceTaxBindingRow[]>([]);
  const [resolution, setResolution] = useState<FinanceTaxResolution | null>(null);
  const [resolveError, setResolveError] = useState('');
  const [stateRules, setStateRules] = useState<FinanceTaxStateRateRules | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [taxTypeFilter, setTaxTypeFilter] = useState('');
  const [jurisdictionFilter, setJurisdictionFilter] = useState('');
  const [keyword, setKeyword] = useState('');

  // 登记税率
  const [formTaxType, setFormTaxType] = useState('VAT');
  const [formJurisdiction, setFormJurisdiction] = useState('');
  const [formTaxCode, setFormTaxCode] = useState('STANDARD');
  const [formRate, setFormRate] = useState('');
  const [formEffectiveFrom, setFormEffectiveFrom] = useState('');
  const [formNote, setFormNote] = useState('');

  // 版本对比
  const [compareFrom, setCompareFrom] = useState('');
  const [compareTo, setCompareTo] = useState('');

  // 取税率
  const [resolveTaxType, setResolveTaxType] = useState('VAT');
  const [resolveAt, setResolveAt] = useState('');
  const [resolveShopId, setResolveShopId] = useState('');
  const [resolveJurisdiction, setResolveJurisdiction] = useState('');
  const [resolveTaxCode, setResolveTaxCode] = useState('');

  // 绑定
  const [bindShopId, setBindShopId] = useState('');
  const [bindTaxType, setBindTaxType] = useState('VAT');
  const [bindJurisdiction, setBindJurisdiction] = useState('');
  const [bindTaxCode, setBindTaxCode] = useState('');
  const [bindEffectiveFrom, setBindEffectiveFrom] = useState('');
  const [bindNote, setBindNote] = useState('');

  /** 可用于录税率的辖区（美国辖区归 channel，页面上禁用并写明原因）。 */
  const usableJurisdictions = useMemo(
    () => (jurisdictions?.items ?? []).filter((j) => j.usableForTaxRate),
    [jurisdictions],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, j, s, r, b] = await Promise.all([
        fetchTaxRateCapabilities(),
        fetchTaxRateOptions(),
        fetchTaxJurisdictions(),
        fetchTaxShops(),
        fetchTaxRates({
          taxType: taxTypeFilter || undefined,
          jurisdictionCode: jurisdictionFilter || undefined,
          keyword: keyword || undefined,
        }),
        fetchTaxBindings({
          taxType: taxTypeFilter || undefined,
          jurisdictionCode: jurisdictionFilter || undefined,
        }),
      ]);
      setCaps(c);
      setOptions(o);
      setJurisdictions(j);
      setShops(s);
      setRates(r);
      setBindings(b);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载税率配置失败');
    } finally {
      setLoading(false);
    }
  }, [taxTypeFilter, jurisdictionFilter, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 州销售税（只读引用 channel）：读不到就如实显示「读不到」。 */
  const loadStateRules = useCallback(async () => {
    try {
      setStateRules(await fetchStateSalesTax());
      setActionError('');
    } catch (e) {
      setStateRules(null);
      setActionError(e instanceof Error ? e.message : '州销售税（归 channel）读不到');
    }
  }, []);

  useEffect(() => {
    void loadStateRules();
  }, [loadStateRules]);

  // 版本历史：选定税档后拉。
  const loadVersions = useCallback(async () => {
    if (!formTaxType || !formJurisdiction || !formTaxCode) {
      setVersions([]);
      setComparison(null);
      return;
    }
    try {
      const list = await fetchTaxRateVersions(formTaxType, formJurisdiction, formTaxCode);
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
  }, [formTaxType, formJurisdiction, formTaxCode]);

  useEffect(() => {
    void loadVersions();
  }, [loadVersions]);

  const runComparison = useCallback(async () => {
    if (!formTaxType || !formJurisdiction || !formTaxCode || !compareFrom || !compareTo) return;
    try {
      const result = await fetchTaxRateComparison(
        formTaxType,
        formJurisdiction,
        formTaxCode,
        Number(compareFrom),
        Number(compareTo),
      );
      setComparison(result);
      setActionError('');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '版本对比失败');
    }
  }, [formTaxType, formJurisdiction, formTaxCode, compareFrom, compareTo]);

  const submitRate = useCallback(async () => {
    if (!formJurisdiction) {
      setActionError('必须选择辖区（目的国/地区）');
      return;
    }
    if (!formTaxCode.trim()) {
      setActionError('必须给出税码');
      return;
    }
    if (formRate.trim() === '' || Number.isNaN(Number(formRate))) {
      setActionError('必须给出税率（小数率，例如 0.19 表示 19%）');
      return;
    }
    if (!formEffectiveFrom) {
      setActionError('必须给出生效时间：没有生效时间的税率等于没有');
      return;
    }
    try {
      const row = await upsertTaxRate({
        taxType: formTaxType,
        jurisdictionCode: formJurisdiction,
        taxCode: formTaxCode.trim(),
        rate: Number(formRate),
        effectiveFrom: new Date(formEffectiveFrom).toISOString(),
        note: formNote,
      });
      setActionError('');
      setNotice(
        `已登记${taxTypeLabel(row.taxType)}第 ${row.version} 版（${row.jurisdictionName || row.jurisdictionCode} · ${row.taxCode}，${formatTaxRate(row.rate)}），生效于 ${taxEffectiveMoment(row.effectiveFrom)}。同一税档已有生效版本时，本次登记会**追加新版本**并把旧版本收口 —— 历史订单仍按当时那版算。`,
      );
      await load();
      await loadVersions();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记税率失败');
    }
  }, [
    formTaxType,
    formJurisdiction,
    formTaxCode,
    formRate,
    formEffectiveFrom,
    formNote,
    load,
    loadVersions,
  ]);

  const submitBinding = useCallback(async () => {
    if (!bindShopId) {
      setActionError('必须选择店铺站点');
      return;
    }
    if (!bindJurisdiction) {
      setActionError('必须选择辖区（目的国/地区）');
      return;
    }
    if (!bindTaxCode.trim()) {
      setActionError('必须给出税码');
      return;
    }
    if (!bindEffectiveFrom) {
      setActionError('必须给出生效时间：没有生效时间的绑定等于没有');
      return;
    }
    try {
      const shop = (shops?.items ?? []).find((s) => s.id === bindShopId);
      const row = await upsertTaxBinding({
        shopId: bindShopId,
        shopCode: shop?.code ?? '',
        shopName: shop?.name ?? '',
        taxType: bindTaxType,
        jurisdictionCode: bindJurisdiction,
        taxCode: bindTaxCode.trim(),
        effectiveFrom: new Date(bindEffectiveFrom).toISOString(),
        note: bindNote,
      });
      setActionError('');
      setNotice(
        `已绑定「${shop?.name || shop?.code || bindShopId}」的${taxTypeLabel(row.taxType)}默认税码为 ${row.taxCode}（辖区 ${row.jurisdictionCode}），第 ${row.version} 版，生效于 ${taxEffectiveMoment(row.effectiveFrom)}。换绑同样是**追加版本**，历史绑定不会被改写。`,
      );
      await load();
      try {
        setBindingVersions(
          await fetchTaxBindingVersions(bindShopId, bindTaxType, bindJurisdiction),
        );
      } catch {
        setBindingVersions([]);
      }
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '绑定失败');
    }
  }, [
    bindShopId,
    bindTaxType,
    bindJurisdiction,
    bindTaxCode,
    bindEffectiveFrom,
    bindNote,
    shops,
    load,
  ]);

  const submitResolve = useCallback(async () => {
    if (!resolveJurisdiction) {
      setActionError('必须给出辖区：订单是要算哪个辖区的税');
      return;
    }
    try {
      const hit = await resolveTaxRate({
        taxType: resolveTaxType,
        asOf: resolveAt ? new Date(resolveAt).toISOString() : undefined,
        shopId: resolveShopId || undefined,
        jurisdictionCode: resolveJurisdiction,
        taxCode: resolveTaxCode || undefined,
      });
      setResolution(hit);
      setResolveError('');
      setActionError('');
    } catch (e) {
      // 🔴 命中不到就如实显示服务端的原话 —— 绝不显示 0% 冒充「税率是 0」。
      setResolution(null);
      setResolveError(e instanceof Error ? e.message : '该时点无生效税率');
    }
  }, [resolveTaxType, resolveAt, resolveShopId, resolveJurisdiction, resolveTaxCode]);

  if (loading && !caps) {
    return (
      <div className="p-6">
        <p className="text-copy-13 text-muted-foreground">正在加载税率配置…</p>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <div className="p-6">
        <Notice tone="danger" title="税率配置加载失败">
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
        title="税率配置"
        description="维护目的国/地区的销售税率、VAT 税率、IOSS 适用税率与代扣代缴税，并按店铺站点绑定默认税率与税码。税率必须带生效时间。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {/* 边界提示：系统提供结构与辖区选项，不替用户决定税率；州销售税归 channel。 */}
      <Notice tone="info" title="系统提供结构与辖区选项，不替用户决定税率">
        税率是租户自己的税务事实，本系统<strong>零预置税率</strong>，也
        <strong>不标任何「推荐税率」</strong>。改税率 =<strong>追加一个新版本</strong>
        并把旧版本收口留作对比 —— 历史订单用当时那版，不会被新政策追溯改写。
        <br />
        🔴 <strong>美国州级销售税归 channel 16.3.2.3 维护</strong>（本模块不重复建表），
        页面最下方只做<strong>只读引用</strong>；「目的国/地区」的值域取自 channel 16.3.1.1 的
        受控辖区码表。
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
        <StatCard label="生效中的税率" value={rates?.activeCount ?? 0} />
        <StatCard label="税率版本总数" value={rates?.total ?? 0} />
        <StatCard label="店铺绑定" value={bindings?.total ?? 0} />
        <StatCard label="可录税率的辖区" value={usableJurisdictions.length} />
      </div>

      {/* 1. 税种选项 */}
      <SectionCard
        title="可用的税种（系统提供选项）"
        description="四个税种的口径说明原样展示。系统不替用户选，落库的税率由租户自己决定。"
      >
        <div className="grid gap-3 md:grid-cols-2">
          {(options?.taxTypes ?? []).map((option) => (
            <div key={option.value} className="rounded-md border p-3">
              <div className="flex items-center justify-between">
                <span className="text-copy-13 font-medium">{option.label}</span>
                <Badge tone="neutral">{option.value}</Badge>
              </div>
              <p className="text-copy-12 text-muted-foreground mt-2">{option.basis}</p>
            </div>
          ))}
        </div>
      </SectionCard>

      {/* 2. 税率记录 */}
      <SectionCard
        title="税率记录"
        description="按 税种 × 辖区 × 税码 维护，带生效时间。改税率 = 追加新版本（旧版本被收口并留作对比）。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={taxTypeFilter}
              onChange={(e) => setTaxTypeFilter(e.target.value)}
              aria-label="按税种筛选"
            >
              <option value="">全部税种</option>
              {(options?.taxTypes ?? []).map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <Input
              className="w-44"
              placeholder="辖区 / 税码 / 备注"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              aria-label="按关键字筛选"
            />
          </div>
        }
      >
        {rates && rates.items.length === 0 ? (
          <EmptyState
            title="还没有录过税率"
            description="税率是租户自己的税务事实，系统不预置任何一行。选好税种、辖区、税码与生效时间后登记第一条。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">税种</th>
                  <th className="p-2">辖区 / 税码</th>
                  <th className="p-2">税率</th>
                  <th className="p-2">生效区间</th>
                  <th className="p-2">版本</th>
                  <th className="p-2">状态</th>
                </tr>
              </thead>
              <tbody>
                {(rates?.items ?? []).map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="p-2">{taxTypeLabel(row.taxType)}</td>
                    <td className="p-2">
                      <div>{row.jurisdictionName || row.jurisdictionCode}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {row.jurisdictionCode} · 税码 {row.taxCode}
                      </div>
                    </td>
                    <td className="p-2">
                      <div>{formatTaxRate(row.rate)}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {taxRateDecimal(row.rate)}
                      </div>
                    </td>
                    <td className="p-2">
                      {taxEffectiveWindow(row.effectiveFrom, row.effectiveTo)}
                      <div className="text-copy-12 text-muted-foreground">
                        起点 {taxEffectiveMoment(row.effectiveFrom)}
                      </div>
                    </td>
                    <td className="p-2">第 {row.version} 版</td>
                    <td className="p-2">
                      <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                        {taxRateStatusLabel(row.status)}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 登记 / 换税率 */}
      <SectionCard
        title="登记 / 换税率"
        description="同一 税种 × 辖区 × 税码 已有生效版本时，本次登记会追加一个新版本并把旧版本收口。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <label className="text-copy-12">
            <span className="text-muted-foreground">税种</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={formTaxType}
              onChange={(e) => setFormTaxType(e.target.value)}
            >
              {(options?.taxTypes ?? []).map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">辖区（目的国/地区）</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={formJurisdiction}
              onChange={(e) => setFormJurisdiction(e.target.value)}
            >
              <option value="">请选择</option>
              {usableJurisdictions.map((j) => (
                <option key={j.code} value={j.code}>
                  {jurisdictionLabel(j.name, j.code)}（{j.code}）
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
              maxLength={32}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">税率（小数率，0.19 = 19%）</span>
            <Input
              className="mt-1"
              value={formRate}
              onChange={(e) => setFormRate(e.target.value)}
              placeholder="0.19"
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
            />
          </label>
        </div>
        <Button className="mt-3" onClick={() => void submitRate()}>
          登记税率（已有生效版本则追加新版本）
        </Button>
      </SectionCard>

      {/* 3. 版本历史与对比 */}
      <SectionCard
        title="版本历史与对比"
        description="选定上方那一条税档（税种 × 辖区 × 税码）后，这里列出它的全部版本，并逐字段给出「换税率前 / 后」的差异。"
      >
        {versions.length === 0 ? (
          <EmptyState
            title="这条税档还没有版本"
            description="在上方登记第一条税率后，这里会列出它的版本链，并可以对比相邻两版。"
          />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full text-left">
                <thead className="text-copy-12 text-muted-foreground">
                  <tr>
                    <th className="p-2">版本</th>
                    <th className="p-2">税率</th>
                    <th className="p-2">税码</th>
                    <th className="p-2">生效区间</th>
                    <th className="p-2">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {versions.map((row) => (
                    <tr key={row.id} className="border-t">
                      <td className="p-2">第 {row.version} 版</td>
                      <td className="p-2">{formatTaxRate(row.rate)}</td>
                      <td className="p-2">{row.taxCode}</td>
                      <td className="p-2">
                        {taxEffectiveWindow(row.effectiveFrom, row.effectiveTo)}
                      </td>
                      <td className="p-2">
                        <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                          {taxRateStatusLabel(row.status)}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-3 flex flex-wrap items-end gap-2">
              <label className="text-copy-12">
                <span className="text-muted-foreground">对比从</span>
                <select
                  className="border-control-border bg-card text-copy-13 mt-1 h-9 rounded-md border px-2"
                  value={compareFrom}
                  onChange={(e) => setCompareFrom(e.target.value)}
                >
                  <option value="">请选择</option>
                  {versions.map((v) => (
                    <option key={v.id} value={v.version}>
                      第 {v.version} 版
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-12">
                <span className="text-muted-foreground">对比到</span>
                <select
                  className="border-control-border bg-card text-copy-13 mt-1 h-9 rounded-md border px-2"
                  value={compareTo}
                  onChange={(e) => setCompareTo(e.target.value)}
                >
                  <option value="">请选择</option>
                  {versions.map((v) => (
                    <option key={v.id} value={v.version}>
                      第 {v.version} 版
                    </option>
                  ))}
                </select>
              </label>
              <Button variant="secondary" onClick={() => void runComparison()}>
                对比
              </Button>
            </div>
            {comparison ? (
              <div className="mt-3 overflow-x-auto">
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-copy-12 text-muted-foreground">
                    <tr>
                      <th className="p-2">字段</th>
                      <th className="p-2">第 {comparison.from.version} 版</th>
                      <th className="p-2">第 {comparison.to.version} 版</th>
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
                          <Badge tone={d.changed ? 'warning' : 'neutral'}>
                            {d.changed ? '有变化' : '无变化'}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className="text-copy-12 text-muted-foreground mt-2">{comparison.note}</p>
              </div>
            ) : null}
          </>
        )}
      </SectionCard>

      {/* 4. 按生效时间取税率 */}
      <SectionCard
        title="按生效时间取税率"
        description="给定「订单日期 + 店铺站点 + 辖区」唯一命中一条生效税率。命中不到就报错 —— 不退回最新一条，也不按 0% 处理。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <label className="text-copy-12">
            <span className="text-muted-foreground">税种</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={resolveTaxType}
              onChange={(e) => setResolveTaxType(e.target.value)}
            >
              {(options?.taxTypes ?? []).map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">订单日期（取税率时刻）</span>
            <Input
              className="mt-1"
              type="datetime-local"
              value={resolveAt}
              onChange={(e) => setResolveAt(e.target.value)}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">辖区</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={resolveJurisdiction}
              onChange={(e) => setResolveJurisdiction(e.target.value)}
            >
              <option value="">请选择</option>
              {usableJurisdictions.map((j) => (
                <option key={j.code} value={j.code}>
                  {jurisdictionLabel(j.name, j.code)}（{j.code}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">店铺站点（可选）</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={resolveShopId}
              onChange={(e) => setResolveShopId(e.target.value)}
            >
              <option value="">不按店铺（直接按辖区取）</option>
              {(shops?.items ?? []).map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name || s.code}（{s.code}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">税码（按店铺取时可留空）</span>
            <Input
              className="mt-1"
              value={resolveTaxCode}
              onChange={(e) => setResolveTaxCode(e.target.value)}
            />
          </label>
        </div>
        <Button className="mt-3" onClick={() => void submitResolve()}>
          取税率
        </Button>
        {resolveError ? (
          <Notice tone="warning" title="该时点没有生效税率">
            {resolveError}
          </Notice>
        ) : null}
        {resolution ? (
          <div className="mt-3 rounded-md border p-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone="success">{resolvedByLabel(resolution.resolvedBy)}</Badge>
              <span className="text-copy-13 font-medium">{formatTaxRate(resolution.rate)}</span>
              <span className="text-copy-12 text-muted-foreground">
                {taxRateDecimal(resolution.rate)}
              </span>
            </div>
            <dl className="text-copy-12 mt-2 grid gap-1 md:grid-cols-2">
              <div>
                <dt className="text-muted-foreground inline">税种：</dt>
                <dd className="inline">{taxTypeLabel(resolution.taxType)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground inline">辖区：</dt>
                <dd className="inline">
                  {resolution.jurisdictionName || resolution.jurisdictionCode}
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground inline">税码：</dt>
                <dd className="inline">{resolution.taxCode}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground inline">取税率时刻：</dt>
                <dd className="inline">{taxEffectiveMoment(resolution.asOf)}</dd>
              </div>
              {resolution.shopId ? (
                <div>
                  <dt className="text-muted-foreground inline">店铺站点：</dt>
                  <dd className="inline">
                    {resolution.shopCode || resolution.shopId}
                    {resolution.bindingVersion ? `（绑定第 ${resolution.bindingVersion} 版）` : ''}
                  </dd>
                </div>
              ) : null}
            </dl>
            <p className="text-copy-12 text-muted-foreground mt-2">{resolution.note}</p>
          </div>
        ) : null}
      </SectionCard>

      {/* 5. 店铺站点绑定 */}
      <SectionCard
        title="店铺站点的默认税率与税码绑定"
        description="把店铺站点绑到一条税档（辖区 × 税码）上。同一 店铺 × 税种 × 辖区 已有生效绑定时，本次绑定会追加新版本 —— 换绑同样不追溯。"
      >
        {bindings && bindings.items.length === 0 ? (
          <EmptyState
            title="还没有绑定过任何店铺站点"
            description="先录好税率，再把店铺站点绑定到对应的税档上。绑定带生效区间，换绑会追加新版本。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">店铺站点</th>
                  <th className="p-2">税种</th>
                  <th className="p-2">辖区 / 税码</th>
                  <th className="p-2">税率</th>
                  <th className="p-2">生效区间</th>
                  <th className="p-2">版本</th>
                  <th className="p-2">状态</th>
                </tr>
              </thead>
              <tbody>
                {(bindings?.items ?? []).map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="p-2">
                      <div>{row.shopName || row.shopCode}</div>
                      <div className="text-copy-12 text-muted-foreground">{row.shopCode}</div>
                    </td>
                    <td className="p-2">{taxTypeLabel(row.taxType)}</td>
                    <td className="p-2">
                      {row.jurisdictionName || row.jurisdictionCode} · {row.taxCode}
                    </td>
                    <td className="p-2">{formatTaxRate(row.rate)}</td>
                    <td className="p-2">
                      {taxEffectiveWindow(row.effectiveFrom, row.effectiveTo)}
                    </td>
                    <td className="p-2">第 {row.version} 版</td>
                    <td className="p-2">
                      <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                        {taxRateStatusLabel(row.status)}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="mt-4 grid gap-3 md:grid-cols-3">
          <label className="text-copy-12">
            <span className="text-muted-foreground">店铺站点</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={bindShopId}
              onChange={(e) => setBindShopId(e.target.value)}
            >
              <option value="">请选择</option>
              {(shops?.items ?? []).map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name || s.code}（{s.code}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">税种</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={bindTaxType}
              onChange={(e) => setBindTaxType(e.target.value)}
            >
              {(options?.taxTypes ?? []).map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">辖区</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={bindJurisdiction}
              onChange={(e) => setBindJurisdiction(e.target.value)}
            >
              <option value="">请选择</option>
              {usableJurisdictions.map((j) => (
                <option key={j.code} value={j.code}>
                  {jurisdictionLabel(j.name, j.code)}（{j.code}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">税码</span>
            <Input
              className="mt-1"
              value={bindTaxCode}
              onChange={(e) => setBindTaxCode(e.target.value)}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">生效时间</span>
            <Input
              className="mt-1"
              type="datetime-local"
              value={bindEffectiveFrom}
              onChange={(e) => setBindEffectiveFrom(e.target.value)}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">备注</span>
            <Input
              className="mt-1"
              value={bindNote}
              onChange={(e) => setBindNote(e.target.value)}
            />
          </label>
        </div>
        <Button className="mt-3" onClick={() => void submitBinding()}>
          绑定 / 换绑（已有生效绑定则追加新版本）
        </Button>

        {bindingVersions.length > 0 ? (
          <div className="mt-4 overflow-x-auto">
            <p className="text-copy-12 text-muted-foreground">该店铺该税种该辖区的绑定版本链：</p>
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">版本</th>
                  <th className="p-2">税码</th>
                  <th className="p-2">生效区间</th>
                  <th className="p-2">状态</th>
                </tr>
              </thead>
              <tbody>
                {bindingVersions.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="p-2">第 {row.version} 版</td>
                    <td className="p-2">{row.taxCode}</td>
                    <td className="p-2">
                      {taxEffectiveWindow(row.effectiveFrom, row.effectiveTo)}
                    </td>
                    <td className="p-2">
                      <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                        {taxRateStatusLabel(row.status)}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </SectionCard>

      {/* 6. 州销售税（只读引用 channel） */}
      <SectionCard
        title="美国州级销售税（🔴 归 channel 16.3.2.3，本模块只读引用）"
        description="州基准税率 / 州级阈值 / 免征额 / Marketplace Facilitator 义务与申报周期全部由 channel 维护；本模块不重复建表，也不改写它的数据。"
      >
        {stateRules === null ? (
          <Notice tone="warning" title="州销售税读不到">
            <TriangleAlert className="mr-2 inline h-4 w-4" />
            channel 服务不可达或返回异常，**读不到**州销售税规则。 这不等于「没有州销售税」——
            本模块只是读不到。
            <Button className="ml-3" onClick={() => void loadStateRules()}>
              重试
            </Button>
          </Notice>
        ) : stateRules.items.length === 0 ? (
          <EmptyState
            title="channel 侧还没有登记州销售税规则"
            description="这是 channel 16.3.2.3 的数据，不是本模块的数据。规则登记在 channel 的「销售税与平台代收」页面。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">州</th>
                  <th className="p-2">版本</th>
                  <th className="p-2">基准税率</th>
                  <th className="p-2">州级阈值</th>
                  <th className="p-2">免征额</th>
                  <th className="p-2">平台代扣代缴</th>
                  <th className="p-2">申报周期</th>
                </tr>
              </thead>
              <tbody>
                {stateRules.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="p-2">{row.stateName || row.stateCode}</td>
                    <td className="p-2">第 {row.ruleVersion} 版</td>
                    <td className="p-2">{formatTaxRate(row.baseRate)}</td>
                    <td className="p-2">{row.thresholdAmount}</td>
                    <td className="p-2">{row.exemptionAmount}</td>
                    <td className="p-2">
                      <Badge tone={row.marketplaceFacilitator ? 'warning' : 'neutral'}>
                        {row.marketplaceFacilitator ? '平台代收代缴' : '卖家自行申报'}
                      </Badge>
                    </td>
                    <td className="p-2">{row.filingCycle}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-copy-12 text-muted-foreground mt-2">{stateRules.note}</p>
          </div>
        )}
      </SectionCard>

      {/* 7. 辖区选项（含归 channel 的不可用辖区） */}
      <SectionCard
        title="辖区选项（实时读 channel 16.3.1.1 的受控辖区码表）"
        description="辖区码不是自由文本。归 channel 维护的辖区仍然列出来，但标明不可用原因。"
      >
        {jurisdictions === null ? (
          <Notice tone="warning" title="辖区码表读不到">
            channel 服务不可达，**读不到**辖区码表 —— 这不等于「没有辖区」。
          </Notice>
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">辖区</th>
                  <th className="p-2">税制</th>
                  <th className="p-2">是否要求 VAT 号</th>
                  <th className="p-2">本模块可用</th>
                </tr>
              </thead>
              <tbody>
                {jurisdictions.items.map((j) => (
                  <tr key={j.code} className="border-t">
                    <td className="p-2">
                      <div>{j.name}</div>
                      <div className="text-copy-12 text-muted-foreground">{j.code}</div>
                    </td>
                    <td className="p-2">{taxRegimeLabel(j.taxRegime)}</td>
                    <td className="p-2">{j.vatRequired ? '要求' : '不要求'}</td>
                    <td className="p-2">
                      {j.usableForTaxRate ? (
                        <Badge tone="success">可录税率</Badge>
                      ) : (
                        <Badge tone="neutral" title={j.unusableReason}>
                          不可用（归 channel）
                        </Badge>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {jurisdictions.items.some((j) => !j.usableForTaxRate) ? (
              <p className="text-copy-12 text-muted-foreground mt-2">
                {jurisdictions.items.find((j) => !j.usableForTaxRate)?.unusableReason}
              </p>
            ) : null}
          </div>
        )}
      </SectionCard>

      {/* 8. 能力声明 */}
      <SectionCard title="能力声明" description="哪些上游真接通、职责怎么切、哪些还没交付、归谁。">
        <div className="space-y-2">
          {(caps?.capabilities ?? []).map((c) => (
            <div key={`${c.module}-${c.name}`} className="rounded-md border p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-copy-13 font-medium">{c.name}</span>
                <Badge tone={c.available ? 'success' : 'neutral'}>
                  {c.module} {c.available ? '已接通' : '未接通'}
                </Badge>
                {c.ownerModule ? <Badge tone="neutral">归属 {c.ownerModule}</Badge> : null}
              </div>
              <p className="text-copy-12 text-muted-foreground mt-1">依据：{c.basis}</p>
              <p className="text-copy-12 mt-1">{c.detail}</p>
            </div>
          ))}
          {(caps?.downstreamGaps ?? []).map((g) => (
            <div key={`${g.module}-${g.name}`} className="rounded-md border border-dashed p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-copy-13 font-medium">{g.name}</span>
                <Badge tone="warning">{g.module} 未交付</Badge>
                <Badge tone="neutral">{g.ownerService}</Badge>
              </div>
              <p className="text-copy-12 mt-1">{g.detail}</p>
            </div>
          ))}
        </div>
      </SectionCard>
    </div>
  );
}
