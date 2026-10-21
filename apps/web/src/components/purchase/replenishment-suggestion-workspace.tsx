'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Calculator, PackageSearch, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  ReplenishmentCapabilities,
  ReplenishmentDecisionInput,
  ReplenishmentSuggestionDetail,
  ReplenishmentSuggestionRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  adviceLabel,
  adviceTone,
  arrivalDateText,
  boxSpecText,
  computeReplenishmentBatch,
  dailySalesText,
  decideReplenishmentSuggestion,
  estimatedCostText,
  fetchReplenishmentBatches,
  fetchReplenishmentCapabilities,
  fetchReplenishmentSuggestion,
  fetchReplenishmentSuggestions,
  fmt,
  growthRateText,
  isActionable,
  rawRequiredText,
  statusLabel,
  suggestedQuantityText,
  upsertReplenishmentCoveragePolicy,
  upsertReplenishmentOrderPolicy,
  type ReplenishmentSuggestionParams,
} from './replenishment-suggestion-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.2.1.2 补货建议工作区。
 *
 * <p>蓝图：「计算建议补货量：目标库存覆盖天数 × 日均销量 −（可用 + 在途），并结合 MOQ、
 * 箱规、采购起订额做取整修正，给出建议供应商与到货时间。」输出「补货建议（建议量、取整说明、
 * 预计到货日、预计费用）；支持接受/调整/忽略」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>输出句的四项同排可见</b>：建议量 / 取整说明 / 预计到货日 / 预计费用 —— 少一列
 *       就没法下单。</li>
 *   <li><b>「答不出来」与「不需要补」分开展示</b>：缺日均销量 / 缺有效报价 / 缺箱规 /
 *       无需补货 是四个不同的状态，**不显示成 0** —— 0 会被读成「不用补」。</li>
 *   <li><b>每条建议都能展开看到计算过程</b>：日均销量怎么来的、扣了哪些在途、取整怎么做的
 *       （蓝图加粗句「建议必须展示计算过程」）。</li>
 *   <li><b>建议量与采购单位分开</b>：「22 箱 = 264 件」两列并排 + 箱规，否则会被当成两个
 *       可以互相比较的数量。</li>
 *   <li><b>处置要写理由</b>：接受 / 调整 / 忽略都要填说明 —— 一句「按建议下单」不是留痕。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */
