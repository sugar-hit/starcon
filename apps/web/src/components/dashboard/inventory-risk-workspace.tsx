'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, StatCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import {
  RISK_TYPES,
  RISK_TYPE_LABELS,
  buildInventoryRiskList,
  describeRiskRow,
  formatQuantity,
  summarizeRiskList,
  thresholdScopeLabel,
  type InventoryRiskRow,
  type RiskProjectionCapabilities,
  type RiskType,
  type StockProjectionPage,
  type StockProjectionRow,
  type VelocityPage,
  type VelocityRow,
} from './inventory-risk-model';

/**
 * 1.1.3.1 库存风险工作区（卷 01 工作台与门户）。
 *
 * <p>蓝图输出：「风险清单（SKU、仓库、可用量、日均销、可用天数、风险类型、建议动作如"生成采购单"）」。
 *
 * <p><b>数据来自三处，跨服务聚合在本页完成</b>：
 *
 * <ul>
 *   <li>{@code GET /warehouse/inventory-risk/projection} —— warehouse 的 (仓 × SKU) 库存事实投影
 *       （可用量 / 在手量 / 预占 / 阈值与档位 / 库龄 / 活跃预占 / 在途）。</li>
 *   <li>{@code GET /trade/stockout-risk/velocities} —— trade 的销售速度登记（日均销）。</li>
 * </ul>
 *
 * <p><b>两种「读不到」必须分开</b>：trade 的销售速度读不到（没有 {@code trade.stockout.read} 权限或
 * trade 服务不可达）时，页面照常给出缺货 / 超卖 / 呆滞 / 超储四类判定，只是「可用天数」一列显示
 * 「不可判定」并说明原因 —— 而不是整页报错或把天数填成 0。
 *
 * <p><b>每个数字都能点开明细</b>：点清单里任意一行，右侧展开该行的口径拆解
 * （可用量怎么来的、阈值出自哪一档、在途怎么分、可用天数怎么除）。
 */
