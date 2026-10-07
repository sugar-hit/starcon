'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, RefreshCw, Scale, ShieldAlert, ShieldCheck } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  AllocationFloorInput,
  AllocationFloorPage,
  AllocationPolicyDetail,
  AllocationPolicyPage,
  PriorityAllocationResult,
  PriorityCapabilities,
  ResolutionPage,
  SkuRef,
} from '../../../../../packages/api-contracts/src/catalog';
import { fetchSkus } from './inventory-model';
import { fetchPolicies, fetchPolicy } from './allocation-policy-model';
import {
  createFloor,
  errorText,
  fetchFloors,
  fetchPriorityCapabilities,
  fetchResolutions,
  floorModeLabels,
  floorStatusLabels,
  floorStatusTone,
  floorText,
  intervalText,
  notNull,
  operatingModeLabels,
  percent,
  previewPriorityAllocation,
  qty,
  retireFloor,
  runPriorityAllocation,
  shortfallReasonLabels,
  suppressedBy,
  sumAllocated,
  supersedeFloor,
  suppressionReasonLabels,
} from './allocation-priority-model';
import { conservationText } from './allocation-priority-model';
import { useIdentity } from '../iam/identity-shell';

/** 保底编辑器草稿。提到父层是因为列表里的「换版」要按同一份草稿开新档。 */
export interface FloorDraft {
  memberId: string;
  floorMode: string;
  floorQuantity: number;
  floorRatio: number;
  floorSafety: number;
  floorNote: string;
}

/**
 * 7.8.1.2 优先级与保底工作区。
 *
 * <p>蓝图：「在分摊冲突时按优先级裁决，并为高价值店铺设置保底可用量与安全垫，防止低优先级店铺长期断货影响其店铺
 * 绩效与平台考核。输出：分摊结果（含被压低店铺的提示）；保底生效记录。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**守恒式摆三段，不摆两段**。除了 7.8.1.1 的两条，还要常驻
 *       「保底池 + 余量池 = 可分配量」。保底是在分摊**之前**先发的，
 *       这一条不摆出来时，「保底把货吃穿了」在界面上完全看不出来。
 *   <li>**每一行都并排显示「无保底基线」与「最终分摊」**。只显示最终值的界面
 *       答不出「它是被谁挤下去的」，而那正是本模块存在的意义。
 *   <li>**保底欠账与被压低分成两个查询开关**。前者是「承诺了但没兑现」，
 *       后者是「没轮到你而货被别人拿走了」—— 处置完全不同，不能混成一个「异常」列表。
 *   <li>**未纳入的上游写在页面上**。店铺绩效（17 域）未交付，所以保底的依据只有
 *       人定的数量/比例/安全垫。这句话常驻提示，不让人以为系统按绩效自动算底线。
 *   <li>**历史档只读**。改保底只能换版（收口 + 新开），页面不给「就地改」的入口。
 * </ul>
 */
