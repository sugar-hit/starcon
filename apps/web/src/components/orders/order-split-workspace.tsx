'use client';

import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { RefreshCw } from 'lucide-react';
import {
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  STATUS_OPTIONS,
  RESERVATION_STATUS_OPTIONS,
  STRATEGY_OPTIONS,
  WRITEBACK_STATUS_OPTIONS,
  advanceSplitStatus,
  conserved,
  createSplit,
  errorText,
  fetchCapabilities,
  fetchReservationCommands,
  fetchRule,
  fetchSplit,
  fetchSplits,
  fetchStockSnapshots,
  fetchWarehouses,
  fetchWritebackTasks,
  isoText,
  money,
  previewSplit,
  qty,
  recordReservation,
  recordWriteback,
  rejectText,
  rejectTone,
  residualText,
  saveRule,
  splitStatusText,
  strategyText,
  unitStatusText,
  writebackStatusText,
} from './order-split-model';
import type {
  OrderSplitCapabilities,
  SplitReservationCommand,
  SplitReservationPage,
  SplitDetail,
  SplitPreview,
  SplitRule,
  SplitStockSnapshotRow,
  SplitWarehouseRow,
  SplitWritebackPage,
  SplitWritebackTask,
} from './order-split-model';

const PAGE_SIZE = 20;

/**
 * 表单字段外壳。
 *
 * <p>刻意**不用** `@starcon/ui` 的 `FormField`：它要求每个字段都带唯一 `id`
 * 且 children 必须是 `Input`（`React.InputHTMLAttributes`），而本页大量字段是
 * `<select>`（主维度、允许部分先发、状态筛选…），套上去要么类型不过、
 * 要么就得为二十几个下拉框编二十几个 id。这里用同样的 className 渲染
 * label + 子元素，视觉与 `FormField` 一致，但下拉框与输入框共用同一个外壳。
 * （与 9.6.1.1 代发配置同一手法。）
 */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-2">
      <span className="text-label-13 text-foreground block">{label}</span>
      {children}
    </div>
  );
}

const SELECT_CLASS =
  'w-full rounded border border-line bg-surface px-2 py-1 text-sm text-foreground';

/**
 * 9.3.1.1 拆单工作区。
 *
 * <p>蓝图：「把一个订单拆成多个履约单元：按仓库拆、按承运商拆、按发货时效拆（如部分现货先发）、
 * 按平台限制拆。」输出是「多个履约请求 + 库存预占指令 + 发货回传按拆分执行」。
 *
 * <p><b>四个区块的顺序对应「先问能不能拆，再问拆成什么样，最后问发出去没有」</b>：
 *
 * <ol>
 *   <li><b>能力与规则</b>：拆单会真实改变包裹形态、发货时效与逐单元回传口径，
 *       所以「本租户配没配规则」排在最前 —— 没配时下面两块都不可写，
 *       且页面必须说清「为什么不能拆」，而不是给一个禁用按钮了事。
 *       🔴 同一块里把两个**没接通**的事说在前面：审单未接通、本模块不预占上游库存。</li>
 *   <li><b>仓与可用库存分布</b>：拆单的全部依据。7.1.1.3 在 warehouse 独立库不可读，
 *       所以这里是租户可维护的真实登记表（带 stock_source 与 collected_at）。</li>
 *   <li><b>拆单方案</b>：预览（无副作用）与落库走**同一段判定逻辑**，
 *       🔴 金额守恒（分摊 + 尾差 == 订单总额）与「未产生新的销售订单」用三行数字摆出来，
 *       让人能自己验算；逐行逐仓的候选与**落选原因**一起显示。</li>
 *   <li><b>预占指令与发货回传</b>：蓝图输出②③。预占是**指令登记**（不是预占事实），
 *       回传**按拆分执行**、一单元一条。</li>
 * </ol>
 *
 * <p>加载态 / 空态 / 错误态三态齐全：任何一块没取到都不会静默显示成 0 或空数组。
 */