export function InventoryRiskWorkspace() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [ready, setReady] = useState(false);

  const [capabilities, setCapabilities] = useState<RiskProjectionCapabilities | null>(null);

  const [projections, setProjections] = useState<StockProjectionRow[]>([]);
  const [projectionMeta, setProjectionMeta] = useState<StockProjectionPage | null>(null);
  const [velocities, setVelocities] = useState<VelocityRow[]>([]);
  const [velocityError, setVelocityError] = useState('');

  const [warehouseKind, setWarehouseKind] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [keyword, setKeyword] = useState('');
  const [riskFilter, setRiskFilter] = useState<'' | RiskType>('');
  const [selectedKey, setSelectedKey] = useState('');

  const query = useMemo(() => {
    const parts = new URLSearchParams();
    if (warehouseKind) parts.set('warehouseKind', warehouseKind);
    if (warehouseId) parts.set('warehouseId', warehouseId);
    if (keyword.trim()) parts.set('keyword', keyword.trim());
    parts.set('size', '500');
    return `?${parts.toString()}`;
  }, [warehouseKind, warehouseId, keyword]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    setNotice('');
    setVelocityError('');
    try {
      const capabilityView = await api<RiskProjectionCapabilities>(
        'warehouse/inventory-risk/capabilities',
      );
      setCapabilities(capabilityView);

      const projectionPage = await api<StockProjectionPage>(
        `warehouse/inventory-risk/projection${query}`,
      );
      setProjections(projectionPage.rows ?? []);
      setProjectionMeta(projectionPage);

      // 销售速度在 trade 服务：读不到时**不整页失败**，只是可用天数不可判定。
      try {
        const velocityRows: VelocityRow[] = [];
        const pageSize = 200;
        let page = 0;
        let total = 0;
        for (;;) {
          const velocityPage = await api<VelocityPage>(
            `trade/stockout-risk/velocities?effectiveOnly=true&size=${pageSize}&page=${page}`,
          );
          velocityRows.push(...(velocityPage.rows ?? []));
          total = velocityPage.total ?? 0;
          if (velocityRows.length >= total || (velocityPage.rows ?? []).length === 0) break;
          page += 1;
          if (page >= 20) {
            setNotice(
              `销售速度登记超过 ${pageSize * 20} 条，日均销只用了前 ${velocityRows.length} 条 —— 已如实截断。`,
            );
            break;
          }
        }
        setVelocities(velocityRows);
      } catch (velocityFailure) {
        setVelocities([]);
        setVelocityError(
          velocityFailure instanceof ApiError
            ? velocityFailure.message
            : '销售速度暂时读不到',
        );
      }
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : '库存风险清单暂时读不到');
    } finally {
      setLoading(false);
      setReady(true);
    }
  }, [query]);

  useEffect(() => {
    void load();
  }, [load]);

  const asOfDate = projectionMeta?.asOfDate ?? '';
  const riskRows = useMemo(
    () => buildInventoryRiskList(projections, velocities, asOfDate),
    [projections, velocities, asOfDate],
  );
  const summary = useMemo(() => summarizeRiskList(riskRows), [riskRows]);
  const visibleRows = useMemo(
    () => (riskFilter ? riskRows.filter((row) => row.risks.includes(riskFilter)) : riskRows),
    [riskRows, riskFilter],
  );
  const selected = useMemo(
    () => riskRows.find((row) => row.key === selectedKey) ?? null,
    [riskRows, selectedKey],
  );

  if (!ready) {
    return <p className="text-copy-13 text-muted-foreground">正在加载库存风险清单…</p>;
  }

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-copy-16 font-medium">库存风险</h1>
          <p className="text-copy-12 text-muted-foreground">
            集中呈现缺货、超卖风险、呆滞、超储四类库存问题，区分本地仓 / 海外仓 / FBA / 在途。
            本页只读：一切状态变更回到归属系统完成。
          </p>
          {projectionMeta && (
            <p className="text-copy-12 text-muted-foreground">
              评估基准日 {projectionMeta.asOfDate}（数据库时钟）· 计算时刻{' '}
              {projectionMeta.computedAt} · 共 {projectionMeta.total} 个（仓 × SKU）组合
            </p>
          )}
        </div>
        <Button variant="outline" onClick={() => void load()} disabled={loading}>
          <RefreshCw className="mr-1 h-4 w-4" />
          刷新
        </Button>
      </header>

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
      {notice && (
        <p role="status" className="text-copy-12 text-warning">
          {notice}
        </p>
      )}
      {velocityError && (
        <p role="status" className="text-copy-12 text-warning">
          销售速度（trade）暂时读不到：{velocityError}。缺货 / 超卖 / 呆滞 / 超储四类判定不受影响，
          但「日均销」与「可用天数」不可判定。
        </p>
      )}

      {/* ---------------- 能力声明 ---------------- */}
      {capabilities && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">能力声明</h2>
          <ul className="space-y-1">
            {[
              {
                key: 'stock',
                available: capabilities.stockProjectionAvailable,
                source: capabilities.stockProjectionSource,
                note: capabilities.stockProjectionNote,
                owner: '',
                impact: '',
              },
              {
                key: 'threshold',
                available: capabilities.thresholdAvailable,
                source: capabilities.thresholdSource,
                note: capabilities.thresholdNote,
                owner: '',
                impact: '',
              },
              {
                key: 'aging',
                available: capabilities.agingAvailable,
                source: capabilities.agingSource,
                note: capabilities.agingNote,
                owner: '',
                impact: '',
              },
              {
                key: 'reservation',
                available: capabilities.reservationAvailable,
                source: capabilities.reservationSource,
                note: capabilities.reservationNote,
                owner: '',
                impact: '',
              },
              {
                key: 'inTransit',
                available: capabilities.inTransitAvailable,
                source: capabilities.inTransitSource,
                note: capabilities.inTransitNote,
                owner: '',
                impact: '',
              },
              {
                key: 'salesVelocity',
                available: capabilities.salesVelocityAvailable,
                source: capabilities.salesVelocityOwnerModule,
                note: capabilities.salesVelocityImpact,
                owner: capabilities.salesVelocityOwnerModule,
                impact: capabilities.salesVelocityImpact,
              },
              {
                key: 'purchaseArrival',
                available: capabilities.purchaseArrivalAvailable,
                source: capabilities.purchaseArrivalOwnerModule,
                note: capabilities.purchaseArrivalImpact,
                owner: capabilities.purchaseArrivalOwnerModule,
                impact: capabilities.purchaseArrivalImpact,
              },
            ].map((item) => (
              <li
                key={item.key}
                className={`text-copy-12 rounded-md border p-2 ${
                  item.available ? 'text-muted-foreground' : 'border-warning/40 bg-warning/5'
                }`}
              >
                <Badge tone={item.available ? 'info' : 'warning'}>
                  {item.available ? '已接通' : '未接通'}
                </Badge>
                <span className="ml-2">
                  {item.available
                    ? `${item.source}：${item.note}`
                    : `${item.owner}：${item.note}（归属 ${item.owner}）—— ${item.impact}`}
                </span>
              </li>
            ))}
          </ul>
          <p className="text-copy-12 text-muted-foreground">{capabilities.riskRuleNote}</p>
          <p className="text-copy-12 text-muted-foreground">{capabilities.availableDaysNote}</p>
          <p className="text-copy-12 text-muted-foreground">{capabilities.writeScopeNote}</p>
        </section>
      )}

      {/* ---------------- 概览 ---------------- */}
      <section className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="风险行数" value={summary.riskRowCount} unit={`/ ${summary.rowCount} 行`} />
        {RISK_TYPES.map((risk) => (
          <StatCard
            key={risk}
            label={RISK_TYPE_LABELS[risk]}
            value={summary.byRisk[risk]}
            unit="行"
          />
        ))}
      </section>

      {/* ---------------- 筛选 ---------------- */}
      <section className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">仓库类型</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={warehouseKind}
            onChange={(e) => setWarehouseKind(e.target.value)}
          >
            <option value="">全部类型</option>
            {(projectionMeta?.warehouseKindOptions ?? []).map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}（{option.warehouseCount}）
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">仓库</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={warehouseId}
            onChange={(e) => setWarehouseId(e.target.value)}
          >
            <option value="">全部仓库</option>
            {(projectionMeta?.warehouseOptions ?? []).map((option) => (
              <option key={option.id} value={option.id}>
                {option.code} · {option.kindLabel}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">SKU</span>
          <input
            className="border rounded px-2 py-1 font-mono text-copy-12"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="SKU 编码或名称"
          />
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">风险类型</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={riskFilter}
            onChange={(e) => setRiskFilter(e.target.value as '' | RiskType)}
          >
            <option value="">全部风险</option>
            {RISK_TYPES.map((risk) => (
              <option key={risk} value={risk}>
                {RISK_TYPE_LABELS[risk]}（{summary.byRisk[risk]}）
              </option>
            ))}
          </select>
        </label>
      </section>

      {/* ---------------- 风险清单 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">风险清单</h2>
        {visibleRows.length === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title={riskFilter ? `没有命中「${RISK_TYPE_LABELS[riskFilter]}」的行` : '没有库存风险'}
            description={
              riskFilter
                ? '换一个风险类型或清掉筛选再看看。'
                : '本租户当前没有 (仓 × SKU) 的库存事实行 —— 先在库存明细里落库，这里才会有内容。'
            }
          />
        ) : (
          <div className="bg-card overflow-x-auto rounded-lg border">
            <table className="w-full text-left text-copy-12">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="px-3 py-2 font-medium">SKU</th>
                  <th className="px-3 py-2 font-medium">仓库</th>
                  <th className="px-3 py-2 font-medium">风险类型</th>
                  <th className="px-3 py-2 text-right font-medium">可用量</th>
                  <th className="px-3 py-2 text-right font-medium">日均销</th>
                  <th className="px-3 py-2 text-right font-medium">可用天数</th>
                  <th className="px-3 py-2 font-medium">阈值档位</th>
                  <th className="px-3 py-2 font-medium">建议动作</th>
                </tr>
              </thead>
              <tbody>
                {visibleRows.map((row) => (
                  <tr
                    key={row.key}
                    onClick={() => setSelectedKey(row.key === selectedKey ? '' : row.key)}
                    className={`cursor-pointer border-b last:border-b-0 ${
                      row.key === selectedKey ? 'bg-muted/50' : ''
                    }`}
                  >
                    <td className="px-3 py-2">
                      <span className="font-mono">{row.skuCode}</span>
                      <span className="text-muted-foreground ml-1">{row.skuName}</span>
                    </td>
                    <td className="px-3 py-2">
                      {row.warehouseCode}
                      <span className="text-muted-foreground ml-1">{row.warehouseKindLabel}</span>
                    </td>
                    <td className="px-3 py-2">
                      {row.risks.length === 0 ? (
                        <span className="text-muted-foreground">—</span>
                      ) : (
                        <span className="flex flex-wrap gap-1">
                          {row.risks.map((risk) => (
                            <Badge key={risk} tone="warning">
                              {RISK_TYPE_LABELS[risk]}
                            </Badge>
                          ))}
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatQuantity(row.available)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {row.coverageStatus === 'NO_VELOCITY'
                        ? '未登记'
                        : formatQuantity(row.dailySales)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {row.coverageStatus === 'OK' ? `${row.coverageDays ?? '—'} 天` : '不可判定'}
                    </td>
                    <td className="px-3 py-2">
                      {thresholdScopeLabel(row.thresholdScopeType)}
                    </td>
                    <td className="px-3 py-2">{row.suggestedAction}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {projectionMeta && (
          <p className="text-copy-12 text-muted-foreground">{projectionMeta.truncationNote}</p>
        )}
        {summary.noThresholdCount > 0 && (
          <p className="text-copy-12 text-muted-foreground">
            有 {summary.noThresholdCount} 行没有配置任何库存阈值（未配置 ≠ 0），这些行不参与缺货与超储判定。
          </p>
        )}
        {summary.noVelocityCount > 0 && (
          <p className="text-copy-12 text-muted-foreground">
            有 {summary.noVelocityCount} 个 SKU 没有登记销售速度，可用天数不可判定。
          </p>
        )}
      </section>

      {/* ---------------- 明细（点行展开） ---------------- */}
      {selected && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">
            明细 · {selected.skuCode} @ {selected.warehouseCode}
          </h2>
          <div className="bg-card rounded-lg border p-3">
            <ul className="text-copy-12 space-y-1">
              {describeRiskRow(selected).map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          </div>
        </section>
      )}
    </div>
  );
}

export type { InventoryRiskRow };
