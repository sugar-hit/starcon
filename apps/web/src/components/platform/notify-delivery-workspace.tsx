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
  BellOff,
  CircleAlert,
  ListChecks,
  Loader2,
  RefreshCw,
  RotateCcw,
  ScrollText,
  Send,
  ShieldCheck,
} from 'lucide-react';
import {
  buildDeliveryInput,
  buildRetryInput,
  buildUnsubscribeInput,
  blockedPhrase,
  channelLabel,
  deliveryRateExplanation,
  deliveryStatusHint,
  deliveryStatusLabel,
  deliveryStatusTone,
  emptyDeliveryDraft,
  emptyRecipientDraft,
  emptyRetryDraft,
  emptyUnsubscribeDraft,
  fetchBlocks,
  fetchCapabilities,
  fetchDeliveries,
  fetchDeliveryDetail,
  fetchStatistics,
  fetchUnsubscribes,
  groupAttemptsByRecipient,
  outcomeLabel,
  attemptSourceLabel,
  rateText,
  recordDelivery,
  registerUnsubscribe,
  retryDelivery,
  revokeUnsubscribe,
  scopeTypeLabel,
  statusCodeLabel,
  summarizeDeliveries,
  summarizeStatistics,
  unsubscribeActive,
  unsubscribePhrase,
  unsubscribeScopeLabel,
  unsubscribeSourceLabel,
  unsubscribeRateExplanation,
  validateDeliveryDraft,
  validateRetryDraft,
  validateUnsubscribeDraft,
  type DeliveryDraft,
  type RetryDraft,
  type Tone,
  type UnsubscribeDraft,
} from './notify-delivery-model';
import type {
  NotifyBlockRow,
  NotifyDeliveryCapabilities,
  NotifyDeliveryDetail,
  NotifyDeliveryRow,
  NotifyDeliveryStatistics,
  NotifyUnsubscribeRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.5.2.1「投递记录」工作区（卷 18 前端入口 `/notifications/deliveries`）。
 *
 * <p>蓝图：「<strong>投递记录</strong>：记录通知投递结果：模板、收件范围、渠道、状态、失败原因、
 * 重试；统计触达率与退订情况。输入：投递记录；重试请求。输出：投递日志 + 统计。
 * 关系：上游 = 18.5.1；下游 = 1.3.2.2 外部推送、15.5.2 触达。
 * <strong>退订必须被尊重并生效</strong>，反复触达退订用户是投诉与合规风险的主要来源。」
 *
 * <p>🔴 页面上的七条纪律：
 *
 * <ol>
 *   <li><b>「投过」不等于「送达了」</b>。五种投递状态分开显示，
 *       「待出结果」**不**显示成「失败」。</li>
 *   <li><b>触达率的分母不含被退订拦下的人</b>。触达率旁边必须同时显示
 *       「实际投递 N 人 / 被退订拦截 M 人」—— 一个孤零零的 100% 会掩盖
 *       「其实有 300 个人因为退订没收到」这件事。</li>
 *   <li><b>退订拦截不是静默丢弃</b>：拦截留痕单独一档，能看到
 *       「哪个收件人、命中了哪一档退订」。</li>
 *   <li><b>重试会重查退订</b>：重试结果里若状态变成「全部被退订拦截」，
 *       如实显示，**不**显示成「重试成功」。</li>
 *   <li><b>本模块不执行投递</b>：登记与重试都是**登记**入口，
 *       页面上的按钮写「登记」而不是「发送」。</li>
 *   <li><b>加载 / 空 / 错误三态齐全</b>：空态区分「还没有投递过」与「筛选无结果」。</li>
 *   <li><b>能力声明里 {@code available=false} 的项按原样显示并写明归谁</b>，
 *       不显示成「暂无」。</li>
 * </ol>
 */

type Tab = 'deliveries' | 'unsubscribes' | 'blocks' | 'capabilities';

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  bad: 'danger',
  idle: 'neutral',
};

const CHANNEL_OPTIONS = [
  { value: 'EMAIL', label: '邮件' },
  { value: 'SMS', label: '短信' },
  { value: 'PUSH', label: '移动推送' },
  { value: 'IN_APP', label: '站内信' },
  { value: 'WEBHOOK', label: 'Webhook' },
];

