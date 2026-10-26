'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader } from '@starcon/ui';
import { AlertTriangle, BellRing, Plus, RefreshCw, Send, Trash2 } from 'lucide-react';
import type {
  Access,
  ExternalChannel,
  ExternalChannelList,
  ExternalDelivery,
  ExternalDeliveryList,
  ExternalPushCapabilities,
  ExternalPushCandidates,
  ExternalPushResult,
  ExternalRetrySweep,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import {
  ALERT_STATE_LABELS,
  CARD_STYLE_LABELS,
  CHANNEL_CODE_LABELS,
  DELIVERY_STATUS_LABELS,
  NOTIFICATION_TYPE_LABELS,
  SOURCE_KIND_LABELS,
  buildChannelInput,
  buildPushInput,
  channelUnusableReason,
  channelUsable,
  draftOf,
  emptyChannelDraft,
  emptyPushDraft,
  failurePhrase,
  nextAttemptPhrase,
  splitByStatus,
  statusTone,
  summarizeDeliveries,
  validateChannelDraft,
  validatePushDraft,
  type ChannelDraft,
  type PushDraft,
} from './external-channel-push-model';

/**
 * 1.3.2.2 外部渠道推送（卷 01 · L3 1.3.2 预警订阅 → {@code /notifications/push}）。
 *
 * <p>蓝图原文：「把选定的通知推送到企业 IM（企业微信/钉钉/飞书）或邮件，
 * 支持卡片化消息与点击跳转。」「**输出**：外部渠道投递记录（成功/失败/重试）；
 * 失败自动重试并告警。」「<b>凭据不落库</b>，只存 secret manager 引用。」
 *
 * <p>🔴 <b>本页必须把「还没接通」说在前面</b>：
 * secret manager（18 系统）与外部 IM / 邮件通道在本仓**都未接通** ⇒
 * 登记渠道可用，但每一次投递都会**真的失败**并留下投递记录。
 * 页面显示「未接通 + 归属系统」，**不显示假成功** ——
 * 「通道未接通」与「本租户还没投过」是两件完全不同的事。
 *
 * <p>🔴 <b>凭据不落库</b>：表单里只填 secret manager 的**引用串**；
 * {@code https://…} 这类地址本体在前端就被挡住（服务端与数据库层各再挡一道）。
 *
 * <p>🔴 <b>租户来自已认证成员关系</b>：前端不持有、不下发任何租户 id，
 * 也不把「谁能改」当成客户端说了算 —— 服务端会独立再判一次。
 *
 * <p>页面分五块：能力与边界、外部渠道、推送、投递记录、以及三态（加载 / 错误 / 空）。
 */

/** 页面里的一条提示。 */
interface Notice {
  kind: 'error' | 'info';
  text: string;
}

/** 从 ApiError 里取出能直接显示的原因。 */
function messageOf(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  return '操作失败，请稍后重试';
}

/** 投递记录清单的筛选（空 = 不筛）。 */
interface DeliveryQuery {
  status: string;
  limit: number;
  offset: number;
}

export function ExternalChannelPushWorkspace() {
  const [capabilities, setCapabilities] = useState<ExternalPushCapabilities | null>(null);
  const [channels, setChannels] = useState<ExternalChannelList | null>(null);
  const [candidates, setCandidates] = useState<ExternalPushCandidates | null>(null);
  const [deliveries, setDeliveries] = useState<ExternalDeliveryList | null>(null);
  const [session, setSession] = useState<Access | null>(null);
  const [channelDraft, setChannelDraft] = useState<ChannelDraft | null>(null);
  const [editingChannel, setEditingChannel] = useState<ExternalChannel | null>(null);
  const [pushDraft, setPushDraft] = useState<PushDraft | null>(null);
  const [query, setQuery] = useState<DeliveryQuery>({ status: '', limit: 20, offset: 0 });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [notices, setNotices] = useState<Notice[]>([]);

  // 🔴 `notice` 必须是**稳定的**引用：它被下面每个 useCallback 当依赖，
  //   不包 useCallback 的话每个 useCallback 都会每次渲染重建（eslint 会报
  //   react-hooks/exhaustive-deps，页面也会白白重渲染）。
  const notice = useCallback((kind: Notice['kind'], text: string) => {
    setNotices((current) => [...current.filter((item) => item.kind !== kind), { kind, text }]);
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setNotices([]);
    try {
      const [caps, channelList, candidateList, current, deliveryList] = await Promise.all([
        api<ExternalPushCapabilities>('identity/external-channel-push/capabilities'),
        api<ExternalChannelList>('identity/external-channel-push/channels'),
        api<ExternalPushCandidates>('identity/external-channel-push/candidates'),
        api<Access>('auth/session'),
        api<ExternalDeliveryList>(
          `identity/external-channel-push/deliveries?limit=${query.limit}&offset=${query.offset}` +
            (query.status ? `&status=${query.status}` : ''),
        ),
      ]);
      setCapabilities(caps);
      setChannels(channelList);
      setCandidates(candidateList);
      setSession(current);
      setDeliveries(deliveryList);
    } catch (error) {
      notice('error', messageOf(error));
    } finally {
      setLoading(false);
    }
  }, [notice, query.limit, query.offset, query.status]);

  useEffect(() => {
    void load();
  }, [load]);

  const summary = useMemo(() => summarizeDeliveries(deliveries), [deliveries]);
  const grouped = useMemo(
    () => splitByStatus(deliveries?.deliveries ?? []),
    [deliveries],
  );

  /** 登记 / 修改一个渠道。 */
  const saveChannel = useCallback(async () => {
    if (!channelDraft) return;
    const reason = validateChannelDraft(channelDraft);
    if (reason) {
      notice('error', reason);
      return;
    }
    setSaving(true);
    setNotices([]);
    try {
      if (editingChannel) {
        await api<ExternalChannel>(
          `identity/external-channel-push/channels/${editingChannel.id}`,
          'PUT',
          buildChannelInput(channelDraft),
        );
      } else {
        await api<ExternalChannel>(
          'identity/external-channel-push/channels',
          'POST',
          buildChannelInput(channelDraft),
        );
      }
      setChannelDraft(null);
      setEditingChannel(null);
      await load();
      notice('info', editingChannel ? '渠道已保存。' : '渠道已登记。');
    } catch (error) {
      notice('error', messageOf(error));
    } finally {
      setSaving(false);
    }
  }, [channelDraft, editingChannel, load, notice]);

  /** 删一个渠道（投递历史留着）。 */
  const removeChannel = useCallback(
    async (channel: ExternalChannel) => {
      setSaving(true);
      setNotices([]);
      try {
        await api<void>(`identity/external-channel-push/channels/${channel.id}`, 'DELETE');
        await load();
        notice('info', `已删除「${channel.displayName}」。历史投递记录仍然保留。`);
      } catch (error) {
        notice('error', messageOf(error));
      } finally {
        setSaving(false);
      }
    },
    [load, notice],
  );

  /** 发起一次推送。 */
  const send = useCallback(async () => {
    if (!pushDraft) return;
    const reason = validatePushDraft(pushDraft);
    if (reason) {
      notice('error', reason);
      return;
    }
    setSaving(true);
    setNotices([]);
    try {
      const result = await api<ExternalPushResult>(
        'identity/external-channel-push/deliveries',
        'POST',
        buildPushInput(pushDraft),
      );
      setPushDraft(null);
      await load();
      const failed = result.failed;
      notice(
        failed > 0 ? 'error' : 'info',
        failed > 0
          ? `已受理 ${result.accepted} 条，但有 ${failed} 条投递失败（外部通道未接通），失败原因与重试排期见下方投递记录。`
          : `已受理 ${result.accepted} 条。`,
      );
    } catch (error) {
      notice('error', messageOf(error));
    } finally {
      setSaving(false);
    }
  }, [load, notice, pushDraft]);

  /** 手工重试一条。 */
  const retry = useCallback(
    async (delivery: ExternalDelivery) => {
      setSaving(true);
      setNotices([]);
      try {
        await api<ExternalDelivery>(
          `identity/external-channel-push/deliveries/${delivery.id}/retry`,
          'POST',
        );
        await load();
        notice('info', '已重试一次，结果见下方投递记录。');
      } catch (error) {
        notice('error', messageOf(error));
      } finally {
        setSaving(false);
      }
    },
    [load, notice],
  );

  /** 确认一条告警（只改告警状态，不改投递结果）。 */
  const acknowledge = useCallback(
    async (delivery: ExternalDelivery) => {
      setSaving(true);
      setNotices([]);
      try {
        await api<ExternalDelivery>(
          `identity/external-channel-push/deliveries/${delivery.id}/acknowledge-alert`,
          'POST',
        );
        await load();
        notice('info', '告警已确认（投递结果不变）。');
      } catch (error) {
        notice('error', messageOf(error));
      } finally {
        setSaving(false);
      }
    },
    [load, notice],
  );

  /** 扫一遍到期的重试（外部调度平时调它；这里也留一个手动入口）。 */
  const sweep = useCallback(async () => {
    setSaving(true);
    setNotices([]);
    try {
      const result = await api<ExternalRetrySweep>(
        'identity/external-channel-push/deliveries/retry-due',
        'POST',
      );
      await load();
      notice(
        'info',
        `到期扫描完成：扫到 ${result.scanned} 条，重试 ${result.retried} 条，投成 ${result.delivered} 条，仍失败 ${result.failed} 条。`,
      );
    } catch (error) {
      notice('error', messageOf(error));
    } finally {
      setSaving(false);
    }
  }, [load, notice]);

  return (
    <div className="space-y-6">
      <PageHeader
        title="外部渠道推送"
        description="把选定的通知推送到企业微信 / 钉钉 / 飞书 / 邮件，支持卡片化消息与点击跳转。"
        actions={
          <>
            <Button variant="outline" size="sm" onClick={() => void sweep()} disabled={saving}>
              <RefreshCw className="mr-1.5 h-4 w-4" />
              扫一遍到期重试
            </Button>
            <Button
              size="sm"
              onClick={() => {
                setChannelDraft(emptyChannelDraft());
                setEditingChannel(null);
              }}
              disabled={channelDraft !== null}
            >
              <Plus className="mr-1.5 h-4 w-4" />
              登记渠道
            </Button>
          </>
        }
      />

      {notices.map((item) => (
        <div
          key={item.kind}
          className={
            item.kind === 'error'
              ? 'text-destructive bg-destructive-soft rounded-md px-3 py-2 text-copy-13'
              : 'text-success bg-success-soft rounded-md px-3 py-2 text-copy-13'
          }
        >
          {item.text}
        </div>
      ))}

      {loading && !deliveries ? (
        <div className="text-muted-foreground px-1 py-10 text-center text-copy-13">
          正在读取外部渠道与投递记录…
        </div>
      ) : null}

      {!loading && !deliveries ? (
        <EmptyState
          icon={<AlertTriangle className="h-7 w-7" />}
          title="读取外部渠道推送失败"
          description="没有拿到渠道清单与投递记录。请检查网络后重试；若是权限问题，请让管理员授予「外部渠道推送查看」权限。"
          action={
            <Button variant="outline" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      ) : null}

      {deliveries ? (
        <>
          {/* ---------------------------------------------- 能力与边界 */}
          <section className="space-y-2">
            <h2 className="text-copy-14 text-foreground font-medium">能力与边界</h2>
            <div className="text-muted-foreground space-y-1 text-copy-13">
              <div>
                已登记 <b>{channels?.total ?? 0}</b> 个外部渠道（启用 {channels?.enabledCount ?? 0}{' '}
                个），本批投递记录 <b>{summary.total}</b> 条：已投递 {summary.delivered} · 待重试{' '}
                {summary.failed} · 推迟 {summary.deferred} · 已耗尽 {summary.exhausted}
                {summary.alerting > 0 ? ` · 待确认告警 ${summary.alerting}` : ''}。
              </div>
              <div>
                🔴 <b>secret manager</b>（{capabilities?.secretManagerOwnerModule ?? '18 系统'}）——
                {capabilities?.secretManagerAvailable
                  ? '已接通，渠道上登记的引用可以解析出凭据。'
                  : '尚未接通，所以渠道上只存引用串，凭据本体既不落库、也不会被伪造。'}
              </div>
              <div>
                🔴 <b>外部投递通道</b>（{capabilities?.deliveryOwnerModule ?? '18 系统'}）——
                {capabilities?.deliveryAvailable
                  ? '已接通。'
                  : '尚未接通，每一次投递尝试都会真的失败，并如实落成投递记录（失败码 + 重试排期 + 告警）。'}
              </div>
              <div>
                🔴 <b>消息模板管理</b>（{capabilities?.templateOwnerModule ?? '18.5.1.1'}）——
                {capabilities?.templateAvailable
                  ? '已交付。'
                  : '未交付，本页不提供模板编辑；投递记录里存的是发出当时的内容快照。'}
              </div>
              {(capabilities?.boundaries ?? []).map((item) => (
                <div key={item}>· {item}</div>
              ))}
            </div>
          </section>

          {/* ---------------------------------------------- 外部渠道 */}
          <section className="space-y-2">
            <div className="flex items-center gap-2">
              <h2 className="text-copy-14 text-foreground font-medium">外部渠道</h2>
              <Badge tone="info">只存 secret manager 引用</Badge>
            </div>
            {channels && channels.channels.length === 0 ? (
              <EmptyState
                icon={<BellRing className="h-7 w-7" />}
                title="本租户还没有登记外部渠道"
                description="登记一个企业微信 / 钉钉 / 飞书 / 邮件通道。注意这里填的是 secret manager 里的引用，不是 Webhook 地址本身 —— 凭据不落库。"
                action={
                  <Button onClick={() => setChannelDraft(emptyChannelDraft())}>登记渠道</Button>
                }
              />
            ) : (
              <ul className="divide-border divide-y rounded-md border">
                {(channels?.channels ?? []).map((channel) => (
                  <li key={channel.id} className="space-y-2 px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {channel.displayName}
                      </span>
                      <Badge tone="neutral">
                        {CHANNEL_CODE_LABELS[channel.channelCode] ?? channel.channelCode}
                      </Badge>
                      {channel.status === 'ENABLED' ? (
                        <Badge tone="success">已启用</Badge>
                      ) : (
                        <Badge tone="neutral">已停用</Badge>
                      )}
                      {channel.cardEnabled ? <Badge tone="info">支持卡片</Badge> : null}
                      {channel.linkEnabled ? <Badge tone="info">支持点击跳转</Badge> : null}
                      {channel.credentialResolvable ? null : (
                        <Badge tone="warning">凭据引用解析不了</Badge>
                      )}
                    </div>
                    <div className="text-muted-foreground text-copy-13 space-y-0.5">
                      <div>
                        凭据引用：<code className="text-foreground">{channel.secretRef}</code>
                        （引用串，不是凭据本体）
                      </div>
                      {!channel.credentialResolvable ? (
                        <div>{channelUnusableReason(channel)}</div>
                      ) : null}
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => {
                          setEditingChannel(channel);
                          setChannelDraft(draftOf(channel));
                        }}
                        disabled={saving}
                      >
                        改渠道
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => void removeChannel(channel)}
                        disabled={saving}
                      >
                        <Trash2 className="mr-1.5 h-4 w-4" />
                        删除
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {/* ---------------------------------------------- 渠道表单 */}
          {channelDraft ? (
            <section className="space-y-3 rounded-md border p-4">
              <h2 className="text-copy-14 text-foreground font-medium">
                {editingChannel ? '修改渠道' : '登记渠道'}
              </h2>
              <div className="grid gap-3 md:grid-cols-2">
                <label className="space-y-1">
                  <span className="text-copy-13 text-muted-foreground">外部渠道</span>
                  <select
                    className="border-control-border bg-card h-9 w-full rounded-md border px-2 text-copy-13"
                    value={channelDraft.channelCode}
                    onChange={(e) =>
                      setChannelDraft({ ...channelDraft, channelCode: e.target.value })
                    }
                  >
                    {Object.entries(CHANNEL_CODE_LABELS).map(([code, label]) => (
                      <option key={code} value={code}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="space-y-1">
                  <span className="text-copy-13 text-muted-foreground">显示名</span>
                  <Input
                    value={channelDraft.displayName}
                    placeholder="例如：跨境运营群机器人"
                    onChange={(e) =>
                      setChannelDraft({ ...channelDraft, displayName: e.target.value })
                    }
                  />
                </label>
                <label className="space-y-1 md:col-span-2">
                  <span className="text-copy-13 text-muted-foreground">
                    secret manager 引用（<b>不要填 Webhook 地址本身</b>）
                  </span>
                  <Input
                    value={channelDraft.secretRef}
                    placeholder="secret://projects/starcon/tenants/xxx/wecom"
                    onChange={(e) =>
                      setChannelDraft({ ...channelDraft, secretRef: e.target.value })
                    }
                  />
                </label>
                <label className="flex items-center gap-2 text-copy-13">
                  <input
                    type="checkbox"
                    checked={channelDraft.cardEnabled}
                    onChange={(e) =>
                      setChannelDraft({ ...channelDraft, cardEnabled: e.target.checked })
                    }
                  />
                  支持卡片化消息
                </label>
                <label className="flex items-center gap-2 text-copy-13">
                  <input
                    type="checkbox"
                    checked={channelDraft.linkEnabled}
                    onChange={(e) =>
                      setChannelDraft({ ...channelDraft, linkEnabled: e.target.checked })
                    }
                  />
                  支持点击跳转
                </label>
              </div>
              <div className="flex gap-2">
                <Button size="sm" onClick={() => void saveChannel()} disabled={saving}>
                  保存
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setChannelDraft(null);
                    setEditingChannel(null);
                  }}
                  disabled={saving}
                >
                  取消
                </Button>
              </div>
            </section>
          ) : null}

          {/* ---------------------------------------------- 推送 */}
          <section className="space-y-2">
            <div className="flex items-center gap-2">
              <h2 className="text-copy-14 text-foreground font-medium">推送</h2>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setPushDraft({
                    ...emptyPushDraft(),
                    recipientMembershipId: session?.membershipId ?? '',
                  });
                }}
                disabled={pushDraft !== null || !session}
              >
                <Send className="mr-1.5 h-4 w-4" />
                发起推送
              </Button>
            </div>
            <div className="text-muted-foreground text-copy-13">
              候选来自 1.3.2.1 的<b>真命中</b>（算不出来的不算命中）。收件人默认是当前登录成员，
              推送会按<b>收件人的通知偏好</b>过滤（没订阅的渠道会被拒，免打扰时段内会推迟）。
            </div>
            {candidates && candidates.candidates.length === 0 ? (
              <EmptyState
                icon={<BellRing className="h-7 w-7" />}
                title="没有可推送的候选"
                description="本租户还没有真命中记录。候选只来自 1.3.2.1 预警规则的命中（算不出来的不进候选）；通知投影类的内容由各域的只读投影提供，在前端聚合层取走后从「发起推送」里提交。"
              />
            ) : (
              <ul className="divide-border divide-y rounded-md border">
                {(candidates?.candidates ?? []).map((candidate) => (
                  <li key={candidate.sourceRef} className="space-y-1 px-4 py-3">
                    <div className="text-copy-14 text-foreground font-medium">{candidate.title}</div>
                    <div className="text-muted-foreground text-copy-13">
                      {candidate.body ?? '（无正文）'}
                    </div>
                    <div className="text-muted-foreground text-copy-13">
                      发生于 {candidate.occurredAt}
                      {candidate.objectType ? ` · ${candidate.objectType}` : ''}
                      {candidate.objectRef ? ` / ${candidate.objectRef}` : ''}
                    </div>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() =>
                        setPushDraft({
                          ...emptyPushDraft(),
                          sourceKind: 'ALERT_HIT',
                          alertHitId: candidate.sourceRef,
                          title: candidate.title,
                          body: candidate.body ?? '',
                          recipientMembershipId: session?.membershipId ?? '',
                        })
                      }
                      disabled={pushDraft !== null}
                    >
                      选它推送
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {/* ---------------------------------------------- 推送表单 */}
          {pushDraft ? (
            <section className="space-y-3 rounded-md border p-4">
              <h2 className="text-copy-14 text-foreground font-medium">发起推送</h2>
              <div className="grid gap-3 md:grid-cols-2">
                <label className="space-y-1">
                  <span className="text-copy-13 text-muted-foreground">推送来源</span>
                  <select
                    className="border-control-border bg-card h-9 w-full rounded-md border px-2 text-copy-13"
                    value={pushDraft.sourceKind}
                    onChange={(e) =>
                      setPushDraft({ ...pushDraft, sourceKind: e.target.value })
                    }
                  >
                    {Object.entries(SOURCE_KIND_LABELS).map(([code, label]) => (
                      <option key={code} value={code}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="space-y-1">
                  <span className="text-copy-13 text-muted-foreground">
                    通知类型（规则命中不带类型，由这里选定）
                  </span>
                  <select
                    className="border-control-border bg-card h-9 w-full rounded-md border px-2 text-copy-13"
                    value={pushDraft.notificationType}
                    onChange={(e) =>
                      setPushDraft({ ...pushDraft, notificationType: e.target.value })
                    }
                  >
                    {Object.entries(NOTIFICATION_TYPE_LABELS).map(([code, label]) => (
                      <option key={code} value={code}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="space-y-1">
                  <span className="text-copy-13 text-muted-foreground">消息样式</span>
                  <select
                    className="border-control-border bg-card h-9 w-full rounded-md border px-2 text-copy-13"
                    value={pushDraft.cardStyle}
                    onChange={(e) => setPushDraft({ ...pushDraft, cardStyle: e.target.value })}
                  >
                    {Object.entries(CARD_STYLE_LABELS).map(([code, label]) => (
                      <option key={code} value={code}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="space-y-1">
                  <span className="text-copy-13 text-muted-foreground">跳转目标（站内相对路径）</span>
                  <Input
                    value={pushDraft.linkUrl}
                    placeholder="/inventory/stockout-alerts"
                    onChange={(e) => setPushDraft({ ...pushDraft, linkUrl: e.target.value })}
                  />
                </label>
                {pushDraft.sourceKind === 'NOTIFICATION' ? (
                  <>
                    <label className="space-y-1">
                      <span className="text-copy-13 text-muted-foreground">上游主键</span>
                      <Input
                        value={pushDraft.notificationRef}
                        placeholder="SKU-0001"
                        onChange={(e) =>
                          setPushDraft({ ...pushDraft, notificationRef: e.target.value })
                        }
                      />
                    </label>
                    <label className="space-y-1">
                      <span className="text-copy-13 text-muted-foreground">标题</span>
                      <Input
                        value={pushDraft.title}
                        onChange={(e) => setPushDraft({ ...pushDraft, title: e.target.value })}
                      />
                    </label>
                  </>
                ) : null}
              </div>
              <div className="space-y-1">
                <span className="text-copy-13 text-muted-foreground">目标渠道</span>
                <div className="space-y-1">
                  {(channels?.channels ?? []).map((channel) => (
                    <label key={channel.id} className="flex items-center gap-2 text-copy-13">
                      <input
                        type="checkbox"
                        checked={pushDraft.channelIds.includes(channel.id)}
                        disabled={!channelUsable(channel)}
                        onChange={(e) =>
                          setPushDraft({
                            ...pushDraft,
                            channelIds: e.target.checked
                              ? [...pushDraft.channelIds, channel.id]
                              : pushDraft.channelIds.filter((id) => id !== channel.id),
                          })
                        }
                      />
                      {channel.displayName}
                      {channelUsable(channel) ? null : (
                        <span className="text-muted-foreground">
                          （{channelUnusableReason(channel)}，不可选）
                        </span>
                      )}
                    </label>
                  ))}
                </div>
              </div>
              <div className="flex gap-2">
                <Button size="sm" onClick={() => void send()} disabled={saving}>
                  推送
                </Button>
                <Button variant="ghost" size="sm" onClick={() => setPushDraft(null)} disabled={saving}>
                  取消
                </Button>
              </div>
            </section>
          ) : null}

          {/* ---------------------------------------------- 投递记录 */}
          <section className="space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-copy-14 text-foreground font-medium">投递记录</h2>
              <select
                className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
                value={query.status}
                onChange={(e) => setQuery({ ...query, status: e.target.value, offset: 0 })}
              >
                <option value="">全部状态</option>
                {Object.entries(DELIVERY_STATUS_LABELS).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
              <Button
                variant="outline"
                size="sm"
                onClick={() =>
                  setQuery({ ...query, offset: Math.max(0, query.offset - query.limit) })
                }
                disabled={query.offset === 0}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setQuery({ ...query, offset: query.offset + query.limit })}
                disabled={query.offset + query.limit >= summary.total}
              >
                下一页
              </Button>
              <span className="text-muted-foreground text-copy-13">
                第 {query.offset + 1} ~ {Math.min(query.offset + query.limit, summary.total)} 条 / 共{' '}
                {summary.total} 条
              </span>
            </div>
            {grouped.delivered.length +
              grouped.pending.length +
              grouped.deferred.length +
              grouped.exhausted.length ===
            0 ? (
              <EmptyState
                icon={<BellRing className="h-7 w-7" />}
                title="还没有投递记录"
                description="本租户还没有发起过外部渠道推送。发起一次后会在这里留下真实记录（成功 / 失败 / 推迟 / 重试耗尽）。"
              />
            ) : (
              <ul className="divide-border divide-y rounded-md border">
                {(deliveries?.deliveries ?? []).map((delivery) => (
                  <li key={delivery.id} className="space-y-1 px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">{delivery.title}</span>
                      <Badge
                        tone={
                          statusTone(delivery.status) === 'ok'
                            ? 'success'
                            : statusTone(delivery.status) === 'bad'
                              ? 'danger'
                              : statusTone(delivery.status) === 'warn'
                                ? 'warning'
                                : 'neutral'
                        }
                      >
                        {DELIVERY_STATUS_LABELS[delivery.status] ?? delivery.status}
                      </Badge>
                      <Badge tone="neutral">
                        {CHANNEL_CODE_LABELS[delivery.channelCode] ?? delivery.channelCode}
                      </Badge>
                      <Badge tone="neutral">
                        {SOURCE_KIND_LABELS[delivery.sourceKind] ?? delivery.sourceKind}
                      </Badge>
                      {delivery.alertState !== 'NONE' ? (
                        <Badge tone="danger">
                          {ALERT_STATE_LABELS[delivery.alertState] ?? delivery.alertState}
                        </Badge>
                      ) : null}
                    </div>
                    <div className="text-muted-foreground text-copy-13 space-y-0.5">
                      <div>
                        通知类型 {NOTIFICATION_TYPE_LABELS[delivery.notificationType] ??
                          delivery.notificationType} · 样式 {CARD_STYLE_LABELS[delivery.cardStyle] ??
                          delivery.cardStyle}
                        {delivery.linkUrl ? ` · 跳转 ${delivery.linkUrl}` : ''}
                      </div>
                      <div>
                        尝试 {delivery.attemptCount} / {delivery.maxAttempts} 次 ·{' '}
                        {nextAttemptPhrase(delivery)}
                      </div>
                      {delivery.status !== 'DELIVERED' ? (
                        <div className="text-destructive">{failurePhrase(delivery)}</div>
                      ) : null}
                      {delivery.alertMessage ? <div>{delivery.alertMessage}</div> : null}
                    </div>
                    <div className="flex flex-wrap gap-2">
                      {delivery.status === 'DELIVERED' ? null : (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => void retry(delivery)}
                          disabled={saving}
                        >
                          重试
                        </Button>
                      )}
                      {delivery.alertState === 'RAISED' ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => void acknowledge(delivery)}
                          disabled={saving}
                        >
                          确认告警
                        </Button>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      ) : null}
    </div>
  );
}
