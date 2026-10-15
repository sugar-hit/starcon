'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  DealerAlertPage,
  DealerAuthorizationPage,
  DealerCapabilities,
  DealerQuotaAttainmentPage,
  DealerRebatePage,
} from '../../../../../packages/api-contracts/src/index';
import {
  attainmentTone,
  breachLabel,
  complianceLabel,
  fetchAlerts,
  fetchAuthorizations,
  fetchCapabilities,
  fetchQuotaAttainment,
  fetchRebates,
  formatAttainment,
  rebateBasisLabel,
  validityLabel,
} from './dealer-channel-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 9.7.1.2 经销与分销管理工作区。
 *
 * <p>四个输出各占一屏，顺序就是蓝图的输出顺序：
 * 授权台账 → 配额完成率 → 返点计提 → 窜货预警。
 *
 * <p><b>页面刻意把三件「未确认/未接通」写成显眼的事实，而不是脚注</b>：
 *
 * <ul>
 *   <li>返点每一行都标「本模块口径 · 未与财务确认」—— 蓝图原文写死
 *       「需与财务确认计提口径」，而 12 应付/返点未交付。</li>
 *   <li>授权每一行都标「未校验（16 合规未交付）」—— 合规校验没有可读上游，
 *       画成「通过」比画成空白更危险。</li>
 *   <li>能力区把 available=false 的依赖逐条列出并写明归属模块，
 *       而不是给一个点下去 404 的按钮。</li>
 * </ul>
 *
 * <p>加载 / 空 / 错误三态齐全。
 */
