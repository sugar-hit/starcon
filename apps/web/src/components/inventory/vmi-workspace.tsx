'use client';

import { useCallback, useEffect, useState } from 'react';
import { Droplets, RefreshCw, ShieldAlert, Signal } from 'lucide-react';
import { Button, EmptyState, FormField, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  VmiAgreementDetail,
  VmiAgreementPage,
  VmiAuditEntry,
  VmiCapabilities,
  VmiSalePage,
  VmiSettlementPage,
  VmiSignalPage,
  VmiSnapshotPage,
  VmiWaterLevelMonitor,
} from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  acknowledgeVmiSignal,
  activateVmiAgreement,
  auditLabel,
  canFulfil,
  cancelVmiSignal,
  closeVmiAgreement,
  confirmVmiSettlement,
  createVmiSettlement,
  dispatchVmiSignal,
  errorText,
  fetchVmiAgreement,
  fetchVmiAgreements,
  fetchVmiAudit,
  fetchVmiCapabilities,
  fetchVmiSales,
  fetchVmiSettlements,
  fetchVmiSignals,
  fetchVmiSnapshots,
  fetchVmiWaterLevels,
  formatAmount,
  formatCoverageDays,
  formatOwnedAssetValue,
  formatQty,
  fulfilVmiSignal,
  generateVmiSignals,
  isAgreementActive,
  isSettlementLocked,
  isSignalOpen,
  missingStockHint,
  monitorOwnershipCards,
  needsSignal,
  noticeTone,
  ownershipSplitNote,
  projectedHint,
  recordVmiSale,
  recordVmiSnapshot,
  safetyGapText,
  signalTargetHint,
  signalTone,
  stockSourceLabel,
  stockSourceTone,
  suspendVmiAgreement,
  toneClass,
  waterLevelLabel,
  waterLevelTone,
} from './vmi-model';

/**
 * 7.7.2.1 VMI 供应商管理库存工作区。
 *
 * <p>蓝图：「支持供应商管理库存（VMI）：货权属供应商、我方享有可销售权，按约定补货或由系统触发供应商备货；
 * 记录安全水位与补货信号，售出后与供应商对账结算。」
 *
 * <p>这是一个真实工作区：VMI 协议、水位监控、补货信号（受理→下发→到货）、售出事实、
 * 结算对账单都落到 PostgreSQL 16，动作闭环齐全。
 *
 * <p>界面上反复说清三件事，否则使用者一定会误读：
 *
 * <ul>
 *   <li>**可售额与自有货值是两张卡，不是相加**。VMI 货属供应商：能卖（计入可售额），
 *       但不是我的货（不计入自有资产）。自有货值那一栏恒显「—（不计入自有资产）」而不是 0。
 *   <li>**每行的在库量标来源**。系统从账本算的、租户登记的、供应商送的要分清；
 *       两边都查不到时报「查不到来源」并说清后果（按 0 计入水位会误判断货）。
 *   <li>**上游/下游未接入显式报出**，不让��从空列表里猜哪一环没通。
 * </ul>
 */
