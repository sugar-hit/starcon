import type {
  Certification,
  CertificationInput,
  CertificationSet,
  CompletenessEvaluationResult,
  CompletenessGap,
  CompletenessGapPage,
  CompletenessOptions,
  CompletenessOverview,
  CompletenessPage,
  CompletenessRow,
  CompletenessScorecard,
  CustomsDeclaration,
  CustomsDeclarationInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.9.2.1 完整度评分前端模型。
 *
 * <p>界面纪律（与后端 {@code CompletenessScoreService} / {@code CompletenessScoreRules} 同源，
 * 规则编号一致 —— 改一处必须同步三处）：
 *
 * <ul>
 *   <li><b>R1 分数一律现算</b>：页面上的数字永远是当前主数据的结论，
 *       改完主数据刷新即可看到变化；历史分数只在「评估历史」里看。
 *   <li><b>R2 分档由后端算</b>：前端**不**自己拿 percent 套阈值 ——
 *       两处各判一次的症状是"列表说良好、评分卡说一般"，而两个数来自同一次评分。
 *   <li><b>R3 两个就绪结论分开显示</b>：刊登就绪与清关就绪是两条独立的链，
 *       合并成一个"完整度 78%"之后运营就看不出自己被哪条链拦住。
 *   <li><b>R4 null 一律显示「—」或"还没登记"，绝不渲染成 0</b>：
 *       认证的 {@code NOT_REQUIRED}（无需认证）与"没填"是两件事，
 *       渲染成 0 之后运营会以为"认证得分 0"，而它其实是一句"确认无需认证"。
 *   <li><b>R5 缺失项必须逐条显示</b>：只显示一个缺失计数会让人无从下手，
 *       所以每条缺失都带主体名与下一步动作。
 * </ul>
 */

const ROOT = 'catalog/completeness';

// ================================================================ 读取

export function fetchOptions() {
  return api<CompletenessOptions>(`${ROOT}/options`);
}

export function fetchOverview() {
  return api<CompletenessOverview>(`${ROOT}/overview`);
}

export type ScoreListQuery = {
  keyword?: string;
  ownerType?: string;
  grade?: string;
  status?: string;
  listingReadyOnly?: boolean;
  missingOnly?: boolean;
  orderBy?: string;
  ascending?: boolean;
  page?: number;
  size?: number;
};

export function emptyScoreListQuery(): ScoreListQuery {
  return { ownerType: 'SKU', missingOnly: false, page: 0, size: 20 };
}

export function fetchScores(query: ScoreListQuery) {
  return api<CompletenessPage>(`${ROOT}?${toQuery(query as Record<string, unknown>)}`);
}

export function fetchScorecard(ownerType: string, ownerId: string) {
  return api<CompletenessScorecard>(
    `${ROOT}/${encodeURIComponent(ownerType)}/${encodeURIComponent(ownerId)}`,
  );
}

export function fetchGaps(params: {
  ownerType?: string;
  dimension?: string;
  keyword?: string;
  page?: number;
  size?: number;
}) {
  return api<CompletenessGapPage>(`${ROOT}/gaps?${toQuery(params as Record<string, unknown>)}`);
}

export function fetchGapSummary(ownerType?: string) {
  return api<{
    ownerCount: number;
    missingCheckCount: number;
    cleanCount: number;
    byDimension: Array<{ dimension: string; dimensionLabel: string; missingCount: number }>;
  }>(`${ROOT}/gaps/summary?${toQuery({ ownerType })}`);
}

// ================================================================ 写入

export function fetchCustoms(skuId: string) {
  return api<CustomsDeclaration | null>(`${ROOT}/customs/skus/${encodeURIComponent(skuId)}`);
}

export function saveCustoms(skuId: string, body: CustomsDeclarationInput) {
  return api<CustomsDeclaration>(`${ROOT}/customs/skus/${encodeURIComponent(skuId)}`, 'PUT', body);
}

export function deleteCustoms(skuId: string, version: number) {
  return api<{ deleted: boolean }>(
    `${ROOT}/customs/skus/${encodeURIComponent(skuId)}?version=${version}`,
    'DELETE',
  );
}

export function fetchCertifications(skuId: string) {
  return api<CertificationSet>(`${ROOT}/certifications/skus/${encodeURIComponent(skuId)}`);
}

export function createCertification(skuId: string, body: CertificationInput) {
  return api<Certification>(
    `${ROOT}/certifications/skus/${encodeURIComponent(skuId)}`,
    'POST',
    body,
  );
}

export function updateCertification(skuId: string, certId: string, body: CertificationInput) {
  return api<Certification>(
    `${ROOT}/certifications/skus/${encodeURIComponent(skuId)}/${encodeURIComponent(certId)}`,
    'PUT',
    body,
  );
}

export function deleteCertification(skuId: string, certId: string, version: number) {
  return api<{ deleted: boolean }>(
    `${ROOT}/certifications/skus/${encodeURIComponent(skuId)}/${encodeURIComponent(certId)}?version=${version}`,
    'DELETE',
  );
}

/**
 * 批量评估（蓝图的「批量补全入口」）。
 *
 * <p>只传主体 id，**分数由服务端现算**（规则 R1）：前端把分数传上去等于
 * 让用户自己给自己打分，而完整度直接决定能否刊登与能否清关。
 */
export function evaluate(ownerType: string, ownerIds: string[], note?: string) {
  return api<CompletenessEvaluationResult>(`${ROOT}/evaluations`, 'POST', {
    ownerType,
    ownerIds,
    note,
  });
}

// ================================================================ 展示口径（R2 / R4）

/** 分档 → 徽标配色。分档由后端算，这里只决定长什么样（规则 R2）。 */
export function gradeTone(grade: string): 'danger' | 'warn' | 'ok' | 'muted' {
  if (grade === 'EXCELLENT') return 'ok';
  if (grade === 'GOOD') return 'ok';
  if (grade === 'FAIR') return 'warn';
  return 'danger';
}

/** 分档中文名。阈值与权重都由后端持有，前端只翻译 code。 */
export function gradeLabel(grade: string | null | undefined) {
  switch (grade) {
    case 'EXCELLENT':
      return '优秀';
    case 'GOOD':
      return '良好';
    case 'FAIR':
      return '一般';
    case 'POOR':
      return '较差';
    default:
      return '未评分';
  }
}

/**
 * 分数展示串。
 *
 * <p>`score` 是 **0..1 的比值**（后端按 numeric(5,4) 存），不是百分数（规则 R2）。
 * 契约里另有 `scorePercent` 字段已经是百分数串 —— 优先用它，
 * 只有它缺失时才在这里换算，避免两处各乘一次 100。
 */
export function scoreText(score: number | null | undefined, percent?: string | null) {
  if (percent) return `${percent}%`;
  if (score === null || score === undefined) return '—';
  return `${(score * 100).toFixed(2)}%`;
}

/**
 * 维度进度串。
 *
 * <p>`total <= 0` 显示「不适用」而不是 0%（规则 R4）：后端保证每个维度
 * `total >= 1`，这里仍然兜一层，防止把"算不出来"渲染成"完成了 0%"。
 */
export function dimensionProgress(satisfied: number, total: number) {
  if (total <= 0) return '不适用';
  return `${satisfied} / ${total}`;
}

/** 维度比率 → 百分比展示；null 显示「—」。 */
export function ratioText(ratio: number | null | undefined) {
  if (ratio === null || ratio === undefined) return '—';
  return `${(ratio * 100).toFixed(1)}%`;
}

/** 认证四态的中文名（规则 R4：与后端同一套词）。 */
export function certificationCompletenessLabel(completeness: string | null | undefined) {
  switch (completeness) {
    case 'COMPLETE':
      return '已齐全';
    case 'PENDING':
      return '已申请未取得';
    case 'EXPIRED':
      return '已过期待续期';
    case 'NONE':
      return '未登记';
    default:
      return '未登记';
  }
}

/** 认证状态的中文名。 */
export function certificationStatusLabel(status: string | null | undefined) {
  switch (status) {
    case 'HELD':
      return '已取得';
    case 'PENDING':
      return '已申请';
    case 'EXPIRED':
      return '已过期';
    case 'NOT_REQUIRED':
      return '无需认证';
    default:
      return '未登记';
  }
}

/** 「无需认证」是完整的两种方式之一（规则 R5）—— 徽标不能与「已取得」同色。 */
export function certificationTone(status: string | null | undefined): 'ok' | 'warn' | 'danger' {
  if (status === 'HELD' || status === 'NOT_REQUIRED') return 'ok';
  if (status === 'PENDING') return 'warn';
  return 'danger';
}

/** 就绪徽标文案：区分"被拦"与"已达门槛但别的维度还差"（规则 R3）。 */
export function readinessLabel(ready: boolean, blockers: string[]) {
  if (ready) return '就绪';
  return blockers.length ? `未就绪：${blockers.join('、')}` : '未就绪';
}

/** 缺失项去重后按 code 计数（同一主体同一 code 只报一次）。 */
export function countByCode(codes: string[]) {
  const counts = new Map<string, number>();
  for (const code of codes) counts.set(code, (counts.get(code) ?? 0) + 1);
  return counts;
}

/** 样本主体串：`A-1、A-2…`；空数组返回 '—' 而不是空串（空串会被当成"没数据"）。 */
export function sampleOwnersText(owners: string[]) {
  if (!owners || owners.length === 0) return '—';
  return owners.slice(0, 5).join('、');
}

/** 缺失项的下一步入口（后端给的路由，前端只做展示）。 */
export function gapNextEntryLabel(entry: string | null | undefined) {
  switch (entry) {
    case '/products/sku/[id]/attributes':
      return '去补属性值';
    case '/products/media':
      return '去传图';
    case '/products/categories':
      return '去指定分类';
    case '/products/completeness':
      return '去补报关 / 认证';
    case '/products/sku':
      return '去建变体';
    default:
      return '去处理';
  }
}

// ================================================================ 表单校验（与后端同一口径）

/** 空白 → null。空串在提交里表示"没填"，不能发成 `""` 让后端以为填了空白。 */
export function blankToNull(value: string) {
  const text = value.trim();
  return text === '' ? null : text;
}

/**
 * HS 编码校验（前端一份，提交前就报错，别等服务层 400）。
 *
 * <p>刻意与后端 `CompletenessScoreRules.hsCodeProblem` 同一口径：
 * 允许数字与字母，小数点 / 短横线会被去掉。
 */
export function hsCodeProblem(value: string) {
  const code = value
    .trim()
    .replace(/[\s.\-_/]/g, '')
    .toUpperCase();
  if (code === '') return null; // 空 = 未填，由评分判缺失，不在表单层拦
  if (code.length > 32) return 'HS 编码最多 32 个字符';
  if (!/^[0-9A-Z]+$/.test(code)) return `HS 编码只含数字与字母：${code}`;
  return null;
}

/** 原产国校验：ISO 3166-1 alpha-2 两位大写。 */
export function originCountryProblem(value: string) {
  const country = value.trim().toUpperCase();
  if (country === '') return null;
  if (!/^[A-Z]{2}$/.test(country)) return '原产国请用两位国家码（如 CN / US / DE）';
  return null;
}

/** 报关属性是否至少有一项内容（全空的行在评分里与"未登记"一样，只多一条脏数据）。 */
export function customsHasContent(input: {
  hsCode?: string | null;
  declaredName?: string | null;
  originCountry?: string | null;
  legalUnit?: string | null;
  declarationElements?: string[] | null;
}) {
  return (
    Boolean(blankToNull(input.hsCode ?? '')) ||
    Boolean(blankToNull(input.declaredName ?? '')) ||
    Boolean(blankToNull(input.originCountry ?? '')) ||
    Boolean(blankToNull(input.legalUnit ?? '')) ||
    (input.declarationElements ?? []).some((element) => Boolean(blankToNull(element ?? '')))
  );
}

/** 报关属性整体校验。返回第一条问题，没有则 null。 */
export function customsProblem(input: {
  hsCode?: string | null;
  declaredName?: string | null;
  originCountry?: string | null;
  legalUnit?: string | null;
  declarationElements?: string[] | null;
}) {
  const hs = hsCodeProblem(input.hsCode ?? '');
  if (hs) return hs;
  const country = originCountryProblem(input.originCountry ?? '');
  if (country) return country;
  if ((input.declaredName ?? '').trim().length > 200) return '申报品名最多 200 个字符';
  if ((input.legalUnit ?? '').trim().length > 32) return '法定计量单位最多 32 个字符';
  if ((input.declarationElements ?? []).length > 60) return '申报要素最多 60 条';
  if (!customsHasContent(input)) return '报关属性至少要填一项';
  return null;
}

/** 认证代码校验：非空、大写字母数字。 */
export function certificationCodeProblem(value: string) {
  const code = value.trim().toUpperCase();
  if (code === '') return '认证种类代码必填（如 CE / FCC / UL / PSE / 3C）';
  if (code.length > 32) return '认证种类代码最多 32 个字符';
  if (!/^[A-Z0-9]+$/.test(code)) return `认证种类代码只含大写字母与数字：${code}`;
  return null;
}

/** 认证整体校验。`status=NOT_REQUIRED` 时禁止带证书编号与有效期（规则 R5）。 */
export function certificationProblem(input: {
  certificationCode?: string | null;
  status?: string | null;
  certificateNo?: string | null;
  issuedOn?: string | null;
  expiresOn?: string | null;
}) {
  const code = certificationCodeProblem(input.certificationCode ?? '');
  if (code) return code;
  const status = (input.status ?? '').trim().toUpperCase();
  if (status === '') return '认证状态必填：已取得 / 已申请 / 已过期 / 无需认证';
  if (!['HELD', 'PENDING', 'EXPIRED', 'NOT_REQUIRED'].includes(status))
    return '认证状态不在词表里（可用：无需认证 / 已取得 / 已申请 / 已过期）';
  if (status === 'HELD' && !blankToNull(input.certificateNo ?? ''))
    return '认证状态为「已取得」时必须填写证书编号：一张没有编号的证书无法被海关与平台核对';
  if (status === 'NOT_REQUIRED') {
    if (blankToNull(input.certificateNo ?? '') || input.issuedOn || input.expiresOn)
      return '认证状态为「无需认证」时不能有证书编号与有效期';
  }
  if (input.issuedOn && input.expiresOn && input.expiresOn < input.issuedOn)
    return '认证到期日不能早于签发日';
  return null;
}

/** 把空白元素从申报要素里剔掉（后端也会剔，但前端剔了列表更好读）。 */
export function cleanElements(elements: string[]) {
  return elements.map((element) => element.trim()).filter((element) => element !== '');
}

// ================================================================ 错误态

/**
 * 错误文案。
 *
 * <p>刻意区分 401（登录过期，要重新登录）、403（没有权限）与其它：
 * 三者给同一句"操作失败"的话，运营会去反复重试一件他根本没权限做的事。
 */
export function completenessErrorText(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 401) return '登录已过期，请重新登录后再试';
    if (error.status === 403) return '当前账号没有商品档案的读写权限';
    if (error.message) return error.message;
  }
  if (error instanceof Error && error.message) return error.message;
  return '完整度评分暂时读不出来，请稍后重试';
}

// ================================================================ 内部

function toQuery(params: Record<string, unknown>) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '' || value === false) continue;
    query.set(key, String(value));
  }
  return query.toString();
}
