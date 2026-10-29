'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, SectionCard, Segmented } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import {
  DIMENSION_AD_SPEND,
  DIMENSION_KEYS,
  DIMENSION_ORDER_COUNT,
  DIMENSION_PROFIT_MARGIN,
  DIMENSION_SALES,
  DIMENSION_INVENTORY_DAYS,
  WINDOW_OPTIONS,
  caliberTooltip,
  capabilityOf,
  classificationLabel,
  classificationTone,
  dimensionLines,
  dimensionOf,
  emptyState,
  fetchCapabilities,
  fetchOverview,
  fetchShops,
  formatAmount,
  formatCount,
  isNotConnected,
  notConnectedDimensions,
  platformOptions,
  salesLines,
  statusLabel,
  statusTone,
  windowLabel,
  type StoreOverviewCapabilities,
  type StoreOverviewDimension,
  type StoreOverviewResponse,
  type StoreOverviewRow,
  type StoreOverviewShop,
  type WindowOption,
} from './store-overview-model';

/**
 * 1.1.2.2 店铺概览工作区（卷 01 工作台与门户 L3 1.1.2 关键指标下的第二格）。
 *
 * <p>蓝图原文：「按店铺横向对比当期表现（**销售额、订单量、利润率、库存天数、广告花费**），
 * 识别掉队店铺。……输出：店铺对比表 / 卡片；点击进入该店铺在 13 的分析页。」
 *
 * <p>🔴 **这一页的五条纪律**（每一条都写在页面上，不只写在注释里）：
 *
 * <ol>
 *   <li><b>「销售额」是三个口径的并列</b>（平台侧 / 我方还原 / 口径差）。
 *       表格里那一格**恒定三行**，少一行就等于把口径差读成了经营差。</li>
 *   <li><b>接不通的维度写明归属模块号</b>（利润率 / 库存天数）——
 *       让运营知道「去哪维护」，而不是看到一个 0 或一个编出来的数。</li>
 *   <li><b>「算不出来」与「等于 0」分开</b>：没有事实 ⇒ 「—」+ 原因；不是 0。</li>
 *   <li><b>多币种绝不相加</b>：每个币种各占一行，界面上**永远没有跨币种的「总计」</b>。</li>
 *   <li><b>只读 + 跳转</b>：本页没有任何写动作；出口是「该店铺在 13 的分析页」与
 *       「该店铺的订单页」。</li>
 * </ol>
 *
 * <p>⚠️ **租户不来自任何客户端输入**：租户来自已认证的成员关系，
 * 可见店铺集合由服务端按 RLS 过滤；本页不下发租户号、不下发范围参数。
 */