export function AllocationPriorityWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  // 保底承诺一旦写进分摊就会真的从别的店手里拿走货 —— 写入必须走 update 权限。
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [caps, setCaps] = useState<PriorityCapabilities | null>(null);
  const [floors, setFloors] = useState<AllocationFloorPage | null>(null);
  const [policies, setPolicies] = useState<AllocationPolicyPage | null>(null);
  const [policyDetail, setPolicyDetail] = useState<AllocationPolicyDetail | null>(null);
  const [resolutions, setResolutions] = useState<ResolutionPage | null>(null);
  const [skus, setSkus] = useState<SkuRef[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [effectiveOnly, setEffectiveOnly] = useState(true);
  const [page, setPage] = useState(0);
  const [policyId, setPolicyId] = useState('');
  const [resolutionMode, setResolutionMode] = useState<'all' | 'shortfall' | 'suppressed'>('all');
  const [resolutionPage, setResolutionPage] = useState(0);

  const [skuId, setSkuId] = useState('');
  const [available, setAvailable] = useState('100');
  const [preview, setPreview] = useState<PriorityAllocationResult | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [floorBusy, setFloorBusy] = useState('');
  // 草稿状态**提到父层**：列表里的「换版」要按同一份草稿开新档，
  // 放在编辑器内部的话换版按钮就拿不到这些值，只能退化成「原样重开一档」的空操作。
  const [draft, setDraft] = useState<FloorDraft>({
    memberId: '',
    floorMode: 'QUANTITY',
    floorQuantity: 20,
    floorRatio: 0.2,
    floorSafety: 0,
    floorNote: '',
  });

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [cap, floorPage, policyPage, resolutionList, skuList] = await Promise.all([
        fetchPriorityCapabilities(),
        fetchFloors({ effectiveOnly, page, size: 20 }),
        fetchPolicies({ effectiveOnly: true, page: 0, size: 50 }),
        fetchResolutions({
          shortfallOnly: resolutionMode === 'shortfall',
          suppressedOnly: resolutionMode === 'suppressed',
          page: resolutionPage,
          size: 20,
        }),
        fetchSkus(),
      ]);
      setCaps(cap);
      setFloors(floorPage);
      setPolicies(policyPage);
      setResolutions(resolutionList);
      setSkus(skuList);
      if (skuList.length > 0 && !skuId) setSkuId(skuList[0]?.id ?? '');
      if (!policyId && policyPage.policies.filter(notNull).length > 0) {
        setPolicyId(policyPage.policies.filter(notNull)[0]?.id ?? '');
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [effectiveOnly, page, resolutionMode, resolutionPage, skuId, policyId]);

  useEffect(() => {
    if (!readable) return;
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readable, effectiveOnly, page, resolutionMode, resolutionPage]);

  // 选中的策略变了就把它的成员名册取出来：保底只能挂在**名册里的店**上，
  // 而名册正是这个下拉的来源 —— 界面上不给「配一个不在名册里的店」的入口。
  useEffect(() => {
    if (!readable || !policyId) {
      setPolicyDetail(null);
      return;
    }
    fetchPolicy(policyId)
      .then(setPolicyDetail)
      .catch((e) => setError(errorText(e)));
  }, [readable, policyId]);

  const doPreview = useCallback(async () => {
    setPreviewing(true);
    setError('');
    try {
      const results = await previewPriorityAllocation({
        skuId,
        availableQuantity: Number(available),
        ...(policyId ? { policyId } : {}),
      });
      setPreview(results[0] ?? null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setPreviewing(false);
    }
  }, [skuId, available, policyId]);

  const doRun = useCallback(async () => {
    setSaving(true);
    setError('');
    try {
      const results = await runPriorityAllocation({
        skuId,
        availableQuantity: Number(available),
        ...(policyId ? { policyId } : {}),
      });
      const result = results[0];
      setPreview(result ?? null);
      setNotice(
        result?.runId
          ? `已落库为分摊批次 ${result.runId}。批次 append-only：改不了也删不掉，`
            + '保底生效记录逐店留痕，下游 7.8.2.1 / 7.8.2.2 读的就是它。'
          : '未落库：没有解析到任何生效的分摊策略。',
      );
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }, [skuId, available, policyId, load]);

  if (!readable) {
    return (
      <EmptyState
        icon={<ShieldAlert className="h-6 w-6" />}
        title="没有保底配置的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  const floorList = floors?.floors.filter(notNull) ?? [];
  const suppressedCount = preview?.suppressedMemberCount ?? 0;
  const shortfallCount = preview?.shortfallMemberCount ?? 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="优先级与保底"
        description="货不够分时先保住谁：为高价值店铺设置保底可用量与安全垫，并在结果里标出被压低的店铺。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive flex items-start gap-3 rounded-md border p-4 text-sm">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div className="space-y-2">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              重新加载
            </Button>
          </div>
        </div>
      )}
      {notice && (
        <div className="border-success/40 bg-success-soft text-success rounded-md border p-4 text-sm">
          {notice}
        </div>
      )}

      {caps && !caps.performanceBasisAvailable && (
        <div className="border-warning/40 bg-warning-soft text-warning rounded-md border p-4 text-sm">
          <p className="font-medium">绩效依据未纳入本模块</p>
          <p className="mt-1">{caps.performanceBasisNote}</p>
        </div>
      )}
      {caps && (
        <div className="border-control-border bg-muted/40 text-muted-foreground space-y-2 rounded-md border p-4 text-xs">
          <div>
            <p className="text-foreground font-medium">三个「留一点」的区别</p>
            <p className="mt-1">{caps.poolNote}</p>
          </div>
          <div>
            <p className="text-foreground font-medium">可用量为什么必须传</p>
            <p className="mt-1">{caps.availableQuantityNote}</p>
          </div>
          <div>
            <p className="text-foreground font-medium">下游交接</p>
            <p className="mt-1">{caps.downstreamNote}</p>
          </div>
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-3">
        <StatCard
          label={effectiveOnly ? '在效保底' : '保底分档'}
          value={floors === null ? '—' : floors.total}
          polarity="neutral"
          comparisonLabel="保底是策略不是事实：只在货真的不够分时才起作用"
        />
        <StatCard
          label="本次被压低的店"
          value={preview === null ? '—' : suppressedCount}
          polarity={preview !== null && suppressedCount > 0 ? 'negative' : 'neutral'}
          comparisonLabel="拿到的比「没有保底时」少，界面并排显示基线"
        />
        <StatCard
          label="本次保底欠账的店"
          value={preview === null ? '—' : shortfallCount}
          polarity={preview !== null && shortfallCount > 0 ? 'negative' : 'neutral'}
          comparisonLabel="承诺存在过却没足额兑现，差额与原因逐店列出"
        />
      </div>

      <SectionCard
        title="带保底的分摊试算"
        description="试算一行都不写：先看结果，确认无误再落库。可用量必填，请传 7.2.2.1 已经算出的那个数。"
        extra={
          <div className="flex flex-wrap items-end gap-2">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">SKU</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={skuId}
                onChange={(e) => {
                  setSkuId(e.target.value);
                  setPreview(null);
                }}
              >
                {skus.length === 0 ? <option value="">没有可选 SKU</option> : null}
                {skus.filter(notNull).map((s) => (
                  <option key={s.id ?? ''} value={s.id ?? ''}>
                    {s.code} · {s.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">当前可用量</span>
              <input
                className="border-control-border bg-card w-28 rounded-md border px-2 py-1.5 text-sm"
                value={available}
                onChange={(e) => setAvailable(e.target.value)}
              />
            </label>
            <Button size="sm" onClick={() => void doPreview()} disabled={previewing}>
              {previewing ? '试算中…' : '试算'}
            </Button>
            {writable ? (
              <Button size="sm" variant="outline" onClick={() => void doRun()} disabled={saving}>
                分摊并落库
              </Button>
            ) : null}
          </div>
        }
      >
        {preview === null ? (
          <EmptyState
            icon={<Scale className="h-5 w-5" />}
            title={previewing ? '试算中' : '还没有试算结果'}
            description={
              previewing
                ? '正在先划保底池、再分余量。'
                : '选一个 SKU 与可用量后试算。保底只在货不够分时起作用，货够分时每家店拿多少由余量分配决定。'
            }
          />
        ) : (
          <PriorityResultView result={preview} />
        )}
      </SectionCard>

      <FloorEditor
        policies={policies}
        policyId={policyId}
        policyDetail={policyDetail}
        writable={writable}
        saving={saving}
        draft={draft}
        onPolicyChange={setPolicyId}
        onDraftChange={setDraft}
        onSaved={async (message) => {
          setNotice(message);
          setSaving(false);
          await load();
        }}
        onError={(message) => {
          setError(message);
          setSaving(false);
        }}
      />

      <SectionCard
        title="保底分档"
        description="一档 = 一个时间区间 + 写定后不可改。改保底走换版（收口旧档 + 开新档），历史那一档因此永远是「当时承诺过什么」的凭据。"
        extra={
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={effectiveOnly}
              onChange={(e) => {
                setEffectiveOnly(e.target.checked);
                setPage(0);
              }}
            />
            只看在效的
          </label>
        }
      >
        {floorList.length === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="h-5 w-5" />}
            title={effectiveOnly ? '当前没有在效的保底' : '还没有保底分档'}
            description={
              effectiveOnly
                ? '货够分时保底完全不介入。不配保底并不意味着平均分 —— 那会让每家店都以为有货。'
                : '在上方新建一档保底。店铺必须先在该策略的成员名册里，否则 DB 会直接拒掉。'
            }
          />
        ) : (
          <FloorTable
            floors={floorList}
            writable={writable}
            busy={floorBusy}
            onRetire={async (id) => {
              setFloorBusy(id);
              setError('');
              try {
                const saved = await retireFloor(id);
                setNotice(saved.message);
                await load();
              } catch (e) {
                setError(errorText(e));
              } finally {
                setFloorBusy('');
              }
            }}
            onSupersede={async (id) => {
              setFloorBusy(id);
              setError('');
              try {
                const row = floorList.find((f) => f.id === id);
                if (!row) throw new Error('找不到这一档保底，列表可能已经变了，请刷新后重试。');
                // 换版 = 收口这一档 + 按当前编辑器的取值开新档。
                // 不做「就地改」：DB 层已经把那条路堵死了（V76 的不可变触发器）。
                const saved = await supersedeFloor(id, {
                  policyId: row.policyId,
                  sourceSystem: row.sourceSystem,
                  referenceId: row.referenceId,
                  referenceLabel: row.referenceLabel,
                  floorMode: draft.floorMode,
                  floorRatio: draft.floorMode === 'RATIO' ? draft.floorRatio : 0,
                  floorQuantity: draft.floorMode === 'QUANTITY' ? draft.floorQuantity : 0,
                  safetyQuantity: draft.floorSafety,
                  effectiveFrom: null,
                  note: draft.floorNote,
                });
                setNotice(saved.message);
                await load();
              } catch (e) {
                setError(errorText(e));
              } finally {
                setFloorBusy('');
              }
            }}
          />
        )}
      </SectionCard>

      <SectionCard
        title="保底生效记录"
        description="事实，append-only。它回答的是「那次分摊为什么它没保住」—— 改不了也删不掉。"
        extra={
          <div className="flex flex-wrap items-end gap-2">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">看哪种问题</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={resolutionMode}
                onChange={(e) => {
                  setResolutionMode(e.target.value as 'all' | 'shortfall' | 'suppressed');
                  setResolutionPage(0);
                }}
              >
                <option value="all">全部</option>
                <option value="shortfall">保底没兑现</option>
                <option value="suppressed">被压低</option>
              </select>
            </label>
          </div>
        }
      >
        {resolutionMode === 'shortfall' ? (
          <p className="text-muted-foreground mb-2 text-xs">
            保底没兑现 ≠ 店铺不重要，而是**货真的不够**：该补货或调低别人的保底，而不是先动这家店的优先级。
          </p>
        ) : null}
        {resolutionMode === 'suppressed' ? (
          <p className="text-muted-foreground mb-2 text-xs">
            被压低 ≠ 没货卖，而是**没分到货**：货以别家店的保底名义被拿走了，该调保底或调优先级。
          </p>
        ) : null}
        {(resolutions?.resolutions.filter(notNull) ?? []).length === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="h-5 w-5" />}
            title="还没有保底生效记录"
            description="落库一次带保底的分摊，这里就会出现逐店的保底兑现与被压低留痕。"
          />
        ) : (
          <ResolutionTable resolutions={resolutions} />
        )}
      </SectionCard>
    </div>
  );
}

// ------------------------------------------------------------ 分摊结果

/**
 * 带保底的分摊结果。
 *
 * <p>试算与落库走**同一个组件**：两个端点只有权限与是否落库不同，
 * 渲染口径不同就会出现「试算看到的」与「落库记下的」两套数。
 */
function PriorityResultView({ result }: { result: PriorityAllocationResult | null | undefined }) {
  if (!result) return null;
  const lines = result.lines?.filter(notNull) ?? [];
  const summed = sumAllocated(lines);
  const consistent = Math.abs(summed - Number(result.allocatedTotal ?? 0)) < 1e-6;
  return (
    <div className="space-y-3">
      <div className="border-control-border bg-muted/40 text-muted-foreground space-y-1 rounded-md border p-3 text-xs">
        <p className="text-foreground font-medium">守恒式</p>
        {conservationText(result).map((line) => (
          <p key={line}>{line}</p>
        ))}
        <p className={consistent ? 'text-emerald-600 dark:text-emerald-400' : 'text-rose-600 dark:text-rose-400'}>
          前端自算 Σ分摊 = {summed.toLocaleString('zh-CN')} 件，
          {consistent ? '与后端一致' : '与后端不一致 —— 这是必须被看见的错账'}
        </p>
        <p className="text-muted-foreground">
          无保底基线合计 {qty(result.baseAllocatedTotal)} 件 · 最终分摊合计 {qty(result.allocatedTotal)} 件
        </p>
      </div>

      <table className="w-full text-sm">
        <thead className="text-muted-foreground text-left text-xs">
          <tr>
            <th className="py-1 text-right">次序</th>
            <th className="py-1">店铺</th>
            <th className="py-1">保底</th>
            <th className="py-1 text-right">保底实得</th>
            <th className="py-1 text-right">余量</th>
            <th className="py-1 text-right">无保底基线</th>
            <th className="py-1 text-right">最终分摊</th>
            <th className="py-1">为什么</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((l) => (
            <tr key={l.referenceId ?? ''} className="border-control-border border-t">
              <td className="text-muted-foreground py-1 text-right">{l.floorOrder}</td>
              <td className="py-1">
                {l.referenceLabel || l.referenceId}
                <span className="text-muted-foreground ml-1 text-xs">
                  {operatingModeLabels[l.shopOperatingMode] ?? l.shopOperatingMode}
                </span>
              </td>
              <td className="text-muted-foreground py-1 text-xs">{floorText(l)}</td>
              <td className={`py-1 text-right ${floorStatusTone(l.floorStatus)}`}>
                {qty(l.floorGranted)}
              </td>
              <td className="py-1 text-right">{qty(l.residualQty)}</td>
              <td className="text-muted-foreground py-1 text-right">{qty(l.baseAllocatedQty)}</td>
              <td className="py-1 text-right font-medium">{qty(l.allocatedQty)}</td>
              <td className={`py-1 text-xs ${floorStatusTone(l.floorStatus)}`}>
                {reasonText(l)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <p className="text-muted-foreground text-xs">{result.note}</p>
      {result.runId ? (
        <p className="text-muted-foreground text-xs">
          已落库为分摊批次 <span className="font-mono">{result.runId}</span>。
        </p>
      ) : null}
    </div>
  );
}

/**
 * 一行的「为什么」。
 *
 * <p>被压低的行必须显式带出「被谁挤下去」：只显示最终分摊额的话，
 * 运营看到的是「这家店今天只有 3 件」，但答不出原因，而处置动作全靠这个原因区分。
 */
function reasonText(l: {
  floorRequested: number | string | null;
  floorShortfall: number | string | null;
  shortfallReason: string | null;
  suppressed: boolean | null;
  suppressionReason: string | null;
  baseAllocatedQty: number | string | null;
  allocatedQty: number | string | null;
}): string {
  const parts: string[] = [];
  const shortfall = Number(l.floorShortfall ?? 0);
  if (shortfall > 0) {
    parts.push(`保底欠 ${qty(shortfall)} 件（${shortfallReasonLabels[l.shortfallReason ?? ''] ?? '原因未标注'}）`);
  } else if (Number(l.floorRequested ?? 0) > 0) {
    parts.push('保底足额兑现');
  } else {
    parts.push('未配保底');
  }
  if (l.suppressed) {
    parts.push(
      `${suppressionReasonLabels[l.suppressionReason ?? ''] ?? '被压低'}（基线比最终多 ${qty(suppressedBy(l))} 件）`,
    );
  }
  return parts.join('；');
}

// ------------------------------------------------------------ 保底分档

function FloorTable({
  floors,
  writable,
  busy,
  onRetire,
  onSupersede,
}: {
  floors: NonNullable<AllocationFloorPage['floors']>;
  writable: boolean;
  busy: string;
  onRetire: (id: string) => Promise<void>;
  onSupersede: (id: string) => Promise<void>;
}) {
  return (
    <table className="w-full text-sm">
      <thead className="text-muted-foreground text-left text-xs">
        <tr>
          <th className="py-1">店铺</th>
          <th className="py-1">口径</th>
          <th className="py-1 text-right">保底</th>
          <th className="py-1 text-right">安全垫</th>
          <th className="py-1">生效区间</th>
          {writable ? <th className="py-1">操作</th> : null}
        </tr>
      </thead>
      <tbody>
        {floors.map((f) => (
          <tr key={f.id ?? ''} className="border-control-border border-t">
            <td className="py-1">{f.referenceLabel || f.referenceId}</td>
            <td className="text-muted-foreground py-1 text-xs">
              {floorModeLabels[f.floorMode] ?? f.floorMode}
            </td>
            <td className="py-1 text-right">
              {f.floorMode === 'RATIO' ? percent(f.floorRatio) : qty(f.floorQuantity)}
            </td>
            <td className="py-1 text-right">
              {Number(f.safetyQuantity ?? 0) > 0 ? `${qty(f.safetyQuantity)} 件` : '—'}
            </td>
            <td className="text-muted-foreground py-1 text-xs">
              {intervalText(f.effectiveFrom, f.effectiveTo)}
            </td>
            {writable ? (
              <td className="py-1">
                {f.effectiveTo ? (
                  <span className="text-muted-foreground text-xs">已收口（不可复活）</span>
                ) : (
                  <div className="flex gap-2">
                    {/* 换版走 `/floors/{保底档 id}/versions`，所以入口必须在有 id 的行上，
                        而不是编辑器里 —— 编辑器手上只有店铺 id。 */}
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busy === f.id}
                      onClick={() => void onSupersede(f.id ?? '')}
                    >
                      {busy === f.id ? '换版中…' : '换版'}
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busy === f.id}
                      onClick={() => void onRetire(f.id ?? '')}
                    >
                      作废
                    </Button>
                  </div>
                )}
              </td>
            ) : null}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ------------------------------------------------------------ 保底编辑

function FloorEditor({
  policies,
  policyId,
  policyDetail,
  writable,
  saving,
  draft,
  onPolicyChange,
  onDraftChange,
  onSaved,
  onError,
}: {
  policies: AllocationPolicyPage | null;
  policyId: string;
  policyDetail: AllocationPolicyDetail | null;
  writable: boolean;
  saving: boolean;
  draft: FloorDraft;
  onPolicyChange: (id: string) => void;
  onDraftChange: (draft: FloorDraft) => void;
  onSaved: (message: string) => Promise<void>;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const members = (policyDetail?.members ?? []).filter(notNull);
  const { memberId, floorMode, floorQuantity, floorRatio, floorSafety, floorNote } = draft;

  const buildInput = (): AllocationFloorInput | null => {
    const member = members.find((m) => m.referenceId === memberId);
    if (!member) {
      onError('先选一家名册里的店铺：保底只能挂在该策略的成员上，否则 DB 会直接拒掉。');
      return null;
    }
    return {
      policyId,
      sourceSystem: member.sourceSystem,
      referenceId: member.referenceId,
      referenceLabel: member.referenceLabel,
      floorMode,
      floorRatio: floorMode === 'RATIO' ? floorRatio : 0,
      floorQuantity: floorMode === 'QUANTITY' ? floorQuantity : 0,
      safetyQuantity: floorSafety,
      effectiveFrom: null,
      note: floorNote,
    };
  };

  const submit = async () => {
    const input = buildInput();
    if (!input) return;
    setBusy(true);
    onError('');
    try {
      const saved = await createFloor(input);
      await onSaved(saved.message);
    } catch (e) {
      onError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  if (!writable) return null;

  return (
    <SectionCard
      title="新建一档保底"
      description="保底挂在策略上（同一店铺在不同 SKU 下可以有不同承诺）。店铺下拉只列该策略的真实成员 —— 给不在名册里的店配保底，货一多就会凭空抢走别人的货，而界面上查不到是谁拿走的。"
      extra={
        <div className="flex flex-wrap items-end gap-2">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground block text-xs">所属策略</span>
            <select
              className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
              value={policyId}
              onChange={(e) => {
                onPolicyChange(e.target.value);
                onDraftChange({ ...draft, memberId: '' });
              }}
            >
              {(policies?.policies.filter(notNull) ?? []).map((p) => (
                <option key={p.id ?? ''} value={p.id ?? ''}>
                  {p.referenceLabel || '兜底档'} · {p.method}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground block text-xs">店铺（名册内）</span>
            <select
              className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
              value={memberId}
              onChange={(e) => onDraftChange({ ...draft, memberId: e.target.value })}
            >
              <option value="">选择一家店</option>
              {members.map((m) => (
                <option key={m.referenceId ?? ''} value={m.referenceId ?? ''}>
                  {m.referenceLabel || m.referenceId} ·{' '}
                  {operatingModeLabels[m.shopOperatingMode] ?? m.shopOperatingMode}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground block text-xs">口径</span>
            <select
              className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
              value={floorMode}
              onChange={(e) => onDraftChange({ ...draft, floorMode: e.target.value })}
            >
              <option value="QUANTITY">按件数保底</option>
              <option value="RATIO">按可分配量比例保底</option>
            </select>
          </label>
          {floorMode === 'QUANTITY' ? (
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">保底件数</span>
              <input
                className="border-control-border bg-card w-24 rounded-md border px-2 py-1.5 text-sm"
                value={floorQuantity}
                onChange={(e) => onDraftChange({ ...draft, floorQuantity: Number(e.target.value) })}
              />
            </label>
          ) : (
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">保底比例（0~1）</span>
              <input
                className="border-control-border bg-card w-24 rounded-md border px-2 py-1.5 text-sm"
                value={floorRatio}
                onChange={(e) => onDraftChange({ ...draft, floorRatio: Number(e.target.value) })}
              />
            </label>
          )}
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground block text-xs">安全垫（件）</span>
            <input
              className="border-control-border bg-card w-24 rounded-md border px-2 py-1.5 text-sm"
              value={floorSafety}
              onChange={(e) => onDraftChange({ ...draft, floorSafety: Number(e.target.value) })}
            />
          </label>
          <Button size="sm" onClick={() => void submit()} disabled={busy || saving}>
            {busy ? '保存中…' : '新建保底'}
          </Button>
        </div>
      }
    >
      <label className="space-y-1 text-sm">
        <span className="text-muted-foreground block text-xs">备注（可选）</span>
        <input
          className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
          value={floorNote}
          onChange={(e) => onDraftChange({ ...draft, floorNote: e.target.value })}
          placeholder="例：平台考核期，断货即扣分"
        />
      </label>
      <p className="text-muted-foreground mt-2 text-xs">
        没有「就地改」的入口：保底内容写定后不可改（DB 层触发器钉死），改保底只能用下面列表里的
        <strong>换版</strong> —— 收口旧档 + 开新档，历史那一档因此永远是「当时承诺过什么」的凭据。
      </p>
    </SectionCard>
  );
}

// ------------------------------------------------------------ 保底生效记录

function ResolutionTable({ resolutions }: { resolutions: ResolutionPage | null }) {
  const rows = resolutions?.resolutions.filter(notNull) ?? [];
  return (
    <table className="w-full text-sm">
      <thead className="text-muted-foreground text-left text-xs">
        <tr>
          <th className="py-1 text-right">次序</th>
          <th className="py-1">店铺</th>
          <th className="py-1 text-right">应得</th>
          <th className="py-1 text-right">实得</th>
          <th className="py-1 text-right">差额</th>
          <th className="py-1 text-right">余量</th>
          <th className="py-1 text-right">基线</th>
          <th className="py-1 text-right">最终</th>
          <th className="py-1">状态</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={`${r.runId}-${r.referenceId}`} className="border-control-border border-t">
            <td className="text-muted-foreground py-1 text-right">{r.floorOrder}</td>
            <td className="py-1">{r.referenceLabel || r.referenceId}</td>
            <td className="py-1 text-right">{qty(r.requestedQty)}</td>
            <td className={`py-1 text-right ${floorStatusTone(r.status)}`}>{qty(r.grantedQty)}</td>
            <td className="py-1 text-right">{Number(r.shortfallQty ?? 0) > 0 ? qty(r.shortfallQty) : '—'}</td>
            <td className="py-1 text-right">{qty(r.residualQty)}</td>
            <td className="text-muted-foreground py-1 text-right">{qty(r.baseAllocatedQty)}</td>
            <td className="py-1 text-right font-medium">{qty(r.allocatedQty)}</td>
            <td className={`py-1 text-xs ${floorStatusTone(r.status)}`}>{resolutionReason(r)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function resolutionReason(r: {
  status: string | null;
  shortfallReason: string | null;
  suppressed: boolean | null;
  suppressionReason: string | null;
}): string {
  const parts: string[] = [floorStatusLabels[r.status ?? ''] ?? r.status ?? '—'];
  if (r.shortfallReason) {
    parts.push(shortfallReasonLabels[r.shortfallReason] ?? r.shortfallReason);
  }
  if (r.suppressed) {
    parts.push(suppressionReasonLabels[r.suppressionReason ?? ''] ?? r.suppressionReason ?? '被压低');
  }
  return parts.join(' · ');
}