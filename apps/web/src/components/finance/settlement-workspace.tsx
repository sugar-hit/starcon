'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  FinanceSettlementCapabilities,
  FinanceSettlementCycleRow,
  FinanceSettlementDetail,
  FinanceSettlementDifference,
  FinanceSettlementImportResult,
  FinanceSettlementLineInput,
  FinanceSettlementOptions,
  FinanceSettlementPage,
  FinanceSettlementRow,
  FinanceSettlementShopOption,
} from '../../../../../packages/api-contracts/src/index';
import {
  createSettlement,
  differenceHint,
  fetchSettlement,
  fetchSettlementCapabilities,
  fetchSettlementCycles,
  fetchSettlementDifference,
  fetchSettlementOptions,
  fetchSettlementShops,
  fetchSettlements,
  formatContribution,
  formatDifference,
  formatSettlementAmount,
  formatSettlementDate,
  formatSettlementMoney,
  formatSettlementTime,
  importSettlementBill,
  recordSettlementArrival,
  settlementCategoryLabel,
  settlementCycleTypeLabel,
  settlementEventLabel,
  settlementFactHint,
  settlementSourceLabel,
  settlementStatusLabel,
  settlementStatusTone,
  upsertSettlementCycle,
  voidSettlement,
} from './settlement-model';

/**
 * 12.3.1.1 结算单。
 *
 * <p>蓝图 12-finance.md：「管理平台结算单：结算周期、店铺、平台账单汇总（销售额、退款、佣金、FBA 费用、
 * 广告费、赔付、其他调整）、结算金额、到账状态。」输出：「结算单 + 汇总项 + 明细行 + 与我方账的差额。」
 * 加粗句：「<b>平台账单是财务事实的重要外部来源，需保留原始文件与解析版本。</b>」
 *
 * <p><b>页面围绕三条纪律组织</b>：
 *
 * <ol>
 *   <li><b>结算金额是算出来的</b>：页面只展示服务端算好的结算金额与汇总项，
 *       <b>不提供</b>「手填总额」的入口 —— 汇总项与结算金额在 DB 层就是派生的。</li>
 *   <li><b>原始文件与解析版本看得见</b>：详情里显示账单文件名、内容指纹与第几版解析，
 *       并说明「同一份账单重解析版本会递增」。</li>
 *   <li><b>上游没交付的事就说没交付</b>：平台结算报告自动同步（上游「卷 02 同步」未交付）
 *       没有建单入口；自动对账任务（12.3.2.1）与利润（13）未交付 ⇒ 页面只显示
 *       <b>差额</b>，不提供「跑对账 / 看利润」的按钮。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有结算单」。
 */

const CATEGORIES = [
  'SALES',
  'REFUND',
  'COMMISSION',
  'FBA_FEE',
  'ADVERTISING',
  'COMPENSATION',
  'ADJUSTMENT',
] as const;

const CYCLE_TYPES = ['WEEKLY', 'BIWEEKLY', 'MONTHLY'] as const;

