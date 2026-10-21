'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import {
  CheckCircle2,
  ExternalLink,
  Image as ImageIcon,
  Loader2,
  RefreshCw,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  allowedHosts,
  attemptLabel,
  canRetry,
  capturedAtLabel,
  collectStateLabel,
  collectStateTone,
  createCollect,
  deduplicatedNote,
  DRAFT_ONLY_NOTE,
  emptyAllowlistMessage,
  emptyCollectsMessage,
  failureReason,
  fetchCollect,
  fetchCollectCapabilities,
  fetchCollects,
  IMAGE_URL_ONLY_NOTE,
  keyValueRows,
  precheckUrl,
  sourceKindLabel,
  sourcePriceLabel,
  unavailableDimensions,
  type CollectListingResult,
  type ListingCollectCapabilities,
  type ListingCollectDraftRow,
  type ListingCollectSummaryRow,
} from './listing-collect-model';

/**
 * 4.4.1.1 链接采集 工作区。
 *
 * 蓝图：「粘贴外部商品链接（1688/淘宝/供应商站/同行 listing），抓取标题、主图、规格、属性、描述等，生成采集草稿供认领为内部商品。输入：商品
 * URL（支持平台白名单）；抓取任务。输出：采集到的商品草稿（图片下载入媒体库）；来源 URL 与抓取时间。关系：上游 = 外部页面；下游 =
 * 4.4.1.3 采集箱、3.1.1.1（认领为 SPU）。<b>采集只产生草稿</b>，进入正式商品库必须走 03 的建档流程并补全属性与合规信息。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **采集只产生草稿**。页面不出现「发布」「上架」「建档」按钮，也不出现价格 / 库存输入框 ——
 *    采集品要进商品库得走 4.4.1.3 采集箱 → 3.1.1.1 建档，页面给出的是**未交付段明账**。
 * 2. **图片只记 URL**。图片区显示的是**外部图片地址**（可点开），并明写「未下载入媒体库」。
 * 3. **白名单外要明确说原因**。服务端返回 400 时把**原文**显示出来（哪个域名不在白名单、
 *    当前允许哪些来源），而不是笼统的「采集失败」。
 * 4. **去重要看得见**。命中同 URL 去重时页面明说「已采过，直接返回既有草稿（未再次请求来源站点）」。
 * 5. **失败要能重试**。失败态显示服务端给的**失败原因**，并给出重试按钮。
 */
