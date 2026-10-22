'use client';

import { useCallback, useEffect, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  accrueAttribution,
  attributionCountText,
  attributionModelLabel,
  attributionMoney,
  attributionPercent,
  attributionRoiText,
  attributionTimestamp,
  accrualStatusLabel,
  commissionBasisLabel,
  emptyStateText,
  fetchAttributionCapabilities,
  fetchAttributionOptions,
  fetchAttributionRecord,
  fetchAttributionRecords,
  fetchAttributionReport,
  fetchAttributionRules,
  fetchAttributionSyncStatus,
  fetchCurrentAttributionRule,
  poolWeightBasisLabel,
  refundTreatmentLabel,
  runAttribution,
  saveAttributionRule,
  syncAttributions,
  unavailableDimensions,
} from './influencer-attribution-model';
import type {
  AttributionCapabilities,
  AttributionDetail,
  AttributionOptions,
  AttributionPage,
  AttributionReportPage,
  AttributionRuleRow,
  AttributionRunResult,
  AttributionSyncStatus,
  SaveAttributionRuleInput,
} from './influencer-attribution-model';

/**
 * 14.5.2.1 带货归因。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **三个 GMV 口径分列三栏**。档案 GMV（14.5.1.1 入库值）、合作历史合计
 *    （本地按合作行求和）、归因口径（本模块按规则分摊后的净额）**谁也不覆盖谁**。
 *    看板与跨部门沟通必须分别引用。
 * 2. **佣金成本是真实成本，但还没进利润表**。13.4 利润（reporting 服务）未交付 ⇒
 *    佣金**只在本服务内留痕**，页面**不许**写「已同步到利润分析」。
 *    「扣佣金后净额」**只扣佣金**（样品 / 履约 / 平台费不在里面），不是净收益。
 * 3. **「没录」必须看得见**。GMV / 佣金 / ROI 没录显示「未录入」，**不是 0.00** ——
 *    0 会被读成「这个达人不用付佣金、ROI 无穷大」。
 * 4. **平台带货数据同步未接入** ⇒ 同步入口明确返回「未接入」并**不写任何数据**，
 *    页面常驻这条说明。
 * 5. **归因规则固定并公示**。一个租户只有一套生效口径，每条归因带自己的规则快照。
 */
