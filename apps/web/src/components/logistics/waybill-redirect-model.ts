import type {
  BuyerNotificationTaskPage,
  CarrierRedirectRuleInput,
  CarrierRedirectRulePage,
  WaybillCancelInput,
  WaybillRedirectCapabilities,
  WaybillRedirectDetail,
  WaybillRedirectInput,
  WaybillRedirectPage,
  WaybillRedirectPrecheckResult,
  WaybillRedirectRow,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 10.2.1.3 改址与取消前端模型。
 *
 * <p>蓝图：「处理运单层面的改址（部分渠道支持且有时限）与取消，联动订单地址变更与
 * 平台通知。」加粗句是本模块存在的理由：**超时的改址请求要明确告知能否成功，
 * 而不是让客服猜。**
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>「已超时限」与「渠道没接通」必须分开显示。</b>这是本模块唯一真正重要的事。
 *       前者是「再也不会成功」（别让客服反复去重试），后者是「接通后仍可成功」
 *       （客服该去催渠道）。画成一个灰按钮或一句「暂不可用」，客服就只能猜 ——
 *       而猜错的代价是买家问三次、客服答三次。</li>
 *   <li><b>「规则未登记」≠「渠道不支持」。</b>前者是<b>系统不知道</b>（该去登记规则），
 *       后者是<b>明确不支持</b>（该换渠道或退款）。两者都画成「不支持」会让运营
 *       以为改配置也没用。</li>
 *   <li><b>「未派发」要先于「能不能成」说。</b>渠道没接通时这一票<b>压根没发出去</b>，
 *       显示成「处理中」会让人一直等一个永远不会到来的受理。</li>
 *   <li><b>汇总不受分页影响。</b>页脚显示筛选后的<b>全量</b>，不是当前页。</li>
 *   <li><b>通知任务只读。</b>页面上<b>没有</b>「发送 / 标记已送达」按钮 ——
 *       15 客户与客服未交付，一个假的「已送达」比没有通知更糟。</li>
 * </ul>
 */

const base = 'trade/logistics/waybill-address-change';

// ------------------------------------------------------------ 参数

export interface WaybillRedirectParams {
  carrierCode?: string;
  verdict?: string;
  outcome?: string;
  salesOrderNo?: string;
  keyword?: string;
  dateFrom?: string;
  dateTo?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export interface BuyerNotificationParams {
  notificationKind?: string;
  salesOrderNo?: string;
  page?: number;
  size?: number;
}

/** 把入参声明成 {@code object}（TS 的 interface 没有隐式索引签名）。 */
function query(params: object): string {
  const parts = Object.entries(params as Record<string, string | number | boolean | undefined | null>)
    .filter(([, v]) => v !== undefined && v !== null && `${v}` !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 读

export function fetchCapabilities(): Promise<WaybillRedirectCapabilities> {
  return api<WaybillRedirectCapabilities>(`${base}/capabilities`);
}

export function fetchRules(carrierCode?: string): Promise<CarrierRedirectRulePage> {
  return api<CarrierRedirectRulePage>(`${base}/rules${query({ carrierCode })}`);
}

export function fetchRedirects(params: WaybillRedirectParams): Promise<WaybillRedirectPage> {
  return api<WaybillRedirectPage>(`${base}/redirects${query(params)}`);
}

export function fetchRedirect(id: string): Promise<WaybillRedirectDetail> {
  return api<WaybillRedirectDetail>(`${base}/redirects/${id}`);
}

export function fetchNotifications(
  params: BuyerNotificationParams,
): Promise<BuyerNotificationTaskPage> {
  return api<BuyerNotificationTaskPage>(`${base}/notifications${query(params)}`);
}

// ------------------------------------------------------------ 写

export function saveRule(input: CarrierRedirectRuleInput): Promise<unknown> {
  return api(`${base}/rules`, 'POST', input);
}

export function submitRedirect(
  requestId: string,
  input: WaybillRedirectInput,
): Promise<WaybillRedirectRow> {
  return api<WaybillRedirectRow>(`${base}/redirects/${requestId}/submit`, 'POST', input);
}

export function submitCancel(
  requestId: string,
  input: WaybillCancelInput,
): Promise<unknown> {
  return api(`${base}/cancels/${requestId}/submit`, 'POST', input);
}

export function precheck(
  requestIds: string[],
  asOf?: string,
): Promise<WaybillRedirectPrecheckResult> {
  return api<WaybillRedirectPrecheckResult>(`${base}/precheck${query({ asOf })}`, 'POST', {
    requestIds,
  });
}

// ------------------------------------------------------------ 展示派生
//
// 下面的函数都是**纯函数**：把后端字段翻译成界面上要显示的话，
// 并且在「不可信 / 缺失」时给出安全回落，而不是静默吞掉。
// 它们的单测在 waybill-redirect-model.test.ts。

export type WaybillRedirectTone = 'ok' | 'warn' | 'bad' | 'muted' | 'wait';

/**
 * 「能不能成」的色调。
 *
 * <p>⚠️ 判定与结论是**两个维度**，所以色调先看结论（能不能成）再看原因：
 * 同样是 {@code canSucceed=false}，「再也不会成功」是红的、「接通后仍可成功」是黄的。
 * 只按 verdict 上色的话，这两种处境会同色 —— 而它们要的行动完全相反。
 */
export function verdictTone(row: {
  verdict: string;
  canSucceed: boolean;
  outcome: string;
}): WaybillRedirectTone {
  if (row.outcome === 'ACCEPTED') return 'ok';
  if (row.outcome === 'REJECTED') return 'bad';
  if (row.canSucceed) return 'ok';
  if (row.verdict === 'DEADLINE_PASSED') return 'bad'; // 再也不会成功
  if (row.verdict === 'CHANNEL_UNSUPPORTED') return 'bad'; // 一定不会成功
  if (row.verdict === 'RULE_UNREGISTERED') return 'warn'; // 系统不知道
  return 'warn'; // 在时限内但渠道没接通
}

/**
 * 客服可以直接念给买家的一句准话。
 *
 * <p>这是蓝图加粗句的字面落地：界面上必须有<b>一整句能照着说</b>的话，
 * 而不是让客服从「verdict / canSucceed / outcome」三个字段里自己拼。
 */
export function verdictSentence(row: {
  verdict: string;
  verdictLabel: string;
  canSucceed: boolean;
  canSucceedReason: string;
}): string {
  if (row.canSucceed) return `可以改（${row.verdictLabel}）`;
  if (row.verdict === 'DEADLINE_PASSED')
    return '**这一票再也不会改址成功**（不是重试一下就能过），请改用「取消 + 重新发货」或与买家确认其它方案';
  if (row.verdict === 'CHANNEL_UNSUPPORTED') return '该渠道不支持改址，**一定**不会成功';
  if (row.verdict === 'RULE_UNREGISTERED')
    return '尚未登记该渠道规则，本系统**不知道**能不能改（请先登记规则，不要据此答复买家）';
  return '在时限内，但渠道没接通：接通后仍可成功';
}

/** 距截止还有多少分钟的可读文案。null = 没有时限，**不编一个数字**。 */
export function deadlineText(minutesToDeadline?: number | null): string {
  if (minutesToDeadline === null || minutesToDeadline === undefined) return '未登记时限';
  if (minutesToDeadline < 0) return '已超时限';
  if (minutesToDeadline < 60) return `剩 ${minutesToDeadline} 分钟`;
  const hours = Math.floor(minutesToDeadline / 60);
  if (hours < 48) return `剩 ${hours} 小时`;
  return `剩 ${Math.floor(hours / 24)} 天`;
}

/**
 * 「未派发」必须**先于**「能不能成」显示。
 *
 * <p>渠道没接通时这一票压根没发出去；显示成「处理中」会让人一直等一个
 * 永远不会到来的受理。
 */
export function dispatchStateText(row: {
  outcome: string;
  canSucceed: boolean;
  carrierRef: string;
}): string {
  if (row.outcome === 'ACCEPTED') return `已受理（回执 ${row.carrierRef}）`;
  if (row.outcome === 'REJECTED') return `已拒收（回执 ${row.carrierRef || '无'}）`;
  return '**未派发**：渠道未接通，本系统不会凭空受理';
}

/** 汇总：强调覆盖筛选后的全量，而不是当前页。 */
export function redirectSummaryLine(page: WaybillRedirectPage): string {
  return (
    `筛选结果共 ${page.total} 条改址单` +
    `（时限内 ${page.withinWindow} · 已超时限 ${page.deadlinePassed}` +
    ` · 渠道不支持 ${page.channelUnsupported} · 规则未登记 ${page.ruleUnregistered}）` +
    ` ｜ 能成 ${page.canSucceedCount}` +
    ` ｜ 已受理 ${page.acceptedCount} · 已拒收 ${page.rejectedCount} · 未派发 ${page.notAttemptedCount}` +
    ` ｜ 已建买家通知 ${page.pendingNotificationCount}`
  );
}

/** 预判结果的一行摘要：分成「还能改 / 已经没戏 / 还没出号」三类。 */
export function precheckSummaryLine(result: WaybillRedirectPrecheckResult): string {
  return (
    `共判定 ${result.selected} 张运单：` +
    `已出号 ${result.issued}（其中 ${result.notIssued} 张未出号需到运单申请作废）` +
    `，此刻能改 ${result.canSucceed} 张，不能改 ${result.cannotSucceed} 张。` +
    `判定不落库，可反复问。`
  );
}

/** 下单 / 发起时刻。空值显示「—」而不是 1970 —— 那是「没有」而不是「1970 年」。 */
export function timeText(value?: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}
