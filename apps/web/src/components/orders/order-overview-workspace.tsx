'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { ArrowLeft, CircleSlash, RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  SalesOrderOverview,
  SalesOrderOverviewCapabilities,
  SalesOrderOverviewLineAttribute,
} from '../../../../../packages/api-contracts/src/index';
import {
  UNAVAILABLE_SECTION_ORDER,
  actionDisabled,
  addressRoleLabels,
  allocationBanner,
  chargeKindLabels,
  chargeTypeLabels,
  documentStatusLabels,
  fetchOrderOverview,
  fetchOrderOverviewCapabilities,
  formatAmount,
  formatTime,
  manualKindLabels,
  orderSourceLabels,
  paymentMethodLabels,
  unavailableCell,
  unavailableSectionLabel,
} from './order-overview-model';

/**
 * 9.1.1.2 订单详情（控制塔）。
 *
 * <p>蓝图：「订单全景：买家与地址快照、商品行（SKU、规格、单价、数量、税费）、费用构成与分摊、
 * 支付与退款记录、履约批次与包裹、物流轨迹、售后关联、平台侧原始报文、状态历史。」
 * 以及那句把它定义成控制塔的话：「<b>客服、采购、财务都从这一个页面理解这笔订单为什么长这样</b>。」
 *
 * <p><b>控制塔的第一要求不是「信息全」，而是「每一段要么是真的，要么明说它没有」。</b>
 * 本页每一处灰块都对应后端 capabilities 里的一条 available=false，
 * 点开写明归属模块与原因 ——
 *
 * <ul>
 *   <li><b>七段不可得分区画成灰块，不画成「暂无数据」</b>。后者会被读成「查过了，确实没有」。</li>
 *   <li><b>费用构成与费用分摊是两个区块</b>。构成有数据、分摊没有表；
 *       把构成摆在分摊的位置上，会让「这一行到底买了多少钱」保持沉默的同时看起来像有答案。</li>
 *   <li><b>时间线标题写「单据状态时间线」</b>，不叫「履约时间线」——
 *       该表只记录 CREATED / SUBMITTED，没有支付事件也没有履约事件。</li>
 *   <li><b>操作入口一律禁用</b>并写明前置条件：本模块是读投影，没有任何写端点。</li>
 * </ul>
 */
