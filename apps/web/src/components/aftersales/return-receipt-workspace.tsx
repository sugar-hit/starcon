'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  ReturnReceiptCapabilities,
  ReturnReceiptDetail,
  ReturnReceiptPage,
  ReturnReceivablePage,
} from '../../../../../packages/api-contracts/src/index';
import {
  appendReceiptPhoto,
  bothDimensionsJudged,
  cancelReceipt,
  createReceipt,
  fetchReceiptCapabilities,
  fetchReceiptDetail,
  fetchReceiptList,
  fetchReceivable,
  isPostedToPendingInspection,
  postBlockedReason,
  postReceipt,
  PENDING_INSPECTION_LABEL,
  receiptSellableDelta,
  sortEvents,
  unavailableActions,
} from './return-receipt-model';

/**
 * 11.2.2.1 退货入库。
 *
 * <p>蓝图：「登记退回实物：扫码核对 SKU 与序列号、清点数量、拍照留证、
 * 判定外包装与商品状态。」
 * 输出：「退货实收记录 + 关联授权行分配；<b>待检库存入库分录（不是可售）</b>。」
 * 加粗句：「<b>退回商品默认进待检状态</b>，这是硬规则——直接进可售会把残次品当新品卖出去。」
 *
 * <p><b>页面围绕三条硬规则组织</b>：
 *
 * <ol>
 *   <li><b>「可售 0」是页面上的一格，不是一句注释</b>：每个已入库的包裹都显示
 *       「进待检 N 件 · <b>可售 0</b>」。哪天这个数字不是 0，说明有人绕过接口改了库存 ——
 *       那是事故不是特性，页面必须让它一眼可见。</li>
 *   <li><b>两个判定维度分两组控件</b>：外包装与商品状态是两个下拉框而不是一个。
 *       「外箱损毁但里面的货完好」是蓝图 11.2.3.1 点名的场景，合成一个值就表达不出来。</li>
 *   <li><b>扫码前先看「还能收几件」</b>：剩余可收量按授权行并排展示
 *       （授权量 / 已被占用 / 还能收），超额在录入前就看得见 ——
 *       而不是录完被后端拒了才知道。</li>
 * </ol>
 *
 * <p>⚠️ 页面**刻意没有**「直接转可售」「质检判定」「分配到订单行」三个按钮：
 * 它们属 11.2.3.2 / 11.2.3.1 / 11.2.2.2，均**未交付**。
 * 渲染一个注定被拒的按钮等于告诉运营系统坏了，而真相是这条路径本就不该存在。
 */
