import type {
  CrmCustomerMergeAmount,
  CrmCustomerMergeCandidateDetail,
  CrmCustomerMergeCandidatePage,
  CrmCustomerMergeCapabilities,
  CrmCustomerMergeLogicalDetail,
  CrmCustomerMergeLogicalPage,
  CrmCustomerMergeOperationPage,
  CrmCustomerMergeRunPage,
  CrmCustomerMergeScanResult,
  CrmCustomerMergeSignal,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.3.1.3 跨店合并前端模型。
 *
 * <p>蓝图：「把同一自然人在不同店铺的身份合并到一个逻辑客户，支持按邮箱/电话/姓名+地址/
 * 平台买家标识匹配，给出置信度并需人工确认，保留合并前映射可拆分。
 * **自动合并风险高**（同名不同人、同人不同邮箱），因此只给建议不自动执行，且必须可拆分。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>候选是「建议」不是「结论」</b>：队列里的每一行都还没合并。
 *       页面必须把「待确认」摆在最显眼的位置 —— 否则运营会以为点一下就合并了。</li>
 *   <li><b>置信度要能解释</b>：逐条列出命中了哪个信号、证据强度多少、
 *       比对的是什么（<b>脱敏形态</b>）。页面只原样展示，不自己算分。</li>
 *   <li><b>排除信号要显眼</b>：一侧已退订 ⇒ 合并会影响<b>所有来源</b>的触达，
 *       这一条不能藏在角落。</li>
 *   <li><b>金额按币种分行</b>：USD 与 EUR 加起来不是任何一个金额，
 *       页面把两个币种合成一个数就是造数据。</li>
 *   <li><b>可拆分是承诺</b>：拆出去之后来源行原样还在 —— 页面在拆分前就把这句话说出来。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没生成过候选」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/customer-merge}，与同服务的 {@code crm/customers}、
 * {@code crm/customer-tags}、{@code crm/customer-contacts} **刻意不共用**。
 */

