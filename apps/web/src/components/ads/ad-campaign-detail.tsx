'use client';

import { useCallback, useEffect, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  adStatusLabel,
  changeHistoryText,
  copyAdCampaignStructure,
  copyResultText,
  creativeTypeLabel,
  emptyAdCopyStructureInput,
  fetchAdCampaignChangeHistory,
  fetchAdCampaignCreatives,
  fetchAdCampaignDetailCapabilities,
  fetchAdCampaignGroups,
  fetchAdCampaignKeywords,
  fetchAdCampaignPanorama,
  fetchAdCampaignProducts,
  money,
  operationLabel,
  operationResultLabel,
  operationResultTone,
  productModeLabel,
  quantity,
  ratioText,
  ratioTone,
  timestampText,
  updateAdCampaignBids,
  validateBidUpdate,
  validateCopyStructure,
} from './ad-campaign-detail-model';
import type {
  AdBidInput,
  AdCampaignChangeHistory,
  AdCampaignCopyResult,
  AdCampaignCreativeRow,
  AdCampaignDetailCapabilities,
  AdCampaignDetailPanorama,
  AdCampaignGroupRow,
  AdCampaignKeywordRow,
  AdCampaignProductRow,
  AdCopyStructureInput,
} from './ad-campaign-detail-model';

/**
 * 14.1.1.2 活动详情。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **「平台侧修改历史」当前拿不到**。平台广告 API 同步器尚未交付 ⇒ 没有同步源 ⇒
 *    平台侧改历史**无法获得**。这一段显示的是**本地**操作审计（谁在本系统里改了预算 / 状态 /
 *    竞价），**不是**平台侧的改历史 —— 平台侧被别人改过的事，这里一条都不会有。
 *    把「拿不到」渲染成「暂无修改记录」，就是在说「平台侧没改过」，那是假的。
 * 2. **「复制结构」的副本是真的，平台侧还不是**。复制完要同时显示三件事：结构真落库了
 *    （新活动 id + 组 / 商品 / 创意条数）、平台侧还没有（待回写 + 状态暂停）、
 *    关键词**没有**复制（平台侧还没建这个活动，关键词 id 无从分配）。
 * 3. **曲线上的点 = 有每日指标的天**。没有指标行的一天是「没数据」，不是「花了 0」——
 *    画成 0 会让人以为「那天没花钱」，而真相是「那天没同步到数据」。
 */
