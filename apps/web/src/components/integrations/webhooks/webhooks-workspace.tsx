'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  FileKey2,
  Filter,
  KeyRound,
  Loader2,
  RefreshCw,
  ScrollText,
  ShieldCheck,
  TriangleAlert,
  Webhook,
} from 'lucide-react';
import {
  EVENT_TYPE_STATUS_OPTIONS,
  SUBSCRIPTION_STATUS_OPTIONS,
  addFilter,
  auditActionLabel,
  capabilityTone,
  changeEventTypeStatus,
  createPolicy,
  createSubscription,
  deliveryCapability,
  eventTypeStatusTone,
  eventTypeVersionLabel,
  fetchAuditEvents,
  fetchCapabilities,
  fetchEventTypes,
  fetchOverview,
  fetchPolicies,
  fetchSigningSecrets,
  fetchSubscriptionDetail,
  fetchSubscriptions,
  filterSummary,
  isEditable,
  issueSigningSecret,
  paginationLabel,
  previewSignature,
  registerEventType,
  removeFilter,
  revokeSigningSecret,
  revokeSubscription,
  rotateSigningSecret,
  secretPlaintext,
  secretStatusTone,
  signatureHeaderLabel,
  signatureVerificationSteps,
  subscriptionStatusTone,
  unavailableHint,
  updatePolicy,
  updateSubscription,
  type Tone,
} from './webhooks-model';
import type {
  EventSubscriptionAuditEventRow,
  EventSubscriptionCapabilities,
  EventSubscriptionDetail,
  EventSubscriptionEventTypeRow,
  EventSubscriptionOverview,
  EventSubscriptionPolicyRow,
  EventSubscriptionRow,
  EventSubscriptionSecretIssued,
  EventSubscriptionSignaturePreview,
} from '../../../../../../packages/api-contracts/src/index';

/**
 * 18.1.2.1「事件订阅」工作区（卷 18 前端入口 `/integrations/webhooks`）。
 *
 * 蓝图：「**事件订阅**：对外提供事件订阅（webhook）：可订阅事件列表、订阅端点配置、签名密钥、
 * 事件过滤、重试与死信策略。输入：订阅配置；事件定义（schema 版本）。输出：订阅记录；
 * 事件投递（带签名）。关系：上游 = 业务事件（outbox）；下游 = 外部端点。
 * **事件 schema 必须版本化，破坏性变更要新增版本而非原地修改。**」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **「可订阅事件列表」真查后端注册表**。每条都带**出处**（哪个服务、哪一行代码）——
 *    页面**不**维护一份本地事件清单。
 * 2. **「结构不可原地修改」说清楚**。事件定义只显示「已登记第 N 版」；改结构的入口是
 *    「登记新版本」，不是「编辑」。
 * 3. **未接入就明说**。本仓没有事件投递执行器（归 18.1.2.2），所以页面**不提供**投递按钮，
 *    只在能力声明里如实写明「未接入 + 归谁 + 现在怎么办」。
 * 4. **签名密钥明文只出现一次**。生成 / 轮换之后立刻显示，但**不缓存**到任何长期存储；
 *    刷新页面就没了（后端也再也拿不回来）。
 * 5. **签名试算是真的**。它让对接方当场核对「平台会发的签名头」，但**不投递**、
 *    **不写任何投递记录** —— 页面不把它说成「已投递」。
 * 6. **加载 / 空 / 错误三态齐全**：空态区分「还没登记过」与「筛选无结果」。
 */

type Tab = 'event-types' | 'subscriptions' | 'policies' | 'audit' | 'capabilities';

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

interface EventTypeForm {
  eventType: string;
  schemaVersion: string;
  status: string;
  payloadSchema: string;
  sourceService: string;
  sourceArtifact: string;
}

const EMPTY_EVENT_TYPE: EventTypeForm = {
  eventType: '',
  schemaVersion: '1',
  status: 'ACTIVE',
  payloadSchema: '{\n  "type": "object"\n}',
  sourceService: '',
  sourceArtifact: '',
};

interface SubscriptionForm {
  appId: string;
  name: string;
  targetUrl: string;
  status: string;
  description: string;
}

const EMPTY_SUBSCRIPTION: SubscriptionForm = {
  appId: '',
  name: '',
  targetUrl: '',
  status: 'ACTIVE',
  description: '',
};

interface FilterForm {
  eventType: string;
  schemaVersion: string;
  filter: string;
  enabled: boolean;
}

const EMPTY_FILTER: FilterForm = {
  eventType: '',
  schemaVersion: '',
  filter: '{}',
  enabled: true,
};

interface PolicyForm {
  subscriptionId: string;
  maxAttempts: string;
  initialBackoffSeconds: string;
  backoffMultiplier: string;
  maxBackoffSeconds: string;
  requestTimeoutSeconds: string;
  deadLetterRetentionDays: string;
}

const EMPTY_POLICY: PolicyForm = {
  subscriptionId: '',
  maxAttempts: '5',
  initialBackoffSeconds: '30',
  backoffMultiplier: '2.00',
  maxBackoffSeconds: '3600',
  requestTimeoutSeconds: '10',
  deadLetterRetentionDays: '30',
};

