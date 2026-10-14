'use client';

import { useCallback, useEffect, useState } from 'react';
import { Boxes, Filter, RefreshCw, Scale, Warehouse } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  WarehouseAllocationCapabilities,
  WarehouseAllocationDecisionDetail,
  WarehouseAllocationDecisionPage,
  WarehouseAllocationDecisionRow,
  WarehouseAllocationHoldPage,
  WarehouseAllocationRulePage,
  WarehouseAllocationSimulation,
  WarehouseAllocationWarehousePage,
} from '../../../../../packages/api-contracts/src/index';
import {
  allocationStatusLabel,
  advanceAllocationStatus,
  allocationStatusLabel as statusLabel,
  blockReasonLabel,
  canAdvance,
  candidateChosen,
  createAllocation,
  crossLegalEntityWarning,
  crossesLegalEntity,
  dimensionUnavailableReason,
  fallbackPolicyLabel,
  fetchAllocation,
  fetchAllocationCapabilities,
  fetchAllocations,
  fetchHolds,
  fetchRules,
  fetchWarehouses,
  holdReasonLabel,
  isPartial,
  moneyText,
  qtyText,
  reasonCodeLabel,
  simulateAllocation,
  timeText,
} from './warehouse-allocation-model';

const PAGE_SIZE = 20;

/**
 * 9.3.2.1 分仓规则工作区。
 *
 * <p>蓝图：「配置并执行仓库分配：按库存可用性、运费成本、时效承诺、货权归属、
 * 买家地区匹配度选择发货仓；支持优先级与兜底策略。」输出是
 * 「分配结果（发货仓 + 分配数量）」与「无法分配的异常挂起」。
 *
 * <p><b>区块顺序对应判定顺序</b>（与后端「资格 → 库存 → 成本 → 打分」一致）：
 *
 * <ol>
 *   <li><b>仓库档案（货权）</b>：货权是仓库的属性，也是分仓的第一道闸 ——
 *       从别的法人的仓发货意味着收入记在那个法人名下。它排在最前，因为它决定
 *       「哪些仓有资格被选中」，不是选完之后再说的备注。</li>
 *   <li><b>分仓规则</b>：优先级、兜底策略与五维权重。权重合计必须为 1。</li>
 *   <li><b>分仓试算</b>：只读预演，逐仓列出可行性与排除原因，**不落库**。</li>
 *   <li><b>分配记录</b>：执行结果（发货仓 + 分配数量）。</li>
 *   <li><b>挂起清单</b>：无法分配的异常挂起，是蓝图输出的第二项，
 *       所以它有独立区块与独立查询，而不是只在下拉框里。</li>
 * </ol>
 *
 * <p>加载态 / 空态 / 错误态三态齐全：任何一块数据没取到都不会静默显示成 0 或空数组。
 */
