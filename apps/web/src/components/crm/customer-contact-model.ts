import type {
  CrmCustomerContactCapabilities,
  CrmCustomerContactIngestInput,
  CrmCustomerContactIngestResult,
  CrmCustomerContactInput,
  CrmCustomerContactPage,
  CrmCustomerContactRow,
  CrmCustomerOptOutCheckResult,
  CrmCustomerOptOutEventPage,
  CrmCustomerOptOutInput,
  CrmCustomerOptOutPage,
  CrmCustomerOptOutRevokeInput,
  CrmCustomerOptOutRow,
  CrmCustomerTimelinePage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.3.2.2 接触记录前端模型。
 *
 * <p>蓝图：「记录与客户的所有接触（客服通话、消息、邮件、营销触达、售后沟通），
 * 可由各模块自动写入，支持时间线检索。」
 * 输出「接触时间线」。
 * 关系：上游 = 15.1、11 售后、15.5.2；下游 = 15.3.1.2。
 * **接触记录是自动化的豁免依据**——曾明确表示"不要再联系我"的客户，营销触达需排除。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>退订状态是一等公民</b>：页面必须把「这个人现在还能不能营销」摆在最显眼处 ——
 *       它是加粗句的落点，也是 15.5.2 将来要消费的豁免依据。</li>
 *   <li><b>退订与撤销都必须带原因</b>：没有原因的退订没法复核；没有理由的撤销
 *       下次没人说得清「为什么又给他发营销邮件了」。</li>
 *   <li><b>来源可分</b>：手工补录与上游模块自动写入是两类记录，页面要分得清
 *       （谁记的一眼看得出来）。</li>
 *   <li><b>不提供任何「发触达」的入口</b>：15.5.2.1 未交付，
 *       能力声明里 {@code marketing_outreach} 恒为 available=false。</li>
 *   <li><b>数量与时间只原样展示</b>：时长由后端给，页面不做任何前端算术。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有接触记录」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/customer-contacts}，与同服务的 {@code crm/customer-tags}、
 * {@code crm/message-inbox}、{@code crm/buyer-emails} **刻意不共用**。
 */

const base = 'crm/customer-contacts';

