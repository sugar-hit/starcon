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
  FinanceCostCollectionCapabilities,
  FinanceCostCollectionEntryPage,
  FinanceCostCollectionOptions,
  FinanceCostCollectionPool,
  FinanceCostCollectionPoolDetail,
  FinanceCostCollectionResult,
  FinanceCostCollectionRule,
  FinanceCostCollectionRuleTemplate,
} from '../../../../../packages/api-contracts/src/index';
import {
  collectAdSpendCost,
  collectHeadMileCost,
  collectLogisticsCost,
  collectManualCost,
  collectPlatformSettlementCost,
  costBasisTypeLabel,
  costDimensionLabel,
  costFeeCategoryLabel,
  costFeeSourceLabel,
  costPeriodBasisLabel,
  fetchCostCollectionCapabilities,
  fetchCostCollectionEntries,
  fetchCostCollectionOptions,
  fetchCostCollectionPools,
  fetchCostCollectionRuleTemplates,
  fetchCostCollectionRules,
  formatCostAmount,
  formatCostMoney,
  formatCostPeriod,
  removeCostCollectionEntry,
} from './cost-collection-model';

/**
 * 12.4.2.1 费用归集。
 *
 * <p>蓝图 12-finance.md：「归集各类费用到指定维度：按月分摊的平台费/订阅费、按单分摊的物流费、按 SKU 分摊的头程费、按期间分摊的人员与办公费用，<b>保留归集依据</b>。」关系：「<b>归集与分摊要分开</b>：先按规则归集费用池，再按维度分摊，便于核对与调整。」
 *
 * <p><b>页面围绕三件事组织</b>：
 *
 * <ol>
 *   <li><b>归集规则</b>（第一块）：规则把「费用来源」绑到「费用项 → 分摊目标维度 → 费用池」。
 *       系统**不预置任何规则行**（费用池怎么划分是租户的账务口径），
 *       但提供**蓝图标准绑定模板**（只读）让「按模板建规则」一键可用。</li>
 *   <li><b>归集动作</b>（第二块）：五个来源各一个入口 —— 平台结算单 / 广告报表 / 运单运费 /
 *       头程发运单 / 人工录入。每个入口都对应蓝图点名的四类口径之一。</li>
 *   <li><b>归集明细与依据</b>（第三、四块）：条目表 + 费用池表，按币种分开汇总。</li>
 * </ol>
 *
 * <p><b>🔴 页面绝不显示任何「已分摊」结论</b>：分摊属 12.4.2.2，**未交付**。
 * 列表里的「分摊目标维度」是<b>规则声明的目标</b>，不是分摊结果；
 * 页面顶部有一条显式的边界提示，说明这一点。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有归集条目」。
 */

/** 归集来源的五个动作入口。 */
type CollectAction = 'PLATFORM_SETTLEMENT' | 'AD_SPEND' | 'LOGISTICS_WAYBILL' | 'HEAD_MILE_SHIPMENT' | 'MANUAL';

const COLLECT_ACTIONS: { key: CollectAction; label: string; hint: string }[] = [
  {
    key: 'PLATFORM_SETTLEMENT',
    label: '从平台结算单归集',
    hint: '12.3.1.2 结算明细行里的佣金 / FBA 费用 / 仓储费 / 配送费（按月）',
  },
  {
    key: 'AD_SPEND',
    label: '从广告报表归集',
    hint: '14.3.1.1 活动报表的广告花费（按月）',
  },
  {
    key: 'LOGISTICS_WAYBILL',
    label: '从运单运费登记归集',
    hint: '10.2.1.1 已登记的运单运费（按单）',
  },
  {
    key: 'HEAD_MILE_SHIPMENT',
    label: '从头程发运单归集',
    hint: '10.4.1.1 已发运的头程发运单（按 SKU）',
  },
  {
    key: 'MANUAL',
    label: '人工录入归集',
    hint: '订阅费 / 人员费用 / 办公费用（按月或按期间）',
  },
];

