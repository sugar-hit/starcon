'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, PageHeader, SectionCard } from '@starcon/ui';
import {
  ArrowLeft,
  Loader2,
  PencilLine,
  RefreshCw,
  Send,
  TriangleAlert,
} from 'lucide-react';
import {
  cellText,
  contentSourceLabel,
  diffLabel,
  diffSummary,
  diffTone,
  editAvailability,
  fetchListingDetail,
  fetchListingDetailCapabilities,
  groupLabel,
  mappingLabel,
  operationLabel,
  operationStatusHint,
  operationStatusLabel,
  publishOutcomeLabel,
  publishSummary,
  quantityText,
  ratingText,
  reviewLabel,
  rowExplain,
  stockLabel,
  statusLabel,
  timeText,
  unavailableDimensions,
  variantMappingLabel,
  writebackAvailability,
  type DiffGroup,
  type ListingDetail,
  type ListingDetailCapabilities,
  type ListingDiffRow,
} from './listing-detail-model';

/**
 * 4.1.1.2 Listing 详情 工作区。
 *
 * <p>蓝图：「单个平台商品的全景：平台原始信息（ASIN/item_id、标题、状态、评分评论数）、内部映射、内容对照（平台当前内容 vs
 * 内部草稿内容）、价格库存差异、发布历史、平台操作记录。输出：左右对照视图 + 编辑入口 + 差异高亮 + 回写按钮。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>左右两列永远成对出现</b>：左边「平台当前内容」、右边「内部草稿内容」。右列整列标为未接入时，
 *       **不折叠、不隐藏**，而是整列写明归属模块 —— 有一侧没接入时，「一致 / 不一致」都答不出来。
 *   <li><b>差异高亮区分三种结局</b>：不同（danger）/ 相同（success）/ 未接入（warning）。
 *       把「只有一侧有值」画成 success 绿色，是本模块最容易犯也最难发现的错。
 *   <li><b>编辑入口与回写按钮禁用并说明归属</b>，而不是点了给一个「成功但什么都没做」的提示。
 *   <li><b>回写登记簿快照不显示成「售价」</b>：标题写「回写登记簿当前值快照」，并把服务端的 limitation 原样透出。
 * </ol>
 */