export function StoreOverviewWorkspace() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [ready, setReady] = useState(false);

  const [data, setData] = useState<StoreOverviewResponse | null>(null);
  const [capabilities, setCapabilities] = useState<StoreOverviewCapabilities | null>(null);
  const [shops, setShops] = useState<StoreOverviewShop[]>([]);

  const [window, setWindow] = useState<WindowOption>('TODAY');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [platformCode, setPlatformCode] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      // 三个端点并行读：能力声明读不到不该让表格也读不到。
      const [caps, overview, visible] = await Promise.all([
        fetchCapabilities().catch(() => null),
        fetchOverview(window, from || undefined, to || undefined, platformCode || undefined),
        fetchShops(window, from || undefined, to || undefined),
      ]);
      setCapabilities(caps);
      setData(overview);
      setShops(visible);
    } catch (failure) {
      setError(
        failure instanceof ApiError
          ? failure.message
          : '店铺概览暂时读不到，请稍后重试（后端不可达或身份校验未通过）。',
      );
    } finally {
      setLoading(false);
      setReady(true);
    }
  }, [window, from, to, platformCode]);

  useEffect(() => {
    void load();
  }, [load]);

  const platforms = useMemo(() => platformOptions(shops), [shops]);
  const missing = useMemo(() => notConnectedDimensions(capabilities), [capabilities]);

  if (!ready) {
    return (
      <SectionCard>
        <EmptyState
          title="正在读取店铺概览…"
          description="店铺维度是投影读模型，可能要先跑一次聚合。"
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-copy-16 font-medium">店铺概览</h1>
          <p className="text-copy-12 text-muted-foreground">
            按店铺横向对比当期表现（销售额、订单量、利润率、库存天数、广告花费），识别掉队店铺。
            <b>口径由 13 单点定义</b>，本页只引用不重算；本页只读，不写任何业务数据。
          </p>
          {data && (
            <p className="text-copy-12 text-muted-foreground">
              窗口 {data.from} ~ {data.to}（{windowLabel(data.windowPreset)}） · 时区{' '}
              {data.timezone}
              {data.timezoneSource === 'FALLBACK_UTC' ? '（本租户还没有投影数据，暂按 UTC）' : ''} ·
              数据截至{' '}
              {data.dataUpdatedAt
                ? data.dataUpdatedAt.replace('T', ' ').slice(0, 19)
                : '尚未投影任何数据'}
            </p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Segmented
            value={window}
            onChange={(value) => setWindow(value as WindowOption)}
            options={WINDOW_OPTIONS.map((option) => ({ label: option.label, value: option.value }))}
            aria-label="时间口径"
          />
          <select
            value={platformCode}
            onChange={(event) => setPlatformCode(event.target.value)}
            aria-label="平台筛选"
            className="border-border text-copy-13 rounded-md border px-2 py-1"
          >
            <option value="">全部平台</option>
            {platforms.map((platform) => (
              <option key={platform.platformCode} value={platform.platformCode}>
                {platform.platformName || platform.platformCode}
              </option>
            ))}
          </select>
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        </div>
      </header>

      {window === 'CUSTOM' && (
        <SectionCard title="自定义区间" description="起止日期按租户时区算（含首尾）。">
          <div className="flex flex-wrap items-end gap-3">
            <label className="text-copy-13 flex flex-col gap-1">
              起
              <input
                type="date"
                value={from}
                onChange={(event) => setFrom(event.target.value)}
                className="border-border rounded-md border px-2 py-1"
              />
            </label>
            <label className="text-copy-13 flex flex-col gap-1">
              止
              <input
                type="date"
                value={to}
                onChange={(event) => setTo(event.target.value)}
                className="border-border rounded-md border px-2 py-1"
              />
            </label>
            <Button
              variant="outline"
              onClick={() => void load()}
              disabled={loading || !from || !to}
            >
              应用区间
            </Button>
          </div>
        </SectionCard>
      )}

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}

      {/* ── 店铺对比表 ───────────────────────────────────────────────────── */}
      <SectionCard
        title="店铺对比"
        description="每一格都写明口径；「销售额」一格是平台侧 / 我方还原 / 口径差三个口径的并列，不是一个数。"
      >
        {loading ? (
          <EmptyState title="正在读取店铺概览…" description="投影读模型可能要跑一次聚合。" />
        ) : error && !data ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title={emptyState(false, null).title}
            description={emptyState(false, null).description}
          />
        ) : !data || data.rows.length === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title={emptyState(true, data).title}
            description={emptyState(true, data).description}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[64rem] text-left text-sm">
              <thead className="text-copy-13 text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3 font-medium">店铺</th>
                  <th className="py-2 pr-3 font-medium">销售额（三个口径并列）</th>
                  <th className="py-2 pr-3 font-medium">订单量</th>
                  <th className="py-2 pr-3 font-medium">利润率</th>
                  <th className="py-2 pr-3 font-medium">库存天数</th>
                  <th className="py-2 pr-3 font-medium">广告花费</th>
                  <th className="py-2 pr-3 font-medium">判定</th>
                  <th className="py-2 font-medium">去哪看</th>
                </tr>
              </thead>
              <tbody>
                {data.rows.map((row) => (
                  <StoreOverviewRowView key={row.shopCode} row={row} />
                ))}
              </tbody>
            </table>
          </div>
        )}

        {data && data.warnings.length > 0 && (
          <ul className="text-copy-13 text-muted-foreground mt-4 space-y-1">
            {data.warnings.map((warning) => (
              <li key={warning}>· {warning}</li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ── 掉队店铺 ─────────────────────────────────────────────────────── */}
      {data && data.laggards.length > 0 && (
        <SectionCard
          title="掉队店铺"
          description="判定规则与 13.1.1.2 多店对比同源（平台侧成交金额环比 ≤ −20%），本页只引用不重算。"
        >
          <ul className="space-y-2">
            {data.laggards.map((laggard) => (
              <li
                key={laggard.shopCode}
                className="text-copy-13 border-border rounded-md border p-2"
              >
                <Badge tone="warning">{classificationLabel('LAGGARD')}</Badge>
                <span className="ml-2">
                  {laggard.shopName}（{laggard.shopCode}）
                  {laggard.platformCode ? ` · ${laggard.platformCode}` : ''}
                </span>
                <span className="text-muted-foreground mt-1 block">—— {laggard.reason}</span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}

      {/* ── 五个维度逐条 ─────────────────────────────────────────────────── */}
      {capabilities && (
        <SectionCard
          title="五个维度逐条"
          description="蓝图 1.1.2.2 点名的五个维度，逐条报出指标码 / 接不接得通 / 口径归谁。接不通的不给公式也不给分母。"
        >
          <ul className="space-y-2">
            {DIMENSION_KEYS.map((key) => {
              const capability = capabilityOf(capabilities, key);
              if (!capability) return null;
              return (
                <li key={key} className="text-copy-12 border-border rounded-md border p-2">
                  <Badge tone={capability.available ? 'success' : 'warning'}>
                    {capability.available ? '已接通' : '未接通'}
                  </Badge>
                  <span className="ml-2">
                    {capability.displayName} · 口径归属：{capability.ownerModule || '—'}
                  </span>
                  {capability.calibers.length > 0 && (
                    <span className="text-muted-foreground mt-1 block">
                      指标码：{capability.calibers.map((caliber) => caliber.metricCode).join(' · ')}
                      {capability.calibers
                        .filter((caliber) => !caliber.registered)
                        .map((caliber) => `（${caliber.metricCode} 未在本租户口径表登记）`)
                        .join(' ')}
                    </span>
                  )}
                  {capability.caliber && (
                    <span className="text-muted-foreground mt-1 block">{capability.caliber}</span>
                  )}
                  {!capability.available && capability.unavailableReason && (
                    <span className="mt-1 block">—— {capability.unavailableReason}</span>
                  )}
                </li>
              );
            })}
          </ul>

          {missing.length > 0 && (
            <div className="mt-4">
              <h3 className="text-copy-14 font-medium">未接通清单（逐条）</h3>
              <ul className="text-copy-12 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
                {capabilities.notDelivered.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          )}

          <div className="mt-4">
            <h3 className="text-copy-14 font-medium">判定规则</h3>
            <ul className="text-copy-12 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
              {capabilities.rules.map((rule) => (
                <li key={rule.ruleCode}>
                  <b>{rule.ruleCode}</b>：{rule.description}
                </li>
              ))}
            </ul>
          </div>

          <div className="mt-4">
            <h3 className="text-copy-14 font-medium">上游（真的读过的）</h3>
            <ul className="text-copy-12 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
              {capabilities.upstreamModules.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>

          <div className="mt-4">
            <h3 className="text-copy-14 font-medium">口径纪律</h3>
            <ul className="text-copy-12 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
              {capabilities.notes.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>
        </SectionCard>
      )}

      {/* ── 跨平台口径差异说明 ───────────────────────────────────────────── */}
      {data && data.caliberNotes.length > 0 && (
        <SectionCard
          title="口径差异说明"
          description="跨平台比较时这些差异会直接改变排序 —— 读表前先读这一段。"
        >
          <ul className="text-copy-13 text-muted-foreground list-disc space-y-1 pl-5">
            {data.caliberNotes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </SectionCard>
      )}
    </div>
  );
}

/** 店铺对比表里的一行 = 一个店铺。 */
function StoreOverviewRowView({ row }: { row: StoreOverviewRow }) {
  const sales = dimensionOf(row, DIMENSION_SALES);
  return (
    <tr className="border-border border-t align-top">
      <td className="py-3 pr-3">
        <div className="text-foreground">{row.shopName || row.shopCode}</div>
        <div className="text-copy-13 text-muted-foreground">{row.shopCode}</div>
        <div className="text-copy-13 text-muted-foreground">
          {row.platformName || row.platformCode || '尚未确认归属'}
        </div>
        {row.currencies.length > 0 && (
          <div className="text-copy-13 text-muted-foreground">
            币种 {row.currencies.join(' / ')}
          </div>
        )}
        {!row.hasFactsInWindow && (
          <div className="text-copy-13 text-muted-foreground mt-1">这个窗口没有店铺维度事实</div>
        )}
      </td>

      {/* 🔴 销售额：三个口径并列，不是一个数。 */}
      <td className="py-3 pr-3">
        <SalesCell dimension={sales} />
      </td>

      <td className="py-3 pr-3">
        <DimensionCell dimension={dimensionOf(row, DIMENSION_ORDER_COUNT)} />
      </td>

      <td className="py-3 pr-3">
        <DimensionCell dimension={dimensionOf(row, DIMENSION_PROFIT_MARGIN)} />
      </td>

      <td className="py-3 pr-3">
        <DimensionCell dimension={dimensionOf(row, DIMENSION_INVENTORY_DAYS)} />
      </td>

      <td className="py-3 pr-3">
        <DimensionCell dimension={dimensionOf(row, DIMENSION_AD_SPEND)} />
      </td>

      <td className="py-3 pr-3">
        <Badge tone={classificationTone(row.classification)}>
          {classificationLabel(row.classification)}
        </Badge>
        {row.classificationNote && (
          <div className="text-copy-13 text-muted-foreground mt-1 max-w-[16rem]">
            {row.classificationNote}
          </div>
        )}
      </td>

      <td className="py-3">
        <div className="flex flex-col gap-1">
          {row.analysisPath && (
            <Link
              href={row.analysisPath}
              className="text-copy-13 text-primary underline-offset-2 hover:underline"
            >
              到 {row.analysisTargetName || '13 店铺分析'}
            </Link>
          )}
          {row.ordersPath && (
            <Link
              href={row.ordersPath}
              className="text-copy-13 text-primary underline-offset-2 hover:underline"
            >
              到该店铺订单
            </Link>
          )}
          {!row.analysisPath && !row.ordersPath && <span className="text-copy-13">—</span>}
        </div>
      </td>
    </tr>
  );
}

/**
 * 销售额那一格 —— **恒定三行**（平台侧 / 我方还原 / 口径差）。
 *
 * <p>🔴 这里刻意不用通用的 {@link DimensionCell}：销售额是**三个口径的并列**，
 * 通用格会把它们平铺成三行没有标签的值，看起来就像三个无关的指标。
 */
function SalesCell({ dimension }: { dimension: StoreOverviewDimension | undefined }) {
  if (isNotConnected(dimension)) {
    return <NotConnectedCell dimension={dimension} />;
  }
  const lines = salesLines(dimension);
  if (lines.length === 0) {
    return (
      <span className="text-copy-13 text-muted-foreground">
        {dimension?.statusNote || '这个窗口没有事实数据（不是 0）'}
      </span>
    );
  }
  const currencies = [...new Set(lines.map((line) => line.currency))];
  return (
    <div className="space-y-1">
      {currencies.map((currency) => (
        <div key={currency}>
          <div className="text-copy-13 text-muted-foreground">{currency}</div>
          <ul className="space-y-0.5">
            {lines
              .filter((line) => line.currency === currency)
              .map((line) => (
                <li key={line.metricCode} className="text-copy-13" title={line.note || undefined}>
                  <span className="text-muted-foreground">{line.label}</span>{' '}
                  <span className="tabular-nums">{line.text}</span>
                </li>
              ))}
          </ul>
        </div>
      ))}
    </div>
  );
}

/** 一个维度的那一格（订单量 / 广告花费 / 未接通的维度）。 */
function DimensionCell({ dimension }: { dimension: StoreOverviewDimension | undefined }) {
  if (isNotConnected(dimension)) {
    return <NotConnectedCell dimension={dimension} />;
  }
  const lines = dimensionLines(dimension);
  if (lines.length === 0) {
    return (
      <span className="text-copy-13 text-muted-foreground">
        {dimension?.statusNote || '这个窗口没有事实数据（不是 0）'}
      </span>
    );
  }
  return (
    <ul className="space-y-0.5">
      {lines.map((line) => (
        <li
          key={`${line.currency}|${line.label}`}
          className="text-copy-13"
          title={line.note || undefined}
        >
          <span className="text-muted-foreground">{line.currency}</span>{' '}
          <span className="tabular-nums">{line.text}</span>
        </li>
      ))}
    </ul>
  );
}

/**
 * 未接通的那一格 —— **必须写明归属模块号**。
 *
 * <p>🔴 不写归属，运营会以为这是系统坏了，而不是「这个口径还没接通、去哪维护」。
 */
function NotConnectedCell({ dimension }: { dimension: StoreOverviewDimension | undefined }) {
  return (
    <div className="text-copy-13 text-muted-foreground space-y-1" title={caliberTooltip(dimension)}>
      <div>
        <Badge tone="warning">未接通</Badge>
      </div>
      {dimension?.ownerModule && <div>口径归属：{dimension.ownerModule}</div>}
      {dimension?.statusNote && <div className="max-w-[18rem]">{dimension.statusNote}</div>}
    </div>
  );
}
