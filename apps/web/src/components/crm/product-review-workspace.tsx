'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { Download, MessageSquare, RefreshCw, Star, TrendingDown } from 'lucide-react';
import type {
  CrmProductReviewCapabilities,
  CrmProductReviewDetail,
  CrmProductReviewPage,
  CrmProductReviewRow,
  CrmProductReviewShopView,
  CrmProductReviewStats,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  continuousLabel,
  createProductReview,
  declineLabel,
  dimensionLabel,
  downloadProductReviewCsv,
  fetchProductReview,
  fetchProductReviewCapabilities,
  fetchProductReviewShops,
  fetchProductReviewStats,
  fetchProductReviews,
  ratingLabel,
  replyStateLabel,
  reviewSourceLabel,
  syncProductReviews,
} from './product-review-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.2.1.1 评价列表工作区。
 *
 * <p>页面按「先看清边界、再看趋势、后看单条」的顺序排，因为蓝图加粗句是
 * 「<b>评分趋势比单条评价更重要</b>：连续下滑要先查是产品问题还是履约问题」——
 * 界面上第一件要让人看到的就是**能力边界**与**趋势**，
 * 而不是一张可以逐条翻的评价清单。
 *
 * <ul>
 *   <li><b>能力声明</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       平台评价同步、评价回复、差评处理三块是 available=false，
 *       页面把它们<b>显式灰掉</b>并写清归属模块，不给任何看起来能点的假入口。</li>
 *   <li><b>评分统计</b> —— 均分 / 星级分布 / 时间趋势 / 三个维度的汇总 /
 *       <b>在下滑的维度</b>（连续下滑会被单独标出）。
 *       数值全部<b>原样展示</b>，页面不做任何前端算术。</li>
 *   <li><b>评价列表</b> —— 真分页 + 真筛选（星级 / 含图 / 含视频 / 未回复 / 时间范围 / 店铺 /
 *       变体 / 订单号 / 关键字），空态区分「筛选无结果」与「还没有评价」。</li>
 *   <li><b>评价详情</b> —— 正文 + 图片 + <b>关联订单上下文</b>。
 *       订单上下文读不到时显示「订单上下文不可用」+ 原因，<b>绝不显示空白</b>。</li>
 *   <li><b>登记评价</b> —— 平台适配器未接入时，这是唯一能进评价的入口。</li>
 * </ul>
 */