export function ReplenishmentSuggestionWorkspace() {
  const [params, setParams] = useState<ReplenishmentSuggestionParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<
    ReturnType<typeof fetchReplenishmentSuggestions>
  > | null>(null);
  const [caps, setCaps] = useState<ReplenishmentCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [advice, setAdvice] = useState('');
  const [status, setStatus] = useState('');
  const [onlyActionable, setOnlyActionable] = useState(false);
  const [detail, setDetail] = useState<ReplenishmentSuggestionDetail | null>(null);
  const [deciding, setDeciding] = useState<ReplenishmentSuggestionRow | null>(null);
  const [computing, setComputing] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchReplenishmentSuggestions(params));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchReplenishmentCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：页面不假装自己知道规则。
        setCaps(null);
      }
    })();
  }, []);

  const rows = data?.rows ?? [];

  const stats = useMemo(() => {
    const items = data?.rows ?? [];
    let replenish = 0;
    let hold = 0;
    let unknown = 0;
    let cost = 0;
    for (const row of items) {
      if (row.advice === 'REPLENISH') replenish += 1;
      else if (row.advice === 'HOLD') hold += 1;
      else unknown += 1;
      if (row.estimatedCost !== null && row.estimatedCost !== undefined) cost += row.estimatedCost;
    }
    return { replenish, hold, unknown, cost };
  }, [data]);

  const openDetail = useCallback(async (id: string) => {
    try {
      setDetail(await fetchReplenishmentSuggestion(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const run = useCallback(
    async (work: () => Promise<unknown>) => {
      setBusy(true);
      setError(null);
      try {
        await work();
        await load();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const nextParams = (): ReplenishmentSuggestionParams => {
    const next: ReplenishmentSuggestionParams = { page: 0, size: 20 };
    if (keyword.trim()) next.keyword = keyword.trim();
    if (advice) next.advice = advice;
    if (status) next.status = status;
    if (onlyActionable) next.onlyActionable = true;
    return next;
  };

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="补货建议"
        description="按目标库存覆盖天数 × 日均销量 −（可用 + 在途）算净需求量，再结合 MOQ、箱规、采购起订额取整，给出建议供应商与到货时间。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setComputing(true)} disabled={busy}>
              <Calculator />
              计算补货建议
            </Button>
          </div>
        }
      />

      {/* 口径常驻：这一页的数从哪来、缺什么，必须一直摆在眼前 */}
      <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
        <PackageSearch className="mt-0.5 size-4 shrink-0" />
        <div>
          <p className="font-medium">
            净需求量 = 覆盖天数 × 日均销量 −（可用 + 覆盖窗口内可到达在途）
          </p>
          <p className="text-muted-foreground">
            {caps?.roundingFormula ??
              '日均销量取 6.2.1.1 的销售速度登记，可用量取仓库的可售量，在途只扣预计到达日落在覆盖窗口内的那些；箱规量不到时不给建议量（不猜成 1）。'}
          </p>
        </div>
      </div>

      {caps && !caps.firstMileLeadTimeAvailable && (
        <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <div>
            <p className="font-medium">预计到货日不含头程时效</p>
            <p className="text-muted-foreground">{caps.firstMileLeadTimeNote}</p>
          </div>
        </div>
      )}

      {data && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="清单 SKU" value={data.total} />
          <StatCard label="建议补货" value={stats.replenish} />
          <StatCard label="无需补货" value={stats.hold} />
          <StatCard label="答不出来（待补数据）" value={stats.unknown} />
        </div>
      )}

      {data?.note && <p className="text-muted-foreground text-xs">{data.note}</p>}

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="SKU 编码 / 名称 / 供应商"
              onChange={(e) => setKeyword(e.target.value)}
              className="w-64"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">建议类别</span>
            <select
              className="h-9 rounded-md border px-2"
              value={advice}
              onChange={(e) => setAdvice(e.target.value)}
            >
              <option value="">全部</option>
              <option value="REPLENISH">建议补货</option>
              <option value="HOLD">无需补货</option>
              <option value="NO_SALES_DATA">缺日均销量</option>
              <option value="NO_QUOTE">缺有效报价</option>
              <option value="NO_BOX_SPEC">缺箱规</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">处置状态</span>
            <select
              className="h-9 rounded-md border px-2"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">全部</option>
              <option value="SUGGESTED">待处置</option>
              <option value="ACCEPTED">已接受</option>
              <option value="ADJUSTED">已调整</option>
              <option value="IGNORED">已忽略</option>
            </select>
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={onlyActionable}
              onChange={(e) => setOnlyActionable(e.target.checked)}
            />
            只看建议补货
          </label>
          <Button onClick={() => setParams(nextParams())}>查询</Button>
        </div>
      </SectionCard>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {loading && <p className="text-muted-foreground text-sm">正在加载补货建议…</p>}

      {!loading && rows.length === 0 && !error && (
        <EmptyState
          title="没有补货建议"
          description="当前筛选条件下没有建议。先点「计算补货建议」生成一份批次，或放宽筛选条件。"
        />
      )}

      {!loading && rows.length > 0 && (
        <SectionCard title={`补货建议清单（${data?.total ?? 0}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">SKU</th>
                  <th className="px-2 py-1">日均销</th>
                  <th className="px-2 py-1">覆盖天数</th>
                  <th className="px-2 py-1">净需求</th>
                  <th className="px-2 py-1">建议量</th>
                  <th className="px-2 py-1">供应商</th>
                  <th className="px-2 py-1">预计到货日</th>
                  <th className="px-2 py-1">预计费用</th>
                  <th className="px-2 py-1">类别</th>
                  <th className="px-2 py-1">处置</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="px-2 py-1 font-medium">
                      {row.skuCode}
                      {row.skuName && (
                        <span className="text-muted-foreground"> / {row.skuName}</span>
                      )}
                    </td>
                    <td className="px-2 py-1">
                      {dailySalesText(row.dailySalesQuantity)}
                      {row.growthRate !== null && row.growthRate !== undefined && (
                        <span className="text-muted-foreground">
                          {' '}
                          （{growthRateText(row.growthRate)}）
                        </span>
                      )}
                    </td>
                    <td className="px-2 py-1">
                      {row.coverageDays} 天
                      <span className="text-muted-foreground"> · {row.coverageSourceLabel}</span>
                    </td>
                    <td className="px-2 py-1">{rawRequiredText(row.rawRequiredQuantity)}</td>
                    <td className="px-2 py-1">
                      {suggestedQuantityText(row.suggestedQuantity)}
                      {row.suggestedPurchaseQuantity !== null &&
                        row.suggestedPurchaseQuantity !== undefined && (
                          <span className="text-muted-foreground">
                            {' '}
                            （{fmt(row.suggestedPurchaseQuantity)}{' '}
                            {row.purchaseUnitCode ?? '采购单位'}）
                          </span>
                        )}
                    </td>
                    <td className="px-2 py-1">{row.supplierName || '—'}</td>
                    <td className="px-2 py-1">{arrivalDateText(row.expectedArrivalDate)}</td>
                    <td className="px-2 py-1">
                      {estimatedCostText(row.estimatedCost, row.currency)}
                    </td>
                    <td className="px-2 py-1">
                      <Badge tone={adviceTone(row.advice)} title={row.adviceBasis}>
                        {adviceLabel(row.advice)}
                      </Badge>
                    </td>
                    <td className="px-2 py-1">{statusLabel(row.status)}</td>
                    <td className="px-2 py-1 text-right">
                      <div className="flex justify-end gap-1">
                        {isActionable(row.advice) && row.status === 'SUGGESTED' && (
                          <Button variant="ghost" onClick={() => setDeciding(row)}>
                            处置
                          </Button>
                        )}
                        <Button variant="ghost" onClick={() => void openDetail(row.id)}>
                          详情
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      )}

      {/* 详情：计算过程必须能展开看到 —— 蓝图加粗句「建议必须展示计算过程」 */}
      {detail && (
        <Modal open onOpenChange={(open) => !open && setDetail(null)}>
          <ModalContent>
            <ModalHeader>
              <ModalTitle>补货建议详情 · {detail.suggestion.skuCode}</ModalTitle>
              <ModalDescription>{detail.suggestion.adviceBasis}</ModalDescription>
            </ModalHeader>
            <div className="flex flex-col gap-3">
              <SectionCard title="取整说明">
                <p className="text-sm">{detail.suggestion.roundingNote || '本条没有取整说明。'}</p>
                <p className="text-muted-foreground mt-2 text-xs">
                  供应商择优依据：{detail.suggestion.supplierSelectionBasis || '—'}
                </p>
                <p className="text-muted-foreground mt-1 text-xs">
                  到货日推导依据：{detail.suggestion.arrivalBasis || '—'}
                </p>
              </SectionCard>
              <SectionCard title="计算过程">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="px-2 py-1">步骤</th>
                      <th className="px-2 py-1">量</th>
                      <th className="px-2 py-1">口径</th>
                      <th className="px-2 py-1">来源</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.steps.map((step) => (
                      <tr key={step.stepOrder} className="border-t">
                        <td className="px-2 py-1">{step.stepKindLabel}</td>
                        <td className="px-2 py-1">{fmt(step.quantity)}</td>
                        <td className="text-muted-foreground px-2 py-1">{step.basis}</td>
                        <td className="text-muted-foreground px-2 py-1">{step.sourceRef}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </SectionCard>
              <SectionCard title="处置留痕">
                {detail.decisions.length === 0 ? (
                  <p className="text-muted-foreground text-sm">还没有处置记录。</p>
                ) : (
                  <ul className="flex flex-col gap-2 text-sm">
                    {detail.decisions.map((decision) => (
                      <li key={decision.id} className="rounded-md border px-2 py-1">
                        <span className="font-medium">{decision.actionLabel}</span>
                        {decision.quantity !== null && decision.quantity !== undefined && (
                          <span className="text-muted-foreground"> · {fmt(decision.quantity)}</span>
                        )}
                        <span className="text-muted-foreground">
                          {' '}
                          · {decision.note} · {momentOf(decision.decidedAt)}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </SectionCard>
            </div>
            <ModalFooter>
              <Button variant="ghost" onClick={() => setDetail(null)}>
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      )}

      {deciding && (
        <DecisionDialog
          row={deciding}
          busy={busy}
          onCancel={() => setDeciding(null)}
          onSubmit={async (input) => {
            const target = deciding;
            setDeciding(null);
            await run(async () => {
              await decideReplenishmentSuggestion(target.id, input);
            });
          }}
        />
      )}

      {computing && (
        <ComputeDialog
          busy={busy}
          onCancel={() => setComputing(false)}
          onRun={async (asOfDate, coverageDays) => {
            setComputing(false);
            await run(() =>
              computeReplenishmentBatch({
                asOfDate: asOfDate || null,
                defaultCoverageDays: coverageDays,
                note: null,
              }),
            );
          }}
        />
      )}

      <PolicySection busy={busy} onRun={run} />
      <BatchSection />
    </div>
  );
}

/** 处置一条建议（接受 / 调整 / 忽略）：说明必填。 */
function DecisionDialog({
  row,
  busy,
  onCancel,
  onSubmit,
}: {
  row: ReplenishmentSuggestionRow;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: ReplenishmentDecisionInput) => Promise<void>;
}) {
  const [action, setAction] = useState<'ACCEPT' | 'ADJUST' | 'IGNORE'>('ACCEPT');
  const [quantity, setQuantity] = useState(
    row.suggestedQuantity === null || row.suggestedQuantity === undefined
      ? ''
      : String(row.suggestedQuantity),
  );
  const [note, setNote] = useState('');
  const quantityValue = Number(quantity);
  const needsQuantity = action === 'ADJUST';
  const valid =
    note.trim() !== '' &&
    (!needsQuantity ||
      (quantity.trim() !== '' && Number.isFinite(quantityValue) && quantityValue > 0));

  return (
    <Modal open onOpenChange={(open) => !open && onCancel()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>处置补货建议 · {row.skuCode}</ModalTitle>
          <ModalDescription>
            建议量 {suggestedQuantityText(row.suggestedQuantity)}
            {row.suggestedPurchaseQuantity !== null &&
              row.suggestedPurchaseQuantity !== undefined && (
                <span className="text-muted-foreground">
                  {' '}
                  （{fmt(row.suggestedPurchaseQuantity)} {row.purchaseUnitCode ?? '采购单位'}）
                </span>
              )}
            ，供应商 {row.supplierName || '—'}
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">处置</span>
            <select
              className="h-9 rounded-md border px-2"
              value={action}
              onChange={(e) => setAction(e.target.value as 'ACCEPT' | 'ADJUST' | 'IGNORE')}
            >
              <option value="ACCEPT">接受（按建议量）</option>
              <option value="ADJUST">调整（改成别的数量）</option>
              <option value="IGNORE">忽略（不补这批）</option>
            </select>
          </label>
          {needsQuantity && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">调整后的数量（基础单位）</span>
              <Input
                type="number"
                min={0}
                step="0.000001"
                value={quantity}
                onChange={(e) => setQuantity(e.target.value)}
                className="w-40"
              />
            </label>
          )}
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">处置说明（必填）</span>
            <Input
              value={note}
              placeholder="为什么这么处置 —— 一句「按建议下单」不是留痕"
              onChange={(e) => setNote(e.target.value)}
              className="w-full"
            />
          </label>
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onCancel}>
            取消
          </Button>
          <Button
            disabled={!valid || busy}
            onClick={() =>
              void onSubmit({
                action,
                quantity:
                  action === 'IGNORE'
                    ? null
                    : action === 'ADJUST'
                      ? quantityValue
                      : (row.suggestedQuantity ?? null),
                note: note.trim(),
              })
            }
          >
            提交处置
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 计算一次补货建议的弹窗。 */
function ComputeDialog({
  busy,
  onCancel,
  onRun,
}: {
  busy: boolean;
  onCancel: () => void;
  onRun: (asOfDate: string, coverageDays: number) => Promise<void>;
}) {
  const [asOfDate, setAsOfDate] = useState('');
  const [coverage, setCoverage] = useState('30');
  const coverageDays = Number(coverage);
  const valid = Number.isFinite(coverageDays) && coverageDays >= 1 && coverageDays <= 365;
  return (
    <Modal open onOpenChange={(open) => !open && onCancel()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>计算补货建议</ModalTitle>
          <ModalDescription>
            日均销量取 6.2.1.1 的销售速度登记，可用量与在途取 warehouse 的授权只读接口，
            报价与箱规取 catalog 的授权只读接口；算完落成一份批次。
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-wrap gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">评估基准日（留空 = 今天）</span>
            <Input
              type="date"
              value={asOfDate}
              onChange={(e) => setAsOfDate(e.target.value)}
              className="w-48"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">兜底覆盖天数</span>
            <Input
              type="number"
              min={1}
              max={365}
              value={coverage}
              onChange={(e) => setCoverage(e.target.value)}
              className="w-32"
            />
          </label>
        </div>
        <p className="text-muted-foreground text-xs">
          兜底覆盖天数只在「按 SKU / 按分类」都没配时生效；配了就用配置里的。
        </p>
        <ModalFooter>
          <Button variant="ghost" onClick={onCancel}>
            取消
          </Button>
          <Button disabled={!valid || busy} onClick={() => void onRun(asOfDate, coverageDays)}>
            开始计算
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 覆盖天数目标 + 采购起订额 / 生产周期的配置区。 */
function PolicySection({
  busy,
  onRun,
}: {
  busy: boolean;
  onRun: (work: () => Promise<unknown>) => Promise<void>;
}) {
  const [scope, setScope] = useState<'DEFAULT' | 'SKU' | 'CATEGORY'>('DEFAULT');
  const [skuId, setSkuId] = useState('');
  const [categoryCode, setCategoryCode] = useState('');
  const [coverageDays, setCoverageDays] = useState('30');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [minOrderAmount, setMinOrderAmount] = useState('');
  const [currency, setCurrency] = useState('');
  const [productionDays, setProductionDays] = useState('');

  const coverageValue = Number(coverageDays);
  const coverageValid =
    Number.isFinite(coverageValue) &&
    coverageValue >= 1 &&
    coverageValue <= 365 &&
    effectiveFrom !== '' &&
    (scope !== 'SKU' || skuId.trim() !== '') &&
    (scope !== 'CATEGORY' || categoryCode.trim() !== '');

  return (
    <SectionCard title="补货配置（覆盖天数目标 / 采购起订额 / 生产周期）">
      <p className="text-muted-foreground mb-3 text-xs">
        覆盖天数目标按 <b>SKU &gt; 分类 &gt; 租户默认</b> 取；配置是<b>版本化</b>的：
        同作用范围再登记一次会把上一条收口，而不是覆盖它 ——
        这样「当时按哪个覆盖天数算的建议」永远查得回来。
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">作用范围</span>
          <select
            className="h-9 rounded-md border px-2"
            value={scope}
            onChange={(e) => setScope(e.target.value as 'DEFAULT' | 'SKU' | 'CATEGORY')}
          >
            <option value="DEFAULT">租户默认</option>
            <option value="SKU">按 SKU</option>
            <option value="CATEGORY">按分类</option>
          </select>
        </label>
        {scope === 'SKU' && (
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">SKU 逻辑 ID</span>
            <Input
              value={skuId}
              placeholder="uuid"
              onChange={(e) => setSkuId(e.target.value)}
              className="w-72"
            />
          </label>
        )}
        {scope === 'CATEGORY' && (
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">分类编码</span>
            <Input
              value={categoryCode}
              onChange={(e) => setCategoryCode(e.target.value)}
              className="w-48"
            />
          </label>
        )}
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">覆盖天数</span>
          <Input
            type="number"
            min={1}
            max={365}
            value={coverageDays}
            onChange={(e) => setCoverageDays(e.target.value)}
            className="w-28"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">生效起点</span>
          <Input
            type="date"
            value={effectiveFrom}
            onChange={(e) => setEffectiveFrom(e.target.value)}
            className="w-40"
          />
        </label>
        <Button
          disabled={!coverageValid || busy}
          onClick={() =>
            void onRun(() =>
              upsertReplenishmentCoveragePolicy({
                scope,
                skuId: scope === 'SKU' ? skuId.trim() : null,
                categoryCode: scope === 'CATEGORY' ? categoryCode.trim() : null,
                coverageDays: coverageValue,
                effectiveFrom,
                effectiveTo: null,
                note: '页面登记',
              }),
            )
          }
        >
          登记覆盖天数目标
        </Button>
      </div>

      <div className="mt-4 flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">采购起订额（金额，可留空）</span>
          <Input
            type="number"
            min={0}
            step="0.01"
            value={minOrderAmount}
            onChange={(e) => setMinOrderAmount(e.target.value)}
            className="w-40"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">币种</span>
          <Input
            value={currency}
            placeholder="USD"
            maxLength={3}
            onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            className="w-28"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">生产周期（天，可留空）</span>
          <Input
            type="number"
            min={0}
            max={365}
            value={productionDays}
            onChange={(e) => setProductionDays(e.target.value)}
            className="w-32"
          />
        </label>
        <Button
          disabled={busy}
          onClick={() =>
            void onRun(() =>
              upsertReplenishmentOrderPolicy({
                supplierId: null,
                supplierRoleId: null,
                minOrderAmount: minOrderAmount.trim() === '' ? null : Number(minOrderAmount),
                currency: currency.trim() === '' ? null : currency.trim(),
                productionLeadTimeDays:
                  productionDays.trim() === '' ? null : Number(productionDays),
                effectiveFrom:
                  effectiveFrom === '' ? new Date().toISOString().slice(0, 10) : effectiveFrom,
                effectiveTo: null,
                note: '页面登记（租户默认）',
              }),
            )
          }
        >
          登记采购起订额 / 生产周期
        </Button>
      </div>
      <p className="text-muted-foreground mt-2 text-xs">
        采购起订额只在<b>币种与报价一致</b>时参与取整（跨币种不比较金额，12 财务才有汇率表）。
      </p>
    </SectionCard>
  );
}

/** 历史批次。 */
function BatchSection() {
  const [batches, setBatches] = useState<
    Awaited<ReturnType<typeof fetchReplenishmentBatches>>['rows']
  >([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        setBatches((await fetchReplenishmentBatches({ page: 0, size: 10 })).rows);
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, []);

  if (error) {
    return (
      <SectionCard title="历史批次">
        <p className="text-sm text-red-600">{error}</p>
      </SectionCard>
    );
  }
  if (batches.length === 0) {
    return (
      <SectionCard title="历史批次">
        <p className="text-muted-foreground text-sm">还没有计算过补货建议。</p>
      </SectionCard>
    );
  }
  return (
    <SectionCard title="历史批次">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="text-muted-foreground text-left">
            <tr>
              <th className="px-2 py-1">批次号</th>
              <th className="px-2 py-1">评估基准日</th>
              <th className="px-2 py-1">SKU 数</th>
              <th className="px-2 py-1">建议补货</th>
              <th className="px-2 py-1">无需补货</th>
              <th className="px-2 py-1">答不出来</th>
              <th className="px-2 py-1">已处置</th>
              <th className="px-2 py-1">完整</th>
            </tr>
          </thead>
          <tbody>
            {batches.map((batch) => (
              <tr key={batch.id} className="border-t">
                <td className="px-2 py-1 font-medium">{batch.batchNo}</td>
                <td className="px-2 py-1">{batch.asOfDate}</td>
                <td className="px-2 py-1">{batch.itemCount}</td>
                <td className="px-2 py-1">{batch.replenishCount}</td>
                <td className="px-2 py-1">{batch.holdCount}</td>
                <td className="px-2 py-1">{batch.noSalesDataCount + batch.noQuoteCount}</td>
                <td className="px-2 py-1">{batch.decidedCount}</td>
                <td className="px-2 py-1">{batch.truncated ? '不完整' : '完整'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </SectionCard>
  );
}

/** 处置时间格式化（timestamptz 带偏移的 ISO 串）。 */
function momentOf(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}