export function ListingCollectWorkspace() {
  const [caps, setCaps] = useState<ListingCollectCapabilities | null>(null);
  const [rows, setRows] = useState<ListingCollectSummaryRow[]>([]);
  const [total, setTotal] = useState(0);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [detail, setDetail] = useState<CollectListingResult | null>(null);

  // ---- 表单
  const [url, setUrl] = useState('');
  const [stateFilter, setStateFilter] = useState('');
  const [sourceFilter, setSourceFilter] = useState('');

  const [loading, setLoading] = useState(true);
  const [collecting, setCollecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [rejection, setRejection] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, page] = await Promise.all([
        fetchCollectCapabilities(),
        fetchCollects(stateFilter || undefined, sourceFilter || undefined),
      ]);
      setCaps(capsResult);
      setRows(page.items);
      setTotal(page.total);
      setActiveId((prev) => prev ?? (page.items.length > 0 ? page.items[0]!.taskId : null));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [stateFilter, sourceFilter]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 选中某条采集记录时，拉它的详情（任务 + 草稿）
  useEffect(() => {
    if (!activeId) {
      setDetail(null);
      return;
    }
    let cancelled = false;
    void fetchCollect(activeId)
      .then((result) => {
        if (!cancelled) setDetail(result);
      })
      .catch(() => {
        // 详情拉不到不阻塞列表（列表里已经有摘要）
        if (!cancelled) setDetail(null);
      });
    return () => {
      cancelled = true;
    };
  }, [activeId]);

  /** 🔴 发起采集。失败分两类：**请求被拒**（400 白名单 / 限频）与**抓取失败**（任务 FAILED）。 */
  const collect = useCallback(
    async (target: string) => {
      const precheck = precheckUrl(target);
      if (precheck) {
        setRejection(precheck);
        setError(null);
        return;
      }
      setCollecting(true);
      setRejection(null);
      setNotice(null);
      setError(null);
      try {
        const result = await createCollect(target);
        setDetail(result);
        setActiveId(result.task.taskId);
        const dedup = deduplicatedNote(result);
        if (dedup) {
          setNotice(dedup);
        } else if (result.task.state === 'FAILED') {
          setNotice(null);
        } else {
          setNotice(null);
        }
        await refresh();
      } catch (e) {
        // 🔴 400（白名单外 / 限频）= 请求根本没被允许：把服务端给的原文显示出来
        const message = e instanceof Error ? e.message : '采集请求失败';
        setRejection(message);
        setError(null);
        await refresh();
      } finally {
        setCollecting(false);
      }
    },
    [refresh],
  );

  const activeDraft: ListingCollectDraftRow | null = detail?.draft ?? null;
  const activeTask = detail?.task ?? null;
  const specRows = useMemo(() => keyValueRows(activeDraft?.specs), [activeDraft]);
  const attributeRows = useMemo(() => keyValueRows(activeDraft?.attributes), [activeDraft]);
  const hosts = useMemo(() => allowedHosts(caps), [caps]);
  const allowlistEmpty = emptyAllowlistMessage(caps);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载链接采集…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="链接采集">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="链接采集加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="链接采集"
        description="粘贴外部商品链接（1688 / 淘宝 / 供应商站 / 同行 listing），抓取标题、主图、规格、属性、描述，生成采集草稿供后续认领为内部商品。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}

      {/* ---- 🔴 白名单拒绝：把服务端给的原文显示出来，不笼统报错 ---- */}
      {rejection && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <ShieldCheck className="mt-0.5 size-4 shrink-0" />
          <div className="space-y-1">
            <p className="text-label-13 font-medium">这次采集被拒绝了</p>
            <p className="text-label-13 whitespace-pre-wrap">{rejection}</p>
          </div>
        </div>
      )}

      {/* ---- 🔴 去重提示 ---- */}
      {notice && (
        <div className="border-brand/40 bg-brand-soft/40 flex items-start gap-2 rounded-lg border px-4 py-3">
          <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-brand" />
          <p className="text-label-13">{notice}</p>
        </div>
      )}

      {/* ---- 🔴 两条边界的界面措辞（后端给原文，前端原样展示） ---- */}
      {caps && (
        <div className="border-brand/40 bg-brand-soft/40 rounded-lg border px-4 py-3">
          <p className="text-foreground mb-1 text-label-13 font-medium">链接采集的三条硬边界</p>
          <ul className="text-muted-foreground space-y-1 text-label-12">
            <li className="flex items-start gap-2">
              <ShieldCheck className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.draftNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <ImageIcon className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.mediaNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <CheckCircle2 className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.dedupNote}</span>
            </li>
          </ul>
        </div>
      )}

      {/* ---- 未接入维度明账 ---- */}
      {caps && unavailableDimensions(caps).length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3">
          <p className="text-warning mb-1 text-label-13 font-medium">部分能力未接入</p>
          <ul className="text-muted-foreground space-y-1 text-label-12">
            {unavailableDimensions(caps).map((d) => (
              <li key={d.dimension}>
                <span className="text-foreground">{d.description}</span>
                ：归 {d.ownerModule} —— {d.impact}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ---- 采集表单 ---- */}
      <SectionCard
        title="采集一个商品链接"
        description={`粘贴商品链接 → 真的去那个页面抓取 → 生成采集草稿。当前允许的来源：${hosts.join('、') || '（白名单为空）'}`}
        extra={
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={collecting}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        <div className="space-y-3 px-4 py-4">
          {allowlistEmpty && (
            <div className="border-warning/40 bg-warning/5 text-warning flex items-start gap-2 rounded-lg border px-3 py-2">
              <TriangleAlert className="mt-0.5 size-4 shrink-0" />
              <p className="text-label-13">{allowlistEmpty}</p>
            </div>
          )}
          <div className="flex flex-wrap gap-2">
            <Input
              className="max-w-lg"
              placeholder="https://detail.1688.com/offer/123456.html"
              value={url}
              disabled={collecting}
              onChange={(e) => setUrl(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void collect(url);
              }}
            />
            <Button onClick={() => void collect(url)} disabled={collecting}>
              {collecting ? (
                <>
                  <Loader2 className="mr-2 size-4 animate-spin" />
                  采集中…
                </>
              ) : (
                '开始采集'
              )}
            </Button>
          </div>
          <p className="text-muted-foreground text-label-12">
            🔴 {DRAFT_ONLY_NOTE}
          </p>
        </div>
      </SectionCard>

      {/* ---- 采集结果 ---- */}
      <SectionCard
        title="采集结果"
        description="草稿内容来自来源页面的原文（JSON-LD / Open Graph / 规格表），不是我们编辑的。"
      >
        {collecting ? (
          <div className="flex items-center justify-center gap-2 px-4 py-12 text-muted-foreground">
            <Loader2 className="size-4 animate-spin" />
            <span className="text-label-13">正在抓取来源页面…</span>
          </div>
        ) : !activeTask ? (
          <EmptyState
            icon={<ImageIcon className="size-8 text-muted-foreground" />}
            title="还没有采集结果"
            description="在上面粘贴一个商品链接开始采集，或从下面的采集历史里选一条。"
          />
        ) : (
          <div className="space-y-4 px-4 py-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={collectStateTone(activeTask.state)}>
                {collectStateLabel(activeTask.state)}
              </Badge>
              <span className="text-muted-foreground text-label-12">
                {activeTask.sourceName}（{activeTask.sourceCode}） · {attemptLabel(activeTask.attemptNo)} ·
                抓取于 {capturedAtLabel(activeTask.capturedAt)}
              </span>
              {canRetry(activeTask) && (
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={collecting}
                  onClick={() => void collect(activeTask.sourceUrl)}
                >
                  <RefreshCw className="mr-2 size-4" />
                  重试
                </Button>
              )}
            </div>

            {activeTask.state === 'FAILED' && (
              <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-3 py-2">
                <TriangleAlert className="mt-0.5 size-4 shrink-0" />
                <div className="space-y-1">
                  <p className="text-label-13 font-medium">采集失败</p>
                  <p className="text-label-13 whitespace-pre-wrap">{failureReason(activeTask)}</p>
                </div>
              </div>
            )}

            {activeTask.state === 'SUCCEEDED' && activeDraft && (
              <>
                <div>
                  <p className="text-muted-foreground text-label-12">来源 URL</p>
                  <a
                    className="text-brand inline-flex items-center gap-1 text-label-13 underline"
                    href={activeDraft.sourceUrl}
                    target="_blank"
                    rel="noreferrer"
                  >
                    {activeDraft.sourceUrl}
                    <ExternalLink className="size-3" />
                  </a>
                  {activeDraft.normalizedUrl !== activeDraft.sourceUrl && (
                    <p className="text-muted-foreground mt-1 text-label-12">
                      规范化 URL（去跟踪参数后的去重键）：{activeDraft.normalizedUrl}
                    </p>
                  )}
                </div>

                <div>
                  <p className="text-foreground text-label-13 font-medium">{activeDraft.title}</p>
                </div>

                {activeDraft.mainImageUrl && (
                  <div className="space-y-1">
                    <p className="text-muted-foreground text-label-12">主图</p>
                    <a
                      className="text-brand inline-flex items-center gap-1 text-label-12 underline"
                      href={activeDraft.mainImageUrl}
                      target="_blank"
                      rel="noreferrer"
                    >
                      {activeDraft.mainImageUrl}
                      <ExternalLink className="size-3" />
                    </a>
                  </div>
                )}

                {activeDraft.imageUrls.length > 1 && (
                  <div className="space-y-1">
                    <p className="text-muted-foreground text-label-12">
                      附图（{activeDraft.imageUrls.length - 1} 张）
                    </p>
                    <ul className="text-muted-foreground space-y-0.5 text-label-12">
                      {activeDraft.imageUrls.slice(1).map((image) => (
                        <li key={image}>
                          <a
                            className="text-brand underline"
                            href={image}
                            target="_blank"
                            rel="noreferrer"
                          >
                            {image}
                          </a>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                <p className="text-muted-foreground text-label-12">🔴 {IMAGE_URL_ONLY_NOTE}</p>

                {specRows.length > 0 && (
                  <div>
                    <p className="text-foreground mb-1 text-label-13 font-medium">规格</p>
                    <dl className="space-y-1">
                      {specRows.map((row) => (
                        <div key={row.name} className="flex gap-2 text-label-12">
                          <dt className="text-muted-foreground min-w-24 shrink-0">{row.name}</dt>
                          <dd className="text-foreground">{row.value}</dd>
                        </div>
                      ))}
                    </dl>
                  </div>
                )}

                {attributeRows.length > 0 && (
                  <div>
                    <p className="text-foreground mb-1 text-label-13 font-medium">属性</p>
                    <dl className="space-y-1">
                      {attributeRows.map((row) => (
                        <div key={row.name} className="flex gap-2 text-label-12">
                          <dt className="text-muted-foreground min-w-24 shrink-0">{row.name}</dt>
                          <dd className="text-foreground">{row.value}</dd>
                        </div>
                      ))}
                    </dl>
                  </div>
                )}

                {activeDraft.description && (
                  <div>
                    <p className="text-foreground mb-1 text-label-13 font-medium">描述</p>
                    <p className="text-muted-foreground text-label-12">{activeDraft.description}</p>
                  </div>
                )}

                <div>
                  <p className="text-muted-foreground text-label-12">
                    🔴 来源页面原文标价：{sourcePriceLabel(
                      activeDraft.sourcePriceAmount,
                      activeDraft.sourcePriceCurrency,
                    )}
                    （**不是我方售价** —— 售价归 05 定价）
                  </p>
                </div>
              </>
            )}
          </div>
        )}
      </SectionCard>

      {/* ---- 采集历史 ---- */}
      <SectionCard
        title="采集历史"
        description={`按时间倒序，共 ${total} 条。`}
        extra={
          <div className="flex items-center gap-2">
            <select
              className="border-border bg-background text-foreground h-8 rounded-md border px-2 text-label-12"
              value={stateFilter}
              onChange={(e) => {
                setStateFilter(e.target.value);
                setActiveId(null);
                void refresh();
              }}
            >
              <option value="">全部状态</option>
              <option value="SUCCEEDED">采集成功</option>
              <option value="FAILED">采集失败</option>
            </select>
            <select
              className="border-border bg-background text-foreground h-8 rounded-md border px-2 text-label-12"
              value={sourceFilter}
              onChange={(e) => {
                setSourceFilter(e.target.value);
                setActiveId(null);
                void refresh();
              }}
            >
              <option value="">全部来源</option>
              {caps?.sources.map((source) => (
                <option key={source.code} value={source.code}>
                  {source.name}（{sourceKindLabel(source.kind)}）
                </option>
              ))}
            </select>
          </div>
        }
      >
        {rows.length === 0 ? (
          <EmptyState
            icon={<ImageIcon className="size-8 text-muted-foreground" />}
            title="没有采集记录"
            description={emptyCollectsMessage()}
          />
        ) : (
          <ul className="divide-border divide-y">
            {rows.map((row) => (
              <li key={row.taskId}>
                <button
                  type="button"
                  className={
                    row.taskId === activeId
                      ? 'bg-brand-soft/40 w-full px-4 py-3 text-left'
                      : 'hover:bg-muted/40 w-full px-4 py-3 text-left'
                  }
                  onClick={() => setActiveId(row.taskId)}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={collectStateTone(row.state)}>{collectStateLabel(row.state)}</Badge>
                    <span className="text-foreground text-label-13">
                      {row.title || row.sourceUrl}
                    </span>
                    <span className="text-muted-foreground text-label-12">
                      {row.sourceName} · {attemptLabel(row.attemptNo)} ·{' '}
                      {capturedAtLabel(row.capturedAt)}
                    </span>
                  </div>
                  {row.state === 'FAILED' && row.errorMessage && (
                    <p className="text-destructive mt-1 text-label-12">{row.errorMessage}</p>
                  )}
                </button>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>
    </div>
  );
}
