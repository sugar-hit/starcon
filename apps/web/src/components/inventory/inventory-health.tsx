'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, HeartPulse, RefreshCw } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  InventoryHealthGate,
  InventoryHealthMetric,
  InventoryHealthScorecard,
  InventoryHealthTrend,
} from '../../../../../packages/api-contracts/src/index';
import {
  assessHealth,
  availabilityLabels,
  coverageText,
  fetchHealth,
  fetchHealthGate,
  fetchHealthTrend,
  gateHeadline,
  gateTone,
  isPartialScore,
  metricDisplay,
  percent,
  sortIssues,
  trendAccuracyText,
  trendPointLabel,
} from './inventory-health-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.6.2.1 库存健康工作区。
 *
 * <p>蓝图：「库存健康度诊断：周转率、缺货率、超储率、呆滞占比、库存准确率、负库存发生次数，
 * 按仓库与类目打分并给出改进建议」「输出：健康度评分卡 + 趋势 + 问题清单与建议」
 * 「**库存准确率是硬指标**，低于阈值应阻断结算与对账」。
 *
 * <p>界面纪律（这三条决定了这一屏会不会误导人）：
 *
 * <ul>
 *   <li>**六个指标恒定六行，不可用的那四行照样占位**。周转率 / 缺货率 / 超储率 / 呆滞占比
 *       依赖尚未交付的 13 销量与 7.6.1，界面上给它们写 0 等于在拿假指标去调补货。
 *       这里显示「上游未交付（待 13）」并把原因写在旁边。
 *   <li>**得分永远带覆盖度**。6 个指标只有 2 个能算时，那个 96 分不是"这个仓健康"，
 *       而是"已覆盖的两个维度上没问题"。两者在补货决策里完全不是一回事。
 *   <li>**红线判定分三种说法**。低于阈值 / 无证据 / 达标 ——「从没核过账」绝不能显示成
 *       「通过」，账不准时报表全是错的，没证据在结算场景下与不准同样危险。
 * </ul>
 */