export function SettlementWorkspace() {
  const [caps, setCaps] = useState<FinanceSettlementCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceSettlementOptions | null>(null);
  const [shops, setShops] = useState<FinanceSettlementShopOption[]>([]);
  const [cycles, setCycles] = useState<FinanceSettlementCycleRow[]>([]);
  const [page, setPage] = useState<FinanceSettlementPage | null>(null);
  const [detail, setDetail] = useState<FinanceSettlementDetail | null>(null);
  const [difference, setDifference] = useState<FinanceSettlementDifference | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [status, setStatus] = useState('');
  const [shopId, setShopId] = useState('');
  const [currency, setCurrency] = useState('');
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 导入账单
  const [importSystem, setImportSystem] = useState('amazon');
  const [importFileKey, setImportFileKey] = useState('');
  const [importFormat, setImportFormat] = useState<'CSV' | 'JSON'>('CSV');
  const [importContent, setImportContent] = useState('');
  const [importShop, setImportShop] = useState('');
  const [importStart, setImportStart] = useState('');
  const [importEnd, setImportEnd] = useState('');
  const [importCurrency, setImportCurrency] = useState('USD');

  // 手工登记
  const [manualSystem, setManualSystem] = useState('manual');
  const [manualReference, setManualReference] = useState('');
  const [manualShop, setManualShop] = useState('');
  const [manualStart, setManualStart] = useState('');
  const [manualEnd, setManualEnd] = useState('');
  const [manualCurrency, setManualCurrency] = useState('USD');
  const [manualLines, setManualLines] = useState<FinanceSettlementLineInput[]>([
    {
      category: 'SALES',
      amount: 0,
      quantity: null,
      occurredOn: null,
      externalReference: null,
      description: '',
    },
  ]);

  // 结算周期配置
  const [cycleShop, setCycleShop] = useState('');
  const [cycleType, setCycleType] = useState<(typeof CYCLE_TYPES)[number]>('MONTHLY');
  const [cycleLag, setCycleLag] = useState('7');

  // 到账登记
  const [arrivalAt, setArrivalAt] = useState('');
  const [arrivalAmount, setArrivalAmount] = useState('');

  /**
   * 筛选参数。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const params = {
    ...(status ? { status } : {}),
    ...(shopId ? { shopId } : {}),
    ...(currency ? { currency } : {}),
    ...(keyword ? { keyword } : {}),
    page: currentPage,
    size: 50,
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, p, s, cy] = await Promise.all([
        fetchSettlementCapabilities(),
        fetchSettlementOptions(),
        fetchSettlements(params),
        fetchSettlementShops(),
        fetchSettlementCycles(),
      ]);
      setCaps(c);
      setOptions(o);
      setPage(p);
      setShops(s);
      setCycles(cy);
    } catch (e) {
      setError(e instanceof Error ? e.message : '结算单加载失败');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, shopId, currency, keyword, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 打开详情：顺带拉差额（实时算出来的）。 */
  const openDetail = useCallback(async (id: string) => {
    setActionError('');
    try {
      const [d, diff] = await Promise.all([fetchSettlement(id), fetchSettlementDifference(id)]);
      setDetail(d);
      setDifference(diff);
      setArrivalAmount(d.settlement.settlementAmount.toFixed(4));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '结算单详情加载失败');
    }
  }, []);

  /** 店铺下拉：上游（2 渠道与店铺）没有店铺就建不了单 —— 页面要说清。 */
  const shopOptions = useMemo(() => shops, [shops]);

  async function runImport() {
    setActionError('');
    setNotice('');
    if (!importFileKey.trim()) {
      setActionError('请写明账单身份（平台结算报告号 / 上传文件名）：它决定解析版本号怎么递增');
      return;
    }
    if (!importShop) {
      setActionError('请选择店铺（结算单挂在一家具体的店铺上）');
      return;
    }
    if (!importStart || !importEnd) {
      setActionError('请写明结算周期（开始日与结束日）');
      return;
    }
    if (!importContent.trim()) {
      setActionError('请粘贴账单内容');
      return;
    }
    try {
      const result: FinanceSettlementImportResult = await importSettlementBill({
        source: 'MANUAL_UPLOAD',
        sourceSystem: importSystem.trim(),
        fileKey: importFileKey.trim(),
        format: importFormat,
        content: importContent,
        shopId: importShop,
        periodStart: importStart,
        periodEnd: importEnd,
        currency: importCurrency.trim().toUpperCase(),
        note: null,
      });
      const lines = [
        result.duplicate
          ? '幂等命中：这份账单同样的内容已经导入过，没有新建结算单'
          : `读到 ${result.scanned} 条，写入 ${result.imported} 行，跳过 ${result.skipped} 条（第 ${result.parseVersion} 版解析）`,
      ];
      for (const warning of result.warnings) lines.push(`⚠️ ${warning}`);
      setNotice(lines.join('；'));
      await load();
      if (result.settlement) await openDetail(result.settlement.id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '平台账单导入失败');
    }
  }

  async function runManualCreate() {
    setActionError('');
    setNotice('');
    if (!manualReference.trim()) {
      setActionError('请写明结算报告号：它与来源系统组成幂等键');
      return;
    }
    if (!manualShop) {
      setActionError('请选择店铺');
      return;
    }
    if (!manualStart || !manualEnd) {
      setActionError('请写明结算周期（开始日与结束日）');
      return;
    }
    const lines = manualLines.filter((l) => Number(l.amount) !== 0);
    if (lines.length === 0) {
      setActionError('至少要有一条金额不为 0 的明细行：结算金额由明细行求和得出');
      return;
    }
    try {
      const created = await createSettlement({
        source: 'MANUAL',
        sourceSystem: manualSystem.trim(),
        sourceReference: manualReference.trim(),
        shopId: manualShop,
        periodStart: manualStart,
        periodEnd: manualEnd,
        currency: manualCurrency.trim().toUpperCase(),
        lines: lines.map((l) => ({
          ...l,
          amount: Number(l.amount),
          quantity: l.quantity === null ? null : Number(l.quantity),
        })),
        note: null,
      });
      setNotice(`已手工登记结算单 ${created.settlementNo}`);
      setManualReference('');
      await load();
      await openDetail(created.id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '手工登记结算单失败');
    }
  }

  async function runCycleSave() {
    setActionError('');
    setNotice('');
    if (!cycleShop) {
      setActionError('请选择店铺');
      return;
    }
    try {
      await upsertSettlementCycle({
        shopId: cycleShop,
        cycleType,
        payoutLagDays: Number(cycleLag),
        currency: null,
        status: 'ACTIVE',
        note: null,
      });
      setNotice('已保存结算周期配置（预计到账时间 = 周期结束日 + 到账滞后天数）');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '保存结算周期配置失败');
    }
  }

  async function runArrival() {
    if (!detail) return;
    setActionError('');
    setNotice('');
    if (!arrivalAt) {
      setActionError('请写明实际到账时间：默认填当前时刻会把「上周到的钱」记成今天到');
      return;
    }
    try {
      await recordSettlementArrival(detail.settlement.id, {
        // ⚠️ datetime-local 给的是**浏览器本地时间**（不带偏移），
        //    toISOString() 把它换成带 Z 的 UTC 时刻 —— 这一步就是「不偏 8 小时」的关键。
        arrivedAt: new Date(arrivalAt).toISOString(),
        receivedAmount: Number(arrivalAmount),
        note: null,
      });
      setNotice('已登记到账');
      await openDetail(detail.settlement.id);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记到账失败');
    }
  }

  async function doVoid(row: FinanceSettlementRow) {
    setActionError('');
    setNotice('');
    const reason = window.prompt('作废理由（必填）：', '');
    if (!reason || !reason.trim()) return;
    try {
      await voidSettlement(row.id, reason.trim());
      setNotice(`已作废 ${row.settlementNo} —— 作废是终态，整单冻结`);
      setDetail(null);
      setDifference(null);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '作废失败');
    }
  }

  const hasFilters = Boolean(status || shopId || currency || keyword);

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="结算单"
        description="结算周期 · 店铺 · 平台账单汇总（销售额 / 退款 / 佣金 / FBA 费用 / 广告费 / 赔付 / 其他调整）· 结算金额 · 到账状态"
        actions={
          <div className="flex items-center gap-2">
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
          </div>
        }
      />

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}
      {actionError && (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已完成">
          {notice}
        </Notice>
      )}

      {loading && <p className="text-muted-foreground text-sm">加载中…</p>}

      {!loading && !error && (
        <>
          {/* ---------- 能力声明：上游没交付的事必须说在界面上 ---------- */}
          {caps && caps.capabilities.some((c) => !c.available) && (
            <SectionCard
              title="能力声明"
              description="🔴 标红的这几项**当前不可用** —— 界面不提供它们的入口，也不拿假数据顶替。"
            >
              <ul className="space-y-2 text-sm">
                {caps.capabilities
                  .filter((c) => !c.available)
                  .map((c) => (
                    <li key={`${c.module}-${c.name}`} className="flex items-start gap-2">
                      <Badge tone="danger">不可用</Badge>
                      <span>
                        <b>{c.name}</b>
                        {c.ownerModule ? `（归属模块 ${c.ownerModule}）` : ''}：{c.detail}
                      </span>
                    </li>
                  ))}
              </ul>
            </SectionCard>
          )}

          {/* ---------- 汇总 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard
              label="结算单总数"
              value={page?.total ?? 0}
              unit="单"
              comparisonLabel="按当前筛选条件"
            />
            <StatCard
              label="待到账"
              value={
                (page?.summaryByCurrency ?? []).reduce((sum, s) => sum + s.pendingCount, 0) ?? 0
              }
              unit="单"
              comparisonLabel="待到账 + 部分到账"
            />
            <StatCard
              label="币种数"
              value={page?.summaryByCurrency.length ?? 0}
              unit="种"
              comparisonLabel="不同币种不合并"
            />
            <StatCard
              label="在册明细行"
              value={detail ? detail.lines.length : 0}
              unit="行"
              comparisonLabel="当前打开的结算单"
            />
          </div>

          {/* ---------- 汇总（按币种分开） ---------- */}
          <SectionCard
            title="结算金额汇总"
            description="⚠️ **作废与被新版本取代的单不计入**。不同币种**不**合并。"
          >
            {!page || page.summaryByCurrency.length === 0 ? (
              <EmptyState
                title="还没有在册的结算单"
                description="上传一份平台账单或手工登记一张结算单后，这里会按币种显示结算金额。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">币种</th>
                    <th className="py-2">结算金额</th>
                    <th className="py-2">已到账金额</th>
                    <th className="py-2">单数</th>
                    <th className="py-2">其中待到账</th>
                  </tr>
                </thead>
                <tbody>
                  {page.summaryByCurrency.map((s) => (
                    <tr key={s.currency} className="border-border border-t">
                      <td className="py-2 font-medium">{s.currency}</td>
                      <td className="py-2 font-medium">
                        {formatSettlementMoney(s.settlementAmount, s.currency)}
                      </td>
                      <td className="py-2">
                        {formatSettlementMoney(s.receivedAmount, s.currency)}
                      </td>
                      <td className="py-2">{s.count}</td>
                      <td className="py-2">{s.pendingCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 平台账单导入 ---------- */}
          <SectionCard
            title="上传平台账单"
            description="CSV / JSON 真解析、真入库。⚠️ 文件级错误整体失败；行级错误只跳过那一行并逐条说明 —— 告警会原样显示在这里。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <Input
                placeholder="来源系统（amazon / shopify …）"
                value={importSystem}
                onChange={(e) => setImportSystem(e.target.value)}
              />
              <Input
                placeholder="账单身份：平台结算报告号 / 上传文件名"
                value={importFileKey}
                onChange={(e) => setImportFileKey(e.target.value)}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={importFormat}
                onChange={(e) => setImportFormat(e.target.value as 'CSV' | 'JSON')}
              >
                <option value="CSV">CSV</option>
                <option value="JSON">JSON</option>
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={importShop}
                onChange={(e) => setImportShop(e.target.value)}
              >
                <option value="">选择店铺</option>
                {shopOptions.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}（{s.platformCode}）
                  </option>
                ))}
              </select>
              <Input
                type="date"
                placeholder="周期开始日"
                value={importStart}
                onChange={(e) => setImportStart(e.target.value)}
              />
              <Input
                type="date"
                placeholder="周期结束日"
                value={importEnd}
                onChange={(e) => setImportEnd(e.target.value)}
              />
              <Input
                placeholder="币种（USD）"
                value={importCurrency}
                onChange={(e) => setImportCurrency(e.target.value)}
              />
            </div>
            <textarea
              className="border-border bg-background mt-3 h-40 w-full rounded-md border p-2 font-mono text-xs"
              placeholder={
                importFormat === 'CSV'
                  ? 'category,amount,currency,quantity,occurred_on,external_reference,description\nSALES,1000.00,USD,10,2026-10-01,ORDER-1,销售额'
                  : '{"rows":[{"category":"SALES","amount":"1000.00","currency":"USD"}]}'
              }
              value={importContent}
              onChange={(e) => setImportContent(e.target.value)}
            />
            <div className="mt-3 flex items-center gap-2">
              <Button onClick={() => void runImport()}>导入账单</Button>
              <span className="text-muted-foreground text-xs">
                类别认得 SALES / REFUND / COMMISSION / FBA_FEE / ADVERTISING / COMPENSATION /
                ADJUSTMENT 及其常见别名；不认识的类别**按行拒收**（猜错类别等于把退款记成销售额）。
              </span>
            </div>
          </SectionCard>

          {/* ---------- 手工登记 ---------- */}
          <SectionCard
            title="手工登记结算单"
            description="结算金额由明细行求和得出 —— 页面**不提供**手填总额的入口。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <Input
                placeholder="来源系统"
                value={manualSystem}
                onChange={(e) => setManualSystem(e.target.value)}
              />
              <Input
                placeholder="结算报告号（幂等键）"
                value={manualReference}
                onChange={(e) => setManualReference(e.target.value)}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={manualShop}
                onChange={(e) => setManualShop(e.target.value)}
              >
                <option value="">选择店铺</option>
                {shopOptions.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}（{s.platformCode}）
                  </option>
                ))}
              </select>
              <Input
                type="date"
                value={manualStart}
                onChange={(e) => setManualStart(e.target.value)}
              />
              <Input type="date" value={manualEnd} onChange={(e) => setManualEnd(e.target.value)} />
              <Input
                placeholder="币种（USD）"
                value={manualCurrency}
                onChange={(e) => setManualCurrency(e.target.value)}
              />
            </div>
            <div className="mt-3 space-y-2">
              {manualLines.map((line, index) => (
                <div key={index} className="grid gap-2 md:grid-cols-4">
                  <select
                    className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                    value={line.category}
                    onChange={(e) =>
                      setManualLines((prev) =>
                        prev.map((l, i) =>
                          i === index ? { ...l, category: e.target.value as typeof l.category } : l,
                        ),
                      )
                    }
                  >
                    {CATEGORIES.map((c) => (
                      <option key={c} value={c}>
                        {settlementCategoryLabel(c)}
                      </option>
                    ))}
                  </select>
                  <Input
                    type="number"
                    step="0.0001"
                    placeholder="金额（绝对值，正负由类别决定）"
                    value={String(line.amount ?? '')}
                    onChange={(e) =>
                      setManualLines((prev) =>
                        prev.map((l, i) =>
                          i === index ? { ...l, amount: Number(e.target.value) } : l,
                        ),
                      )
                    }
                  />
                  <Input
                    placeholder="说明"
                    value={line.description ?? ''}
                    onChange={(e) =>
                      setManualLines((prev) =>
                        prev.map((l, i) =>
                          i === index ? { ...l, description: e.target.value } : l,
                        ),
                      )
                    }
                  />
                  <Button
                    variant="secondary"
                    onClick={() => setManualLines((prev) => prev.filter((_, i) => i !== index))}
                  >
                    删除
                  </Button>
                </div>
              ))}
              <Button
                variant="secondary"
                onClick={() =>
                  setManualLines((prev) => [
                    ...prev,
                    {
                      category: 'SALES',
                      amount: 0,
                      quantity: null,
                      occurredOn: null,
                      externalReference: null,
                      description: '',
                    },
                  ])
                }
              >
                加一行明细
              </Button>
            </div>
            <div className="mt-3">
              <Button onClick={() => void runManualCreate()}>登记结算单</Button>
            </div>
          </SectionCard>

          {/* ---------- 结算周期配置 ---------- */}
          <SectionCard
            title="结算周期配置"
            description="一家店一份：预计到账时间 = 结算周期结束日 + 到账滞后天数。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={cycleShop}
                onChange={(e) => setCycleShop(e.target.value)}
              >
                <option value="">选择店铺</option>
                {shopOptions.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}（{s.platformCode}）
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={cycleType}
                onChange={(e) => setCycleType(e.target.value as (typeof CYCLE_TYPES)[number])}
              >
                {CYCLE_TYPES.map((c) => (
                  <option key={c} value={c}>
                    {settlementCycleTypeLabel(c)}
                  </option>
                ))}
              </select>
              <Input
                type="number"
                placeholder="到账滞后天数"
                value={cycleLag}
                onChange={(e) => setCycleLag(e.target.value)}
              />
              <Button onClick={() => void runCycleSave()}>保存配置</Button>
            </div>
            {cycles.length > 0 && (
              <table className="text-copy-13 mt-4 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">店铺</th>
                    <th className="py-2">平台</th>
                    <th className="py-2">周期</th>
                    <th className="py-2">到账滞后（天）</th>
                    <th className="py-2">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {cycles.map((c) => (
                    <tr key={c.id} className="border-border border-t">
                      <td className="py-2">{c.shopCode}</td>
                      <td className="py-2">{c.platformCode}</td>
                      <td className="py-2">{settlementCycleTypeLabel(c.cycleType)}</td>
                      <td className="py-2">{c.payoutLagDays}</td>
                      <td className="py-2">{c.status}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 结算单列表 ---------- */}
          <SectionCard title="结算单" description="点一行看汇总项、明细行、原始文件与差额。">
            <div className="mb-4 grid gap-3 md:grid-cols-4">
              <Input
                placeholder="关键字：单号 / 店铺 / 结算报告号"
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setCurrentPage(0);
                }}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部状态</option>
                {(options?.statuses ?? []).map((s) => (
                  <option key={s} value={s}>
                    {settlementStatusLabel(s)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={shopId}
                onChange={(e) => {
                  setShopId(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部店铺</option>
                {shopOptions.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={currency}
                onChange={(e) => {
                  setCurrency(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部币种</option>
                {(options?.currencies ?? []).map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>

            {!page || page.items.length === 0 ? (
              <EmptyState
                title={hasFilters ? '没有符合条件的结算单' : '还没有结算单'}
                description={
                  hasFilters
                    ? '换个筛选条件再看看。'
                    : '上传一份平台账单或手工登记一张结算单后，这里会出现结算单列表。'
                }
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">结算单号</th>
                    <th className="py-2">店铺</th>
                    <th className="py-2">结算周期</th>
                    <th className="py-2">结算金额</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">预计到账</th>
                    <th className="py-2">实际到账</th>
                    <th className="py-2">解析版本</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <tr key={row.id} className="border-border border-t">
                      <td className="py-2">
                        <button
                          className="underline-offset-2 hover:underline"
                          onClick={() => void openDetail(row.id)}
                        >
                          {row.settlementNo}
                        </button>
                      </td>
                      <td className="py-2">
                        {row.shopName}
                        <span className="text-muted-foreground">（{row.platformCode}）</span>
                      </td>
                      <td className="py-2">
                        {formatSettlementDate(row.periodStart)} ~{' '}
                        {formatSettlementDate(row.periodEnd)}
                      </td>
                      <td className="py-2 font-medium">
                        {formatSettlementMoney(row.settlementAmount, row.currency)}
                      </td>
                      <td className="py-2">
                        <Badge tone={settlementStatusTone(row.status)}>
                          {settlementStatusLabel(row.status)}
                        </Badge>
                      </td>
                      <td className="py-2">{formatSettlementTime(row.expectedAt)}</td>
                      <td className="py-2">{formatSettlementTime(row.arrivedAt)}</td>
                      <td className="py-2">第 {row.parseVersion} 版</td>
                      <td className="py-2">
                        {row.status === 'PENDING' || row.status === 'PARTIAL' ? (
                          <Button variant="secondary" onClick={() => void doVoid(row)}>
                            作废
                          </Button>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 详情 ---------- */}
          {detail && (
            <SectionCard
              title={`结算单详情 · ${detail.settlement.settlementNo}`}
              description={settlementFactHint(detail.settlement)}
            >
              <div className="grid gap-4 md:grid-cols-2">
                <div>
                  <h3 className="mb-2 text-sm font-medium">结算汇总项</h3>
                  <p className="text-muted-foreground mb-2 text-xs">
                    金额是<b>对结算金额的贡献</b>（正=增加、负=减少），各行之和 = 结算金额。
                  </p>
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2">类别</th>
                        <th className="py-2">贡献</th>
                        <th className="py-2">笔数</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.summary.map((s) => (
                        <tr key={s.id} className="border-border border-t">
                          <td className="py-2">{settlementCategoryLabel(s.category)}</td>
                          <td className="py-2">{formatContribution(s.amount)}</td>
                          <td className="py-2">{s.lineCount}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <p className="mt-2 text-sm font-medium">
                    结算金额：
                    {formatSettlementMoney(
                      detail.settlement.settlementAmount,
                      detail.settlement.currency,
                    )}
                  </p>
                </div>

                <div>
                  <h3 className="mb-2 text-sm font-medium">原始文件与解析版本</h3>
                  {detail.sourceFile ? (
                    <div className="text-copy-13 space-y-1">
                      <p>
                        账单身份：<b>{detail.sourceFile.fileKey}</b>
                      </p>
                      <p>
                        第 <b>{detail.sourceFile.parseVersion}</b> 版解析 · 状态{' '}
                        {detail.sourceFile.parseStatus} · {detail.sourceFile.byteSize} 字节
                      </p>
                      <p className="text-muted-foreground break-all text-xs">
                        内容指纹（sha256）：{detail.sourceFile.contentHash}
                      </p>
                      <p className="text-muted-foreground text-xs">
                        解析时刻：{formatSettlementTime(detail.sourceFile.parsedAt)}
                      </p>
                      {detail.sourceFile.parseWarnings.length > 0 && (
                        <ul className="text-xs">
                          {detail.sourceFile.parseWarnings.map((w) => (
                            <li key={w}>⚠️ {w}</li>
                          ))}
                        </ul>
                      )}
                    </div>
                  ) : (
                    <p className="text-muted-foreground text-xs">
                      这张结算单不是从文件导入的（手工登记），没有原始文件留痕。
                    </p>
                  )}
                </div>
              </div>

              <h3 className="mb-2 mt-4 text-sm font-medium">结算明细行</h3>
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">#</th>
                    <th className="py-2">类别</th>
                    <th className="py-2">账单原值</th>
                    <th className="py-2">贡献</th>
                    <th className="py-2">数量</th>
                    <th className="py-2">日期</th>
                    <th className="py-2">外部流水号</th>
                    <th className="py-2">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.lines.map((l) => (
                    <tr key={l.id} className="border-border border-t">
                      <td className="py-2">{l.lineNo}</td>
                      <td className="py-2">{settlementCategoryLabel(l.category)}</td>
                      <td className="py-2">{formatSettlementAmount(l.rawAmount)}</td>
                      <td className="py-2">{formatContribution(l.amount)}</td>
                      <td className="py-2">{l.quantity === null ? '—' : l.quantity.toFixed(6)}</td>
                      <td className="py-2">{formatSettlementDate(l.occurredOn)}</td>
                      <td className="py-2">{l.externalReference ?? '—'}</td>
                      <td className="py-2">{l.description}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              {/* ---------- 与我方账的差额 ---------- */}
              {difference && (
                <>
                  <h3 className="mb-2 mt-4 text-sm font-medium">与我方账的差额</h3>
                  <p className="text-muted-foreground mb-2 text-xs">{differenceHint(difference)}</p>
                  <div className="grid gap-2 md:grid-cols-4">
                    <StatCard
                      label="平台结算金额"
                      value={formatSettlementMoney(
                        difference.platformSettlementAmount,
                        difference.currency,
                      )}
                    />
                    <StatCard
                      label="我方应收未核销"
                      value={formatSettlementMoney(
                        difference.receivableOpenAmount,
                        difference.currency,
                      )}
                    />
                    <StatCard
                      label="差额"
                      value={formatDifference(
                        difference.differenceVsReceivable,
                        difference.currency,
                      )}
                    />
                    <StatCard
                      label="按结算单号关联的收款"
                      value={`${difference.linkedReceiptCount} 笔`}
                    />
                  </div>
                  {difference.receivableBySource.length > 0 && (
                    <table className="text-copy-13 mt-3 w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-2">我方应收来源</th>
                          <th className="py-2">笔数</th>
                          <th className="py-2">应收总额</th>
                          <th className="py-2">未核销余额</th>
                        </tr>
                      </thead>
                      <tbody>
                        {difference.receivableBySource.map((r) => (
                          <tr key={r.source} className="border-border border-t">
                            <td className="py-2">{r.source}</td>
                            <td className="py-2">{r.count}</td>
                            <td className="py-2">{formatSettlementAmount(r.amount)}</td>
                            <td className="py-2">{formatSettlementAmount(r.openAmount)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                  <Notice tone="warning" title="归属说明">
                    {difference.receiptLinkNote} {difference.reconciliationNote}
                  </Notice>
                </>
              )}

              {/* ---------- 登记到账 ---------- */}
              {(detail.settlement.status === 'PENDING' ||
                detail.settlement.status === 'PARTIAL') && (
                <>
                  <h3 className="mb-2 mt-4 text-sm font-medium">登记到账</h3>
                  <div className="grid gap-3 md:grid-cols-3">
                    <Input
                      type="datetime-local"
                      placeholder="实际到账时间"
                      value={arrivalAt}
                      onChange={(e) => setArrivalAt(e.target.value)}
                    />
                    <Input
                      type="number"
                      step="0.0001"
                      placeholder="实际到账金额"
                      value={arrivalAmount}
                      onChange={(e) => setArrivalAmount(e.target.value)}
                    />
                    <Button onClick={() => void runArrival()}>登记到账</Button>
                  </div>
                </>
              )}

              {/* ---------- 事件流 ---------- */}
              <h3 className="mb-2 mt-4 text-sm font-medium">事件流</h3>
              <ul className="text-copy-13 space-y-1">
                {detail.events.map((e) => (
                  <li key={e.id} className="border-border border-t py-1">
                    {formatSettlementTime(e.occurredAt)} · {settlementEventLabel(e.eventType)}
                    {e.reason ? ` · ${e.reason}` : ''}
                  </li>
                ))}
              </ul>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
