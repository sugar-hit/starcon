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
  FinanceCostAllocationBatch,
  FinanceCostAllocationBatchDetail,
  FinanceCostAllocationBatchPage,
  FinanceCostAllocationCapabilities,
  FinanceCostAllocationOptions,
  FinanceCostAllocationPool,
  FinanceCostAllocationPoolPage,
  FinanceCostAllocationResult,
  FinanceCostAllocationRule,
  FinanceCostAllocationTarget,
} from '../../../../../packages/api-contracts/src/index';
import {
  allocateCost,
  allocationBatchStatusLabel,
  allocationBasisLabel,
  allocationBasisSourceLabel,
  allocationDimensionLabel,
  allocationUnavailableText,
  collectCostAllocationTargets,
  createCostAllocationRule,
  fetchCostAllocationBatches,
  fetchCostAllocationCapabilities,
  fetchCostAllocationOptions,
  fetchCostAllocationPools,
  fetchCostAllocationRules,
  fetchCostAllocationTargets,
  formatAllocationAmount,
  formatAllocationMoney,
  formatAllocationPeriod,
  formatAllocationQuantity,
  formatAllocationRatio,
  isPoolAllocated,
  revertCostAllocationBatch,
  roundingPolicyLabel,
} from './cost-allocation-model';

/**
 * 12.4.2.2 分摊规则。
 *
 * <p>蓝图 12-finance.md：「配置费用分摊规则：分摊维度（SKU/SPU/店铺/订单/类目）、分摊基数（数量/金额/重量/体积/等额）、生效区间、分摊顺序与舍入差处理。」输入：「规则配置；待分摊费用池。」输出：「分摊结果（保留舍入差额到指定对象）+ 分摊明细。」关系：「上游 = 12.4.2.1；下游 = 13 利润（SKU 利润）。<b>分摊结果之和必须等于被分摊总额</b>（含舍入差处理），否则利润报表总额对不上 —— 这是财务最不能接受的错误。」
 *
 * <p><b>页面围绕三件事组织</b>：
 *
 * <ol>
 *   <li><b>分摊规则</b>（第一块）：维度 / 基数 / 生效区间 / 分摊顺序 / 舍入差处理。
 *       系统<b>不预置任何规则行</b>（费用怎么摊是租户自己的账务口径）。</li>
 *   <li><b>待分摊的费用池</b>（第二块）：上游 12.4.2.1 归集出来的池，
 *       采集分摊对象（来自真实上游事实）后执行分摊。</li>
 *   <li><b>分摊结果与明细</b>（第三、四块）：批次 + 逐对象明细，
 *       <b>被分摊总额 / 分摊总额 / 舍入差</b>三列并排，让那条等式肉眼可查。</li>
 * </ol>
 *
 * <p><b>🔴 页面绝不自己算账</b>：占比、舍入前金额、合计全部来自服务端；
 * 页面只做显示格式化。它唯一做的判断是「这个池已���分摊 / 未分摊」——
 * 那也是服务端给的（上游 12.4.2.1 留下的 {@code allocation_available}）。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有分摊对象 / 还没有分摊批次」。
 */

const DIMENSIONS = ['SKU', 'SPU', 'SHOP', 'ORDER', 'CATEGORY'] as const;
const BASES = ['QUANTITY', 'AMOUNT', 'WEIGHT', 'VOLUME', 'EQUAL'] as const;
const POLICIES = [
  'LARGEST_REMAINDER',
  'SMALLEST_REMAINDER',
  'FIRST_TARGET',
  'LAST_TARGET',
  'SPECIFIED',
] as const;

