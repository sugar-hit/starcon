'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
} from '@starcon/ui';
import { Loader2, RefreshCw, TriangleAlert, Waypoints } from 'lucide-react';
import {
  activeFilterCount,
  applyFilterChange,
  decisionLabel,
  decideCandidate,
  emptyFilters,
  fetchCapabilities,
  fetchFilterOptions,
  fetchMapping,
  fetchMappings,
  fetchUnmapped,
  hasNextPage,
  hasPrevPage,
  isPending,
  mappingStateLabel,
  mappingStatusLabel,
  pageSummary,
  relationLabel,
  removeMapping,
  resetFilters,
  runBatchCandidates,
  saveMapping,
  scoreText,
  unavailableDimensions,
  unmappedSummary,
  type BatchCandidateResult,
  type CandidateDecisionResult,
  type MappingCandidate,
  type MappingCapabilities,
  type MappingDetail,
  type MappingFilterOptions,
  type MappingFilters,
  type MappingPage,
  type UnmappedPage,
} from './listing-mapping-model';

/**
 * 4.1.2.1 映射管理 工作区。
 *
 * 蓝图：「建立并维护"平台变体 → 内部 SKU/套装"的映射，支持一对一、一对多（套装）、多对一；
 * 支持批量映射（按规则：SKU 编码匹配、条码匹配、标题相似度推荐但需人工确认）。输入：映射建立操作；
 * 匹配规则；批量候选。输出：`listing_mapping_version` 记录（当前生效版本 + 历史版本）；
 * 未映射清单回流 02 映射缺失队列。关系：上游 = 4.1.1.1、02 同步到的 listing；下游 = 09 订单
 * （订单行按映射解析 SKU）、07 库存（回写可售量）。**映射未建立时订单进入待映射状态，
 * 绝不按名称猜 SKU**（实体目录 §6）。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>候选不是映射</b>。标题相似度候选恒带「待人工确认」徽章，确认之前界面上
 *       **一条映射都不显示** —— 前端自己画一条映射出来，等于把「按名称猜 SKU」
 *       这条禁令从服务端搬到了浏览器里。</li>
 *   <li><b>改映射生成新版本</b>，不原地改。详情区把版本历史逐条列出来（生效时间点 + 变更原因），
 *       「这条映射当时是什么」必须能回溯。</li>
 *   <li><b>解除不是删除</b>。解除后变体退回未映射，历史版本原样保留，而且**可以重新建立**。</li>
 *   <li><b>筛选与分页全在服务端</b>。前端只翻译 query string，不在浏览器里过滤已分页的数据。</li>
 *   <li><b>未交付段如实列出</b>：「按版本回溯受影响订单」（4.1.2.2）与「未映射清单回流 02 队列」
 *       （2.4.2.3）都没交付，页面上写明归属模块与影响，而不是画一个空列表冒充已交付。</li>
 * </ol>
 */