export function InventoryHealthWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [warehouseId, setWarehouseId] = useState('');
  const [threshold, setThreshold] = useState('0.99');
  const [cards, setCards] = useState<InventoryHealthScorecard[]>([]);
  const [gate, setGate] = useState<InventoryHealthGate | null>(null);
  const [trend, setTrend] = useState<InventoryHealthTrend | null>(null);
  const [warehouses, setWarehouses] = useState<{ id: string; code: string; name: string }[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [p, g, t] = await Promise.all([
        fetchHealth({ warehouseId: warehouseId || undefined, accuracyThreshold: threshold }),
        fetchHealthGate(warehouseId || undefined, threshold),
        fetchHealthTrend(warehouseId || undefined),
      ]);
      setCards(p.scorecards);
      setGate(g);
      setTrend(t);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [warehouseId, threshold]);

  useEffect(() => {
    if (!readable) {
      setLoading(false);
      return;
    }
    void reload();
  }, [readable, reload]);

  useEffect(() => {
    if (!readable) return;
    fetch('/api/warehouse/warehouses')
      .then((r) => (r.ok ? r.json() : []))
      .then((list: { id: string; code: string; name: string }[]) => setWarehouses(list ?? []))
      .catch(() => setWarehouses([]));
  }, [readable]);

  async function runAssessment() {
    setBusy(true);
    setError('');
    try {
      await assessHealth(warehouseId || undefined, threshold);
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '评估失败');
    } finally {
      setBusy(false);
    }
  }

  if (!readable) {
    return (
      <EmptyState
        title="没有库存健康查看权限"
        description="该页面需要 warehouse.inventory.read 权限。"
      />
    );
  }

  const tone = gate ? gateTone(gate) : 'unverified';

  return (
    <div className="space-y-6">
      <PageHeader
        title="库存健康"
        description="按仓库给出健康度评分卡、准确率趋势与问题清单。得分只覆盖已算得出的指标维度。"
        actions={
          <>
            <Button variant="outline" onClick={() => void reload()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => void runAssessment()} disabled={busy || !writable}>
              <HeartPulse />
              {busy ? '评估中…' : '执行评估'}
            </Button>
          </>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive flex items-start gap-3 rounded-md border p-4 text-sm">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {/* 红线判定：三种状态分开说，没证据不能说成"通过"。 */}
      <div
        className={`flex items-start gap-3 rounded-md border p-4 text-sm ${
          tone === 'passed'
            ? 'border-success/40 bg-success-soft text-success'
            : 'border-destructive/40 bg-destructive-soft text-destructive'
        }`}
      >
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
        <div className="space-y-1">
          <p className="font-medium">{gate ? gateHeadline(gate) : '尚未评估，账目准确率无证据'}</p>
          {gate?.blockReason && <p className="opacity-90">{gate.blockReason}</p>}
          <p className="opacity-80">
            该判定由本模块给出，供 12 财务结算与 7.1.2.2 对账在各自边界内执行阻断；
            本服务不代下游修改单据状态。
          </p>
        </div>
      </div>

      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-3">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">仓库</span>
            <select
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={warehouseId}
              onChange={(e) => setWarehouseId(e.target.value)}
            >
              <option value="">全部</option>
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.code} {w.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">准确率阈值（0–1）</span>
            <input
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={threshold}
              onChange={(e) => setThreshold(e.target.value)}
              placeholder="0.99"
            />
          </label>
        </div>
      </SectionCard>

      {cards.length > 0 && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="当前范围得分"
            value={gate ? `${tone === 'unverified' ? '—' : (cards[0]?.score ?? '—')}` : '—'}
            comparisonLabel={
              cards[0]
                ? `覆盖 ${coverageText(cards[0])}${
                    isPartialScore(cards[0]) ? '（部分维度，勿当整体健康度）' : ''
                  }`
                : '尚未评估'
            }
          />
          <StatCard
            label="准确率"
            value={percent(gate?.accuracyRate)}
            comparisonLabel={`阈值 ${percent(gate?.accuracyThreshold)}`}
          />
          <StatCard
            label="可算指标"
            value={
              cards[0] ? `${cards[0].availableMetricCount} / ${cards[0].totalMetricCount}` : '—'
            }
            comparisonLabel="其余指标依赖尚未交付的上游"
          />
          <StatCard
            label="可结算"
            value={gate && !gate.blocked ? '是' : '否'}
            comparisonLabel={gate && gate.blocked ? '准确率红线已触发' : '未触发阻断'}
          />
        </div>
      )}

      {cards.length === 0 && !loading && (
        <EmptyState
          title="还没有库存健康评估结论"
          description="点右上角「执行评估」跑一次：它会按仓库逐个打分，并给出准确率红线判定。"
        />
      )}

      {cards.map((card) => (
        <SectionCard
          key={card.scopeType + (card.scopeKey ?? 'tenant')}
          title={`${card.scopeLabel} · ${card.score} 分（${card.gradeLabel}）`}
        >
          <p className="text-muted-foreground mb-3 text-sm">
            覆盖 {coverageText(card)} 个指标
            {isPartialScore(card) && ' —— 分数只代表已算得出的维度，其余维度尚未测量'}
          </p>

          {/* 六行恒定渲染：算不出的那几行照样占位，写明原因与阻塞模块。 */}
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left">
                <th className="py-2 pr-3 font-medium">指标</th>
                <th className="py-2 pr-3 font-medium">取值</th>
                <th className="py-2 pr-3 font-medium">口径</th>
                <th className="py-2 pr-3 font-medium">扣分</th>
                <th className="py-2 font-medium">说明</th>
              </tr>
            </thead>
            <tbody>
              {card.metrics.map((metric) => {
                const d = metricDisplay(metric);
                return (
                  <tr key={metric.metricType} className="border-b last:border-0">
                    <td className="py-2 pr-3">
                      <span className="font-medium">{metric.metricLabel}</span>
                      <div className="text-muted-foreground text-xs">
                        {availabilityLabels[metric.availability] ?? metric.availability}
                      </div>
                    </td>
                    <td className="py-2 pr-3">
                      {d.hasValue ? (
                        <span className="font-mono">{d.value}</span>
                      ) : (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </td>
                    <td className="text-muted-foreground py-2 pr-3 text-xs">
                      {d.source || '—'}
                      {d.fraction && <div className="font-mono">{d.fraction}</div>}
                    </td>
                    <td className="py-2 pr-3 font-mono">{metric.deduction}</td>
                    <td className="text-muted-foreground py-2 text-xs">{d.hint || '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </SectionCard>
      ))}

      {trend && trend.points.length > 0 && (
        <SectionCard title="准确率趋势">
          <p className="text-muted-foreground mb-3 text-sm">
            核对口径与盘点口径各自成序列，不混成一条线 —— 盘一次不等于核一次。
          </p>
          <ul className="space-y-1 text-sm">
            {trend.points.map((p) => (
              <li key={`${p.source}-${p.sourceId}`} className="flex items-center gap-3">
                <span className="text-muted-foreground w-52 shrink-0 truncate">
                  {trendPointLabel(p)}
                </span>
                <span className="font-mono">{trendAccuracyText(p)}</span>
                <span className="text-muted-foreground text-xs">
                  样本 {p.sampleCount} · 差异 {p.discrepancyCount}
                </span>
                <span className="text-muted-foreground text-xs">{p.at.slice(0, 10)}</span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}

      {gate && gate.issues.length > 0 && (
        <SectionCard title={`问题清单（${gate.issues.length}）`}>
          <ul className="space-y-3">
            {sortIssues(gate.issues).map((issue, i) => (
              <li key={`${issue.scopeLabel}-${issue.metricType}-${i}`} className="text-sm">
                <p className="font-medium">
                  [
                  {issue.severity === 'CRITICAL'
                    ? '严重'
                    : issue.severity === 'WARN'
                      ? '警告'
                      : '提示'}
                  ]{issue.scopeLabel} · {issue.title}
                </p>
                <p className="text-muted-foreground mt-1 text-xs">{issue.detail}</p>
                <p className="mt-1 text-xs">建议：{issue.recommendation}</p>
                <p className="text-muted-foreground mt-1 text-xs">证据：{issue.evidence}</p>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}
    </div>
  );
}
