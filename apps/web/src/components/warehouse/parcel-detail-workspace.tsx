'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Package, RefreshCw } from 'lucide-react';
import { Button, EmptyState, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  ParcelDetail,
  ParcelDetailCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionHint,
  allocationVerdict,
  dimensionCell,
  matchBasisLabel,
  orderLineLabel,
  parcelCapabilities,
  parcelDetail,
  quantityCell,
  unavailableText,
  unmatchedRows,
  volumeCell,
  weightCell,
} from './parcel-detail-model';

/**
 * 10.2.2.1 包裹详情工作区。
 *
 * <p>蓝图 10 卷：「<b>单包裹视图：包裹内 SKU 与数量（parcel_item）、订单行对应关系、重量体积、
 * 面单、轨迹、交运信息。</b>」输出：「包裹详情 + 操作入口。」
 *
 * <p>四条硬要求在界面上是**看得见**的（页面不自己解释，全部取自后端）：
 *
 * <ul>
 *   <li><b>没量 ≠ 0。</b>未称重/未量尺寸显示「未量」，不显示 0 g / 0 cbm。</li>
 *   <li><b>订单行对应关系是主角。</b>每条明细项下面直接列它关联到的销售订单行与分摊件数；
 *       没关联上时整块标成「只能归到订单级」并逐条列名 —— 蓝图点名这就是失败模式。</li>
 *   <li><b>轨迹与交运画成灰块。</b>本服务没有这两段数据，写明归谁未接入，
 *       不用空白冒充「这一票没有轨迹」。</li>
 *   <li><b>体积重不试算。</b>除数归 10 物流，界面只展示实测三边与实测体积。</li>
 * </ul>
 *
 * <p>本工作区<b>只读</b>：没有写操作按钮 —— 包裹是 8.3.3.2 的凭据，
 * 动作一律走 actions 段指向的归属模块。
 */