export function WebhooksWorkspace() {
  const [tab, setTab] = useState<Tab>('event-types');

  const [capabilities, setCapabilities] = useState<EventSubscriptionCapabilities | null>(null);
  const [overview, setOverview] = useState<EventSubscriptionOverview | null>(null);

  const [eventTypes, setEventTypes] = useState<EventSubscriptionEventTypeRow[]>([]);
  const [eventTypeTotal, setEventTypeTotal] = useState(0);
  const [eventTypePage, setEventTypePage] = useState(0);
  const [eventTypeKeyword, setEventTypeKeyword] = useState('');
  const [eventTypeStatus, setEventTypeStatus] = useState('');

  const [subscriptions, setSubscriptions] = useState<EventSubscriptionRow[]>([]);
  const [subscriptionTotal, setSubscriptionTotal] = useState(0);
  const [subscriptionPage, setSubscriptionPage] = useState(0);
  const [subscriptionKeyword, setSubscriptionKeyword] = useState('');
  const [subscriptionStatus, setSubscriptionStatus] = useState('');

  const [detail, setDetail] = useState<EventSubscriptionDetail | null>(null);
  const [secrets, setSecrets] = useState<EventSubscriptionSecretIssued | null>(null);
  const [preview, setPreview] = useState<EventSubscriptionSignaturePreview | null>(null);
  const [previewPayload, setPreviewPayload] = useState('{"eventType":"SkuProfileSaved"}');

  const [policies, setPolicies] = useState<EventSubscriptionPolicyRow[]>([]);
  const [auditRows, setAuditRows] = useState<EventSubscriptionAuditEventRow[]>([]);
  const [auditTotal, setAuditTotal] = useState(0);
  const [auditPage, setAuditPage] = useState(0);

  const [eventTypeForm, setEventTypeForm] = useState<EventTypeForm>(EMPTY_EVENT_TYPE);
  const [subscriptionForm, setSubscriptionForm] = useState<SubscriptionForm>(EMPTY_SUBSCRIPTION);
  const [editingSubscriptionId, setEditingSubscriptionId] = useState<string | null>(null);
  const [filterForm, setFilterForm] = useState<FilterForm>(EMPTY_FILTER);
  const [policyForm, setPolicyForm] = useState<PolicyForm>(EMPTY_POLICY);
  const [editingPolicyId, setEditingPolicyId] = useState<string | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const fail = (e: unknown) => {
    const message = e instanceof Error ? e.message : '请求失败，请稍后重试';
    setError(message);
  };

  const loadEventTypes = useCallback(async () => {
    const page = await fetchEventTypes({
      keyword: eventTypeKeyword,
      status: eventTypeStatus,
      page: eventTypePage,
      size: 20,
    });
    setEventTypes(page.items);
    setEventTypeTotal(page.total);
  }, [eventTypeKeyword, eventTypeStatus, eventTypePage]);

  const loadSubscriptions = useCallback(async () => {
    const page = await fetchSubscriptions({
      keyword: subscriptionKeyword,
      status: subscriptionStatus,
      page: subscriptionPage,
      size: 20,
    });
    setSubscriptions(page.items);
    setSubscriptionTotal(page.total);
  }, [subscriptionKeyword, subscriptionStatus, subscriptionPage]);

  const loadPolicies = useCallback(async () => {
    setPolicies(await fetchPolicies());
  }, []);

  const loadAudit = useCallback(async () => {
    const page = await fetchAuditEvents({ page: auditPage, size: 20 });
    setAuditRows(page.items);
    setAuditTotal(page.total);
  }, [auditPage]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov] = await Promise.all([fetchCapabilities(), fetchOverview()]);
      setCapabilities(caps);
      setOverview(ov);
      await Promise.all([loadEventTypes(), loadSubscriptions(), loadPolicies(), loadAudit()]);
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, [loadEventTypes, loadSubscriptions, loadPolicies, loadAudit]);

  useEffect(() => {
    void load();
  }, [load]);

  const run = async (work: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await work();
      setNotice(ok);
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  // ------------------------------------------------------------ 事件定义

  const submitEventType = async () => {
    await run(async () => {
      await registerEventType({
        eventType: eventTypeForm.eventType,
        schemaVersion: Number(eventTypeForm.schemaVersion) || 1,
        status: eventTypeForm.status,
        payloadSchema: eventTypeForm.payloadSchema,
        sourceService: eventTypeForm.sourceService,
        sourceArtifact: eventTypeForm.sourceArtifact,
      });
      setEventTypeForm(EMPTY_EVENT_TYPE);
    }, '事件定义已登记。');
  };

  const toggleEventTypeStatus = async (row: EventSubscriptionEventTypeRow) => {
    const next = row.status === 'ACTIVE' ? 'DEPRECATED' : 'ACTIVE';
    await run(
      async () => {
        await changeEventTypeStatus(row.id, next);
      },
      `已把 ${eventTypeVersionLabel(row)} 标为 ${next === 'ACTIVE' ? '可用' : '已废弃'}。`,
    );
  };

  // ------------------------------------------------------------ 订阅

  const submitSubscription = async () => {
    await run(async () => {
      const input = {
        appId: subscriptionForm.appId,
        name: subscriptionForm.name,
        targetUrl: subscriptionForm.targetUrl,
        status: subscriptionForm.status,
        description: subscriptionForm.description,
      };
      if (editingSubscriptionId) {
        await updateSubscription(editingSubscriptionId, input);
      } else {
        await createSubscription(input);
      }
      setSubscriptionForm(EMPTY_SUBSCRIPTION);
      setEditingSubscriptionId(null);
    }, editingSubscriptionId ? '订阅已修改。' : '订阅已创建。');
  };

  const openSubscription = async (id: string) => {
    setBusy(true);
    setError('');
    try {
      const [d, s] = await Promise.all([fetchSubscriptionDetail(id), fetchSigningSecrets(id)]);
      setDetail(d);
      setSecrets(null);
      setPreview(null);
      void s;
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (id: string) => {
    await run(
      async () => {
        await revokeSubscription(id, '运营在界面上吊销');
        if (detail?.subscription.id === id) setDetail(null);
      },
      '订阅已吊销（记录保留，审计痕迹不删）。',
    );
  };

  const submitFilter = async () => {
    if (!detail) return;
    await run(async () => {
      await addFilter(detail.subscription.id, {
        eventType: filterForm.eventType,
        schemaVersion: filterForm.schemaVersion ? Number(filterForm.schemaVersion) : 0,
        filter: filterForm.filter,
        enabled: filterForm.enabled,
      });
      setFilterForm(EMPTY_FILTER);
      await openSubscription(detail.subscription.id);
    }, '事件过滤已添加。');
  };

  const dropFilter = async (filterId: string) => {
    if (!detail) return;
    await run(async () => {
      await removeFilter(detail.subscription.id, filterId);
      await openSubscription(detail.subscription.id);
    }, '事件过滤已删除。');
  };

  // ------------------------------------------------------------ 签名密钥

  const issueSecret = async () => {
    if (!detail) return;
    await run(async () => {
      const issued = await issueSigningSecret(detail.subscription.id);
      setSecrets(issued);
      await openSubscription(detail.subscription.id);
    }, '签名密钥已生成 —— 明文只在下面出现这一次。');
  };

  const rotateSecret = async () => {
    if (!detail) return;
    await run(async () => {
      const issued = await rotateSigningSecret(detail.subscription.id);
      setSecrets(issued);
      await openSubscription(detail.subscription.id);
    }, '签名密钥已轮换 —— 旧密钥立即失效，明文只在下面出现这一次。');
  };

  const revokeSecret = async () => {
    if (!detail) return;
    await run(async () => {
      await revokeSigningSecret(detail.subscription.id);
      setSecrets(null);
      await openSubscription(detail.subscription.id);
    }, '签名密钥已吊销。');
  };

  // ------------------------------------------------------------ 签名试算

  const runPreview = async () => {
    if (!detail) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      setPreview(
        await previewSignature(detail.subscription.id, {
          payload: previewPayload,
          timestamp: '',
        }),
      );
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  // ------------------------------------------------------------ 策略

  const submitPolicy = async () => {
    await run(async () => {
      const input = {
        subscriptionId: policyForm.subscriptionId,
        maxAttempts: Number(policyForm.maxAttempts) || 5,
        initialBackoffSeconds: Number(policyForm.initialBackoffSeconds) || 30,
        backoffMultiplier: Number(policyForm.backoffMultiplier) || 2,
        maxBackoffSeconds: Number(policyForm.maxBackoffSeconds) || 3600,
        requestTimeoutSeconds: Number(policyForm.requestTimeoutSeconds) || 10,
        deadLetterRetentionDays: Number(policyForm.deadLetterRetentionDays) || 30,
      };
      if (editingPolicyId) {
        await updatePolicy(editingPolicyId, input);
      } else {
        await createPolicy(input);
      }
      setPolicyForm(EMPTY_POLICY);
      setEditingPolicyId(null);
    }, editingPolicyId ? '策略已修改。' : '策略已新建。');
  };

  const hasEventTypeFilter = Boolean(eventTypeKeyword || eventTypeStatus);
  const hasSubscriptionFilter = Boolean(subscriptionKeyword || subscriptionStatus);
  const delivery = deliveryCapability(capabilities);

  return (
    <div className="space-y-4">
      <PageHeader
        title="事件订阅"
        description="对外提供事件订阅（webhook）：可订阅事件列表、订阅端点配置、签名密钥、事件过滤、重试与死信策略。事件实际投递尚未接入。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            刷新
          </Button>
        }
      />

      {error && (
        <div className="text-destructive flex items-start gap-2 rounded-md border p-3 text-sm">
          <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {notice && (
        <div className="text-success flex items-start gap-2 rounded-md border p-3 text-sm">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{notice}</span>
        </div>
      )}

      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="事件定义"
            value={overview.eventTypeCount}
            unit="条"
            icon={<Webhook className="h-4 w-4" />}
          />
          <StatCard label="订阅" value={overview.subscriptionCount} unit="个" />
          <StatCard
            label="事件过滤"
            value={overview.filterCount}
            unit="条"
            icon={<Filter className="h-4 w-4" />}
          />
          <StatCard
            label="生效签名密钥"
            value={overview.activeSigningSecretCount}
            unit="把"
            icon={<KeyRound className="h-4 w-4" />}
          />
        </div>
      )}

      <Segmented
        value={tab}
        onChange={(value) => setTab(value)}
        options={[
          { value: 'event-types', label: '可订阅事件' },
          { value: 'subscriptions', label: '订阅' },
          { value: 'policies', label: '策略' },
          { value: 'audit', label: '审计' },
          { value: 'capabilities', label: '能力声明' },
        ]}
      />

      {/* ------------------------------------------------------ 可订阅事件 */}
      {tab === 'event-types' && (
        <div className="space-y-3">
          <SectionCard
            title="可订阅事件列表"
            description="🔴 这不是一份写死的清单 —— 每一行都是登记过的**事件定义**，带出处（哪个服务、哪一行代码）。事件 schema 必须版本化：破坏性变更要登记新版本，不能原地改。"
          >
            <div className="space-y-3">
              <div className="flex flex-wrap items-end gap-2">
                <label className="space-y-1 text-sm">
                  <span className="text-muted">关键词</span>
                  <Input
                    value={eventTypeKeyword}
                    placeholder="事件类型 / 出处服务 / 出处位置"
                    onChange={(e) => {
                      setEventTypeKeyword(e.target.value);
                      setEventTypePage(0);
                    }}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-muted">状态</span>
                  <select
                    className="border-input h-9 rounded-md border px-2 text-sm"
                    value={eventTypeStatus}
                    onChange={(e) => {
                      setEventTypeStatus(e.target.value);
                      setEventTypePage(0);
                    }}
                  >
                    <option value="">全部</option>
                    {EVENT_TYPE_STATUS_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </label>
                <Button
                  variant="outline"
                  onClick={() => void loadEventTypes()}
                  disabled={loading}
                >
                  筛选
                </Button>
              </div>

              {loading ? (
                <div className="text-muted flex items-center gap-2 text-sm">
                  <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
                </div>
              ) : eventTypes.length === 0 ? (
                <EmptyState
                  icon={<Webhook className="h-6 w-6" />}
                  title={
                    hasEventTypeFilter ? '没有匹配的事件定义' : '还没有登记过任何事件定义'
                  }
                  description={
                    hasEventTypeFilter
                      ? '换个关键词或把状态筛选清掉再试。'
                      : '登记一条事件定义（事件类型 + schema 版本 + 出处）之后，它才能被订阅。'
                  }
                />
              ) : (
                <div className="space-y-2">
                  {eventTypes.map((row) => (
                    <div
                      key={row.id}
                      className="flex flex-wrap items-start justify-between gap-2 rounded-md border p-3 text-sm"
                    >
                      <div className="space-y-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">{eventTypeVersionLabel(row)}</span>
                          <Badge tone={BADGE_TONE[eventTypeStatusTone(row.status)]}>
                            {row.statusLabel}
                          </Badge>
                        </div>
                        <div className="text-muted text-xs">
                          出处 {row.sourceService} · {row.sourceArtifact}
                        </div>
                        <details className="text-muted text-xs">
                          <summary className="cursor-pointer">载荷 schema</summary>
                          <pre className="mt-1 overflow-x-auto rounded bg-muted p-2 text-xs">
                            {row.payloadSchema}
                          </pre>
                        </details>
                      </div>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() => void toggleEventTypeStatus(row)}
                      >
                        {row.status === 'ACTIVE' ? '标为已废弃' : '标为可用'}
                      </Button>
                    </div>
                  ))}
                  <div className="text-muted flex items-center justify-between text-xs">
                    <span>
                      {paginationLabel({
                        page: eventTypePage,
                        size: 20,
                        total: eventTypeTotal,
                      })}
                    </span>
                    <span className="flex gap-1">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={eventTypePage === 0 || busy}
                        onClick={() => setEventTypePage((p) => Math.max(p - 1, 0))}
                      >
                        上一页
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={(eventTypePage + 1) * 20 >= eventTypeTotal || busy}
                        onClick={() => setEventTypePage((p) => p + 1)}
                      >
                        下一页
                      </Button>
                    </span>
                  </div>
                </div>
              )}
            </div>
          </SectionCard>

          <SectionCard
            title="登记事件定义"
            description="登记一个事件类型的一个 schema 版本。出处（服务 + 文件:行）是**必填** —— 「这个事件真的由哪个服务发出」不可编造。"
          >
            <div className="grid gap-3 md:grid-cols-2">
              <label className="space-y-1 text-sm">
                <span className="text-muted">事件类型</span>
                <Input
                  value={eventTypeForm.eventType}
                  placeholder="SkuProfileSaved"
                  onChange={(e) => setEventTypeForm({ ...eventTypeForm, eventType: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">schema 版本</span>
                <Input
                  value={eventTypeForm.schemaVersion}
                  placeholder="1"
                  onChange={(e) =>
                    setEventTypeForm({ ...eventTypeForm, schemaVersion: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">状态</span>
                <select
                  className="border-input h-9 rounded-md border px-2 text-sm"
                  value={eventTypeForm.status}
                  onChange={(e) => setEventTypeForm({ ...eventTypeForm, status: e.target.value })}
                >
                  {EVENT_TYPE_STATUS_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">出处服务</span>
                <Input
                  value={eventTypeForm.sourceService}
                  placeholder="catalog"
                  onChange={(e) =>
                    setEventTypeForm({ ...eventTypeForm, sourceService: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm md:col-span-2">
                <span className="text-muted">出处位置（文件:行）</span>
                <Input
                  value={eventTypeForm.sourceArtifact}
                  placeholder="catalog/SkuDetailService.java:435"
                  onChange={(e) =>
                    setEventTypeForm({ ...eventTypeForm, sourceArtifact: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm md:col-span-2">
                <span className="text-muted">载荷 schema（JSON 对象）</span>
                <textarea
                  className="border-input min-h-24 w-full rounded-md border p-2 font-mono text-xs"
                  value={eventTypeForm.payloadSchema}
                  onChange={(e) =>
                    setEventTypeForm({ ...eventTypeForm, payloadSchema: e.target.value })
                  }
                />
              </label>
            </div>
            <div className="mt-3">
              <Button onClick={() => void submitEventType()} disabled={busy}>
                登记事件定义
              </Button>
            </div>
          </SectionCard>
        </div>
      )}

      {/* ------------------------------------------------------ 订阅 */}
      {tab === 'subscriptions' && (
        <div className="space-y-3">
          <SectionCard
            title="订阅端点"
            description="订阅必须挂在一个已注册应用上（18.1.1.1）。端点地址只能是 http / https。吊销**不删**记录 —— 订阅与它的审计痕迹都要留着。"
          >
            <div className="space-y-3">
              <div className="flex flex-wrap items-end gap-2">
                <label className="space-y-1 text-sm">
                  <span className="text-muted">关键词</span>
                  <Input
                    value={subscriptionKeyword}
                    placeholder="订阅名 / 端点地址 / 应用名"
                    onChange={(e) => {
                      setSubscriptionKeyword(e.target.value);
                      setSubscriptionPage(0);
                    }}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-muted">状态</span>
                  <select
                    className="border-input h-9 rounded-md border px-2 text-sm"
                    value={subscriptionStatus}
                    onChange={(e) => {
                      setSubscriptionStatus(e.target.value);
                      setSubscriptionPage(0);
                    }}
                  >
                    <option value="">全部</option>
                    {SUBSCRIPTION_STATUS_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </label>
                <Button
                  variant="outline"
                  onClick={() => void loadSubscriptions()}
                  disabled={loading}
                >
                  筛选
                </Button>
              </div>

              {loading ? (
                <div className="text-muted flex items-center gap-2 text-sm">
                  <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
                </div>
              ) : subscriptions.length === 0 ? (
                <EmptyState
                  icon={<Webhook className="h-6 w-6" />}
                  title={
                    hasSubscriptionFilter ? '没有匹配的订阅' : '还没有登记过订阅'
                  }
                  description={
                    hasSubscriptionFilter
                      ? '换个关键词或把状态筛选清掉再试。'
                      : '先在下面登记一个订阅端点，事件才有地方可去。'
                  }
                />
              ) : (
                <div className="space-y-2">
                  {subscriptions.map((row) => (
                    <div
                      key={row.id}
                      className="flex flex-wrap items-start justify-between gap-2 rounded-md border p-3 text-sm"
                    >
                      <div className="space-y-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">{row.name}</span>
                          <Badge tone={BADGE_TONE[subscriptionStatusTone(row.status)]}>
                            {row.statusLabel}
                          </Badge>
                        </div>
                        <div className="text-muted text-xs">
                          应用 {row.appName} · 端点 {row.targetUrl}
                        </div>
                        <div className="text-muted text-xs">
                          事件过滤 {row.filterCount} 条 ·{' '}
                          {row.hasActiveSecret ? '已有生效签名密钥' : '尚无生效签名密钥'}
                        </div>
                      </div>
                      <div className="flex gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => void openSubscription(row.id)}
                        >
                          详情
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => {
                            setEditingSubscriptionId(row.id);
                            setSubscriptionForm({
                              appId: row.appId,
                              name: row.name,
                              targetUrl: row.targetUrl,
                              status: row.status,
                              description: row.description,
                            });
                          }}
                        >
                          编辑
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy || !isEditable(row)}
                          onClick={() => void revoke(row.id)}
                        >
                          吊销
                        </Button>
                      </div>
                    </div>
                  ))}
                  <div className="text-muted flex items-center justify-between text-xs">
                    <span>
                      {paginationLabel({
                        page: subscriptionPage,
                        size: 20,
                        total: subscriptionTotal,
                      })}
                    </span>
                    <span className="flex gap-1">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={subscriptionPage === 0 || busy}
                        onClick={() => setSubscriptionPage((p) => Math.max(p - 1, 0))}
                      >
                        上一页
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={(subscriptionPage + 1) * 20 >= subscriptionTotal || busy}
                        onClick={() => setSubscriptionPage((p) => p + 1)}
                      >
                        下一页
                      </Button>
                    </span>
                  </div>
                </div>
              )}
            </div>
          </SectionCard>

          <SectionCard
            title={editingSubscriptionId ? '修改订阅' : '登记订阅'}
            description="订阅端点是**外部系统**的地址（下游）。平台会往这个地址发带签名的事件 —— 但投递执行器尚未接入（归 18.1.2.2）。"
          >
            <div className="grid gap-3 md:grid-cols-2">
              <label className="space-y-1 text-sm">
                <span className="text-muted">所属应用 id</span>
                <Input
                  value={subscriptionForm.appId}
                  placeholder="platform_app 的 id"
                  onChange={(e) =>
                    setSubscriptionForm({ ...subscriptionForm, appId: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">订阅名</span>
                <Input
                  value={subscriptionForm.name}
                  placeholder="订单事件"
                  onChange={(e) => setSubscriptionForm({ ...subscriptionForm, name: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm md:col-span-2">
                <span className="text-muted">端点地址</span>
                <Input
                  value={subscriptionForm.targetUrl}
                  placeholder="https://partner.example.com/hooks/starcon"
                  onChange={(e) =>
                    setSubscriptionForm({ ...subscriptionForm, targetUrl: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">状态</span>
                <select
                  className="border-input h-9 rounded-md border px-2 text-sm"
                  value={subscriptionForm.status}
                  onChange={(e) => setSubscriptionForm({ ...subscriptionForm, status: e.target.value })}
                >
                  {SUBSCRIPTION_STATUS_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">说明</span>
                <Input
                  value={subscriptionForm.description}
                  placeholder="对接方 / 用途"
                  onChange={(e) =>
                    setSubscriptionForm({ ...subscriptionForm, description: e.target.value })
                  }
                />
              </label>
            </div>
            <div className="mt-3 flex gap-2">
              <Button onClick={() => void submitSubscription()} disabled={busy}>
                {editingSubscriptionId ? '保存修改' : '登记订阅'}
              </Button>
              {editingSubscriptionId && (
                <Button
                  variant="outline"
                  disabled={busy}
                  onClick={() => {
                    setEditingSubscriptionId(null);
                    setSubscriptionForm(EMPTY_SUBSCRIPTION);
                  }}
                >
                  取消
                </Button>
              )}
            </div>
          </SectionCard>

          {detail && (
            <SectionCard
              title={`订阅详情 · ${detail.subscription.name}`}
              description="事件过滤、签名密钥与生效策略。签名密钥**明文只在生成 / 轮换那一次出现**，之后任何读接口都拿不回。"
            >
              <div className="space-y-4">
                {/* ------------------------------------------------ 事件过滤 */}
                <div className="space-y-2">
                  <h4 className="text-sm font-medium">事件过滤</h4>
                  {detail.filters.length === 0 ? (
                    <div className="text-muted text-sm">
                      还没有订阅任何事件 —— 订阅方收不到任何东西。
                    </div>
                  ) : (
                    detail.filters.map((f) => (
                      <div
                        key={f.id}
                        className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm"
                      >
                        <div className="space-y-1">
                          <span className="font-medium">
                            {f.eventType} v{f.schemaVersion}
                          </span>
                          <div className="text-muted text-xs">
                            过滤条件 {filterSummary(f.filter)} · {f.enabled ? '启用' : '停用'}
                          </div>
                        </div>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => void dropFilter(f.id)}
                        >
                          删除
                        </Button>
                      </div>
                    ))
                  )}
                  <div className="grid gap-2 md:grid-cols-3">
                    <label className="space-y-1 text-sm">
                      <span className="text-muted">事件类型</span>
                      <Input
                        value={filterForm.eventType}
                        placeholder="SkuProfileSaved"
                        onChange={(e) => setFilterForm({ ...filterForm, eventType: e.target.value })}
                      />
                    </label>
                    <label className="space-y-1 text-sm">
                      <span className="text-muted">schema 版本（留空 = 当前可用版本）</span>
                      <Input
                        value={filterForm.schemaVersion}
                        placeholder="1"
                        onChange={(e) =>
                          setFilterForm({ ...filterForm, schemaVersion: e.target.value })
                        }
                      />
                    </label>
                    <label className="space-y-1 text-sm">
                      <span className="text-muted">过滤条件（JSON 对象）</span>
                      <Input
                        value={filterForm.filter}
                        placeholder="{}"
                        onChange={(e) => setFilterForm({ ...filterForm, filter: e.target.value })}
                      />
                    </label>
                  </div>
                  <Button variant="outline" disabled={busy} onClick={() => void submitFilter()}>
                    添加事件过滤
                  </Button>
                </div>

                {/* ------------------------------------------------ 签名密钥 */}
                <div className="space-y-2">
                  <h4 className="text-sm font-medium">签名密钥</h4>
                  {detail.signingSecrets.length === 0 ? (
                    <div className="text-muted text-sm">
                      还没有签名密钥 —— 对接方无法验签。
                    </div>
                  ) : (
                    detail.signingSecrets.map((s) => (
                      <div
                        key={s.id}
                        className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm"
                      >
                        <div className="flex items-center gap-2">
                          <span className="font-medium">第 {s.version} 版</span>
                          <Badge tone={BADGE_TONE[secretStatusTone(s.status)]}>
                            {s.statusLabel}
                          </Badge>
                          <span className="text-muted text-xs">末四位 {s.secretHint}</span>
                        </div>
                      </div>
                    ))
                  )}
                  <div className="flex flex-wrap gap-2">
                    <Button disabled={busy} onClick={() => void issueSecret()}>
                      生成签名密钥
                    </Button>
                    <Button
                      variant="outline"
                      disabled={busy}
                      onClick={() => void rotateSecret()}
                    >
                      轮换
                    </Button>
                    <Button variant="outline" disabled={busy} onClick={() => void revokeSecret()}>
                      吊销
                    </Button>
                  </div>
                  {secrets && (
                    <div className="rounded-md border border-warning p-3 text-sm">
                      <div className="flex items-center gap-2 font-medium">
                        <TriangleAlert className="h-4 w-4" />
                        签名密钥明文（只出现这一次）
                      </div>
                      <code className="mt-2 block overflow-x-auto rounded bg-muted p-2 text-xs">
                        {secretPlaintext(secrets)}
                      </code>
                      <p className="text-muted mt-2 text-xs">{secrets.warning}</p>
                    </div>
                  )}
                </div>

                {/* ------------------------------------------------ 生效策略 */}
                <div className="space-y-1">
                  <h4 className="text-sm font-medium">生效的重试与死信策略</h4>
                  {detail.effectivePolicy ? (
                    <div className="text-muted text-xs">
                      最多尝试 {detail.effectivePolicy.maxAttempts} 次 · 首次退避{' '}
                      {detail.effectivePolicy.initialBackoffSeconds}s · 倍数{' '}
                      {detail.effectivePolicy.backoffMultiplier} · 单次退避上限{' '}
                      {detail.effectivePolicy.maxBackoffSeconds}s · 超时{' '}
                      {detail.effectivePolicy.requestTimeoutSeconds}s · 死信保留{' '}
                      {detail.effectivePolicy.deadLetterRetentionDays} 天 · 签名{' '}
                      {detail.effectivePolicy.signatureAlgorithm}（
                      {detail.effectivePolicy.signatureHeader}）
                    </div>
                  ) : (
                    <div className="text-muted text-xs">
                      没有配置策略 —— 投递执行器接入后需要一份（现在还没有默认策略）。
                    </div>
                  )}
                </div>

                {/* ------------------------------------------------ 签名试算 */}
                <div className="space-y-2">
                  <h4 className="text-sm font-medium">签名试算</h4>
                  <p className="text-muted text-xs">
                    给定样例载荷，算出平台**会发**的签名头，让对接方当场核对。
                    🔴 **不投递、不写任何投递记录**。
                  </p>
                  <label className="block space-y-1 text-sm">
                    <span className="text-muted">样例载荷</span>
                    <textarea
                      className="border-input min-h-20 w-full rounded-md border p-2 font-mono text-xs"
                      value={previewPayload}
                      onChange={(e) => setPreviewPayload(e.target.value)}
                    />
                  </label>
                  <Button variant="outline" disabled={busy} onClick={() => void runPreview()}>
                    试算签名
                  </Button>
                  {preview && (
                    <div className="space-y-2 rounded-md border p-3 text-sm">
                      <div className="text-xs">
                        <span className="text-muted">签名头：</span>
                        <code className="ml-1 break-all">{signatureHeaderLabel(preview)}</code>
                      </div>
                      <ol className="text-muted list-decimal space-y-1 pl-5 text-xs">
                        {signatureVerificationSteps(preview).map((step) => (
                          <li key={step}>{step}</li>
                        ))}
                      </ol>
                      <p className="text-muted text-xs">{preview.note}</p>
                    </div>
                  )}
                </div>
              </div>
            </SectionCard>
          )}
        </div>
      )}

      {/* ------------------------------------------------------ 策略 */}
      {tab === 'policies' && (
        <div className="space-y-3">
          <SectionCard
            title="重试与死信策略"
            description="🔴 这里只是**策略配置**：最多尝试几次、退避怎么算、超时多久、死信留几天。执行与死信本身归 18.1.2.2「投递记录」。租户级默认策略对所有订阅生效，订阅级专属策略覆盖它。"
          >
            {loading ? (
              <div className="text-muted flex items-center gap-2 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
              </div>
            ) : policies.length === 0 ? (
              <EmptyState
                icon={<ScrollText className="h-6 w-6" />}
                title="还没有配置任何策略"
                description="投递执行器接入之前这份配置不影响任何东西，但它定义了「接入之后按什么重试」。"
              />
            ) : (
              <div className="space-y-2">
                {policies.map((p) => (
                  <div
                    key={p.id}
                    className="flex flex-wrap items-start justify-between gap-2 rounded-md border p-3 text-sm"
                  >
                    <div className="space-y-1">
                      <span className="font-medium">
                        {p.subscriptionId ? '订阅专属' : '租户默认'}
                      </span>
                      <div className="text-muted text-xs">
                        最多尝试 {p.maxAttempts} 次 · 首次退避 {p.initialBackoffSeconds}s · 倍数{' '}
                        {p.backoffMultiplier} · 单次退避上限 {p.maxBackoffSeconds}s · 超时{' '}
                        {p.requestTimeoutSeconds}s · 死信保留 {p.deadLetterRetentionDays} 天
                      </div>
                    </div>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busy}
                      onClick={() => {
                        setEditingPolicyId(p.id);
                        setPolicyForm({
                          subscriptionId: p.subscriptionId,
                          maxAttempts: String(p.maxAttempts),
                          initialBackoffSeconds: String(p.initialBackoffSeconds),
                          backoffMultiplier: String(p.backoffMultiplier),
                          maxBackoffSeconds: String(p.maxBackoffSeconds),
                          requestTimeoutSeconds: String(p.requestTimeoutSeconds),
                          deadLetterRetentionDays: String(p.deadLetterRetentionDays),
                        });
                      }}
                    >
                      编辑
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard
            title={editingPolicyId ? '修改策略' : '新建策略'}
            description="订阅 id 留空 = 租户级默认策略；填订阅 id = 该订阅的专属策略。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <label className="space-y-1 text-sm">
                <span className="text-muted">订阅 id（留空 = 租户默认）</span>
                <Input
                  value={policyForm.subscriptionId}
                  onChange={(e) => setPolicyForm({ ...policyForm, subscriptionId: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">最多尝试次数</span>
                <Input
                  value={policyForm.maxAttempts}
                  onChange={(e) => setPolicyForm({ ...policyForm, maxAttempts: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">首次退避秒数</span>
                <Input
                  value={policyForm.initialBackoffSeconds}
                  onChange={(e) =>
                    setPolicyForm({ ...policyForm, initialBackoffSeconds: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">退避倍数</span>
                <Input
                  value={policyForm.backoffMultiplier}
                  onChange={(e) =>
                    setPolicyForm({ ...policyForm, backoffMultiplier: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">单次退避上限（秒）</span>
                <Input
                  value={policyForm.maxBackoffSeconds}
                  onChange={(e) =>
                    setPolicyForm({ ...policyForm, maxBackoffSeconds: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">请求超时（秒）</span>
                <Input
                  value={policyForm.requestTimeoutSeconds}
                  onChange={(e) =>
                    setPolicyForm({ ...policyForm, requestTimeoutSeconds: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">死信保留天数</span>
                <Input
                  value={policyForm.deadLetterRetentionDays}
                  onChange={(e) =>
                    setPolicyForm({ ...policyForm, deadLetterRetentionDays: e.target.value })
                  }
                />
              </label>
            </div>
            <div className="mt-3 flex gap-2">
              <Button onClick={() => void submitPolicy()} disabled={busy}>
                {editingPolicyId ? '保存修改' : '新建策略'}
              </Button>
              {editingPolicyId && (
                <Button
                  variant="outline"
                  disabled={busy}
                  onClick={() => {
                    setEditingPolicyId(null);
                    setPolicyForm(EMPTY_POLICY);
                  }}
                >
                  取消
                </Button>
              )}
            </div>
          </SectionCard>
        </div>
      )}

      {/* ------------------------------------------------------ 审计 */}
      {tab === 'audit' && (
        <SectionCard
          title="订阅配置变更审计"
          description="🔴 只追加 —— 数据库触发器直接挡掉 UPDATE / DELETE。审计一旦可改就没有意义。"
        >
          {loading ? (
            <div className="text-muted flex items-center gap-2 text-sm">
              <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
            </div>
          ) : auditRows.length === 0 ? (
            <EmptyState
              icon={<ScrollText className="h-6 w-6" />}
              title="还没有审计记录"
              description="登记事件定义、创建订阅、生成签名密钥都会在这里留痕。"
            />
          ) : (
            <div className="space-y-2">
              {auditRows.map((row) => (
                <div
                  key={row.id}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm"
                >
                  <span className="font-medium">{auditActionLabel(row.action)}</span>
                  <span className="text-muted text-xs">{row.occurredAt}</span>
                </div>
              ))}
              <div className="text-muted flex items-center justify-between text-xs">
                <span>
                  {paginationLabel({ page: auditPage, size: 20, total: auditTotal })}
                </span>
                <span className="flex gap-1">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={auditPage === 0 || busy}
                    onClick={() => setAuditPage((p) => Math.max(p - 1, 0))}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={(auditPage + 1) * 20 >= auditTotal || busy}
                    onClick={() => setAuditPage((p) => p + 1)}
                  >
                    下一页
                  </Button>
                </span>
              </div>
            </div>
          )}
        </SectionCard>
      )}

      {/* ------------------------------------------------------ 能力声明 */}
      {tab === 'capabilities' && (
        <div className="space-y-3">
          <SectionCard
            title="能力声明"
            description="逐条说清：有没有、依据是什么、缺了会怎样、归谁。"
          >
            {loading ? (
              <div className="text-muted flex items-center gap-2 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
              </div>
            ) : (
              <div className="space-y-2">
                {(capabilities?.capabilities ?? []).map((c) => (
                  <div
                    key={`${c.module}-${c.name}`}
                    className="flex flex-wrap items-start justify-between gap-2 rounded-md border p-3 text-sm"
                  >
                    <div className="space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{c.name}</span>
                        <Badge tone={BADGE_TONE[capabilityTone(c.available)]}>
                          {c.available ? '已具备' : '未接入'}
                        </Badge>
                        {c.ownerModule && <Badge tone="info">归 {c.ownerModule}</Badge>}
                      </div>
                      <div className="text-muted text-xs">依据：{c.basis}</div>
                      {c.impact && <div className="text-muted text-xs">影响：{c.impact}</div>}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard title="下游缺口" description="谁没交付、归谁。">
            {(capabilities?.downstreamGaps ?? []).length === 0 ? (
              <div className="text-muted text-sm">没有登记下游缺口。</div>
            ) : (
              <div className="space-y-2">
                {(capabilities?.downstreamGaps ?? []).map((g) => (
                  <div key={`${g.module}-${g.name}`} className="rounded-md border p-3 text-sm">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{g.name}</span>
                      <Badge tone={BADGE_TONE[capabilityTone(g.available)]}>
                        {g.available ? '已具备' : '未交付'}
                      </Badge>
                      <Badge tone="info">{g.module}</Badge>
                    </div>
                    <div className="text-muted mt-1 text-xs">{g.detail}</div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {delivery && !delivery.available && (
            <div className="rounded-md border border-warning p-3 text-sm">
              <div className="flex items-center gap-2 font-medium">
                <FileKey2 className="h-4 w-4" />
                事件实际投递未接入
              </div>
              <p className="text-muted mt-1 text-xs">{unavailableHint(delivery)}</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
