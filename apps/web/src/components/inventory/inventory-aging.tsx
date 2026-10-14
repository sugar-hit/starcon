'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, Clock, RefreshCw, Save } from 'lucide-react';
import {
  Button,
  EmptyState,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  AgingBucketReport,
  AgingPolicyPage,
  SlowMovingPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  agingBasisOptions,
  agingBasisText,
  agingBucketLabel,
  agingDaysText,
  agingSortOptions,
  capitalTieText,
  dispositionLabel,
  dispositionNoteText,
  fetchAgingBuckets,
  fetchAgingPolicies,
  fetchSlowMoving,
  saveAgingPolicy,
  slowMovingStatusLabel,
  slowMovingStatusOptions,
  thresholdSourceText,
} from './inventory-aging-model';
import { errorText, fetchSkus, qty } from './inventory-model';
import { useIdentity } from '../iam/identity-shell';

/** 兜底阈值：与后端 inventory_aging_policy 的默认值同源，空输入时用它而不是发 0。 */
const DEFAULT_SLOW_MOVING_DAYS = 60;
const DEFAULT_OBSOLETE_DAYS = 90;

/**
 * 7.6.1.1 库龄与呆滞工作区。
 *
 * <p>蓝图：「按入库时间计算库龄分布，识别滞销、临期、残次、呆滞库存，给出处置建议
 * （降价促销、清仓、退仓、报废）」，输出「库龄分桶报表 + 呆滞清单 + 占用资金估算」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>四个分桶固定四行，哪怕某一桶是 0 也要显示</b>。少一行的分布图会让人以为
 *       「这个区间不存在」，而它只是当前没有货。
 *   <li><b>「90+」的下界是 91，界面上写明</b>。60~90 天的货归「61-90」桶，
 *       不写清楚会有人以为 90 天的货漏出了统计。
 *   <li><b>占用资金显示原因，不显示 0 元</b>。仓库服务没有成本列，
 *       显示 0 会被读成「这些呆滞货不占资金」——那比报错危险得多。
 *   <li><b>处置建议只是依据，没有处置按钮</b>。调价属 5.4.1、退仓报废属 6.4.2.2，
 *       都未交付；放一个「一键处置」会让人以为点了就会改价。
 *   <li><b>阈值来源常驻显示</b>。滞销线逐 SKU 可配，界面上必须能看出这一行用的是
 *       配的值还是默认兜底 60 天。
 * </ul>
 */