export function CostCollectionWorkspace() {
  const [caps, setCaps] = useState<FinanceCostCollectionCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceCostCollectionOptions | null>(null);
  const [rules, setRules] = useState<FinanceCostCollectionRule[]>([]);
  const [templates, setTemplates] = useState<FinanceCostCollectionRuleTemplate[]>([]);
  const [pools, setPools] = useState<FinanceCostCollectionPool[]>([]);
  const [entries, setEntries] = useState<FinanceCostCollectionEntryPage | null>(null);
  const [poolDetail, setPoolDetail] = useState<FinanceCostCollectionPoolDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [feeSource, setFeeSource] = useState('');
  const [targetDimension, setTargetDimension] = useState('');
  const [currency, setCurrency] = useState('');
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 归集动作
  const [action, setAction] = useState<CollectAction>('PLATFORM_SETTLEMENT');
  const [ruleId, setRuleId] = useState('');
  const [baseDate, setBaseDate] = useState('');
  const [manualKey, setManualKey] = useState('');
  const [manualSubject, setManualSubject] = useState('');
  const [manualAmount, setManualAmount] = useState('');
  const [manualCurrency, setManualCurrency] = useState('');
  const [manualPeriodStart, setManualPeriodStart] = useState('');
  const [manualPeriodEnd, setManualPeriodEnd] = useState('');
  const [manualEvidence, setManualEvidence] = useState('');
  const [lastResult, setLastResult] = useState<FinanceCostCollectionResult | null>(null);

  const params = useMemo(
    () => ({
      ...(feeSource ? { feeSource } : {}),
      ...(targetDimension ? { targetDimension } : {}),
      ...(currency ? { currency } : {}),
      ...(keyword ? { keyword } : {}),
    }),
    [feeSource, targetDimension, currency, keyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, r, t, p, e] = await Promise.all([
        fetchCostCollectionCapabilities(),
        fetchCostCollectionOptions(),
        fetchCostCollectionRules(),
        fetchCostCollectionRuleTemplates(),
        fetchCostCollectionPools(),
        fetchCostCollectionEntries(params, currentPage, 50),
      ]);
      setCaps(c);
      setOptions(o);
      setRules(r);
      setTemplates(t);
      setPools(p.items);
      setEntries(e);
    } catch (err) {
      setError(err instanceof Error ? err.message : '费用归集加载失败');
    } finally {
      setLoading(false);
    }
  }, [params, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 当前来源下可用的规则（只有启用中的规则能用来归集）。 */
  const activeRules = useMemo(
    () => rules.filter((rule) => rule.status === 'ACTIVE'),
    [rules],
  );

  /** 人工录入只能用订阅 / 人员 / 办公三类来源。 */
  const manualRules = useMemo(
    () =>
      activeRules.filter((rule) =>
        ['SUBSCRIPTION', 'PERSONNEL', 'OFFICE'].includes(rule.feeSource),
      ),
    [activeRules],
  );

  /** 归集动作的结果说明。 */
  function resultText(result: FinanceCostCollectionResult): string {
    const parts = [`归集 ${result.collected} 条`];
    if (result.skippedDuplicated > 0) parts.push(`跳过重复 ${result.skippedDuplicated} 条`);
    if (result.skippedNoFact > 0) parts.push(`跳过无事实 ${result.skippedNoFact} 条`);
    return parts.join('，');
  }

  async function runCollect() {
    setActionError('');
    setNotice('');
    setLastResult(null);
    try {
      let result: FinanceCostCollectionResult;
      if (action === 'PLATFORM_SETTLEMENT') {
        result = await collectPlatformSettlementCost(ruleId);
      } else if (action === 'AD_SPEND') {
        result = await collectAdSpendCost(ruleId, baseDate);
      } else if (action === 'LOGISTICS_WAYBILL') {
        result = await collectLogisticsCost(ruleId);
      } else if (action === 'HEAD_MILE_SHIPMENT') {
        result = await collectHeadMileCost(ruleId);
      } else {
        result = await collectManualCost({
          ruleId,
          sourceRefKey: manualKey,
          sourceRefCode: manualKey,
          subject: manualSubject,
          amount: Number(manualAmount),
          currency: manualCurrency,
          baseDate,
          periodStart: manualPeriodStart || null,
          periodEnd: manualPeriodEnd || null,
          quantity: null,
          evidenceRef: manualEvidence,
          note: '',
        });
      }
      setLastResult(result);
      setNotice(resultText(result));
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '归集失败');
    }
  }

  async function removeEntry(id: string) {
    setActionError('');
    setNotice('');
    try {
      await removeCostCollectionEntry(id);
      setNotice('已撤掉该条归集（池合计已同步回退）');
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '撤掉归集条目失败');
    }
  }

  async function openPool(id: string) {
    setActionError('');
    try {
      const { fetchCostCollectionPool } = await import('./cost-collection-model');
      setPoolDetail(await fetchCostCollectionPool(id));
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '费用池详情加载失败');
    }
  }

  if (loading && !entries) {
    return (
      <PageHeader
        title="费用归集"
        description="12.4.2.1 · 按规则把各类费用收进费用池，保留归集依据"
      />
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="费用归集"
        description="12.4.2.1 · 按规则把各类费用收进费用池，保留归集依据"
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

      {/* 🔴 归集与分摊的边界：页面必须把这句话说在前面。 */}
      <Notice tone="info" title="本页只做归集，不做分摊">
        下面的一切都是<b>待分摊的费用池</b>：「分摊目标维度」是规则声明的<b>目标</b>，
        不是分摊结果。分摊（按 SKU / 订单 / 期间拆开）属 12.4.2.2，尚未交付。
      </Notice>

      {notice ? <Notice tone="success" title="操作完成">{notice}</Notice> : null}
      {actionError ? (
        <Notice tone="danger" title="操作失败">
          {actionError}
        </Notice>
      ) : null}

      {/* ---------------- 1. 归集规则 ---------------- */}
      <SectionCard title="归集规则" description="费用来源 → 费用项 → 分摊目标维度 → 费用池">
        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">
            规则由租户自己维护，系统<b>不预置任何一行</b>（费用池怎么划分是租户的账务口径）。
            下面的蓝图标准绑定模板是<b>只读</b>的，可直接照着建规则。
          </p>
          {templates.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">费用来源</th>
                    <th className="py-2 pr-3">费用项</th>
                    <th className="py-2 pr-3">分摊目标维度</th>
                    <th className="py-2 pr-3">期间口径</th>
                    <th className="py-2 pr-3">建议池标识</th>
                    <th className="py-2 pr-3">本租户</th>
                  </tr>
                </thead>
                <tbody>
                  {templates.map((template) => (
                    <tr key={template.feeSource} className="border-b last:border-0">
                      <td className="py-2 pr-3">{costFeeSourceLabel(template.feeSource)}</td>
                      <td className="py-2 pr-3">{costFeeCategoryLabel(template.feeCategory)}</td>
                      <td className="py-2 pr-3">{costDimensionLabel(template.targetDimension)}</td>
                      <td className="py-2 pr-3">{costPeriodBasisLabel(template.periodBasis)}</td>
                      <td className="py-2 pr-3 font-mono text-xs">{template.poolKey}</td>
                      <td className="py-2 pr-3">
                        {template.configured ? (
                          <Badge tone="success">已配置</Badge>
                        ) : (
                          <Badge tone="neutral">未配置</Badge>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <EmptyState title="没有可用的规则模板" description="模板由服务端下发，请稍后重试" />
          )}

          {rules.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">规则</th>
                    <th className="py-2 pr-3">来源 → 费用项</th>
                    <th className="py-2 pr-3">目标维度</th>
                    <th className="py-2 pr-3">费用池</th>
                    <th className="py-2 pr-3">生效区间</th>
                    <th className="py-2 pr-3">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {rules.map((rule) => (
                    <tr key={rule.id} className="border-b last:border-0">
                      <td className="py-2 pr-3">
                        <div className="font-medium">{rule.ruleName}</div>
                        <div className="font-mono text-xs text-muted-foreground">{rule.ruleCode}</div>
                      </td>
                      <td className="py-2 pr-3">
                        {costFeeSourceLabel(rule.feeSource)} → {costFeeCategoryLabel(rule.feeCategory)}
                      </td>
                      <td className="py-2 pr-3">{costDimensionLabel(rule.targetDimension)}</td>
                      <td className="py-2 pr-3">{rule.poolName || rule.poolKey}</td>
                      <td className="py-2 pr-3 text-xs">
                        {rule.effectiveFrom} ~ {rule.effectiveTo ?? '不限'}
                      </td>
                      <td className="py-2 pr-3">
                        {rule.status === 'ACTIVE' ? (
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
              title="还没有归集规则"
              description="归集必须先有规则：按上面的蓝图标准绑定模板建一条，规则把「费用来源」绑到「费用项 → 分摊目标维度 → 费用池」。"
            />
          )}
        </div>
      </SectionCard>

      {/* ---------------- 2. 归集动作 ---------------- */}
      <SectionCard title="归集" description="把各类费用按规则收进费用池">
        <div className="space-y-4">
          <div className="flex flex-wrap gap-2">
            {COLLECT_ACTIONS.map((item) => (
              <Button
                key={item.key}
                variant={action === item.key ? 'default' : 'secondary'}
                onClick={() => setAction(item.key)}
              >
                {item.label}
              </Button>
            ))}
          </div>
          <p className="text-sm text-muted-foreground">
            {COLLECT_ACTIONS.find((item) => item.key === action)?.hint}
          </p>

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <label className="space-y-1 text-sm">
              <span className="text-xs text-muted-foreground">归集规则</span>
              <select
                className="w-full rounded-md border border-input bg-background px-2 py-2 text-sm"
                value={ruleId}
                onChange={(event) => setRuleId(event.target.value)}
              >
                <option value="">选择一条启用中的规则…</option>
                {(action === 'MANUAL' ? manualRules : activeRules).map((rule) => (
                  <option key={rule.id} value={rule.id}>
                    {rule.ruleName}（{costFeeSourceLabel(rule.feeSource)} ·{' '}
                    {costDimensionLabel(rule.targetDimension)}）
                  </option>
                ))}
              </select>
            </label>

            <label className="space-y-1 text-sm">
              <span className="text-xs text-muted-foreground">
                基准日期（{action === 'AD_SPEND' ? '必填：要关账的那个月' : '人工录入必填'}）
              </span>
              <Input
                type="date"
                value={baseDate}
                onChange={(event) => setBaseDate(event.target.value)}
              />
            </label>

            {action === 'MANUAL' ? (
              <>
                <label className="space-y-1 text-sm">
                  <span className="text-xs text-muted-foreground">费用单据号</span>
                  <Input
                    value={manualKey}
                    placeholder="如 SUB-2026-03"
                    onChange={(event) => setManualKey(event.target.value)}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-xs text-muted-foreground">金额</span>
                  <Input
                    value={manualAmount}
                    placeholder="如 899.00"
                    onChange={(event) => setManualAmount(event.target.value)}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-xs text-muted-foreground">币种</span>
                  <Input
                    value={manualCurrency}
                    placeholder="如 USD"
                    onChange={(event) => setManualCurrency(event.target.value)}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-xs text-muted-foreground">费用归属说明</span>
                  <Input
                    value={manualSubject}
                    placeholder="如 ERP 订阅 2026 年 3 月"
                    onChange={(event) => setManualSubject(event.target.value)}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-xs text-muted-foreground">归属期间起（会计期间口径必填）</span>
                  <Input
                    type="date"
                    value={manualPeriodStart}
                    onChange={(event) => setManualPeriodStart(event.target.value)}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-xs text-muted-foreground">归属期间止（会计期间口径必填）</span>
                  <Input
                    type="date"
                    value={manualPeriodEnd}
                    onChange={(event) => setManualPeriodEnd(event.target.value)}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-xs text-muted-foreground">凭据引用（发票号 / 合同号）</span>
                  <Input
                    value={manualEvidence}
                    onChange={(event) => setManualEvidence(event.target.value)}
                  />
                </label>
              </>
            ) : null}
          </div>

          <Button
            onClick={() => void runCollect()}
            disabled={!ruleId || (action === 'AD_SPEND' && !baseDate)}
          >
            开始归集
          </Button>

          {lastResult ? (
            <div className="rounded-md border border-border p-3 text-sm">
              <p className="font-medium">{resultText(lastResult)}</p>
              {lastResult.notes.length ? (
                <ul className="mt-2 list-disc space-y-1 pl-5 text-xs text-muted-foreground">
                  {lastResult.notes.slice(0, 20).map((note) => (
                    <li key={note}>{note}</li>
                  ))}
                </ul>
              ) : null}
            </div>
          ) : null}
        </div>
      </SectionCard>

      {/* ---------------- 3. 费用池 ---------------- */}
      <SectionCard title="费用池" description="按 池标识 × 维度 × 归属期间 × 币种 唯一">
        {pools.length ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">费用池</th>
                  <th className="py-2 pr-3">目标维度</th>
                  <th className="py-2 pr-3">归属期间</th>
                  <th className="py-2 pr-3">币种</th>
                  <th className="py-2 pr-3 text-right">合计</th>
                  <th className="py-2 pr-3 text-right">笔数</th>
                  <th className="py-2 pr-3">状态</th>
                </tr>
              </thead>
              <tbody>
                {pools.map((pool) => (
                  <tr key={pool.id} className="border-b last:border-0">
                    <td className="py-2 pr-3">
                      <button
                        type="button"
                        className="text-left underline-offset-2 hover:underline"
                        onClick={() => void openPool(pool.id)}
                      >
                        {pool.poolName || pool.poolKey}
                      </button>
                    </td>
                    <td className="py-2 pr-3">{costDimensionLabel(pool.dimension)}</td>
                    <td className="py-2 pr-3 text-xs">
                      {formatCostPeriod(pool.periodStart, pool.periodEnd)}
                    </td>
                    <td className="py-2 pr-3">{pool.currency}</td>
                    <td className="py-2 pr-3 text-right tabular-nums">
                      {formatCostMoney(pool.totalAmount, pool.currency)}
                    </td>
                    <td className="py-2 pr-3 text-right tabular-nums">{pool.entryCount}</td>
                    <td className="py-2 pr-3">
                      <Badge tone="neutral">未分摊</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState
            title="还没有费用池"
            description="费用池在第一次归集时自动建出来（按 池标识 × 维度 × 归属期间 × 币种）。"
          />
        )}

        {poolDetail ? (
          <div className="mt-4 rounded-md border border-border p-3">
            <div className="flex items-center justify-between">
              <p className="text-sm font-medium">
                {poolDetail.pool.poolName || poolDetail.pool.poolKey} ·{' '}
                {formatCostPeriod(poolDetail.pool.periodStart, poolDetail.pool.periodEnd)}
              </p>
              <Button variant="ghost" onClick={() => setPoolDetail(null)}>
                收起
              </Button>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              合计 {formatCostMoney(poolDetail.pool.totalAmount, poolDetail.pool.currency)} ·
              共 {poolDetail.pool.entryCount} 条 · 目标维度{' '}
              {costDimensionLabel(poolDetail.pool.dimension)} · 尚未分摊（分摊属 12.4.2.2）
            </p>
            <ul className="mt-2 space-y-1 text-xs">
              {poolDetail.entries.map((entry) => (
                <li key={entry.id} className="flex justify-between gap-3">
                  <span className="truncate">
                    {costFeeCategoryLabel(entry.feeCategory)} · {entry.sourceRefCode || entry.sourceRefKey}
                  </span>
                  <span className="tabular-nums">
                    {formatCostMoney(entry.amount, entry.currency)}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </SectionCard>

      {/* ---------------- 4. 归集明细 ---------------- */}
      <SectionCard title="归集明细" description="费用项 → 归集规则 → 费用池条目 → 归集依据">
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <label className="space-y-1 text-sm">
              <span className="text-xs text-muted-foreground">费用来源</span>
              <select
                className="w-full rounded-md border border-input bg-background px-2 py-2 text-sm"
                value={feeSource}
                onChange={(event) => {
                  setFeeSource(event.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部</option>
                {(options?.feeSources ?? []).map((source) => (
                  <option key={source} value={source}>
                    {costFeeSourceLabel(source)}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-xs text-muted-foreground">分摊目标维度</span>
              <select
                className="w-full rounded-md border border-input bg-background px-2 py-2 text-sm"
                value={targetDimension}
                onChange={(event) => {
                  setTargetDimension(event.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部</option>
                {(options?.dimensions ?? []).map((dimension) => (
                  <option key={dimension} value={dimension}>
                    {costDimensionLabel(dimension)}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-xs text-muted-foreground">币种</span>
              <select
                className="w-full rounded-md border border-input bg-background px-2 py-2 text-sm"
                value={currency}
                onChange={(event) => {
                  setCurrency(event.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部</option>
                {(options?.currencies ?? []).map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-xs text-muted-foreground">关键字</span>
              <Input
                value={keyword}
                placeholder="单据号 / 池名 / 备注"
                onChange={(event) => {
                  setKeyword(event.target.value);
                  setCurrentPage(0);
                }}
              />
            </label>
          </div>

          {entries?.totalsByCurrency?.length ? (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {entries.totalsByCurrency.map((total) => (
                <StatCard
                  key={total.currency}
                  label={`${total.currency} 归集合计`}
                  value={formatCostAmount(total.amount)}
                  comparisonLabel={`${total.count} 条 · 尚未分摊`}
                />
              ))}
            </div>
          ) : null}

          {entries?.totalsByDimension?.length ? (
            <div className="flex flex-wrap gap-2">
              {entries.totalsByDimension.map((total) => (
                <Badge key={`${total.dimension}-${total.currency}`} tone="neutral">
                  {costDimensionLabel(total.dimension)} · {total.currency}{' '}
                  {formatCostAmount(total.amount)}
                </Badge>
              ))}
            </div>
          ) : null}

          {entries && entries.items.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">费用项</th>
                    <th className="py-2 pr-3">目标维度</th>
                    <th className="py-2 pr-3">费用池 / 期间</th>
                    <th className="py-2 pr-3">归集依据</th>
                    <th className="py-2 pr-3 text-right">金额</th>
                    <th className="py-2 pr-3" />
                  </tr>
                </thead>
                <tbody>
                  {entries.items.map((entry) => (
                    <tr key={entry.id} className="border-b last:border-0 align-top">
                      <td className="py-2 pr-3">
                        <div>{costFeeCategoryLabel(entry.feeCategory)}</div>
                        <div className="text-xs text-muted-foreground">
                          {costFeeSourceLabel(entry.feeSource)}
                        </div>
                      </td>
                      <td className="py-2 pr-3">{costDimensionLabel(entry.targetDimension)}</td>
                      <td className="py-2 pr-3 text-xs">
                        <div>{entry.poolName || entry.poolKey}</div>
                        <div className="text-muted-foreground">
                          {formatCostPeriod(entry.periodStart, entry.periodEnd)}
                        </div>
                      </td>
                      <td className="py-2 pr-3 text-xs">
                        {entry.basis ? (
                          <>
                            <div>
                              {costBasisTypeLabel(entry.basis.basisType)} · {entry.basis.basisCode}
                            </div>
                            <div className="text-muted-foreground">
                              依据金额 {formatCostMoney(entry.basis.basisAmount, entry.basis.basisCurrency)}
                              {entry.basis.evidenceRef ? ` · 凭据 ${entry.basis.evidenceRef}` : ''}
                            </div>
                            <div className="text-muted-foreground">
                              基准日 {entry.baseDate} · 状态 未分摊
                            </div>
                          </>
                        ) : (
                          <span className="text-muted-foreground">无依据</span>
                        )}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums">
                        {formatCostMoney(entry.amount, entry.currency)}
                      </td>
                      <td className="py-2 pr-3 text-right">
                        <Button variant="ghost" onClick={() => void removeEntry(entry.id)}>
                          撤掉
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
                feeSource || targetDimension || currency || keyword
                  ? '当前筛选没有匹配的归集条目'
                  : '还没有归集条目'
              }
              description={
                feeSource || targetDimension || currency || keyword
                  ? '换个筛选条件看看，或清空筛选。'
                  : '先配一条归集规则，再从上游来源或人工录入归集第一笔费用。'
              }
            />
          )}

          {entries && entries.total > entries.size ? (
            <div className="flex items-center justify-between text-sm">
              <span className="text-xs text-muted-foreground">
                第 {entries.page + 1} 页 · 共 {entries.total} 条（每页 {entries.size} 条）
              </span>
              <div className="flex gap-2">
                <Button
                  variant="secondary"
                  disabled={entries.page <= 0}
                  onClick={() => setCurrentPage((page) => Math.max(0, page - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="secondary"
                  disabled={(entries.page + 1) * entries.size >= entries.total}
                  onClick={() => setCurrentPage((page) => page + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          ) : null}
        </div>
      </SectionCard>

      {caps && caps.capabilities.some((item) => !item.available) ? (
        <SectionCard title="能力边界" description="哪些没接通、归谁">
          <ul className="space-y-2 text-sm">
            {caps.capabilities
              .filter((item) => !item.available)
              .map((item) => (
                <li key={item.moduleId} className="rounded-md border border-border p-3">
                  <div className="font-medium">
                    {item.moduleId} · {item.dimension}
                    {item.ownerModule ? (
                      <span className="ml-2 text-xs text-muted-foreground">
                        归属 {item.ownerModule}
                      </span>
                    ) : null}
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">{item.note}</p>
                </li>
              ))}
          </ul>
        </SectionCard>
      ) : null}
    </div>
  );
}