export function ListingMappingWorkspace() {
  const [filters, setFilters] = useState<MappingFilters>(emptyFilters);
  const [page, setPage] = useState<MappingPage | null>(null);
  const [caps, setCaps] = useState<MappingCapabilities | null>(null);
  const [options, setOptions] = useState<MappingFilterOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 详情：选中的一条映射 + 它的版本历史
  const [detail, setDetail] = useState<MappingDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);

  // 未映射清单
  const [unmapped, setUnmapped] = useState<UnmappedPage | null>(null);
  const [unmappedError, setUnmappedError] = useState<string | null>(null);

  // 批量候选
  const [rule, setRule] = useState('TITLE_SIMILARITY');
  const [listingIds, setListingIds] = useState('');
  const [batch, setBatch] = useState<BatchCandidateResult | null>(null);
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchError, setBatchError] = useState<string | null>(null);
  const [decision, setDecision] = useState<CandidateDecisionResult | null>(null);

  // 建立 / 修改映射
  const [variantId, setVariantId] = useState('');
  const [skuId, setSkuId] = useState('');
  const [relation, setRelation] = useState('ONE_TO_ONE');
  const [quantity, setQuantity] = useState('');
  const [reason, setReason] = useState('');
  const [saveBusy, setSaveBusy] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saveNotice, setSaveNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPage(await fetchMappings(filters));
    } catch (e) {
      setError(e instanceof Error ? e.message : '映射列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchCapabilities());
        setOptions(await fetchFilterOptions());
      } catch {
        // 能力声明 / 词表拿不到不影响列表本身，页面仍要能看单。
      }
    })();
  }, []);

  useEffect(() => {
    void (async () => {
      try {
        setUnmapped(await fetchUnmapped({ size: 50 }));
        setUnmappedError(null);
      } catch (e) {
        setUnmappedError(e instanceof Error ? e.message : '未映射清单加载失败');
      }
    })();
  }, []);

  const rows = useMemo(() => page?.items ?? [], [page]);
  const missing = useMemo(() => unavailableDimensions(caps), [caps]);
  const activeCount = activeFilterCount(filters);

  const openDetail = async (id: string) => {
    setDetailError(null);
    try {
      setDetail(await fetchMapping(id));
    } catch (e) {
      setDetail(null);
      setDetailError(e instanceof Error ? e.message : '映射详情加载失败');
    }
  };

  const submitMapping = async () => {
    setSaveBusy(true);
    setSaveError(null);
    setSaveNotice(null);
    try {
      const saved = await saveMapping({
        listingVariantId: variantId.trim(),
        relationType: relation,
        internalSkuId: skuId.trim(),
        componentQuantity: relation === 'ONE_TO_MANY' ? Number(quantity) : null,
        changeReason: reason.trim(),
      });
      setSaveNotice(
        `已建立映射：第 ${saved.mapping.currentVersionNo ?? 1} 版（${relationLabel(
          saved.mapping.relationType,
        )}）`,
      );
      setDetail(saved);
      // 映射变了，未映射清单与列表都必须重读，否则界面显示的是旧事实。
      await load();
      setUnmapped(await fetchUnmapped({ size: 50 }));
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : '保存映射失败');
    } finally {
      setSaveBusy(false);
    }
  };

  const dropMapping = async () => {
    if (!detail) return;
    setSaveBusy(true);
    setSaveError(null);
    setSaveNotice(null);
    try {
      const removed = await removeMapping(detail.mapping.id, reason.trim() || '运营解除映射');
      setDetail(removed);
      setSaveNotice('已解除：变体退回未映射，历史版本原样保留（可以重新建立映射）');
      await load();
      setUnmapped(await fetchUnmapped({ size: 50 }));
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : '解除映射失败');
    } finally {
      setSaveBusy(false);
    }
  };

  const runBatch = async () => {
    setBatchBusy(true);
    setBatchError(null);
    setDecision(null);
    try {
      const ids = listingIds
        .split(/[\s,，]+/)
        .map((s) => s.trim())
        .filter(Boolean);
      setBatch(await runBatchCandidates({ rule, listingIds: ids }));
    } catch (e) {
      setBatchError(e instanceof Error ? e.message : '批量候选失败');
    } finally {
      setBatchBusy(false);
    }
  };

  const decide = async (candidate: MappingCandidate, verdict: 'CONFIRMED' | 'REJECTED') => {
    setBatchError(null);
    setDecision(null);
    try {
      setDecision(
        await decideCandidate(
          candidate.id,
          verdict,
          verdict === 'CONFIRMED' ? '人工核对无误' : '人工核对后否决',
        ),
      );
      // 确认候选会真的建映射 ⇒ 列表与未映射清单都要重读
      await load();
      setUnmapped(await fetchUnmapped({ size: 50 }));
    } catch (e) {
      setBatchError(e instanceof Error ? e.message : '候选裁决失败');
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="映射管理"
        description="建立并维护「平台变体 → 内部 SKU/套装」的映射：一对一、一对多（套装）、多对一。映射未建立时订单行进入待映射状态，绝不按名称猜 SKU。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
          <Button variant="outline" size="sm" onClick={() => void load()} className="ml-auto">
            重试
          </Button>
        </div>
      )}

      {/* 🔴 未交付段明账：逐条列出归属模块与影响，不画 0 也不画占位 */}
      {missing.length > 0 && (
        <SectionCard
          title="本模块未接入的能力"
          description="如实列出而不是填 0 或留空：0 会被读成「这件事已经做完了」。"
        >
          <ul className="space-y-2">
            {missing.map((d) => (
              <li key={d.dimension} className="text-copy-13 flex flex-wrap items-baseline gap-2">
                <Badge tone="warning">{d.dimension}</Badge>
                <span className="text-foreground">{d.description}</span>
                <span className="text-muted-foreground">归 {d.ownerModule}</span>
                <span className="text-muted-foreground">{d.impact}</span>
              </li>
            ))}
          </ul>
          {caps?.gapNote && (
            <p className="text-copy-13 text-muted-foreground mt-3 border-t pt-3">{caps.gapNote}</p>
          )}
        </SectionCard>
      )}

      {/* 建立映射 */}
      <SectionCard
        title="建立 / 修改映射"
        description="改映射会生成新版本而不是原地改；变更原因必填 —— 没有原因的版本历史无法回溯「当时为什么这么改」。"
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label="平台变体 ID">
            <Input
              value={variantId}
              onChange={(e) => setVariantId(e.target.value)}
              placeholder="listing_variant 的 UUID"
            />
          </Field>
          <Field label="内部 SKU 逻辑 ID">
            <Input
              value={skuId}
              onChange={(e) => setSkuId(e.target.value)}
              placeholder="03 商品的 SKU UUID（跨服务逻辑 ID）"
            />
          </Field>
          <Field label="映射基数">
            <Select
              value={relation}
              onChange={(v) => setRelation(v)}
              options={options?.relationTypes ?? ['ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE']}
              labels={RELATION_LABELS}
              emptyLabel="选择基数"
            />
          </Field>
          {relation === 'ONE_TO_MANY' && (
            <Field label="组件数量（> 0）">
              <Input
                value={quantity}
                onChange={(e) => setQuantity(e.target.value)}
                placeholder="平台卖 1 件 = 内部扣几套组件"
              />
            </Field>
          )}
          <Field label="变更原因" className="sm:col-span-2 lg:col-span-4">
            <Input
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="例如：平台变体编码与内部 SKU 编码一致"
            />
          </Field>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <Button
            size="sm"
            onClick={() => void submitMapping()}
            disabled={
              saveBusy ||
              variantId.trim() === '' ||
              skuId.trim() === '' ||
              reason.trim() === '' ||
              (relation === 'ONE_TO_MANY' && quantity.trim() === '')
            }
          >
            {saveBusy && <Loader2 className="h-4 w-4 animate-spin" />}
            建立映射
          </Button>
          {saveError && <span className="text-copy-13 text-destructive">{saveError}</span>}
          {saveNotice && <span className="text-copy-13 text-success">{saveNotice}</span>}
        </div>
      </SectionCard>

      {/* 筛选 */}
      <SectionCard
        title="筛选"
        description={`当前 ${activeCount} 个条件生效，全部走服务端 where 子句。`}
        extra={
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setFilters(resetFilters(filters))}
            disabled={activeCount === 0}
          >
            清空筛选
          </Button>
        }
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label="映射基数">
            <Select
              value={filters.relationType}
              onChange={(v) => setFilters(applyFilterChange(filters, { relationType: v }))}
              options={options?.relationTypes ?? []}
              labels={RELATION_LABELS}
              emptyLabel="全部基数"
            />
          </Field>
          <Field label="映射状态">
            <Select
              value={filters.mappingStatus}
              onChange={(v) => setFilters(applyFilterChange(filters, { mappingStatus: v }))}
              options={options?.mappingStatuses ?? []}
              labels={MAPPING_STATUS_LABELS}
              emptyLabel="全部状态"
            />
          </Field>
          <Field label="存续状态">
            <Select
              value={filters.status}
              onChange={(v) => setFilters(applyFilterChange(filters, { status: v }))}
              options={options?.statuses ?? []}
              labels={MAPPING_STATE_LABELS}
              emptyLabel="全部存续"
            />
          </Field>
          <Field label="内部 SKU 逻辑 ID">
            <Input
              value={filters.internalSkuId}
              onChange={(e) =>
                setFilters(applyFilterChange(filters, { internalSkuId: e.target.value }))
              }
              placeholder="按内部 SKU 反查"
            />
          </Field>
          <Field label="店铺编码">
            <Input
              value={filters.shopCode}
              onChange={(e) => setFilters(applyFilterChange(filters, { shopCode: e.target.value }))}
              placeholder="留空 = 全部店铺"
            />
          </Field>
          <Field label="排序">
            <Select
              value={filters.sort}
              onChange={(v) => setFilters({ ...filters, sort: v, page: 0 })}
              options={options?.sorts ?? []}
              labels={SORT_LABELS}
              emptyLabel="默认"
            />
          </Field>
          <Field label="搜索（标题 / 平台商品 ID / 变体 ID）" className="sm:col-span-2 lg:col-span-4">
            <Input
              value={filters.search}
              onChange={(e) => setFilters(applyFilterChange(filters, { search: e.target.value }))}
              placeholder="如 B0C… 或变体编码"
            />
          </Field>
        </div>
      </SectionCard>

      <div className="flex flex-wrap items-center gap-3">
        <Badge tone="primary">共 {page?.total ?? 0} 条</Badge>
        <Badge tone={page && page.removedInPage > 0 ? 'warning' : 'neutral'}>
          本页已解除 {page?.removedInPage ?? 0}
        </Badge>
        <span className="text-copy-13 text-muted-foreground">{pageSummary(page)}</span>
      </div>

      {/* 映射列表 */}
      <SectionCard
        title="映射"
        description="内部 SKU 只显示逻辑 ID —— 编码与名称归 03 商品，本模块不复制。点「版本历史」看当前生效版本与逐版变更原因。"
      >
        {rows.length === 0 && !loading ? (
          <EmptyState
            icon={<Waypoints className="h-7 w-7" />}
            title="还没有任何映射"
            description="当前筛选条件下没有数据。可以先看「未映射清单」，从那里挑一个平台变体建立映射。"
            action={
              activeCount > 0 ? (
                <Button variant="outline" onClick={() => setFilters(resetFilters(filters))}>
                  清空筛选
                </Button>
              ) : undefined
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="px-3 py-2">平台变体</th>
                  <th className="px-3 py-2">店铺</th>
                  <th className="px-3 py-2">基数</th>
                  <th className="px-3 py-2">映射状态</th>
                  <th className="px-3 py-2">内部 SKU</th>
                  <th className="px-3 py-2">当前版本</th>
                  <th className="px-3 py-2">共用变体</th>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} className="border-border/60 border-b last:border-0">
                    <td className="px-3 py-2">
                      <div className="text-foreground font-medium">{r.title}</div>
                      <div className="text-muted-foreground font-mono text-label-12">
                        {r.externalItemId} / {r.externalVariantId}
                      </div>
                    </td>
                    <td className="px-3 py-2">{r.shopCode}</td>
                    <td className="px-3 py-2">
                      <Badge tone="info">{relationLabel(r.relationType)}</Badge>
                      {r.componentQuantity !== null && (
                        <div className="text-muted-foreground mt-1 text-label-12">
                          组件数量 {String(r.componentQuantity)}
                        </div>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone={mappingStatusTone(r.mappingStatus)}>
                        {mappingStatusLabel(r.mappingStatus)}
                      </Badge>
                      <div className="text-muted-foreground mt-1 text-label-12">
                        {mappingStateLabel(r.status)}
                      </div>
                    </td>
                    <td className="px-3 py-2 font-mono text-label-12">
                      {r.internalSkuId ?? '—'}
                    </td>
                    <td className="px-3 py-2">
                      <div>第 {r.currentVersionNo ?? '—'} 版</div>
                      <div className="text-muted-foreground text-label-12">
                        共 {r.versionCount} 版历史
                      </div>
                    </td>
                    <td className="px-3 py-2">{r.sharedWithVariants}</td>
                    <td className="px-3 py-2">
                      <Button variant="outline" size="sm" onClick={() => void openDetail(r.id)}>
                        版本历史
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="border-border mt-4 flex items-center justify-between border-t pt-4">
          <span className="text-copy-13 text-muted-foreground">{pageSummary(page)}</span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={!hasPrevPage(page) || loading}
              onClick={() => setFilters({ ...filters, page: Math.max(0, filters.page - 1) })}
            >
              上一页
            </Button>
            <span className="text-copy-13">第 {filters.page + 1} 页</span>
            <Button
              variant="outline"
              size="sm"
              disabled={!hasNextPage(page) || loading}
              onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
            >
              下一页
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* 详情：当前映射 + 版本历史 */}
      {detailError && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{detailError}</span>
        </div>
      )}
      {detail && (
        <SectionCard
          title="版本历史"
          description="改映射生成新版本，不原地改：已产生的订单仍按当时的版本解析（4.1.2.2 的加粗句）。"
          extra={
            <Button
              variant="outline"
              size="sm"
              onClick={() => void dropMapping()}
              disabled={saveBusy || detail.mapping.status !== 'ACTIVE'}
            >
              解除映射
            </Button>
          }
        >
          <div className="text-copy-13 flex flex-wrap items-center gap-2">
            <Badge tone="info">{relationLabel(detail.mapping.relationType)}</Badge>
            <Badge tone={mappingStatusTone(detail.mapping.mappingStatus)}>
              {mappingStatusLabel(detail.mapping.mappingStatus)}
            </Badge>
            <span className="text-muted-foreground">内部 SKU</span>
            <span className="font-mono">{detail.mapping.internalSkuId ?? '—'}</span>
            <span className="text-muted-foreground">变体</span>
            <span className="font-mono">{detail.mapping.externalVariantId}</span>
          </div>
          <div className="overflow-x-auto mt-3">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="px-3 py-2">版本</th>
                  <th className="px-3 py-2">生效时间点</th>
                  <th className="px-3 py-2">基数</th>
                  <th className="px-3 py-2">内部 SKU</th>
                  <th className="px-3 py-2">变更原因</th>
                  <th className="px-3 py-2">来源</th>
                </tr>
              </thead>
              <tbody>
                {detail.versions.map((v) => (
                  <tr key={v.id} className="border-border/60 border-b last:border-0">
                    <td className="px-3 py-2">
                      第 {v.versionNo} 版
                      {v.effectiveTo === null && (
                        <Badge tone="success" className="ml-2">
                          当前生效
                        </Badge>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      <div>{v.effectiveFrom}</div>
                      <div className="text-muted-foreground text-label-12">
                        {v.effectiveTo === null ? '至今' : `至 ${v.effectiveTo}`}
                      </div>
                    </td>
                    <td className="px-3 py-2">{relationLabel(v.relationType)}</td>
                    <td className="px-3 py-2 font-mono text-label-12">
                      {v.internalSkuId ?? '—'}
                    </td>
                    <td className="px-3 py-2">{v.changeReason}</td>
                    <td className="px-3 py-2">
                      {v.changeSource === 'BATCH_CONFIRMED' ? '批量候选人工确认' : '人工建立'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="text-copy-13 text-muted-foreground mt-3 border-t pt-3">
            {detail.affectedOrdersNote}
          </p>
        </SectionCard>
      )}

      {/* 批量候选 */}
      <SectionCard
        title="批量映射候选"
        description="候选只写候选表：确认之前不产生任何映射行。标题相似度推荐恒需人工确认 —— 自动按相似度落映射正是蓝图禁止的「按名称猜 SKU」。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <Field label="匹配规则" className="min-w-48">
            <Select
              value={rule}
              onChange={(v) => setRule(v)}
              options={options?.matchRules ?? ['SKU_CODE', 'BARCODE', 'TITLE_SIMILARITY']}
              labels={RULE_LABELS}
              emptyLabel="选择规则"
            />
          </Field>
          <Field label="平台商品 ID 列表" className="min-w-96 flex-1">
            <Input
              value={listingIds}
              onChange={(e) => setListingIds(e.target.value)}
              placeholder="逗号或换行分隔的 listing UUID（可从下面的未映射清单复制）"
            />
          </Field>
          <Button size="sm" onClick={() => void runBatch()} disabled={batchBusy}>
            {batchBusy && <Loader2 className="h-4 w-4 animate-spin" />}
            生成候选
          </Button>
        </div>
        {batchError && <p className="text-copy-13 text-destructive mt-3">{batchError}</p>}
        {decision && (
          <p className="text-copy-13 text-success mt-3">
            裁决完成：{decision.decision === 'CONFIRMED' ? '已确认并建立映射' : '已否决'} ——
            {decision.reason}
          </p>
        )}
        {batch && (
          <>
            <div className="flex flex-wrap items-center gap-3 mt-3">
              <Badge tone="primary">候选 {batch.candidateCount} 条</Badge>
              <Badge tone={batch.requiresConfirmationCount > 0 ? 'warning' : 'neutral'}>
                需人工确认 {batch.requiresConfirmationCount}
              </Badge>
              <Badge tone="info">比对 SKU {batch.scannedSkus} 条</Badge>
              {batch.truncated && <Badge tone="danger">检索被上限截断（不是全集）</Badge>}
            </div>
            <p className="text-copy-13 text-muted-foreground mt-2">{batch.reason}</p>
            {batch.candidates.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground mt-3">
                没有可推荐的候选 —— 这是真状态（这些变体确实没有可匹配项），不是查不到。
              </p>
            ) : (
              <div className="overflow-x-auto mt-3">
                <table className="w-full text-left text-copy-13">
                  <thead className="text-muted-foreground border-b">
                    <tr>
                      <th className="px-3 py-2">平台变体</th>
                      <th className="px-3 py-2">内部 SKU</th>
                      <th className="px-3 py-2">命中依据</th>
                      <th className="px-3 py-2">分数</th>
                      <th className="px-3 py-2">裁决</th>
                      <th className="px-3 py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {batch.candidates.map((c) => (
                      <tr key={c.id} className="border-border/60 border-b last:border-0">
                        <td className="px-3 py-2 font-mono text-label-12">
                          {c.externalVariantId}
                        </td>
                        <td className="px-3 py-2">
                          <div className="font-mono text-label-12">{c.internalSkuId}</div>
                          <div className="text-muted-foreground text-label-12">
                            {c.internalSkuCode} · {c.internalSkuName}
                          </div>
                        </td>
                        <td className="px-3 py-2 text-label-12">{c.matchedText}</td>
                        <td className="px-3 py-2">{scoreText(c)}</td>
                        <td className="px-3 py-2">
                          <Badge tone={c.requiresConfirmation ? 'warning' : 'neutral'}>
                            {c.requiresConfirmation ? '待人工确认' : '精确命中'}
                          </Badge>
                          <div className="text-muted-foreground mt-1 text-label-12">
                            {decisionLabel(c.decision)}
                          </div>
                        </td>
                        <td className="px-3 py-2">
                          {isPending(c) && (
                            <div className="flex items-center gap-2">
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() => void decide(c, 'CONFIRMED')}
                              >
                                确认
                              </Button>
                              <Button
                                size="sm"
                                variant="ghost"
                                onClick={() => void decide(c, 'REJECTED')}
                              >
                                否决
                              </Button>
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </SectionCard>

      {/* 未映射清单 */}
      <SectionCard
        title="未映射清单"
        description="平台变体还没有任何生效映射 —— 它们的订单行会进入待映射状态。"
      >
        {unmappedError && (
          <p className="text-copy-13 text-destructive mb-3">{unmappedError}</p>
        )}
        <p className="text-copy-13 text-muted-foreground">{unmappedSummary(unmapped)}</p>
        {unmapped && !unmapped.backflowDelivered && (
          <p className="text-copy-13 text-warning mt-2">
            🔴 回流目标（{unmapped.backflowOwnerModule}）未交付：这里给的是**清单本身**，
            「把清单送进队列」那一跳尚未接通，运营暂时看不到待办队列里的这些条目。
          </p>
        )}
        {unmapped && unmapped.items.length > 0 && (
          <div className="overflow-x-auto mt-3">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="px-3 py-2">平台商品</th>
                  <th className="px-3 py-2">变体</th>
                  <th className="px-3 py-2">店铺</th>
                  <th className="px-3 py-2">未映射天数</th>
                  <th className="px-3 py-2">listing ID</th>
                </tr>
              </thead>
              <tbody>
                {unmapped.items.map((v) => (
                  <tr key={v.listingVariantId} className="border-border/60 border-b last:border-0">
                    <td className="px-3 py-2">
                      <div className="text-foreground font-medium">{v.title}</div>
                      <div className="text-muted-foreground font-mono text-label-12">
                        {v.externalItemId}
                      </div>
                    </td>
                    <td className="px-3 py-2 font-mono text-label-12">{v.externalVariantId}</td>
                    <td className="px-3 py-2">{v.shopCode}</td>
                    <td className="px-3 py-2">{v.unmappedDays} 天</td>
                    <td className="px-3 py-2 font-mono text-label-12">{v.listingId}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

// ================================================================ 小组件与文案

const RELATION_LABELS: Record<string, string> = {
  ONE_TO_ONE: '一对一',
  ONE_TO_MANY: '一对多（套装）',
  MANY_TO_ONE: '多对一',
};

const MAPPING_STATUS_LABELS: Record<string, string> = {
  MAPPED: '已映射',
  BUNDLE: '套装映射',
  UNMAPPED: '未映射',
};

const MAPPING_STATE_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  REMOVED: '已解除',
};

const RULE_LABELS: Record<string, string> = {
  SKU_CODE: 'SKU 编码匹配',
  BARCODE: '条码匹配',
  TITLE_SIMILARITY: '标题相似度推荐',
};

const SORT_LABELS: Record<string, string> = {
  RECENT: '最近更新',
  VERSION: '版本号从新到旧',
  SKEW: '按基数与更新时间',
};

function mappingStatusTone(status: string): 'success' | 'warning' | 'neutral' {
  if (status === 'MAPPED') return 'success';
  if (status === 'BUNDLE') return 'warning';
  return 'neutral';
}

function Field({
  label,
  children,
  className,
}: {
  label: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <label className={`flex flex-col gap-1.5 ${className ?? ''}`}>
      <span className="text-muted-foreground text-label-12">{label}</span>
      {children}
    </label>
  );
}

function Select({
  value,
  onChange,
  options,
  labels,
  emptyLabel,
}: {
  value: string;
  onChange: (value: string) => void;
  options: readonly string[];
  labels: Record<string, string>;
  emptyLabel: string;
}) {
  return (
    <select
      className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
      value={value}
      onChange={(e) => onChange(e.target.value)}
    >
      <option value="">{emptyLabel}</option>
      {options.map((o) => (
        <option key={o} value={o}>
          {labels[o] ?? o}
        </option>
      ))}
    </select>
  );
}