export function InventoryAgingWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [report, setReport] = useState<AgingBucketReport | null>(null);
  const [list, setList] = useState<SlowMovingPage | null>(null);
  const [policies, setPolicies] = useState<AgingPolicyPage | null>(null);
  const [skus, setSkus] = useState<{ id: string; code: string; name: string }[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [keyword, setKeyword] = useState('');
  const [skuId, setSkuId] = useState('');
  const [basis, setBasis] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [sort, setSort] = useState('AGING_DAYS');
  const [pageNumber, setPageNumber] = useState(0);

  const [editing, setEditing] = useState<string | null>(null);
  const [slowDays, setSlowDays] = useState('');
  const [obsoleteDays, setObsoleteDays] = useState('');
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    if (!readable) return;
    setLoading(true);
    setError('');
    try {
      // exactOptionalPropertyTypes 下不能显式传 undefined，必须条件展开。
      const scope = {
        ...(skuId ? { skuId } : {}),
        ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
      };
      const [r, l, p] = await Promise.all([
        fetchAgingBuckets({ ...scope, ...(basis ? { agingBasis: basis } : {}) }),
        fetchSlowMoving({
          ...scope,
          ...(statusFilter ? { slowMovingStatus: [statusFilter] } : {}),
          sort,
          page: pageNumber,
          size: 20,
        }),
        fetchAgingPolicies({ size: 100 }),
      ]);
      setReport(r);
      setList(l);
      setPolicies(p);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [readable, skuId, keyword, basis, statusFilter, sort, pageNumber]);

  useEffect(() => {
    if (!readable) return;
    void load();
  }, [load, readable]);

  useEffect(() => {
    if (!readable) return;
    void fetchSkus()
      .then(setSkus)
      .catch(() => undefined);
  }, [readable]);

  async function save() {
    if (!editing) return;
    setSaving(true);
    try {
      await saveAgingPolicy({
        skuId: editing,
        slowMovingDays: slowDays.trim() ? Number(slowDays) : DEFAULT_SLOW_MOVING_DAYS,
        obsoleteDays: obsoleteDays.trim() ? Number(obsoleteDays) : DEFAULT_OBSOLETE_DAYS,
        enabled: true,
        notes: null,
      });
      setEditing(null);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }

  if (!readable) {
    return (
      <PageHeader
        title="库龄与呆滞"
        description="按入库时间计算库龄分布，识别滞销、临期、残次、呆滞库存。"
      />
    );
  }

  const capitalNote = report
    ? capitalTieText(report.capitalTie.available, report.capitalTie.reason)
    : '';
  const totalPages = list ? Math.max(1, Math.ceil(list.total / (list.size || 20))) : 1;

  return (
    <div className="space-y-6">
      <PageHeader
        title="库龄与呆滞"
        description="按入库时间计算库龄分布，识别滞销、临期、残次、呆滞库存，并给出处置建议依据。"
        actions={
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      />

      {error ? (
        <div className="border-destructive/40 bg-destructive/10 text-destructive rounded-md border p-3 text-sm">
          {error}
        </div>
      ) : null}

      <SectionCard title="筛选">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <input
            className="border-input bg-background rounded-md border px-3 py-2 text-sm"
            placeholder="SKU 编码 / 名称 / 批次号"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPageNumber(0);
            }}
          />
          <select
            className="border-input bg-background rounded-md border px-3 py-2 text-sm"
            value={skuId}
            onChange={(e) => {
              setSkuId(e.target.value);
              setPageNumber(0);
            }}
          >
            <option value="">全部 SKU</option>
            {skus.map((s) => (
              <option key={s.id} value={s.id}>
                {s.code} {s.name}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background rounded-md border px-3 py-2 text-sm"
            value={basis}
            onChange={(e) => {
              setBasis(e.target.value);
              setPageNumber(0);
            }}
          >
            {agingBasisOptions.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background rounded-md border px-3 py-2 text-sm"
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value);
              setPageNumber(0);
            }}
          >
            {slowMovingStatusOptions.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background rounded-md border px-3 py-2 text-sm"
            value={sort}
            onChange={(e) => setSort(e.target.value)}
          >
            {agingSortOptions.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
      </SectionCard>

      {report ? (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label="在手总量"
              value={qty(report.totals.onHand)}
              comparisonLabel={`覆盖 ${report.totals.bucketCount} 个库位 × 批次行`}
            />
            <StatCard
              label="可用总量"
              value={qty(report.totals.available)}
              comparisonLabel={`已预占 ${qty(report.totals.reserved)}`}
            />
            <StatCard
              label="呆滞可用量"
              value={qty(list?.summary.obsoleteAvailable ?? 0)}
              comparisonLabel={`${list?.summary.obsoleteCount ?? 0} 行命中呆滞线`}
            />
            <StatCard
              label="呆滞且临期/过期"
              value={qty(list?.summary.obsoleteAndUpAvailable ?? 0)}
              comparisonLabel="既占资金又马上要报损，优先处理"
            />
          </div>

          <SectionCard
            title="库龄分桶"
            description="分桶边界互不重叠：0-30（含 30 当天）、31-60、61-90、91 天及以上。「90+」的下界是 91，因此 90 天的货归「61-90」桶；这样四个桶相加恒等于在手总量。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="py-2">库龄桶</th>
                    <th className="py-2 text-right">在库行数</th>
                    <th className="py-2 text-right">在手量</th>
                    <th className="py-2 text-right">可用量</th>
                    <th className="py-2 text-right">其中按真实收货日</th>
                  </tr>
                </thead>
                <tbody>
                  {report.buckets.map((b) => (
                    <tr key={b.bucket} className="border-b last:border-0">
                      <td className="py-2 font-medium">{agingBucketLabel(b.bucket, b.label)}</td>
                      <td className="py-2 text-right">{b.bucketCount}</td>
                      <td className="py-2 text-right">{qty(b.onHand)}</td>
                      <td className="py-2 text-right">{qty(b.available)}</td>
                      <td className="py-2 text-right">
                        {b.lotBasedBucketCount} / {b.bucketCount}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-muted-foreground mt-3 text-xs">
              报表基准日 {report.asOf}；默认滞销线 {report.defaultSlowMovingDays} 天、呆滞线{' '}
              {report.defaultObsoleteDays} 天。
              {report.distinctSlowMovingDaysCount > 1
                ? ` 当前租户实际用到 ${report.distinctSlowMovingDaysCount} 种不同的滞销线，跨 SKU 的库龄口径不完全一致属正常。`
                : ''}
            </p>
            <p className="text-muted-foreground mt-1 text-xs">{report.agingBasisNote}</p>
            <p className="mt-1 flex items-start gap-1 text-xs text-amber-600 dark:text-amber-400">
              <AlertTriangle className="mt-0.5 size-3.5 shrink-0" />
              {capitalNote}
            </p>
          </SectionCard>
        </>
      ) : null}

      <SectionCard
        title="呆滞清单"
        description={
          list ? `${dispositionNoteText()}${list.salesVelocityNote}` : dispositionNoteText()
        }
      >
        {loading && !list ? (
          <EmptyState title="加载中" />
        ) : !list || list.rows.length === 0 ? (
          <EmptyState
            title="没有需要处理的库存"
            description="当前筛选下未命中滞销、临期、残次或呆滞。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="py-2">SKU / 批次</th>
                  <th className="py-2">库位</th>
                  <th className="py-2">库龄</th>
                  <th className="py-2">档位</th>
                  <th className="py-2 text-right">可用量</th>
                  <th className="py-2">处置建议</th>
                  <th className="py-2">判定依据</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {list.rows.map((r) => (
                  <tr key={r.bucketId} className="border-b align-top last:border-0">
                    <td className="py-2">
                      <div className="font-medium">{r.skuCode}</div>
                      <div className="text-muted-foreground text-xs">
                        {r.lotCode ?? '无批次'} · {r.warehouseCode}
                      </div>
                    </td>
                    <td className="py-2">
                      <div>{r.locationCode}</div>
                      <div className="text-muted-foreground text-xs">{r.qualityStatusCode}</div>
                    </td>
                    <td className="py-2">
                      <div>{agingDaysText(r.agingDays)}</div>
                      <div className="text-muted-foreground text-xs">
                        {agingBasisText(r.agingBasis, r.agingBasisLabel, r.inboundDate)}
                      </div>
                    </td>
                    <td className="py-2">
                      <div>
                        {slowMovingStatusLabel(r.slowMovingStatus, r.slowMovingStatusLabel)}
                      </div>
                      <div className="text-muted-foreground text-xs">
                        {thresholdSourceText(r.slowMovingDaysSource)}
                      </div>
                    </td>
                    <td className="py-2 text-right">{qty(r.available)}</td>
                    <td className="py-2">
                      <div>{dispositionLabel(r.disposition, r.dispositionLabel)}</div>
                      <div className="text-muted-foreground text-xs">{r.dispositionRationale}</div>
                    </td>
                    <td className="text-muted-foreground max-w-md py-2 text-xs">{r.basis}</td>
                    <td className="py-2">
                      {writable ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => {
                            setEditing(r.skuId);
                            setSlowDays(String(r.slowMovingDays));
                            setObsoleteDays(String(r.obsoleteDays));
                          }}
                        >
                          调阈值
                        </Button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {list && list.total > 0 ? (
          <div className="mt-3 flex items-center justify-between text-sm">
            <span className="text-muted-foreground">
              共 {list.total} 行，第 {list.page + 1} / {totalPages} 页
            </span>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={list.page <= 0}
                onClick={() => setPageNumber(Math.max(0, pageNumber - 1))}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={list.page + 1 >= totalPages}
                onClick={() => setPageNumber(pageNumber + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        ) : null}
      </SectionCard>

      <SectionCard
        title="库龄阈值策略"
        description="滞销线与呆滞线逐 SKU 配置；快消与家具的合理阈值差一个数量级。未配置的 SKU 使用默认兜底值，并在清单上标明来源。"
      >
        {!policies || policies.rows.length === 0 ? (
          <EmptyState
            title="尚未配置任何阈值策略"
            description={`当前所有 SKU 使用默认兜底：滞销 ${report?.defaultSlowMovingDays ?? 60} 天、呆滞 ${report?.defaultObsoleteDays ?? 90} 天。可在清单里对某个 SKU 单独配置。`}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="py-2">SKU</th>
                  <th className="py-2 text-right">滞销线</th>
                  <th className="py-2 text-right">呆滞线</th>
                  <th className="py-2">状态</th>
                  <th className="py-2 text-right">在手量</th>
                  <th className="py-2">备注</th>
                </tr>
              </thead>
              <tbody>
                {policies.rows.map((p) => (
                  <tr key={p.policyId} className="border-b last:border-0">
                    <td className="py-2">
                      <div className="font-medium">{p.skuCode}</div>
                      <div className="text-muted-foreground text-xs">{p.skuName}</div>
                    </td>
                    <td className="py-2 text-right">{p.slowMovingDays} 天</td>
                    <td className="py-2 text-right">{p.obsoleteDays} 天</td>
                    <td className="py-2">{p.enabled ? '启用' : '停用（退回默认）'}</td>
                    <td className="py-2 text-right">{qty(p.onHand)}</td>
                    <td className="text-muted-foreground py-2 text-xs">{p.notes}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      <Modal open={editing !== null} onOpenChange={(open) => !open && setEditing(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>配置库龄阈值</ModalTitle>
            <ModalDescription>
              呆滞线必须不早于滞销线 ——
              呆滞是滞销的更严重档，两条线倒过来会让同一条库存同时命中两档。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3 py-2">
            <label className="block text-sm">
              <span className="text-muted-foreground mb-1 block">滞销线（天）</span>
              <input
                className="border-input bg-background w-full rounded-md border px-3 py-2"
                value={slowDays}
                onChange={(e) => setSlowDays(e.target.value)}
              />
            </label>
            <label className="block text-sm">
              <span className="text-muted-foreground mb-1 block">呆滞线（天）</span>
              <input
                className="border-input bg-background w-full rounded-md border px-3 py-2"
                value={obsoleteDays}
                onChange={(e) => setObsoleteDays(e.target.value)}
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="outline" onClick={() => setEditing(null)}>
              取消
            </Button>
            <Button onClick={() => void save()} disabled={saving}>
              <Save className="mr-2 size-4" />
              {saving ? '保存中' : '保存'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <p className="text-muted-foreground flex items-start gap-1 text-xs">
        <Clock className="mt-0.5 size-3.5 shrink-0" />
        库龄每天都在变，本页所有库龄均在查询时从批次收货日实时算出，不落库 ——
        存下来的库龄第二天就会过期。
      </p>
    </div>
  );
}
