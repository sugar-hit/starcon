'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, Search } from 'lucide-react';
import { Button, EmptyState, FormField, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type { GoodsReceiptPage } from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  allocationStatusLabel,
  cancelAllocation,
  capabilityGaps,
  confirmAllocation,
  errorText,
  fetchCandidateReceipts,
  fetchAllocationCapabilities,
  fetchAllocationList,
  isFullyAllocated,
  locationTypeLabel,
  measureState,
  qualityRoleLabel,
  reasonLines,
  recommendLocations,
  releaseAllocation,
  remainingText,
  requiredTypeText,
  scoreDrift,
  type Candidate,
  type LinePlan,
} from './warehouse-location-allocation-model';

/**
 * 8.1.2.2 库位自动分配工作区。
 *
 * <p>蓝图：「按规则为入库商品推荐库位：同品类聚类、最短上架路径、容量与承重匹配、
 * 待检/残次专用库位强制规则。输入：入库 SKU 与数量；库位属性与占用；路径距离。
 * 输出：推荐库位列表（带推荐理由）。」红线：「**质量状态决定库位类型**：残次品入残次库位是
 * 硬规则，不允许混放。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>硬规则在界面上先说一遍。</b>每一行都写明「必须放进 {类型} 类库位」，
 *       而候选列表按构造就只含该类型 —— 界面不提供「虽然是残次品但也可以放存储位」这种
 *       选项，因为那在 DB 层就会被拒，给人一个点了必失败的按钮是自找骂。
 *   <li><b>「无法判定」与「通过」分开渲染。</b>容量/承重没量过时显示灰底「无法判定」，
 *       不给绿勾 —— 判不了就是判不了，伪装成通过比不显示更坏。
 *   <li><b>理由逐条列出并标出每条分值。</b>score 就是这些权重之和，当场可核对。
 *   <li><b>推荐 ≠ 已选定。</b>上半部分是随库存变化的实时推荐，下半部分是已落库的决策
 *       （带当时的理由快照）。两张表分开，不让人误以为推荐也已经定下来了。
 *   <li><b>未交付的维度如实列出</b>（巷道路径图 / catalog 类目 / 库存体积口径 / 上架任务），
 *       并写明归属模块。
 *   <li>加载 / 空 / 错误三态分开渲染。
 * </ul>
 */

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'PLANNED', label: '已选定' },
  { value: 'RELEASED', label: '已释放' },
  { value: 'CANCELLED', label: '已撤销' },
];

export function WarehouseLocationAllocationWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.facilities.read');
  const writable = session.permissions.includes('warehouse.facilities.update');

  const [caps, setCaps] = useState<Awaited<ReturnType<typeof fetchAllocationCapabilities>> | null>(null);
  const [receipts, setReceipts] = useState<GoodsReceiptPage | null>(null);
  const [receiptId, setReceiptId] = useState('');
  const [receiptIdInput, setReceiptIdInput] = useState('');
  const [needCapacity, setNeedCapacity] = useState('');
  const [needWeight, setNeedWeight] = useState('');

  const [plans, setPlans] = useState<LinePlan[] | null>(null);
  const [list, setList] = useState<Awaited<ReturnType<typeof fetchAllocationList>> | null>(null);
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);

  const [loading, setLoading] = useState(false);
  const [recommending, setRecommending] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const loadContext = useCallback(async () => {
    if (!readable) return;
    setLoading(true);
    setError('');
    try {
      const [capabilities, receiptPage] = await Promise.all([
        fetchAllocationCapabilities(),
        fetchCandidateReceipts(),
      ]);
      setCaps(capabilities);
      setReceipts(receiptPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [readable]);

  const loadList = useCallback(async () => {
    if (!readable) return;
    try {
      setList(
        await fetchAllocationList({ status: status || undefined, keyword: keyword || undefined, page, size: 20 }),
      );
    } catch (e) {
      setError(errorText(e));
    }
  }, [readable, status, keyword, page]);

  useEffect(() => {
    void loadContext();
  }, [loadContext]);
  useEffect(() => {
    void loadList();
  }, [loadList]);

  const runRecommend = useCallback(async () => {
    if (!receiptId) {
      setError('请先选择一张收货单：推荐的对象是入库 SKU 与数量。');
      return;
    }
    setRecommending(true);
    setError('');
    setNotice('');
    try {
      const result = await recommendLocations({
        receiptId,
        requiredCapacity: needCapacity,
        requiredWeight: needWeight,
      });
      setPlans(result);
      if (result.length === 0) setNotice('这张收货单没有可推荐的入库明细行。');
      else if (result.every(isFullyAllocated)) {
        setNotice('这张收货单的货已被全部指派上架，没有待上架数量。');
      }
    } catch (e) {
      setPlans(null);
      setError(errorText(e));
    } finally {
      setRecommending(false);
    }
  }, [receiptId, needCapacity, needWeight]);

  const choose = useCallback(
    async (plan: LinePlan, candidate: Candidate) => {
      setError('');
      setNotice('');
      try {
        const saved = await confirmAllocation({
          receiptId,
          receiptLineId: plan.receiptLineId,
          locationId: candidate.locationId,
          allocateQuantity: null,
          requiredCapacity: needCapacity,
          requiredWeight: needWeight,
        });
        setNotice(`已把「${plan.skuCode ?? plan.skuId}」指派到库位 ${saved.locationCode}。`);
        await runRecommend();
        await loadList();
      } catch (e) {
        setError(errorText(e));
      }
    },
    [receiptId, needCapacity, needWeight, runRecommend, loadList],
  );

  const release = useCallback(
    async (id: string) => {
      setError('');
      try {
        await releaseAllocation(id);
        setNotice('已释放该上架决策。');
        await loadList();
      } catch (e) {
        setError(errorText(e));
      }
    },
    [loadList],
  );

  const cancel = useCallback(
    async (id: string, code: string) => {
      const reason = window.prompt(`撤销库位 ${code} 的上架决策必须写原因（「为什么撤掉」是复盘必答题）：`);
      if (reason === null) return;
      if (reason.trim() === '') {
        setError('撤销原因不能为空。');
        return;
      }
      setError('');
      try {
        await cancelAllocation(id, reason);
        setNotice('已撤销，该收货明细行可以重新指派库位。');
        await loadList();
      } catch (e) {
        setError(errorText(e));
      }
    },
    [loadList],
  );

  const gaps = useMemo(() => (caps ? capabilityGaps(caps) : []), [caps]);
  const totals = list?.totals ?? null;

  return (
    <main className="space-y-4">
      <PageHeader
        title="库位自动分配"
        description="为入库商品推荐库位：同品类聚类、最短上架路径、容量与承重匹配；残次品入残次库位是硬规则。"
        actions={
          <Button variant="secondary" onClick={() => void loadContext()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="库位分配操作失败">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="操作完成">
          {notice}
        </Notice>
      )}
      {!readable && (
        <Notice tone="warning" title="没有查看权限">
          当前角色未被授予仓库档案（warehouse.facilities.read）的权限。
        </Notice>
      )}
      {gaps.length > 0 && (
        <Notice tone="info" title="以下维度尚未接入（已按替代口径计算，不假装已交付）">
          {gaps.map((gap) => (
            <span key={gap.flag} className="mr-4 inline-block">
              {gap.label}
              {gap.owner ? `（归 ${gap.owner}）` : '（本服务无真相来源）'}
            </span>
          ))}
        </Notice>
      )}
      {caps && <p className="text-xs leading-relaxed text-muted-foreground">{caps.note}</p>}

      {totals && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="上架决策"
            value={String(totals.decisionCount)}
            comparisonLabel={`已选定 ${totals.plannedCount} · 已释放 ${totals.releasedCount} · 已撤销 ${totals.cancelledCount}`}
          />
          <StatCard
            label="覆盖明细行"
            value={String(totals.coverageLineCount)}
            comparisonLabel="一行货同时只能有一个未取消的决策"
          />
          <StatCard
            label="残次 / 待检决策"
            value={`${totals.defectiveCount} / ${totals.pendingInspectionCount}`}
            comparisonLabel="两个质量角色，两个数不相加"
          />
          <StatCard
            label="上架数量合计"
            value={String(totals.allocateQuantity)}
            comparisonLabel="只统计未撤销的决策"
          />
        </div>
      )}

      <SectionCard title="按收货单推荐库位">
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="alloc-receipt" label="收货单" hint="从真实收货单列表里选，或在下方手填收货单 id">
            <select
              id="alloc-receipt"
              className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
              value={receiptId}
              onChange={(e) => setReceiptId(e.target.value)}
            >
              <option value="">请选择收货单…</option>
              {(receipts?.rows ?? []).map((row) => (
                <option key={row.id} value={row.id}>
                  {row.receiptCode} · {row.warehouseName} · {row.statusLabel}
                </option>
              ))}
            </select>
          </FormField>
          <FormField
            id="alloc-capacity"
            label="需求容量"
            hint="与库位容量同一单位；留空 = 未量过，容量规则将判不了"
          >
            <Input
              id="alloc-capacity"
              value={needCapacity}
              inputMode="decimal"
              placeholder="留空表示未量过"
              onChange={(e) => setNeedCapacity(e.target.value)}
            />
          </FormField>
          <FormField id="alloc-weight" label="需求承重" hint="留空 = 未量过，承重规则将判不了">
            <Input
              id="alloc-weight"
              value={needWeight}
              inputMode="decimal"
              placeholder="留空表示未量过"
              onChange={(e) => setNeedWeight(e.target.value)}
            />
          </FormField>
          <div className="flex items-end">
            <Button onClick={() => void runRecommend()} disabled={recommending || !readable}>
              <Search className="mr-2 h-4 w-4" />
              {recommending ? '推荐中…' : '推荐库位'}
            </Button>
          </div>
        </div>
        <div className="mt-3 flex flex-wrap items-end gap-2">
          <FormField id="alloc-receipt-id" label="或直接填写收货单 id" hint="列表没加载出来时也能用">
            <Input
              id="alloc-receipt-id"
              value={receiptIdInput}
              placeholder="收货单 id"
              onChange={(e) => setReceiptIdInput(e.target.value)}
            />
          </FormField>
          <Button
            variant="secondary"
            onClick={() => {
              setReceiptId(receiptIdInput.trim());
              setNotice('');
            }}
          >
            使用该 id
          </Button>
        </div>
      </SectionCard>

      {recommending && <p className="py-4 text-sm text-muted-foreground">正在按规则计算候选库位…</p>}

      {plans && plans.length === 0 && (
        <EmptyState title="没有可推荐的入库明细行" description="换一张收货单，或先在 8.2.1.1 收货单里登记实收。" />
      )}

      {(plans ?? []).map((plan) => (
        <SectionCard
          key={plan.receiptLineId}
          title={`${plan.skuCode ?? plan.skuId}${plan.skuName ? ` · ${plan.skuName}` : ''}（${qualityRoleLabel(plan.qualityRole)}）`}
        >
          <div className="mb-3 grid gap-2 text-sm sm:grid-cols-2 lg:grid-cols-4">
            <p>
              <span className="text-muted-foreground">质量状态：</span>
              {qualityRoleLabel(plan.qualityRole)}
              {plan.qualityLabel ? `（${plan.qualityLabel}）` : ''}
            </p>
            <p>
              <span className="text-muted-foreground">必须放进：</span>
              <span className="font-medium text-primary">{requiredTypeText(plan)}</span>
              <span className="ml-1 text-xs text-muted-foreground">（硬规则，不允许混放）</span>
            </p>
            <p>
              <span className="text-muted-foreground">待上架数量：</span>
              {plan.outstandingQuantity}
              {plan.sourceLocationCode ? (
                <span className="ml-1 text-xs text-muted-foreground">当前在 {plan.sourceLocationCode}</span>
              ) : null}
            </p>
            <p>
              <span className="text-muted-foreground">候选 / 已排除：</span>
              {plan.candidateCount} / {plan.rejectedLocationCount}
            </p>
          </div>

          {isFullyAllocated(plan) && (
            <Notice tone="info" title="这批货已被全部指派上架">
              没有待上架数量。如需改派，请先在下方决策清单里撤销原决策。
            </Notice>
          )}

          {plan.candidates.length === 0 && !isFullyAllocated(plan) && (
            <EmptyState
              title="没有符合硬规则且容量承重不超限的库位"
              description={plan.note}
            />
          )}

          <ul className="mt-2 space-y-3">
            {plan.candidates.map((candidate) => {
              const lines = reasonLines(candidate);
              const drift = scoreDrift(candidate);
              return (
                <li key={candidate.locationId} className="rounded-md border border-border p-3">
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <p className="text-sm font-medium">
                      {candidate.code} · {candidate.name}
                      <span className="ml-2 text-xs font-normal text-muted-foreground">
                        {locationTypeLabel(candidate.type)} · 层级 {candidate.depth} · 路径 {candidate.distance} 跳
                      </span>
                    </p>
                    <p className="text-sm">
                      <span className="text-muted-foreground">推荐分：</span>
                      <span className="font-medium">{candidate.score}</span>
                      {drift !== 0 && (
                        <span className="ml-2 text-xs text-destructive">
                          与理由合计相差 {drift}（前端公式与后端权重不一致）
                        </span>
                      )}
                    </p>
                  </div>

                  <p className="mt-1 text-xs text-muted-foreground">
                    容量：
                    {candidate.capacity === null ? '未量' : candidate.capacity}
                    {' · 已承诺 '}
                    {candidate.occupiedCapacity}
                    {' · '}
                    {measureState(candidate.capacityEvaluated, candidate.remainingCapacity)}
                    {' ｜ 承重：'}
                    {candidate.weightLimit === null ? '未量' : candidate.weightLimit}
                    {' · 已承诺 '}
                    {candidate.occupiedWeight}
                    {' · '}
                    {measureState(candidate.weightEvaluated, candidate.remainingWeight)}
                    {' ｜ 同品类聚类命中 '}
                    {candidate.affinitySkus} 个共置 SKU
                  </p>

                  <ul className="mt-2 flex flex-wrap gap-2">
                    {lines.map((line) => (
                      <li
                        key={line.text}
                        className={
                          line.advisory
                            ? 'rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground'
                            : 'rounded bg-primary/10 px-2 py-0.5 text-xs text-primary'
                        }
                      >
                        {line.text}
                        {line.weight > 0 ? ` +${line.weight}` : ''}
                      </li>
                    ))}
                  </ul>

                  {writable && !isFullyAllocated(plan) && (
                    <div className="mt-3">
                      <Button size="sm" onClick={() => void choose(plan, candidate)}>
                        指派到该库位
                      </Button>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
          <p className="mt-2 text-xs text-muted-foreground">{plan.note}</p>
        </SectionCard>
      ))}

      <SectionCard title="已选定的上架决策">
        <div className="mb-3 flex flex-wrap gap-2">
          <FormField id="alloc-status" label="状态">
            <select
              id="alloc-status"
              className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(1);
              }}
            >
              {STATUS_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="alloc-keyword" label="关键字" hint="匹配收货单号、SKU、库位编码与推荐理由">
            <Input
              id="alloc-keyword"
              value={keyword}
              placeholder="收货单号 / SKU / 库位"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(1);
              }}
            />
          </FormField>
        </div>

        {list && list.rows.length === 0 ? (
          <EmptyState title="还没有上架决策" description="在上方推荐并指派一个库位后，这里会出现可审计的决策记录。" />
        ) : null}

        {list && list.rows.length > 0 && (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-border text-xs text-muted-foreground">
                    <th className="py-2 pr-3">收货单</th>
                    <th className="py-2 pr-3">SKU</th>
                    <th className="py-2 pr-3">质量状态</th>
                    <th className="py-2 pr-3">源位 → 目标位</th>
                    <th className="py-2 pr-3">数量</th>
                    <th className="py-2 pr-3">路径</th>
                    <th className="py-2 pr-3">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {list.rows.map((row) => (
                    <tr key={row.id} className="border-b border-border/60 align-top">
                      <td className="py-2 pr-3">{row.receiptCode}</td>
                      <td className="py-2 pr-3">
                        {row.skuCode}
                        {row.skuName ? <span className="ml-1 text-xs text-muted-foreground">{row.skuName}</span> : null}
                      </td>
                      <td className="py-2 pr-3">
                        {qualityRoleLabel(row.qualityRole)}
                        <span className="ml-1 text-xs text-muted-foreground">
                          → {row.requiredTypeLabel ?? locationTypeLabel(row.requiredType)}
                        </span>
                      </td>
                      <td className="py-2 pr-3">
                        {row.sourceLocationCode} → {row.locationCode}
                      </td>
                      <td className="py-2 pr-3">{row.allocateQuantity}</td>
                      <td className="py-2 pr-3">{row.sourceDistance} 跳</td>
                      <td className="py-2 pr-3">
                        {allocationStatusLabel(row.status)}
                        {row.cancelReason ? (
                          <span className="ml-1 text-xs text-muted-foreground">（{row.cancelReason}）</span>
                        ) : null}
                      </td>
                      <td className="py-2">
                        {writable && row.status === 'PLANNED' && (
                          <span className="mr-2">
                            <button
                              type="button"
                              className="text-xs text-primary underline-offset-2 hover:underline"
                              onClick={() => void release(row.id)}
                            >
                              释放
                            </button>
                          </span>
                        )}
                        {writable && row.status !== 'CANCELLED' && (
                          <button
                            type="button"
                            className="text-xs text-destructive underline-offset-2 hover:underline"
                            onClick={() => void cancel(row.id, row.locationCode ?? row.id)}
                          >
                            撤销
                          </button>
                        )}
                        <p className="mt-1 text-xs text-muted-foreground">{row.ruleSummary}</p>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-3 flex items-center justify-between text-sm">
              <span className="text-muted-foreground">
                第 {list.page} 页 · 共 {list.total} 条（合计 {list.totals.decisionCount} 条，不受分页与筛选影响）
              </span>
              <span className="flex gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={list.page <= 1}
                  onClick={() => setPage((p) => Math.max(1, p - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={list.page * list.size >= list.total}
                  onClick={() => setPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </span>
            </div>
            <p className="mt-2 text-xs text-muted-foreground">{list.note}</p>
          </>
        )}
      </SectionCard>
    </main>
  );
}

export { remainingText };
