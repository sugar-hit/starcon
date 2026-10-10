import type {
  CustomsPage,
  CustomsProfile,
  CustomsProfileSaved,
  CustomsRow,
  DeclarationElement,
  DeclarationReady,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 3.7.1.1 HS 编码与申报要素前端模型。
 *
 * <p>蓝图对这一格的限定语是「HS 编码错误直接导致申报失败与罚款，
 * **必须允许审核流程而非任意填写**」。所以本模块的界面纪律只有一条主线：
 * <b>把「能不能报关」与「字段填没填全」分开表达</b>。
 *
 * <ul>
 *   <li><b>可报关性（{@code declarationReady}）是审核态派生值</b>，不是字段完整度。
 *       后端已经算好了并配了中文 {@code blockerLabels}，页面逐条读它，
 *       不自己猜、不把英文码直接甩给运营（规则见 {@link blockerLines}）。
 *   <li><b>提交 / 通过 / 驳回都是不可轻易撤销的业务动作</b>：它们决定这份资料
 *       能不能被 10 报关引用，所以按钮要二次确认，且确认框要讲清后果。
 *   <li><b>写与审核是两个权限</b>（{@code catalog.compliance.update} /
 *       {@code catalog.compliance.review}）：合并成一个等于允许「自己填自己批」。
 * </ul>
 *
 * <p>字段级校验与后端 {@code CustomsAttributeRules} 逐条对齐：
 * 前端拦住的是「一定会被后端拒绝的提交」，两边的判定口径必须是同一套。
 */

const ROOT = 'catalog/customs-attributes';

export function profilePath(skuId: string) {
  return `${ROOT}/skus/${encodeURIComponent(skuId)}`;
}

/** 读报关属性 + 申报要素 + 现算的可报关性判定。 */
export function fetchProfile(skuId: string) {
  return api<CustomsProfile>(profilePath(skuId));
}

/**
 * 保存：<b>一次提交同时写属性与申报要素全量清单</b>。
 *
 * <p>拆成两个端点只会让「填了 HS 码忘了填申报要素」变成常态，
 * 而那正是审核通过前会被 `REQUIRED_ELEMENTS_INCOMPLETE` 拦下的洞。
 */
export function saveProfile(skuId: string, body: unknown) {
  return api<CustomsProfileSaved>(profilePath(skuId), 'PUT', body);
}

/**
 * 筛选报关属性（供 16 合规与 10 报关批量核）。
 *
 * <p><b>刻意保留空串</b>：`preferenceAgreement=""` 在后端表示
 * 「筛出不适用任何协定的那些」，与「不筛」是两件事 —— 运营真的会问
 * 「哪些货完全不走协定」。所以这里只跳过 `undefined`，不跳过 `''`。
 */
export function searchProfiles(params: {
  keyword?: string;
  hsCode?: string;
  originCountryCode?: string;
  status?: string;
  preferenceAgreement?: string;
  preferentialOriginEligible?: boolean;
  page?: number;
  size?: number;
}) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) continue;
    query.set(key, String(value));
  }
  const suffix = query.toString();
  return api<CustomsPage>(`${ROOT}/profiles${suffix ? `?${suffix}` : ''}`);
}

/** 提交审核（DRAFT → 已提交待审）。version 是乐观锁凭据，必须回传。 */
export function submitForReview(skuId: string, version: number) {
  return api<CustomsProfile>(`${profilePath(skuId)}/submit`, 'POST', { version });
}

/** 审核（通过 / 驳回），需 `catalog.compliance.review`。 */
export function reviewProfile(skuId: string, body: { approve: boolean; rejectReason: string }) {
  return api<CustomsProfile>(`${profilePath(skuId)}/review`, 'POST', body);
}

/** 供 10 报关取数：未审核通过时后端一律 409，所以这里的失败不是「空数据」。 */
export function fetchDeclarationReady(skuId: string) {
  return api<DeclarationReady>(`${ROOT}/declaration-ready?skuId=${encodeURIComponent(skuId)}`);
}

// ------------------------------------------------------------ 审核状态机

/**
 * 审核状态。
 *
 * <p><b>五种而不是三种</b>：后端的 status 只有 DRAFT / APPROVED / REJECTED，
 * 但「草稿」与「已提交待审」在 status 上是同一个 DRAFT（靠 `submittedAt` 区分），
 * 而这两个状态下一步动作完全相反 —— 前者去「提交」，后者去「等审核」/「撤回重改」。
 * 塌缩成一个「草稿」会让运营不知道自己该不该点提交。
 */
export type ReviewState = {
  key: 'NOT_RECORDED' | 'DRAFT' | 'AWAITING_REVIEW' | 'APPROVED' | 'REJECTED';
  label: string;
  tone: 'neutral' | 'warning' | 'success' | 'danger';
  /** 下一步该做什么 —— 状态标签旁边直接给出动作提示。 */
  next: string;
};