export function ProductReviewWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.product_review.update');

  const [capabilities, setCapabilities] = useState<CrmProductReviewCapabilities | null>(null);
  const [shops, setShops] = useState<CrmProductReviewShopView[]>([]);
  const [page, setPage] = useState<CrmProductReviewPage | null>(null);
  const [stats, setStats] = useState<CrmProductReviewStats | null>(null);
  const [detail, setDetail] = useState<CrmProductReviewDetail | null>(null);

  // 筛选
  const [shopId, setShopId] = useState('');
  const [rating, setRating] = useState('');
  const [hasImage, setHasImage] = useState(false);
  const [hasVideo, setHasVideo] = useState(false);
  const [unreplied, setUnreplied] = useState(false);
  const [variantRef, setVariantRef] = useState('');
  const [salesOrderNo, setSalesOrderNo] = useState('');
  const [keyword, setKeyword] = useState('');
  const [reviewedFrom, setReviewedFrom] = useState('');
  const [reviewedTo, setReviewedTo] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const [statsDays, setStatsDays] = useState(30);

  // 登记评价
  const [newShopId, setNewShopId] = useState('');
  const [newRating, setNewRating] = useState('5');
  const [newContent, setNewContent] = useState('');
  const [newProductRef, setNewProductRef] = useState('');
  const [newProductTitle, setNewProductTitle] = useState('');
  const [newVariantRef, setNewVariantRef] = useState('');
  const [newVariantLabel, setNewVariantLabel] = useState('');
  const [newImageUrls, setNewImageUrls] = useState('');
  const [newVideoUrl, setNewVideoUrl] = useState('');
  const [newBuyerRef, setNewBuyerRef] = useState('');
  const [newOrderNo, setNewOrderNo] = useState('');
  const [newReviewedAt, setNewReviewedAt] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  /** 当前是否有筛选条件 —— 空态要区分「筛选无结果」与「还没有评价」。 */
  const hasFilter = useMemo(
    () =>
      Boolean(
        shopId ||
          rating ||
          hasImage ||
          hasVideo ||
          unreplied ||
          variantRef ||
          salesOrderNo ||
          keyword ||
          reviewedFrom ||
          reviewedTo,
      ),
    [
      shopId,
      rating,
      hasImage,
      hasVideo,
      unreplied,
      variantRef,
      salesOrderNo,
      keyword,
      reviewedFrom,
      reviewedTo,
    ],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = {
        shopId: shopId || undefined,
        rating: rating ? Number(rating) : undefined,
        hasImage: hasImage || undefined,
        hasVideo: hasVideo || undefined,
        unreplied: unreplied || undefined,
        variantRef: variantRef || undefined,
        salesOrderNo: salesOrderNo || undefined,
        keyword: keyword || undefined,
        reviewedFrom: reviewedFrom || undefined,
        reviewedTo: reviewedTo || undefined,
        page: pageIndex,
        size: 20,
      };
      const [caps, shopList, reviewPage, reviewStats] = await Promise.all([
        fetchProductReviewCapabilities(),
        fetchProductReviewShops(),
        fetchProductReviews(params),
        fetchProductReviewStats(params, statsDays),
      ]);
      setCapabilities(caps);
      setShops(shopList);
      setPage(reviewPage);
      setStats(reviewStats);
    } catch (e) {
      setError(errorText(e, '评价工作区加载失败'));
    } finally {
      setLoading(false);
    }
  }, [
    shopId,
    rating,
    hasImage,
    hasVideo,
    unreplied,
    variantRef,
    salesOrderNo,
    keyword,
    reviewedFrom,
    reviewedTo,
    pageIndex,
    statsDays,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (reviewId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchProductReview(reviewId));
    } catch (e) {
      setActionError(errorText(e, '评价详情读取失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const submitReview = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createProductReview({
        shopId: newShopId,
        rating: Number(newRating),
        title: '',
        content: newContent,
        productRef: newProductRef,
        productTitle: newProductTitle,
        variantRef: newVariantRef,
        variantLabel: newVariantLabel,
        imageUrls: newImageUrls
          .split('\n')
          .map((line) => line.trim())
          .filter((line) => line.length > 0),
        videoUrl: newVideoUrl,
        buyerRef: newBuyerRef,
        buyerName: '',
        salesOrderNo: newOrderNo,
        externalReviewId: '',
        replied: false,
        repliedAt: '',
        platformReviewedAt: newReviewedAt,
        note: '',
      });
      setNotice(`已登记评价 ${created.reviewNo}（${ratingLabel(created.rating)}）。`);
      setNewContent('');
      setNewImageUrls('');
      setNewVideoUrl('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '登记评价失败'));
    } finally {
      setBusy(false);
    }
  }, [
    newShopId,
    newRating,
    newContent,
    newProductRef,
    newProductTitle,
    newVariantRef,
    newVariantLabel,
    newImageUrls,
    newVideoUrl,
    newBuyerRef,
    newOrderNo,
    newReviewedAt,
    load,
  ]);

  const runSync = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await syncProductReviews({ shopId: newShopId });
      setNotice(result.note);
      await load();
    } catch (e) {
      // 🔴 平台评价适配器未接入 ⇒ 这里恒为 409。页面**如实显示原因**，
      //   不给一个「点了就成功」的假按钮。
      setActionError(errorText(e, '平台评价同步失败（适配器未接入，本次没有写入任何评价）'));
    } finally {
      setBusy(false);
    }
  }, [newShopId, load]);

  const runExport = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      await downloadProductReviewCsv({
        shopId: shopId || undefined,
        rating: rating ? Number(rating) : undefined,
        hasImage: hasImage || undefined,
        hasVideo: hasVideo || undefined,
        unreplied: unreplied || undefined,
        variantRef: variantRef || undefined,
        salesOrderNo: salesOrderNo || undefined,
        keyword: keyword || undefined,
        reviewedFrom: reviewedFrom || undefined,
        reviewedTo: reviewedTo || undefined,
      });
      setNotice('已导出当前筛选结果（CSV 与列表同一套筛选条件）。');
    } catch (e) {
      setActionError(errorText(e, '导出失败'));
    } finally {
      setBusy(false);
    }
  }, [
    shopId,
    rating,
    hasImage,
    hasVideo,
    unreplied,
    variantRef,
    salesOrderNo,
    keyword,
    reviewedFrom,
    reviewedTo,
  ]);

  if (loading) {
    return (
      <SectionCard title="评价工作区">
        <p className="text-muted-foreground text-copy-13">加载中…</p>
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      {error && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive rounded-md px-3 py-2 text-copy-13">
          {error}
        </div>
      )}
      {actionError && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive rounded-md px-3 py-2 text-copy-13">
          {actionError}
        </div>
      )}
      {notice && (
        <div className="border-border bg-muted/40 rounded-md px-3 py-2 text-copy-13">{notice}</div>
      )}

      {/* ---------------------------------------------------------- 能力边界 */}
      <SectionCard title="能力边界">
        <ul className="space-y-2 text-copy-13">
          {(capabilities?.dimensions ?? []).map((dimension) => (
            <li key={dimension.dimension} className="flex flex-wrap items-start gap-2">
              <Badge tone={dimension.available ? 'success' : 'warning'}>
                {dimension.available ? '可用' : '未接入'}
              </Badge>
              <span className="font-medium">{dimension.description}</span>
              {!dimension.available && (
                <span className="text-muted-foreground">
                  （归属模块 {dimension.ownerModule}）—— {dimension.impact}
                </span>
              )}
            </li>
          ))}
        </ul>
        {capabilities?.highlight && (
          <p className="text-muted-foreground mt-3 text-copy-12">{capabilities.highlight}</p>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 评分统计 */}
      <SectionCard title="评分统计（趋势 · 分布 · 下滑定位）">
        {!stats ? (
          <p className="text-muted-foreground text-copy-13">暂无统计数据。</p>
        ) : (
          <div className="space-y-4 text-copy-13">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-muted-foreground">作用域</span>
              <select
                className={control}
                value={statsDays}
                onChange={(e) => setStatsDays(Number(e.target.value))}
              >
                <option value={7}>近 7 天</option>
                <option value={30}>近 30 天</option>
                <option value={90}>近 90 天</option>
                <option value={365}>近 365 天</option>
              </select>
              <span className="text-muted-foreground">
                {stats.scopeFrom} 起 · 共 <strong>{stats.totalReviews}</strong> 条 · 平均分{' '}
                <strong>{stats.averageRating ?? '—'}</strong> · 未回复{' '}
                <strong>{stats.unrepliedCount}</strong> · 含图 <strong>{stats.withImageCount}</strong>{' '}
                · 含视频 <strong>{stats.withVideoCount}</strong>
              </span>
            </div>

            <div>
              <h3 className="font-medium">星级分布</h3>
              <ul className="mt-1 flex flex-wrap gap-3 text-copy-12">
                {stats.ratingDistribution.map((bucket) => (
                  <li key={bucket.rating}>
                    {ratingLabel(bucket.rating)}：<strong>{bucket.count}</strong> 条
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <h3 className="font-medium">趋势（按平台评价时刻分桶）</h3>
              <ul className="mt-1 space-y-1 text-copy-12">
                {stats.trend.map((bucket) => (
                  <li key={bucket.bucketStart}>
                    {bucket.bucketStart} → {bucket.bucketEnd}：<strong>{bucket.count}</strong> 条 ·
                    均分 {bucket.averageRating ?? '—'}
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <h3 className="flex items-center gap-1 font-medium">
                <TrendingDown className="h-4 w-4" />
                在下滑的维度
              </h3>
              {stats.declining.length === 0 ? (
                <p className="text-muted-foreground mt-1 text-copy-12">
                  当前作用域内没有出现下滑的维度（店铺 / 商品 / 变体）。
                </p>
              ) : (
                <ul className="mt-1 space-y-1 text-copy-12">
                  {stats.declining.map((decline) => (
                    <li key={`${decline.dimension}-${decline.key}`} className="flex flex-wrap gap-2">
                      <Badge tone={decline.continuous ? 'danger' : 'neutral'}>
                        {continuousLabel(decline.continuous)}
                      </Badge>
                      <span>{declineLabel(decline)}</span>
                      <span className="text-muted-foreground">
                        近窗 {decline.recentAverage ?? '—'} · 中窗 {decline.previousAverage ?? '—'} ·
                        远窗 {decline.olderAverage ?? '—'} · 差值 {decline.delta ?? '—'} · 共{' '}
                        {decline.count} 条
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {stats.note && <p className="text-muted-foreground text-copy-12">{stats.note}</p>}
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 筛选 */}
      <SectionCard title="筛选与导出">
        <div className="grid gap-3 md:grid-cols-4">
          <Field label="店铺">
            <select className={control} value={shopId} onChange={(e) => setShopId(e.target.value)}>
              <option value="">全部店铺</option>
              {shops.map((shop) => (
                <option key={shop.id} value={shop.id}>
                  {shop.name} · {shop.platformCode}
                </option>
              ))}
            </select>
          </Field>
          <Field label="星级">
            <select className={control} value={rating} onChange={(e) => setRating(e.target.value)}>
              <option value="">全部星级</option>
              {[5, 4, 3, 2, 1].map((value) => (
                <option key={value} value={value}>
                  {value} 星
                </option>
              ))}
            </select>
          </Field>
          <Field label="变体编码">
            <input
              className={control}
              value={variantRef}
              onChange={(e) => setVariantRef(e.target.value)}
              placeholder="SKU-1001"
            />
          </Field>
          <Field label="关联订单号">
            <input
              className={control}
              value={salesOrderNo}
              onChange={(e) => setSalesOrderNo(e.target.value)}
              placeholder="SO-1001"
            />
          </Field>
          <Field label="平台评价时刻起点" hint="留空 = 不限。">
            <input
              className={control}
              value={reviewedFrom}
              onChange={(e) => setReviewedFrom(e.target.value)}
              placeholder="2026-10-01T00:00:00+08:00"
            />
          </Field>
          <Field label="平台评价时刻终点" hint="留空 = 不限。">
            <input
              className={control}
              value={reviewedTo}
              onChange={(e) => setReviewedTo(e.target.value)}
              placeholder="2026-10-31T00:00:00+08:00"
            />
          </Field>
          <Field label="关键字" hint="内容 / 标题 / 订单号 / 评价号 / 买家标识。">
            <input
              className={control}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </Field>
          <Field label="其它筛选">
            <div className="flex flex-wrap gap-3 pt-1">
              <label className="flex items-center gap-1 text-copy-12">
                <input
                  type="checkbox"
                  checked={hasImage}
                  onChange={(e) => setHasImage(e.target.checked)}
                />
                含图
              </label>
              <label className="flex items-center gap-1 text-copy-12">
                <input
                  type="checkbox"
                  checked={hasVideo}
                  onChange={(e) => setHasVideo(e.target.checked)}
                />
                含视频
              </label>
              <label className="flex items-center gap-1 text-copy-12">
                <input
                  type="checkbox"
                  checked={unreplied}
                  onChange={(e) => setUnreplied(e.target.checked)}
                />
                未回复
              </label>
            </div>
          </Field>
        </div>

        <div className="mt-3 flex flex-wrap gap-2">
          <Button onClick={() => setPageIndex(0)} size="sm" variant="secondary">
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
          <Button onClick={() => void runExport()} size="sm" variant="secondary" disabled={busy}>
            <Download className="mr-1 h-4 w-4" />
            导出 CSV
          </Button>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 评价列表 */}
      <SectionCard title="评价列表">
        {!page || page.items.length === 0 ? (
          <EmptyState
            title={hasFilter ? '当前筛选没有命中任何评价' : '还没有评价'}
            description={
              hasFilter
                ? '把筛选条件放宽一点再试；导出走的也是同一套筛选条件。'
                : '平台评价同步适配器未交付，评价目前只能人工登记 —— 登记后这里就会出现它们。'
            }
          />
        ) : (
          <>
            <p className="text-muted-foreground mb-2 text-copy-12">
              共 <strong>{page.totalElements}</strong> 条 · 第 {page.page + 1} / {page.totalPages} 页
            </p>
            <ul className="space-y-2 text-copy-13">
              {page.items.map((row) => (
                <li key={row.id} className="border-border rounded-md px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={row.rating >= 4 ? 'success' : row.rating >= 3 ? 'neutral' : 'danger'}>
                      <Star className="mr-1 h-3 w-3" />
                      {ratingLabel(row.rating)}
                    </Badge>
                    <Badge tone={row.replied ? 'neutral' : 'warning'}>
                      {replyStateLabel(row.replied)}
                    </Badge>
                    <button
                      type="button"
                      className="hover:underline font-medium"
                      onClick={() => void openDetail(row.id)}
                    >
                      {row.reviewNo}
                    </button>
                    <span>
                      {row.shopName} · {row.platformCode} · 变体 {row.variantRef}
                    </span>
                    <span className="text-muted-foreground">
                      平台时刻 {row.platformReviewedAt} ·{' '}
                      {row.hasImage ? '含图' : '无图'} · {row.hasVideo ? '含视频' : '无视频'}
                    </span>
                    {row.salesOrderNo && (
                      <span className="text-muted-foreground">订单 {row.salesOrderNo}</span>
                    )}
                  </div>
                  {row.content && (
                    <p className="text-muted-foreground mt-1 line-clamp-2 text-copy-12">
                      {row.content}
                    </p>
                  )}
                </li>
              ))}
            </ul>
            <div className="mt-3 flex gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={page.page <= 0}
                onClick={() => setPageIndex(Math.max(page.page - 1, 0))}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={page.page + 1 >= page.totalPages}
                onClick={() => setPageIndex(page.page + 1)}
              >
                下一页
              </Button>
            </div>
          </>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 详情 */}
      {detail && (
        <SectionCard title={`评价详情 · ${detail.review.reviewNo}`}>
          <div className="space-y-3 text-copy-13">
            <p>
              {detail.review.shopName} · {detail.review.platformCode} ·{' '}
              {ratingLabel(detail.review.rating)} · {replyStateLabel(detail.review.replied)} ·{' '}
              {reviewSourceLabel(detail.review.source)}
            </p>
            {detail.review.title && <p className="font-medium">{detail.review.title}</p>}
            {detail.review.content && <p>{detail.review.content}</p>}
            <p className="text-muted-foreground text-copy-12">
              商品 {detail.review.productRef || '—'} · 变体 {detail.review.variantRef}
              {detail.review.variantLabel ? `（${detail.review.variantLabel}）` : ''} · 买家{' '}
              {detail.review.buyerRef || '—'} · 平台评价 id {detail.review.externalReviewId || '—'}
            </p>
            {detail.review.imageUrls.length > 0 && (
              <p className="text-muted-foreground text-copy-12">
                图片 {detail.review.imageUrls.length} 张：{detail.review.imageUrls.join(' · ')}
              </p>
            )}
            {detail.review.videoUrl && (
              <p className="text-muted-foreground text-copy-12">视频：{detail.review.videoUrl}</p>
            )}

            {/* 🔴 订单上下文：读不到时**明确说不可用 + 原因**，绝不显示空白。 */}
            <div className="border-border rounded-md px-3 py-2">
              <h3 className="font-medium">关联订单上下文</h3>
              {detail.orderContext.available ? (
                detail.orderContext.reason ? (
                  <p className="text-muted-foreground mt-1 text-copy-12">
                    {detail.orderContext.reason}
                  </p>
                ) : null
              ) : (
                <p className="text-destructive mt-1 text-copy-12">
                  订单上下文不可用：{detail.orderContext.reason}
                </p>
              )}
              <p className="text-muted-foreground mt-1 text-copy-12">
                订单号 {detail.orderContext.salesOrderNo || '—'} · 单号{' '}
                {detail.orderContext.orderNo || '—'} · 订单状态{' '}
                {detail.orderContext.orderStatus || '—'} · 签收时刻{' '}
                {detail.orderContext.deliveredAt || '—'} · 履约状态{' '}
                {detail.orderContext.deliveryState || '—'} · 承运商{' '}
                {detail.orderContext.carrierCode || '—'} · 确认单{' '}
                {detail.orderContext.caseNo || '—'}
              </p>
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 登记评价 */}
      <SectionCard title="登记评价（人工登记）">
        <div className="space-y-3">
          <p className="text-muted-foreground text-copy-12">
            平台评价适配器未交付 —— 同步端点会明确答「未接入」并且<b>一行都不写</b>，
            这里是目前唯一能进评价的入口。
          </p>
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="店铺" hint="来自上游 channel 的真实店铺清单。">
              <select
                className={control}
                value={newShopId}
                onChange={(e) => setNewShopId(e.target.value)}
                disabled={!writable}
              >
                <option value="">（选择一家店铺）</option>
                {shops.map((shop) => (
                  <option key={shop.id} value={shop.id}>
                    {shop.name} · {shop.platformCode}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="星级" hint="1 ~ 5 星。">
              <select
                className={control}
                value={newRating}
                onChange={(e) => setNewRating(e.target.value)}
                disabled={!writable}
              >
                {[5, 4, 3, 2, 1].map((value) => (
                  <option key={value} value={value}>
                    {value} 星
                  </option>
                ))}
              </select>
            </Field>
            <Field label="平台评价时刻" hint="带时区偏移量，例如 2026-10-10T08:00:00+08:00。">
              <input
                className={control}
                value={newReviewedAt}
                onChange={(e) => setNewReviewedAt(e.target.value)}
                placeholder="2026-10-10T08:00:00+08:00"
                disabled={!writable}
              />
            </Field>
            <Field label="商品编码" hint="必填 —— 没有商品维度就定位不到是哪个商品出了问题。">
              <input
                className={control}
                value={newProductRef}
                onChange={(e) => setNewProductRef(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="商品名称">
              <input
                className={control}
                value={newProductTitle}
                onChange={(e) => setNewProductTitle(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="变体编码" hint="必填 —— 蓝图里评价就带着变体。">
              <input
                className={control}
                value={newVariantRef}
                onChange={(e) => setNewVariantRef(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="变体名称">
              <input
                className={control}
                value={newVariantLabel}
                onChange={(e) => setNewVariantLabel(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="关联订单号" hint="可空。填了才能查到履约上下文。">
              <input
                className={control}
                value={newOrderNo}
                onChange={(e) => setNewOrderNo(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="买家标识">
              <input
                className={control}
                value={newBuyerRef}
                onChange={(e) => setNewBuyerRef(e.target.value)}
                disabled={!writable}
              />
            </Field>
          </div>
          <Field label="评价内容">
            <textarea
              className={control}
              rows={3}
              value={newContent}
              onChange={(e) => setNewContent(e.target.value)}
              disabled={!writable}
            />
          </Field>
          <div className="grid gap-3 md:grid-cols-2">
            <Field label="图片地址（每行一个）">
              <textarea
                className={control}
                rows={2}
                value={newImageUrls}
                onChange={(e) => setNewImageUrls(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="视频地址">
              <input
                className={control}
                value={newVideoUrl}
                onChange={(e) => setNewVideoUrl(e.target.value)}
                disabled={!writable}
              />
            </Field>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              onClick={() => void submitReview()}
              size="sm"
              disabled={
                busy ||
                !writable ||
                !newShopId ||
                !newReviewedAt ||
                !newProductRef ||
                !newVariantRef
              }
            >
              <MessageSquare className="mr-1 h-4 w-4" />
              登记评价
            </Button>
            <Button
              onClick={() => void runSync()}
              size="sm"
              variant="secondary"
              disabled={busy || !writable || !newShopId}
            >
              <RefreshCw className="mr-1 h-4 w-4" />
              从平台同步评价（未接入）
            </Button>
          </div>
          <p className="text-muted-foreground text-copy-12">
            三个维度（{dimensionLabel('SHOP')} / {dimensionLabel('PRODUCT')} /{' '}
            {dimensionLabel('VARIANT')}）的下滑定位由后端现算，页面只原样展示。
          </p>
        </div>
      </SectionCard>
    </div>
  );
}

/** 把错误转成能读的一句话。 */
function errorText(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}
