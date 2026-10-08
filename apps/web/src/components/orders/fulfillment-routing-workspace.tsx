'use client';

import { useCallback, useEffect, useState } from 'react';
import { Boxes, Filter, RefreshCw, Route, ShieldCheck } from 'lucide-react';
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
  DecisionPage,
  FulfillmentRoutingCapabilities,
  McfPage,
  RoutingDecisionDetail,
  SwitchPage,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  ApiError,
  advanceDecisionStatus,
  canAdvance,
  createRoutingDecision,
  evaluateRouting,
  fetchCapabilities,
  fetchDecision,
  fetchDecisions,
  fetchMcfAuthorizations,
  fetchSwitches,
  mcfStatusLabel,
  mcfUsable,
  moneyText,
  pathLabel,
  qtyText,
  reasonLabel,
  switchTriggerLabel,
  timeText,
} from './fulfillment-routing-model';

const PAGE_SIZE = 20;

/**
 * 9.3.2.2 FBA 与自发货选择工作区。
 *
 * <p>蓝图：「在平台仓（FBA/MFN）与自有/第三方仓之间选择履约路径：优先用平台仓（买家体验好）、
 * 平台仓缺货时切自发货（需符合平台的多渠道配送要求）、或按成本优先。」输出是
 * 「履约路径决策 + 缺货时切换记录」。
 *
 * <p><b>四个区块的顺序不是随意的</b>，它对应「先问资格，再问库存，最后才问成本」：
 *
 * <ol>
 *   <li><b>多渠道配送资质</b>：没有资质时缺货就**不许**切自发货（蓝图加粗句）。
 *       所以资质排在最前 —— 它不是配置项，是硬闸。</li>
 *   <li><b>路径评估</b>：只读试算，按当前库存与成本参数给出「应该走哪条路」。
 *       它不落库（试算留痕会污染决策历史）。</li>
 *   <li><b>决策与缺货切换</b>：落决策的同一次动作会写下切换记录，
 *       所以「为什么这单没走平台仓」在这里有独立区块与独立查询，而不是藏在详情里。</li>
 * </ol>
 *
 * <p>加载态 / 空态 / 错误态三态齐全：任何一块数据没取到都不会静默显示成 0 或空数组。
 */