const base = 'crm/customer-merge';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface CustomerMergeCandidateParams {
  status?: string | undefined;
  matchType?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 匹配依据的可读名（后端也给了 label，这份是**兜底**：后端没给时页面不至于显示裸码）。 */
export function customerMergeMatchTypeLabel(code: string | undefined): string {
  switch (code) {
    case 'PLATFORM_BUYER_ID':
      return '平台买家标识相同';
    case 'EMAIL':
      return '邮箱相同';
    case 'PHONE':
      return '电话相同';
    case 'NAME_ADDRESS':
      return '姓名与地址都相同';
    default:
      return code ?? '';
  }
}

/** 候选状态的可读名。 */
export function customerMergeStatusLabel(code: string | undefined): string {
  switch (code) {
    case 'SUGGESTED':
      return '待确认';
    case 'CONFIRMED':
      return '已合并';
    case 'REJECTED':
      return '已拒绝';
    case 'STALE':
      return '已失效';
    default:
      return code ?? '';
  }
}

/** 映射状态（生效中 / 已拆分）的可读名。 */
export function customerMergeMappingStateLabel(code: string | undefined): string {
  switch (code) {
    case 'ACTIVE':
      return '生效中';
    case 'SPLIT':
      return '已拆分';
    default:
      return code ?? '';
  }
}

/** 退订状态的可读名（合并会把一个来源的退订扩散到所有来源，这一格很要紧）。 */
export function customerMergeOptOutLabel(code: string | undefined): string {
  switch (code) {
    case 'NONE':
      return '无退订记录';
    case 'ACTIVE':
      return '退订生效中';
    case 'REVOKED':
      return '退订已撤销';
    default:
      return code ?? '';
  }
}

/**
 * 排除信号的可读名。
 *
 * <p>🔴 {@code OPT_OUT} 不是普通备注：合并到一个逻辑客户会让**所有来源**继承这个退订，
 * 所以它必须有一句自己的话，不能和别的排除信号混在一行里。
 */
export function customerMergeExclusionFlagLabel(code: string | undefined): string {
  switch (code) {
    case 'OPT_OUT':
      return '一侧已退订：合并后所有来源都不得触达';
    case 'CONFLICTING_NAME':
      return '两侧姓名不一致';
    case 'ADDRESS_UNAVAILABLE':
      return '地址取不到：「姓名+地址」这一档没能验证';
    case 'NO_ADDRESS':
      return '一侧没有登记地址';
    default:
      return code ?? '';
  }
}

/**
 * 置信度的显示文本。
 *
 * <p>🔴 <b>前端不做任何算术</b>：置信度是后端用 BigDecimal 算好的
 * （numeric(5,4)，0~1），页面只原样显示。
 * 四位小数不是装饰 —— 它对应「这一对到底是 0.9000 还是 0.9500」的那次判定。
 */
export function customerMergeConfidenceText(confidence: number | null | undefined): string {
  if (confidence === null || confidence === undefined) return '—';
  return String(confidence);
}

/**
 * 一句人话：凭什么认为这两个人是同一个人。
 *
 * <p>🔴 逐条列出命中了哪个信号、各自的证据强度，以及**比对的是什么**（脱敏形态）。
 * 只给一个分数不叫「可解释」。
 */
export function customerMergeSignalText(signals: CrmCustomerMergeSignal[] | undefined): string {
  if (!signals || signals.length === 0) return '没有任何信号命中';
  return signals
    .map((signal) => {
      const value = signal.maskedValue ? `（比对值 ${signal.maskedValue}）` : '';
      return `${signal.label || customerMergeMatchTypeLabel(signal.signalType)} ${customerMergeConfidenceText(
        signal.strength,
      )}${value}`;
    })
    .join('；');
}

/**
 * 金额按币种分行的文本（**一行一个币种**，不做任何前端算术）。
 *
 * <p>「累计消费 USD 315.60 / EUR 12.00」比「327.60」诚实 ——
 * USD 与 EUR 加起来不是任何一个金额。
 */
export function customerMergeAmountText(amounts: CrmCustomerMergeAmount[] | undefined): string {
  if (!amounts || amounts.length === 0) return '无消费记录';
  return amounts
    .map((amount) => {
      const total = String(amount.totalAmount);
      // 客单价在**订单数为 0** 时是 null（不可判定），不是 0 —— 后端给的，页面只原样显示。
      const avg = amount.avgOrderAmount === null ? '—' : String(amount.avgOrderAmount);
      return `${amount.currency} ${total}（${amount.orderCount} 单 · 客单价 ${avg}）`;
    })
    .join(' / ');
}

/**
 * 空态文案：**区分**「筛选无结果」与「还没生成过候选」。
 *
 * <p>这两种空态要的行动完全不同：前者是「把筛选条件放宽」，
 * 后者是「先生成一次候选」。而且后者必须说清
 * 「生成候选**不会**合并任何客户」—— 不然运营会以为点一下就合并了。
 */
export function customerMergeEmptyStateText(
  filtered: boolean,
  hasRuns: boolean,
): { title: string; description: string } {
  if (filtered) {
    return {
      title: '没有命中任何候选',
      description:
        '当前筛选条件下没有候选。放宽筛选条件再试一次 —— 这不代表这个租户没有需要合并的客户。',
    };
  }
  if (!hasRuns) {
    return {
      title: '还没有候选',
      description:
        '候选需要先生成一次。点「生成候选建议」按邮箱 / 电话 / 姓名+地址 / 平台买家标识扫一遍 —— ' +
        '🔴 它**只给建议**，不会合并任何客户。',
    };
  }
  return {
    title: '还没有待确认的候选',
    description:
      '上一次生成候选没有产出待确认项：可能没有跨店的重名 / 同邮箱客户，也可能都已经被复核过了。',
  };
}

/** 候选队列的一句话说明（**不是**结论 —— 每一行都还没合并）。 */
export function customerMergeCandidateNote(
  page: CrmCustomerMergeCandidatePage | null | undefined,
): string {
  if (!page) return '';
  return (
    `待确认 ${page.suggestedCount} 条 · 已合并 ${page.confirmedCount} 条 · 已拒绝 ${page.rejectedCount} 条。` +
    (page.note ?? '')
  );
}

/** 逻辑客户队列的一句话说明。 */
export function customerMergeLogicalNote(
  page: CrmCustomerMergeLogicalPage | null | undefined,
): string {
  if (!page) return '';
  return `生效中 ${page.mergedCustomerCount} 个 · 已解散 ${page.splitCustomerCount} 个。${page.note ?? ''}`;
}

/** 一次候选生成运行的摘要（**如实**说扫了多少、砍掉了多少）。 */
export function customerMergeRunText(
  run:
    | {
        runNo: string;
        status: string;
        scannedCustomerCount: number;
        candidateCount: number;
        belowThresholdCount: number;
        excludedSameShopCount: number;
        excludedAlreadyMergedCount: number;
        optOutCandidateCount: number;
        addressResolvedCount: number;
        addressUnavailableCount: number;
        truncated: boolean;
        failureReason: string;
      }
    | undefined,
): string {
  if (!run) return '';
  return (
    `${run.runNo} · ${run.status} · 扫了 ${run.scannedCustomerCount} 位来源客户 · ` +
    `产出候选 ${run.candidateCount} 条 · 同名不同人（置信度不足）${run.belowThresholdCount} 对 · ` +
    `同店 ${run.excludedSameShopCount} 对 · 已合并过 ${run.excludedAlreadyMergedCount} 对 · ` +
    `带退订 ${run.optOutCandidateCount} 条 · 地址取到 ${run.addressResolvedCount} / ` +
    `取不到 ${run.addressUnavailableCount}` +
    (run.truncated ? ' · 🔴 本轮名单被截断，不是全量' : '') +
    (run.failureReason ? ` · ${run.failureReason}` : '')
  );
}

/** 一次操作的说明文本。 */
export function customerMergeOperationText(
  operation:
    | { operationLabel: string; logicalRef: string | null; reason: string; memberCount: number }
    | undefined,
): string {
  if (!operation) return '';
  const target = operation.logicalRef ? `逻辑客户 ${operation.logicalRef}` : '候选';
  return `${operation.operationLabel} · ${target} · 成员 ${operation.memberCount} 位 · ${operation.reason}`;
}

// ================================================================ 请求

export function fetchCustomerMergeCapabilities() {
  return api<CrmCustomerMergeCapabilities>(`${base}/capabilities`);
}

export function fetchCustomerMergeCandidates(params: CustomerMergeCandidateParams = {}) {
  return api<CrmCustomerMergeCandidatePage>(`${base}/candidates${query({ ...params })}`);
}

export function fetchCustomerMergeCandidate(candidateId: string, revealContact = false) {
  // ⚠️ 查询串**不能**写成模板串里的插值（`${revealContact ? ... : ''}`）：
  //   api-proxy 的白名单守卫会从本文件的源码里反向抽取「真实调用点」，
  //   那种写法会让它抽出 `candidates/{id}{id}` 这样的假路径 ⇒ 守卫自己先失效。
  //   用字符串拼接，抽取出来的就是干净的真实路径。
  return api<CrmCustomerMergeCandidateDetail>(
    `${base}/candidates/${candidateId}` + (revealContact ? '?revealContact=true' : ''),
  );
}

export function scanCustomerMerge(input: { maxCustomers?: number; force?: boolean } = {}) {
  return api<CrmCustomerMergeScanResult>(`${base}/scan`, 'POST', {
    maxCustomers: input.maxCustomers ?? null,
    force: input.force ?? null,
  });
}

export function confirmCustomerMerge(
  candidateId: string,
  input: { reason: string; logicalCustomerId?: string | undefined },
) {
  return api<CrmCustomerMergeLogicalDetail>(`${base}/candidates/${candidateId}/confirm`, 'POST', {
    reason: input.reason,
    logicalCustomerId: input.logicalCustomerId ?? null,
  });
}

export function rejectCustomerMerge(candidateId: string, input: { reason: string }) {
  return api<unknown>(`${base}/candidates/${candidateId}/reject`, 'POST', { reason: input.reason });
}

export function fetchCustomerMergeLogical(params: { page?: number; size?: number } = {}) {
  return api<CrmCustomerMergeLogicalPage>(`${base}/logical${query({ ...params })}`);
}

export function fetchCustomerMergeLogicalDetail(logicalId: string) {
  return api<CrmCustomerMergeLogicalDetail>(`${base}/logical/${logicalId}`);
}

export function splitCustomerMerge(
  logicalId: string,
  input: { customerProfileId: string; reason: string },
) {
  return api<CrmCustomerMergeLogicalDetail>(`${base}/logical/${logicalId}/split`, 'POST', {
    customerProfileId: input.customerProfileId,
    reason: input.reason,
  });
}

export function fetchCustomerMergeOperations(params: { page?: number; size?: number } = {}) {
  return api<CrmCustomerMergeOperationPage>(`${base}/operations${query({ ...params })}`);
}

export function fetchCustomerMergeRuns(params: { page?: number; size?: number } = {}) {
  return api<CrmCustomerMergeRunPage>(`${base}/runs${query({ ...params })}`);
}

/** 拼查询串（**空值一律不拼** —— 拼一个空的 status= 会把筛选悄悄变成「状态为空」）。 */
function query(params: Record<string, unknown>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}