export function InfluencerAttributionWorkspace() {
  const [capabilities, setCapabilities] = useState<AttributionCapabilities | null>(null);
  const [options, setOptions] = useState<AttributionOptions | null>(null);
  const [syncStatus, setSyncStatus] = useState<AttributionSyncStatus | null>(null);
  const [tab, setTab] = useState<'report' | 'records' | 'rules'>('report');
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts, sync] = await Promise.all([
          fetchAttributionCapabilities(),
          fetchAttributionOptions(),
          fetchAttributionSyncStatus(),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
        setSyncStatus(sync);
      } catch (e) {
        if (alive) setError(errorText(e));
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const unavailable = unavailableDimensions(capabilities);

  return (
    <div className="flex flex-col gap-4 p-4">
      <header className="flex flex-col gap-2">
        <h1 className="text-xl font-medium">带货归因</h1>
        <p className="text-sm text-muted-foreground">
          达人带来的订单量、GMV、退款率、佣金成本与有效粉丝转化；对比达人投入产出，
          并在归因批次写完后触发一次评级重算。
        </p>
        {capabilities ? (
          <div className="flex flex-col gap-1 text-sm">
            <p>{capabilities.caliberNote}</p>
            <p>{capabilities.commissionNote}</p>
            <p>{capabilities.ruleNote}</p>
            <p>{capabilities.syncNote}</p>
          </div>
        ) : null}
        {unavailable.length ? (
          <div className="rounded border p-4">
            <p className="text-sm font-medium">本模块未交付的能力（界面上必须让人看见）</p>
            <ul className="flex flex-col gap-1 text-sm">
              {unavailable.map((dimension) => (
                <li key={dimension.dimension} className="text-muted-foreground">
                  <span className="font-medium">{dimension.dimension}</span>：{dimension.description}
                  {dimension.ownerModule ? `（责任模块 ${dimension.ownerModule}）` : ''}
                  —— {dimension.impact}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        {capabilities?.profitIntegration ? (
          <p className="text-sm text-amber-600">
            🔴 佣金成本还没进利润表：{capabilities.profitIntegration.note}
          </p>
        ) : null}
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      <nav className="flex flex-wrap gap-2">
        {(
          [
            ['report', '达人效果报表'],
            ['records', '归因记录'],
            ['rules', '归因规则'],
          ] as const
        ).map(([key, label]) => (
          <button
            key={key}
            type="button"
            className="rounded border px-3 py-1 text-sm"
            onClick={() => setTab(key)}
            aria-pressed={tab === key}
          >
            {label}
          </button>
        ))}
      </nav>

      {tab === 'report' ? <ReportTab options={options} /> : null}
      {tab === 'records' ? <RecordsTab options={options} /> : null}
      {tab === 'rules' ? <RulesTab options={options} syncStatus={syncStatus} /> : null}
    </div>
  );
}

// ==================================================================
// 达人效果报表
// ==================================================================

function ReportTab({ options }: { options: AttributionOptions | null }) {
  const [page, setPage] = useState<AttributionReportPage | null>(null);
  const [keyword, setKeyword] = useState('');
  const [shopCode, setShopCode] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [sort, setSort] = useState('ATTRIBUTED_GMV_DESC');
  const [current, setCurrent] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const load = useCallback(
    async (target: number) => {
      setLoading(true);
      try {
        const result = await fetchAttributionReport({
          keyword: keyword.trim() || undefined,
          shopCode: shopCode || undefined,
          platformCode: platformCode || undefined,
          sort,
          page: target,
          size: 20,
        });
        setPage(result);
        setCurrent(target);
        setError('');
      } catch (e) {
        setError(errorText(e));
      } finally {
        setLoading(false);
      }
    },
    [keyword, shopCode, platformCode, sort],
  );

  useEffect(() => {
    void load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shopCode, platformCode, sort]);

  const summary = page?.summary;
  const filtered = Boolean(keyword.trim() || shopCode || platformCode);

  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1 text-sm">
          达人关键字
          <input
            className="rounded border px-2 py-1 text-sm"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void load(0);
            }}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          店铺
          <select
            className="rounded border px-2 py-1 text-sm"
            value={shopCode}
            onChange={(e) => setShopCode(e.target.value)}
          >
            <option value="">全部</option>
            {(options?.shopCodes ?? []).map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          平台
          <select
            className="rounded border px-2 py-1 text-sm"
            value={platformCode}
            onChange={(e) => setPlatformCode(e.target.value)}
          >
            <option value="">全部</option>
            {(options?.platformCodes ?? []).map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          排序
          <select
            className="rounded border px-2 py-1 text-sm"
            value={sort}
            onChange={(e) => setSort(e.target.value)}
          >
            <option value="ATTRIBUTED_GMV_DESC">归因 GMV 从高到低</option>
            <option value="COMMISSION_DESC">佣金成本从高到低</option>
            <option value="ROI_DESC">ROI 从高到低</option>
            <option value="EFFECTIVE_ORDERS_DESC">有效订单量从高到低</option>
            <option value="FOLLOWERS_DESC">粉丝数从高到低</option>
          </select>
        </label>
        <button
          type="button"
          className="rounded border px-3 py-1 text-sm"
          onClick={() => void load(0)}
        >
          查询
        </button>
      </div>

      {summary ? (
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <div className="rounded border p-4">
            <p className="text-muted-foreground text-xs">归因 GMV 合计</p>
            <p className="text-sm font-medium">
              {attributionMoney(summary.attributedGmvTotal)}
            </p>
          </div>
          <div className="rounded border p-4">
            <p className="text-muted-foreground text-xs">佣金成本合计</p>
            <p className="text-sm font-medium">{attributionMoney(summary.commissionTotal)}</p>
            <p className="text-muted-foreground text-xs">
              已计提 {attributionMoney(summary.commissionAccruedTotal)} / 待计提{' '}
              {attributionMoney(summary.commissionPendingTotal)}
            </p>
          </div>
          <div className="rounded border p-4">
            <p className="text-muted-foreground text-xs">整体 ROI</p>
            <p className="text-sm font-medium">{attributionRoiText(summary.roi)}</p>
          </div>
          <div className="rounded border p-4">
            <p className="text-muted-foreground text-xs">有效粉丝转化率</p>
            <p className="text-sm font-medium">
              {attributionPercent(summary.followerConversionRate)}
            </p>
            <p className="text-muted-foreground text-xs">
              有效订单 {attributionCountText(summary.effectiveOrderTotal)}
            </p>
          </div>
        </div>
      ) : null}

      {summary ? (
        <p className="text-muted-foreground text-xs">
          归因口径按规则{' '}
          <span className="font-medium">{summary.ruleCode ?? '（未配置生效规则）'}</span>
          {summary.attributionModel ? `（${attributionModelLabel(summary.attributionModel)}）` : ''}
          统计；有归因记录的达人 {summary.influencerCount} 位、归因记录 {summary.attributionCount} 条。
        </p>
      ) : null}

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      <div className="overflow-auto">
        {loading ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : page && page.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            {emptyStateText(page.total, filtered)}
          </p>
        ) : page ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">达人</th>
                <th className="py-1">粉丝</th>
                <th className="py-1">① 档案 GMV</th>
                <th className="py-1">② 合作历史合计</th>
                <th className="py-1">③ 归因 GMV</th>
                <th className="py-1">归因订单量</th>
                <th className="py-1">归因退款率</th>
                <th className="py-1">佣金成本</th>
                <th className="py-1">有效订单量</th>
                <th className="py-1">有效粉丝转化率</th>
                <th className="py-1">ROI</th>
                <th className="py-1">扣佣金后净额</th>
                <th className="py-1">评级</th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((row) => (
                <tr key={row.influencerId} className="border-t">
                  <td className="py-1">
                    <span className="font-medium">{row.displayName}</span>
                    <span className="text-muted-foreground text-xs"> {row.shopCode}</span>
                  </td>
                  <td className="py-1">{row.followerCount}</td>
                  <td className="py-1">
                    {attributionMoney(row.profileGmv, row.profileGmvCurrency)}
                  </td>
                  <td className="py-1">{attributionMoney(row.cooperationGmvTotal)}</td>
                  <td className="py-1">{attributionMoney(row.attributedGmvTotal)}</td>
                  <td className="py-1">{attributionCountText(row.attributedOrderTotal)}</td>
                  <td className="py-1">{attributionPercent(row.attributedRefundRate)}</td>
                  <td className="py-1">
                    {attributionMoney(row.commissionTotal)}
                    <span className="text-muted-foreground text-xs">
                      {' '}
                      已计提 {attributionMoney(row.commissionAccruedTotal)}
                    </span>
                  </td>
                  <td className="py-1">{attributionCountText(row.effectiveOrderTotal)}</td>
                  <td className="py-1">{attributionPercent(row.followerConversionRate)}</td>
                  <td className="py-1">{attributionRoiText(row.roi)}</td>
                  <td className="py-1">{attributionMoney(row.netGmvAfterCommission)}</td>
                  <td className="py-1">
                    {row.ratingTierName ?? '暂无评分'}
                    <span className="text-muted-foreground text-xs">
                      {' '}
                      {row.ratingScore === null ? '' : row.ratingScore.toFixed(4)}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </div>

      {page && page.total > 0 ? (
        <div className="flex items-center gap-2 text-sm">
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm disabled:opacity-40"
            disabled={current === 0 || loading}
            onClick={() => void load(current - 1)}
          >
            上一页
          </button>
          <span className="text-muted-foreground text-xs">
            第 {current + 1} / {Math.max(1, Math.ceil(page.total / 20))} 页，共 {page.total} 位达人
          </span>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm disabled:opacity-40"
            disabled={loading || (current + 1) * 20 >= page.total}
            onClick={() => void load(current + 1)}
          >
            下一页
          </button>
        </div>
      ) : null}

      <p className="text-muted-foreground text-xs">
        🔴 「扣佣金后净额」只扣了佣金（样品 / 履约 / 平台费不在里面），**不是净收益**；
        佣金成本**还没进利润表**（13.4 未交付）。
      </p>
    </section>
  );
}

// ==================================================================
// 归因记录
// ==================================================================

function RecordsTab({ options }: { options: AttributionOptions | null }) {
  const [page, setPage] = useState<AttributionPage | null>(null);
  const [detail, setDetail] = useState<AttributionDetail | null>(null);
  const [rules, setRules] = useState<AttributionRuleRow[]>([]);
  const [keyword, setKeyword] = useState('');
  const [ruleCode, setRuleCode] = useState('');
  const [accrualStatus, setAccrualStatus] = useState('');
  const [current, setCurrent] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const result = await fetchAttributionRules();
        if (alive) setRules(result.items);
      } catch {
        // 规则列表读不出来不影响归因记录本身，页面上照样能看记录。
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const load = useCallback(
    async (target: number) => {
      setLoading(true);
      try {
        const result = await fetchAttributionRecords({
          keyword: keyword.trim() || undefined,
          ruleCode: ruleCode || undefined,
          accrualStatus: accrualStatus || undefined,
          sort: 'ATTRIBUTED_GMV_DESC',
          page: target,
          size: 20,
        });
        setPage(result);
        setCurrent(target);
        setError('');
      } catch (e) {
        setError(errorText(e));
      } finally {
        setLoading(false);
      }
    },
    [keyword, ruleCode, accrualStatus],
  );

  useEffect(() => {
    void load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ruleCode, accrualStatus]);

  const filtered = Boolean(keyword.trim() || ruleCode || accrualStatus);

  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1 text-sm">
          达人关键字
          <input
            className="rounded border px-2 py-1 text-sm"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void load(0);
            }}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          归因规则
          <select
            className="rounded border px-2 py-1 text-sm"
            value={ruleCode}
            onChange={(e) => setRuleCode(e.target.value)}
          >
            <option value="">全部</option>
            {rules.map((rule) => (
              <option key={rule.id} value={rule.ruleCode}>
                {rule.ruleCode}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          计提状态
          <select
            className="rounded border px-2 py-1 text-sm"
            value={accrualStatus}
            onChange={(e) => setAccrualStatus(e.target.value)}
          >
            <option value="">全部</option>
            {(options?.accrualStatuses ?? []).map((option) => (
              <option key={option.code} value={option.code}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="rounded border px-3 py-1 text-sm"
          onClick={() => void load(0)}
        >
          查询
        </button>
      </div>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      <div className="overflow-auto">
        {loading ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : page && page.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">{emptyStateText(page.total, filtered)}</p>
        ) : page ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">达人</th>
                <th className="py-1">活动</th>
                <th className="py-1">规则</th>
                <th className="py-1">归因模型</th>
                <th className="py-1">基数 GMV</th>
                <th className="py-1">权重</th>
                <th className="py-1">归因 GMV</th>
                <th className="py-1">佣金成本</th>
                <th className="py-1">计提状态</th>
                <th className="py-1">归因时刻</th>
                <th className="py-1">操作</th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((row) => (
                <tr key={row.id} className="border-t">
                  <td className="py-1">{row.influencerName}</td>
                  <td className="py-1">{row.campaignName}</td>
                  <td className="py-1">{row.ruleCode}</td>
                  <td className="py-1">{attributionModelLabel(row.attributionModel)}</td>
                  <td className="py-1">{attributionMoney(row.attributionBaseGmv)}</td>
                  <td className="py-1">
                    {row.attributionWeight === null ? '—' : row.attributionWeight.toFixed(6)}
                  </td>
                  <td className="py-1">{attributionMoney(row.attributedGmv, row.currency)}</td>
                  <td className="py-1">{attributionMoney(row.commissionAmount)}</td>
                  <td className="py-1">
                    {accrualStatusLabel(row.accrualStatus)}
                    {row.commissionVariance === null ? null : (
                      <span className="text-amber-600 text-xs">
                        {' '}
                        差额 {attributionMoney(row.commissionVariance)}
                      </span>
                    )}
                  </td>
                  <td className="py-1">{attributionTimestamp(row.attributedAt)}</td>
                  <td className="py-1">
                    <button
                      type="button"
                      className="rounded border px-2 py-1 text-sm"
                      onClick={() => {
                        void (async () => {
                          try {
                            setDetail(await fetchAttributionRecord(row.id));
                            setError('');
                          } catch (e) {
                            setError(errorText(e));
                          }
                        })();
                      }}
                    >
                      详情
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </div>

      {page && page.total > 0 ? (
        <div className="flex items-center gap-2 text-sm">
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm disabled:opacity-40"
            disabled={current === 0 || loading}
            onClick={() => void load(current - 1)}
          >
            上一页
          </button>
          <span className="text-muted-foreground text-xs">
            第 {current + 1} / {Math.max(1, Math.ceil(page.total / 20))} 页，共 {page.total} 条归因
          </span>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm disabled:opacity-40"
            disabled={loading || (current + 1) * 20 >= page.total}
            onClick={() => void load(current + 1)}
          >
            下一页
          </button>
        </div>
      ) : null}

      {detail ? (
        <AttributionDetailPanel
          detail={detail}
          onChanged={(next) => {
            setDetail(next);
            void load(current);
          }}
          onError={setError}
        />
      ) : null}
    </section>
  );
}

// ==================================================================
// 归因详情（含佣金计提）
// ==================================================================

function AttributionDetailPanel({
  detail,
  onChanged,
  onError,
}: {
  detail: AttributionDetail;
  onChanged: (detail: AttributionDetail) => void;
  onError: (message: string) => void;
}) {
  const row = detail.row;
  return (
    <div className="rounded border p-4">
      <p className="text-sm font-medium">归因详情 · {row.influencerName}</p>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <div>
          <p className="text-muted-foreground text-xs">① 档案 GMV</p>
          <p className="text-sm">
            {attributionMoney(detail.influencer.profileGmv, detail.influencer.profileGmvCurrency)}
          </p>
        </div>
        <div>
          <p className="text-muted-foreground text-xs">② 合作历史合计</p>
          <p className="text-sm">{attributionMoney(detail.influencer.cooperationGmvTotal)}</p>
        </div>
        <div>
          <p className="text-muted-foreground text-xs">③ 归因 GMV</p>
          <p className="text-sm">{attributionMoney(detail.influencer.attributedGmvTotal)}</p>
        </div>
        <div>
          <p className="text-muted-foreground text-xs">佣金成本</p>
          <p className="text-sm">{attributionMoney(detail.influencer.commissionTotal)}</p>
        </div>
        <div>
          <p className="text-muted-foreground text-xs">ROI</p>
          <p className="text-sm">{attributionRoiText(detail.influencer.roi)}</p>
        </div>
        <div>
          <p className="text-muted-foreground text-xs">有效粉丝转化率</p>
          <p className="text-sm">
            {attributionPercent(detail.influencer.followerConversionRate)}
          </p>
        </div>
        <div>
          <p className="text-muted-foreground text-xs">评级</p>
          <p className="text-sm">{detail.influencer.ratingTierName ?? '暂无评分'}</p>
        </div>
        <div>
          <p className="text-muted-foreground text-xs">源合作记录</p>
          <p className="text-sm">
            {detail.source.campaignName} · GMV {attributionMoney(detail.source.gmv)} · 佣金{' '}
            {attributionMoney(detail.source.commission)}
          </p>
        </div>
      </div>

      <p className="text-muted-foreground text-xs">
        规则快照：{detail.rule.ruleCode} · {attributionModelLabel(detail.rule.attributionModel)} ·{' '}
        {poolWeightBasisLabel(detail.rule.poolWeightBasis)} · 窗口 {detail.rule.attributionWindowDays}{' '}
        天 · {refundTreatmentLabel(detail.rule.refundTreatment)} ·{' '}
        {commissionBasisLabel(detail.rule.commissionBasis)}
      </p>

      <div className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1 text-sm">
          计提状态
          <select
            className="rounded border px-2 py-1 text-sm"
            defaultValue={row.accrualStatus}
            id={`accrual-status-${row.id}`}
          >
            <option value="ACCRUED">已计提</option>
            <option value="REVERSED">已冲销</option>
            <option value="PENDING">退回待计提</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          备注
          <input
            id={`accrual-note-${row.id}`}
            className="rounded border px-2 py-1 text-sm"
            defaultValue={row.accrualNote}
          />
        </label>
        <button
          type="button"
          className="rounded border px-3 py-1 text-sm"
          onClick={() => {
            const status = (
              document.getElementById(`accrual-status-${row.id}`) as HTMLSelectElement | null
            )?.value;
            const note = (
              document.getElementById(`accrual-note-${row.id}`) as HTMLInputElement | null
            )?.value;
            void (async () => {
              try {
                onChanged(await accrueAttribution(row.id, { status: status ?? 'ACCRUED', note: note ?? '' }));
              } catch (e) {
                onError(errorText(e));
              }
            })();
          }}
        >
          提交计提状态
        </button>
      </div>
      <p className="text-muted-foreground text-xs">
        当前状态：{accrualStatusLabel(row.accrualStatus)}
        {row.accruedAt ? ` · 计提时刻 ${attributionTimestamp(row.accruedAt)}` : ''}
        {row.commissionVariance === null ? null : ` · 与口径的差额 ${attributionMoney(row.commissionVariance)}`}
      </p>
    </div>
  );
}

// ==================================================================
// 归因规则
// ==================================================================

function RulesTab({
  options,
  syncStatus,
}: {
  options: AttributionOptions | null;
  syncStatus: AttributionSyncStatus | null;
}) {
  const [rules, setRules] = useState<AttributionRuleRow[]>([]);
  const [current, setCurrent] = useState<AttributionRuleRow | null>(null);
  const [run, setRun] = useState<AttributionRunResult | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState<SaveAttributionRuleInput>({
    ruleCode: '',
    attributionModel: 'COOPERATION_FULL',
    poolWeightBasis: 'FOLLOWER_SHARE',
    attributionWindowDays: 30,
    refundTreatment: 'GROSS',
    commissionBasis: 'COOPERATION_COMMISSION',
    commissionRate: null,
    status: 'ACTIVE',
    note: '',
  });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [page, active] = await Promise.all([
        fetchAttributionRules(),
        fetchCurrentAttributionRule(),
      ]);
      setRules(page.items);
      setCurrent(active);
      setError('');
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <section className="flex flex-col gap-3">
      <div className="rounded border p-4">
        <p className="text-sm font-medium">生效的归因规则</p>
        {loading ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : current ? (
          <p className="text-sm">
            {current.ruleCode} · {attributionModelLabel(current.attributionModel)} ·{' '}
            {poolWeightBasisLabel(current.poolWeightBasis)} · 窗口 {current.attributionWindowDays} 天 ·{' '}
            {refundTreatmentLabel(current.refundTreatment)} ·{' '}
            {commissionBasisLabel(current.commissionBasis)}
          </p>
        ) : (
          <p className="text-sm text-amber-600">
            还没有生效的归因规则 —— 没有规则就没有归因，跑批次会直接报冲突。
          </p>
        )}
        <div className="flex flex-wrap items-end gap-2">
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            onClick={() => {
              void (async () => {
                try {
                  setRun(await runAttribution({}));
                  setNotice('');
                  setError('');
                  await load();
                } catch (e) {
                  setError(errorText(e));
                }
              })();
            }}
          >
            运行归因批次
          </button>
          {run ? <p className="text-sm">{run.message}</p> : null}
        </div>
        {run ? (
          <p className="text-muted-foreground text-xs">
            新建 {run.created} 条 / 刷新 {run.refreshed} 条 / 跳过出窗 {run.skippedOutOfWindow} 条 /
            跳过无效果数据 {run.skippedNoEffectData} 条 / 跳过分摊基准不可用 {run.skippedNoSplitBasis} 条
            / 触发评级重算 {run.ratingRecomputed} 次
          </p>
        ) : null}
      </div>

      <div className="rounded border p-4">
        <p className="text-sm font-medium">配置一套归因规则（保存后旧的自动停用）</p>
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1 text-sm">
            规则编码
            <input
              className="rounded border px-2 py-1 text-sm"
              value={form.ruleCode}
              onChange={(e) => setForm({ ...form, ruleCode: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            归因模型
            <select
              className="rounded border px-2 py-1 text-sm"
              value={form.attributionModel}
              onChange={(e) => setForm({ ...form, attributionModel: e.target.value })}
            >
              {(options?.attributionModels ?? []).map((option) => (
                <option key={option.code} value={option.code}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            分摊基准
            <select
              className="rounded border px-2 py-1 text-sm"
              value={form.poolWeightBasis}
              onChange={(e) => setForm({ ...form, poolWeightBasis: e.target.value })}
            >
              {(options?.poolWeightBases ?? []).map((option) => (
                <option key={option.code} value={option.code}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            归因窗口（天）
            <input
              type="number"
              min={0}
              max={365}
              className="rounded border px-2 py-1 text-sm"
              value={form.attributionWindowDays}
              onChange={(e) =>
                setForm({ ...form, attributionWindowDays: Number(e.target.value) })
              }
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            退款口径
            <select
              className="rounded border px-2 py-1 text-sm"
              value={form.refundTreatment}
              onChange={(e) => setForm({ ...form, refundTreatment: e.target.value })}
            >
              {(options?.refundTreatments ?? []).map((option) => (
                <option key={option.code} value={option.code}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            佣金计提口径
            <select
              className="rounded border px-2 py-1 text-sm"
              value={form.commissionBasis}
              onChange={(e) => setForm({ ...form, commissionBasis: e.target.value })}
            >
              {(options?.commissionBases ?? []).map((option) => (
                <option key={option.code} value={option.code}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            佣金比例（按归因 GMV 计提时必填）
            <input
              className="rounded border px-2 py-1 text-sm"
              value={form.commissionRate ?? ''}
              onChange={(e) =>
                setForm({
                  ...form,
                  commissionRate: e.target.value === '' ? null : Number(e.target.value),
                })
              }
            />
          </label>
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            onClick={() => {
              void (async () => {
                try {
                  await saveAttributionRule(form);
                  setNotice('归因规则已保存（旧的生效规则已自动停用）。');
                  setError('');
                  await load();
                } catch (e) {
                  setError(errorText(e));
                }
              })();
            }}
          >
            保存规则
          </button>
        </div>
        {notice ? <p className="text-sm">{notice}</p> : null}
      </div>

      <div className="rounded border p-4">
        <p className="text-sm font-medium">归因规则历史</p>
        {loading ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : rules.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有配过归因规则。</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">规则编码</th>
                <th className="py-1">归因模型</th>
                <th className="py-1">分摊基准</th>
                <th className="py-1">窗口</th>
                <th className="py-1">退款口径</th>
                <th className="py-1">佣金口径</th>
                <th className="py-1">状态</th>
                <th className="py-1">创建时刻</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((rule) => (
                <tr key={rule.id} className="border-t">
                  <td className="py-1">{rule.ruleCode}</td>
                  <td className="py-1">{attributionModelLabel(rule.attributionModel)}</td>
                  <td className="py-1">{poolWeightBasisLabel(rule.poolWeightBasis)}</td>
                  <td className="py-1">{rule.attributionWindowDays} 天</td>
                  <td className="py-1">{refundTreatmentLabel(rule.refundTreatment)}</td>
                  <td className="py-1">{commissionBasisLabel(rule.commissionBasis)}</td>
                  <td className="py-1">{rule.status}</td>
                  <td className="py-1">{attributionTimestamp(rule.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="rounded border p-4">
        <p className="text-sm font-medium">平台带货数据同步</p>
        {syncStatus ? (
          <>
            <p className="text-muted-foreground text-xs">{syncStatus.note}</p>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">平台</th>
                  <th className="py-1">是否接入</th>
                  <th className="py-1">说明</th>
                  <th className="py-1">本租户归因记录</th>
                </tr>
              </thead>
              <tbody>
                {syncStatus.items.map((item) => (
                  <tr key={item.platformCode} className="border-t">
                    <td className="py-1">{item.platformName}</td>
                    <td className="py-1">{item.available ? '已接入' : '未接入'}</td>
                    <td className="py-1 text-muted-foreground">{item.reason}</td>
                    <td className="py-1">{item.registeredCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="flex flex-wrap items-end gap-2">
              <label className="flex flex-col gap-1 text-sm">
                平台
                <select
                  id="sync-platform"
                  className="rounded border px-2 py-1 text-sm"
                  defaultValue={options?.platformCodes?.[0] ?? ''}
                >
                  {(options?.platformCodes ?? []).map((code) => (
                    <option key={code} value={code}>
                      {code}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                店铺
                <select
                  id="sync-shop"
                  className="rounded border px-2 py-1 text-sm"
                  defaultValue={options?.shopCodes?.[0] ?? ''}
                >
                  {(options?.shopCodes ?? []).map((code) => (
                    <option key={code} value={code}>
                      {code}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                className="rounded border px-3 py-1 text-sm"
                onClick={() => {
                  const platformCode = (
                    document.getElementById('sync-platform') as HTMLSelectElement | null
                  )?.value;
                  const shopCode = (
                    document.getElementById('sync-shop') as HTMLSelectElement | null
                  )?.value;
                  void (async () => {
                    try {
                      const result = await syncAttributions({
                        platformCode: platformCode ?? '',
                        shopCode: shopCode ?? '',
                      });
                      setNotice(result.message);
                      setError('');
                    } catch (e) {
                      setError(errorText(e));
                    }
                  })();
                }}
              >
                触发同步
              </button>
            </div>
          </>
        ) : (
          <p className="text-muted-foreground text-sm">加载中…</p>
        )}
      </div>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </section>
  );
}