export function reviewState(profile: {
  status: string | null;
  submittedAt: string | null;
  rejectReason?: string | null;
}): ReviewState {
  if (profile.status === null || profile.status === undefined) {
    return {
      key: 'NOT_RECORDED',
      label: '未维护',
      tone: 'neutral',
      next: '先填写报关属性与申报要素，保存后才能提交审核。',
    };
  }
  switch (profile.status) {
    case 'APPROVED':
      return {
        key: 'APPROVED',
        label: '已审核通过',
        tone: 'success',
        next: '10 报关可以引用这份申报资料。',
      };
    case 'REJECTED':
      return {
        key: 'REJECTED',
        label: '已驳回',
        tone: 'danger',
        next: '按驳回原因改完，再重新提交审核。',
      };
    case 'DRAFT':
      return profile.submittedAt
        ? {
            key: 'AWAITING_REVIEW',
            label: '待审核',
            tone: 'warning',
            next: '已提交，等有审核权限的人处理。',
          }
        : { key: 'DRAFT', label: '草稿', tone: 'neutral', next: '内容齐备后提交审核。' };
    default:
      return {
        key: 'DRAFT',
        label: profile.status,
        tone: 'neutral',
        next: '状态未知，请刷新后重试。',
      };
  }
}

/**
 * 审核动作是否可执行。
 *
 * <p>与后端 {@code writeAttribute} 的锁**完全一致：只有 APPROVED 被拒改**。
 * 已驳回（REJECTED）是可编辑的 —— 「驳回 → 改 → 重新提交」是蓝图要求的正式路径，
 * 把驳回态也锁住会让运营改完没路可走，只能重新建一份。
 */
export function canEdit(profile: { status: string | null }): boolean {
  return profile.status !== 'APPROVED';
}

export function canSubmit(profile: {
  status: string | null;
  submittedAt: string | null;
}): boolean {
  // 已提交的 DRAFT 不可重复提交；REJECTED 也不能（要先改再提交，而后端只认 DRAFT 可提交）。
  return profile.status === 'DRAFT' && !profile.submittedAt;
}

export function canReview(profile: { status: string | null; submittedAt: string | null }): boolean {
  return profile.status === 'DRAFT' && Boolean(profile.submittedAt);
}

// ------------------------------------------------------------ 阻断原因

/**
 * 阻断原因的兜底中文。
 *
 * <p>后端 `blockerLabels` 才是权威口径，这里只在它缺失时兜底 ——
 * 直接把英文码甩给运营（「REQUIRED_ELEMENTS_INCOMPLETE」）等于让运营自己翻译。
 */
const FALLBACK_LABELS: Record<string, string> = {
  NOT_RECORDED: '这个 SKU 还没有报关属性，10 报关引用不到任何申报资料',
  NOT_APPROVED: '报关属性还没提交审核，草稿不可被报关引用',
  SUBMITTED_AWAITING_REVIEW: '报关属性已提交，等待审核通过后才能被报关引用',
  DECLARATION_REJECTED: '报关属性已被驳回，改完后重新提交才能被报关引用',
  NO_ELEMENTS: '一条申报要素都没有：海关按申报要素逐项比对，缺项会被判为填报不符并退单',
  REQUIRED_ELEMENTS_INCOMPLETE: '有必填申报要素还是空的，补齐后重新提交审核',
};

export type BlockerLine = { code: string; label: string };

/** 逐条把阻断原因读成中文。顺序与后端一致（后端固定了顺序）。 */
export function blockerLines(profile: {
  blockers?: string[] | null;
  blockerLabels?: string[] | null;
}): BlockerLine[] {
  const codes = profile.blockers ?? [];
  const labels = profile.blockerLabels ?? [];
  return codes.map((code, index) => ({
    code,
    label: labels[index]?.trim() || FALLBACK_LABELS[code] || code,
  }));
}

/** 可报关性的一句话结论。界面上必须显式说清「只有通过审核才能报关」。 */
export function readinessText(profile: {
  declarationReady: boolean;
  status: string | null;
}): string {
  if (profile.declarationReady)
    return '已审核通过，10 报关可以引用这份申报资料。';
  return '未审核通过，10 报关取数会直接被拒绝（接口返回 409），不会拿到半成品资料。';
}

// ------------------------------------------------------------ 申报要素

/** 必填却还空着的行：后端 `incomplete` 字段的现算同义，页面据此高亮。 */
export function incompleteRows(elements: readonly DeclarationElement[]): DeclarationElement[] {
  return elements.filter((element) => element.required && element.elementValue.trim() === '');
}