export function OrderSplitWorkspace() {
  const [capabilities, setCapabilities] = useState<OrderSplitCapabilities | null>(null);
  const [rule, setRule] = useState<SplitRule | null>(null);
  const [ruleMissing, setRuleMissing] = useState(false);
  const [warehouses, setWarehouses] = useState<SplitWarehouseRow[] | null>(null);
  const [stock, setStock] = useState<SplitStockSnapshotRow[] | null>(null);
  const [preview, setPreview] = useState<SplitPreview | null>(null);
  const [page, setPage] = useState<Awaited<ReturnType<typeof fetchSplits>> | null>(null);
  const [detail, setDetail] = useState<SplitDetail | null>(null);
  const [reservations, setReservations] = useState<SplitReservationPage | null>(null);
  const [writebacks, setWritebacks] = useState<SplitWritebackPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 拆单请求表单。订单行用「行内新增」而不是 textarea：结构化输入才不会把
  // 「数量 10」和「金额 100」在粘贴时对调行 —— 那种错一旦发生，守恒校验会报一个
  // 看不懂的数，而真正的错误在表单里。
  const [form, setForm] = useState({
    orderRef: '',
    orderNo: '',
    currencyCode: 'USD',
    platformCode: 'AMAZON',
    shopCode: '',
    platformOrderRef: '',
    orderTotalAmount: '',
    recipientName: '',
    recipientPhone: '',
    recipientEmail: '',
    countryCode: 'US',
    addressLine: '',
    lineRef: '',
    skuRef: '',
    skuCode: '',
    skuName: '',
    lineQty: '',
    lineAmount: '',
    note: '',
  });
  const [lines, setLines] = useState<{ ref: string; skuRef: string; qty: string; amount: string }[]>(
    [],
  );
  const [ruleForm, setRuleForm] = useState({
    primaryStrategy: 'WAREHOUSE',
    maxUnitsPerSplit: '8',
    allowPartialFulfillment: true,
    splitByTimeliness: false,
    maxShipByHours: '',
    stockFreshnessHours: '',
    enabled: true,
  });

  const [filters, setFilters] = useState({
    keyword: '',
    status: '',
    strategy: '',
    warehouseRef: '',
  });
  const [pageNo, setPageNo] = useState(0);
  const [resvFilters, setResvFilters] = useState({ status: '' });
  const [wbFilters, setWbFilters] = useState({ status: '' });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const caps = await fetchCapabilities();
      setCapabilities(caps);
      let r: SplitRule | null = null;
      let missing = false;
      try {
        r = await fetchRule();
      } catch (e) {
        // 「没登记过规则」是一个正常状态，不是错误：capabilities.ruleConfigured 已如实报出。
        if (e instanceof Error && /不存在|404/.test(e.message)) missing = true;
        else throw e;
      }
      setRule(r);
      setRuleMissing(missing);
      const [wh, st, p, res, wb] = await Promise.all([
        fetchWarehouses(false),
        fetchStockSnapshots(),
        fetchSplits({ ...filters, page: pageNo, size: PAGE_SIZE }),
        fetchReservationCommands({ ...resvFilters, page: 0, size: PAGE_SIZE }),
        fetchWritebackTasks({ ...wbFilters, page: 0, size: PAGE_SIZE }),
      ]);
      setWarehouses(wh);
      setStock(st);
      setPage(p);
      setReservations(res);
      setWritebacks(wb);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [filters, pageNo, resvFilters, wbFilters]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (id: string) => {
    try {
      setDetail(await fetchSplit(id));
    } catch (e) {
      setActionError(errorText(e));
    }
  }, []);

  const addLine = useCallback(() => {
    setLines((current) => [
      ...current,
      { ref: form.lineRef.trim(), skuRef: form.skuRef.trim(), qty: form.lineQty.trim(), amount: form.lineAmount.trim() },
    ]);
    setForm((f) => ({ ...f, lineRef: '', skuRef: '', lineQty: '', lineAmount: '' }));
  }, [form.lineRef, form.skuRef, form.lineQty, form.lineAmount]);

  const buildInput = useCallback(() => {
    const payload = lines.filter((l) => l.ref && l.qty && l.amount);
    return {
      orderRef: form.orderRef.trim(),
      orderNo: form.orderNo.trim(),
      // 留空 = 先试 sales_order 真值，取不到自动回落请求快照；
      // 显式写了 REQUEST_SNAPSHOT 就是「我知道订单不在本服务可见范围内」。
      orderSource: 'REQUEST_SNAPSHOT',
      currencyCode: form.currencyCode.trim().toUpperCase(),
      recipientName: form.recipientName.trim(),
      recipientPhone: form.recipientPhone.trim(),
      recipientEmail: form.recipientEmail.trim(),
      countryCode: form.countryCode.trim().toUpperCase(),
      addressLine: form.addressLine.trim(),
      platformCode: form.platformCode.trim().toUpperCase(),
      shopCode: form.shopCode.trim(),
      platformOrderRef: form.platformOrderRef.trim(),
      orderTotalAmount: Number(form.orderTotalAmount),
      lines: payload.map((l) => ({
        orderLineRef: l.ref,
        skuRef: l.skuRef,
        skuCode: '',
        skuName: '',
        qty: Number(l.qty),
        amount: Number(l.amount),
      })),
      // 拆分时刻显式给出（后端不接受隐含的服务器当前时间）。
      plannedAt: new Date().toISOString(),
      note: form.note.trim(),
    };
  }, [form, lines]);

  const runPreview = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      setPreview(await previewSplit(buildInput()));
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [buildInput]);

  const runSplit = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      const saved = await createSplit(buildInput());
      setPreview(null);
      await load();
      await openDetail(saved.id);
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [buildInput, load, openDetail]);

  const saveRuleForm = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      await saveRule({
        enabled: ruleForm.enabled,
        splitByWarehouse: true,
        splitByCarrier: true,
        splitByTimeliness: ruleForm.splitByTimeliness,
        splitByPlatformRestriction: true,
        primaryStrategy: ruleForm.primaryStrategy as never,
        maxUnitsPerSplit: Number(ruleForm.maxUnitsPerSplit),
        allowPartialFulfillment: ruleForm.allowPartialFulfillment,
        // ⚠️ 契约把这两个可空字段生成成非空 number（丢了可空性），
        // 而「留空 = 不限」与「填 0」在时效与新鲜度上完全是两回事，必须传 null。
        maxShipByHours: (ruleForm.maxShipByHours.trim() === ''
          ? null
          : Number(ruleForm.maxShipByHours)) as unknown as number,
        stockFreshnessHours: (ruleForm.stockFreshnessHours.trim() === ''
          ? null
          : Number(ruleForm.stockFreshnessHours)) as unknown as number,
      });
      await load();
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [ruleForm, load]);

  const advance = useCallback(
    async (splitId: string, status: 'CONFIRMED' | 'DISPATCHED' | 'CANCELLED', reason = '') => {
      setBusy(true);
      setActionError(null);
      try {
        await advanceSplitStatus(splitId, { status, reason, occurredAt: new Date().toISOString() });
        await load();
        await openDetail(splitId);
      } catch (e) {
        setActionError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load, openDetail],
  );

  const ackReservation = useCallback(
    async (splitId: string, unitId: string, status: 'ACKNOWLEDGED' | 'REJECTED') => {
      const text = window.prompt(
        status === 'ACKNOWLEDGED'
          ? 'warehouse 侧返回的预占号'
          : '下游拒绝原因',
        status === 'ACKNOWLEDGED' ? 'RSV-0001' : '',
      );
      if (text === null) return;
      setBusy(true);
      setActionError(null);
      try {
        await recordReservation(splitId, unitId, {
          status,
          externalReservationRef: status === 'ACKNOWLEDGED' ? text : '',
          rejectReason: status === 'REJECTED' ? text : '',
          occurredAt: new Date().toISOString(),
        });
        await load();
      } catch (e) {
        setActionError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const writeback = useCallback(
    async (splitId: string, unitId: string) => {
      const tracking = window.prompt('物流号（回传成功必填）');
      if (tracking === null || tracking.trim() === '') return;
      setBusy(true);
      setActionError(null);
      try {
        await recordWriteback(splitId, unitId, {
          status: 'CONFIRMED',
          trackingNumber: tracking.trim(),
          carrierCode: '',
          lastError: '',
          occurredAt: new Date().toISOString(),
        });
        await load();
        await openDetail(splitId);
      } catch (e) {
        setActionError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load, openDetail],
  );

  const unavailableDimensions = useMemo(
    () => capabilities?.dimensions.filter((d) => !d.available) ?? [],
    [capabilities],
  );

  if (loading) {
    return (
      <PageHeader
        title="拆单"
        description="9.3.1.1 拆单"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled>
            <RefreshCw size={16} /> 加载中
          </Button>
        }
      />
    );
  }

  if (error) {
    return (
      <PageHeader
        title="拆单"
        description="9.3.1.1 拆单"
        actions={
          <Button variant="secondary" onClick={() => void load()}>
            <RefreshCw size={16} /> 重试
          </Button>
        }
      >
        <Notice tone="danger" title="拆单数据加载失败">
          {error}
        </Notice>
      </PageHeader>
    );
  }

  const canWrite = Boolean(rule) && Boolean(rule?.enabled);
  const totalPages = page ? Math.max(1, Math.ceil(page.total / PAGE_SIZE)) : 1;

  return (
    <PageHeader
      title="拆单"
      description="9.3.1.1 把一个订单拆成多个履约单元：按仓库拆、按承运商拆、按发货时效拆、按平台限制拆。拆单不产生新的销售订单，订单总金额与买家视角不变。"
      actions={
        <Button variant="secondary" onClick={() => void load()} disabled={busy}>
          <RefreshCw size={16} /> 刷新
        </Button>
      }
    >
      {actionError ? <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice> : null}

      {/* ---------------------------------------------------- ① 能力与规则 */}
      <SectionCard title="能力与拆单规则">
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <StatCard label="规则" value={ruleMissing ? '未登记' : rule?.enabled ? '已启用' : '已停用'} />
          <StatCard label="主维度" value={rule ? strategyText(rule.primaryStrategy) : '—'} />
          <StatCard
            label="🔴 预占上游库存"
            value={capabilities?.reservesUpstreamStock ? '是' : '否（本模块只登记指令）'}
          />
          <StatCard
            label="🔴 审单"
            value={capabilities?.orderReviewConnected ? '已接通' : '未接通'}
          />
        </div>

        {capabilities ? (
          <Notice tone="warning" title="请注意">
            🔴 <strong>本模块不预占上游库存</strong>：7 预占（inventory_reservation 等）在
            warehouse 的独立库里，按模块边界纪律不得跨库读写。页面上的预占指令是
            <strong>指令登记</strong>，真实预占要由 warehouse 侧的授权 API 建立。
            订单总金额与买家视角不变，且拆单
            <strong>不产生新的销售订单</strong>（creates_sales_order 由数据库约束恒为 false）。
            {capabilities.notes ? ` ${capabilities.notes}` : ''}
          </Notice>
        ) : null}

        {capabilities?.downstreamGaps ? (
          <Notice tone="info" title="下游缺口">
            {capabilities.downstreamGaps}
          </Notice>
        ) : null}

        {unavailableDimensions.length > 0 ? (
          <div className="grid gap-2">
            {unavailableDimensions.map((d) => (
              <Notice key={d.code} tone="info" title={d.label}>
                <strong>{d.label}</strong>（{d.source}）：{d.unavailableReason}
              </Notice>
            ))}
          </div>
        ) : null}

        {ruleMissing ? (
          <Notice tone="warning" title="请注意">
            本租户尚未登记拆单规则。拆单会真实改变包裹形态、发货时效与逐单元回传口径，
            「没配规则 = 默认全拆」会把一次运营疏忽变成一批错发的货，所以本模块不提供隐含默认值。
          </Notice>
        ) : null}

        <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-6">
          <Field label="主维度">
            <select
              className={SELECT_CLASS}
              value={ruleForm.primaryStrategy}
              onChange={(e) => setRuleForm({ ...ruleForm, primaryStrategy: e.target.value })}
            >
              {STRATEGY_OPTIONS.filter((o) => o.value).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="单元数上限">
            <Input
              value={ruleForm.maxUnitsPerSplit}
              onChange={(e) => setRuleForm({ ...ruleForm, maxUnitsPerSplit: e.target.value })}
            />
          </Field>
          <Field label="允许部分先发">
            <select
              className={SELECT_CLASS}
              value={ruleForm.allowPartialFulfillment ? 'yes' : 'no'}
              onChange={(e) =>
                setRuleForm({ ...ruleForm, allowPartialFulfillment: e.target.value === 'yes' })
              }
            >
              <option value="yes">允许（现货先发）</option>
              <option value="no">不允许（必须整行一次发完）</option>
            </select>
          </Field>
          <Field label="时效档上限（小时）">
            <Input
              value={ruleForm.maxShipByHours}
              placeholder="留空 = 不限"
              onChange={(e) => setRuleForm({ ...ruleForm, maxShipByHours: e.target.value })}
            />
          </Field>
          <Field label="库存新鲜度（小时）">
            <Input
              value={ruleForm.stockFreshnessHours}
              placeholder="留空 = 不限"
              onChange={(e) => setRuleForm({ ...ruleForm, stockFreshnessHours: e.target.value })}
            />
          </Field>
          <Field label="规则启用">
            <select
              className={SELECT_CLASS}
              value={ruleForm.enabled ? 'yes' : 'no'}
              onChange={(e) => setRuleForm({ ...ruleForm, enabled: e.target.value === 'yes' })}
            >
              <option value="yes">启用</option>
              <option value="no">停用（暂停拆单）</option>
            </select>
          </Field>
        </div>
        <div className="flex gap-2">
          <Button onClick={() => void saveRuleForm()} disabled={busy}>
            保存规则
          </Button>
          <Button
            variant="secondary"
            onClick={() => setRuleForm({ ...ruleForm, splitByTimeliness: !ruleForm.splitByTimeliness })}
          >
            {ruleForm.splitByTimeliness ? '关闭按时效拆' : '开启按时效拆'}
          </Button>
        </div>
      </SectionCard>

      {/* -------------------------------------------- ② 仓与可用库存分布 */}
      <SectionCard title="发货仓与可用库存分布">
        <Notice tone="info" title="说明">
          7.1.1.3 可用量在 warehouse 独立库，本模块不可跨库读。这里的分布是租户可维护的
          <strong>真实登记表</strong>，带 stock_source 与 collected_at —— collected_at
          表示「这批数据是什么时候观测到的」，用写入时刻冒充就再也判断不出数据有多旧。
        </Notice>
        {!warehouses || warehouses.length === 0 ? (
          <EmptyState
            title="还没有登记发货仓"
            description="仓未登记时，拆单无法知道这个仓走哪个承运商、承诺什么时效、接不接本单的平台 —— 一个仓都接不了时拆单会被挡下并逐条说明原因。"
          />
        ) : (
          <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-3">
            {warehouses.map((w) => (
              <div key={w.warehouseRef} className="rounded border p-3 text-sm">
                <div className="flex items-center justify-between">
                  <strong>{w.warehouseName || w.warehouseRef}</strong>
                  <span className={w.active ? 'text-xs' : 'text-xs line-through opacity-60'}>
                    {w.active ? '在用' : '已停用'}
                  </span>
                </div>
                <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
                  <dt className="opacity-60">仓引用</dt>
                  <dd>{w.warehouseRef}</dd>
                  <dt className="opacity-60">承运商</dt>
                  <dd>{w.carrierCode || '未登记（按承运商拆时会被落选）'}</dd>
                  <dt className="opacity-60">承诺时效</dt>
                  <dd>
                    {w.shipByHours === null || w.shipByHours === undefined
                      ? '未知（不是 0）'
                      : `${w.shipByHours} 小时`}
                  </dd>
                  <dt className="opacity-60">平台限制</dt>
                  <dd>{w.allowedPlatforms || '不限平台'}</dd>
                  <dt className="opacity-60">货权归属</dt>
                  <dd>{w.legalEntityCode || '未登记'}</dd>
                </dl>
              </div>
            ))}
          </div>
        )}

        {!stock || stock.length === 0 ? (
          <EmptyState
            title="还没有登记可用库存分布"
            description="没有库存分布时所有仓都会以「库存不足」落选，拆单会被挡下。库存分布挂在已登记的仓上。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs opacity-60">
                  <th className="p-2">仓</th>
                  <th className="p-2">SKU</th>
                  <th className="p-2">可用量</th>
                  <th className="p-2">采集时刻</th>
                  <th className="p-2">来源</th>
                </tr>
              </thead>
              <tbody>
                {stock.map((s) => (
                  <tr key={`${s.warehouseRef}-${s.skuRef}`} className="border-t">
                    <td className="p-2">{s.warehouseRef}</td>
                    <td className="p-2">{s.skuRef}</td>
                    <td className="p-2">{qty(s.availableQty)}</td>
                    <td className="p-2">{isoText(s.collectedAt)}</td>
                    <td className="p-2">
                      {s.stockSource === 'MODULE_7_1_1_3' ? `7.1.1.3（${s.sourceReference}）` : '租户维护'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------------ ③ 拆单方案 */}
      <SectionCard title="拆单方案">
        {!canWrite ? (
          <Notice tone="warning" title="请注意">
            {ruleMissing
              ? '请先在上面登记拆单规则：没有规则就没有「按哪个维度拆」的依据，本模块不给隐含默认值。'
              : '拆单规则当前处于停用状态（enabled=false）：先启用再拆单。'}
          </Notice>
        ) : null}

        <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-4">
          <Field label="订单引用 *">
            <Input value={form.orderRef} onChange={(e) => setForm({ ...form, orderRef: e.target.value })} />
          </Field>
          <Field label="我方单号">
            <Input value={form.orderNo} onChange={(e) => setForm({ ...form, orderNo: e.target.value })} />
          </Field>
          <Field label="币种 *">
            <Input
              value={form.currencyCode}
              onChange={(e) => setForm({ ...form, currencyCode: e.target.value })}
            />
          </Field>
          <Field label="🔴 订单总金额 *">
            <Input
              value={form.orderTotalAmount}
              placeholder="拆单全程不变，守恒校验比的就是它"
              onChange={(e) => setForm({ ...form, orderTotalAmount: e.target.value })}
            />
          </Field>
          <Field label="平台码">
            <Input
              value={form.platformCode}
              placeholder="留空 = 非平台单（不建回传任务）"
              onChange={(e) => setForm({ ...form, platformCode: e.target.value })}
            />
          </Field>
          <Field label="店铺码">
            <Input value={form.shopCode} onChange={(e) => setForm({ ...form, shopCode: e.target.value })} />
          </Field>
          <Field label="平台单号">
            <Input
              value={form.platformOrderRef}
              onChange={(e) => setForm({ ...form, platformOrderRef: e.target.value })}
            />
          </Field>
          <Field label="备注">
            <Input value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
          </Field>
          <Field label="收件人">
            <Input
              value={form.recipientName}
              onChange={(e) => setForm({ ...form, recipientName: e.target.value })}
            />
          </Field>
          <Field label="国家码">
            <Input
              value={form.countryCode}
              onChange={(e) => setForm({ ...form, countryCode: e.target.value })}
            />
          </Field>
          <Field label="地址快照">
            <Input
              value={form.addressLine}
              onChange={(e) => setForm({ ...form, addressLine: e.target.value })}
            />
          </Field>
        </div>

        <div className="rounded border p-3">
          <div className="mb-2 text-sm font-medium">订单行快照 *</div>
          <div className="grid gap-2 md:grid-cols-4">
            <Field label="行引用">
              <Input value={form.lineRef} onChange={(e) => setForm({ ...form, lineRef: e.target.value })} />
            </Field>
            <Field label="SKU 引用">
              <Input value={form.skuRef} onChange={(e) => setForm({ ...form, skuRef: e.target.value })} />
            </Field>
            <Field label="数量">
              <Input value={form.lineQty} onChange={(e) => setForm({ ...form, lineQty: e.target.value })} />
            </Field>
            <Field label="行金额">
              <Input
                value={form.lineAmount}
                onChange={(e) => setForm({ ...form, lineAmount: e.target.value })}
              />
            </Field>
          </div>
          <div className="mt-2 flex gap-2">
            <Button variant="secondary" onClick={addLine} disabled={busy}>
              加入订单行
            </Button>
          </div>
          {lines.length > 0 ? (
            <div className="mt-2 overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs opacity-60">
                    <th className="p-2">行引用</th>
                    <th className="p-2">SKU 引用</th>
                    <th className="p-2">数量</th>
                    <th className="p-2">金额</th>
                    <th className="p-2" />
                  </tr>
                </thead>
                <tbody>
                  {lines.map((l, i) => (
                    <tr key={`${l.ref}-${i}`} className="border-t">
                      <td className="p-2">{l.ref}</td>
                      <td className="p-2">{l.skuRef}</td>
                      <td className="p-2">{l.qty}</td>
                      <td className="p-2">{l.amount}</td>
                      <td className="p-2">
                        <Button
                          variant="ghost"
                          onClick={() => setLines(lines.filter((_, index) => index !== i))}
                        >
                          移除
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
        </div>

        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => void runPreview()} disabled={busy || !canWrite}>
            预览拆单（无副作用）
          </Button>
          <Button onClick={() => void runSplit()} disabled={busy || !canWrite}>
            落一次拆单
          </Button>
        </div>

        {preview ? (
          <div className="grid gap-3">
            <Notice
              tone={preview.amountConserved ? 'info' : 'danger'}
              title="🔴 金额守恒"
            >
              🔴 金额守恒：分摊 <strong>{money(preview.allocatedTotal)}</strong> + 尾差{' '}
              <strong>{residualText(preview.residualTotal)}</strong> ={' '}
              <strong>{money(preview.orderTotalAmount)}</strong>
              {preview.amountConserved ? '（与订单总金额一致）' : '（不一致，不可落库）'}；
              未产生新的销售订单：<strong>{preview.createsSalesOrder ? '是' : '否'}</strong>。
              拆分维度：{strategyText(preview.strategy)}；订单内容来源：
              {preview.orderSource === 'SALES_ORDER' ? 'sales_order 真值' : '请求快照'}。
            </Notice>
            {preview.blockedReason ? <Notice tone="warning" title="现在还不能落拆单">
              {preview.blockedReason}
            </Notice> : null}

            {preview.units.length === 0 ? (
              <EmptyState
                title="没有可落库的履约单元"
                description="所有候选仓都落选了，逐条原因在下面的候选表里。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-xs opacity-60">
                      <th className="p-2">单元</th>
                      <th className="p-2">仓</th>
                      <th className="p-2">承运商</th>
                      <th className="p-2">承诺发货</th>
                      <th className="p-2">平台</th>
                      <th className="p-2">行数</th>
                      <th className="p-2">件数</th>
                      <th className="p-2">分摊金额</th>
                    </tr>
                  </thead>
                  <tbody>
                    {preview.units.map((u) => (
                      <tr key={u.unitSeq} className="border-t">
                        <td className="p-2">#{u.unitSeq}</td>
                        <td className="p-2">{u.warehouseRef}</td>
                        <td className="p-2">{u.carrierCode || '—'}</td>
                        <td className="p-2">
                          {u.shipByAt ? isoText(u.shipByAt) : u.shipByHours === null || u.shipByHours === undefined ? '未知' : `${u.shipByHours}h`}
                        </td>
                        <td className="p-2">{u.platformCode || '—'}</td>
                        <td className="p-2">{u.lineCount}</td>
                        <td className="p-2">{qty(u.totalQty)}</td>
                        <td className="p-2">{money(u.allocatedAmount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <div>
              <div className="mb-2 text-sm font-medium">
                逐行逐仓候选与落选原因（落选与选中同等重要）
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-xs opacity-60">
                      <th className="p-2">行引用</th>
                      <th className="p-2">仓</th>
                      <th className="p-2">可用</th>
                      <th className="p-2">需求</th>
                      <th className="p-2">结论</th>
                      <th className="p-2">说明</th>
                    </tr>
                  </thead>
                  <tbody>
                    {preview.candidates.map((c, i) => (
                      <tr key={`${c.orderLineRef}-${c.warehouseRef}-${i}`} className="border-t">
                        <td className="p-2">{c.orderLineRef}</td>
                        <td className="p-2">{c.warehouseRef}</td>
                        <td className="p-2">{qty(c.availableQty)}</td>
                        <td className="p-2">{qty(c.demandQty)}</td>
                        <td className="p-2">
                          <span data-tone={rejectTone(c as never)}>{rejectText(c as never)}</span>
                        </td>
                        <td className="p-2 text-xs opacity-70">{c.rejectDetail}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        ) : null}
      </SectionCard>

      {/* ------------------------------------------------------ ④ 拆单列表 */}
      <SectionCard title="拆单记录">
        <div className="grid gap-3 md:grid-cols-4">
          <Field label="关键字">
            <Input
              value={filters.keyword}
              placeholder="拆单号 / 单号 / 订单引用"
              onChange={(e) => setFilters({ ...filters, keyword: e.target.value })}
            />
          </Field>
          <Field label="状态">
            <select
              className={SELECT_CLASS}
              value={filters.status}
              onChange={(e) => {
                setFilters({ ...filters, status: e.target.value });
                setPageNo(0);
              }}
            >
              {STATUS_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="拆分维度">
            <select
              className={SELECT_CLASS}
              value={filters.strategy}
              onChange={(e) => {
                setFilters({ ...filters, strategy: e.target.value });
                setPageNo(0);
              }}
            >
              {STRATEGY_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="仓引用">
            <Input
              value={filters.warehouseRef}
              onChange={(e) => {
                setFilters({ ...filters, warehouseRef: e.target.value });
                setPageNo(0);
              }}
            />
          </Field>
        </div>

        {!page || page.items.length === 0 ? (
          <EmptyState title="还没有拆单记录" description="上面登记好仓与库存分布，落一次拆单就会出现在这里。" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs opacity-60">
                  <th className="p-2">拆单号</th>
                  <th className="p-2">状态</th>
                  <th className="p-2">订单</th>
                  <th className="p-2">维度</th>
                  <th className="p-2">单元</th>
                  <th className="p-2">🔴 订单总额</th>
                  <th className="p-2">分摊 + 尾差</th>
                  <th className="p-2">守恒</th>
                  <th className="p-2" />
                </tr>
              </thead>
              <tbody>
                {page.items.map((s) => {
                  const ok = conserved(s);
                  return (
                    <tr key={s.id} className="border-t">
                      <td className="p-2">{s.splitNo}</td>
                      <td className="p-2">{splitStatusText(s.status)}</td>
                      <td className="p-2">
                        {s.orderNo || s.orderRef}
                        <div className="text-xs opacity-60">{s.orderSource}</div>
                      </td>
                      <td className="p-2">{strategyText(s.strategy)}</td>
                      <td className="p-2">{s.unitCount}</td>
                      <td className="p-2">{money(s.orderTotalAmount)}</td>
                      <td className="p-2">
                        {money(s.allocatedAmountTotal)} + {residualText(s.residualAmountTotal)}
                      </td>
                      <td className="p-2">{ok ? '✓' : '✗'}</td>
                      <td className="p-2">
                        <Button variant="ghost" onClick={() => void openDetail(s.id)}>
                          详情
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        <div className="flex items-center justify-between text-xs">
          <span>
            共 {page?.total ?? 0} 条 · 第 {pageNo + 1} / {totalPages} 页 · 已下发{' '}
            {page?.dispatchedCount ?? 0} 单
          </span>
          <div className="flex gap-2">
            <Button
              variant="secondary"
              disabled={pageNo <= 0}
              onClick={() => setPageNo((n) => Math.max(0, n - 1))}
            >
              上一页
            </Button>
            <Button
              variant="secondary"
              disabled={pageNo + 1 >= totalPages}
              onClick={() => setPageNo((n) => n + 1)}
            >
              下一页
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ------------------------------------------------------ ⑤ 拆单详情 */}
      {detail ? (
        <SectionCard title={`拆单详情 ${detail.split.splitNo}`}>
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <StatCard label="状态" value={splitStatusText(detail.split.status)} />
            <StatCard label="拆分维度" value={strategyText(detail.split.strategy)} />
            <StatCard label="履约单元" value={String(detail.split.unitCount)} />
            <StatCard
              label="🔴 产生新销售订单"
              value={detail.createsSalesOrder ? '是' : '否'}
            />
          </div>
          <Notice
            tone={detail.amountConserved ? 'info' : 'danger'}
            title="🔴 金额守恒"
          >
            🔴 金额守恒：分摊 <strong>{money(detail.split.allocatedAmountTotal)}</strong> + 尾差{' '}
            <strong>{residualText(detail.split.residualAmountTotal)}</strong> ={' '}
            <strong>{money(detail.split.orderTotalAmount)}</strong>；订单总额在拆分前后不变。
          </Notice>
          <Notice tone="info" title="读这一页时必须知道的事">
            {detail.notes}
          </Notice>

          <div className="flex gap-2">
            {detail.split.status === 'PLANNED' ? (
              <Button disabled={busy} onClick={() => void advance(detail.split.id, 'CONFIRMED')}>
                确认方案
              </Button>
            ) : null}
            {detail.split.status === 'CONFIRMED' ? (
              <Button disabled={busy} onClick={() => void advance(detail.split.id, 'DISPATCHED')}>
                下发到履约 / 预占
              </Button>
            ) : null}
            {detail.split.status === 'PLANNED' || detail.split.status === 'CONFIRMED' ? (
              <Button
                variant="secondary"
                disabled={busy}
                onClick={() => {
                  const reason = window.prompt('作废原因（必填）');
                  if (reason && reason.trim()) void advance(detail.split.id, 'CANCELLED', reason.trim());
                }}
              >
                作废
              </Button>
            ) : null}
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs opacity-60">
                  <th className="p-2">单元</th>
                  <th className="p-2">状态</th>
                  <th className="p-2">仓</th>
                  <th className="p-2">承运商</th>
                  <th className="p-2">承诺发货</th>
                  <th className="p-2">平台</th>
                  <th className="p-2">件数</th>
                  <th className="p-2">分摊金额</th>
                  <th className="p-2">回传</th>
                </tr>
              </thead>
              <tbody>
                {detail.units.map((u) => {
                  const wb = detail.writebackTasks.find((t: SplitWritebackTask) => t.unitId === u.id);
                  return (
                    <tr key={u.id} className="border-t">
                      <td className="p-2">#{u.unitSeq}</td>
                      <td className="p-2">{unitStatusText(u.unitStatus)}</td>
                      <td className="p-2">{u.warehouseRef}</td>
                      <td className="p-2">{u.carrierCode || '—'}</td>
                      <td className="p-2">{u.shipByAt ? isoText(u.shipByAt) : '—'}</td>
                      <td className="p-2">{u.platformCode || '—'}</td>
                      <td className="p-2">{qty(u.totalQty)}</td>
                      <td className="p-2">{money(u.allocatedAmount)}</td>
                      <td className="p-2">
                        {wb ? (
                          <Button
                            variant="ghost"
                            disabled={busy || wb.status === 'CONFIRMED'}
                            onClick={() => void writeback(detail.split.id, u.id)}
                          >
                            {writebackStatusText(wb.status)}
                            {wb.trackingNumber ? ` · ${wb.trackingNumber}` : ''}
                          </Button>
                        ) : (
                          <span className="text-xs opacity-60">非平台单，无回传任务</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <div>
            <div className="mb-2 text-sm font-medium">单元行级明细（尾差就落在某一行的 residual 上）</div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs opacity-60">
                    <th className="p-2">行引用</th>
                    <th className="p-2">SKU</th>
                    <th className="p-2">切走数量</th>
                    <th className="p-2">分摊金额</th>
                    <th className="p-2">🔴 尾差</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.unitLines.map((l) => (
                    <tr key={l.id} className="border-t">
                      <td className="p-2">{l.orderLineRef}</td>
                      <td className="p-2">{l.skuRef || '—'}</td>
                      <td className="p-2">{qty(l.allocatedQty)}</td>
                      <td className="p-2">{money(l.allocatedAmount)}</td>
                      <td className="p-2">{residualText(l.residualAmount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div>
            <div className="mb-2 text-sm font-medium">逐行逐仓候选依据（落库，可回答「当时为什么这么拆」）</div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs opacity-60">
                    <th className="p-2">行引用</th>
                    <th className="p-2">仓</th>
                    <th className="p-2">可用</th>
                    <th className="p-2">需求</th>
                    <th className="p-2">结论</th>
                    <th className="p-2">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.candidates.map((c) => (
                    <tr key={c.id} className="border-t">
                      <td className="p-2">{c.orderLineRef}</td>
                      <td className="p-2">{c.warehouseRef}</td>
                      <td className="p-2">{qty(c.availableQty)}</td>
                      <td className="p-2">{qty(c.demandQty)}</td>
                      <td className="p-2">
                        <span data-tone={rejectTone(c)}>{rejectText(c)}</span>
                      </td>
                      <td className="p-2 text-xs opacity-70">{c.rejectDetail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div>
            <div className="mb-2 text-sm font-medium">
              本单的库存预占指令（🔴 指令登记，不是预占事实）
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs opacity-60">
                    <th className="p-2">仓</th>
                    <th className="p-2">SKU</th>
                    <th className="p-2">数量</th>
                    <th className="p-2">目标服务</th>
                    <th className="p-2">状态</th>
                    <th className="p-2">回执号</th>
                    <th className="p-2">🔴 已预占上游库存</th>
                    <th className="p-2" />
                  </tr>
                </thead>
                <tbody>
                  {detail.reservations.map((r: SplitReservationCommand) => (
                    <tr key={r.id} className="border-t">
                      <td className="p-2">{r.warehouseRef}</td>
                      <td className="p-2">{r.skuRef}</td>
                      <td className="p-2">{qty(r.reservedQty)}</td>
                      <td className="p-2">{r.targetService}</td>
                      <td className="p-2">{r.commandStatusLabel}</td>
                      <td className="p-2">{r.externalReservationRef || '—'}</td>
                      <td className="p-2">{r.upstreamReserved ? '是' : '否'}</td>
                      <td className="p-2">
                        {r.commandStatus === 'REQUESTED' ? (
                          <>
                            <Button
                              variant="ghost"
                              disabled={busy}
                              onClick={() =>
                                void ackReservation(detail.split.id, r.unitId, 'ACKNOWLEDGED')
                              }
                            >
                              登记已预占
                            </Button>
                            <Button
                              variant="ghost"
                              disabled={busy}
                              onClick={() =>
                                void ackReservation(detail.split.id, r.unitId, 'REJECTED')
                              }
                            >
                              登记拒绝
                            </Button>
                          </>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div>
            <div className="mb-2 text-sm font-medium">事件流（append-only，不可改写）</div>
            <ul className="grid gap-1 text-sm">
              {detail.events.map((e) => (
                <li key={e.id} className="border-t py-1">
                  <span className="opacity-60">{isoText(e.occurredAt)}</span> · {e.eventType}
                  {e.fromStatus ? ` (${e.fromStatus} → ${e.toStatus})` : ''} · {e.detail}
                </li>
              ))}
            </ul>
          </div>
        </SectionCard>
      ) : null}

      {/* ------------------------------------------ ⑥ 预占指令与回传清单 */}
      <SectionCard title="预占指令与发货回传（全部拆单）">
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="预占指令状态">
            <select
              className={SELECT_CLASS}
              value={resvFilters.status}
              onChange={(e) => setResvFilters({ status: e.target.value })}
            >
              {RESERVATION_STATUS_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="回传任务状态">
            <select
              className={SELECT_CLASS}
              value={wbFilters.status}
              onChange={(e) => setWbFilters({ status: e.target.value })}
            >
              {WRITEBACK_STATUS_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <div className="grid gap-3 md:grid-cols-2">
          <div>
            <div className="mb-2 text-sm font-medium">
              预占指令（共 {reservations?.total ?? 0} 条 · 待认领{' '}
              {reservations?.requestedCount ?? 0} · 已预占 {reservations?.acknowledgedCount ?? 0} ·
              被拒 {reservations?.rejectedCount ?? 0}）
            </div>
            {!reservations || reservations.items.length === 0 ? (
              <EmptyState title="没有预占指令" description="落一次拆单就会按履约单元自动登记预占指令。" />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-xs opacity-60">
                      <th className="p-2">仓</th>
                      <th className="p-2">SKU</th>
                      <th className="p-2">数量</th>
                      <th className="p-2">状态</th>
                      <th className="p-2">回执号</th>
                    </tr>
                  </thead>
                  <tbody>
                    {reservations.items.map((r) => (
                      <tr key={r.id} className="border-t">
                        <td className="p-2">{r.warehouseRef}</td>
                        <td className="p-2">{r.skuRef}</td>
                        <td className="p-2">{qty(r.reservedQty)}</td>
                        <td className="p-2">{r.commandStatusLabel}</td>
                        <td className="p-2">{r.externalReservationRef || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div>
            <div className="mb-2 text-sm font-medium">
              发货回传（共 {writebacks?.total ?? 0} 条 · 待回传 {writebacks?.pendingCount ?? 0} ·
              被拒 {writebacks?.rejectedCount ?? 0} · 待查证{' '}
              {writebacks?.pendingVerificationCount ?? 0}）
            </div>
            {!writebacks || writebacks.items.length === 0 ? (
              <EmptyState
                title="没有回传任务"
                description="平台单每拆一个履约单元就有一条回传任务；非平台单不建（没有平台可回传）。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-xs opacity-60">
                      <th className="p-2">平台单号</th>
                      <th className="p-2">物流号</th>
                      <th className="p-2">承运商</th>
                      <th className="p-2">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {writebacks.items.map((t) => (
                      <tr key={t.id} className="border-t">
                        <td className="p-2">{t.platformOrderRef || '—'}</td>
                        <td className="p-2">{t.trackingNumber || '—'}</td>
                        <td className="p-2">{t.carrierCode || '—'}</td>
                        <td className="p-2">{writebackStatusText(t.status)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </SectionCard>
    </PageHeader>
  );
}
