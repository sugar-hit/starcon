'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import {
  ArrowLeftRight,
  CircleAlert,
  Landmark,
  Loader2,
  RefreshCw,
  Scale,
  TriangleAlert,
  Unplug,
  Wallet,
} from 'lucide-react';
import {
  canRetry,
  capabilityTone,
  directProviderCapabilities,
  disableProvider,
  directionLabel,
  executePayment,
  failureClassLabel,
  fetchAttempts,
  fetchAvailability,
  fetchBalances,
  fetchCapabilities,
  fetchFees,
  fetchFlows,
  fetchOperations,
  fetchOverview,
  fetchProviders,
  fetchRates,
  fetchUpstreamFundAccounts,
  fetchUpstreamPaymentOrders,
  flowTypeLabel,
  formatAmount,
  formatMoney,
  formatRate,
  idempotencyHint,
  importBalance,
  importFee,
  operationStatusLabel,
  operationTone,
  operationTypeLabel,
  queryRate,
  receiptProviderNotice,
  registerReceipt,
  retryPayment,
  saveProvider,
  unavailableHint,
  validatePayment,
  validateRate,
  validateReceipt,
  type Tone,
} from './payment-funds-model';
import type {
  FundFlowRow,
  PaymentAttemptRow,
  PaymentBalanceRow,
  PaymentFeeRow,
  PaymentFundsCapabilities,
  PaymentOperationRow,
  PaymentProviderAvailability,
  PaymentProviderRow,
  PaymentRateRow,
  UpstreamFundAccountRow,
  UpstreamPaymentOrderRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.2.2.1「支付与资金」工作区（卷 18 前端入口 `/integrations/connectors/payment-funds`）。
 *
 * <p>🔴 页面上的六条纪律：
 *
 * 1. **未接入就明说**。本仓目前没有任何真实的支付服务商凭据与网络通道，
 *    所以「直连 PayPal / Stripe / PingPong / 连连 / 万里汇」这几格 `available=false`：
 *    页面**不提供**必然 409 的按钮（一个点了必然失败的按钮不如没有），
 *    并写明缺在谁身上、现在能走哪条路。
 * 2. **幂等键是一等公民**。付款执行表单把幂等键放在第一个字段，
 *    按钮旁边写清「同键重复提交只会产生一笔」。
 * 3. **「结果未知」不是「失败」**。UNKNOWN 的操作显示成 warning，
 *    只在**查证明确说服务商侧没有**时才给「重发」按钮 ——
 *    「查不到」不等于「没付出去」，盲目重发可能付两次。
 * 4. **收款登记在服务商未接入时照样在本地台账里**，但要显示「未向服务商登记」。
 * 5. **密钥只存引用**。配置表单里只有「密钥引用」与「末位提示」，
 *    没有任何「粘贴 API Key」的地方。
 * 6. **加载 / 空 / 错误三态齐全**：空态区分「还没登记过」与「筛选无结果」。
 */

type Tab =
  'providers' | 'operations' | 'flows' | 'balances' | 'rates' | 'upstream' | 'capabilities';

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

const PROVIDER_OPTIONS = [
  { value: 'PAYPAL', label: 'PayPal' },
  { value: 'STRIPE', label: 'Stripe' },
  { value: 'PINGPONG', label: 'PingPong' },
  { value: 'LIANLIAN', label: '连连国际' },
  { value: 'WORLDFIRST', label: '万里汇' },
];

interface ProviderForm {
  providerCode: string;
  name: string;
  baseUrl: string;
  credentialRef: string;
  credentialHint: string;
  settlementCurrency: string;
}

const EMPTY_PROVIDER: ProviderForm = {
  providerCode: 'PAYPAL',
  name: '',
  baseUrl: '',
  credentialRef: '',
  credentialHint: '',
  settlementCurrency: 'USD',
};

interface PaymentForm {
  providerId: string;
  idempotencyKey: string;
  reference: string;
  currency: string;
  amount: string;
  counterpartyName: string;
  counterpartyRef: string;
}

const EMPTY_PAYMENT: PaymentForm = {
  providerId: '',
  idempotencyKey: '',
  reference: '',
  currency: 'USD',
  amount: '',
  counterpartyName: '',
  counterpartyRef: '',
};

interface ReceiptForm {
  providerId: string;
  idempotencyKey: string;
  reference: string;
  currency: string;
  amount: string;
  counterpartyName: string;
  providerSerialNo: string;
}

const EMPTY_RECEIPT: ReceiptForm = {
  providerId: '',
  idempotencyKey: '',
  reference: '',
  currency: 'USD',
  amount: '',
  counterpartyName: '',
  providerSerialNo: '',
};

/** 读取错误信息（把 ApiError 的状态码也带出来，运营才知道是 409 未接入还是别的）。 */
function errorText(error: unknown): string {
  if (error && typeof error === 'object' && 'status' in error) {
    const status = (error as { status: number }).status;
    const message = error instanceof Error ? error.message : String(error);
    return status === 409 ? `未接入 / 状态不允许：${message}` : message;
  }
  return error instanceof Error ? error.message : String(error);
}

export function PaymentFundsWorkspace() {
  const [tab, setTab] = useState<Tab>('providers');

  const [capabilities, setCapabilities] = useState<PaymentFundsCapabilities | null>(null);
  const [overview, setOverview] = useState<{ providers: number; unknownOperations: number } | null>(
    null,
  );
  const [providers, setProviders] = useState<PaymentProviderRow[]>([]);
  const [availability, setAvailability] = useState<PaymentProviderAvailability[]>([]);
  const [operations, setOperations] = useState<PaymentOperationRow[]>([]);
  const [flows, setFlows] = useState<FundFlowRow[]>([]);
  const [balances, setBalances] = useState<PaymentBalanceRow[]>([]);
  const [fees, setFees] = useState<PaymentFeeRow[]>([]);
  const [rates, setRates] = useState<PaymentRateRow[]>([]);
  const [upstreamAccounts, setUpstreamAccounts] = useState<UpstreamFundAccountRow[]>([]);
  const [upstreamOrders, setUpstreamOrders] = useState<UpstreamPaymentOrderRow[]>([]);
  const [attempts, setAttempts] = useState<PaymentAttemptRow[]>([]);
  const [selectedOperation, setSelectedOperation] = useState<PaymentOperationRow | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const [providerForm, setProviderForm] = useState<ProviderForm>(EMPTY_PROVIDER);
  const [paymentForm, setPaymentForm] = useState<PaymentForm>(EMPTY_PAYMENT);
  const [receiptForm, setReceiptForm] = useState<ReceiptForm>(EMPTY_RECEIPT);
  const [rateForm, setRateForm] = useState({
    providerId: '',
    baseCurrency: 'USD',
    quoteCurrency: 'CNY',
  });
  const [balanceImport, setBalanceImport] = useState({
    providerId: '',
    currency: 'USD',
    balanceAmount: '',
    availableAmount: '',
    sourceRef: '',
  });
  const [feeImport, setFeeImport] = useState({
    providerId: '',
    feeType: '',
    amount: '',
    currency: 'USD',
    sourceRef: '',
  });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, ov, provs, avail, ops, flowRows, balanceRows, feeRows, rateRows] =
        await Promise.all([
          fetchCapabilities(),
          fetchOverview(),
          fetchProviders(),
          fetchAvailability(),
          fetchOperations(),
          fetchFlows(),
          fetchBalances(),
          fetchFees(),
          fetchRates(),
        ]);
      setCapabilities(caps);
      setOverview({ providers: ov.providers, unknownOperations: ov.unknownOperations });
      setProviders(provs);
      setAvailability(avail);
      setOperations(ops);
      setFlows(flowRows);
      setBalances(balanceRows);
      setFees(feeRows);
      setRates(rateRows);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const loadUpstream = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const [accounts, orders] = await Promise.all([
        fetchUpstreamFundAccounts(),
        fetchUpstreamPaymentOrders(),
      ]);
      setUpstreamAccounts(accounts);
      setUpstreamOrders(orders);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const directCapabilities = useMemo(
    () => (capabilities ? directProviderCapabilities(capabilities.capabilities) : []),
    [capabilities],
  );

  /** 付款执行。幂等键必填 —— 蓝图加粗句「所有第三方资金操作必须有幂等键」。 */
  async function submitPayment() {
    const errors = validatePayment({
      idempotencyKey: paymentForm.idempotencyKey,
      reference: paymentForm.reference,
      currency: paymentForm.currency,
      amount: paymentForm.amount === '' ? null : Number(paymentForm.amount),
      counterpartyName: paymentForm.counterpartyName,
    });
    if (errors.length) {
      setFormError(errors.join('；'));
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      const row = await executePayment({
        providerId: paymentForm.providerId,
        idempotencyKey: paymentForm.idempotencyKey.trim(),
        reference: paymentForm.reference.trim(),
        currency: paymentForm.currency.trim().toUpperCase(),
        amount: Number(paymentForm.amount),
        baseCurrency: null,
        exchangeRate: null,
        baseAmount: null,
        counterpartyName: paymentForm.counterpartyName.trim(),
        counterpartyRef: paymentForm.counterpartyRef.trim(),
        counterpartyType: null,
        note: null,
      });
      setSelectedOperation(row);
      await load();
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 收款登记。 */
  async function submitReceipt() {
    const errors = validateReceipt({
      idempotencyKey: receiptForm.idempotencyKey,
      reference: receiptForm.reference,
      currency: receiptForm.currency,
      amount: receiptForm.amount === '' ? null : Number(receiptForm.amount),
      counterpartyName: receiptForm.counterpartyName,
    });
    if (errors.length) {
      setFormError(errors.join('；'));
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      const row = await registerReceipt({
        providerId: receiptForm.providerId,
        idempotencyKey: receiptForm.idempotencyKey.trim(),
        reference: receiptForm.reference.trim(),
        currency: receiptForm.currency.trim().toUpperCase(),
        amount: Number(receiptForm.amount),
        baseCurrency: null,
        exchangeRate: null,
        baseAmount: null,
        counterpartyName: receiptForm.counterpartyName.trim(),
        counterpartyRef: null,
        counterpartyType: null,
        providerSerialNo: receiptForm.providerSerialNo.trim(),
        occurredAt: null,
        note: null,
      });
      setSelectedOperation(row);
      await load();
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 重发（只在上一次查证确认「服务商侧没有」之后才允许）。 */
  async function submitRetry(operation: PaymentOperationRow) {
    setBusy(true);
    setFormError(null);
    try {
      const row = await retryPayment(operation.id);
      setSelectedOperation(row);
      await load();
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 打开一条操作的查证留痕。 */
  async function openAttempts(operation: PaymentOperationRow) {
    setSelectedOperation(operation);
    setBusy(true);
    try {
      setAttempts(await fetchAttempts(operation.id));
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 登记服务商配置（密钥只存引用）。 */
  async function submitProvider() {
    if (!providerForm.name.trim()) {
      setFormError('配置名不能为空');
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      await saveProvider({
        id: null,
        providerCode: providerForm.providerCode,
        name: providerForm.name.trim(),
        baseUrl: providerForm.baseUrl.trim(),
        credentialRef: providerForm.credentialRef.trim(),
        credentialHint: providerForm.credentialHint.trim(),
        settlementCurrency: providerForm.settlementCurrency.trim().toUpperCase(),
        status: 'ACTIVE',
        timeoutSeconds: 15,
        maxAttempts: 3,
        remark: null,
      });
      setProviderForm(EMPTY_PROVIDER);
      await load();
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 停用服务商配置（**不删**）。 */
  async function submitDisableProvider(provider: PaymentProviderRow) {
    setBusy(true);
    setFormError(null);
    try {
      await disableProvider(provider.id);
      await load();
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 导入余额（服务商账单 / 对账单）。 */
  async function submitBalanceImport() {
    if (!balanceImport.providerId) {
      setFormError('先选一个服务商配置');
      return;
    }
    if (!balanceImport.balanceAmount) {
      setFormError('账面余额不能为空');
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      await importBalance({
        providerId: balanceImport.providerId,
        currency: balanceImport.currency.trim().toUpperCase(),
        balanceAmount: Number(balanceImport.balanceAmount),
        availableAmount:
          balanceImport.availableAmount === '' ? null : Number(balanceImport.availableAmount),
        observedAt: null,
        sourceRef: balanceImport.sourceRef.trim(),
        note: null,
      });
      await load();
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 导入手续费明细。 */
  async function submitFeeImport() {
    if (!feeImport.providerId) {
      setFormError('先选一个服务商配置');
      return;
    }
    if (!feeImport.feeType.trim() || !feeImport.amount) {
      setFormError('费用类型与金额不能为空');
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      await importFee({
        providerId: feeImport.providerId,
        feeType: feeImport.feeType.trim(),
        amount: Number(feeImport.amount),
        currency: feeImport.currency.trim().toUpperCase(),
        chargedAmount: null,
        providerSerialNo: null,
        providerReference: feeImport.sourceRef.trim(),
        occurredAt: null,
        summary: null,
      });
      await load();
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 汇率查询（真读 catalog 汇率事实并落库）。 */
  async function submitRateQuery() {
    const errors = validateRate({
      baseCurrency: rateForm.baseCurrency,
      quoteCurrency: rateForm.quoteCurrency,
    });
    if (errors.length) {
      setFormError(errors.join('；'));
      return;
    }
    if (!rateForm.providerId) {
      setFormError('先选一个服务商配置');
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      await queryRate({
        providerId: rateForm.providerId,
        idempotencyKey: null,
        baseCurrency: rateForm.baseCurrency.trim().toUpperCase(),
        quoteCurrency: rateForm.quoteCurrency.trim().toUpperCase(),
        asOf: null,
      });
      await load();
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const providerSelect = (
    <select
      aria-label="服务商配置"
      className="border-border bg-background h-9 rounded-md border px-2 text-sm"
      value={paymentForm.providerId}
      onChange={(e) => {
        const id = e.target.value;
        setPaymentForm({ ...paymentForm, providerId: id });
        setReceiptForm({ ...receiptForm, providerId: id });
        setRateForm({ ...rateForm, providerId: id });
        setBalanceImport({ ...balanceImport, providerId: id });
        setFeeImport({ ...feeImport, providerId: id });
      }}
    >
      <option value="">（选择服务商配置）</option>
      {providers.map((p) => (
        <option key={p.id} value={p.id}>
          {p.providerLabel} · {p.name}
          {p.status === 'DISABLED' ? '（已停用）' : ''}
        </option>
      ))}
    </select>
  );

  return (
    <div className="space-y-4">
      <PageHeader
        title="支付与资金"
        description="对接 PayPal / Stripe / PingPong / 连连 / 万里汇：余额查询、收款登记、付款执行、汇率查询、手续费明细同步"
        actions={
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            {loading ? (
              <Loader2 className="size-4 animate-spin" />
            ) : (
              <RefreshCw className="size-4" />
            )}
            刷新
          </Button>
        }
      />

      <Segmented
        value={tab}
        onChange={setTab}
        aria-label="支付与资金视图"
        options={[
          { value: 'providers', label: '服务商配置', icon: <Landmark className="size-3.5" /> },
          { value: 'operations', label: '资金操作', icon: <ArrowLeftRight className="size-3.5" /> },
          { value: 'flows', label: '资金流水', icon: <Wallet className="size-3.5" /> },
          { value: 'balances', label: '余额与手续费', icon: <Scale className="size-3.5" /> },
          { value: 'rates', label: '汇率', icon: <Scale className="size-3.5" /> },
          { value: 'upstream', label: '上游只读', icon: <Landmark className="size-3.5" /> },
          { value: 'capabilities', label: '能力声明', icon: <Unplug className="size-3.5" /> },
        ]}
      />

      {error && (
        <Notice tone="danger" title="读取失败">
          {error}
        </Notice>
      )}
      {formError && (
        <Notice tone="warning" title="操作未完成">
          {formError}
        </Notice>
      )}

      {overview && (
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <StatCard label="服务商配置" value={overview.providers} />
          <StatCard label="资金流水" value={flows.length} />
          <StatCard
            label="结果未知的操作"
            value={overview.unknownOperations}
            polarity={overview.unknownOperations > 0 ? 'negative' : 'neutral'}
          />
          <StatCard label="手续费明细" value={fees.length} />
        </div>
      )}

      {loading && (
        <div className="text-muted-foreground flex items-center gap-2 text-sm">
          <Loader2 className="size-4 animate-spin" /> 正在读取…
        </div>
      )}

      {!loading && tab === 'providers' && (
        <div className="grid gap-4 lg:grid-cols-[2fr_1fr]">
          <SectionCard title="服务商连接档案" description="🔴 只存密钥的引用，库里没有任何密钥明文">
            {providers.length === 0 ? (
              <EmptyState
                icon={<Landmark className="size-5" />}
                title="还没登记过服务商配置"
                description="登记一个服务商配置后，才能对��发起资金操作（配置里只有密钥引用，不存明文）"
              />
            ) : (
              <ul className="divide-border divide-y">
                {providers.map((p) => (
                  <li key={p.id} className="flex flex-wrap items-center gap-3 py-3">
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-sm font-medium">{p.name}</span>
                        <Badge tone="neutral">{p.providerLabel}</Badge>
                        <Badge tone={p.status === 'ACTIVE' ? 'success' : 'neutral'}>
                          {p.statusLabel}
                        </Badge>
                        {p.credentialConfigured ? (
                          <Badge tone="success">密钥引用已配置</Badge>
                        ) : (
                          <Badge tone="neutral">未配置密钥引用</Badge>
                        )}
                      </div>
                      <p className="text-muted-foreground mt-1 text-xs">
                        密钥引用：{p.credentialRef || '（未配置）'} · 末位提示：
                        {p.credentialHint || '—'}· 结算币种：{p.settlementCurrency || '—'} · 操作{' '}
                        {p.operationCount} 次 · 流水 {p.fundFlowCount} 笔
                      </p>
                    </div>
                    {p.status === 'ACTIVE' && (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => void submitDisableProvider(p)}
                      >
                        停用
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard title="登记服务商配置" description="密钥只填引用与末位提示，不填明文">
            <div className="space-y-3">
              <label className="block text-xs font-medium">
                服务商
                <select
                  aria-label="服务商"
                  className="border-border bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                  value={providerForm.providerCode}
                  onChange={(e) =>
                    setProviderForm({ ...providerForm, providerCode: e.target.value })
                  }
                >
                  {PROVIDER_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-xs font-medium">
                配置名
                <Input
                  className="mt-1"
                  value={providerForm.name}
                  placeholder="如：美国站 PayPal"
                  onChange={(e) => setProviderForm({ ...providerForm, name: e.target.value })}
                />
              </label>
              <label className="block text-xs font-medium">
                接口地址
                <Input
                  className="mt-1"
                  value={providerForm.baseUrl}
                  placeholder="https://…"
                  onChange={(e) => setProviderForm({ ...providerForm, baseUrl: e.target.value })}
                />
              </label>
              <label className="block text-xs font-medium">
                密钥引用
                <Input
                  className="mt-1"
                  value={providerForm.credentialRef}
                  placeholder="secret://payments/paypal/main"
                  onChange={(e) =>
                    setProviderForm({ ...providerForm, credentialRef: e.target.value })
                  }
                />
              </label>
              <label className="block text-xs font-medium">
                密钥末位提示
                <Input
                  className="mt-1"
                  value={providerForm.credentialHint}
                  placeholder="9f2a"
                  onChange={(e) =>
                    setProviderForm({ ...providerForm, credentialHint: e.target.value })
                  }
                />
              </label>
              <Button disabled={busy} onClick={() => void submitProvider()}>
                保存配置
              </Button>
            </div>
          </SectionCard>
        </div>
      )}

      {!loading && tab === 'providers' && (
        <SectionCard title="通道可用性" description="逐服务商如实答：没接通就写明缺什么、归谁">
          {availability.length === 0 ? (
            <EmptyState title="还没有服务商可答" description="登记配置后这里会显示逐家通道状态" />
          ) : (
            <ul className="space-y-2">
              {availability.map((a) => (
                <li key={a.providerCode} className="flex flex-wrap items-start gap-2">
                  <Badge tone={a.connected ? 'success' : 'neutral'}>
                    {a.providerLabel} · {a.connected ? '已接通' : '未接入'}
                  </Badge>
                  <span className="text-muted-foreground min-w-0 flex-1 text-xs">
                    {a.connected ? '通道可用' : unavailableHint(a)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </SectionCard>
      )}

      {!loading && tab === 'operations' && (
        <div className="space-y-4">
          <div className="grid gap-4 lg:grid-cols-2">
            <SectionCard
              title="付款执行"
              description="🔴 幂等键必填：同键重复提交只会产生一笔，绝不重复扣款"
            >
              <div className="space-y-3">
                {providerSelect}
                <label className="block text-xs font-medium">
                  幂等键
                  <Input
                    className="mt-1"
                    value={paymentForm.idempotencyKey}
                    placeholder="如：po-2026-0001-usd-1"
                    onChange={(e) =>
                      setPaymentForm({ ...paymentForm, idempotencyKey: e.target.value })
                    }
                  />
                </label>
                <label className="block text-xs font-medium">
                  业务单号
                  <Input
                    className="mt-1"
                    value={paymentForm.reference}
                    placeholder="PO-2026-0001"
                    onChange={(e) => setPaymentForm({ ...paymentForm, reference: e.target.value })}
                  />
                </label>
                <div className="grid grid-cols-2 gap-3">
                  <label className="block text-xs font-medium">
                    币种
                    <Input
                      className="mt-1"
                      value={paymentForm.currency}
                      onChange={(e) => setPaymentForm({ ...paymentForm, currency: e.target.value })}
                    />
                  </label>
                  <label className="block text-xs font-medium">
                    金额
                    <Input
                      className="mt-1"
                      value={paymentForm.amount}
                      placeholder="100.00"
                      onChange={(e) => setPaymentForm({ ...paymentForm, amount: e.target.value })}
                    />
                  </label>
                </div>
                <label className="block text-xs font-medium">
                  收款方名称
                  <Input
                    className="mt-1"
                    value={paymentForm.counterpartyName}
                    onChange={(e) =>
                      setPaymentForm({ ...paymentForm, counterpartyName: e.target.value })
                    }
                  />
                </label>
                <label className="block text-xs font-medium">
                  收款方账号
                  <Input
                    className="mt-1"
                    value={paymentForm.counterpartyRef}
                    onChange={(e) =>
                      setPaymentForm({ ...paymentForm, counterpartyRef: e.target.value })
                    }
                  />
                </label>
                <Button disabled={busy} onClick={() => void submitPayment()}>
                  {busy ? <Loader2 className="size-4 animate-spin" /> : null}
                  执行付款
                </Button>
                <p className="text-muted-foreground text-xs">
                  发起超时不会当成失败：系统会先向服务商**查证**这一笔，查证确认已支付就**不重发**；
                  只有查证明确说服务商侧没有，才允许重发。
                </p>
              </div>
            </SectionCard>

            <SectionCard
              title="收款登记"
              description="记进本系统的资金流水台账，并尝试通知服务商侧"
            >
              <div className="space-y-3">
                <label className="block text-xs font-medium">
                  服务商配置
                  <select
                    aria-label="收款服务商配置"
                    className="border-border bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                    value={receiptForm.providerId}
                    onChange={(e) => setReceiptForm({ ...receiptForm, providerId: e.target.value })}
                  >
                    <option value="">（选择服务商配置）</option>
                    {providers.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.providerLabel} · {p.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="block text-xs font-medium">
                  幂等键
                  <Input
                    className="mt-1"
                    value={receiptForm.idempotencyKey}
                    placeholder="如：rc-2026-0001-usd-1"
                    onChange={(e) =>
                      setReceiptForm({ ...receiptForm, idempotencyKey: e.target.value })
                    }
                  />
                </label>
                <label className="block text-xs font-medium">
                  业务单号
                  <Input
                    className="mt-1"
                    value={receiptForm.reference}
                    placeholder="RC-2026-0001"
                    onChange={(e) => setReceiptForm({ ...receiptForm, reference: e.target.value })}
                  />
                </label>
                <div className="grid grid-cols-2 gap-3">
                  <label className="block text-xs font-medium">
                    币种
                    <Input
                      className="mt-1"
                      value={receiptForm.currency}
                      onChange={(e) => setReceiptForm({ ...receiptForm, currency: e.target.value })}
                    />
                  </label>
                  <label className="block text-xs font-medium">
                    金额
                    <Input
                      className="mt-1"
                      value={receiptForm.amount}
                      placeholder="250.00"
                      onChange={(e) => setReceiptForm({ ...receiptForm, amount: e.target.value })}
                    />
                  </label>
                </div>
                <label className="block text-xs font-medium">
                  付款方名称
                  <Input
                    className="mt-1"
                    value={receiptForm.counterpartyName}
                    onChange={(e) =>
                      setReceiptForm({ ...receiptForm, counterpartyName: e.target.value })
                    }
                  />
                </label>
                <label className="block text-xs font-medium">
                  服务商流水号
                  <Input
                    className="mt-1"
                    value={receiptForm.providerSerialNo}
                    onChange={(e) =>
                      setReceiptForm({ ...receiptForm, providerSerialNo: e.target.value })
                    }
                  />
                </label>
                <Button disabled={busy} onClick={() => void submitReceipt()}>
                  登记收款
                </Button>
                <p className="text-muted-foreground text-xs">
                  服务商未接入时**照样记本地台账**，但会明确标注「未向服务商登记」。
                </p>
              </div>
            </SectionCard>
          </div>

          <SectionCard title="资金操作台账" description="每一行的状态、幂等键与失败原因">
            {operations.length === 0 ? (
              <EmptyState
                icon={<ArrowLeftRight className="size-5" />}
                title="还没有资金操作记录"
                description="发起一次付款执行或收款登记后，这里会出现可追溯的操作记录"
              />
            ) : (
              <ul className="divide-border divide-y">
                {operations.map((o) => {
                  const notice = receiptProviderNotice(o);
                  return (
                    <li key={o.id} className="flex flex-wrap items-start gap-3 py-3">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone={BADGE_TONE[operationTone(o.status)]}>
                            {operationStatusLabel(o.status)}
                          </Badge>
                          <Badge tone="neutral">{operationTypeLabel(o.operationType)}</Badge>
                          <span className="text-sm">{o.providerLabel}</span>
                          <span className="text-sm font-medium">
                            {formatMoney(o.amount, o.currency)}
                          </span>
                          {o.direction && (
                            <span className="text-muted-foreground text-xs">
                              {directionLabel(o.direction)}
                            </span>
                          )}
                        </div>
                        <p className="text-muted-foreground mt-1 text-xs">
                          幂等键 {o.idempotencyKey} · 单号 {o.reference || '—'} · 尝试{' '}
                          {o.attemptCount} 次
                          {o.providerSerialNo ? ` · 服务商流水号 ${o.providerSerialNo}` : ''}
                        </p>
                        {o.failureReason && (
                          <p className="text-muted-foreground mt-1 text-xs">
                            {failureClassLabel(o.failureClass)}：{o.failureReason}
                          </p>
                        )}
                        {notice && (
                          <p className="text-warning-foreground mt-1 text-xs">
                            <TriangleAlert className="mr-1 inline size-3.5" />
                            {notice}
                          </p>
                        )}
                      </div>
                      <div className="flex shrink-0 gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy}
                          onClick={() => void openAttempts(o)}
                        >
                          查证留痕
                        </Button>
                        {canRetry(o) && (
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy}
                            onClick={() => void submitRetry(o)}
                          >
                            重发
                          </Button>
                        )}
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </SectionCard>

          {selectedOperation && (
            <SectionCard
              title={`查证留痕 · ${operationStatusLabel(selectedOperation.status)}`}
              description={idempotencyHint(selectedOperation)}
            >
              {attempts.length === 0 ? (
                <EmptyState
                  title="还没有留痕"
                  description="点上面那行的「查证留痕」看每一次发起与查证"
                />
              ) : (
                <ul className="divide-border divide-y">
                  {attempts.map((a) => (
                    <li key={a.id} className="py-2 text-xs">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone="neutral">#{a.attemptNo}</Badge>
                        <Badge tone={a.attemptKind === 'QUERY' ? 'warning' : 'neutral'}>
                          {a.attemptKindLabel}
                        </Badge>
                        <span>{a.outcomeLabel}</span>
                      </div>
                      <p className="text-muted-foreground mt-1">
                        {a.requestSummary}
                        {a.failureReason ? ` · ${a.failureReason}` : ''}
                      </p>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          )}
        </div>
      )}

      {!loading && tab === 'flows' && (
        <SectionCard title="资金流水台账" description="只追加：资金流水是账，改一笔账就等于改历史">
          {flows.length === 0 ? (
            <EmptyState
              icon={<Wallet className="size-5" />}
              title="还没有资金流水"
              description="付款执行成功、收款登记或账单导入都会在这里留下一笔"
            />
          ) : (
            <ul className="divide-border divide-y">
              {flows.map((f) => (
                <li key={f.id} className="flex flex-wrap items-center gap-3 py-3">
                  <Badge tone="neutral">{flowTypeLabel(f.flowType)}</Badge>
                  <span className="text-sm font-medium">{formatMoney(f.amount, f.currency)}</span>
                  <span className="text-muted-foreground text-xs">{f.providerName}</span>
                  <span className="text-muted-foreground text-xs">{f.reference || '—'}</span>
                  <span className="text-muted-foreground ml-auto text-xs">{f.flowNo}</span>
                </li>
              ))}
            </ul>
          )}
        </SectionCard>
      )}

      {!loading && tab === 'balances' && (
        <div className="space-y-4">
          <div className="grid gap-4 lg:grid-cols-2">
            <SectionCard
              title="导入余额"
              description="从服务商账单 / 对账单导入（今天就能用的路径）"
            >
              <div className="space-y-3">
                <label className="block text-xs font-medium">
                  服务商配置
                  <select
                    aria-label="余额导入服务商"
                    className="border-border bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                    value={balanceImport.providerId}
                    onChange={(e) =>
                      setBalanceImport({ ...balanceImport, providerId: e.target.value })
                    }
                  >
                    <option value="">（选择服务商配置）</option>
                    {providers.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.providerLabel} · {p.name}
                      </option>
                    ))}
                  </select>
                </label>
                <div className="grid grid-cols-3 gap-3">
                  <label className="block text-xs font-medium">
                    币种
                    <Input
                      className="mt-1"
                      value={balanceImport.currency}
                      onChange={(e) =>
                        setBalanceImport({ ...balanceImport, currency: e.target.value })
                      }
                    />
                  </label>
                  <label className="block text-xs font-medium">
                    账面余额
                    <Input
                      className="mt-1"
                      value={balanceImport.balanceAmount}
                      placeholder="1234.56"
                      onChange={(e) =>
                        setBalanceImport({ ...balanceImport, balanceAmount: e.target.value })
                      }
                    />
                  </label>
                  <label className="block text-xs font-medium">
                    可用余额
                    <Input
                      className="mt-1"
                      value={balanceImport.availableAmount}
                      placeholder="可选"
                      onChange={(e) =>
                        setBalanceImport({ ...balanceImport, availableAmount: e.target.value })
                      }
                    />
                  </label>
                </div>
                <label className="block text-xs font-medium">
                  来源说明
                  <Input
                    className="mt-1"
                    value={balanceImport.sourceRef}
                    placeholder="对账单-2026-10"
                    onChange={(e) =>
                      setBalanceImport({ ...balanceImport, sourceRef: e.target.value })
                    }
                  />
                </label>
                <Button disabled={busy} onClick={() => void submitBalanceImport()}>
                  导入余额
                </Button>
              </div>
            </SectionCard>

            <SectionCard title="导入手续费明细" description="从服务商账单 / 对账单导入">
              <div className="space-y-3">
                <label className="block text-xs font-medium">
                  服务商配置
                  <select
                    aria-label="手续费导入服务商"
                    className="border-border bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                    value={feeImport.providerId}
                    onChange={(e) => setFeeImport({ ...feeImport, providerId: e.target.value })}
                  >
                    <option value="">（选择服务商配置）</option>
                    {providers.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.providerLabel} · {p.name}
                      </option>
                    ))}
                  </select>
                </label>
                <div className="grid grid-cols-3 gap-3">
                  <label className="block text-xs font-medium">
                    费用类型
                    <Input
                      className="mt-1"
                      value={feeImport.feeType}
                      placeholder="CROSS_BORDER_FEE"
                      onChange={(e) => setFeeImport({ ...feeImport, feeType: e.target.value })}
                    />
                  </label>
                  <label className="block text-xs font-medium">
                    币种
                    <Input
                      className="mt-1"
                      value={feeImport.currency}
                      onChange={(e) => setFeeImport({ ...feeImport, currency: e.target.value })}
                    />
                  </label>
                  <label className="block text-xs font-medium">
                    金额
                    <Input
                      className="mt-1"
                      value={feeImport.amount}
                      placeholder="12.34"
                      onChange={(e) => setFeeImport({ ...feeImport, amount: e.target.value })}
                    />
                  </label>
                </div>
                <Button disabled={busy} onClick={() => void submitFeeImport()}>
                  导入手续费
                </Button>
              </div>
            </SectionCard>
          </div>

          <SectionCard title="余额观测" description="只追加：余额历史不许改写">
            {balances.length === 0 ? (
              <EmptyState title="还没有余额观测" description="从服务商对账单导入一条即可" />
            ) : (
              <ul className="divide-border divide-y">
                {balances.map((b) => (
                  <li key={b.id} className="flex flex-wrap items-center gap-3 py-3 text-sm">
                    <Badge tone="neutral">{b.source}</Badge>
                    <span className="font-medium">{formatMoney(b.balanceAmount, b.currency)}</span>
                    <span className="text-muted-foreground text-xs">
                      可用 {formatAmount(b.availableAmount)} · {b.providerName} ·{' '}
                      {b.sourceRef || '—'}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard title="手续费明细" description="只追加">
            {fees.length === 0 ? (
              <EmptyState title="还没有手续费明细" description="从服务商账单导入一条即可" />
            ) : (
              <ul className="divide-border divide-y">
                {fees.map((f) => (
                  <li key={f.id} className="flex flex-wrap items-center gap-3 py-3 text-sm">
                    <Badge tone="neutral">{f.source}</Badge>
                    <span className="font-medium">{formatMoney(f.amount, f.currency)}</span>
                    <span className="text-muted-foreground text-xs">
                      {f.feeType} · {f.providerName} · {f.summary || '—'}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </div>
      )}

      {!loading && tab === 'rates' && (
        <div className="space-y-4">
          <SectionCard
            title="汇率查询"
            description="真读上游 5.2.2.1 汇率事实并把观测值落库；服务商锁汇报价未接入时如实标注"
          >
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <label className="block text-xs font-medium">
                服务商配置
                <select
                  aria-label="汇率服务商"
                  className="border-border bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                  value={rateForm.providerId}
                  onChange={(e) => setRateForm({ ...rateForm, providerId: e.target.value })}
                >
                  <option value="">（选择服务商配置）</option>
                  {providers.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.providerLabel} · {p.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-xs font-medium">
                基准币种
                <Input
                  className="mt-1"
                  value={rateForm.baseCurrency}
                  onChange={(e) => setRateForm({ ...rateForm, baseCurrency: e.target.value })}
                />
              </label>
              <label className="block text-xs font-medium">
                报价币种
                <Input
                  className="mt-1"
                  value={rateForm.quoteCurrency}
                  onChange={(e) => setRateForm({ ...rateForm, quoteCurrency: e.target.value })}
                />
              </label>
              <div className="flex items-end">
                <Button disabled={busy} onClick={() => void submitRateQuery()}>
                  查询汇率
                </Button>
              </div>
            </div>
          </SectionCard>

          <SectionCard title="汇率观测" description="只追加；反推出来的汇率会标 inverted">
            {rates.length === 0 ? (
              <EmptyState title="还没有汇率观测" description="查一次汇率即可" />
            ) : (
              <ul className="divide-border divide-y">
                {rates.map((r) => (
                  <li key={r.id} className="flex flex-wrap items-center gap-3 py-3 text-sm">
                    <Badge tone="neutral">{r.source}</Badge>
                    <span className="font-medium">
                      {formatRate(r.rate, r.baseCurrency, r.quoteCurrency)}
                    </span>
                    {r.inverted && <Badge tone="warning">由反方向推导</Badge>}
                    <span className="text-muted-foreground text-xs">{r.providerName}</span>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </div>
      )}

      {!loading && tab === 'upstream' && (
        <div className="space-y-4">
          <Notice tone="info" title="只读连接">
            上游 12.7 资金账户与 12.2 收付款住在 finance 服务里，本模块**只经授权端点读**，
            不建跨库外键、不抄余额历史，也**不回写** finance 的账。
          </Notice>
          <Button variant="outline" disabled={busy} onClick={() => void loadUpstream()}>
            {busy ? <Loader2 className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
            读取上游
          </Button>
          <SectionCard title="上游资金账户（12.7）">
            {upstreamAccounts.length === 0 ? (
              <EmptyState title="还没有读到上游资金账户" description="点上面的「读取上游」" />
            ) : (
              <ul className="divide-border divide-y">
                {upstreamAccounts.map((a) => (
                  <li key={a.id} className="flex flex-wrap items-center gap-3 py-3 text-sm">
                    <span className="font-medium">{a.accountName}</span>
                    <span className="text-muted-foreground text-xs">{a.accountNo}</span>
                    <span className="ml-auto">{formatMoney(a.currentBalance, a.currency)}</span>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
          <SectionCard title="上游收付款（12.2）">
            {upstreamOrders.length === 0 ? (
              <EmptyState title="还没有读到上游付款单" description="点上面的「读取上游」" />
            ) : (
              <ul className="divide-border divide-y">
                {upstreamOrders.map((o) => (
                  <li key={o.id} className="flex flex-wrap items-center gap-3 py-3 text-sm">
                    <span className="font-medium">{o.orderNo}</span>
                    <span className="text-muted-foreground text-xs">{o.counterpartyName}</span>
                    <span className="ml-auto">{formatMoney(o.amount, o.currency)}</span>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </div>
      )}

      {!loading && tab === 'capabilities' && (
        <div className="space-y-4">
          <SectionCard
            title="直连服务商：还没接通"
            description="🔴 本仓没有任何真实的支付服务商凭据与网络通道，这几格如实答「未接入」"
          >
            {directCapabilities.length === 0 ? (
              <EmptyState title="没有未接通项" description="（这通常意味着能力声明没更新）" />
            ) : (
              <ul className="space-y-3">
                {directCapabilities.map((c) => (
                  <li key={c.name}>
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={BADGE_TONE[capabilityTone(c.available)]}>
                        {c.available ? '已接通' : '未接入'}
                      </Badge>
                      <span className="text-sm font-medium">{c.name}</span>
                      {c.ownerModule && (
                        <span className="text-muted-foreground text-xs">归 {c.ownerModule}</span>
                      )}
                    </div>
                    <p className="text-muted-foreground mt-1 text-xs">{c.evidence}</p>
                    {c.note && <p className="mt-1 text-xs">{c.note}</p>}
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard title="全部能力声明" description="逐维写明有没有、依据是什么、缺了归谁">
            {capabilities === null ? (
              <EmptyState title="还没有读到能力声明" />
            ) : (
              <ul className="space-y-3">
                {capabilities.capabilities.map((c) => (
                  <li key={`${c.moduleId}-${c.name}`}>
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={BADGE_TONE[capabilityTone(c.available)]}>
                        {c.available ? '可用' : '未接入'}
                      </Badge>
                      <span className="text-sm font-medium">{c.name}</span>
                    </div>
                    <p className="text-muted-foreground mt-1 text-xs">{c.evidence}</p>
                    {c.note && <p className="mt-1 text-xs">{c.note}</p>}
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard title="本模块与别的模块的边界">
            {capabilities === null ? (
              <EmptyState title="还没有读到能力声明" />
            ) : (
              <ul className="space-y-3">
                {capabilities.gaps.map((g) => (
                  <li key={`${g.moduleId}-${g.name}`}>
                    <div className="flex flex-wrap items-center gap-2">
                      <CircleAlert className="size-3.5" />
                      <span className="text-sm font-medium">
                        {g.moduleId} {g.name}
                      </span>
                      <span className="text-muted-foreground text-xs">
                        {g.service} · {g.relation}
                      </span>
                    </div>
                    <p className="text-muted-foreground mt-1 text-xs">{g.note}</p>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </div>
      )}
    </div>
  );
}