export function ListingDetailWorkspace({ listingId }: { listingId: string }) {
  const [detail, setDetail] = useState<ListingDetail | null>(null);
  const [caps, setCaps] = useState<ListingDetailCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDetail(await fetchListingDetail(listingId));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Listing 详情加载失败');
    } finally {
      setLoading(false);
    }
  }, [listingId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchListingDetailCapabilities());
      } catch {
        // 能力声明拿不到不影响全景本身，按钮按「不可用 + 未知归属」处理
      }
    })();
  }, []);

  const edit = editAvailability(caps);
  const writeback = writebackAvailability(caps);
  const missing = unavailableDimensions(caps);
  const grouped = useMemo(() => groupDiffRows(detail?.diff.rows ?? []), [detail]);
  const profile = detail?.profile;

  if (loading && !detail) {
    return (
      <div className="flex flex-col gap-4">
        <PageHeader title="Listing 详情" description="加载中…" />
        <div className="text-muted-foreground flex items-center gap-2 text-copy-13">
          <Loader2 className="h-4 w-4 animate-spin" />
          正在读取平台原始信息与差异对照…
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex flex-col gap-4">
        <PageHeader title="Listing 详情" description="读取失败" />
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
          <Button variant="outline" size="sm" onClick={() => void load()} className="ml-auto">
            重试
          </Button>
        </div>
        <BackToList />
      </div>
    );
  }

  if (!detail || !profile) {
    return (
      <div className="flex flex-col gap-4">
        <PageHeader title="Listing 详情" description="未找到" />
        <EmptyState
          title="找不到这条 listing"
          description="它可能不在当前租户或你的数据范围内 —— 跨租户与不存在返回的是同一个答复。"
        />
        <BackToList />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Listing 详情"
        description="单个平台商品的全景：平台原始信息、内部映射、内容对照、价格库存差异、发布历史与平台操作记录。"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Link href="/listings">
              <Button variant="outline" size="sm">
                <ArrowLeft className="h-4 w-4" />
                返回列表
              </Button>
            </Link>
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
              {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
              刷新
            </Button>
            {/* 🔴 两个入口本批次都不可用：禁用 + 说明归属，而不是点了没反应 */}
            <Button size="sm" disabled={!edit.supported} title={edit.reason ?? undefined}>
              <PencilLine className="h-4 w-4" />
              编辑内容
            </Button>
            <Button size="sm" disabled={!writeback.supported} title={writeback.reason ?? undefined}>
              <Send className="h-4 w-4" />
              内容回写
            </Button>
          </div>
        }
      />

      {missing.length > 0 && (
        <SectionCard
          title="本模块未接入的维度"
          description="如实列出而不是填 0 或留空：0 会被读成「这个商品卖 0 块」。"
        >
          <ul className="space-y-2">
            {missing.map((d) => (
              <li key={d.dimension} className="text-copy-13 flex flex-wrap items-baseline gap-2">
                <Badge tone="warning">{d.dimension}</Badge>
                <span className="text-foreground">{d.description}</span>
                <span className="text-muted-foreground">归 {d.ownerModule}</span>
              </li>
            ))}
          </ul>
          <div className="mt-3 space-y-1 border-t pt-3">
            {[caps?.priceNote, caps?.internalStockNote, caps?.contentDraftNote, caps?.salesNote]
              .filter((n): n is string => Boolean(n))
              .map((note) => (
                <p key={note} className="text-copy-13 text-muted-foreground">
                  {note}
                </p>
              ))}
          </div>
        </SectionCard>
      )}

      {(edit.reason || writeback.reason) && (
        <div className="text-copy-13 text-muted-foreground space-y-1">
          {edit.reason && <p>编辑入口：{edit.reason}</p>}
          {writeback.reason && <p>内容回写：{writeback.reason}</p>}
        </div>
      )}

      {/* 平台原始信息 */}
      <SectionCard
        title="平台原始信息"
        description="平台侧事实原文照抄（ASIN / item_id 不折叠大小写），不做任何改写。"
      >
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field label="平台商品 ID">
            <span className="font-mono text-copy-13">{profile.externalItemId}</span>
          </Field>
          <Field label="店铺 / 站点">
            {profile.shopCode}
            {profile.marketplaceCode ? ` · ${profile.marketplaceCode}` : ' · 未归站点'}
          </Field>
          <Field label="平台">
            {profile.platformCode ?? '—'}
          </Field>
          <Field label="类目路径">
            <span className="text-copy-13">{profile.categoryPath || '平台未给'}</span>
          </Field>
          <Field label="商品状态">
            <Badge tone={profile.status === 'ON_SALE' ? 'success' : 'neutral'}>
              {statusLabel(profile.status)}
            </Badge>
          </Field>
          <Field label="审核状态">
            <Badge tone={profile.reviewStatus === 'REJECTED' ? 'danger' : 'neutral'}>
              {reviewLabel(profile.reviewStatus)}
            </Badge>
            {profile.reviewMessage && (
              <span className="text-copy-13 text-muted-foreground ml-2">{profile.reviewMessage}</span>
            )}
          </Field>
          <Field label="评分 / 评论数">
            {ratingText(profile.rating, profile.ratingCount)}
          </Field>
          <Field label="🔴 平台侧可售状态">
            <Badge
              tone={
                profile.platformStockState === 'IN_STOCK'
                  ? 'success'
                  : profile.platformStockState === 'OUT_OF_STOCK'
                    ? 'danger'
                    : 'neutral'
              }
            >
              {stockLabel(profile.platformStockState)}
            </Badge>
            <span className="text-copy-13 text-muted-foreground ml-2">
              可售量 {quantityText(profile.platformStockQty)}
            </span>
          </Field>
          <Field label="上架 / 最近同步">
            <span className="text-copy-13">
              {timeText(profile.launchedAt)} / {timeText(profile.lastSyncedAt)}
            </span>
          </Field>
          <Field label="🔴 内部可售量" hint="归 07 库存，未接入">
            <span className="text-muted-foreground text-copy-13">未接入（07 库存系统）</span>
          </Field>
          <Field label="🔴 售价" hint="归 05 定价，未接入">
            <span className="text-muted-foreground text-copy-13">未接入（05 定价与价目系统）</span>
          </Field>
          <Field label="🔴 销量" hint="归 09 订单，未接入">
            <span className="text-muted-foreground text-copy-13">未接入（09 订单与履约系统）</span>
          </Field>
        </div>
        <div className="mt-4 border-t pt-3">
          <p className="text-foreground text-copy-13">{profile.title}</p>
        </div>
      </SectionCard>

      {/* 差异对照（本模块的核心价值） */}
      <SectionCard
        title="差异对照"
        description="左右两列逐项对照，差异高亮。「未接入」与「相同」是两种不同的结论，本模块不把它们混为一谈。"
        extra={<Badge tone={detail.differentCount > 0 ? 'danger' : 'success'}>{diffSummary(detail.diff)}</Badge>}
      >
        {grouped.map(([group, rows]) => (
          <div key={group} className="mb-6 last:mb-0">
            <div className="text-muted-foreground mb-2 flex items-center gap-2 text-copy-13">
              <span className="text-foreground font-medium">{groupLabel(group)}</span>
              <span>{groupHint(group)}</span>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead>
                  <tr className="border-b">
                    <th className="py-2 pr-3 font-medium">对照项</th>
                    <th className="py-2 pr-3 font-medium">{rows[0]?.platformLabel ?? '左侧'}</th>
                    <th className="py-2 pr-3 font-medium">{rows[0]?.internalLabel ?? '右侧'}</th>
                    <th className="py-2 font-medium">结论</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <DiffRowView key={row.dimension} row={row} />
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ))}
        <p className="text-copy-13 text-muted-foreground border-t pt-3">{detail.diff.note}</p>
      </SectionCard>

      {/* 内容对照 */}
      <SectionCard
        title="内容对照"
        description={
          detail.contentSnapshot
            ? `左列取自当前平台内容快照（${detail.contentSnapshot.language} · ${contentSourceLabel(detail.contentSnapshot.source)} · 采集于 ${timeText(detail.contentSnapshot.capturedAt)}，历史 ${detail.contentSnapshot.historyCount} 份）。`
            : '尚未登记过平台内容快照，标题回退到 02 同步写下的平台侧事实。'
        }
      >
        <div className="overflow-x-auto">
          <table className="w-full text-left text-copy-13">
            <thead>
              <tr className="border-b">
                <th className="py-2 pr-3 font-medium">字段</th>
                <th className="py-2 pr-3 font-medium">平台当前内容</th>
                <th className="py-2 pr-3 font-medium">内部草稿内容</th>
              </tr>
            </thead>
            <tbody>
              {detail.contentComparison.map((f) => (
                <tr key={f.field} className="border-b last:border-0">
                  <td className="py-2 pr-3 font-medium">{f.label}</td>
                  <td className="py-2 pr-3">
                    <span className={f.platformKnown ? '' : 'text-muted-foreground'}>
                      {cellText(f.platformValue, f.platformKnown)}
                    </span>
                  </td>
                  <td className="py-2 pr-3">
                    {/* 🔴 右列恒为未接入，且永远显示这一列 —— 隐藏它就看不见缺口 */}
                    <span className="text-muted-foreground">{cellText(f.internalValue, f.internalKnown)}</span>
                    <span className="text-copy-12 text-muted-foreground ml-2">归 {f.ownerModule}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </SectionCard>

      {/* 内部映射 */}
      <SectionCard
        title="内部映射"
        description={`平台变体 ${detail.variantCount} 个，已映射 ${detail.mappedVariantCount} 个（${mappingLabel(detail.mappingStatus)}）。内部 SKU 是 03 商品的逻辑 id，本模块不跨库查它的编码与名称。`}
      >
        {detail.variants.length === 0 ? (
          <EmptyState
            title="尚未登记平台变体"
            description="没有变体就不存在「已映射 / 未映射」这回事 —— 那是未知，不是全部已映射。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead>
                <tr className="border-b">
                  <th className="py-2 pr-3 font-medium">平台变体</th>
                  <th className="py-2 pr-3 font-medium">平台侧属性</th>
                  <th className="py-2 pr-3 font-medium">内部 SKU</th>
                  <th className="py-2 font-medium">映射状态</th>
                </tr>
              </thead>
              <tbody>
                {detail.variants.map((v) => (
                  <tr key={v.id} className="border-b last:border-0">
                    <td className="py-2 pr-3 font-mono">{v.externalVariantId}</td>
                    <td className="text-muted-foreground py-2 pr-3">{v.attributes || '{}'}</td>
                    <td className="py-2 pr-3 font-mono">
                      {v.internalSkuId ?? <span className="text-muted-foreground">未挂内部 SKU</span>}
                    </td>
                    <td className="py-2">
                      <Badge tone={v.mappingStatus === 'UNMAPPED' ? 'warning' : 'success'}>
                        {variantMappingLabel(v.mappingStatus)}
                      </Badge>
                      {v.componentQuantity !== null && (
                        <span className="text-muted-foreground ml-2">
                          × {quantityText(v.componentQuantity)}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 回写登记簿（只读，且不是售价） */}
      <SectionCard
        title="回写登记簿"
        description="2.5.2.1 登记的「平台上当前在售的值」快照 —— 只读，本模块不写这张表。"
      >
        {detail.writebackRegistry.registered ? (
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-3">
              <Badge tone={detail.writebackRegistry.status === 'BLOCKED' ? 'danger' : 'success'}>
                {detail.writebackRegistry.status === 'BLOCKED' ? '已封禁' : '已登记为可回写目标'}
              </Badge>
              <span className="text-copy-13">
                当前库存快照{' '}
                <span className="font-medium">{quantityText(detail.writebackRegistry.currentStock, '未登记')}</span>
              </span>
              <span className="text-copy-13">
                当前价快照{' '}
                <span className="font-medium">
                  {quantityText(detail.writebackRegistry.currentPrice, '未登记')}
                  {detail.writebackRegistry.currentPrice !== null && detail.writebackRegistry.currency
                    ? ` ${detail.writebackRegistry.currency}`
                    : ''}
                </span>
              </span>
              {detail.writebackRegistry.blockedReason && (
                <span className="text-copy-13 text-destructive">
                  封禁原因：{detail.writebackRegistry.blockedReason}
                </span>
              )}
            </div>
            <p className="text-copy-13 text-muted-foreground">{detail.writebackRegistry.source}</p>
            <p className="text-copy-13 text-muted-foreground border-t pt-2">
              {detail.writebackRegistry.limitation}
            </p>
          </div>
        ) : (
          <div className="space-y-2">
            <EmptyState
              title="该商品尚未登记为可回写目标"
              description="平台上当前在售的价 / 量在本系统内没有登记值 —— 未登记不是 0，也不是「平台上卖 0 块」。"
            />
            <p className="text-copy-13 text-muted-foreground">
              {detail.writebackRegistry.limitation}
            </p>
          </div>
        )}
      </SectionCard>

      {/* 发布历史 */}
      <SectionCard
        title="发布历史"
        description="发布动作的结果留痕（追加写、不可改写）。状态变更另见下方轨迹 —— 状态变了不等于发过一次布。"
      >
        {detail.publishHistory.length === 0 ? (
          <EmptyState
            title="还没有发布记录"
            description="4.3.1.1 发布 / 4.3.2.1 内容回写尚未交付，本模块只负责留痕、不代它们发起发布。"
          />
        ) : (
          <ul className="space-y-2">
            {detail.publishHistory.map((r) => (
              <li key={r.id} className="border-b pb-2 last:border-0 last:pb-0">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={r.outcome === 'SUCCEEDED' ? 'success' : 'danger'}>
                    {publishOutcomeLabel(r.outcome)}
                  </Badge>
                  <span className="text-copy-13">{publishSummary(r)}</span>
                  <span className="text-copy-13 text-muted-foreground ml-auto">
                    {timeText(r.occurredAt)}
                  </span>
                </div>
                {r.snapshotId && (
                  <p className="text-copy-12 text-muted-foreground mt-1 font-mono">
                    内容快照 {r.snapshotId}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* 平台操作记录 */}
      <SectionCard title="平台操作记录" description="发往平台的写操作及其回执（直接读 2.5.1.1 的任务清单，零复制）。">
        {detail.platformOperations.length === 0 ? (
          <EmptyState
            title="没有可归属到本商品的操作记录"
            description="只有带商品键（店铺 + 平台商品 ID）的操作才会出现在这里；店铺级操作请到 2.5.1.1 的任务清单查看。"
          />
        ) : (
          <ul className="space-y-2">
            {detail.platformOperations.map((o) => (
              <li key={o.id} className="border-b pb-2 last:border-0 last:pb-0">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={operationTone(o.status)}>{operationStatusLabel(o.status)}</Badge>
                  <span className="text-copy-13">{operationLabel(o.operation)}</span>
                  <span className="text-copy-13 text-muted-foreground">
                    重试 {o.attempts} 次
                    {o.platformRequestId ? ` · 平台请求 ${o.platformRequestId}` : ''}
                  </span>
                  <span className="text-copy-13 text-muted-foreground ml-auto">
                    {timeText(o.occurredAt)}
                  </span>
                </div>
                {operationStatusHint(o.status) && (
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    {operationStatusHint(o.status)}
                  </p>
                )}
                {o.lastError && <p className="text-copy-12 text-destructive mt-1">{o.lastError}</p>}
              </li>
            ))}
          </ul>
        )}
        {caps?.platformOperationNote && (
          <p className="text-copy-13 text-muted-foreground border-t pt-3">{caps.platformOperationNote}</p>
        )}
      </SectionCard>

      {/* 状态变更轨迹 */}
      <SectionCard title="状态变更轨迹" description="与发布历史不是一回事：状态变了不等于发过一次布。">
        {detail.statusChanges.length === 0 ? (
          <EmptyState title="状态未变过" description="首次登记即为当前状态，尚无变更记录。" />
        ) : (
          <ul className="space-y-1">
            {detail.statusChanges.map((s, i) => (
              <li key={`${s.occurredAt}-${i}`} className="text-copy-13 flex flex-wrap items-center gap-2">
                <Badge tone={s.source === 'PLATFORM' ? 'info' : 'neutral'}>{s.source}</Badge>
                <span>
                  {s.fromStatus ? `${statusLabel(s.fromStatus)} → ` : '首次登记 → '}
                  {statusLabel(s.toStatus)}
                </span>
                <span className="text-muted-foreground ml-auto">{timeText(s.occurredAt)}</span>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {caps?.upstreamNote && (
        <p className="text-copy-13 text-muted-foreground">{caps.upstreamNote}</p>
      )}
    </div>
  );
}

// ================================================================ 局部视图

/** 差异表的一行。🔴 `UNAVAILABLE` 用 warning 且整行左侧标出，不用 success。 */
function DiffRowView({ row }: { row: ListingDiffRow }) {
  return (
    <tr
      className={`border-b last:border-0 ${
        row.status === 'DIFFERENT' ? 'bg-destructive-soft/30' : ''
      }`}
    >
      <td className="py-2 pr-3 font-medium">{row.label}</td>
      <td className="py-2 pr-3">
        <span className={row.platformKnown ? '' : 'text-muted-foreground'}>
          {cellText(row.platformValue, row.platformKnown)}
        </span>
      </td>
      <td className="py-2 pr-3">
        <span className={row.internalKnown ? '' : 'text-muted-foreground'}>
          {cellText(row.internalValue, row.internalKnown)}
        </span>
        {!row.available && (
          <span className="text-copy-12 text-muted-foreground ml-2">归 {row.ownerModule}</span>
        )}
      </td>
      <td className="py-2">
        <Badge tone={diffTone(row.status)}>{diffLabel(row.status)}</Badge>
        {row.note && <p className="text-copy-12 text-muted-foreground mt-1">{row.note}</p>}
        {!row.available && row.impact && (
          <p className="text-copy-12 text-muted-foreground mt-1">{rowExplain(row)}</p>
        )}
      </td>
    </tr>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <div className="text-muted-foreground text-copy-12 mb-1">
        {label}
        {hint && <span className="ml-1">（{hint}）</span>}
      </div>
      <div className="flex flex-wrap items-center">{children}</div>
    </div>
  );
}

function BackToList() {
  return (
    <Link href="/listings">
      <Button variant="outline" size="sm">
        <ArrowLeft className="h-4 w-4" />
        返回列表
      </Button>
    </Link>
  );
}

/** 按分组归拢，组内保持服务端顺序（口径由服务端定，前端不重排）。 */
function groupDiffRows(rows: ListingDiffRow[]): [DiffGroup, ListingDiffRow[]][] {
  const order: DiffGroup[] = ['CONTENT', 'PRICE_STOCK', 'PLATFORM_SIDE', 'REGISTRY', 'MAPPING'];
  return order
    .map((g) => [g, rows.filter((r) => r.group === g)] as [DiffGroup, ListingDiffRow[]])
    .filter(([, rs]) => rs.length > 0);
}

/** 每组一句话说清「这一组在比什么」—— 口径由蓝图定，不在前端重新解释。 */
function groupHint(group: DiffGroup): string {
  switch (group) {
    case 'CONTENT':
      return '平台当前内容 vs 内部草稿内容（4.2.1.1 未交付 ⇒ 右列整列未接入）';
    case 'PRICE_STOCK':
      return '售价 / 内部可售量 / 平台可售状态（05、07 未交付 ⇒ 不可比对）';
    case 'PLATFORM_SIDE':
      return '平台自己的两个事实是否互相矛盾';
    case 'REGISTRY':
      return '平台回报 vs 系统登记簿：两个真实数字之间的落差';
    case 'MAPPING':
      return '平台变体 vs 内部 SKU 映射';
    default:
      return '';
  }
}

function operationTone(
  status: string,
): 'success' | 'danger' | 'warning' | 'info' | 'neutral' {
  switch (status) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'UNKNOWN':
      return 'warning';
    case 'SENDING':
      return 'info';
    default:
      return 'neutral';
  }
}