export function WarehouseAllocationWorkspace() {
  const [capabilities, setCapabilities] = useState<WarehouseAllocationCapabilities | null>(null);
  const [warehouses, setWarehouses] = useState<WarehouseAllocationWarehousePage | null>(null);
  const [rules, setRules] = useState<WarehouseAllocationRulePage | null>(null);
  const [allocations, setAllocations] = useState<WarehouseAllocationDecisionPage | null>(null);
  const [holds, setHolds] = useState<WarehouseAllocationHoldPage | null>(null);
  const [detail, setDetail] = useState<WarehouseAllocationDecisionDetail | null>(null);
  const [simulation, setSimulation] = useState<WarehouseAllocationSimulation | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [appliedKeyword, setAppliedKeyword] = useState('');
  const [page, setPage] = useState(0);

  const [orderRef, setOrderRef] = useState('');
  const [skuRef, setSkuRef] = useState('');
  const [requestedQty, setRequestedQty] = useState('1');
  const [country, setCountry] = useState('US');
  const [region, setRegion] = useState('');
  const [orderEntity, setOrderEntity] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState('');
  const [actionNote, setActionNote] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, whs, rulePage, allocationPage, holdPage] = await Promise.all([
        fetchAllocationCapabilities(),
        fetchWarehouses({ activeOnly: true, page: 0, size: PAGE_SIZE }),
        fetchRules({ page: 0, size: PAGE_SIZE }),
        // exactOptionalPropertyTypes 下不能显式传 undefined：
        // 空关键字时干脆不放这个键，否则类型不通过（运行期行为一致）。
        appliedKeyword
          ? fetchAllocations({ keyword: appliedKeyword, page, size: PAGE_SIZE })
          : fetchAllocations({ page, size: PAGE_SIZE }),
        appliedKeyword
          ? fetchHolds({ keyword: appliedKeyword, page: 0, size: PAGE_SIZE })
          : fetchHolds({ page: 0, size: PAGE_SIZE }),
      ]);
      setCapabilities(caps);
      setWarehouses(whs);
      setRules(rulePage);
      setAllocations(allocationPage);
      setHolds(holdPage);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }, [appliedKeyword, page]);

  useEffect(() => {
    void load();
  }, [load]);

  // 订单货权主体默认带出仓库档案里的第一个法人，少一步手工输入。
  // 刻意**独立成一个 effect** 而不是写在 load 里：load 一旦读 orderEntity 就形成
  // 「load → 设 orderEntity → load 依赖变化 → load」的回环。
  // 也**不替用户猜**：档案为空时留空，由后端明确报错而不是这里静默填一个值。
  useEffect(() => {
    const firstEntity = warehouses?.items[0]?.legalEntityCode;
    if (!orderEntity && firstEntity) setOrderEntity(firstEntity);
  }, [orderEntity, warehouses]);

  /** 试算：只读，不落库。挂起时也返回 200 —— 挂起是业务结论，不是请求失败。 */
  const runSimulation = useCallback(async () => {
    setBusy(true);
    setActionError('');
    setActionNote('');
    try {
      const result = await simulateAllocation({
        orderRef: orderRef || 'PREVIEW',
        orderLineRef: '',
        platformCode: '',
        skuRef,
        requestedQty: Number(requestedQty),
        destinationCountryCode: country,
        destinationRegionCode: region,
        orderLegalEntityCode: orderEntity,
        ruleCode: null,
        at: null,
      });
      setSimulation(result);
    } catch (cause) {
      setSimulation(null);
      setActionError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setBusy(false);
    }
  }, [country, orderEntity, orderRef, region, requestedQty, skuRef]);

  /** 执行分配。 */
  const runAllocation = useCallback(async () => {
    setBusy(true);
    setActionError('');
    setActionNote('');
    try {
      const saved = await createAllocation({
        orderRef: orderRef || `SO-${Date.now()}`,
        orderLineRef: '',
        platformCode: '',
        skuRef,
        requestedQty: Number(requestedQty),
        destinationCountryCode: country,
        destinationRegionCode: region,
        orderLegalEntityCode: orderEntity,
        ruleCode: null,
        reason: '',
        at: null,
      });
      setActionNote(saved.message);
      setSimulation(null);
      setDetail(await fetchAllocation(saved.decisionId));
      await load();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setBusy(false);
    }
  }, [country, load, orderEntity, orderRef, region, requestedQty, skuRef]);

  const advance = useCallback(
    async (id: string, status: 'CONFIRMED' | 'RELEASED') => {
      setBusy(true);
      setActionError('');
      setActionNote('');
      try {
        const saved = await advanceAllocationStatus(id, {
          status,
          releasedToModule: status === 'RELEASED' ? 'MODULE_9_3_3_1' : '',
        });
        setActionNote(saved.message);
        setDetail(await fetchAllocation(id));
        await load();
      } catch (cause) {
        setActionError(cause instanceof Error ? cause.message : String(cause));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="分仓规则"
        description="按库存可用性、运费成本、时效承诺、货权归属与买家地区选择发货仓"
        actions={
          <>
            <Button onClick={() => void load()} disabled={loading}>
              <RefreshCw className="h-4 w-4" />
              刷新
            </Button>
          </>
        }
      />

      {error && (
        <Notice tone="danger" title="数据加载失败">
          {error}
        </Notice>
      )}
      {actionError && (
        <Notice tone="danger" title="操作被拒">
          {actionError}
        </Notice>
      )}
      {actionNote && (
        <Notice tone="success" title="已完成">
          {actionNote}
        </Notice>
      )}

      {capabilities && !capabilities.reservesUpstreamStock && (
        <Notice tone="info" title="本模块不预占上游库存">
          {capabilities.upstreamNote}
        </Notice>
      )}

      <div className="grid gap-4 md:grid-cols-4">
        <StatCard label="已分配" value={allocations?.allocatedCount ?? '—'} />
        <StatCard label="已挂起" value={allocations?.suspendedCount ?? '—'} />
        <StatCard label="部分分配" value={allocations?.partialCount ?? '—'} />
        <StatCard label="跨法人发货" value={allocations?.crossLegalEntityCount ?? '—'} />
      </div>

      {/* 区块 1：仓库档案（货权）—— 分仓的第一道资格闸 */}
      <SectionCard
        title="仓库档案与货权法人"
        description="货权归属是仓库的属性：从别的法人的仓发货意味着收入记在那个法人名下"
      >
        {loading && !warehouses ? (
          <p className="text-muted text-sm">加载中…</p>
        ) : warehouses && warehouses.items.length === 0 ? (
          <EmptyState
            title="还没有仓库档案"
            description="没有仓库档案就没有货权归属可依据，也无法执行分仓。先到后端登记发货仓与它的货权法人。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left">
                  <th>仓库</th>
                  <th>货权法人</th>
                  <th>区域</th>
                  <th>优先级</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                {warehouses?.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td>{row.warehouseName || row.warehouseRef}</td>
                    <td>
                      <span className="font-mono">{row.legalEntityCode}</span>
                    </td>
                    <td>{row.regionCode}</td>
                    <td>{row.defaultPriority}</td>
                    <td>{row.active ? '启用' : '停用'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 区块 2：分仓规则 */}
      <SectionCard
        title="分仓规则"
        description="优先级决定哪条规则生效；兜底策略决定首选仓不可行时怎么办"
      >
        {loading && !rules ? (
          <p className="text-muted text-sm">加载中…</p>
        ) : rules && rules.items.length === 0 ? (
          <EmptyState
            title="还没有分仓规则"
            description="没有命中规则时后端会明确报错而不是凭空决定从哪个仓发。先配一条规则。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left">
                  <th>优先级</th>
                  <th>规则</th>
                  <th>选仓口径</th>
                  <th>兜底策略</th>
                  <th>货权</th>
                  <th>权重（库存/成本/时效/货权/地区）</th>
                </tr>
              </thead>
              <tbody>
                {rules?.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td>{row.priority}</td>
                    <td>{row.ruleName || row.ruleCode}</td>
                    <td>{row.selectionPolicy}</td>
                    <td>{fallbackPolicyLabel(row.fallbackPolicy)}</td>
                    <td>{row.requireLegalEntityMatch ? '要求货权一致' : '不要求货权一致'}</td>
                    <td className="font-mono text-xs">
                      {row.weightStock} / {row.weightCost} / {row.weightLeadTime} /{' '}
                      {row.weightLegalEntity} / {row.weightRegion}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 区块 3：试算与执行分配 */}
      <SectionCard title="分仓试算与执行" description="试算只读不落库；执行分配才写决策与候选">
        <div className="grid gap-3 md:grid-cols-3">
          <FormField id="wa-orderRef" label="订单号">
            <Input
              value={orderRef}
              id="wa-orderRef"
              onChange={(e) => setOrderRef(e.target.value)}
            />
          </FormField>
          <FormField id="wa-skuRef" label="SKU">
            <Input
              value={skuRef}
              id="wa-skuRef"
              onChange={(e) => setSkuRef(e.target.value)}
              placeholder="SKU-1"
            />
          </FormField>
          <FormField id="wa-requestedQty" label="需求数量">
            <Input
              value={requestedQty}
              id="wa-requestedQty"
              onChange={(e) => setRequestedQty(e.target.value)}
              inputMode="decimal"
            />
          </FormField>
          <FormField id="wa-country" label="目的国（两位码）">
            <Input value={country} id="wa-country" onChange={(e) => setCountry(e.target.value)} />
          </FormField>
          <FormField id="wa-region" label="买家地区">
            <Input
              value={region}
              id="wa-region"
              onChange={(e) => setRegion(e.target.value)}
              placeholder="US-WEST"
            />
          </FormField>
          <FormField id="wa-orderEntity" label="订单货权主体">
            <Input
              value={orderEntity}
              id="wa-orderEntity"
              onChange={(e) => setOrderEntity(e.target.value)}
              placeholder="CN-ENT-A"
            />
          </FormField>
        </div>
        <div className="mt-3 flex gap-2">
          <Button onClick={() => void runSimulation()} disabled={busy || !skuRef}>
            试算（不落库）
          </Button>
          <Button onClick={() => void runAllocation()} disabled={busy || !skuRef || !orderEntity}>
            执行分配
          </Button>
        </div>

        {simulation && (
          <div className="mt-4 space-y-2">
            {simulation.chosenWarehouseRef ? (
              <Notice tone="success" title={`选定发货仓 ${simulation.chosenWarehouseRef}`}>
                {simulation.reason}
              </Notice>
            ) : (
              <Notice tone="warning" title="无可行仓，已挂起">
                {simulation.reason}
              </Notice>
            )}
            {crossLegalEntityWarning({
              chosenLegalEntityCode: simulation.chosenLegalEntityCode,
              orderLegalEntityCode: simulation.orderLegalEntityCode,
            }) && (
              <Notice tone="warning" title="跨法人发货">
                {crossLegalEntityWarning({
                  chosenLegalEntityCode: simulation.chosenLegalEntityCode,
                  orderLegalEntityCode: simulation.orderLegalEntityCode,
                })}
              </Notice>
            )}
            <CandidateTable candidates={simulation.candidates} />
            {(simulation.warnings ?? []).map((w) => (
              <p key={w} className="text-muted text-xs">
                {w}
              </p>
            ))}
          </div>
        )}
      </SectionCard>

      {/* 区块 4：分配记录 */}
      <SectionCard title="分配记录" description="分配结果是举证凭据：候选行不会被回算">
        <div className="mb-3 flex gap-2">
          <Input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="订单号 / SKU / 发货仓"
          />
          <Button
            onClick={() => {
              setAppliedKeyword(keyword);
              setPage(0);
            }}
          >
            搜索
          </Button>
        </div>
        {loading && !allocations ? (
          <p className="text-muted text-sm">加载中…</p>
        ) : allocations && allocations.items.length === 0 ? (
          <EmptyState
            title="还没有分配记录"
            description="执行一次分仓分配后，这里会出现发货仓与分配数量；无法分配的会进挂起清单。"
          />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left">
                    <th>订单</th>
                    <th>发货仓</th>
                    <th>货权法人</th>
                    <th>需求 / 已分</th>
                    <th>运费</th>
                    <th>状态</th>
                    <th>决策时间</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {allocations?.items.map((row) => (
                    <AllocationRowView
                      key={row.id}
                      row={row}
                      busy={busy}
                      onOpen={() => void fetchAllocation(row.id).then(setDetail)}
                      onAdvance={(status) => void advance(row.id, status)}
                    />
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-3 flex items-center gap-2 text-sm">
              <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                上一页
              </Button>
              <span>
                第 {page + 1} 页 / 共 {allocations?.total ?? 0} 条
              </span>
              <Button
                disabled={(allocations?.items.length ?? 0) < PAGE_SIZE}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </>
        )}
      </SectionCard>

      {/* 区块 5：挂起清单（蓝图输出的第二项） */}
      <SectionCard title="无法分配的挂起" description="挂起是终态：不会悄悄回落到某个默认仓">
        {loading && !holds ? (
          <p className="text-muted text-sm">加载中…</p>
        ) : holds && holds.items.length === 0 ? (
          <EmptyState title="没有挂起记录" description="当前没有发不出去的订单。" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left">
                  <th>订单</th>
                  <th>挂起原因</th>
                  <th>被排除仓数</th>
                  <th>逐仓排除原因</th>
                  <th>差额</th>
                  <th>时间</th>
                </tr>
              </thead>
              <tbody>
                {holds?.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td>{row.orderRef}</td>
                    <td>{holdReasonLabel(row.holdReason)}</td>
                    <td>{row.excludedWarehouseCount}</td>
                    <td className="text-xs">{row.exclusionDetail || '—'}</td>
                    <td>{qtyText(row.shortfallQty)}</td>
                    <td>{timeText(row.heldAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {detail && (
        <SectionCard title="分配详情" description={detail.note}>
          <p className="text-sm">
            订单 <span className="font-mono">{detail.decision.orderRef}</span> · 状态{' '}
            {statusLabel(detail.decision.status)} · 发货仓{' '}
            {detail.decision.chosenWarehouseRef ?? '（挂起，未选定）'} · 已分{' '}
            {qtyText(detail.decision.allocatedQty)} / {qtyText(detail.decision.requestedQty)}
          </p>
          {crossLegalEntityWarning(detail.decision) && (
            <Notice tone="warning" title="跨法人发货">
              {crossLegalEntityWarning(detail.decision)}
            </Notice>
          )}
          {isPartial(detail.decision.status) && (
            <Notice tone="warning" title="部分分配">
              差额已挂起，不会显示成已全部分配。
            </Notice>
          )}
          <CandidateTable candidates={detail.candidates} />
          {detail.holds.length > 0 && (
            <div className="mt-3 space-y-1 text-xs">
              {detail.holds.map((h) => (
                <p key={h.id}>
                  {holdReasonLabel(h.holdReason)}：{h.reason}
                </p>
              ))}
            </div>
          )}
          <div className="text-muted mt-3 space-y-1 text-xs">
            {detail.events.map((e) => (
              <p key={e.id}>
                {timeText(e.occurredAt)} · {e.eventKind} · {e.detail}
              </p>
            ))}
          </div>
        </SectionCard>
      )}

      {capabilities && (
        <SectionCard title="能力与上游可用性" description="哪些输入真有依据，由接口自己说">
          <ul className="space-y-1 text-sm">
            {capabilities.dimensions.map((d) => (
              <li key={d.code}>
                <span className={d.available ? 'text-foreground' : 'text-warning'}>
                  {d.available ? '可用' : '不可用'}
                </span>{' '}
                · {d.label} · <span className="text-muted text-xs">{d.source}</span>
                {!d.available && (
                  <span className="text-warning text-xs">
                    {' '}
                    {dimensionUnavailableReason(capabilities.dimensions, d.code)}
                  </span>
                )}
              </li>
            ))}
          </ul>
          <p className="text-muted mt-2 text-xs">{capabilities.legalEntityNote}</p>
        </SectionCard>
      )}
    </div>
  );
}

function CandidateTable({
  candidates,
}: {
  candidates: WarehouseAllocationSimulation['candidates'];
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left">
            <th>仓库</th>
            <th>货权法人</th>
            <th>可用量</th>
            <th>运费</th>
            <th>时效(天)</th>
            <th>综合分</th>
            <th>结论</th>
          </tr>
        </thead>
        <tbody>
          {candidates.map((c) => (
            <tr
              key={c.warehouseRef}
              className={`border-t ${candidateChosen(c) ? 'bg-accent/40' : ''}`}
            >
              <td>{c.warehouseName || c.warehouseRef}</td>
              <td>
                <span className="font-mono">{c.legalEntityCode}</span>
                {c.legalEntityMatch ? (
                  <span className="text-muted ml-1 text-xs">与订单一致</span>
                ) : (
                  <span className="text-warning ml-1 text-xs">与订单主体不同</span>
                )}
              </td>
              <td>{qtyText(c.availableQty)}</td>
              <td>{moneyText(c.shippingCost)}</td>
              <td>{c.leadTimeDays ?? '—'}</td>
              <td className="font-mono text-xs">{c.totalScore}</td>
              <td>
                {c.feasible ? (
                  candidateChosen(c) ? (
                    <span className="text-success">已选中</span>
                  ) : (
                    <span className="text-muted">可行未选中</span>
                  )
                ) : (
                  <span className="text-warning">
                    {blockReasonLabel(c.infeasibleReasonCode)}
                    {c.infeasibleReason ? ` · ${c.infeasibleReason}` : ''}
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AllocationRowView({
  row,
  busy,
  onOpen,
  onAdvance,
}: {
  row: WarehouseAllocationDecisionRow;
  busy: boolean;
  onOpen: () => void;
  onAdvance: (status: 'CONFIRMED' | 'RELEASED') => void;
}) {
  const cross = crossesLegalEntity(row);
  return (
    <tr className="border-t">
      <td className="font-mono text-xs">{row.orderRef}</td>
      <td>{row.chosenWarehouseRef ?? '—'}</td>
      <td>
        <span className="font-mono text-xs">{row.chosenLegalEntityCode ?? '—'}</span>
        {cross && <span className="text-warning ml-1 text-xs">跨法人</span>}
      </td>
      <td>
        {qtyText(row.allocatedQty)} / {qtyText(row.requestedQty)}
      </td>
      <td>{moneyText(row.unitShippingCost, row.currencyCode)}</td>
      <td>
        {allocationStatusLabel(row.status)}
        <span className="text-muted block text-xs">{reasonCodeLabel(row.reasonCode)}</span>
      </td>
      <td className="text-xs">{timeText(row.decidedAt)}</td>
      <td className="whitespace-nowrap text-right">
        <Button size="sm" variant="ghost" onClick={onOpen}>
          详情
        </Button>
        {canAdvance(row.status) && (
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => onAdvance('CONFIRMED')}>
            确认
          </Button>
        )}
        {row.status === 'CONFIRMED' && (
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => onAdvance('RELEASED')}>
            下发
          </Button>
        )}
      </td>
    </tr>
  );
}