export function DealerChannelWorkspace() {
  const [capabilities, setCapabilities] = useState<DealerCapabilities | null>(null);
  const [authorizations, setAuthorizations] = useState<DealerAuthorizationPage | null>(null);
  const [attainment, setAttainment] = useState<DealerQuotaAttainmentPage | null>(null);
  const [rebates, setRebates] = useState<DealerRebatePage | null>(null);
  const [alerts, setAlerts] = useState<DealerAlertPage | null>(null);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // 本仓库开了 exactOptionalPropertyTypes：可选字段**不能**显式传 undefined，
      // 必须条件展开 —— 传 undefined 会在编译期报错，而不是运行时被忽略。
      const [caps, auths, att, reb, alr] = await Promise.all([
        fetchCapabilities(),
        fetchAuthorizations({ ...(keyword.trim() ? { keyword } : {}), size: 50 }),
        fetchQuotaAttainment({ size: 50 }),
        fetchRebates({ size: 50 }),
        fetchAlerts({ size: 50 }),
      ]);
      setCapabilities(caps);
      setAuthorizations(auths);
      setAttainment(att);
      setRebates(reb);
      setAlerts(alr);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const unavailable = (capabilities?.dependencies ?? []).filter((d) => !d.available);
  const openAlerts = (alerts?.items ?? []).filter((a) => a.alertState === 'OPEN').length;
  const pendingRebate = (rebates?.items ?? []).reduce((sum, r) => sum + Number(r.rebateAmount), 0);
  const unconfirmed = (rebates?.items ?? []).filter((r) => !r.financeConfirmed).length;

  return (
    <div className="space-y-6">
      <PageHeader
        title="经销与分销管理"
        description="经销商授权台账、销量配额与回款考核、返点计提、跨区域窜货预警。"
        actions={
          <Button onClick={() => void load()} disabled={loading} variant="outline">
            <RefreshCw className="mr-1.5 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="rounded-md border border-red-300 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="授权台账（份）" value={authorizations?.total ?? 0} />
        <StatCard label="考核期间（个）" value={attainment?.total ?? 0} />
        <StatCard
          label="待处置窜货预警（条）"
          value={openAlerts}
          unit={`/ 共 ${alerts?.total ?? 0} 条`}
        />
        <StatCard
          label="已计提返点"
          value={pendingRebate.toFixed(2)}
          unit={unconfirmed > 0 ? `${unconfirmed} 笔未与财务确认口径` : '已全部确认口径'}
        />
      </div>

      {unavailable.length > 0 && (
        <SectionCard
          title="未接通的下游"
          description="这些环节有明确归属模块，当前由本模块自己的口径兜底。"
        >
          <ul className="space-y-2">
            {unavailable.map((d) => (
              <li key={d.moduleId} className="flex items-start gap-2 text-sm">
                <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />
                <span>
                  <Badge tone="warning">
                    {d.moduleId} {d.moduleName}
                  </Badge>
                  <span className="text-muted-foreground ml-2">归属 {d.ownerService} · 未交付</span>
                  <p className="text-muted-foreground mt-1">{d.note}</p>
                </span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}

      <SectionCard
        title="授权台账"
        description="输出①：经销商的授权区域、产品线、期限、经销价与返点比例。"
      >
        <div className="mb-3 flex gap-2">
          <Input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="按授权编号或经销商名称搜索"
            aria-label="搜索授权台账"
          />
        </div>
        {loading && !authorizations ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : (authorizations?.items.length ?? 0) === 0 ? (
          <EmptyState
            title="还没有经销授权"
            description="经销商来自 3.6.1.2 的客户角色往来单位；为它建一份授权（区域 + 产品线 + 期限）后，这里才会有内容。"
          />
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">授权编号</th>
                <th className="p-2">授权范围</th>
                <th className="p-2">期限</th>
                <th className="p-2 text-right">经销价</th>
                <th className="p-2 text-right">返点比例</th>
                <th className="p-2">状态</th>
              </tr>
            </thead>
            <tbody>
              {authorizations!.items.map((a) => (
                <tr key={a.id} className="border-b last:border-0">
                  <td className="p-2 font-medium">{a.authCode}</td>
                  <td className="text-muted-foreground p-2">
                    区域 {a.regionCodes.join('、')} / 产品线 {a.productLineCodes.join('、')}
                  </td>
                  <td className="text-muted-foreground p-2">{validityLabel(a)}</td>
                  <td className="p-2 text-right">
                    {Number(a.dealerPrice).toFixed(4)} {a.currency}
                  </td>
                  <td className="p-2 text-right">{(Number(a.rebateRate) * 100).toFixed(4)}%</td>
                  <td className="p-2">
                    <Badge tone={a.status === 'ACTIVE' ? 'success' : 'neutral'}>{a.status}</Badge>
                    {/* 合规没有可读上游（16 未交付）—— 明确写「未校验」，不留空让人以为是「通过」。 */}
                    <span className="ml-1.5 text-xs text-amber-700">
                      {complianceLabel(a.complianceState)}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      <SectionCard
        title="配额完成率"
        description="输出②：按授权 × 期间的销量、金额与回款考核。目标为 0 表示不限配额。"
      >
        {loading && !attainment ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : (attainment?.items.length ?? 0) === 0 ? (
          <EmptyState
            title="还没有考核期间"
            description="为一份授权建一个期间的销量与回款目标后，这里会按期间显示完成率。"
          />
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">授权 / 期间</th>
                <th className="p-2 text-right">数量</th>
                <th className="p-2 text-right">金额</th>
                <th className="p-2 text-right">回款</th>
              </tr>
            </thead>
            <tbody>
              {attainment!.items.map((q) => (
                <tr key={q.quotaId} className="border-b last:border-0">
                  <td className="p-2">
                    <div className="font-medium">{q.authCode}</div>
                    <div className="text-muted-foreground text-xs">
                      {q.periodStart} ~ {q.periodEnd}
                    </div>
                  </td>
                  <td className="p-2 text-right">
                    <div>
                      {Number(q.actualQuantity).toFixed(2)} / {Number(q.targetQuantity).toFixed(2)}
                    </div>
                    <Badge tone={attainmentTone(q.quantityAttainmentRate)}>
                      {formatAttainment(q.quantityAttainmentRate)}
                    </Badge>
                  </td>
                  <td className="p-2 text-right">
                    <div>
                      {Number(q.actualAmount).toFixed(2)} / {Number(q.targetAmount).toFixed(2)}
                    </div>
                    <Badge tone={attainmentTone(q.amountAttainmentRate)}>
                      {formatAttainment(q.amountAttainmentRate)}
                    </Badge>
                  </td>
                  <td className="p-2 text-right">
                    <div>
                      {Number(q.actualSettledAmount).toFixed(2)} /{' '}
                      {Number(q.targetSettledAmount).toFixed(2)}
                    </div>
                    <Badge tone={attainmentTone(q.settledAttainmentRate)}>
                      {formatAttainment(q.settledAttainmentRate)}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      <SectionCard
        title="返点计提台账"
        description="输出③：按期间计提的返点金额。经销返点是应收外的负债性承诺。"
      >
        {(rebates?.items.length ?? 0) === 0 ? (
          <EmptyState
            title="还没有返点计提"
            description="在一个已有销售的期间执行计提后，这里会显示计提基数、比例与金额。"
          />
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">期间</th>
                <th className="p-2 text-right">计提基数</th>
                <th className="p-2 text-right">比例</th>
                <th className="p-2 text-right">返点金额</th>
                <th className="p-2">口径</th>
              </tr>
            </thead>
            <tbody>
              {rebates!.items.map((r) => (
                <tr key={r.id} className="border-b last:border-0">
                  <td className="p-2">
                    {r.periodStart} ~ {r.periodEnd}
                  </td>
                  <td className="p-2 text-right">{Number(r.baseAmount).toFixed(4)}</td>
                  <td className="p-2 text-right">{(Number(r.rebateRate) * 100).toFixed(4)}%</td>
                  <td className="p-2 text-right font-medium">
                    {Number(r.rebateAmount).toFixed(4)} {r.currency}
                  </td>
                  <td className="p-2">
                    {/* 口径未确认是常态，必须显眼 —— 这是看板上最该被质疑的一列金额。 */}
                    <Badge tone={r.financeConfirmed ? 'success' : 'warning'}>
                      {rebateBasisLabel(r)}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      <SectionCard
        title="窜货预警"
        description="输出④：销售区域或产品线落在授权范围之外时产生。价格倒挂维度待 5.5.2 交付后合并。"
      >
        {(alerts?.items.length ?? 0) === 0 ? (
          <EmptyState title="没有窜货预警" description="授权范围内的销售不会产生预警。" />
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">越界维度</th>
                <th className="p-2">实际销售</th>
                <th className="p-2 text-right">金额</th>
                <th className="p-2">级别</th>
                <th className="p-2">处置</th>
              </tr>
            </thead>
            <tbody>
              {alerts!.items.map((a) => (
                <tr key={a.id} className="border-b last:border-0">
                  <td className="p-2">{breachLabel(a.breachKind)}</td>
                  <td className="text-muted-foreground p-2">
                    区域 {a.soldRegionCode} / 产品线 {a.productLineCode}
                    <div className="text-xs">
                      授权：区域 {a.authorizedRegions.join('、')} / 产品线{' '}
                      {a.authorizedProductLines.join('、')}
                    </div>
                  </td>
                  <td className="p-2 text-right">
                    {Number(a.saleAmount).toFixed(2)} {a.currency}
                  </td>
                  <td className="p-2">
                    <Badge tone={a.severity === 'HIGH' ? 'danger' : 'warning'}>{a.severity}</Badge>
                  </td>
                  <td className="p-2">
                    <Badge tone={a.alertState === 'OPEN' ? 'warning' : 'neutral'}>
                      {a.alertState}
                    </Badge>
                    {a.note && <div className="text-muted-foreground text-xs">{a.note}</div>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>
    </div>
  );
}
