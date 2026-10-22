'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, Segmented, StatCard } from '@starcon/ui';
import {
  AlertTriangle,
  Ban,
  History,
  MessageSquare,
  PhoneCall,
  RefreshCw,
  ShieldOff,
  Undo2,
} from 'lucide-react';
import type {
  CrmCustomerContactCapabilities,
  CrmCustomerContactPage,
  CrmCustomerOptOutEventPage,
  CrmCustomerOptOutPage,
  CrmCustomerTimelinePage,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  createCustomerContact,
  customerContactDirectionLabel,
  customerContactDurationText,
  customerContactOriginLabel,
  customerContactSuppressedText,
  customerContactTypeLabel,
  fetchCustomerContactCapabilities,
  fetchCustomerContactOptOutEvents,
  fetchCustomerContactOptOuts,
  fetchCustomerContactTimeline,
  fetchCustomerContacts,
  grantCustomerContactOptOut,
  revokeCustomerContactOptOut,
} from './customer-contact-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.3.2.2 接触记录工作区。
 *
 * <p>页面按「先看清边界、再看时间线、最后才动手」的顺序排，因为蓝图加粗句是
 * 「**接触记录是自动化的豁免依据**——曾明确表示"不要再联系我"的客户，营销触达需排除」。
 * 界面上第一件要让人看到的就是**能力边界**与**豁免判定**，
 * 而不是一张可以直接点「群发」的按钮。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       11 售后自动写入、15.5.2.1 营销触达、15.3.1.2 客户详情三块 available=false，
 *       页面把它们<b>显式灰掉</b>并写清归属模块，<b>不提供</b>「发营销触达」这类假入口。</li>
 *   <li><b>接触记录</b> —— 真分页真筛选（客户 / 类型 / 方向 / 来源 / 合规 / 关键字 / 时间窗）。</li>
 *   <li><b>退订与豁免</b> —— 退订必须带依据；撤销必须带原因；两者都留痕。
 *       判定条明确写「可以营销 / 不可营销」。</li>
 *   <li><b>时间线</b> —— 按客户看接触时间线，顶部直接给出该客户的豁免判定。</li>
 * </ul>
 */