export function AdCampaignDetailWorkspace({ campaignId }: { campaignId: string }) {
  const [capabilities, setCapabilities] = useState<AdCampaignDetailCapabilities | null>(null);
  const [panorama, setPanorama] = useState<AdCampaignDetailPanorama | null>(null);
  const [groups, setGroups] = useState<AdCampaignGroupRow[]>([]);
  const [products, setProducts] = useState<AdCampaignProductRow[]>([]);
  const [creatives, setCreatives] = useState<AdCampaignCreativeRow[]>([]);
  const [keywords, setKeywords] = useState<AdCampaignKeywordRow[]>([]);
  const [changeHistory, setChangeHistory] = useState<AdCampaignChangeHistory | null>(null);

  const [groupKeyword, setGroupKeyword] = useState('');
  const [creativeType, setCreativeType] = useState('');
  const [keywordKeyword, setKeywordKeyword] = useState('');

  const [bidInput, setBidInput] = useState<AdBidInput>({ groupId: '', bid: '', reason: '' });
  const [bidErrors, setBidErrors] = useState<string[]>([]);
  const [bidMessage, setBidMessage] = useState('');

  const [copyInput, setCopyInput] = useState<AdCopyStructureInput>(emptyAdCopyStructureInput);
  const [copyErrors, setCopyErrors] = useState<string[]>([]);
  const [copyResult, setCopyResult] = useState<AdCampaignCopyResult | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [view, groupPage, productPage, creativePage, keywordPage, history] = await Promise.all([
        fetchAdCampaignPanorama(campaignId),
        fetchAdCampaignGroups(campaignId, { size: 100 }),
        fetchAdCampaignProducts(campaignId, { size: 100 }),
        fetchAdCampaignCreatives(campaignId, { size: 100 }),
        fetchAdCampaignKeywords(campaignId, { size: 100 }),
        fetchAdCampaignChangeHistory(campaignId),
      ]);
      setPanorama(view);
      setGroups(groupPage.items);
      setProducts(productPage.items);
      setCreatives(creativePage.items);
      setKeywords(keywordPage.items);
      setChangeHistory(history);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [campaignId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const caps = await fetchAdCampaignDetailCapabilities();
        if (alive) setCapabilities(caps);
      } catch (e) {
        if (alive) setError(errorText(e));
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const reloadGroups = useCallback(
    async (keyword: string) => {
      const page = await fetchAdCampaignGroups(campaignId, { keyword, size: 100 });
      setGroups(page.items);
    },
    [campaignId],
  );

  const reloadCreatives = useCallback(
    async (type: string) => {
      const page = await fetchAdCampaignCreatives(campaignId, { creativeType: type, size: 100 });
      setCreatives(page.items);
    },
    [campaignId],
  );

  const reloadKeywords = useCallback(
    async (keyword: string) => {
      const page = await fetchAdCampaignKeywords(campaignId, { keyword, size: 100 });
      setKeywords(page.items);
    },
    [campaignId],
  );

  async function submitBid() {
    const check = validateBidUpdate(bidInput);
    setBidErrors(check.errors);
    if (!check.ok) return;
    setBusy(true);
    setError('');
    try {
      const result = await updateAdCampaignBids(campaignId, [
        { groupId: bidInput.groupId, bid: Number(bidInput.bid), reason: bidInput.reason },
      ]);
      setBidMessage(
        `已生效 ${result.applied} 条、被拒 ${result.rejected} 条、跳过 ${result.skipped} 条。${result.note}`,
      );
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitCopy() {
    const check = validateCopyStructure(copyInput);
    setCopyErrors(check.errors);
    if (!check.ok) return;
    setBusy(true);
    setError('');
    try {
      const result = await copyAdCampaignStructure(campaignId, {
        targetShopCode: copyInput.targetShopCode,
        productMode: copyInput.productMode,
        targetListingId: copyInput.targetListingId || undefined,
        newCampaignName: copyInput.newCampaignName || undefined,
        reason: copyInput.reason,
      });
      setCopyResult(result);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return <p className="text-muted-foreground text-sm">正在加载活动详情…</p>;
  }
  if (error && !panorama) {
    return (
      <div className="space-y-2">
        <p className="text-destructive text-sm">加载失败：{error}</p>
        <button
          type="button"
          className="rounded border px-2 py-1 text-sm"
          onClick={() => void load()}
        >
          重试
        </button>
      </div>
    );
  }
  if (!panorama) {
    return <p className="text-muted-foreground text-sm">没有可显示的活动。</p>;
  }

  const campaign = panorama.campaign;
  const curve = panorama.budgetCurve;

  return (
    <main className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-lg font-medium">{campaign.campaignName}</h1>
        <p className="text-muted-foreground text-xs">
          {campaign.platformName} · {campaign.shopName} · 平台侧 id{' '}
          {campaign.externalCampaignId} · 状态 {adStatusLabel(campaign.status)}
        </p>
        <p className="text-muted-foreground text-xs">
          🔴 本地已改、尚未回写平台：
          {campaign.pendingPlatformWriteback ? '是' : '否'}；本地改动时刻{' '}
          {timestampText(campaign.localChangedAt)}；数据截至 {timestampText(campaign.dataAsOf)}
        </p>
        {campaign.note ? <p className="text-muted-foreground text-xs">{campaign.note}</p> : null}
      </header>

      {error ? <p className="text-destructive text-sm">操作失败：{error}</p> : null}

      {/* ================= 基本设置 ================= */}
      <Section title="基本设置">
        <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <Stat label="日预算" value={money(campaign.dailyBudget, campaign.currency)} />
          <Stat label="消耗合计" value={money(campaign.spend, campaign.currency)} />
          <Stat label="销售额合计" value={money(campaign.sales, campaign.currency)} />
          <Stat label="ACOS" value={ratioText(campaign.acos)} />
          <Stat label="ROAS" value={ratioText(campaign.roas, false)} />
          <Stat label="曝光" value={quantity(campaign.impressions)} />
          <Stat label="点击" value={quantity(campaign.clicks)} />
          <Stat label="订单" value={quantity(campaign.orders)} />
          <Stat label="原始平台标记" value={campaign.platformAdType} />
          <Stat label="归一后类型" value={campaign.internalAdType} />
          <Stat label="开始时间" value={timestampText(campaign.startAt)} />
          <Stat label="结束时间" value={timestampText(campaign.endAt)} />
        </dl>
        <p className="text-muted-foreground text-xs">{panorama.note}</p>
      </Section>

      {/* ================= 预算与消耗曲线 ================= */}
      <Section title="预算与消耗曲线">
        <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <Stat label="日预算" value={money(curve.dailyBudget, curve.currency)} />
          <Stat label="区间预算" value={money(curve.windowBudget, curve.currency)} />
          <Stat label="区间天数（有数据的天）" value={String(curve.windowDays)} />
          <Stat label="区间消耗" value={money(curve.totalSpend, curve.currency)} />
          <Stat label="区间销售额" value={money(curve.totalSales, curve.currency)} />
          <Stat label="区间 ACOS" value={ratioText(curve.acos)} />
          <Stat label="区间 ROAS" value={ratioText(curve.roas, false)} />
        </dl>
        {curve.points.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有每日指标 —— 这**不等于**「没有投放」，只是还没有同步到表现数据。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left text-xs">
                <th className="p-2">日期</th>
                <th className="p-2">日预算</th>
                <th className="p-2">消耗</th>
                <th className="p-2">销售额</th>
                <th className="p-2">ACOS</th>
                <th className="p-2">ROAS</th>
                <th className="p-2">预算使用率</th>
              </tr>
            </thead>
            <tbody>
              {curve.points.map((p) => (
                <tr key={p.date} className="border-b">
                  <td className="p-2">{p.date}</td>
                  <td className="p-2">{money(p.dailyBudget, curve.currency)}</td>
                  <td className="p-2">{money(p.spend, curve.currency)}</td>
                  <td className="p-2">{money(p.sales, curve.currency)}</td>
                  <td className={`p-2 ${ratioTone(p.acos)}`}>{ratioText(p.acos)}</td>
                  <td className={`p-2 ${ratioTone(p.roas)}`}>{ratioText(p.roas, false)}</td>
                  <td className={`p-2 ${ratioTone(p.budgetUsageRate)}`}>
                    {ratioText(p.budgetUsageRate)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="text-muted-foreground text-xs">{curve.note}</p>
      </Section>

      {/* ================= 每日表现明细 ================= */}
      <Section title={`每日表现明细（${panorama.dailyMetrics.length} 天）`}>
        {panorama.dailyMetrics.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有每日指标数据。</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left text-xs">
                <th className="p-2">日期</th>
                <th className="p-2">曝光</th>
                <th className="p-2">点击</th>
                <th className="p-2">订单</th>
                <th className="p-2">消耗</th>
                <th className="p-2">销售额</th>
                <th className="p-2">ACOS</th>
                <th className="p-2">ROAS</th>
              </tr>
            </thead>
            <tbody>
              {panorama.dailyMetrics.map((m) => (
                <tr key={m.metricDate} className="border-b">
                  <td className="p-2">{m.metricDate}</td>
                  <td className="p-2">{quantity(m.impressions)}</td>
                  <td className="p-2">{quantity(m.clicks)}</td>
                  <td className="p-2">{quantity(m.orders)}</td>
                  <td className="p-2">{money(m.spend, campaign.currency)}</td>
                  <td className="p-2">{money(m.sales, campaign.currency)}</td>
                  <td className={`p-2 ${ratioTone(m.acos)}`}>{ratioText(m.acos)}</td>
                  <td className={`p-2 ${ratioTone(m.roas)}`}>{ratioText(m.roas, false)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      {/* ================= 广告组 ================= */}
      <Section title={`广告组（${groups.length}）`}>
        <div className="flex items-end gap-2">
          <Field label="筛选">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={groupKeyword}
              onChange={(e) => setGroupKeyword(e.target.value)}
              placeholder="组名或平台侧 id"
            />
          </Field>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            onClick={() => void reloadGroups(groupKeyword)}
          >
            搜索
          </button>
        </div>
        {groups.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有登记广告组结构 —— {panorama.structure.structureNote}
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left text-xs">
                <th className="p-2">广告组</th>
                <th className="p-2">平台侧 id</th>
                <th className="p-2">状态</th>
                <th className="p-2">默认竞价</th>
                <th className="p-2">组级日预算</th>
                <th className="p-2">定向</th>
                <th className="p-2">商品 / 创意</th>
                <th className="p-2">结构截至</th>
              </tr>
            </thead>
            <tbody>
              {groups.map((g) => (
                <tr key={g.id} className="border-b">
                  <td className="p-2">{g.groupName || '（未命名）'}</td>
                  <td className="p-2">{g.externalGroupId}</td>
                  <td className="p-2">{adStatusLabel(g.status)}</td>
                  <td className="p-2">{money(g.defaultBid, g.bidCurrency)}</td>
                  <td className="p-2">
                    {g.dailyBudget === null ? '继承活动预算' : money(g.dailyBudget, campaign.currency)}
                  </td>
                  <td className="p-2">{g.targetingType || '—'}</td>
                  <td className="p-2">
                    {g.productCount} / {g.creativeCount}
                  </td>
                  <td className="p-2">{timestampText(g.dataAsOf)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      {/* ================= 投放商品 ================= */}
      <Section title={`投放商品（${products.length}）`}>
        {products.length === 0 ? (
          <p className="text-muted-foreground text-sm">这个活动还没有登记投放商品。</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left text-xs">
                <th className="p-2">商品</th>
                <th className="p-2">平台侧 id</th>
                <th className="p-2">所属广告组</th>
                <th className="p-2">listing 状态</th>
              </tr>
            </thead>
            <tbody>
              {products.map((p) => (
                <tr key={p.id} className="border-b">
                  <td className="p-2">{p.title || '（无标题）'}</td>
                  <td className="p-2">{p.externalItemId || '—'}</td>
                  <td className="p-2">{p.groupName || '—'}</td>
                  <td className="p-2">
                    {p.listingStatus ?? '—'} / {p.listingReviewStatus ?? '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      {/* ================= 创意 ================= */}
      <Section title={`创意（${creatives.length}）`}>
        <div className="flex items-end gap-2">
          <Field label="类型">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={creativeType}
              onChange={(e) => {
                setCreativeType(e.target.value);
                void reloadCreatives(e.target.value);
              }}
            >
              <option value="">全部类型</option>
              {(capabilities?.creativeTypes ?? []).map((t) => (
                <option key={t} value={t}>
                  {creativeTypeLabel(t)}
                </option>
              ))}
            </select>
          </Field>
        </div>
        {creatives.length === 0 ? (
          <p className="text-muted-foreground text-sm">这个活动还没有登记创意。</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left text-xs">
                <th className="p-2">创意</th>
                <th className="p-2">平台侧 id</th>
                <th className="p-2">类型</th>
                <th className="p-2">广告组</th>
                <th className="p-2">时长</th>
                <th className="p-2">状态</th>
              </tr>
            </thead>
            <tbody>
              {creatives.map((c) => (
                <tr key={c.id} className="border-b">
                  <td className="p-2">{c.creativeName || '（未命名）'}</td>
                  <td className="p-2">{c.externalCreativeId}</td>
                  <td className="p-2">{creativeTypeLabel(c.creativeType)}</td>
                  <td className="p-2">{c.groupName ?? '活动级'}</td>
                  <td className="p-2">{c.durationSeconds === null ? '—' : `${c.durationSeconds}s`}</td>
                  <td className="p-2">{adStatusLabel(c.status)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      {/* ================= 关键词（读上游 14.1.3.1） ================= */}
      <Section title={`关键词（${keywords.length}，来自 14.1.3.1）`}>
        <div className="flex items-end gap-2">
          <Field label="筛选">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={keywordKeyword}
              onChange={(e) => setKeywordKeyword(e.target.value)}
              placeholder="关键词"
            />
          </Field>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            onClick={() => void reloadKeywords(keywordKeyword)}
          >
            搜索
          </button>
        </div>
        {keywords.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            这个活动还没有关联的关键词（关键词由 14.1.3.1 管理，本页只读）。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left text-xs">
                <th className="p-2">关键词</th>
                <th className="p-2">平台侧 id</th>
                <th className="p-2">原始标记 → 归一类型</th>
                <th className="p-2">竞价</th>
                <th className="p-2">状态</th>
              </tr>
            </thead>
            <tbody>
              {keywords.map((k) => (
                <tr key={k.id} className="border-b">
                  <td className="p-2">{k.keyword}</td>
                  <td className="p-2">{k.externalKeywordId}</td>
                  <td className="p-2">
                    {k.platformAdType} → {k.internalAdType}
                  </td>
                  <td className="p-2">{money(k.bid, k.bidCurrency)}</td>
                  <td className="p-2">{k.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      {/* ================= 平台侧修改历史（显式不可用） ================= */}
      <Section title="修改历史">
        {changeHistory ? (
          <>
            <p className="text-amber-600 text-sm">
              🔴 平台侧修改历史**当前不可得**（{changeHistory.ownerModule}）。
              {changeHistory.impact}
            </p>
            <p className="text-muted-foreground text-xs whitespace-pre-line">
              {changeHistoryText(
                changeHistory.available,
                changeHistory.note,
                changeHistory.localAuditNote,
              )}
            </p>
            {changeHistory.localAudit.length === 0 ? (
              <p className="text-muted-foreground text-sm">本系统里还没有对这个活动做过操作。</p>
            ) : (
              <ul className="space-y-1 text-sm">
                {changeHistory.localAudit.map((log) => (
                  <li key={log.id}>
                    <span className="text-muted-foreground text-xs">
                      {timestampText(log.createdAt)}
                    </span>{' '}
                    {operationLabel(log.operation)} ·{' '}
                    <span className={operationResultTone(log.result)}>
                      {operationResultLabel(log.result)}
                    </span>
                    {log.reason ? ` · ${log.reason}` : ''}
                    {log.message ? ` · ${log.message}` : ''}
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : (
          <p className="text-muted-foreground text-sm">正在加载修改历史…</p>
        )}
      </Section>

      {/* ================= 操作入口 ================= */}
      <Section title="操作入口">
        <ul className="space-y-1 text-sm">
          {panorama.operations.entries.map((entry) => (
            <li key={entry.operation}>
              <span className={entry.available ? 'text-emerald-600' : 'text-muted-foreground'}>
                {entry.available ? '可用' : '未交付'}
              </span>{' '}
              <strong>{entry.label}</strong>
              {entry.source ? ` · 实现方：${entry.source}` : ''}
              {entry.ownerModule ? ` · 责任模块：${entry.ownerModule}` : ''}
              {entry.impact ? ` · 影响：${entry.impact}` : ''}
              {entry.note ? ` · ${entry.note}` : ''}
            </li>
          ))}
        </ul>
        {capabilities ? (
          <p className="text-muted-foreground text-xs whitespace-pre-line">
            {capabilities.boundaryNote}
          </p>
        ) : null}
      </Section>

      {/* ================= 改竞价 ================= */}
      <Section title="改竞价（广告组默认竞价）">
        {capabilities ? (
          <p className="text-muted-foreground text-xs">
            🔴 {capabilities.bidGuardrails.note}
          </p>
        ) : null}
        <div className="flex flex-wrap items-end gap-2">
          <Field label="广告组 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={bidInput.groupId}
              onChange={(e) => setBidInput({ ...bidInput, groupId: e.target.value })}
              placeholder="广告组 id"
            />
          </Field>
          <Field label="目标竞价">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={bidInput.bid}
              onChange={(e) => setBidInput({ ...bidInput, bid: e.target.value })}
              placeholder="例如 1.5000"
            />
          </Field>
          <Field label="理由">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={bidInput.reason}
              onChange={(e) => setBidInput({ ...bidInput, reason: e.target.value })}
              placeholder="写进审计"
            />
          </Field>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            disabled={busy}
            onClick={() => void submitBid()}
          >
            提交
          </button>
        </div>
        {bidErrors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {bidErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        {bidMessage ? <p className="text-muted-foreground text-xs">{bidMessage}</p> : null}
        <p className="text-muted-foreground text-xs">
          🔴 竞价改动只落本地 —— 平台回写队列尚未交付，界面上会标出「尚未回写平台」。
        </p>
      </Section>

      {/* ================= 复制结构 ================= */}
      <Section title="复制结构到新店铺 / 新 ASIN">
        {capabilities ? (
          <p className="text-muted-foreground text-xs whitespace-pre-line">
            {capabilities.copyStructureNote}
          </p>
        ) : null}
        <div className="flex flex-wrap items-end gap-2">
          <Field label="目标店铺编码">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={copyInput.targetShopCode}
              onChange={(e) => setCopyInput({ ...copyInput, targetShopCode: e.target.value })}
              placeholder="例如 IMP-AMZ-1"
            />
          </Field>
          <Field label="投放商品处理方式">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={copyInput.productMode}
              onChange={(e) => setCopyInput({ ...copyInput, productMode: e.target.value })}
            >
              {(capabilities?.productModes ?? ['KEEP_PRODUCTS', 'TARGET_LISTING']).map((m) => (
                <option key={m} value={m}>
                  {productModeLabel(m)}
                </option>
              ))}
            </select>
          </Field>
          {copyInput.productMode === 'TARGET_LISTING' ? (
            <Field label="目标 ASIN（listing id）">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={copyInput.targetListingId}
                onChange={(e) => setCopyInput({ ...copyInput, targetListingId: e.target.value })}
                placeholder="listing id（UUID）"
              />
            </Field>
          ) : null}
          <Field label="新活动名（留空自动生成）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={copyInput.newCampaignName}
              onChange={(e) => setCopyInput({ ...copyInput, newCampaignName: e.target.value })}
            />
          </Field>
          <Field label="理由">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={copyInput.reason}
              onChange={(e) => setCopyInput({ ...copyInput, reason: e.target.value })}
              placeholder="写进审计"
            />
          </Field>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            disabled={busy}
            onClick={() => void submitCopy()}
          >
            复制结构
          </button>
        </div>
        {copyErrors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {copyErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        {copyResult ? (
          <div className="space-y-1 text-sm">
            <p>{copyResultText(copyResult)}</p>
            <p className="text-muted-foreground text-xs">{copyResult.note}</p>
          </div>
        ) : null}
      </Section>
    </main>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded border p-4">
      <h2 className="font-medium">{title}</h2>
      {children}
    </section>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span>{label}</span>
      {children}
    </label>
  );
}