export function elementLabel(element: {
  elementCode: string;
  elementName: string;
}): string {
  const code = element.elementCode.trim();
  const name = element.elementName.trim();
  if (code === '') return name === '' ? '未命名申报要素' : name;
  return name === '' ? code : `${code} ${name}`;
}

// ------------------------------------------------------------ 权限

/** 写报关属性 / 提交审核：`catalog.compliance.update`。 */
export function writable(permissions: readonly string[]): boolean {
  return permissions.includes('catalog.compliance.update');
}

/**
 * 审核：`catalog.compliance.review`。
 *
 * <p>与 {@link writable} **刻意分开**：能填报关属性的人不一定有权批准它。
 * 页面必须能让两者分别收敛，否则一个权限就等于「自己填自己批」。
 */
export function reviewable(permissions: readonly string[]): boolean {
  return permissions.includes('catalog.compliance.review');
}

// ------------------------------------------------------------ 草稿与校验

/** 与后端 `MAX_ELEMENTS` 一致：一次最多保存 100 条申报要素。 */
export const MAX_ELEMENTS = 100;

export type ElementDraft = {
  elementCode: string;
  elementName: string;
  elementValue: string;
  required: boolean;
};

export type CustomsDraft = {
  hsCode: string;
  originCountryCode: string;
  declaredName: string;
  declaredValueReference: string;
  statutoryUnitCode: string;
  preferentialOriginEligible: boolean;
  preferenceAgreement: string;
  note: string;
  elements: ElementDraft[];
  /**
   * 乐观锁凭据。
   *
   * <p><b>必须从服务端回填</b>：恒为 0 会让第一次保存把 version 推到 1，
   * 此后每次提交都拿 0 去 CAS，必然 409 —— 与 3.7.2.1 的 packagingVersion 同一个坑。
   */
  version: number;
};

export function draftFrom(profile: CustomsProfile): CustomsDraft {
  return {
    hsCode: profile.hsCode ?? '',
    originCountryCode: profile.originCountryCode ?? '',
    declaredName: profile.declaredName ?? '',
    declaredValueReference:
      profile.declaredValueReference === null || profile.declaredValueReference === undefined
        ? ''
        : String(profile.declaredValueReference),
    statutoryUnitCode: profile.statutoryUnitCode ?? '',
    preferentialOriginEligible: profile.preferentialOriginEligible === true,
    preferenceAgreement: profile.preferenceAgreement ?? '',
    note: profile.note ?? '',
    elements: [...(profile.elements ?? [])]
      .sort((left, right) => left.ordinal - right.ordinal)
      .map((element) => ({
        elementCode: element.elementCode,
        elementName: element.elementName,
        elementValue: element.elementValue ?? '',
        required: element.required,
      })),
    version: profile.version ?? 0,
  };
}

/** 新增一条申报要素时的空行（位次由客户端重排，不让用户手填）。 */
export function emptyElement(): ElementDraft {
  return { elementCode: '', elementName: '', elementValue: '', required: true };
}

/**
 * 草稿校验 —— 与后端 `CustomsAttributeService.validate` 同一套判定。
 *
 * <p>这里拦的是「一定会被后端拒绝的提交」，所以规则逐条对应后端，
 * 不做「前端更宽松」的美化：放宽只会把错误推迟到点保存那一刻。
 */