export function CustomerContactWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.contact.update');

  const [view, setView] = useState<'contacts' | 'optouts' | 'timeline'>('contacts');

  const [capabilities, setCapabilities] = useState<CrmCustomerContactCapabilities | null>(null);
  const [contacts, setContacts] = useState<CrmCustomerContactPage | null>(null);
  const [optOuts, setOptOuts] = useState<CrmCustomerOptOutPage | null>(null);
  const [events, setEvents] = useState<CrmCustomerOptOutEventPage | null>(null);
  const [timeline, setTimeline] = useState<CrmCustomerTimelinePage | null>(null);

  // 接触记录筛选
  const [contactType, setContactType] = useState('');
  const [direction, setDirection] = useState('');
  const [origin, setOrigin] = useState('');
  const [complianceState, setComplianceState] = useState('');
  const [customerRef, setCustomerRef] = useState('');
  const [keyword, setKeyword] = useState('');
  const [contactPageIndex, setContactPageIndex] = useState(0);

  // 退订筛选
  const [optOutCustomerRef, setOptOutCustomerRef] = useState('');
  const [optOutScope, setOptOutScope] = useState('');
  const [optOutState, setOptOutState] = useState('');
  const [optOutPageIndex, setOptOutPageIndex] = useState(0);

  // 时间线
  const [timelineCustomerRef, setTimelineCustomerRef] = useState('');
  const [timelinePageIndex, setTimelinePageIndex] = useState(0);

  // 手工补录
  const [manualCustomerRef, setManualCustomerRef] = useState('');
  const [manualCustomerName, setManualCustomerName] = useState('');
  const [manualType, setManualType] = useState('CALL');
  const [manualDirection, setManualDirection] = useState('INBOUND');
  const [manualOccurredAt, setManualOccurredAt] = useState('');
  const [manualSubject, setManualSubject] = useState('');
  const [manualSummary, setManualSummary] = useState('');
  const [manualDuration, setManualDuration] = useState('');

  // 登记退订 / 撤销
  const [optOutScopeInput, setOptOutScopeInput] = useState('ALL');
  const [optOutReason, setOptOutReason] = useState('');
  const [optOutCustomerName, setOptOutCustomerName] = useState('');
  const [revokeReason, setRevokeReason] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, contactPage, optOutPage] = await Promise.all([
        fetchCustomerContactCapabilities(),
        fetchCustomerContacts({
          contactType: contactType || undefined,
          direction: direction || undefined,
          origin: origin || undefined,
          complianceState: complianceState || undefined,
          customerRef: customerRef || undefined,
          keyword: keyword || undefined,
          page: contactPageIndex,
          size: 20,
        }),
        fetchCustomerContactOptOuts({
          customerRef: optOutCustomerRef || undefined,
          scope: optOutScope || undefined,
          state: optOutState || undefined,
          page: optOutPageIndex,
          size: 20,
        }),
      ]);
      setCapabilities(caps);
      setContacts(contactPage);
      setOptOuts(optOutPage);
    } catch (e) {
      setError(errorText(e, '接触记录工作区加载失败'));
    } finally {
      setLoading(false);
    }
  }, [
    contactType,
    direction,
    origin,
    complianceState,
    customerRef,
    keyword,
    contactPageIndex,
    optOutCustomerRef,
    optOutScope,
    optOutState,
    optOutPageIndex,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 时间线：按客户拉，同时带回该客户的豁免判定。 */
  const loadTimeline = useCallback(async () => {
    if (!timelineCustomerRef.trim()) {
      setActionError('请先填写客户标识再查时间线。');
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      const page = await fetchCustomerContactTimeline({
        customerRef: timelineCustomerRef.trim(),
        page: timelinePageIndex,
        size: 20,
      });
      setTimeline(page);
    } catch (e) {
      setActionError(errorText(e, '时间线读不到'));
    } finally {
      setBusy(false);
    }
  }, [timelineCustomerRef, timelinePageIndex]);

  const submitManual = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createCustomerContact({
        customerRef: manualCustomerRef.trim(),
        customerName: manualCustomerName.trim(),
        contactType: manualType,
        direction: manualDirection,
        occurredAt: manualOccurredAt.trim(),
        subject: manualSubject.trim(),
        summary: manualSummary.trim(),
        durationSeconds: manualDuration.trim() === '' ? null : Number(manualDuration.trim()),
        shopId: null,
        shopName: '',
        platformCode: '',
        orderReference: '',
        sourceModule: '',
        sourceRef: '',
        sourceNo: '',
        detail: {},
        complianceState: 'COMPLIANT',
      });
      setNotice(
        `已补录接触记录 ${created.contactNo}（${created.customerRef}）。` +
          '注意：退订 / 免打扰**不是**一种接触类型 —— 请到「退订与豁免」里显式登记。',
      );
      setManualSubject('');
      setManualSummary('');
      setManualDuration('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '接触记录补录失败'));
    } finally {
      setBusy(false);
    }
  }, [
    manualCustomerRef,
    manualCustomerName,
    manualType,
    manualDirection,
    manualOccurredAt,
    manualSubject,
    manualSummary,
    manualDuration,
    load,
  ]);

  const submitOptOut = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const granted = await grantCustomerContactOptOut({
        customerRef: optOutCustomerRef.trim(),
        customerName: optOutCustomerName.trim(),
        scope: optOutScopeInput,
        reason: optOutReason.trim(),
        evidence: {},
      });
      setNotice(
        `已登记 ${granted.customerRef} 的退订（范围：${granted.scopeLabel}）。` +
          '该客户此后会被营销触达排除 —— 撤销要走显式动作并留痕。',
      );
      setOptOutReason('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '退订登记失败'));
    } finally {
      setBusy(false);
    }
  }, [optOutCustomerRef, optOutCustomerName, optOutScopeInput, optOutReason, load]);

  const submitRevoke = useCallback(async (optOutId: string) => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      await revokeCustomerContactOptOut(optOutId, { reason: revokeReason.trim() });
      setNotice('已撤销该退订，并留下审计事件与时间线记录。');
      setRevokeReason('');
      const page = await fetchCustomerContactOptOutEvents(optOutId, { size: 20 });
      setEvents(page);
      await load();
    } catch (e) {
      setActionError(errorText(e, '撤销退订失败'));
    } finally {
      setBusy(false);
    }
  }, [revokeReason, load]);

  const availableDimensions = useMemo(
    () => capabilities?.dimensions.filter((d) => d.available) ?? [],
    [capabilities],
  );
  const unavailableDimensions = useMemo(
    () => capabilities?.dimensions.filter((d) => !d.available) ?? [],
    [capabilities],
  );

  const hasContactFilter = Boolean(
    contactType || direction || origin || complianceState || customerRef || keyword,
  );
  const hasOptOutFilter = Boolean(optOutCustomerRef || optOutScope || optOutState);

  if (loading && !contacts) {
    return (
      <SectionCard title="接触记录">
        <p className="text-muted-foreground text-copy-13">正在读取接触记录……</p>
      </SectionCard>
    );
  }

  if (error && !contacts) {
    return (
      <SectionCard title="接触记录">
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {error}
        </div>
        <div className="mt-3">
          <Button onClick={() => void load()} size="sm" variant="secondary">
            <RefreshCw className="mr-1 h-4 w-4" />
            重试
          </Button>
        </div>
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      {/* ---------------------------------------------------------- 能力边界 */}
      <SectionCard title="能力边界">
        <p className="text-muted-foreground text-copy-13">{capabilities?.highlight}</p>
        <ul className="mt-3 space-y-2">
          {availableDimensions.map((dimension) => (
            <li key={dimension.dimension} className="flex flex-wrap items-start gap-2">
              <Badge tone="success">可用</Badge>
              <span className="font-medium">{dimension.description}</span>
              <span className="text-muted-foreground">—— {dimension.source}</span>
            </li>
          ))}
          {unavailableDimensions.map((dimension) => (
            <li key={dimension.dimension} className="flex flex-wrap items-start gap-2">
              <Badge tone="warning">未接入</Badge>
              <span className="font-medium">{dimension.description}</span>
              <span className="text-muted-foreground">
                （归属模块 {dimension.ownerModule}）—— {dimension.impact}
              </span>
            </li>
          ))}
        </ul>
        <div className="border-warning/40 bg-warning/10 text-copy-13 mt-3 rounded-md px-3 py-2">
          <p className="flex items-start gap-2">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{capabilities?.exemptionNote}</span>
          </p>
          <p className="mt-2">{capabilities?.actionPolicy}</p>
        </div>
        <p className="text-muted-foreground mt-2 text-xs">{capabilities?.boundaryNote}</p>
      </SectionCard>

      {actionError && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {actionError}
        </div>
      )}
      {notice && (
        <div className="border-success/40 bg-success/10 text-copy-13 rounded-md px-3 py-2">
          {notice}
        </div>
      )}

      <Segmented
        value={view}
        onChange={setView}
        aria-label="接触记录工作区视图"
        options={[
          { value: 'contacts', label: '接触记录', icon: <PhoneCall className="h-4 w-4" /> },
          { value: 'optouts', label: '退订与豁免', icon: <ShieldOff className="h-4 w-4" /> },
          { value: 'timeline', label: '客户时间线', icon: <History className="h-4 w-4" /> },
        ]}
      />

      {/* ---------------------------------------------------------- 接触记录 */}
      {view === 'contacts' && (
        <SectionCard title="接触记录">
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="客户标识" hint="逻辑引用：本仓没有买家档案主数据。">
              <input
                className={control}
                value={customerRef}
                onChange={(e) => {
                  setCustomerRef(e.target.value);
                  setContactPageIndex(0);
                }}
                placeholder="BUYER-001"
              />
            </Field>
            <Field label="接触类型">
              <select
                className={control}
                value={contactType}
                onChange={(e) => {
                  setContactType(e.target.value);
                  setContactPageIndex(0);
                }}
              >
                <option value="">全部类型</option>
                {['CALL', 'MESSAGE', 'EMAIL', 'MARKETING', 'AFTERSALES', 'OPT_OUT', 'OTHER'].map(
                  (t) => (
                    <option key={t} value={t}>
                      {customerContactTypeLabel(t)}
                    </option>
                  ),
                )}
              </select>
            </Field>
            <Field label="方向">
              <select
                className={control}
                value={direction}
                onChange={(e) => {
                  setDirection(e.target.value);
                  setContactPageIndex(0);
                }}
              >
                <option value="">全部方向</option>
                <option value="INBOUND">客户发起</option>
                <option value="OUTBOUND">我们发起</option>
              </select>
            </Field>
            <Field label="来源" hint="手工补录与上游模块自动写入分开记账。">
              <select
                className={control}
                value={origin}
                onChange={(e) => {
                  setOrigin(e.target.value);
                  setContactPageIndex(0);
                }}
              >
                <option value="">全部来源</option>
                <option value="MANUAL">手工补录</option>
                <option value="AUTO">自动写入</option>
              </select>
            </Field>
            <Field label="合规状态">
              <select
                className={control}
                value={complianceState}
                onChange={(e) => {
                  setComplianceState(e.target.value);
                  setContactPageIndex(0);
                }}
              >
                <option value="">全部状态</option>
                <option value="COMPLIANT">合规</option>
                <option value="VIOLATION">违反退订豁免</option>
              </select>
            </Field>
            <Field label="关键字">
              <input
                className={control}
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setContactPageIndex(0);
                }}
                placeholder="记录号 / 客户 / 主题"
              />
            </Field>
          </div>

          <div className="mt-4">
            {contacts && contacts.items.length === 0 ? (
              hasContactFilter ? (
                <EmptyState
                  icon={<PhoneCall className="h-6 w-6" />}
                  title="当前筛选没有匹配的接触记录"
                  description="把筛选条件放宽一点再试 —— 「筛选无结果」和「还没有接触记录」是两件不同的事。"
                />
              ) : (
                <EmptyState
                  icon={<PhoneCall className="h-6 w-6" />}
                  title="还没有接触记录"
                  description="15.1 消息与 15.1.2.1 邮件会自动落成接触记录；线下通话、售后沟通等可以手工补录。"
                />
              )
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">客户</th>
                    <th className="py-2">类型</th>
                    <th className="py-2">方向</th>
                    <th className="py-2">来源</th>
                    <th className="py-2">发生时刻</th>
                    <th className="py-2">主题</th>
                    <th className="py-2">合规</th>
                  </tr>
                </thead>
                <tbody>
                  {(contacts?.items ?? []).map((row) => (
                    <tr key={row.id} className="border-border/60 border-t">
                      <td className="py-2">
                        <div className="font-medium">{row.customerRef}</div>
                        {row.customerName && (
                          <div className="text-muted-foreground text-xs">{row.customerName}</div>
                        )}
                      </td>
                      <td className="py-2">{row.contactTypeLabel}</td>
                      <td className="py-2">{row.directionLabel}</td>
                      <td className="py-2">
                        <div>{row.originLabel}</div>
                        {row.sourceModule && (
                          <div className="text-muted-foreground text-xs">
                            {row.sourceModule}
                            {row.sourceNo ? ` / ${row.sourceNo}` : ''}
                          </div>
                        )}
                      </td>
                      <td className="py-2">
                        <div>{row.occurredAt}</div>
                        {row.durationSeconds !== null && (
                          <div className="text-muted-foreground text-xs">
                            {customerContactDurationText(row.durationSeconds)}
                          </div>
                        )}
                      </td>
                      <td className="py-2">
                        <div>{row.subject}</div>
                        {row.summary && (
                          <div className="text-muted-foreground text-xs">{row.summary}</div>
                        )}
                      </td>
                      <td className="py-2">
                        <Badge tone={row.complianceState === 'VIOLATION' ? 'warning' : 'success'}>
                          {row.complianceStateLabel}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {contacts && contacts.totalPages > 1 && (
              <div className="text-muted-foreground mt-3 flex items-center gap-2 text-xs">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={contactPageIndex <= 0}
                  onClick={() => setContactPageIndex((i) => Math.max(0, i - 1))}
                >
                  上一页
                </Button>
                <span>
                  第 {contacts.page + 1} / {contacts.totalPages} 页，共 {contacts.totalElements} 条
                </span>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={contactPageIndex + 1 >= contacts.totalPages}
                  onClick={() => setContactPageIndex((i) => i + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 退订与豁免 */}
      {view === 'optouts' && (
        <>
          <SectionCard title="退订与豁免判定">
            <p className="text-muted-foreground text-copy-13">
              客户明确表示「不要再联系我」之后，营销触达必须被排除。登记必须带依据；
              撤销必须带原因 —— 两者都会留痕（审计事件 + 时间线记录）。
            </p>
            {writable && (
              <div className="mt-4 grid gap-3 md:grid-cols-2">
                <Field label="客户标识">
                  <input
                    className={control}
                    value={optOutCustomerRef}
                    onChange={(e) => setOptOutCustomerRef(e.target.value)}
                    placeholder="BUYER-001"
                  />
                </Field>
                <Field label="客户名称">
                  <input
                    className={control}
                    value={optOutCustomerName}
                    onChange={(e) => setOptOutCustomerName(e.target.value)}
                  />
                </Field>
                <Field label="退订范围">
                  <select
                    className={control}
                    value={optOutScopeInput}
                    onChange={(e) => setOptOutScopeInput(e.target.value)}
                  >
                    <option value="ALL">全部触达（不要再联系我）</option>
                    <option value="MARKETING">仅营销触达</option>
                  </select>
                </Field>
                <Field label="依据" hint="必填：他凭什么说不要再联系。">
                  <input
                    className={control}
                    value={optOutReason}
                    onChange={(e) => setOptOutReason(e.target.value)}
                    placeholder="客户在站内信里说不要再发促销邮件"
                  />
                </Field>
              </div>
            )}
            {writable && (
              <div className="mt-3">
                <Button disabled={busy} onClick={() => void submitOptOut()}>
                  <Ban className="mr-1 h-4 w-4" />
                  登记退订
                </Button>
              </div>
            )}
          </SectionCard>

          <SectionCard title="退订记录">
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="客户标识">
                <input
                  className={control}
                  value={optOutCustomerRef}
                  onChange={(e) => {
                    setOptOutCustomerRef(e.target.value);
                    setOptOutPageIndex(0);
                  }}
                />
              </Field>
              <Field label="范围">
                <select
                  className={control}
                  value={optOutScope}
                  onChange={(e) => {
                    setOptOutScope(e.target.value);
                    setOptOutPageIndex(0);
                  }}
                >
                  <option value="">全部范围</option>
                  <option value="ALL">全部触达</option>
                  <option value="MARKETING">仅营销触达</option>
                </select>
              </Field>
              <Field label="状态">
                <select
                  className={control}
                  value={optOutState}
                  onChange={(e) => {
                    setOptOutState(e.target.value);
                    setOptOutPageIndex(0);
                  }}
                >
                  <option value="">全部状态</option>
                  <option value="ACTIVE">生效中</option>
                  <option value="REVOKED">已撤销</option>
                </select>
              </Field>
            </div>

            <div className="mt-4">
              {optOuts && optOuts.items.length === 0 ? (
                hasOptOutFilter ? (
                  <EmptyState
                    icon={<ShieldOff className="h-6 w-6" />}
                    title="当前筛选没有匹配的退订记录"
                    description="把筛选条件放宽一点再试 —— 「筛选无结果」和「还没有退订记录」是两件不同的事。"
                  />
                ) : (
                  <EmptyState
                    icon={<ShieldOff className="h-6 w-6" />}
                    title="还没有退订记录"
                    description="客户明确说「不要再联系我」时，在这里登记一条（必须带依据）。"
                  />
                )
              ) : (
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">客户</th>
                      <th className="py-2">范围</th>
                      <th className="py-2">状态</th>
                      <th className="py-2">依据</th>
                      <th className="py-2">登记时刻</th>
                      <th className="py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {(optOuts?.items ?? []).map((row) => (
                      <tr key={row.id} className="border-border/60 border-t">
                        <td className="py-2">
                          <div className="font-medium">{row.customerRef}</div>
                          {row.customerName && (
                            <div className="text-muted-foreground text-xs">{row.customerName}</div>
                          )}
                        </td>
                        <td className="py-2">{row.scopeLabel}</td>
                        <td className="py-2">
                          <Badge tone={row.state === 'ACTIVE' ? 'warning' : 'success'}>
                            {row.stateLabel}
                          </Badge>
                        </td>
                        <td className="py-2">
                          <div>{row.reason}</div>
                          {row.state === 'REVOKED' && row.revokeReason && (
                            <div className="text-muted-foreground text-xs">
                              撤销原因：{row.revokeReason}
                            </div>
                          )}
                        </td>
                        <td className="py-2">{row.recordedAt}</td>
                        <td className="py-2 text-right">
                          {writable && row.state === 'ACTIVE' && (
                            <Button
                              size="sm"
                              variant="secondary"
                              disabled={busy}
                              onClick={() => void submitRevoke(row.id)}
                            >
                              <Undo2 className="mr-1 h-4 w-4" />
                              撤销退订
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {optOuts && optOuts.totalPages > 1 && (
                <div className="text-muted-foreground mt-3 flex items-center gap-2 text-xs">
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={optOutPageIndex <= 0}
                    onClick={() => setOptOutPageIndex((i) => Math.max(0, i - 1))}
                  >
                    上一页
                  </Button>
                  <span>
                    第 {optOuts.page + 1} / {optOuts.totalPages} 页，共 {optOuts.totalElements} 条
                  </span>
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={optOutPageIndex + 1 >= optOuts.totalPages}
                    onClick={() => setOptOutPageIndex((i) => i + 1)}
                  >
                    下一页
                  </Button>
                </div>
              )}
            </div>

            {writable && (
              <div className="mt-4 grid gap-3 md:grid-cols-2">
                <Field label="撤销原因" hint="撤销退订必须写明原因，否则后端拒绝。">
                  <input
                    className={control}
                    value={revokeReason}
                    onChange={(e) => setRevokeReason(e.target.value)}
                    placeholder="客户主动回来说可以继续联系"
                  />
                </Field>
              </div>
            )}
          </SectionCard>

          {events && (
            <SectionCard title="退订审计流水">
              <p className="text-muted-foreground text-copy-13">
                审计流水**只追加**：登记与撤销都记一笔，改不了也删不掉。
              </p>
              <table className="text-copy-13 mt-3 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">事件</th>
                    <th className="py-2">时刻</th>
                    <th className="py-2">原因</th>
                  </tr>
                </thead>
                <tbody>
                  {events.items.map((row) => (
                    <tr key={row.id} className="border-border/60 border-t">
                      <td className="py-2">{row.eventTypeLabel}</td>
                      <td className="py-2">{row.occurredAt}</td>
                      <td className="py-2">{row.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </SectionCard>
          )}
        </>
      )}

      {/* ---------------------------------------------------------- 客户时间线 */}
      {view === 'timeline' && (
        <>
          <SectionCard title="客户接触时间线">
            <div className="grid gap-3 md:grid-cols-2">
              <Field label="客户标识" hint="时间线按客户检索，客户标识是逻辑引用。">
                <input
                  className={control}
                  value={timelineCustomerRef}
                  onChange={(e) => {
                    setTimelineCustomerRef(e.target.value);
                    setTimelinePageIndex(0);
                  }}
                  placeholder="BUYER-001"
                />
              </Field>
            </div>
            <div className="mt-3">
              <Button disabled={busy} onClick={() => void loadTimeline()}>
                <History className="mr-1 h-4 w-4" />
                查时间线
              </Button>
            </div>

            {timeline && (
              <div className="mt-4 space-y-3">
                <div className="border-warning/40 bg-warning/10 text-copy-13 rounded-md px-3 py-2">
                  <p className="flex items-start gap-2">
                    <ShieldOff className="mt-0.5 h-4 w-4 shrink-0" />
                    <span>
                      豁免判定：<strong>{customerContactSuppressedText(timeline.optOut?.suppressed ?? false)}</strong>
                      {timeline.optOut?.suppressed ? (
                        <>
                          {' '}
                          —— 范围 {timeline.optOut.scopeLabel}，依据「{timeline.optOut.reason}」，
                          登记于 {timeline.optOut.recordedAt}。
                        </>
                      ) : (
                        ' —— 该客户当前没有生效中的退订。'
                      )}
                    </span>
                  </p>
                </div>

                {timeline.items.length === 0 ? (
                  <EmptyState
                    icon={<MessageSquare className="h-6 w-6" />}
                    title="该客户还没有接触记录"
                    description="「还没有接触记录」和「筛选无结果」是两件不同的事 —— 这里是真的还没有。"
                  />
                ) : (
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2">发生时刻</th>
                        <th className="py-2">类型</th>
                        <th className="py-2">方向</th>
                        <th className="py-2">来源</th>
                        <th className="py-2">主题</th>
                        <th className="py-2">合规</th>
                      </tr>
                    </thead>
                    <tbody>
                      {timeline.items.map((row) => (
                        <tr key={row.id} className="border-border/60 border-t">
                          <td className="py-2">{row.occurredAt}</td>
                          <td className="py-2">{row.contactTypeLabel}</td>
                          <td className="py-2">{row.directionLabel}</td>
                          <td className="py-2">
                            <div>{row.originLabel}</div>
                            {row.sourceModule && (
                              <div className="text-muted-foreground text-xs">
                                {row.sourceModule}
                              </div>
                            )}
                          </td>
                          <td className="py-2">
                            <div>{row.subject}</div>
                            {row.summary && (
                              <div className="text-muted-foreground text-xs">{row.summary}</div>
                            )}
                          </td>
                          <td className="py-2">
                            <Badge tone={row.complianceState === 'VIOLATION' ? 'warning' : 'success'}>
                              {row.complianceStateLabel}
                            </Badge>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                {timeline.totalPages > 1 && (
                  <div className="text-muted-foreground flex items-center gap-2 text-xs">
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={timelinePageIndex <= 0}
                      onClick={() => setTimelinePageIndex((i) => Math.max(0, i - 1))}
                    >
                      上一页
                    </Button>
                    <span>
                      第 {timeline.page + 1} / {timeline.totalPages} 页，共 {timeline.totalElements} 条
                    </span>
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={timelinePageIndex + 1 >= timeline.totalPages}
                      onClick={() => setTimelinePageIndex((i) => i + 1)}
                    >
                      下一页
                    </Button>
                  </div>
                )}
              </div>
            )}
          </SectionCard>

          <SectionCard title="手工补录接触记录">
            <p className="text-muted-foreground text-copy-13">
              线下通话、售后沟通等补录到这里。<strong>退订 / 免打扰不是一种接触类型</strong>
              —— 它是一个合规状态，只能走「退订与豁免」里的显式动作。
            </p>
            {writable && (
              <div className="mt-4 grid gap-3 md:grid-cols-2">
                <Field label="客户标识">
                  <input
                    className={control}
                    value={manualCustomerRef}
                    onChange={(e) => setManualCustomerRef(e.target.value)}
                    placeholder="BUYER-001"
                  />
                </Field>
                <Field label="客户名称">
                  <input
                    className={control}
                    value={manualCustomerName}
                    onChange={(e) => setManualCustomerName(e.target.value)}
                  />
                </Field>
                <Field label="接触类型">
                  <select
                    className={control}
                    value={manualType}
                    onChange={(e) => setManualType(e.target.value)}
                  >
                    {['CALL', 'MESSAGE', 'EMAIL', 'MARKETING', 'AFTERSALES', 'OTHER'].map((t) => (
                      <option key={t} value={t}>
                        {customerContactTypeLabel(t)}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="方向">
                  <select
                    className={control}
                    value={manualDirection}
                    onChange={(e) => setManualDirection(e.target.value)}
                  >
                    <option value="INBOUND">客户发起</option>
                    <option value="OUTBOUND">我们发起</option>
                  </select>
                </Field>
                <Field
                  label="发生时刻"
                  hint="带时区偏移量，例如 2026-10-10T08:00:00+08:00（补录按发生时刻排，不按登记时刻）。"
                >
                  <input
                    className={control}
                    value={manualOccurredAt}
                    onChange={(e) => setManualOccurredAt(e.target.value)}
                    placeholder="2026-10-10T08:00:00+08:00"
                  />
                </Field>
                <Field label="通话时长（秒）" hint="只有通话需要填；数量原样提交，页面不做换算。">
                  <input
                    className={control}
                    value={manualDuration}
                    onChange={(e) => setManualDuration(e.target.value)}
                    placeholder="183.5"
                  />
                </Field>
                <Field label="主题">
                  <input
                    className={control}
                    value={manualSubject}
                    onChange={(e) => setManualSubject(e.target.value)}
                    placeholder="关于发货的来电"
                  />
                </Field>
                <Field label="摘要">
                  <input
                    className={control}
                    value={manualSummary}
                    onChange={(e) => setManualSummary(e.target.value)}
                    placeholder="客户问什么时候发货"
                  />
                </Field>
              </div>
            )}
            {writable && (
              <div className="mt-3">
                <Button disabled={busy} onClick={() => void submitManual()}>
                  <MessageSquare className="mr-1 h-4 w-4" />
                  补录接触记录
                </Button>
              </div>
            )}
          </SectionCard>
        </>
      )}

      <div className="grid gap-3 md:grid-cols-3">
        <StatCard
          label="接触记录"
          value={contacts ? String(contacts.totalElements) : '—'}
          comparisonLabel="当前筛选下的接触记录条数"
        />
        <StatCard
          label="退订记录"
          value={optOuts ? String(optOuts.totalElements) : '—'}
          comparisonLabel="当前筛选下的退订（含已撤销）条数"
        />
        <StatCard
          label="豁免判定"
          value={
            capabilities?.dimensions.find((d) => d.dimension === 'opt_out_exemption_check')
              ? '可查询'
              : '—'
          }
          comparisonLabel="某个客户当前还能不能被营销"
        />
      </div>
    </div>
  );
}

function errorText(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return `${fallback}：${e.message}`;
  return fallback;
}