export function ParcelDetailWorkspace() {
  const [caps, setCaps] = useState<ParcelDetailCapabilities | null>(null);
  const [detail, setDetail] = useState<ParcelDetail | null>(null);
  const [parcelId, setParcelId] = useState('');
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);

  const loadCaps = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setCaps(await parcelCapabilities());
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载能力声明失败');
    } finally {
      setLoading(false);
    }
  }, []);

  const loadDetail = useCallback(async (id: string) => {
    if (!id.trim()) return;
    setDetailLoading(true);
    setDetailError(null);
    try {
      setDetail(await parcelDetail(id.trim()));
    } catch (e) {
      setDetailError(e instanceof Error ? e.message : '加载包裹详情失败');
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadCaps();
  }, [loadCaps]);

  const verdict = useMemo(
    () => (detail ? allocationVerdict(detail.costAllocation) : null),
    [detail],
  );

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="包裹详情"
        description="单包裹视图：SKU 与数量、订单行对应关系、重量体积、面单。轨迹与交运信息归各自模块，未接入。"
        actions={
          <Button variant="outline" onClick={() => void loadCaps()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="能力声明加载失败">
          {error}
        </Notice>
      )}

      {caps && (
        <SectionCard title="能力与边界" description="哪些段是真数据，哪些段归别的模块。">
          <ul className="text-copy-13 space-y-2">
            {caps.sections.map((s) => (
              <li key={s.label} className="flex flex-wrap items-start gap-2">
                <span
                  className={
                    s.available
                      ? 'text-success text-copy-12 mt-0.5 shrink-0'
                      : 'text-muted-foreground text-copy-12 mt-0.5 shrink-0'
                  }
                >
                  {s.available ? '● 已接入' : '○ 未接入'}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="text-foreground">{s.label}</span>
                  {s.ownerModule && (
                    <span className="text-muted-foreground"> · 归 {s.ownerModule}</span>
                  )}
                  <span className="text-muted-foreground text-copy-12 block">{s.reason}</span>
                </span>
              </li>
            ))}
          </ul>
          <p className="text-muted-foreground border-border text-copy-12 mt-4 border-t pt-3">
            {caps.note}
          </p>
        </SectionCard>
      )}

      <SectionCard title="按包裹 ID 查看" description="蓝图输入是 parcel_id。">
        <div className="flex flex-wrap items-end gap-2">
          <div className="min-w-[280px] flex-1">
            <label className="text-muted-foreground text-copy-12 mb-1 block" htmlFor="parcel-id">
              包裹 ID
            </label>
            <Input
              id="parcel-id"
              value={parcelId}
              placeholder="粘贴 parcel_id"
              onChange={(e) => setParcelId(e.target.value)}
            />
          </div>
          <Button
            onClick={() => void loadDetail(parcelId)}
            disabled={detailLoading || !parcelId.trim()}
          >
            查看详情
          </Button>
        </div>
        {detailError && (
          <Notice tone="danger" title="详情加载失败">
            {detailError}
          </Notice>
        )}
      </SectionCard>

      {detailLoading && (
        <SectionCard>
          <p className="text-muted-foreground text-copy-13">加载中…</p>
        </SectionCard>
      )}

      {!detailLoading && !detail && !detailError && (
        <EmptyState
          icon={<Package className="h-6 w-6" />}
          title="还没有选择包裹"
          description="输入 parcel_id 查看单包裹视图。也可以从 8.3.3.2 的打包作业单进入。"
        />
      )}

      {detail && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label="包裹件数"
              value={quantityCell(detail.header.totalQuantity)}
              unit="件"
            />
            <StatCard label="明细行数" value={detail.header.itemCount} />
            <StatCard
              label="实测毛重"
              value={weightCell(detail.header.measuredGrossWeightG, '').trim()}
            />
            <StatCard label="实测体积" value={volumeCell(detail.header.volumeCbm)} />
          </div>

          <SectionCard title="包裹头" description={detail.header.parcelCode}>
            <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-3">
              <Field label="打包作业单">
                {detail.header.packingOrderCode}（{detail.header.packingOrderStatus}）
              </Field>
              <Field label="所属出库单">
                {detail.header.outboundOrderCode} · {detail.header.outboundSourceLabel}
              </Field>
              <Field label="发货仓">{detail.header.warehouseName ?? '—'}</Field>
              <Field label="包材">
                {detail.header.materialName ?? '—'}
                {detail.header.fillerMaterialName
                  ? ` · 填充物 ${detail.header.fillerMaterialName}`
                  : ''}
              </Field>
              <Field label="实测尺寸">
                {dimensionCell(
                  detail.header.outerLengthCm,
                  detail.header.outerWidthCm,
                  detail.header.outerHeightCm,
                )}
              </Field>
              <Field label="重量口径">
                毛重 {weightCell(detail.header.measuredGrossWeightG, 'g')} · 自重{' '}
                {weightCell(detail.header.tareWeightG, 'g')} · 净重{' '}
                {weightCell(detail.header.netContentWeightG, 'g')}
              </Field>
            </dl>
            <p className="text-muted-foreground border-border text-copy-12 mt-4 border-t pt-3">
              {detail.header.volumeDivisorNote}
            </p>
          </SectionCard>

          <SectionCard
            title="运费成本归集"
            description="蓝图：parcel_item 与订单行的对应关系是成本归集到行的基础，缺了就只能算到订单级。"
          >
            {verdict && (
              <Notice
                tone={verdict.tone === 'ok' ? 'success' : 'warning'}
                title={verdict.level === 'LINE' ? '可归集到销售订单行' : '只能归到订单级'}
              >
                {verdict.label}
                {detail.costAllocation.fallbackReason && (
                  <span className="text-copy-12 block">{detail.costAllocation.fallbackReason}</span>
                )}
              </Notice>
            )}
            <dl className="mt-4 grid gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-4">
              <Field label="合计件数">{quantityCell(detail.costAllocation.totalQuantity)} 件</Field>
              <Field label="已关联件数">
                {quantityCell(detail.costAllocation.matchedQuantity)} 件
              </Field>
              <Field label="未关联件数">
                {quantityCell(detail.costAllocation.unmatchedQuantity)} 件
              </Field>
              <Field label="涉及订单行">
                {detail.costAllocation.distinctSalesOrderLineCount} 行 /{' '}
                {detail.costAllocation.distinctSalesOrderCount} 单
              </Field>
            </dl>
            {unmatchedRows(detail.costAllocation).length > 0 && (
              <div className="mt-4">
                <p className="text-muted-foreground text-copy-12 mb-2">未关联的明细项：</p>
                <ul className="text-copy-13 space-y-1">
                  {unmatchedRows(detail.costAllocation).map((row) => (
                    <li key={row}>· {row}</li>
                  ))}
                </ul>
              </div>
            )}
            <p className="text-muted-foreground border-border text-copy-12 mt-4 border-t pt-3">
              {detail.costAllocation.note}
            </p>
          </SectionCard>

          <SectionCard title="包裹内明细项" description="哪些 SKU、多少件、对应哪条订单行。">
            <div className="space-y-3">
              {detail.items.map((item) => (
                <div key={item.parcelItemId} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <span className="text-foreground text-copy-13">
                      #{item.itemNo} {item.skuCode} · {item.skuName}
                    </span>
                    <span className="text-copy-13">{quantityCell(item.quantity)} 件</span>
                  </div>
                  <p className="text-muted-foreground text-copy-12 mt-1">
                    出库单行 #{item.outboundLineNo}
                    {item.serialNumber && ` · 序列号 ${item.serialNumber}`}
                  </p>
                  {item.salesOrderLines.length > 0 ? (
                    <ul className="mt-2 space-y-1">
                      {item.salesOrderLines.map((link) => (
                        <li
                          key={link.linkId}
                          className="text-copy-12 flex flex-wrap items-center gap-2"
                        >
                          <span className="text-foreground">{orderLineLabel(link)}</span>
                          <span className="text-muted-foreground">{matchBasisLabel(link)}</span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="text-warning text-copy-12 mt-2">{item.orderLineNote}</p>
                  )}
                </div>
              ))}
              {detail.items.length === 0 && (
                <EmptyState
                  icon={<Package className="h-6 w-6" />}
                  title="这个包裹还没有明细项"
                  description="蓝图要求「包裹必须含明细项」—— 还没有装货的箱子不构成一份可对账的凭据。"
                />
              )}
            </div>
          </SectionCard>

          <SectionCard
            title="面单"
            description="读 8.3.4.2 已交付的打印记录，本模块不自建渲染引擎。"
          >
            {detail.label.available ? (
              <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-3">
                <Field label="运单号">{detail.label.waybillNo ?? '—'}</Field>
                <Field label="承运渠道">{detail.label.carrierCode ?? '—'}</Field>
                <Field label="打印任务">
                  {detail.label.jobNo}（{detail.label.jobStatusLabel ?? '—'}）
                </Field>
                <Field label="本包裹打印次数">{detail.label.printCount}</Field>
                <Field label="目标累计打印次数">{detail.label.targetPrintCount ?? '—'} 次</Field>
                <Field label="寄件">{detail.label.shipToName ?? '—'}</Field>
              </dl>
            ) : (
              <Notice tone="info" title="还没有记过面单打印">
                {detail.label.note}
              </Notice>
            )}
            <p className="text-muted-foreground border-border text-copy-12 mt-4 border-t pt-3">
              {detail.label.note}
            </p>
          </SectionCard>

          <SectionCard title="操作入口" description="本模块只读，动作都在归属模块里做。">
            <ul className="space-y-2">
              {detail.actions.map((a) => (
                <li key={a.action} className="text-copy-13 flex flex-wrap items-baseline gap-2">
                  <span className="text-foreground">{a.label}</span>
                  <span className="text-muted-foreground">{actionHint(a)}</span>
                  {a.href && (
                    <a
                      className="text-primary text-copy-12 underline"
                      href={a.href}
                      target="_blank"
                      rel="noreferrer"
                    >
                      前往
                    </a>
                  )}
                  <span className="text-muted-foreground text-copy-12 block w-full">{a.note}</span>
                </li>
              ))}
            </ul>
          </SectionCard>

          <SectionCard title="未接入的段落" description="空白会被读成「没有数据」，所以逐段说明。">
            <ul className="space-y-2">
              {detail.unavailable.map((u) => (
                <li key={u.label} className="text-copy-13">
                  <span className="text-foreground">{unavailableText(u)}</span>
                  <span className="text-muted-foreground text-copy-12 block">{u.reason}</span>
                </li>
              ))}
            </ul>
          </SectionCard>

          <SectionCard title="口径说明">
            <ul className="text-muted-foreground text-copy-12 space-y-1">
              {detail.notes.map((n) => (
                <li key={n}>· {n}</li>
              ))}
            </ul>
          </SectionCard>
        </>
      )}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-muted-foreground text-copy-12">{label}</dt>
      <dd className="text-foreground text-copy-13 truncate">{children}</dd>
    </div>
  );
}