export function validateDraft(draft: CustomsDraft): string[] {
  const errors: string[] = [];

  const hsCode = draft.hsCode.trim();
  if (hsCode === '') errors.push('请填写 HS 编码');
  else if (!/^[0-9]{6,10}$/.test(hsCode))
    errors.push(
      'HS 编码必须是 6–10 位纯数字（如 851712 或 8517123090）：带字母或位数不对的是目的国本地税则号，不是这一列该装的编码',
    );

  const origin = draft.originCountryCode.trim().toUpperCase();
  if (origin === '') errors.push('请填写原产地');
  else if (!/^[A-Z]{2}$/.test(origin))
    errors.push(
      '原产地必须是两位大写字母的 ISO 3166-1 alpha-2 代码（如 CN / VN / DE）：填错会让优惠失效或被追缴',
    );

  const declaredName = draft.declaredName.trim();
  if (declaredName === '') errors.push('请填写申报品名');
  else if (declaredName.length > 200)
    errors.push('申报品名最多 200 字：它会原样出现在报关单上，长度不够会截断成另一个商品');

  const unit = draft.statutoryUnitCode.trim();
  if (unit === '') errors.push('请填写法定计量单位代码');
  else if (unit.length > 8) errors.push('法定计量单位代码最多 8 个字符（例：PCE 件 / KGM 千克）');

  if (draft.note.trim().length > 1000) errors.push('备注最多 1000 字');

  // 金额：BigDecimal + NUMERIC，禁浮点。这里只做格式与量级校验，
  // 真正的精度由后端 NUMERIC(18,6) 兜底 —— 前端绝不用 Number 参与金额运算。
  const value = draft.declaredValueReference.trim();
  if (value !== '') {
    if (!/^\d+(\.\d{1,6})?$/.test(value))
      errors.push('申报价值参考最多 6 位小数：多出的位数会被数据库静默四舍五入，而报的那个数就不存在了');
    else if (Number(value) <= 0) errors.push('申报价值参考必须大于 0');
  }

  // 享惠与协定代码必须双向一致（与后端 normalizePreference 同一口径）。
  const agreement = draft.preferenceAgreement.trim();
  if (draft.preferentialOriginEligible && agreement === '')
    errors.push('勾了可享惠就必须填写协定代码（RCEP / CPTPP 等）：没有协定代码这项优惠直接失效');
  if (!draft.preferentialOriginEligible && agreement !== '')
    errors.push(`没有勾可享惠就不该填协定代码（当前填了「${agreement}」）`);

  if (draft.elements.length > MAX_ELEMENTS)
    errors.push(`一次最多保存 ${MAX_ELEMENTS} 条申报要素`);
  draft.elements.forEach((element, index) => {
    const at = `第 ${index + 1} 条申报要素`;
    if (element.elementCode.trim() === '') errors.push(`${at}：申报要素代码不能为空（例：01 品名）`);
    else if (element.elementCode.trim().length > 32) errors.push(`${at}：申报要素代码最多 32 个字符`);
    if (element.elementName.trim() === '') errors.push(`${at}：申报要素名称不能为空`);
    else if (element.elementName.trim().length > 200) errors.push(`${at}：申报要素名称最多 200 字`);
    if (element.elementValue.length > 2000) errors.push(`${at}：申报要素值最多 2000 字`);
  });

  return errors;
}

export type CustomsPayload = {
  hsCode: string;
  originCountryCode: string;
  declaredName: string;
  declaredValueReference: number | null;
  statutoryUnitCode: string;
  preferentialOriginEligible: boolean;
  preferenceAgreement: string;
  note: string;
  elements: {
    ordinal: number;
    elementCode: string;
    elementName: string;
    elementValue: string | null;
    required: boolean;
  }[];
  version: number;
};

/**
 * 草稿 → 入参。
 *
 * <p><b>位次由客户端按行序重排</b>：位次唯一（DB 有 UNIQUE 约束）且必须 > 0
 * 是跨行不变量，让用户手填只会制造后端必然拒绝的请求；行序本身已经表达了位次。
 */
export function draftToPayload(draft: CustomsDraft): CustomsPayload {
  return {
    hsCode: draft.hsCode.trim(),
    originCountryCode: draft.originCountryCode.trim().toUpperCase(),
    declaredName: draft.declaredName.trim(),
    declaredValueReference: nullableValue(draft.declaredValueReference),
    statutoryUnitCode: draft.statutoryUnitCode.trim(),
    preferentialOriginEligible: draft.preferentialOriginEligible,
    preferenceAgreement: draft.preferenceAgreement.trim(),
    note: draft.note.trim(),
    elements: draft.elements.map((element, index) => ({
      ordinal: index + 1,
      elementCode: element.elementCode.trim(),
      elementName: element.elementName.trim(),
      elementValue: element.elementValue.trim() === '' ? null : element.elementValue.trim(),
      required: element.required,
    })),
    version: draft.version,
  };
}

function nullableValue(text: string): number | null {
  const trimmed = text.trim();
  return trimmed === '' ? null : Number(trimmed);
}

/** 列表行上的简要判定：审核队列与税则校验页直接读。 */
export function rowState(
  row: Pick<
    CustomsRow,
    'status' | 'requiredMissingCount' | 'elementCount' | 'submittedAt'
  >,
): {
  label: string;
  tone: ReviewState['tone'];
} {
  if (row.status === null || row.status === undefined) return { label: '未维护', tone: 'neutral' };
  if (row.status === 'APPROVED' && row.requiredMissingCount > 0)
    return { label: '已通过但要素缺失', tone: 'danger' };
  if (row.status === 'APPROVED' && row.elementCount === 0)
    return { label: '已通过但无要素', tone: 'danger' };
  return reviewState({ status: row.status, submittedAt: row.submittedAt ?? null });
}

// ------------------------------------------------------------ 错误处理

export function errorText(error: unknown, fallback = '操作失败'): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

/** 404：SKU 不存在，或当前租户看不到它。RLS 已过滤，不区分两者。 */
export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/** 409：乐观锁撞了，或已审核通过的内容不可直接改。两种都要提示刷新。 */
export function isConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}