export function FulfillmentRoutingWorkspace() {
  const [capabilities, setCapabilities] = useState<FulfillmentRoutingCapabilities | null>(null);
  const [mcf, setMcf] = useState<McfPage | null>(null);
  const [decisions, setDecisions] = useState<DecisionPage | null>(null);
  const [switches, setSwitches] = useState<SwitchPage | null>(null);
  const [detail, setDetail] = useState<RoutingDecisionDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [appliedKeyword, setAppliedKeyword] = useState('');

  // 评估表单
  const [orderRef, setOrderRef] = useState('');
  const [skuRef, setSkuRef] = useState('');
  const [requestedQty, setRequestedQty] = useState('1');
  const [platformAvailable, setPlatformAvailable] = useState('');
  const [policy, setPolicy] = useState('PLATFORM_FIRST');
  const [evaluation, setEvaluation] = useState<Awaited<
    ReturnType<typeof evaluateRouting>
  > | null>(null);
  const [evaluating, setEvaluating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [actionError, setActionError] = useState('');
  const [actionNote, setActionNote] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, mcfPage, decisionPage, switchPage] = await Promise.all([
        fetchCapabilities(),
        fetchMcfAuthorizations({ page: 0, size: PAGE_SIZE }),
        fetchDecisions({ keyword: appliedKeyword, page: 0, size: PAGE_SIZE }),
        fetchSwitches({ page: 0, size: PAGE_SIZE }),
      ]);
      setCapabilities(caps);
      setMcf(mcfPage);
      setDecisions(decisionPage);
      setSwitches(switchPage);
    } catch (e) {
      setError(loadError(e));
    } finally {
      setLoading(false);
    }
  }, [appliedKeyword]);

  useEffect(() => {
    void load();
  }, [load]);

  async function runEvaluation() {
    setEvaluating(true);
    setActionError('');
    setActionNote('');
    try {
      const result = await evaluateRouting({
        orderRef: orderRef.trim(),
        orderLineRef: '',
        platformCode: 'AMAZON',
        shopCode: 'SHOP-US',
        skuRef: skuRef.trim(),
        requestedQty: Number(requestedQty),
        platformAvailableQty: platformAvailable ? Number(platformAvailable) : null,
        at: null,
      });
      setEvaluation(result);
    } catch (e) {
      setEvaluation(null);
      setActionError(loadError(e));
    } finally {
      setEvaluating(false);
    }
  }

  async function saveDecision() {
    setSaving(true);
    setActionError('');
    setActionNote('');
    try {
      const saved = await createRoutingDecision({
        orderRef: orderRef.trim(),
        orderLineRef: '',
        platformCode: 'AMAZON',
        shopCode: 'SHOP-US',
        skuRef: skuRef.trim(),
        requestedQty: Number(requestedQty),
        selectedPolicy: policy,
        policySource: null,
        forceMerchantPath: false,
        reason: '',
        at: null,
      });
      setActionNote(
        saved.switchId
          ? `${saved.message}（切换记录 ${saved.switchId.slice(0, 8)}，触发原因：${switchTriggerLabel(
              saved.switchTrigger ?? '',
            )}）`
          : saved.message,
      );
      await load();
    } catch (e) {
      setActionError(loadError(e));
    } finally {
      setSaving(false);
    }
  }

  async function openDetail(decisionId: string) {
    setActionError('');
    try {
      setDetail(await fetchDecision(decisionId));
    } catch (e) {
      setActionError(loadError(e));
    }
  }

  async function confirm(decisionId: string) {
    setActionError('');
    try {
      await advanceDecisionStatus(decisionId, { status: 'CONFIRMED', reason: '' });
      await load();
      if (detail?.decision.id === decisionId) await openDetail(decisionId);
    } catch (e) {
      setActionError(loadError(e));
    }
  }

  const blockedByMcf = capabilities
    ? capabilities.dimensions.find((d) => d.code === 'MCF_PERMISSION')?.available === false
    : false;

  return (
    <div className="space-y-6">
      <PageHeader
        title="FBA 与自发货选择"
        description="在平台仓（FBA/MFN）与自有/第三方仓之间选择履约路径，并留下缺货切换记录。"
      />

      {error ? (
        <Notice tone="danger" title="数据加载失败">
          {error}
        </Notice>
      ) : null}
      {actionError ? (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      ) : null}
      {actionNote ? (
        <Notice tone="success" title="已完成">
          {actionNote}
        </Notice>
      ) : null}

      {/* 上游可用性：如实报出，不让调用方从空数组里猜哪一环没通 */}
      {capabilities ? (
        <SectionCard title="输入口径" description="哪些输入真有依据、缺的是什么。">
          <div className="grid gap-3 md:grid-cols-2">
            {capabilities.dimensions.map((d) => (
              <Notice
                key={d.code}
                tone={d.available ? 'info' : 'warning'}
                title={`${d.label}：${d.available ? '可用' : '不可用'}`}
              >
                {d.available ? d.source : `${d.source} —— ${d.unavailableReason ?? '原因未说明'}`}
              </Notice>
            ))}
          </div>
        </SectionCard>
      ) : null}

      <div className="grid gap-4 md:grid-cols-4">
        <StatCard label="决策总数（当前筛选）" value={decisions ? decisions.total : '—'} />
        <StatCard
          label="走平台仓"
          value={decisions ? decisions.platformPathCount : '—'}
          unit="买家体验优先"
        />
        <StatCard
          label="走自发货"
          value={decisions ? decisions.merchantPathCount : '—'}
          unit="平台仓不可用或成本更优"
        />
        <StatCard
          label="缺货切换"
          value={decisions ? decisions.switchedCount : '—'}
          unit="每条可追到触发原因"
        />
      </div>

      {/* 1. 资质闸门 */}
      <SectionCard
        title="多渠道配送资质"
        description="缺货时切自发货受平台政策约束：资质未核验或已过期都不许切。"
      >
        {loading ? (
          <Notice tone="info" title="加载中">
            正在读取资质登记…
          </Notice>
        ) : !mcf || mcf.items.length === 0 ? (
          <EmptyState
            title="还没有登记任何资质"
            description="没有资质时，平台仓缺货只能挂起，不能自动切自发货。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground text-xs">
                <tr>
                  <th className="py-2 pr-3">平台</th>
                  <th className="py-2 pr-3">店铺</th>
                  <th className="py-2 pr-3">状态</th>
                  <th className="py-2 pr-3">有效期</th>
                  <th className="py-2 pr-3">出处</th>
                  <th className="py-2">能否用于切换</th>
                </tr>
              </thead>
              <tbody>
                {mcf.items.map((row) => (
                  <tr key={row.id} className="border-border border-t">
                    <td className="py-2 pr-3">{row.platformCode}</td>
                    <td className="py-2 pr-3">{row.shopCode}</td>
                    <td className="py-2 pr-3">
                      {mcfStatusLabel(row.status)}
                      {row.expired && row.status === 'AUTHORIZED' ? (
                        <span className="text-muted-foreground">（已过期）</span>
                      ) : null}
                    </td>
                    <td className="py-2 pr-3">{timeText(row.expiresAt)}</td>
                    <td className="py-2 pr-3">{row.sourceReference || '—'}</td>
                    <td className="py-2">
                      {mcfUsable(row) ? (
                        '可以'
                      ) : row.status === 'AUTHORIZED' ? (
                        '需先续期'
                      ) : (
                        '需先核验'
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {blockedByMcf ? (
          <Notice tone="warning" title="资质维度当前不可用">
            缺货切换会被拒绝，直到资质被登记。
          </Notice>
        ) : null}
      </SectionCard>

      {/* 2. 评估（只读试算） */}
      <SectionCard
        title="路径评估"
        description="按当前库存、资质与成本参数算出「应该走哪条路」。这是试算，不会写入决策历史。"
      >
        <div className="grid gap-3 md:grid-cols-5">
          <FormField id="fr-order-ref" label="订单号">
            <Input value={orderRef} onChange={(e) => setOrderRef(e.target.value)} placeholder="SO-1001" />
          </FormField>
          <FormField id="fr-sku-ref" label="SKU 编号">
            <Input value={skuRef} onChange={(e) => setSkuRef(e.target.value)} placeholder="SKU-1" />
          </FormField>
          <FormField id="fr-qty" label="需求数量">
            <Input
              value={requestedQty}
              onChange={(e) => setRequestedQty(e.target.value)}
              inputMode="decimal"
            />
          </FormField>
          <FormField id="fr-platform-available" label="平台仓可用量（可留空）">
            <Input
              value={platformAvailable}
              onChange={(e) => setPlatformAvailable(e.target.value)}
              inputMode="decimal"
              placeholder="留空读快照"
            />
          </FormField>
          <label className="flex flex-col gap-1 text-copy-12">
            <span className="text-muted-foreground">决策策略</span>
            <select
              id="fr-policy"
              className="border-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
              value={policy}
              onChange={(e) => setPolicy(e.target.value)}
            >
              <option value="PLATFORM_FIRST">优先平台仓</option>
              <option value="COST_FIRST">成本优先</option>
              <option value="MERCHANT_FIRST">优先自发货</option>
            </select>
          </label>
        </div>
        <div className="mt-3 flex flex-wrap gap-2">
          <Button type="button" onClick={() => void runEvaluation()} disabled={evaluating}>
            <Route className="mr-2 h-4 w-4" />
            {evaluating ? '评估中…' : '试算'}
          </Button>
          <Button
            type="button"
            variant="secondary"
            onClick={() => void saveDecision()}
            disabled={saving || !orderRef.trim() || !skuRef.trim()}
          >
            {saving ? '落库中…' : '按此落一条决策'}
          </Button>
        </div>

        {evaluation ? (
          <div className="mt-4 space-y-3">
            <Notice
              tone={evaluation.mcfUsable ? 'info' : 'warning'}
              title={`判定结果：${pathLabel(evaluation.chosenPath)}（${reasonLabel(evaluation.reasonCode)}）`}
            >
              {evaluation.reason}
            </Notice>
            {evaluation.warnings.map((w) => (
              <Notice key={w} tone="warning" title="注意">
                {w}
              </Notice>
            ))}
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-muted-foreground text-xs">
                  <tr>
                    <th className="py-2 pr-3">路径</th>
                    <th className="py-2 pr-3">可用量</th>
                    <th className="py-2 pr-3">单件成本</th>
                    <th className="py-2 pr-3">时效（天）</th>
                    <th className="py-2 pr-3">是否可选</th>
                    <th className="py-2">排除原因</th>
                  </tr>
                </thead>
                <tbody>
                  {evaluation.candidates.map((c) => (
                    <tr key={c.pathKind} className="border-border border-t">
                      <td className="py-2 pr-3">
                        {pathLabel(c.pathKind)}
                        {c.chosen ? <span className="text-muted-foreground">（已选）</span> : null}
                      </td>
                      <td className="py-2 pr-3">{qtyText(c.availableQty)}</td>
                      <td className="py-2 pr-3">{moneyText(c.unitCost)}</td>
                      <td className="py-2 pr-3">{c.leadTimeDays ?? '—'}</td>
                      <td className="py-2 pr-3">{c.feasible ? '可选' : '不可选'}</td>
                      <td className="py-2">{c.infeasibleReason || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ) : null}
      </SectionCard>

      {/* 3. 缺货切换记录：蓝图的第二项输出，独立区块 */}
      <SectionCard
        title="缺货切换记录"
        description="每一行都带触发原因与当时的可用量快照：事后库存会被补上，只有这份快照能回答「当时缺多少」。"
      >
        {loading ? (
          <Notice tone="info" title="加载中">
            正在读取切换记录…
          </Notice>
        ) : !switches || switches.items.length === 0 ? (
          <EmptyState
            title="还没有缺货切换"
            description="平台仓库存能满足需求时不会产生切换记录，这是正常状态。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground text-xs">
                <tr>
                  <th className="py-2 pr-3">订单号</th>
                  <th className="py-2 pr-3">切换</th>
                  <th className="py-2 pr-3">触发原因</th>
                  <th className="py-2 pr-3">平台仓可用量</th>
                  <th className="py-2 pr-3">需求量</th>
                  <th className="py-2 pr-3">发生时间</th>
                  <th className="py-2">说明</th>
                </tr>
              </thead>
              <tbody>
                {switches.items.map((row) => (
                  <tr key={row.id} className="border-border border-t">
                    <td className="py-2 pr-3">{row.orderRef}</td>
                    <td className="py-2 pr-3">
                      {pathLabel(row.fromPath)} → {pathLabel(row.toPath)}
                    </td>
                    <td className="py-2 pr-3">{switchTriggerLabel(row.triggerReason)}</td>
                    <td className="py-2 pr-3">{qtyText(row.platformAvailableQty)}</td>
                    <td className="py-2 pr-3">{qtyText(row.requestedQty)}</td>
                    <td className="py-2 pr-3">{timeText(row.switchedAt)}</td>
                    <td className="py-2">{row.reason || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 4. 决策列表 */}
      <SectionCard title="履约路径决策" description="决策是举证凭据，不能改写；确认与作废走专门的动作。">
        <div className="flex flex-wrap items-end gap-2">
          <FormField id="fr-keyword" label="关键字（订单号 / SKU / 说明）">
            <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="SO-1001" />
          </FormField>
          <Button type="button" variant="secondary" onClick={() => setAppliedKeyword(keyword.trim())}>
            <Filter className="mr-2 h-4 w-4" />
            筛选
          </Button>
          <Button type="button" variant="ghost" onClick={() => void load()}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        </div>

        {loading ? (
          <Notice tone="info" title="加载中">
            正在读取决策…
          </Notice>
        ) : !decisions || decisions.items.length === 0 ? (
          <EmptyState
            title="还没有决策"
            description="用上面的「按此落一条决策」写下第一条履约路径决策。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground text-xs">
                <tr>
                  <th className="py-2 pr-3">订单号</th>
                  <th className="py-2 pr-3">选定路径</th>
                  <th className="py-2 pr-3">原因</th>
                  <th className="py-2 pr-3">平台仓可用量</th>
                  <th className="py-2 pr-3">单件成本</th>
                  <th className="py-2 pr-3">状态</th>
                  <th className="py-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {decisions.items.map((row) => (
                  <tr key={row.id} className="border-border border-t">
                    <td className="py-2 pr-3">{row.orderRef}</td>
                    <td className="py-2 pr-3">{pathLabel(row.chosenPath)}</td>
                    <td className="py-2 pr-3">{reasonLabel(row.reasonCode)}</td>
                    <td className="py-2 pr-3">{qtyText(row.platformAvailableQty)}</td>
                    <td className="py-2 pr-3">{moneyText(row.selectedUnitCost)}</td>
                    <td className="py-2 pr-3">{row.status}</td>
                    <td className="py-2">
                      <Button type="button" variant="ghost" onClick={() => void openDetail(row.id)}>
                        <Boxes className="mr-2 h-4 w-4" />
                        详情
                      </Button>
                      {canAdvance(row.status) ? (
                        <Button
                          type="button"
                          variant="ghost"
                          onClick={() => void confirm(row.id)}
                        >
                          <ShieldCheck className="mr-2 h-4 w-4" />
                          确认
                        </Button>
                      ) : null}
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
          title={`决策详情 · ${detail.decision.orderRef}`}
          description={detail.note}
        >
          <Notice tone="info" title={detail.decision.reason}>
            候选行是决策当时的库存与成本快照，事后不会被回算。
          </Notice>
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground text-xs">
                <tr>
                  <th className="py-2 pr-3">路径</th>
                  <th className="py-2 pr-3">可用量</th>
                  <th className="py-2 pr-3">单件成本</th>
                  <th className="py-2 pr-3">是否可选</th>
                  <th className="py-2">排除原因</th>
                </tr>
              </thead>
              <tbody>
                {detail.candidates.map((c) => (
                  <tr key={c.pathKind} className="border-border border-t">
                    <td className="py-2 pr-3">{pathLabel(c.pathKind)}</td>
                    <td className="py-2 pr-3">{qtyText(c.availableQty)}</td>
                    <td className="py-2 pr-3">{moneyText(c.unitCost)}</td>
                    <td className="py-2 pr-3">{c.feasible ? '可选' : '不可选'}</td>
                    <td className="py-2">{c.infeasibleReason || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <h3 className="mt-4 mb-2 text-sm font-medium">事件时间线</h3>
          <ul className="space-y-1 text-sm">
            {detail.events.map((e) => (
              <li key={e.id} className="text-muted-foreground">
                <span className="text-copy-13">{timeText(e.occurredAt)}</span> · {e.eventKind} ·{' '}
                {e.detail}
              </li>
            ))}
          </ul>
        </SectionCard>
      ) : null}
    </div>
  );
}

/** 把后端错误翻译成能直接显示的话。 */
function loadError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 0) return '网络不可用，请检查连接后重试';
    if (error.status === 403) return '当前角色没有履约路径的权限';
    return error.message || '操作失败，请稍后重试';
  }
  if (error instanceof Error) return error.message;
  return '未知错误，请稍后重试';
}