/** 补充信息快照（后端 jsonb，结构随来源而异）。 */
export type CustomerContactDetail = Record<string, unknown>;

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface CustomerContactParams {
  customerRef?: string | undefined;
  contactType?: string | undefined;
  direction?: string | undefined;
  origin?: string | undefined;
  sourceModule?: string | undefined;
  complianceState?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CustomerContactTimelineParams {
  customerRef: string;
  contactType?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CustomerContactOptOutParams {
  customerRef?: string | undefined;
  scope?: string | undefined;
  state?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CustomerContactOptOutCheckParams {
  customerRef?: string | undefined;
  customerRefs?: string | undefined;
}

function query(params: Record<string, string | number | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 能力声明

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchCustomerContactCapabilities() {
  return api<CrmCustomerContactCapabilities>(`${base}/capabilities`);
}

// ================================================================ 接触记录

/** 接触记录列表（真分页 + 真筛选）。 */
export function fetchCustomerContacts(params: CustomerContactParams = {}) {
  return api<CrmCustomerContactPage>(`${base}/contacts${query({ ...params })}`);
}

/** 单条接触记录。 */
export function fetchCustomerContact(contactId: string) {
  return api<CrmCustomerContactRow>(`${base}/contacts/${encodeURIComponent(contactId)}`);
}

/**
 * 手工补录一条接触记录。
 *
 * <p>⚠️ {@code contactType} **不含** OPT_OUT：退订是合规状态，
 * 只能走退订端点（显式动作）改 —— 退订不可被普通接触记录悄悄覆盖或撤销。
 */
export function createCustomerContact(input: CrmCustomerContactInput) {
  return api<CrmCustomerContactRow>(`${base}/contacts`, 'POST', input);
}

/**
 * 接触时间线（按客户）。
 *
 * <p>🔴 返回值里带**当前退订判定**（{@code optOut}）——
 * 那是加粗句「接触记录是自动化的豁免依据」的对外接口。
 */
export function fetchCustomerContactTimeline(params: CustomerContactTimelineParams) {
  return api<CrmCustomerTimelinePage>(`${base}/timeline${query({ ...params })}`);
}

/**
 * 上游模块自动写入一条接触记录（幂等）。
 *
 * <p>⚠️ 同一条上游记录重复写入**不会**重复记账；
 * 幂等命中时后端会明确说清（{@code created=false}），不是「写失败」。
 */
export function ingestCustomerContact(input: CrmCustomerContactIngestInput) {
  return api<CrmCustomerContactIngestResult>(`${base}/ingest`, 'POST', input);
}

// ================================================================ 退订 / 免打扰

/** 退订记录列表（真分页 + 真筛选）。 */
export function fetchCustomerContactOptOuts(params: CustomerContactOptOutParams = {}) {
  return api<CrmCustomerOptOutPage>(`${base}/opt-outs${query({ ...params })}`);
}

/** 单条退订记录。 */
export function fetchCustomerContactOptOut(optOutId: string) {
  return api<CrmCustomerOptOutRow>(`${base}/opt-outs/${encodeURIComponent(optOutId)}`);
}

/** 退订审计流水（只追加：谁在什么时候登记 / 撤销、依据是什么）。 */
export function fetchCustomerContactOptOutEvents(
  optOutId: string,
  params: { page?: number | undefined; size?: number | undefined } = {},
) {
  return api<CrmCustomerOptOutEventPage>(
    `${base}/opt-outs/${encodeURIComponent(optOutId)}/events${query({ ...params })}`,
  );
}

/**
 * 登记退订 / 免打扰（显式动作）。
 *
 * <p>⚠️ 必须写明依据（{@code reason}），否则后端 400。
 */
export function grantCustomerContactOptOut(input: CrmCustomerOptOutInput) {
  return api<CrmCustomerOptOutRow>(`${base}/opt-outs`, 'POST', input);
}

/**
 * 显式撤销退订。
 *
 * <p>⚠️ 必须写明撤销原因（{@code reason}），否则后端 400。
 * 撤销会留痕：追加一条只追加的审计事件，并在时间线上补一条记录。
 */
export function revokeCustomerContactOptOut(optOutId: string, input: CrmCustomerOptOutRevokeInput) {
  return api<CrmCustomerOptOutRow>(
    `${base}/opt-outs/${encodeURIComponent(optOutId)}/revoke`,
    'POST',
    input,
  );
}

/**
 * 可查询的豁免判定 —— 这个客户现在还能不能被营销。
 *
 * <p>给 {@code customerRef} 查单个；给 {@code customerRefs}（逗号分隔）查一批。
 */
export function fetchCustomerContactOptOutCheck(params: CustomerContactOptOutCheckParams) {
  return api<CrmCustomerOptOutCheckResult>(`${base}/opt-out-check${query({ ...params })}`);
}

// ================================================================ 可读形态

/** 接触类型的可读名。 */
export function customerContactTypeLabel(contactType: string): string {
  switch (contactType) {
    case 'CALL':
      return '客服通话';
    case 'MESSAGE':
      return '消息';
    case 'EMAIL':
      return '邮件';
    case 'MARKETING':
      return '营销触达';
    case 'AFTERSALES':
      return '售后沟通';
    case 'OPT_OUT':
      return '退订 / 免打扰';
    case 'OTHER':
      return '其它接触';
    default:
      return contactType;
  }
}

/** 接触方向的可读名。 */
export function customerContactDirectionLabel(direction: string): string {
  switch (direction) {
    case 'INBOUND':
      return '客户发起';
    case 'OUTBOUND':
      return '我们发起';
    default:
      return direction;
  }
}

/** 记录来源的可读名。 */
export function customerContactOriginLabel(origin: string): string {
  switch (origin) {
    case 'MANUAL':
      return '手工补录';
    case 'AUTO':
      return '自动写入';
    default:
      return origin;
  }
}

/** 合规状态的可读名。 */
export function customerContactComplianceLabel(complianceState: string): string {
  switch (complianceState) {
    case 'COMPLIANT':
      return '合规';
    case 'VIOLATION':
      return '违反退订豁免';
    default:
      return complianceState;
  }
}

/** 退订范围的可读名。 */
export function customerContactOptOutScopeLabel(scope: string): string {
  switch (scope) {
    case 'ALL':
      return '全部触达';
    case 'MARKETING':
      return '仅营销触达';
    default:
      return scope;
  }
}

/** 退订状态的可读名。 */
export function customerContactOptOutStateLabel(state: string): string {
  switch (state) {
    case 'ACTIVE':
      return '生效中';
    case 'REVOKED':
      return '已撤销';
    default:
      return state;
  }
}

/** 退订审计事件的可读名。 */
export function customerContactOptOutEventLabel(eventType: string): string {
  switch (eventType) {
    case 'GRANTED':
      return '登记退订';
    case 'REVOKED':
      return '撤销退订';
    default:
      return eventType;
  }
}

/**
 * 豁免判定的可读形态。
 *
 * <p>🔴 「不可营销」与「可以营销」必须是**两句话**：把退订画成「未标记」，
 * 会让「他明确说过不要再联系我」看起来像一条可选备注。
 */
export function customerContactSuppressedText(suppressed: boolean): string {
  return suppressed ? '不可营销' : '可以营销';
}

/** 补充信息快照转成可读对象（后端 jsonb，结构随来源而异）。 */
export function customerContactDetail(value: unknown): CustomerContactDetail {
  if (value && typeof value === 'object') return value as CustomerContactDetail;
  return {};
}

/**
 * 时长的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：秒数由后端给，这里只原样显示（带单位）。
 * 「3 分 05 秒」那种换算是算术 —— 前端只原样展示，避免各处口径不一。
 */
export function customerContactDurationText(durationSeconds: number | null | undefined): string {
  if (durationSeconds === null || durationSeconds === undefined) return '';
  return `${durationSeconds} 秒`;
}