export function CostAllocationWorkspace() {
  const [caps, setCaps] = useState<FinanceCostAllocationCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceCostAllocationOptions | null>(null);
  const [rules, setRules] = useState<FinanceCostAllocationRule[]>([]);
  const [pools, setPools] = useState<FinanceCostAllocationPoolPage | null>(null);
  const [batches, setBatches] = useState<FinanceCostAllocationBatchPage | null>(null);
  const [targets, setTargets] = useState<FinanceCostAllocationTarget[]>([]);
  const [detail, setDetail] = useState<FinanceCostAllocationBatchDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 新建规则表单
  const [ruleCode, setRuleCode] = useState('');
  const [ruleName, setRuleName] = useState('');
  const [ruleDimension, setRuleDimension] = useState<string>('SKU');
  const [ruleBasis, setRuleBasis] = useState<string>('QUANTITY');
  const [rulePoolKey, setRulePoolKey] = useState('');
  const [rulePriority, setRulePriority] = useState('100');
  const [rulePolicy, setRulePolicy] = useState<string>('LARGEST_REMAINDER');
  const [ruleRoundingType, setRuleRoundingType] = useState<string>('SKU');
  const [ruleRoundingCode, setRuleRoundingCode] = useState('');
  const [ruleFrom, setRuleFrom] = useState('');
  const [ruleTo, setRuleTo] = useState('');

  // 分摊动作
  const [selectedPoolId, setSelectedPoolId] = useState('');
  const [collectDimension, setCollectDimension] = useState<string>('SKU');
  const [allocateRuleId, setAllocateRuleId] = useState('');

  const load = useCallback(async () => {
    setError('');
    try {
      const [capabilities, opts, ruleList, poolPage, batchPage] = await Promise.all([
        fetchCostAllocationCapabilities(),
        fetchCostAllocationOptions(),
        fetchCostAllocationRules(),
        fetchCostAllocationPools({ size: 50 }),
        fetchCostAllocationBatches({ size: 50 }),
      ]);
      setCaps(capabilities);
      setOptions(opts);
      setRules(ruleList);
      setPools(poolPage);
      setBatches(batchPage);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // 选中的池变化时，把它的分摊对象拉出来（对象是采集的，页面只是显示）。
  useEffect(() => {
    if (!selectedPoolId) {
      setTargets([]);
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const page = await fetchCostAllocationTargets({ poolId: selectedPoolId, size: 100 });
        if (!cancelled) setTargets(page.items);
      } catch {
        if (!cancelled) setTargets([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [selectedPoolId, batches]);

  const selectedPool = useMemo(
    () => pools?.items.find((pool) => pool.poolId === selectedPoolId) ?? null,
    [pools, selectedPoolId],
  );

  async function submitRule() {
    setActionError('');
    setNotice('');
    try {
      await createCostAllocationRule({
        ruleCode: ruleCode.trim(),
        ruleName: ruleName.trim() || ruleCode.trim(),
        targetDimension: ruleDimension as FinanceCostAllocationRule['targetDimension'],
        allocationBasis: ruleBasis as FinanceCostAllocationRule['allocationBasis'],
        poolKey: rulePoolKey.trim() || '*',
        priority: Number(rulePriority) || 100,
        roundingPolicy: rulePolicy as FinanceCostAllocationRule['roundingPolicy'],
        roundingTargetType:
          rulePolicy === 'SPECIFIED'
            ? (ruleRoundingType as FinanceCostAllocationRule['roundingTargetType'])
            : null,
        roundingTargetCode: rulePolicy === 'SPECIFIED' ? ruleRoundingCode.trim() : null,
        effectiveFrom: ruleFrom,
        effectiveTo: ruleTo || null,
        status: 'ACTIVE',
        note: '',
      });
      setNotice('分摊规则已创建');
      setRuleCode('');
      setRuleName('');
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '创建分摊规则失败');
    }
  }

  async function collect() {
    if (!selectedPoolId) {
      setActionError('先选一个待分摊的费用池');
      return;
    }
    setActionError('');
    setNotice('');
    try {
      const result = await collectCostAllocationTargets(selectedPoolId, collectDimension);
      const skipped = result.skipped > 0 ? `，跳过 ${result.skipped} 条` : '';
      setNotice(
        `已采集 ${result.collected} 个${allocationDimensionLabel(collectDimension)}分摊对象${skipped}` +
          (result.notes.length ? `。${result.notes.join('；')}` : ''),
      );
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '采集分摊对象失败');
    }
  }

  async function allocate() {
    if (!selectedPoolId) {
      setActionError('先选一个待分摊的费用池');
      return;
    }
    setActionError('');
    setNotice('');
    try {
      const result = await allocateCost(
        selectedPoolId,
        allocateRuleId.trim() ? allocateRuleId.trim() : undefined,
      );
      setDetail(result);
      setNotice(
        `分摊完成：被分摊总额 ${formatAllocationMoney(
          result.batch.sourceTotal,
          result.batch.currency,
        )}，分摊总额 ${formatAllocationMoney(
          result.batch.allocatedTotal,
          result.batch.currency,
        )}，舍入差 ${formatAllocationAmount(result.batch.roundingDifference)}`,
      );
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '执行分摊失败');
    }
  }

  async function openBatch(batch: FinanceCostAllocationBatch) {
    setActionError('');
    try {
      const { fetchCostAllocationBatch } = await import('./cost-allocation-model');
      setDetail(await fetchCostAllocationBatch(batch.id));
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '分摊明细加载失败');
    }
  }

  async function revert(batch: FinanceCostAllocationBatch) {
    setActionError('');
    setNotice('');
    try {
      const result = await revertCostAllocationBatch(batch.id);
      setDetail(result);
      setNotice('已撤销该次分摊（费用池回到未分摊，可按新规则重摊；历史结果留档）');
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '撤销分摊失败');
    }
  }

  if (loading && !pools) {
    return (
      <PageHeader
        title="分摊规则"
        description="12.4.2.2 · 按规则把费用池摊到 SKU / SPU / 店铺 / 订单 / 类目，零头落到指定对象"
      />
    );
  }

  const allocatedPoolCount =
    pools?.items.filter((pool) => isPoolAllocated(pool)).length ?? 0;
  const activeBatchCount =
    batches?.items.filter((batch) => batch.status === 'ALLOCATED').length ?? 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="分摊规则"
        description="12.4.2.2 · 按规则把费用池摊到 SKU / SPU / 店铺 / 订单 / 类目，零头落到指定对象"
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

      <Notice tone="info" title="分摊结果之和必须等于被分摊总额">
        这是本模块唯一必须恒成立的等式（蓝图原文：否则利润报表总额对不上）。
        舍入产生的零头<b>不消失</b>：它落到规则指定的���象上，并逐行留痕。
      </Notice>

      {notice ? <Notice tone="success" title="操作完成">{notice}</Notice> : null}
      {actionError ? (
        <Notice tone="danger" title="操作失败">
          {actionError}
        </Notice>
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="待分摊费用池" value={pools?.total ?? 0} />
        <StatCard label="本页已分摊的池" value={allocatedPoolCount} />
        <StatCard label="分摊批次" value={batches?.total ?? 0} />
        <StatCard label="本页有效批次" value={activeBatchCount} />
      </div>

      {/* ---------------- 1. 分摊规则 ---------------- */}
      <SectionCard
        title="分摊规则"
        description="分摊维度 · 分摊基数 · 生效区间 · 分摊顺序 · 舍入差处理"
      >
        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">
            规则由租户自己维护，系统<b>不预置任何一行</b>。
            生效区间必须覆盖费用池的归属期间才命中 ⇒ 新规则<b>不会追溯改写历史分摊</b>；
            多条规则命中时<b>分摊顺序数字小的先用</b>。
          </p>

          {rules.length === 0 ? (
            <EmptyState
              title="还没有分摊规则"
              description="先建一条规则：挑一个分摊维度和分摊基数，再定生效区间与舍入差处理。"
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">规则</th>
                    <th className="py-2 pr-3">分摊维度</th>
                    <th className="py-2 pr-3">分摊基数</th>
                    <th className="py-2 pr-3">适用池</th>
                    <th className="py-2 pr-3">顺序</th>
                    <th className="py-2 pr-3">生效区间</th>
                    <th className="py-2 pr-3">舍入差</th>
                    <th className="py-2 pr-3">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {rules.map((rule) => (
                    <tr key={rule.id} className="border-b last:border-0">
                      <td className="py-2 pr-3">
                        <div>{rule.ruleName}</div>
                        <div className="font-mono text-xs text-muted-foreground">{rule.ruleCode}</div>
                      </td>
                      <td className="py-2 pr-3">{allocationDimensionLabel(rule.targetDimension)}</td>
                      <td className="py-2 pr-3">{allocationBasisLabel(rule.allocationBasis)}</td>
                      <td className="py-2 pr-3 font-mono text-xs">{rule.poolKey}</td>
                      <td className="py-2 pr-3">{rule.priority}</td>
                      <td className="py-2 pr-3">
                        {formatAllocationPeriod(rule.effectiveFrom, rule.effectiveTo)}
                      </td>
                      <td className="py-2 pr-3">
                        <div>{roundingPolicyLabel(rule.roundingPolicy)}</div>
                        {rule.roundingTargetCode ? (
                          <div className="font-mono text-xs text-muted-foreground">
                            落到 {rule.roundingTargetCode}
                          </div>
                        ) : null}
                      </td>
                      <td className="py-2 pr-3">
                        <Badge tone={rule.status === 'ACTIVE' ? 'success' : 'neutral'}>
                          {rule.status === 'ACTIVE' ? '启用' : '停用'}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="space-y-3 rounded-md border p-4">
            <p className="text-sm font-medium">新建分摊规则</p>
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-sm">
                规则编码
                <Input
                  value={ruleCode}
                  onChange={(event) => setRuleCode(event.target.value)}
                  placeholder="R-SKU-QUANTITY"
                />
              </label>
              <label className="text-sm">
                规则名称
                <Input
                  value={ruleName}
                  onChange={(event) => setRuleName(event.target.value)}
                  placeholder="按销量把平台费摊到 SKU"
                />
              </label>
              <label className="text-sm">
                适用费用池
                <Input
                  value={rulePoolKey}
                  onChange={(event) => setRulePoolKey(event.target.value)}
                  placeholder="*（全部池）"
                />
              </label>
              <label className="text-sm">
                分摊维度
                <select
                  className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                  value={ruleDimension}
                  onChange={(event) => setRuleDimension(event.target.value)}
                >
                  {DIMENSIONS.map((dimension) => (
                    <option key={dimension} value={dimension}>
                      {allocationDimensionLabel(dimension)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                分摊基数
                <select
                  className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                  value={ruleBasis}
                  onChange={(event) => setRuleBasis(event.target.value)}
                >
                  {BASES.map((basis) => (
                    <option key={basis} value={basis}>
                      {allocationBasisLabel(basis)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                分摊顺序（数字小的先用）
                <Input
                  value={rulePriority}
                  onChange={(event) => setRulePriority(event.target.value)}
                  inputMode="numeric"
                />
              </label>
              <label className="text-sm">
                生效起始日
                <Input
                  type="date"
                  value={ruleFrom}
                  onChange={(event) => setRuleFrom(event.target.value)}
                />
              </label>
              <label className="text-sm">
                生效截止日（留空 = 长期有效）
                <Input
                  type="date"
                  value={ruleTo}
                  onChange={(event) => setRuleTo(event.target.value)}
                />
              </label>
              <label className="text-sm">
                舍入差处理
                <select
                  className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                  value={rulePolicy}
                  onChange={(event) => setRulePolicy(event.target.value)}
                >
                  {POLICIES.map((policy) => (
                    <option key={policy} value={policy}>
                      {roundingPolicyLabel(policy)}
                    </option>
                  ))}
                </select>
              </label>
              {rulePolicy === 'SPECIFIED' ? (
                <>
                  <label className="text-sm">
                    零头落点类型
                    <select
                      className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                      value={ruleRoundingType}
                      onChange={(event) => setRuleRoundingType(event.target.value)}
                    >
                      {DIMENSIONS.map((dimension) => (
                        <option key={dimension} value={dimension}>
                          {allocationDimensionLabel(dimension)}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="text-sm">
                    零头落点对象编码
                    <Input
                      value={ruleRoundingCode}
                      onChange={(event) => setRuleRoundingCode(event.target.value)}
                      placeholder="SKU-A"
                    />
                  </label>
                </>
              ) : null}
            </div>
            <Button onClick={() => void submitRule()} disabled={!ruleCode.trim() || !ruleFrom}>
              创建规则
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---------------- 2. 待分摊的费用池 ---------------- */}
      <SectionCard
        title="待分摊的费用池"
        description="上游 12.4.2.1 归集出来的池 · 采集分摊对象后执行分摊"
      >
        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">
            分摊对象<b>来自真实上游事实</b>（平台订单 / 销售订单行 / 商品主数据），
            本模块不凭空造对象。对象采集后会被<b>快照</b>下来 —— 之后上游改了也不影响已发生的分摊。
          </p>

          {pools && pools.items.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">费用池</th>
                    <th className="py-2 pr-3">归属期间</th>
                    <th className="py-2 pr-3">币种</th>
                    <th className="py-2 pr-3">被分摊总额</th>
                    <th className="py-2 pr-3">分摊对象</th>
                    <th className="py-2 pr-3">状态</th>
                    <th className="py-2 pr-3"></th>
                  </tr>
                </thead>
                <tbody>
                  {pools.items.map((pool) => (
                    <tr key={pool.poolId} className="border-b last:border-0">
                      <td className="py-2 pr-3">
                        <div>{pool.poolName}</div>
                        <div className="font-mono text-xs text-muted-foreground">{pool.poolKey}</div>
                      </td>
                      <td className="py-2 pr-3">
                        {formatAllocationPeriod(pool.periodStart, pool.periodEnd)}
                      </td>
                      <td className="py-2 pr-3">{pool.currency}</td>
                      <td className="py-2 pr-3 font-mono">
                        {formatAllocationAmount(pool.totalAmount)}
                      </td>
                      <td className="py-2 pr-3">{pool.targetCount}</td>
                      <td className="py-2 pr-3">
                        <Badge tone={isPoolAllocated(pool) ? 'success' : 'neutral'}>
                          {isPoolAllocated(pool) ? '已分摊' : '未分摊'}
                        </Badge>
                      </td>
                      <td className="py-2 pr-3">
                        <Button variant="ghost" onClick={() => setSelectedPoolId(pool.poolId)}>
                          选中
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <EmptyState
              title="还没有待分摊的费用池"
              description="先在 12.4.2.1 费用归集里归集出费用池，池合计就是这里的被分摊总额。"
            />
          )}

          <div className="space-y-3 rounded-md border p-4">
            <p className="text-sm font-medium">分摊动作</p>
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-sm">
                目标费用池
                <select
                  className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                  value={selectedPoolId}
                  onChange={(event) => setSelectedPoolId(event.target.value)}
                >
                  <option value="">（未选择）</option>
                  {pools?.items.map((pool) => (
                    <option key={pool.poolId} value={pool.poolId}>
                      {pool.poolKey} · {pool.currency} · {pool.totalAmount.toFixed(4)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                分摊维度（采集对象用）
                <select
                  className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                  value={collectDimension}
                  onChange={(event) => setCollectDimension(event.target.value)}
                >
                  {DIMENSIONS.map((dimension) => (
                    <option key={dimension} value={dimension}>
                      {allocationDimensionLabel(dimension)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                规则（留空 = 按分摊顺序自动选）
                <select
                  className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                  value={allocateRuleId}
                  onChange={(event) => setAllocateRuleId(event.target.value)}
                >
                  <option value="">（按分摊顺序自动选）</option>
                  {rules.map((rule) => (
                    <option key={rule.id} value={rule.id}>
                      {rule.ruleCode} · {allocationDimensionLabel(rule.targetDimension)} ·{' '}
                      {allocationBasisLabel(rule.allocationBasis)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" onClick={() => void collect()} disabled={!selectedPoolId}>
                采集分摊对象
              </Button>
              <Button onClick={() => void allocate()} disabled={!selectedPoolId}>
                执行分摊
              </Button>
            </div>
            {selectedPool ? (
              <p className="text-sm text-muted-foreground">
                当前选中：{selectedPool.poolKey} · {formatAllocationMoney(
                  selectedPool.totalAmount,
                  selectedPool.currency,
                )} · 已采集 {selectedPool.targetCount} 个分摊对象
              </p>
            ) : null}
          </div>

          {targets.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">分摊对象</th>
                    <th className="py-2 pr-3">维度</th>
                    <th className="py-2 pr-3">数量基数</th>
                    <th className="py-2 pr-3">金额基数</th>
                    <th className="py-2 pr-3">重量基数</th>
                    <th className="py-2 pr-3">体积基数</th>
                    <th className="py-2 pr-3">来源</th>
                    <th className="py-2 pr-3">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {targets.map((target) => (
                    <tr key={target.id} className="border-b last:border-0">
                      <td className="py-2 pr-3">
                        <div>{target.targetName}</div>
                        <div className="font-mono text-xs text-muted-foreground">
                          {target.targetCode}
                        </div>
                      </td>
                      <td className="py-2 pr-3">
                        {allocationDimensionLabel(target.targetDimension)}
                      </td>
                      <td className="py-2 pr-3 font-mono">
                        {formatAllocationQuantity(target.basisQuantity)}
                      </td>
                      <td className="py-2 pr-3 font-mono">
                        {formatAllocationAmount(target.basisAmount)}
                      </td>
                      <td className="py-2 pr-3 font-mono">
                        {formatAllocationQuantity(target.basisWeight)}
                      </td>
                      <td className="py-2 pr-3 font-mono">
                        {formatAllocationQuantity(target.basisVolume)}
                      </td>
                      <td className="py-2 pr-3">
                        {allocationBasisSourceLabel(target.basisSource)}
                      </td>
                      <td className="py-2 pr-3 text-xs text-muted-foreground">
                        {target.basisNote || '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : selectedPoolId ? (
            <EmptyState
              title="这个池还没有分摊对象"
              description="分摊对象来自真实上游事实，先点「采集分摊对象」。"
            />
          ) : null}
        </div>
      </SectionCard>

      {/* ---------------- 3. 分摊批次 ---------------- */}
      <SectionCard title="分摊批次" description="被分摊总额 · 分摊总额 · 舍入差（按币种分开）">
        {batches && batches.items.length > 0 ? (
          <div className="space-y-4">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">费用池</th>
                    <th className="py-2 pr-3">规则</th>
                    <th className="py-2 pr-3">被分摊总额</th>
                    <th className="py-2 pr-3">分摊总额</th>
                    <th className="py-2 pr-3">舍入差</th>
                    <th className="py-2 pr-3">零头落点</th>
                    <th className="py-2 pr-3">状态</th>
                    <th className="py-2 pr-3"></th>
                  </tr>
                </thead>
                <tbody>
                  {batches.items.map((batch) => (
                    <tr key={batch.id} className="border-b last:border-0">
                      <td className="py-2 pr-3">
                        <div>{batch.poolName}</div>
                        <div className="font-mono text-xs text-muted-foreground">{batch.poolKey}</div>
                      </td>
                      <td className="py-2 pr-3">
                        <div>{batch.ruleName}</div>
                        <div className="font-mono text-xs text-muted-foreground">{batch.ruleCode}</div>
                      </td>
                      <td className="py-2 pr-3 font-mono">
                        {formatAllocationAmount(batch.sourceTotal)}
                      </td>
                      <td className="py-2 pr-3 font-mono">
                        {formatAllocationAmount(batch.allocatedTotal)}
                      </td>
                      <td className="py-2 pr-3 font-mono">
                        {formatAllocationAmount(batch.roundingDifference)}
                      </td>
                      <td className="py-2 pr-3 font-mono text-xs">
                        {batch.roundingTargetCode ?? '—'}
                      </td>
                      <td className="py-2 pr-3">
                        <Badge tone={batch.status === 'ALLOCATED' ? 'success' : 'neutral'}>
                          {allocationBatchStatusLabel(batch.status)}
                        </Badge>
                      </td>
                      <td className="py-2 pr-3">
                        <div className="flex gap-1">
                          <Button variant="ghost" onClick={() => void openBatch(batch)}>
                            明细
                          </Button>
                          {batch.status === 'ALLOCATED' ? (
                            <Button variant="ghost" onClick={() => void revert(batch)}>
                              撤销
                            </Button>
                          ) : null}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {batches.totalsByCurrency.length > 0 ? (
              <div className="space-y-1 text-sm">
                {batches.totalsByCurrency.map((total) => (
                  <div key={total.currency} className="text-muted-foreground">
                    {total.currency}：被分摊总额 {formatAllocationAmount(total.sourceTotal)} · 分摊总额{' '}
                    {formatAllocationAmount(total.allocatedTotal)} · {total.batchCount} 个批次
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        ) : (
          <EmptyState
            title="还没有分摊批次"
            description="选一个待分摊的费用池，采集分摊对象后执行分摊。"
          />
        )}
      </SectionCard>

      {/* ---------------- 4. 分摊明细 ---------------- */}
      {detail ? (
        <SectionCard
          title="分摊明细"
          description={`批次 ${detail.batch.ruleCode} · 被分摊总额 ${formatAllocationMoney(
            detail.batch.sourceTotal,
            detail.batch.currency,
          )} · 分摊总额 ${formatAllocationMoney(
            detail.batch.allocatedTotal,
            detail.batch.currency,
          )} · 舍入差 ${formatAllocationAmount(detail.batch.roundingDifference)}`}
        >
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">分摊对象</th>
                  <th className="py-2 pr-3">基数</th>
                  <th className="py-2 pr-3">占比</th>
                  <th className="py-2 pr-3">舍入前金额</th>
                  <th className="py-2 pr-3">分摊额</th>
                  <th className="py-2 pr-3">零头调整</th>
                  <th className="py-2 pr-3">说明</th>
                </tr>
              </thead>
              <tbody>
                {detail.results.map((result) => (
                  <tr key={result.id} className="border-b last:border-0">
                    <td className="py-2 pr-3">
                      <div>{result.targetName}</div>
                      <div className="font-mono text-xs text-muted-foreground">
                        {result.targetCode}
                        {result.isRoundingTarget ? ' · 零头落点' : ''}
                      </div>
                    </td>
                    <td className="py-2 pr-3 font-mono">
                      {formatAllocationQuantity(result.basisValue)}
                    </td>
                    <td className="py-2 pr-3 font-mono">
                      {result.basisAvailable ? formatAllocationRatio(result.shareRatio) : '—'}
                    </td>
                    <td className="py-2 pr-3 font-mono">
                      {formatAllocationAmount(result.exactAmount)}
                    </td>
                    <td className="py-2 pr-3 font-mono">
                      {formatAllocationAmount(result.allocatedAmount)}
                    </td>
                    <td className="py-2 pr-3 font-mono">
                      {formatAllocationAmount(result.roundingAdjustment)}
                    </td>
                    <td className="py-2 pr-3 text-xs text-muted-foreground">
                      {allocationUnavailableText(result) || '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      ) : null}

      {/* ---------------- 5. 能力声明 ---------------- */}
      {caps ? (
        <SectionCard title="能力声明" description="哪些上游真接通、哪些还没交付、归谁">
          <div className="space-y-3 text-sm">
            {caps.capabilities.map((capability) => (
              <div key={capability.dimension} className="rounded-md border p-3">
                <div className="flex items-center gap-2">
                  <Badge tone={capability.available ? 'success' : 'neutral'}>
                    {capability.available ? '已接通' : '未接通'}
                  </Badge>
                  <span className="font-medium">{capability.dimension}</span>
                  <span className="font-mono text-xs text-muted-foreground">{capability.moduleId}</span>
                </div>
                <p className="mt-1 text-muted-foreground">{capability.note}</p>
                <p className="mt-1 font-mono text-xs text-muted-foreground">{capability.evidence}</p>
              </div>
            ))}
            {caps.gaps.length > 0 ? (
              <div className="rounded-md border p-3">
                <p className="font-medium">下游缺口</p>
                {caps.gaps.map((gap) => (
                  <div key={gap.moduleId} className="mt-1 text-muted-foreground">
                    <span className="font-mono text-xs">{gap.moduleId}</span> {gap.name} —{' '}
                    {gap.note}
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        </SectionCard>
      ) : null}

      {options ? null : null}
    </div>
  );
}