export function OrderOverviewWorkspace({ orderId }: { orderId: string }) {
  const [detail, setDetail] = useState<SalesOrderOverview | null>(null);
  const [caps, setCaps] = useState<SalesOrderOverviewCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDetail(await fetchOrderOverview(orderId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '订单详情加载失败');
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchOrderOverviewCapabilities());
      } catch {
        // 能力声明拿不到不该挡住详情本身 —— 详情才是这个页面的主内容。
        setCaps(null);
      }
    })();
  }, []);

  if (loading) return <EmptyState title="正在加载订单详情…" description="读取订单全景数据。" />;

  if (error) {
    return (
      <EmptyState
        title="订单详情加载失败"
        description={error}
        action={
          <Button variant="outline" onClick={() => void load()}>
            <RefreshCw className="mr-2 h-4 w-4" />
            重试
          </Button>
        }
      />
    );
  }

  if (!detail) return <EmptyState title="订单不存在" description="该订单不存在或无权查看。" />;

  const h = detail.header;
  const sections = new Map((caps?.unavailableSections ?? []).map((s) => [s.key, s]));
  const actions = caps?.actions ?? [];

  return (
    <div className="space-y-4">
      <PageHeader
        title={`订单 ${h.orderNo}`}
        description={`${orderSourceLabels[h.orderSource] ?? h.orderSource} · ${documentStatusLabels[h.documentStatus] ?? h.documentStatus} · ${h.currency}`}
        actions={
          <div className="flex gap-2">
            <Link href="/orders">
              <Button variant="outline">
                <ArrowLeft className="mr-2 h-4 w-4" />
                返回订单列表
              </Button>
            </Link>
            <Button variant="ghost" onClick={() => void load()}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
          </div>
        }
      />

      {/* ---- 订单头：金额卡 ---- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="订单总额" value={`${h.currency} ${formatAmount(h.grandTotalAmount)}`} />
        <StatCard label="商品小计" value={formatAmount(h.subtotalAmount)} />
        <StatCard label="折扣" value={formatAmount(h.discountAmount)} />
        <StatCard label="税费" value={formatAmount(h.taxAmount)} />
        <StatCard label="运费" value={formatAmount(h.shippingAmount)} />
        <StatCard label="其他费用" value={formatAmount(h.otherAmount)} />
        <StatCard
          label="单据状态"
          value={documentStatusLabels[h.documentStatus] ?? h.documentStatus}
        />
        <StatCard
          label="付款方式"
          value={paymentMethodLabels[h.paymentMethod] ?? h.paymentMethod ?? '—'}
        />
      </div>

      {/* ---- 头信息 ---- */}
      <SectionCard title="订单信息">
        <dl className="grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
          <Field label="内部单号" value={h.orderNo} />
          <Field label="外部订单号" value={h.externalOrderNo || '—'} />
          <Field
            label="手工单子类"
            value={h.manualKind ? (manualKindLabels[h.manualKind] ?? h.manualKind) : '—'}
          />
          <Field
            label="账期天数"
            value={h.paymentTermsDays && h.paymentTermsDays > 0 ? `${h.paymentTermsDays} 天` : '—'}
          />
          <Field label="下单时间" value={formatTime(h.createdAt)} />
          <Field label="提交审单时间" value={formatTime(h.submittedAt)} />
          <Field label="最近更新" value={formatTime(h.updatedAt)} />
          <Field label="备注" value={h.note || '—'} />
        </dl>
      </SectionCard>

      {/* ---- 买家与地址快照（真实数据） ---- */}
      <SectionCard
        title="买家与地址快照"
        description="下单当时冻结的一份快照：买家之后改名或改地址，都不会改写这里。"
      >
        {detail.buyer ? (
          <dl className="grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
            <Field label="客户编码" value={detail.buyer.customerCode} />
            <Field label="客户名称" value={detail.buyer.customerName} />
            <Field
              label="客户类型"
              value={detail.buyer.customerType === 'ORGANIZATION' ? '企业' : '个人'}
            />
            <Field label="客户默认币种" value={detail.buyer.customerDefaultCurrency ?? '—'} />
          </dl>
        ) : (
          <p className="text-muted-foreground text-sm">该订单没有客户快照。</p>
        )}

        <div className="mt-3 grid gap-3 lg:grid-cols-2">
          {detail.addresses.map((addr) => (
            <div key={addr.addressRole} className="rounded-lg border p-3 text-sm">
              <div className="mb-2 font-medium">
                {addressRoleLabels[addr.addressRole] ?? addr.addressRole}
              </div>
              <dl className="grid gap-1">
                <Field label="收件人" value={addr.recipientName} inline />
                <Field label="公司" value={addr.companyName ?? '—'} inline />
                <Field label="电话" value={addr.recipientPhone ?? '—'} inline />
                <Field label="邮箱" value={addr.recipientEmail ?? '—'} inline />
                <Field
                  label="地址"
                  value={[
                    addr.countryCode,
                    addr.province,
                    addr.city,
                    addr.district,
                    addr.addressLine1,
                    addr.addressLine2,
                    addr.postcode,
                  ]
                    .filter(Boolean)
                    .join(' ')}
                  inline
                />
                <Field label="税号" value={addr.taxId ?? '—'} inline />
              </dl>
            </div>
          ))}
        </div>
      </SectionCard>

      {/* ---- 商品行（真实数据） ---- */}
      <SectionCard
        title="商品行"
        description="SKU、规格、单价、数量、税费全部是下单当时的快照列，不回查实时商品。"
      >
        {detail.lines.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            该订单还没有商品行（草稿允许先建头后补行）。
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="p-2">#</th>
                  <th className="p-2">SKU</th>
                  <th className="p-2">规格</th>
                  <th className="p-2 text-right">数量</th>
                  <th className="p-2 text-right">单价</th>
                  <th className="p-2 text-right">折扣</th>
                  <th className="p-2 text-right">行税</th>
                  <th className="p-2 text-right">行小计</th>
                  <th className="p-2">映射版本</th>
                </tr>
              </thead>
              <tbody>
                {/* ⚠️ codegen 生成的数组元素类型带 `| null`（Jackson 把 record 组件
                    都标成 nullable），所以每行都要先滤掉 null —— 直接用会报
                    「possibly null」。这不是模型出错，是契约生成的保守形状。 */}
                {detail.lines
                  .filter((l): l is NonNullable<typeof l> => l !== null)
                  .map((line) => {
                    // codegen 把 Map<String, ...> 渲染成 `{ }`（空的 object 类型），
                    // 所以这里显式断言成「按 lineId 分组的属性数组」，而不是让它去索引。
                    const attrs =
                      (
                        detail.attributesByLineId as unknown as Record<
                          string,
                          SalesOrderOverviewLineAttribute[]
                        >
                      )[line.id] ?? [];
                    return (
                      <tr key={line.id} className="border-b last:border-0">
                        <td className="p-2">{line.lineNo}</td>
                        <td className="p-2">
                          <div>{line.skuCode}</div>
                          <div className="text-muted-foreground text-xs">{line.skuName}</div>
                          {attrs.length > 0 && (
                            <ul className="text-muted-foreground mt-1 text-xs">
                              {attrs.map((at) => (
                                <li key={at.id}>
                                  {at.attributeName}：{at.attributeValue}
                                  {at.attributeUnit ? ` ${at.attributeUnit}` : ''}
                                </li>
                              ))}
                            </ul>
                          )}
                        </td>
                        <td className="p-2">{line.specSnapshot ?? '—'}</td>
                        <td className="p-2 text-right">{formatAmount(line.quantity)}</td>
                        <td className="p-2 text-right">{formatAmount(line.unitPrice)}</td>
                        <td className="p-2 text-right">{formatAmount(line.discountAmount)}</td>
                        <td className="p-2 text-right">{formatAmount(line.taxAmount)}</td>
                        <td className="p-2 text-right font-medium">
                          {formatAmount(line.lineAmount)}
                        </td>
                        <td className="p-2">{line.skuMappingVersion ?? '—'}</td>
                      </tr>
                    );
                  })}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---- 费用构成（真实） ---- */}
      <SectionCard
        title="费用构成"
        description="一笔费用一行，回答「这笔单一共收了哪些钱」。这**不是**费用分摊。"
      >
        {detail.charges.length === 0 ? (
          <p className="text-muted-foreground text-sm">该订单还没有费用行。</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="p-2">类型</th>
                  <th className="p-2">说明</th>
                  <th className="p-2">层级</th>
                  <th className="p-2 text-right">金额</th>
                </tr>
              </thead>
              <tbody>
                {/* ⚠️ 同上：codegen 的数组元素带 `| null`，渲染前先滤掉。 */}
                {detail.charges
                  .filter((c): c is NonNullable<typeof c> => c !== null)
                  .map((c) => (
                    <tr key={c.id} className="border-b last:border-0">
                      <td className="p-2">{chargeTypeLabels[c.chargeType] ?? c.chargeType}</td>
                      <td className="p-2">{c.label ?? '—'}</td>
                      <td className="p-2">{chargeKindLabels[c.chargeKind] ?? c.chargeKind}</td>
                      <td className="p-2 text-right">{formatAmount(c.amount)}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---- 状态时间线（真实，但只是单据维度） ---- */}
      <SectionCard
        title="单据状态时间线"
        description="照 sales_order_status_history 真实返回。该表只记录 CREATED / SUBMITTED，**没有支付事件也没有履约事件** —— 所以这是单据时间线，不是履约时间线。"
      >
        {detail.timeline.length === 0 ? (
          <p className="text-muted-foreground text-sm">该订单还没有状态事件。</p>
        ) : (
          <ol className="space-y-2">
            {detail.timeline.map((e) => (
              <li key={e.id} className="flex items-start gap-3 text-sm">
                <span className="bg-muted mt-1.5 h-2 w-2 shrink-0 rounded-full" />
                <div>
                  <div className="font-medium">
                    {e.fromStatus} → {e.toStatus}
                    <Badge className="ml-2">{e.eventType}</Badge>
                  </div>
                  <div className="text-muted-foreground text-xs">
                    {formatTime(e.occurredAt)}
                    {e.reason ? ` · ${e.reason}` : ''}
                  </div>
                </div>
              </li>
            ))}
          </ol>
        )}
      </SectionCard>

      {/* ---- 尚未接入的分区：灰块 + 归属模块 ---- */}
      <SectionCard
        title="尚未接入的分区"
        description="这些是蓝图画了、但本服务还没有数据源的段落。它们画成灰块并写明归属 —— 而不是画成「暂无数据」，因为后者会被读成「查过了，确实没有」。"
      >
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {UNAVAILABLE_SECTION_ORDER.map((key) => {
            const section = sections.get(key);
            const cell = unavailableCell(
              (detail as unknown as Record<string, unknown>)[key],
              section,
            );
            return (
              <div key={key} className="rounded-lg border border-dashed p-3">
                <div className="flex items-center gap-2 text-sm">
                  <CircleSlash className="text-muted-foreground h-4 w-4" />
                  <span className="font-medium">{unavailableSectionLabel(key)}</span>
                  <span className="text-muted-foreground ml-auto text-xs" title={cell?.title}>
                    {cell?.text ?? '—'}
                  </span>
                </div>
                <p className="text-muted-foreground mt-1 text-xs">
                  归属：{section?.ownerModule ?? '待定'}
                </p>
              </div>
            );
          })}
        </div>

        {/* 费用分摊单独一条横幅：构成有、分摊没有，两者在界面上必须分开讲。 */}
        {(() => {
          const banner = allocationBanner(
            detail.chargeAllocations,
            sections.get('chargeAllocations'),
          );
          if (!banner) return null;
          return (
            <div className="border-primary/40 bg-primary/5 mt-3 flex items-start gap-2 rounded-lg border p-3 text-sm">
              <TriangleAlert className="text-primary mt-0.5 h-4 w-4 shrink-0" />
              <div>
                <div className="font-medium">{banner.title}</div>
                <p className="text-muted-foreground mt-1 text-xs">{banner.body}</p>
                <p className="text-muted-foreground mt-1 text-xs">
                  归属：{sections.get('chargeAllocations')?.ownerModule ?? '待定'}
                </p>
              </div>
            </div>
          );
        })()}
      </SectionCard>

      {/* ---- 操作入口：全部禁用并说明归属 ---- */}
      <SectionCard
        title="操作入口"
        description="本模块是 sales_order 簇的读投影，没有任何写端点 —— 按钮一律禁用并写明前置条件。"
      >
        {actions.length === 0 ? (
          <p className="text-muted-foreground text-sm">正在读取能力声明…</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {actions.map((action) => {
              const hint = actionDisabled(action.code, action);
              return (
                <Button
                  key={action.code}
                  variant="outline"
                  disabled={hint.disabled}
                  title={hint.title}
                >
                  {action.label}
                </Button>
              );
            })}
          </div>
        )}
      </SectionCard>
    </div>
  );
}

function Field({
  label,
  value,
  inline,
}: {
  label: string;
  value: string | number | null | undefined;
  inline?: boolean;
}) {
  const text = value === null || value === undefined || value === '' ? '—' : String(value);
  if (inline) {
    return (
      <div className="flex gap-2">
        <dt className="text-muted-foreground w-20 shrink-0">{label}</dt>
        <dd>{text}</dd>
      </div>
    );
  }
  return (
    <div>
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="mt-0.5">{text}</dd>
    </div>
  );
}