export function VmiWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [capabilities, setCapabilities] = useState<VmiCapabilities | null>(null);
  const [agreements, setAgreements] = useState<VmiAgreementPage | null>(null);
  const [detail, setDetail] = useState<VmiAgreementDetail | null>(null);
  const [monitor, setMonitor] = useState<VmiWaterLevelMonitor | null>(null);
  const [signals, setSignals] = useState<VmiSignalPage | null>(null);
  const [sales, setSales] = useState<VmiSalePage | null>(null);
  const [settlements, setSettlements] = useState<VmiSettlementPage | null>(null);
  const [snapshots, setSnapshots] = useState<VmiSnapshotPage | null>(null);
  const [audit, setAudit] = useState<VmiAuditEntry[]>([]);

  const [loading, setLoading] = useState(true);
  const [drillLoading, setDrillLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [status, setStatus] = useState('');
  const [mode, setMode] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string>('');

  const [saleLineId, setSaleLineId] = useState('');
  const [saleQty, setSaleQty] = useState('');
  const [saleRef, setSaleRef] = useState('');
  const [periodStart, setPeriodStart] = useState('');
  const [periodEnd, setPeriodEnd] = useState('');
  const [snapshotLineId, setSnapshotLineId] = useState('');
  const [snapshotSource, setSnapshotSource] = useState('TENANT_MAINTAINED');
  const [snapshotQty, setSnapshotQty] = useState('');
  const [fulfilQty, setFulfilQty] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, page1] = await Promise.all([
        fetchVmiCapabilities(),
        fetchVmiAgreements({ status, replenishmentMode: mode, keyword, page, size: 20 }),
      ]);
      setCapabilities(caps);
      setAgreements(page1);
      const first = page1.agreements[0];
      if (!selected && first) setSelected(first.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [status, mode, keyword, page, selected]);

  const loadDrill = useCallback(async () => {
    if (!selected) return;
    setDrillLoading(true);
    try {
      const [d, m, sg, sl, st, sn, au] = await Promise.all([
        fetchVmiAgreement(selected),
        fetchVmiWaterLevels(selected),
        fetchVmiSignals({ agreementId: selected, page: 0, size: 50 }),
        fetchVmiSales(selected, undefined, 0, 50),
        fetchVmiSettlements(selected, undefined, 0, 20),
        fetchVmiSnapshots(selected, undefined, 0, 20),
        fetchVmiAudit(selected),
      ]);
      setDetail(d);
      setMonitor(m);
      setSignals(sg);
      setSales(sl);
      setSettlements(st);
      setSnapshots(sn);
      setAudit(au);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setDrillLoading(false);
    }
  }, [selected]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadDrill();
  }, [loadDrill]);

  const afterMutation = async (message: string) => {
    setSaving(false);
    setNotice(message);
    setError('');
    await loadDrill();
  };

  const run = (work: () => Promise<void>) => {
    setSaving(true);
    setNotice('');
    setError('');
    void work().catch((e) => {
      setSaving(false);
      setError(errorText(e));
    });
  };

  if (!readable) {
    return (
      <EmptyState
        title="没有 VMI 库存的查看权限"
        description="需要 warehouse.inventory.read 才能查看 VMI 协议、水位监控与对账单。"
      />
    );
  }

  const agreement = detail?.agreement ?? null;
  const cards = monitor ? monitorOwnershipCards(monitor) : null;
  const active = agreement ? isAgreementActive(agreement) : false;
  const openSignals = (signals?.signals ?? []).filter(isSignalOpen);

  return (
    <div className="space-y-4">
      <PageHeader
        title="VMI 供应商管理库存"
        description="货权属供应商、我方享有可销售权。可售额计入，自有资产不计入 —— 这两个数必须分开看，界面上没有把它们相加。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="这一步没能完成">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已处理">
          {notice}
        </Notice>
      )}

      {/* ---------------- 口径与未接入环节：先说清规则，再谈数据 ---------------- */}
      {capabilities && (
        <SectionCard title="口径与未接入环节">
          <div className="space-y-2 text-sm">
            <p className="font-medium">{capabilities.separationNote}</p>
            <p className="text-muted-foreground">{capabilities.sellableNote}</p>
            <p className="text-muted-foreground">{capabilities.ownedAssetNote}</p>
            <p className="text-muted-foreground">
              自有货值口径：<span className="font-medium">不存在</span> —— 显示「—（不计入自有资产）」而不是 0。
            </p>
            <p className="text-muted-foreground">{capabilities.availableQuantityNote}</p>
            <p className="text-muted-foreground">{capabilities.salesFactNote}</p>
            <p className="text-muted-foreground">{capabilities.signalHandoffNote}</p>
            <p className="text-muted-foreground">{capabilities.settlementHandoffNote}</p>
          </div>
          {capabilities.unavailable.length > 0 && (
            <div className="mt-3">
              <Notice tone="warning" title="以下环节尚未接入（不是本模块做坏了）">
                <ul className="list-disc space-y-1 pl-5">
                  {capabilities.unavailable.map((u) => (
                    <li key={`${u.capability}-${u.ownerModule}`}>
                      <span className="font-medium">{u.capability}</span>（{u.ownerModule} · {u.ownerService}）：{u.reason}
                      <br />
                      <span className="text-muted-foreground">交接方式：{u.handoff}</span>
                    </li>
                  ))}
                </ul>
              </Notice>
            </div>
          )}
        </SectionCard>
      )}

      {/* ---------------- 协议列表 ---------------- */}
      <SectionCard title="VMI 协议">
        <div className="mb-3 flex flex-wrap items-end gap-2">
          <FormField id="vmi-status" label="状态">
            <select
              className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.agreementStatuses ?? []).map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="vmi-mode" label="补货模式">
            <select
              className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
              value={mode}
              onChange={(e) => {
                setMode(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.replenishmentModes ?? []).map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="vmi-keyword" label="关键字">
            <Input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="协议号 / 供应商 / 备注"
            />
          </FormField>
        </div>

        {loading && <p className="text-muted-foreground text-sm">加载中…</p>}

        {!loading && (agreements?.agreements.length ?? 0) === 0 && (
          <EmptyState
            title="还没有 VMI 协议"
            description="VMI 协议要至少带一行明细（水位、补货倍数、结算价），没有这些就无法监控也无法对账。"
          />
        )}

        {!loading && (agreements?.agreements.length ?? 0) > 0 && (
          <div className="space-y-2">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="py-2">协议号</th>
                  <th className="py-2">供应商</th>
                  <th className="py-2">状态</th>
                  <th className="py-2">补货模式</th>
                  <th className="py-2 text-right">行数</th>
                  <th className="py-2 text-right">累计供货</th>
                  <th className="py-2 text-right">累计已售</th>
                  <th className="py-2 text-right">已确认应付</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {agreements?.agreements.map((row) => (
                  <tr key={row.id} className="border-border border-t">
                    <td className="py-2 font-medium">{row.agreementCode}</td>
                    <td className="py-2">{row.supplierName}</td>
                    <td className="py-2">{row.statusLabel}</td>
                    <td className="py-2">{row.replenishmentModeLabel}</td>
                    <td className="py-2 text-right">{row.lineCount}</td>
                    <td className="py-2 text-right">{formatQty(row.totalSupplied)}</td>
                    <td className="py-2 text-right">{formatQty(row.totalSold)}</td>
                    <td className="py-2 text-right">{formatAmount(row.settledAmount, row.currency)}</td>
                    <td className="py-2 text-right">
                      <Button size="sm" variant="outline" onClick={() => setSelected(row.id)}>
                        {selected === row.id ? '已选中' : '查看'}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="flex items-center justify-between text-sm">
              <span className="text-muted-foreground">
                共 {agreements?.total ?? 0} 条 · 第 {(agreements?.page ?? 0) + 1} 页
              </span>
              <div className="flex gap-2">
                <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
                  上一页
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={!agreements || (page + 1) * 20 >= agreements.total}
                  onClick={() => setPage(page + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 选中协议的明细 ---------------- */}
      {!agreement && !loading && (
        <EmptyState title="选择一份协议" description="选中后这里显示水位监控、补货信号与结算对账单。" />
      )}

      {agreement && (
        <>
          <SectionCard
            title={`${agreement.agreementCode} · ${agreement.supplierName}`}
            extra={
              writable ? (
                <div className="flex flex-wrap gap-2">
                  {agreement.status === 'DRAFT' && (
                    <Button
                      size="sm"
                      disabled={saving}
                      onClick={() =>
                        run(async () => {
                          const next = await activateVmiAgreement(agreement.id);
                          await afterMutation(`协议已启用（${next.statusLabel}）。`);
                        })
                      }
                    >
                      启用协议
                    </Button>
                  )}
                  {active && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={saving}
                      onClick={() =>
                        run(async () => {
                          const next = await suspendVmiAgreement(agreement.id);
                          await afterMutation(`协议已暂停（${next.statusLabel}）。`);
                        })
                      }
                    >
                      暂停
                    </Button>
                  )}
                  {active && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={saving}
                      onClick={() =>
                        run(async () => {
                          await closeVmiAgreement(agreement.id);
                          await afterMutation('协议已关闭，全部明细行被数据库冻结，数量不能再动。');
                        })
                      }
                    >
                      关闭
                    </Button>
                  )}
                </div>
              ) : undefined
            }
          >
            <div className="text-muted-foreground flex flex-wrap gap-4 text-sm">
              <span>供应商：{agreement.supplierName}（{agreement.supplierCode}）</span>
              <span>结算周期：{agreement.settlementCycleLabel}</span>
              <span>补货模式：{agreement.replenishmentModeLabel}</span>
              <span>信号去向：{agreement.signalTargetLabel}</span>
              <span>账套法人：{agreement.legalEntityCode}</span>
            </div>
          </SectionCard>

          {/* ---------------- 水位监控：两张分开的卡片 ---------------- */}
          <SectionCard title="水位监控">
            {drillLoading && !monitor && <p className="text-muted-foreground text-sm">加载中…</p>}
            {monitor && cards && (
              <>
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  {/* 卡 1：VMI 可售 —— 计入可售额 */}
                  <StatCard label="VMI 可售量（计入可售额）" value={cards.sellable} icon={<Droplets className="h-4 w-4" />} />
                  <StatCard label="VMI 可售额" value={cards.sellableValue} />
                  {/* 卡 2：自有货值 —— 恒不计入，显「—」而不是 0 */}
                  <StatCard label="VMI 自有货值（不计入自有资产）" value={cards.owned} icon={<ShieldAlert className="h-4 w-4" />} />
                  <StatCard label="水位不足 / 断货行数" value={`${monitor.belowSafetyLineCount} / ${monitor.stockoutLineCount}`} />
                </div>

                <Notice tone="warning" title="两个口径不可相加">
                  {cards.note}
                </Notice>

                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  <StatCard label="我方自有量（对照，不含 VMI）" value={formatQty(monitor.selfOwnedSplit.selfOwnedQuantity)} />
                  <StatCard label="安全水位合计" value={formatQty(monitor.totalSafetyLevel)} />
                  <StatCard label="建议补货合计" value={formatQty(monitor.totalProjectedQuantity)} icon={<Signal className="h-4 w-4" />} />
                  <StatCard label="未了结信号" value={monitor.openSignalCount} />
                </div>

                {monitor.missingStockSourceLineCount > 0 && (
                  <Notice
                    tone={noticeTone(stockSourceTone('MISSING'))}
                    title={`有 ${monitor.missingStockSourceLineCount} 行查不到在库来源`}
                  >
                    {missingStockHint()}
                  </Notice>
                )}
              </>
            )}
          </SectionCard>

          {/* ---------------- 明细行 ---------------- */}
          <SectionCard title="协议明细与水位">
            {(monitor?.lines.length ?? 0) === 0 && !drillLoading ? (
              <EmptyState title="这份协议还没有明细行" description="至少需要一行 SKU + 货主 + 安全水位 + 结算价。" />
            ) : (
              <div className="space-y-2">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="py-2">SKU</th>
                      <th className="py-2">仓库</th>
                      <th className="py-2">货主</th>
                      <th className="py-2 text-right">安全水位</th>
                      <th className="py-2 text-right">当前可售</th>
                      <th className="py-2">水位状态</th>
                      <th className="py-2">来源</th>
                      <th className="py-2 text-right">建议补货</th>
                      <th className="py-2 text-right">可售额</th>
                      <th className="py-2 text-right">自有货值</th>
                      <th className="py-2 text-right">覆盖率</th>
                    </tr>
                  </thead>
                  <tbody>
                    {monitor?.lines.map((l) => (
                      <tr key={l.id} className="border-border border-t">
                        <td className="py-2">
                          <div className="font-medium">{l.skuCode}</div>
                          <div className="text-muted-foreground">{l.skuName}</div>
                        </td>
                        <td className="py-2">{l.warehouseCode}</td>
                        <td className="py-2">
                          <div>{l.stockOwnerCode}</div>
                          <div className="text-muted-foreground">{l.stockOwnerKind}</div>
                        </td>
                        <td className="py-2 text-right">{formatQty(l.safetyLevel)}</td>
                        <td className="py-2 text-right">{formatQty(l.currentSellableQuantity)}</td>
                        <td className={`py-2 ${toneClass(waterLevelTone(l.waterLevelStatus))}`}>
                          <div className="font-medium">{waterLevelLabel(l.waterLevelStatus, l.waterLevelStatusLabel)}</div>
                          <div className="text-muted-foreground">{safetyGapText(l.safetyGap)}</div>
                        </td>
                        <td className={`py-2 ${toneClass(stockSourceTone(l.stockSource))}`}>
                          {stockSourceLabel(l.stockSource, l.stockSourceLabel)}
                        </td>
                        <td className="py-2 text-right">
                          {needsSignal(l) ? (
                            <>
                              <div className="font-medium">{formatQty(l.projectedQuantity)}</div>
                              <div className="text-muted-foreground text-xs">
                                × 倍数 {formatQty(l.replenishmentMultiple)}
                              </div>
                            </>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                        <td className="py-2 text-right">{formatAmount(l.ownershipSplit.sellableValue, agreement.currency)}</td>
                        <td className="text-muted-foreground py-2 text-right">
                          {formatOwnedAssetValue(l.ownershipSplit.ownedAssetValue)}
                        </td>
                        <td className="py-2 text-right">{formatCoverageDays(l.coverageDays)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {monitor?.lines.filter(needsSignal).map((l) => (
                  <p key={`hint-${l.id}`} className="text-muted-foreground text-xs">
                    {l.skuCode}：{projectedHint(l)}
                    {l.openSignalCode ? `（已有未了结信号 ${l.openSignalCode}，不重复出单）` : ''}
                  </p>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ---------------- 补货信号 ---------------- */}
          <SectionCard
            title="补货信号"
            extra={
              writable && active ? (
                <Button
                  size="sm"
                  disabled={saving}
                  onClick={() =>
                    run(async () => {
                      const result = await generateVmiSignals(agreement.id);
                      const skipped = result.skippedReasons.length > 0 ? ` 跳过：${result.skippedReasons.join('；')}` : '';
                      await afterMutation(`已出 ${result.createdCount} 张补货信号。${skipped}`);
                    })
                  }
                >
                  <Signal className="mr-1.5 h-3.5 w-3.5" />
                  按水位出补货信号
                </Button>
              ) : undefined
            }
          >
            {(signals?.total ?? 0) === 0 && !drillLoading ? (
              <EmptyState title="还没有补货信号" description="水位跌破安全水位时点上面的按钮，系统按补货倍数算建议量。" />
            ) : (
              <div className="space-y-2">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="py-2">信号号</th>
                      <th className="py-2">SKU</th>
                      <th className="py-2">触发原因</th>
                      <th className="py-2 text-right">当前可售</th>
                      <th className="py-2 text-right">安全水位</th>
                      <th className="py-2 text-right">建议量</th>
                      <th className="py-2">去向</th>
                      <th className="py-2">状态</th>
                      <th className="py-2 text-right">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {signals?.signals.map((s) => (
                      <tr key={s.id} className="border-border border-t">
                        <td className="py-2 font-medium">{s.signalCode}</td>
                        <td className="py-2">{s.skuCode}</td>
                        <td className="py-2">{s.triggerReasonLabel}</td>
                        <td className="py-2 text-right">{formatQty(s.currentSellableQuantity)}</td>
                        <td className="py-2 text-right">{formatQty(s.safetyLevel)}</td>
                        <td className="py-2 text-right font-medium">{formatQty(s.suggestedQuantity)}</td>
                        <td className="py-2 text-muted-foreground">{s.signalTargetLabel}</td>
                        <td className={`py-2 ${toneClass(signalTone(s.status))}`}>{s.statusLabel}</td>
                        <td className="py-2 text-right">
                          {writable && (
                            <div className="flex justify-end gap-1">
                              {s.status === 'OPEN' && (
                                <Button
                                  size="sm"
                                  variant="outline"
                                  disabled={saving}
                                  onClick={() =>
                                    run(async () => {
                                      await acknowledgeVmiSignal(s.id);
                                      await afterMutation(`信号 ${s.signalCode} 已受理。`);
                                    })
                                  }
                                >
                                  受理
                                </Button>
                              )}
                              {s.status === 'ACKNOWLEDGED' && (
                                <Button
                                  size="sm"
                                  variant="outline"
                                  disabled={saving}
                                  onClick={() =>
                                    run(async () => {
                                      await dispatchVmiSignal(s.id);
                                      await afterMutation(`信号 ${s.signalCode} 已下发，只写了交接键。`);
                                    })
                                  }
                                >
                                  下发
                                </Button>
                              )}
                              {canFulfil(s) && (
                                <Button
                                  size="sm"
                                  variant="outline"
                                  disabled={saving || !fulfilQty}
                                  onClick={() =>
                                    run(async () => {
                                      await fulfilVmiSignal(s.id, Number(fulfilQty));
                                      setFulfilQty('');
                                      await afterMutation(`信号 ${s.signalCode} 已登记到货，累计供货已累加。`);
                                    })
                                  }
                                >
                                  登记到货
                                </Button>
                              )}
                              {isSignalOpen(s) && (
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  disabled={saving}
                                  onClick={() =>
                                    run(async () => {
                                      await cancelVmiSignal(s.id);
                                      await afterMutation(`信号 ${s.signalCode} 已作废。`);
                                    })
                                  }
                                >
                                  作废
                                </Button>
                              )}
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {writable && <Input value={fulfilQty} onChange={(e) => setFulfilQty(e.target.value)} placeholder="到货数量（先填这里再点「登记到货」）" />}
                <p className="text-muted-foreground text-xs">{agreement.signalTargetLabel}：{signalTargetHint(agreement.signalTarget)}</p>
              </div>
            )}
          </SectionCard>

          {/* ---------------- 售出事实与对账 ---------------- */}
          <SectionCard
            title="售出事实与结算对账"
            extra={
              writable && active ? (
                <Button
                  size="sm"
                  disabled={saving || !periodStart || !periodEnd}
                  onClick={() =>
                    run(async () => {
                      await createVmiSettlement(agreement.id, periodStart, periodEnd);
                      await afterMutation('对账单已生成：收进区间内已售但尚未对账的售出事实。');
                    })
                  }
                >
                  按区间出对账单
                </Button>
              ) : undefined
            }
          >
            <div className="space-y-3">
              <div className="flex flex-wrap items-end gap-2">
                <FormField id="vmi-period-start" label="对账起始日">
                  <Input type="date" value={periodStart} onChange={(e) => setPeriodStart(e.target.value)} />
                </FormField>
                <FormField id="vmi-period-end" label="对账结束日">
                  <Input type="date" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
                </FormField>
              </div>

              <div>
                <h4 className="mb-1 text-sm font-medium">售出事实（对账的唯一来源）</h4>
                {(sales?.total ?? 0) === 0 ? (
                  <p className="text-muted-foreground text-sm">还没有售出记录 —— VMI 是售出才结算，没卖出去就没有应付。</p>
                ) : (
                  <table className="w-full text-sm">
                    <thead className="text-muted-foreground text-left">
                      <tr>
                        <th className="py-2">引用</th>
                        <th className="py-2">SKU</th>
                        <th className="py-2 text-right">售出</th>
                        <th className="py-2">售出时刻</th>
                        <th className="py-2">来源</th>
                        <th className="py-2">对账状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      {sales?.sales.map((s) => (
                        <tr key={s.id} className="border-border border-t">
                          <td className="py-2">{s.saleRef}</td>
                          <td className="py-2">{s.skuCode}</td>
                          <td className="py-2 text-right">{formatQty(s.soldQuantity)}</td>
                          <td className="py-2">{s.occurredAt}</td>
                          <td className="text-muted-foreground py-2">{s.sourceSystem}</td>
                          <td className="py-2">
                            {s.unsettleable ? (
                              <span className="text-warning">尚未对账</span>
                            ) : (
                              <span className="text-muted-foreground">{s.settlementCode}</span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>

              <div>
                <h4 className="mb-1 text-sm font-medium">结算对账单（→ 12 结算）</h4>
                {(settlements?.total ?? 0) === 0 ? (
                  <p className="text-muted-foreground text-sm">还没有对账单。</p>
                ) : (
                  <table className="w-full text-sm">
                    <thead className="text-muted-foreground text-left">
                      <tr>
                        <th className="py-2">对账单号</th>
                        <th className="py-2">区间</th>
                        <th className="py-2 text-right">明细数</th>
                        <th className="py-2 text-right">数量</th>
                        <th className="py-2 text-right">应付（到分）</th>
                        <th className="py-2">状态</th>
                        <th className="py-2 text-right">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {settlements?.settlements.map((s) => (
                        <tr key={s.id} className="border-border border-t">
                          <td className="py-2 font-medium">{s.settlementCode}</td>
                          <td className="py-2">
                            {s.periodStart} ~ {s.periodEnd}
                          </td>
                          <td className="py-2 text-right">{s.lineCount}</td>
                          <td className="py-2 text-right">{formatQty(s.totalQuantity)}</td>
                          <td className="py-2 text-right font-medium">{formatAmount(s.totalAmount, s.currency)}</td>
                          <td className="py-2">{s.statusLabel}</td>
                          <td className="py-2 text-right">
                            {writable && s.status === 'OPEN' && !isSettlementLocked(s) && (
                              <Button
                                size="sm"
                                variant="outline"
                                disabled={saving || s.lineCount === 0}
                                onClick={() =>
                                  run(async () => {
                                    await confirmVmiSettlement(s.id);
                                    await afterMutation(`对账单 ${s.settlementCode} 已确认，应付交 12 结算。`);
                                  })
                                }
                              >
                                确认交 12
                              </Button>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                <p className="text-muted-foreground text-xs">
                  表头应付按 HALF_UP 抹到分，明细金额保留 4 位：明细精确到分以下用于追溯，表头是拿去对账付款的数。
                </p>
              </div>

              {writable && active && (
                <div className="border-control-border space-y-2 rounded-md border p-3">
                  <h4 className="text-sm font-medium">记一笔售出</h4>
                  <div className="flex flex-wrap items-end gap-2">
                    <FormField id="vmi-sale-line" label="明细行">
                      <select
                        className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
                        value={saleLineId}
                        onChange={(e) => setSaleLineId(e.target.value)}
                      >
                        <option value="">选择</option>
                        {monitor?.lines.map((l) => (
                          <option key={l.id} value={l.id}>
                            {l.skuCode}
                          </option>
                        ))}
                      </select>
                    </FormField>
                    <FormField id="vmi-sale-qty" label="售出数量">
                      <Input value={saleQty} onChange={(e) => setSaleQty(e.target.value)} placeholder="数量" />
                    </FormField>
                    <FormField id="vmi-sale-ref" label="销售引用">
                      <Input value={saleRef} onChange={(e) => setSaleRef(e.target.value)} placeholder="幂等键，如订单行号" />
                    </FormField>
                    <Button
                      size="sm"
                      disabled={saving || !saleLineId || !saleQty || !saleRef}
                      onClick={() =>
                        run(async () => {
                          await recordVmiSale(agreement.id, saleLineId, {
                            soldQuantity: Number(saleQty),
                            saleRef,
                            occurredAt: new Date().toISOString(),
                          });
                          setSaleQty('');
                          setSaleRef('');
                          await afterMutation('售出已记录，它是对账的唯一来源。');
                        })
                      }
                    >
                      记录售出
                    </Button>
                  </div>
                  <p className="text-muted-foreground text-xs">
                    累计已售不得超过累计供货 —— 在供应商还没把货送过来之前先记销售会凭空生成一笔应付。
                  </p>
                </div>
              )}
            </div>
          </SectionCard>

          {/* ---------------- 在库观测 ---------------- */}
          <SectionCard title="在库观测">
            <div className="space-y-3">
              <Notice tone="info" title="VMI 货常常在供应商自己的仓">
                那种情况下我方账本里没有桶，只能靠这里登记；来源必须显式标出来，否则财务会把租户报上来的数当成系统算出来的数。
                <span className="block">系统从账本算的（WAREHOUSE_BUCKET）不���受手工登记。</span>
              </Notice>
              {(snapshots?.total ?? 0) === 0 ? (
                <p className="text-muted-foreground text-sm">还没有观测记录。</p>
              ) : (
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="py-2">SKU</th>
                      <th className="py-2">来源</th>
                      <th className="py-2">系统</th>
                      <th className="py-2 text-right">观测在库</th>
                      <th className="py-2">观测时刻</th>
                    </tr>
                  </thead>
                  <tbody>
                    {snapshots?.snapshots.map((n) => (
                      <tr key={n.id} className="border-border border-t">
                        <td className="py-2">{n.skuCode}</td>
                        <td className={`py-2 ${toneClass(stockSourceTone(n.stockSource))}`}>
                          {stockSourceLabel(n.stockSource, n.stockSourceLabel)}
                        </td>
                        <td className="text-muted-foreground py-2">{n.sourceSystem}</td>
                        <td className="py-2 text-right">{formatQty(n.observedQuantity)}</td>
                        <td className="py-2">{n.observedAt}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {writable && active && (
                <div className="flex flex-wrap items-end gap-2">
                  <FormField id="vmi-snap-line" label="明细行">
                    <select
                      className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
                      value={snapshotLineId}
                      onChange={(e) => setSnapshotLineId(e.target.value)}
                    >
                      <option value="">选择</option>
                      {monitor?.lines.map((l) => (
                        <option key={l.id} value={l.id}>
                          {l.skuCode}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField id="vmi-snap-source" label="来源">
                    <select
                      className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
                      value={snapshotSource}
                      onChange={(e) => setSnapshotSource(e.target.value)}
                    >
                      {(capabilities?.stockSources ?? [])
                        .filter((s) => s.value !== 'WAREHOUSE_BUCKET')
                        .map((s) => (
                          <option key={s.value} value={s.value}>
                            {s.label}
                          </option>
                        ))}
                    </select>
                  </FormField>
                  <FormField id="vmi-snap-qty" label="观测在库量">
                    <Input value={snapshotQty} onChange={(e) => setSnapshotQty(e.target.value)} placeholder="数量" />
                  </FormField>
                  <Button
                    size="sm"
                    disabled={saving || !snapshotLineId || !snapshotQty}
                    onClick={() =>
                      run(async () => {
                        await recordVmiSnapshot(agreement.id, {
                          agreementLineId: snapshotLineId,
                          stockSource: snapshotSource,
                          observedAt: new Date().toISOString(),
                          observedQuantity: Number(snapshotQty),
                        });
                        setSnapshotQty('');
                        await afterMutation('在库观测已登记，水位监控会优先用它。');
                      })
                    }
                  >
                    登记观测
                  </Button>
                </div>
              )}
            </div>
          </SectionCard>

          {/* ---------------- 审计 ---------------- */}
          <SectionCard title="审计时间线">
            {audit.length === 0 ? (
              <p className="text-muted-foreground text-sm">暂无审计记录。</p>
            ) : (
              <ul className="space-y-1 text-sm">
                {audit.map((a) => (
                  <li key={a.id} className="border-border border-t py-2">
                    <span className="font-medium">{auditLabel(a.eventType)}</span>
                    <span className="text-muted-foreground"> · {a.occurredAt} · {a.detail}</span>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          {!writable && (
            <Notice tone="info" title="只读身份">
              你有查看权限但没有 warehouse.inventory.update，所有写操作不可用。
            </Notice>
          )}
        </>
      )}
    </div>
  );
}