export function ReturnReceiptWorkspace() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [trackingNo, setTrackingNo] = useState('');
  const [data, setData] = useState<ReturnReceiptPage | null>(null);
  const [caps, setCaps] = useState<ReturnReceiptCapabilities | null>(null);
  const [detail, setDetail] = useState<ReturnReceiptDetail | null>(null);
  const [receivable, setReceivable] = useState<ReturnReceivablePage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  // 建单的输入：真实表单，不是硬编码常量。
  const [newRmaId, setNewRmaId] = useState('');
  // ⚠️ 授权行 ID **必填且不可省略**：蓝图输出句要求「关联授权行分配」，
  //   而额度就挂在授权行上 —— 没有它就没有可收量，实收也答不出「这批货退的是哪几件」。
  const [newRmaLineId, setNewRmaLineId] = useState('');
  const [newChannel, setNewChannel] = useState('CARRIER');
  const [newCarrier, setNewCarrier] = useState('');
  const [newTrackingNo, setNewTrackingNo] = useState('');
  const [newSku, setNewSku] = useState('');
  const [newSerial, setNewSerial] = useState('');
  const [newBatch, setNewBatch] = useState('');
  const [newQty, setNewQty] = useState('1');
  // ⚠️ 两个维度**分开**的下拉框（硬规则 2）。
  const [newPackaging, setNewPackaging] = useState('NOT_INSPECTED');
  const [newGoods, setNewGoods] = useState('NOT_INSPECTED');
  const [newReceivedAt, setNewReceivedAt] = useState(() => new Date().toISOString().slice(0, 16));

  // 追加照片（拍照留证可在草稿期持续补）。
  const [photoUri, setPhotoUri] = useState('');
  const [photoSha, setPhotoSha] = useState('');
  const [photoCaption, setPhotoCaption] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // ⚠️ exactOptionalPropertyTypes：筛选条件只能「要么有要么没有」，
      //   不能把 undefined 当成一个已给的键塞进去。
      const filters: Parameters<typeof fetchReceiptList>[0] = { page, size: 20 };
      if (status) filters.status = status;
      if (keyword) filters.keyword = keyword;
      if (trackingNo) filters.trackingNo = trackingNo;
      setData(await fetchReceiptList(filters));
      setCaps(await fetchReceiptCapabilities());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [page, status, keyword, trackingNo]);

  useEffect(() => {
    void load();
  }, [load]);

  const open = useCallback(async (id: string) => {
    setError(null);
    try {
      const d = await fetchReceiptDetail(id);
      setDetail(d);
      // ⚠️ 剩余可收量与详情**一起**拉：扫码那一格必须和授权行额度同屏，
      //   分两次请求会让操作员在两个数字之间对不上。
      try {
        setReceivable(await fetchReceivable(d.head.rmaId));
      } catch {
        setReceivable(null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const run = useCallback(
    async (work: () => Promise<ReturnReceiptDetail>) => {
      setBusy(true);
      setError(null);
      try {
        const d = await work();
        setDetail(d);
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const submitNew = () =>
    run(async () => {
      const created = await createReceipt({
        rmaId: newRmaId,
        instructionId: null,
        receiptChannel: newChannel,
        carrierCode: newCarrier || null,
        returnTrackingNo: newTrackingNo || null,
        packageNote: '',
        receivedAt: new Date(newReceivedAt).toISOString(),
        lines: [
          {
            rmaLineId: newRmaLineId,
            scannedSkuCode: newSku || null,
            scannedSerialNo: newSerial || null,
            batchNo: newBatch || null,
            receivedQty: Number(newQty),
            packagingCondition: newPackaging,
            goodsCondition: newGoods,
            countNote: null,
          },
        ],
        photos: [],
      });
      setNewRmaId('');
      setNewRmaLineId('');
      setNewCarrier('');
      setNewTrackingNo('');
      setNewSku('');
      setNewSerial('');
      setNewBatch('');
      return created;
    });

  const blocked = detail ? postBlockedReason(detail) : '先选一张实收单';

  return (
    <div className="space-y-6">
      <PageHeader
        title="退货入库"
        description="登记退回实物：扫码核对 SKU 与序列号、清点数量、拍照留证、判定外包装与商品状态。退回商品默认进待检，不是可售。"
      />

      {error ? (
        <SectionCard title="出错了">
          <p className="text-sm text-red-600">{error}</p>
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1 h-4 w-4" />
            重试
          </Button>
        </SectionCard>
      ) : null}

      {/* ---- 第一屏：待检在途量 + 草稿，不是「一共多少条」 ---- */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="待检库存（退回商品）"
          value={data ? String(data.summary.pendingInspectionQty ?? 0) : '—'}
          unit="件 · 默认进待检（硬规则）"
        />
        <StatCard
          // ⚠️ 硬规则 1 的正对照格：这一格**永远**是 0。
          // 非 0 说明有别的服务绕过了本模块改了库存 —— 事故，不是特性。
          label="其中可售增量"
          value={data ? String(data.summary.sellableQtyDelta ?? 0) : '—'}
          unit="恒为 0：退回商品不进可售"
        />
        <StatCard label="已入库批次" value={data ? String(data.summary.posted ?? 0) : '—'} />
        <StatCard label="待清点草稿" value={data ? String(data.summary.draft ?? 0) : '—'} />
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        {/* ---- 列表 ---- */}
        <SectionCard title="实收单">
          <div className="flex flex-wrap gap-2">
            <Input
              placeholder="单号 / 授权单 / 订单号"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
            <Input
              placeholder="回寄运单号"
              value={trackingNo}
              onChange={(e) => setTrackingNo(e.target.value)}
            />
            <select
              className="rounded border px-2 py-1 text-sm"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">全部状态</option>
              <option value="DRAFT">草稿</option>
              <option value="POSTED">已入库</option>
              <option value="CANCELLED">已撤销</option>
            </select>
            <Button onClick={() => void load()} disabled={loading}>
              <RefreshCw className="h-4 w-4" />
            </Button>
          </div>

          {loading ? (
            <p className="text-muted-foreground py-6 text-center text-sm">加载中…</p>
          ) : !data || data.rows.length === 0 ? (
            <EmptyState
              title="还没有退货实收记录"
              description="退回实物入库前，先由 11.2.1.1 批出退货授权单，再在这里登记收到的包裹。"
            />
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="py-2">实收单</th>
                  <th className="py-2">状态</th>
                  <th className="py-2">件数</th>
                  <th className="py-2">入库</th>
                </tr>
              </thead>
              <tbody>
                {data.rows.map((r) => (
                  <tr
                    key={r.id}
                    className="hover:bg-muted/50 cursor-pointer border-b"
                    onClick={() => void open(r.id)}
                  >
                    <td className="py-2">
                      <div>{r.receiptNo}</div>
                      <div className="text-muted-foreground text-xs">
                        {r.rmaNo} · {r.receiptChannelLabel}
                      </div>
                    </td>
                    <td className="py-2">
                      <Badge tone={r.receiptStatus === 'POSTED' ? 'success' : 'neutral'}>
                        {r.receiptStatusLabel}
                      </Badge>
                    </td>
                    <td className="py-2">{String(r.receivedQty ?? 0)}</td>
                    <td className="py-2">
                      {r.receiptStatus === 'POSTED' ? (
                        // ⚠️ 硬规则 1 在列表里就摆出来：进待检 N · 可售 0
                        <span className="text-xs">
                          待检 <b>{String(r.pendingInspectionQty ?? 0)}</b>
                          <span className="text-muted-foreground mx-1">·</span>
                          可售 <b className="text-emerald-600">0</b>
                        </span>
                      ) : (
                        <span className="text-muted-foreground text-xs">未入库</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {data && data.total > data.size ? (
            <div className="mt-3 flex gap-2">
              <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                上一页
              </Button>
              <span className="text-muted-foreground self-center text-sm">
                第 {data.page + 1} 页 / 共 {data.total} 条
              </span>
              <Button
                disabled={(page + 1) * data.size >= data.total}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          ) : null}
        </SectionCard>

        <div className="space-y-4">
          {/* ---- 建单：两个判定维度分开 ---- */}
          <SectionCard title="登记退回实物">
            <div className="grid gap-2 md:grid-cols-2">
              <Input
                placeholder="退货授权单 ID（必填）"
                value={newRmaId}
                onChange={(e) => setNewRmaId(e.target.value)}
              />
              <Input
                placeholder="授权行 ID（必填，额度挂在它上面）"
                value={newRmaLineId}
                onChange={(e) => setNewRmaLineId(e.target.value)}
              />
              <select
                className="rounded border px-2 py-1 text-sm"
                value={newChannel}
                onChange={(e) => setNewChannel(e.target.value)}
              >
                <option value="CARRIER">承运商送达</option>
                <option value="BUYER_DROPOFF">买家自行送抵</option>
                <option value="WALK_IN">门店直接收货</option>
              </select>
              <Input
                placeholder="承运商（CARRIER 必填）"
                value={newCarrier}
                onChange={(e) => setNewCarrier(e.target.value)}
              />
              <Input
                placeholder="回寄运单号（CARRIER 必填）"
                value={newTrackingNo}
                onChange={(e) => setNewTrackingNo(e.target.value)}
              />
              <Input
                placeholder="扫到的 SKU 编码"
                value={newSku}
                onChange={(e) => setNewSku(e.target.value)}
              />
              <Input
                placeholder="序列号 / 批次（至少一个）"
                value={newSerial}
                onChange={(e) => setNewSerial(e.target.value)}
              />
              <Input
                placeholder="批次号"
                value={newBatch}
                onChange={(e) => setNewBatch(e.target.value)}
              />
              <Input
                placeholder="清点数量"
                value={newQty}
                onChange={(e) => setNewQty(e.target.value)}
              />
              {/* ⚠️ 维度① 外包装 */}
              <select
                className="rounded border px-2 py-1 text-sm"
                value={newPackaging}
                onChange={(e) => setNewPackaging(e.target.value)}
              >
                <option value="NOT_INSPECTED">外包装：未判定</option>
                <option value="INTACT">外包装：完好</option>
                <option value="DAMAGED">外包装：破损</option>
                <option value="DESTROYED">外包装：损毁</option>
                <option value="MISSING">外包装：无</option>
              </select>
              {/* ⚠️ 维度② 商品状态（独立于外包装） */}
              <select
                className="rounded border px-2 py-1 text-sm"
                value={newGoods}
                onChange={(e) => setNewGoods(e.target.value)}
              >
                <option value="NOT_INSPECTED">商品状态：未判定</option>
                <option value="UNOPENED">未拆封</option>
                <option value="OPENED_UNDAMAGED">已拆封、无损坏</option>
                <option value="USED">有使用痕迹</option>
                <option value="DAMAGED">商品损坏</option>
                <option value="DEFECTIVE">商品有缺陷</option>
                <option value="MISSING_PARTS">配件缺失</option>
                <option value="NOT_APPLICABLE">不适用（散装 / 无此维度）</option>
              </select>
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              入库前两个维度都必须判定（选「不适用」也算已判定）。外箱损毁但里面的货完好是常见且合法的组合
              —— 这正是两个维度分开的原因。
            </p>
            <Button
              className="mt-3"
              disabled={busy || !newRmaId || !newRmaLineId}
              onClick={() => void submitNew()}
            >
              登记实收草稿
            </Button>
          </SectionCard>

          {/* ---- 详情 ---- */}
          <SectionCard title={detail ? `实收详情 ${detail.head.receiptNo}` : '实收详情'}>
            {!detail ? (
              <EmptyState
                title="还没有选中实收单"
                description="点左侧任意一行查看明细、拍照留证与入库分录。"
              />
            ) : (
              <div className="space-y-4">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={detail.head.receiptStatus === 'POSTED' ? 'success' : 'neutral'}>
                    {detail.head.receiptStatusLabel}
                  </Badge>
                  <span className="text-muted-foreground text-xs">
                    {detail.head.receiptChannelLabel}
                    {detail.head.returnTrackingNo ? ` · ${detail.head.returnTrackingNo}` : ''}
                  </span>
                </div>

                {/* ---- 剩余可收量：硬规则 3 的可见面 ---- */}
                {receivable ? (
                  <div className="rounded border p-3 text-sm">
                    <div className="mb-2 font-medium">剩余可收量（按授权行）</div>
                    <table className="w-full text-xs">
                      <thead>
                        <tr className="border-b text-left">
                          <th className="py-1">SKU</th>
                          <th className="py-1">授权量</th>
                          <th className="py-1">已被占用</th>
                          <th className="py-1">还能收</th>
                        </tr>
                      </thead>
                      <tbody>
                        {receivable.rows.map((r) => (
                          <tr key={r.rmaLineId} className="border-b">
                            <td className="py-1">{r.skuCode}</td>
                            <td className="py-1">{String(r.authorizedQty ?? 0)}</td>
                            <td className="py-1">{String(r.takenQty ?? 0)}</td>
                            <td className="py-1">
                              {r.overAuthorized ? (
                                // ⚠️ 负数**显式**显示成「超出授权 N 件」，
                                //   不悄悄 clamp 到 0 —— clamp 会让超收看起来像刚好收满。
                                <span className="font-medium text-red-600">
                                  超出授权 {String(r.authorizedQty ?? 0)} −{' '}
                                  {String(r.takenQty ?? 0)}
                                </span>
                              ) : (
                                String(r.remainingQty ?? 0)
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : null}

                {/* ---- 明细：两个判定维度分列显示 ---- */}
                <div>
                  <div className="mb-2 font-medium">逐件明细</div>
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b text-left">
                        <th className="py-1">SKU / 序列号</th>
                        <th className="py-1">数量</th>
                        <th className="py-1">外包装</th>
                        <th className="py-1">商品状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.lines.map((l) => (
                        <tr key={l.id} className="border-b">
                          <td className="py-1">
                            {l.skuCode}
                            <div className="text-muted-foreground">
                              {l.scannedSerialNo || l.batchNo}
                            </div>
                          </td>
                          <td className="py-1">{String(l.receivedQty ?? 0)}</td>
                          {/* ⚠️ 两列各带自己的 label —— 合并成「状态」一列就答不出说的是哪个 */}
                          <td className="py-1">{l.packagingConditionLabel}</td>
                          <td className="py-1">{l.goodsConditionLabel}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                {/* ---- 入库分录：硬规则 1 的落点 ---- */}
                <div className="rounded border p-3 text-sm">
                  <div className="mb-1 font-medium">待检入库分录</div>
                  {detail.postings.length === 0 ? (
                    <p className="text-muted-foreground text-xs">
                      尚未入库。建单只是登记草稿，**入库之后**才生成待检分录。
                    </p>
                  ) : (
                    <ul className="space-y-1 text-xs">
                      {detail.postings.map((p) => (
                        <li key={p.id} className="flex flex-wrap items-center gap-2">
                          <span>{p.skuCode}</span>
                          <Badge tone="warning">
                            {p.inventoryState === 'PENDING_INSPECTION'
                              ? PENDING_INSPECTION_LABEL
                              : PENDING_INSPECTION_LABEL}
                          </Badge>
                          <span>待检 {String(p.pendingInspectionQty ?? 0)}</span>
                          {/* ⚠️ 硬规则 1 的显式正对照格 */}
                          <span className="text-emerald-600">可售 0</span>
                        </li>
                      ))}
                    </ul>
                  )}
                  <p className="text-muted-foreground mt-2 text-xs">{detail.inventoryStateNote}</p>
                  <p className="text-muted-foreground mt-1 text-xs">
                    质检与处置属 11.2.3.1 / 11.2.3.2，**未交付**： 当前状态恒为「
                    {detail.inspectionStatusLabel}」，本模块没有「转可售」入口。
                  </p>
                </div>

                {/* ---- 拍照留证 ---- */}
                <div className="rounded border p-3 text-sm">
                  <div className="mb-2 font-medium">拍照留证（{detail.photos.length}）</div>
                  {detail.photos.length === 0 ? (
                    <p className="text-muted-foreground text-xs">
                      还没有照片。退货纠纷的举证材料就是这几张 —— 入库后不能事后补改。
                    </p>
                  ) : (
                    <ul className="space-y-1 text-xs">
                      {detail.photos.map((p) => (
                        <li key={p.id} className="break-all">
                          <b>{p.photoRoleLabel}</b> · {p.objectUri}
                          <div className="text-muted-foreground">
                            sha256 {p.contentSha256.slice(0, 16)}… · 拍摄 {p.capturedAt}
                          </div>
                        </li>
                      ))}
                    </ul>
                  )}
                  {detail.head.receiptStatus === 'DRAFT' ? (
                    <div className="mt-2 flex flex-wrap gap-2">
                      <Input
                        className="flex-1"
                        placeholder="照片 object_uri"
                        value={photoUri}
                        onChange={(e) => setPhotoUri(e.target.value)}
                      />
                      <Input
                        className="flex-1"
                        placeholder="sha256（64 位十六进制）"
                        value={photoSha}
                        onChange={(e) => setPhotoSha(e.target.value)}
                      />
                      <Input
                        className="flex-1"
                        placeholder="说明"
                        value={photoCaption}
                        onChange={(e) => setPhotoCaption(e.target.value)}
                      />
                      <Button
                        disabled={busy || !photoUri || photoSha.length !== 64}
                        onClick={() =>
                          void run(() =>
                            appendReceiptPhoto(detail.head.id, {
                              photoSource: 'TENANT_MANUAL_UPLOAD',
                              sourceReference: null,
                              photoRole: 'PACKAGING',
                              objectUri: photoUri,
                              contentSha256: photoSha,
                              contentBytes: 1024,
                              contentType: 'image/jpeg',
                              capturedAt: new Date().toISOString(),
                              caption: photoCaption || null,
                            }),
                          ).then(() => {
                            setPhotoUri('');
                            setPhotoSha('');
                            setPhotoCaption('');
                          })
                        }
                      >
                        追加照片
                      </Button>
                    </div>
                  ) : null}
                </div>

                {/* ---- 留痕 ---- */}
                <div>
                  <div className="mb-2 font-medium">收货留痕</div>
                  <ul className="space-y-1 text-xs">
                    {sortEvents(detail.events).map((e) => (
                      <li key={e.id}>
                        <b>{e.eventTypeLabel}</b> · {e.occurredAt} · {e.reasonNote}
                      </li>
                    ))}
                  </ul>
                </div>

                {/* ---- 写入口 ---- */}
                <div className="flex flex-wrap gap-2">
                  <Button
                    disabled={busy || !!blocked}
                    onClick={() =>
                      void run(() =>
                        postReceipt(detail.head.id, {
                          note: null,
                          expectedVersion: detail.head.version,
                        }),
                      )
                    }
                  >
                    入库（进待检）
                  </Button>
                  {detail.head.receiptStatus === 'DRAFT' ? (
                    <Button
                      disabled={busy}
                      onClick={() =>
                        void run(() =>
                          cancelReceipt(detail.head.id, {
                            cancelReason: '运营在收货台录错，重新登记',
                            expectedVersion: detail.head.version,
                          }),
                        )
                      }
                    >
                      撤销
                    </Button>
                  ) : null}
                </div>
                {blocked && detail.head.receiptStatus === 'DRAFT' ? (
                  <p className="text-muted-foreground text-xs">{blocked}</p>
                ) : null}

                {/* ---- 刻意没有的动作 ---- */}
                <div className="rounded border border-dashed p-3 text-xs">
                  <div className="mb-1 font-medium">刻意不提供的能力</div>
                  <ul className="space-y-1">
                    {unavailableActions(detail.actions).map((a) => (
                      <li key={a.code}>
                        <b>{a.label}</b>
                        <span className="text-muted-foreground mx-1">→</span>
                        <span className="text-muted-foreground">
                          归属 {a.ownerModule}：{a.unavailableReason}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            )}
          </SectionCard>
        </div>
      </div>

      {caps ? (
        <SectionCard title="能力边界">
          <div className="flex gap-2 text-xs">
            <TriangleAlert className="h-4 w-4 shrink-0 text-amber-600" />
            <ul className="space-y-1">
              {caps.boundaries.map((b) => (
                <li key={b.boundary}>
                  <b>{b.boundary}</b>
                  <span className="text-muted-foreground mx-1">→</span>
                  <span className="text-muted-foreground">
                    归属 {b.ownerModule}：{b.reason}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </SectionCard>
      ) : null}
    </div>
  );
}