const STATUS_OPTIONS = [
  { value: 'PENDING', label: '待出结果' },
  { value: 'DELIVERED', label: '已送达' },
  { value: 'PARTIAL', label: '部分送达' },
  { value: 'FAILED', label: '全部失败' },
  { value: 'BLOCKED', label: '全部被退订拦截' },
];

const SCOPE_OPTIONS = [
  { value: 'SINGLE', label: '单个收件人' },
  { value: 'SEGMENT', label: '指定分群' },
  { value: 'SUBSCRIBERS', label: '订阅者全集' },
];

const UNSUBSCRIBE_SCOPE_OPTIONS = [
  { value: 'GLOBAL', label: '全部通知' },
  { value: 'TEMPLATE', label: '单个模板' },
  { value: 'SCENE', label: '整个场景' },
];

export function NotifyDeliveryWorkspace() {
  const [tab, setTab] = useState<Tab>('deliveries');

  const [capabilities, setCapabilities] = useState<NotifyDeliveryCapabilities | null>(null);
  const [statistics, setStatistics] = useState<NotifyDeliveryStatistics | null>(null);

  const [deliveries, setDeliveries] = useState<NotifyDeliveryRow[]>([]);
  const [channel, setChannel] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');

  const [unsubscribes, setUnsubscribes] = useState<NotifyUnsubscribeRow[]>([]);
  const [unsubscribeKeyword, setUnsubscribeKeyword] = useState('');
  const [blocks, setBlocks] = useState<NotifyBlockRow[]>([]);

  const [detail, setDetail] = useState<NotifyDeliveryDetail | null>(null);
  const [deliveryDraft, setDeliveryDraft] = useState<DeliveryDraft>(emptyDeliveryDraft());
  const [unsubscribeDraft, setUnsubscribeDraft] =
    useState<UnsubscribeDraft>(emptyUnsubscribeDraft());
  const [retryDraft, setRetryDraft] = useState<RetryDraft>(emptyRetryDraft());
  const [retryTarget, setRetryTarget] = useState<NotifyDeliveryRow | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const fail = (e: unknown) => {
    const message = e instanceof Error ? e.message : '请求失败，请稍后重试';
    setError(message);
  };

  const loadDeliveries = useCallback(async () => {
    const rows = await fetchDeliveries({ channel, status, keyword, limit: 100 });
    setDeliveries(rows);
  }, [channel, status, keyword]);

  const loadUnsubscribes = useCallback(async () => {
    const rows = await fetchUnsubscribes({ recipientRef: unsubscribeKeyword, limit: 100 });
    setUnsubscribes(rows);
  }, [unsubscribeKeyword]);

  const loadBlocks = useCallback(async () => {
    const rows = await fetchBlocks({ limit: 100 });
    setBlocks(rows);
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, stats] = await Promise.all([fetchCapabilities(), fetchStatistics()]);
      setCapabilities(caps);
      setStatistics(stats);
      await Promise.all([loadDeliveries(), loadUnsubscribes(), loadBlocks()]);
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, [loadDeliveries, loadUnsubscribes, loadBlocks]);

  useEffect(() => {
    void load();
  }, [load]);

  const hasDeliveryFilter = Boolean(channel || status || keyword);
  const summary = summarizeDeliveries(deliveries);
  const statSummary = summarizeStatistics(statistics);

  async function openDetail(id: string) {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchDeliveryDetail(id));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  async function submitDelivery() {
    const problem = validateDeliveryDraft(deliveryDraft);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const created = await recordDelivery(buildDeliveryInput(deliveryDraft));
      setNotice(
        `已登记投递：收件人 ${created.delivery.recipientCount} 人，其中 ${created.delivery.blockedCount} 人命中退订被拦截。`,
      );
      setDeliveryDraft(emptyDeliveryDraft());
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  async function submitRetry() {
    if (!retryTarget) return;
    const problem = validateRetryDraft(retryDraft);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await retryDelivery(retryTarget.id, buildRetryInput(retryDraft));
      setRetryTarget(null);
      setRetryDraft(emptyRetryDraft());
      // 🔴 重试的结果**如实显示**：全被退订拦下时状态是 BLOCKED，
      //    这里必须原样说出来，不能显示成「重试成功」。
      setNotice(
        result.delivery.status === 'BLOCKED'
          ? '本次重试的收件人全部命中退订，已拦截，未发出任何通知。'
          : `重试已登记：当前 ${result.delivery.deliveredCount} 人送达 / ${result.delivery.failedCount} 人失败，共 ${result.delivery.attemptCount} 次尝试。`,
      );
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  async function submitUnsubscribe() {
    const problem = validateUnsubscribeDraft(unsubscribeDraft);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const row = await registerUnsubscribe(buildUnsubscribeInput(unsubscribeDraft));
      setNotice(
        unsubscribeActive(row)
          ? `已登记退订：${row.recipientRef}（${unsubscribeScopeLabel(row.scopeType)}）。`
          : '已登记退订。',
      );
      setUnsubscribeDraft(emptyUnsubscribeDraft());
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  async function revoke(id: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await revokeUnsubscribe(id);
      setNotice('已撤销这条退订（软撤销：历史区间留着，只是不再拦截投递）。');
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="投递记录"
        description="记录通知投递结果：模板、收件范围、渠道、状态、失败原因、重试；统计触达率与退订情况。退订必须被尊重并生效 —— 命中退订的收件人会被拦下并计入统计。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            {loading ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <RefreshCw className="h-4 w-4" />
            )}
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-center gap-2 rounded-md border px-3 py-2 text-sm">
          <CircleAlert className="h-4 w-4 shrink-0" />
          {error}
        </div>
      )}
      {notice && (
        <div className="border-success/40 bg-success/5 text-success flex items-center gap-2 rounded-md border px-3 py-2 text-sm">
          <ShieldCheck className="h-4 w-4 shrink-0" />
          {notice}
        </div>
      )}

      {statistics && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="触达率" value={rateText(statistics.deliveryRate)} />
          <StatCard label="退订率" value={rateText(statistics.unsubscribeRate)} />
          <StatCard label="退订拦截" value={statistics.blockedAttempts} unit="次" />
          <StatCard label="实际投递" value={statistics.totalTargeted} unit="人" />
        </div>
      )}

      {statistics && (
        <SectionCard title="统计口径">
          <div className="text-muted space-y-1 text-xs">
            <div>{deliveryRateExplanation(statistics)}</div>
            <div>{unsubscribeRateExplanation(statistics)}</div>
            <div>
              拦截数 {statistics.blockedAttempts} 次：每一次都是「本该送达但因退订被拦下」的留痕，
              不静默丢弃。
            </div>
          </div>
        </SectionCard>
      )}

      <Segmented
        value={tab}
        onChange={setTab}
        aria-label="投递记录视图"
        options={[
          {
            value: 'deliveries',
            label: `投递记录（${summary.total}）`,
            icon: <ListChecks className="h-4 w-4" />,
          },
          {
            value: 'unsubscribes',
            label: `退订清单（${unsubscribes.length}）`,
            icon: <BellOff className="h-4 w-4" />,
          },
          {
            value: 'blocks',
            label: `拦截留痕（${blocks.length}）`,
            icon: <ShieldCheck className="h-4 w-4" />,
          },
          { value: 'capabilities', label: '能力声明', icon: <ScrollText className="h-4 w-4" /> },
        ]}
      />

      {tab === 'deliveries' && (
        <>
          <SectionCard
            title="投递记录"
            description="本模块只登记投递结果，不执行投递 —— 真正的渠道发送是下游 1.3.2.2 与 15.5.2 的事。"
          >
            <div className="flex flex-wrap items-end gap-2">
              <label className="space-y-1 text-sm">
                <span className="text-muted">关键词</span>
                <Input
                  value={keyword}
                  placeholder="模板 key / 收件范围"
                  onChange={(e) => setKeyword(e.target.value)}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">渠道</span>
                <select
                  className="border-input h-9 rounded-md border px-2 text-sm"
                  value={channel}
                  onChange={(e) => setChannel(e.target.value)}
                >
                  <option value="">全部</option>
                  {CHANNEL_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">状态</span>
                <select
                  className="border-input h-9 rounded-md border px-2 text-sm"
                  value={status}
                  onChange={(e) => setStatus(e.target.value)}
                >
                  <option value="">全部</option>
                  {STATUS_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <Button variant="outline" onClick={() => void loadDeliveries()} disabled={loading}>
                筛选
              </Button>
            </div>

            {loading ? (
              <div className="text-muted flex items-center gap-2 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
              </div>
            ) : deliveries.length === 0 ? (
              <EmptyState
                icon={<Send className="h-6 w-6" />}
                title={hasDeliveryFilter ? '没有匹配的投递记录' : '还没有任何投递记录'}
                description={
                  hasDeliveryFilter
                    ? '换个关键词或把筛选清掉再试。'
                    : '登记一次投递（下方表单），或等业务侧的通知流过来 —— 投递明细会在这里逐条落下来。'
                }
              />
            ) : (
              <div className="space-y-2">
                {deliveries.map((row) => (
                  <div
                    key={row.id}
                    className="flex flex-wrap items-start justify-between gap-2 rounded-md border p-3 text-sm"
                  >
                    <div className="space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{row.templateKey}</span>
                        <Badge tone={BADGE_TONE[deliveryStatusTone(row.status)]}>
                          {deliveryStatusLabel(row.status)}
                        </Badge>
                        <Badge tone="neutral">{channelLabel(row.channel)}</Badge>
                        <Badge tone="neutral">{scopeTypeLabel(row.scopeType)}</Badge>
                      </div>
                      <div className="text-muted text-xs">
                        收件人 {row.recipientCount} 人 · 实际投递 {row.targetedCount} 人 ·
                        被退订拦截 {row.blockedCount} 人 · 尝试 {row.attemptCount} 次
                      </div>
                      <div className="text-muted text-xs">
                        送达 {row.deliveredCount} · 失败 {row.failedCount} · 待出结果{' '}
                        {row.pendingCount}· 触达率 {rateText(row.deliveryRate)}
                      </div>
                      <div className="text-muted text-xs">{deliveryStatusHint(row)}</div>
                      {row.failureReason && (
                        <div className="text-destructive text-xs">
                          最近一次失败：{row.failureReason}
                        </div>
                      )}
                    </div>
                    <div className="flex gap-1">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() => void openDetail(row.id)}
                      >
                        详情
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() => {
                          setRetryTarget(row);
                          setRetryDraft(emptyRetryDraft());
                        }}
                      >
                        <RotateCcw className="h-4 w-4" />
                        重试
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="登记一次投递"
            description="逐个收件人先查退订：命中退订的不进投递尝试，改写一行拦截留痕并计入统计。"
          >
            <div className="space-y-3">
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                <label className="space-y-1 text-sm">
                  <span className="text-muted">模板 ID</span>
                  <Input
                    value={deliveryDraft.templateId}
                    placeholder="18.5.1.1 的模板 id"
                    onChange={(e) =>
                      setDeliveryDraft({ ...deliveryDraft, templateId: e.target.value })
                    }
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-muted">渠道</span>
                  <select
                    className="border-input h-9 rounded-md border px-2 text-sm"
                    value={deliveryDraft.channel}
                    onChange={(e) =>
                      setDeliveryDraft({ ...deliveryDraft, channel: e.target.value })
                    }
                  >
                    {CHANNEL_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-muted">收件范围</span>
                  <select
                    className="border-input h-9 rounded-md border px-2 text-sm"
                    value={deliveryDraft.scopeType}
                    onChange={(e) =>
                      setDeliveryDraft({ ...deliveryDraft, scopeType: e.target.value })
                    }
                  >
                    {SCOPE_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-muted">范围标识</span>
                  <Input
                    value={deliveryDraft.scopeRef}
                    placeholder="单个收件人时必填"
                    onChange={(e) =>
                      setDeliveryDraft({ ...deliveryDraft, scopeRef: e.target.value })
                    }
                  />
                </label>
              </div>

              <div className="space-y-2">
                {deliveryDraft.recipients.map((recipient, index) => (
                  <div key={index} className="grid gap-2 sm:grid-cols-5">
                    <Input
                      value={recipient.ref}
                      placeholder="收件人标识"
                      onChange={(e) => {
                        const next = [...deliveryDraft.recipients];
                        next[index] = { ...recipient, ref: e.target.value };
                        setDeliveryDraft({ ...deliveryDraft, recipients: next });
                      }}
                    />
                    <select
                      className="border-input h-9 rounded-md border px-2 text-sm"
                      value={recipient.outcome}
                      onChange={(e) => {
                        const next = [...deliveryDraft.recipients];
                        next[index] = { ...recipient, outcome: e.target.value };
                        setDeliveryDraft({ ...deliveryDraft, recipients: next });
                      }}
                    >
                      <option value="DELIVERED">已送达</option>
                      <option value="FAILED">失败</option>
                      <option value="PENDING">待出结果</option>
                    </select>
                    <Input
                      value={recipient.statusCode}
                      placeholder="响应码（0=无）"
                      onChange={(e) => {
                        const next = [...deliveryDraft.recipients];
                        next[index] = { ...recipient, statusCode: e.target.value };
                        setDeliveryDraft({ ...deliveryDraft, recipients: next });
                      }}
                    />
                    <Input
                      value={recipient.failureReason}
                      placeholder="失败原因"
                      onChange={(e) => {
                        const next = [...deliveryDraft.recipients];
                        next[index] = { ...recipient, failureReason: e.target.value };
                        setDeliveryDraft({ ...deliveryDraft, recipients: next });
                      }}
                    />
                    <Input
                      value={recipient.durationMs}
                      placeholder="耗时 ms"
                      onChange={(e) => {
                        const next = [...deliveryDraft.recipients];
                        next[index] = { ...recipient, durationMs: e.target.value };
                        setDeliveryDraft({ ...deliveryDraft, recipients: next });
                      }}
                    />
                  </div>
                ))}
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() =>
                      setDeliveryDraft({
                        ...deliveryDraft,
                        recipients: [...deliveryDraft.recipients, emptyRecipientDraft()],
                      })
                    }
                  >
                    加一个收件人
                  </Button>
                  {deliveryDraft.recipients.length > 1 && (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() =>
                        setDeliveryDraft({
                          ...deliveryDraft,
                          recipients: deliveryDraft.recipients.slice(0, -1),
                        })
                      }
                    >
                      去掉最后一个
                    </Button>
                  )}
                </div>
              </div>

              <Button onClick={() => void submitDelivery()} disabled={busy}>
                <Send className="h-4 w-4" />
                登记投递
              </Button>
            </div>
          </SectionCard>
        </>
      )}

      {tab === 'unsubscribes' && (
        <>
          <SectionCard
            title="退订清单"
            description="生效中的退订会在登记投递时被拦下；撤销退订是软撤销（历史区间留着，只是不再拦截）。"
          >
            <div className="flex flex-wrap items-end gap-2">
              <label className="space-y-1 text-sm">
                <span className="text-muted">收件人</span>
                <Input
                  value={unsubscribeKeyword}
                  placeholder="邮箱 / 手机号 / 设备号"
                  onChange={(e) => setUnsubscribeKeyword(e.target.value)}
                />
              </label>
              <Button variant="outline" onClick={() => void loadUnsubscribes()} disabled={loading}>
                筛选
              </Button>
            </div>

            {loading ? (
              <div className="text-muted flex items-center gap-2 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
              </div>
            ) : unsubscribes.length === 0 ? (
              <EmptyState
                icon={<BellOff className="h-6 w-6" />}
                title="还没有退订登记"
                description="登记一条退订后，它会在后续的投递登记里被尊重 —— 反复触达退订用户是投诉与合规风险的主要来源。"
              />
            ) : (
              <div className="space-y-2">
                {unsubscribes.map((row) => (
                  <div
                    key={row.id}
                    className="flex flex-wrap items-start justify-between gap-2 rounded-md border p-3 text-sm"
                  >
                    <div className="space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{row.recipientRef}</span>
                        <Badge tone={unsubscribeActive(row) ? 'warning' : 'neutral'}>
                          {unsubscribePhrase(row)}
                        </Badge>
                        <Badge tone="neutral">{unsubscribeScopeLabel(row.scopeType)}</Badge>
                        <Badge tone="neutral">{row.channel}</Badge>
                      </div>
                      <div className="text-muted text-xs">
                        来源 {unsubscribeSourceLabel(row.source)}
                        {row.scopeKey && row.scopeKey !== '*' ? ` · 范围 ${row.scopeKey}` : ''}
                        {row.reason ? ` · ${row.reason}` : ''}
                      </div>
                    </div>
                    {unsubscribeActive(row) && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() => void revoke(row.id)}
                      >
                        撤销
                      </Button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="登记一条退订"
            description="先查后登记：已经生效的同一条退订不会产生第二行。"
          >
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
              <label className="space-y-1 text-sm">
                <span className="text-muted">收件人标识</span>
                <Input
                  value={unsubscribeDraft.recipientRef}
                  onChange={(e) =>
                    setUnsubscribeDraft({ ...unsubscribeDraft, recipientRef: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">渠道</span>
                <select
                  className="border-input h-9 rounded-md border px-2 text-sm"
                  value={unsubscribeDraft.channel}
                  onChange={(e) =>
                    setUnsubscribeDraft({ ...unsubscribeDraft, channel: e.target.value })
                  }
                >
                  <option value="ALL">全部渠道</option>
                  {CHANNEL_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">退订范围</span>
                <select
                  className="border-input h-9 rounded-md border px-2 text-sm"
                  value={unsubscribeDraft.scopeType}
                  onChange={(e) =>
                    setUnsubscribeDraft({ ...unsubscribeDraft, scopeType: e.target.value })
                  }
                >
                  {UNSUBSCRIBE_SCOPE_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">范围标识</span>
                <Input
                  value={unsubscribeDraft.scopeKey}
                  placeholder="场景级退订时填场景名"
                  onChange={(e) =>
                    setUnsubscribeDraft({ ...unsubscribeDraft, scopeKey: e.target.value })
                  }
                />
              </label>
            </div>
            <div className="mt-2 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              <label className="space-y-1 text-sm">
                <span className="text-muted">模板 ID（模板级退订时必填）</span>
                <Input
                  value={unsubscribeDraft.templateId}
                  onChange={(e) =>
                    setUnsubscribeDraft({ ...unsubscribeDraft, templateId: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">来源</span>
                <select
                  className="border-input h-9 rounded-md border px-2 text-sm"
                  value={unsubscribeDraft.source}
                  onChange={(e) =>
                    setUnsubscribeDraft({ ...unsubscribeDraft, source: e.target.value })
                  }
                >
                  <option value="USER_REQUEST">收件人要求</option>
                  <option value="COMPLAINT">投诉</option>
                  <option value="ADMIN">运营登记</option>
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">原因</span>
                <Input
                  value={unsubscribeDraft.reason}
                  onChange={(e) =>
                    setUnsubscribeDraft({ ...unsubscribeDraft, reason: e.target.value })
                  }
                />
              </label>
            </div>
            <Button className="mt-2" onClick={() => void submitUnsubscribe()} disabled={busy}>
              登记退订
            </Button>
          </SectionCard>
        </>
      )}

      {tab === 'blocks' && (
        <SectionCard
          title="退订拦截留痕"
          description="每一次「本该送达但因退订被拦下」的留痕 —— 它回答「为什么这个收件人没收到」。"
        >
          {loading ? (
            <div className="text-muted flex items-center gap-2 text-sm">
              <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
            </div>
          ) : blocks.length === 0 ? (
            <EmptyState
              icon={<ShieldCheck className="h-6 w-6" />}
              title="还没有退订拦截"
              description="当一个收件人命中生效中的退订时，这里会出现一行 —— 不是静默丢弃。"
            />
          ) : (
            <div className="space-y-2">
              {blocks.map((row) => (
                <div key={row.id} className="rounded-md border p-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{row.recipientRef}</span>
                    <Badge tone="warning">{blockedPhrase(row)}</Badge>
                    <Badge tone="neutral">{channelLabel(row.channel)}</Badge>
                  </div>
                  <div className="text-muted mt-1 text-xs">拦截时间 {row.blockedAt}</div>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {tab === 'capabilities' && (
        <SectionCard title="能力声明" description="哪些真交付、哪些还没交付、归谁。">
          {capabilities?.capabilities.map((capability) => (
            <div key={capability.name} className="rounded-md border p-3 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{capability.name}</span>
                <Badge tone={capability.available ? 'success' : 'warning'}>
                  {capability.available ? '已交付' : '未交付'}
                </Badge>
                <Badge tone="neutral">{capability.moduleId}</Badge>
              </div>
              <div className="text-muted mt-1 text-xs">依据：{capability.evidence}</div>
              {capability.ownerModule && (
                <div className="text-muted text-xs">归属未交付模块：{capability.ownerModule}</div>
              )}
              {capability.note && <div className="text-muted text-xs">{capability.note}</div>}
            </div>
          ))}
        </SectionCard>
      )}

      {detail && (
        <SectionCard
          title="投递记录详情"
          description={`${detail.delivery.templateKey} · ${channelLabel(detail.delivery.channel)} · 触达率 ${rateText(detail.delivery.deliveryRate)}`}
          extra={
            <Button variant="outline" size="sm" onClick={() => setDetail(null)}>
              关闭
            </Button>
          }
        >
          <div className="text-muted space-y-1 text-xs">
            <div>
              收件人 {detail.delivery.recipientCount} 人 · 实际投递 {detail.delivery.targetedCount}{' '}
              人 · 被退订拦截 {detail.delivery.blockedCount} 人 · 尝试{' '}
              {detail.delivery.attemptCount} 次
            </div>
            <div>{deliveryStatusHint(detail.delivery)}</div>
          </div>

          <div className="mt-3 space-y-2">
            <div className="text-sm font-medium">逐次尝试（{detail.attempts.length}）</div>
            {detail.attempts.length === 0 ? (
              <div className="text-muted text-sm">还没有投递尝试记录。</div>
            ) : (
              groupAttemptsByRecipient(detail.attempts).map((group) => (
                <div key={group.recipientRef} className="rounded-md border p-3 text-sm">
                  <div className="font-medium">{group.recipientRef}</div>
                  <div className="mt-1 space-y-1">
                    {group.attempts.map((attempt) => (
                      <div
                        key={attempt.id}
                        className="text-muted flex flex-wrap items-center gap-2 text-xs"
                      >
                        <Badge tone="neutral">第 {attempt.attemptNo} 次</Badge>
                        <Badge
                          tone={
                            attempt.outcome === 'DELIVERED'
                              ? 'success'
                              : attempt.outcome === 'FAILED'
                                ? 'danger'
                                : 'warning'
                          }
                        >
                          {outcomeLabel(attempt.outcome)}
                        </Badge>
                        <Badge tone="neutral">{attemptSourceLabel(attempt.attemptSource)}</Badge>
                        <span>响应 {statusCodeLabel(attempt.statusCode)}</span>
                        <span>耗时 {attempt.durationMs} ms</span>
                        {attempt.failureReason && <span>原因 {attempt.failureReason}</span>}
                      </div>
                    ))}
                  </div>
                </div>
              ))
            )}
          </div>

          <div className="mt-3 space-y-2">
            <div className="text-sm font-medium">退订拦截（{detail.blocks.length}）</div>
            {detail.blocks.length === 0 ? (
              <div className="text-muted text-sm">这次投递没有被退订拦截过。</div>
            ) : (
              detail.blocks.map((block) => (
                <div
                  key={block.id}
                  className="text-muted flex flex-wrap items-center gap-2 text-xs"
                >
                  <span className="text-foreground font-medium">{block.recipientRef}</span>
                  <Badge tone="warning">{blockedPhrase(block)}</Badge>
                  <span>拦截时间 {block.blockedAt}</span>
                </div>
              ))
            )}
          </div>
        </SectionCard>
      )}

      {retryTarget && (
        <SectionCard
          title="发起重试"
          description={`${retryTarget.templateKey} · ${retryTarget.channel}。服务端会重查退订 —— 投递之后才退订的收件人，重试会被拦下。`}
          extra={
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                setRetryTarget(null);
                setRetryDraft(emptyRetryDraft());
              }}
            >
              关闭
            </Button>
          }
        >
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
            <label className="space-y-1 text-sm">
              <span className="text-muted">收件人（留空 = 全部失败 / 待出结果的）</span>
              <Input
                value={retryDraft.recipients}
                placeholder="多个用逗号或空格隔开"
                onChange={(e) => setRetryDraft({ ...retryDraft, recipients: e.target.value })}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted">响应码</span>
              <Input
                value={retryDraft.statusCode}
                onChange={(e) => setRetryDraft({ ...retryDraft, statusCode: e.target.value })}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted">失败原因</span>
              <Input
                value={retryDraft.failureReason}
                onChange={(e) => setRetryDraft({ ...retryDraft, failureReason: e.target.value })}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted">重试备注</span>
              <Input
                value={retryDraft.reason}
                onChange={(e) => setRetryDraft({ ...retryDraft, reason: e.target.value })}
              />
            </label>
          </div>
          <div className="mt-2 flex items-center gap-2">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={retryDraft.outcomeKnown}
                onChange={(e) => setRetryDraft({ ...retryDraft, outcomeKnown: e.target.checked })}
              />
              <span className="text-muted">
                本次重试已有结果（不勾 = 结果还没回，记「待出结果」）
              </span>
            </label>
            {retryDraft.outcomeKnown && (
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={retryDraft.success}
                  onChange={(e) => setRetryDraft({ ...retryDraft, success: e.target.checked })}
                />
                <span className="text-muted">成功</span>
              </label>
            )}
          </div>
          <Button className="mt-2" onClick={() => void submitRetry()} disabled={busy}>
            <RotateCcw className="h-4 w-4" />
            登记重试
          </Button>
        </SectionCard>
      )}
    </div>
  );
}
