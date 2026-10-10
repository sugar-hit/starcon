import type {
  DestinationRequirement,
  DestinationRequirementList,
  DestinationVersionInput,
  DestinationVersionProfile,
  DestinationVersionRow,
  DestinationVersionSaved,
  EffectiveDestinationVersion,
  ResolvedDeclaration,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 3.7.1.2 目的地报关版本前端模型。
 *
 * <p>蓝图对这一格的限定语只有一句，但它决定了这个页面的全部形态：
 * 「<b>不同目的地必须用不同版本，不能用一套申报资料打通全球</b>」。
 * 所以界面纪律是<b>让「覆盖」与「继承」在屏幕上永远分得开</b>：
 *
 * <ul>
 *   <li><b>基础资料与目的地方案并排出现</b>（{@code profile.base}）。只列差异列的话，
 *       「这个格子空着」在页面上长得和「忘了填」一模一样，而那正是漏维护一个
 *       目的地的起点 —— 一旦漏了，系统就会拿别处的口径去申报。
 *   <li><b>每一格标注它来自哪一层</b>（{@link sourceLine}）：目的地版本覆盖 vs 继承基础。
 *       后端在 {@code ResolvedDeclaration} 上给了 {@code *Source} 与 {@code *Overridden}，
 *       页面逐格读它，不自己猜。
 *   <li><b>无生效版本是一等公民</b>：它不是「还没填」而是「<b>下游现在拿不到数</b>」，
 *       后端在 {@code /effective}、{@code /requirements} 与 {@code /customs-declaration/resolve}
 *       上都会返回 409，页面必须把这个 409 显式说出来，而不是退化成空表格。
 *   <li><b>发布要二次确认</b>且按独立权限收敛（{@code catalog.compliance.publish}）：
 *       能编辑目的地版本的人不一定有权把它发布成 10 报关清关时引用的那一份。
 * </ul>
 *
 * <p>字段级校验与后端 {@link com.starcon.catalog.CustomsDestinationVersionRules} 逐条对齐：
 * 前端拦住的是「一定会被后端拒绝的提交」，两边的判定口径必须是同一套。
 */

const ROOT = 'catalog/customs-destination-versions';

/** 合成出口挂在另一个前缀下（见后端 CustomsDeclarationResolveController 的类注释）。 */
const RESOLVE_ROOT = 'catalog/customs-declaration';

export function versionsPath(skuId: string) {
  return `${ROOT}/skus/${encodeURIComponent(skuId)}`;
}

/** 某 SKU 在某目的地的版本面板（含草稿与已发布，标出此刻生效的那个）。 */
export function fetchVersions(skuId: string, destination: string) {
  return api<DestinationVersionProfile>(
    `${versionsPath(skuId)}?destination=${encodeURIComponent(destination)}`,
  );
}

/**
 * 保存一个目的地版本（草稿态）：<b>一次提交写版本 + 认证要求全量清单</b>。
 *
 * <p>分开提交只会让「填了 DE 的税号忘了填 UKCA」变成常态，而那正是货到港被扣的那一类。
 */
export function saveVersion(skuId: string, body: DestinationVersionInput) {
  return api<DestinationVersionSaved>(versionsPath(skuId), 'PUT', body);
}

/**
 * 发布（草稿 → 已发布），需 `catalog.compliance.publish`。
 *
 * <p>用独立端点而不是复用保存：发布是一个<b>状态流转</b>并留下发布留痕，
 * 混进保存会让「我只是想改个字」与「我把它发布成下游要用的那一份」无法区分。
 */
export function publishVersion(versionId: string, version: number) {
  return api<DestinationVersionRow>(
    `${ROOT}/${encodeURIComponent(versionId)}/publish`,
    'POST',
    { version },
  );
}

/**
 * 按目的地查某时刻的生效版本（规则 R4）。
 *
 * <p><b>没有生效版本时后端返回 409</b>，所以这里的失败**不是**「空数据」——
 * 用 {@link isNoEffectiveVersion} 把它与其它错误分开，让页面能说出「该目的地还没维护」。
 */
export function fetchEffective(skuId: string, destination: string, at?: string) {
  return api<EffectiveDestinationVersion>(
    `${ROOT}/effective${query({ skuId, destination, at })}`,
  );
}

/** 认证要求清单（供 16 合规的认证台账）。同样在无生效版本时 409。 */
export function fetchRequirements(skuId: string, destination: string, at?: string) {
  return api<DestinationRequirementList>(
    `${ROOT}/requirements${query({ skuId, destination, at })}`,
  );
}

/**
 * <b>合成基础 + 目的地覆盖 = 一份可直接申报的资料</b>：这就是 10 报关清关时真正拿到的。
 *
 * <p>同样在无生效版本 / 基础未审核通过时 409，所以这里的失败一定带着原因，
 * 页面要把那句话原样带给运营。
 */
export function fetchResolved(skuId: string, destination: string, at?: string) {
  return api<ResolvedDeclaration>(
    `${RESOLVE_ROOT}/resolve${query({ skuId, destination, at })}`,
  );
}

/**
 * query 拼装。
 *
 * <p><b>{@code at} 为空串时必须真的被丢掉</b>（而不是发出 `at=`）：
 * 空串到了后端会被 `parseInstant` 判成非法时间而 400，
 * 而「不传 = 查此刻」是这个端点最常见的用法。
 */
function query(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, value);
  }
  const suffix = search.toString();
  return suffix ? `?${suffix}` : '';
}

// ------------------------------------------------------------ 覆盖 vs 继承

/** 来源标记的中文标签（与后端 `CustomsDestinationVersionRules.sourceLabel` 同口径）。 */
export function sourceLabel(source: string | null | undefined): string {
  return source === 'MODULE_3_7_1_2' ? '目的地版本覆盖' : '继承基础报关属性';
}

/** 一个字段的来源行：这一格来自哪一层。页面上紧挨着值显示。 */
export function sourceLine(field: {
  source?: string | null;
  overridden?: boolean;
}): { label: string; overridden: boolean; tone: 'neutral' | 'accent' } {
  const overridden = field.overridden === true;
  return {
    label: sourceLabel(field.source),
    overridden,
    tone: overridden ? 'accent' : 'neutral',
  };
}

/**
 * 合成结果的一句话结论。
 *
 * <p>界面上必须显式说清「哪些格被这个目的地改过」：继承与覆盖在页面上
 * 长得一模一样，而运营漏维护一个目的地正是从看不出差别开始的。
 */
export function resolutionText(resolved: {
  hsCodeOverridden: boolean;
  declaredNameOverridden: boolean;
  preferentialOriginEligible: boolean;
  preferentialApplicable: boolean;
  preferentialGranted: boolean;
}): string {
  const overridden: string[] = [];
  if (resolved.hsCodeOverridden) overridden.push('税号');
  if (resolved.declaredNameOverridden) overridden.push('申报品名');
  const base = overridden.length
    ? `本目的地覆盖了：${overridden.join('、')}；其余继承基础报关属性。`
    : '本目的地没有覆盖任何字段，整份资料沿用基础报关属性。';
  const preference = resolved.preferentialGranted
    ? '享惠：基础有资格且该目的地承认，可按协定税率。'
    : resolved.preferentialOriginEligible && !resolved.preferentialApplicable
      ? '享惠：基础有资格，但该目的地不承认，按最惠国税率。'
      : '享惠：基础属性没有资格，不享惠。';
  return `${base}${preference}`;
}

// ------------------------------------------------------------ 目的国与税号校验

/** 两位大写字母。`'*'` 单独判，因为它不是「格式不对」而是「本模块不接受」。 */
const COUNTRY = /^[A-Z]{2}$/;

/**
 * 目的国校验 —— 与后端 `normalizeDestination` 同一套判定。
 *
 * <p><b>通配 {@code '*'} 单独给一条消息</b>，不能混进「格式不对」里：
 * 它不是打错字，而是把蓝图点名要杜绝的「用一套申报资料打通全球」引进模块。
 */
export function validateDestination(raw: string): string[] {
  const value = raw.trim().toUpperCase();
  if (value === '') return ['请填写目的地国家'];
  if (value.replace(/\s+/g, '') === '*')
    return [
      '目的地不能写成「*」（不限目的地）：不同目的地必须用不同版本，不能用一套申报资料打通全球。请为这个目的地单独建一个版本',
    ];
  if (!COUNTRY.test(value))
    return [
      '目的地必须是两位大写字母的 ISO 3166-1 alpha-2 代码（如 DE / US / GB）：目的地决定了这一版用哪个税号、哪个申报品名与哪套认证要求',
    ];
  return [];
}

/**
 * 目的地税号校验 —— 与后端 `normalizeDestinationHsCode` 同一套判定。
 *
 * <p><b>空串在这里是合法值</b>（沿用基础 HS 编码），不是「没填」：
 * 所以这一条只在非空时才报错，而页面必须把「留空 = 沿用基础」写在 hint 里，
 * 否则运营会把合法值当成漏填而去编一个。
 */
export function validateDestinationHsCode(raw: string): string[] {
  const value = raw.trim();
  if (value === '') return [];
  if (!/^[0-9]{6,10}$/.test(value))
    return [
      '目的地税号必须是 6–10 位纯数字（如 851712 或 8517123090），或者留空表示沿用基础 HS 编码：错码会直接导致申报失败与罚款',
    ];
  return [];
}

// ------------------------------------------------------------ 版本状态与生效状态

/**
 * 版本状态。
 *
 * <p><b>「已发布」与「此刻生效」分成两格</b>：一个已发布的版本可能早已被下一个接替。
 * 把两者塌缩成一个标签，运营会把历史版本当成现在该用的版本 ——
 * 那就是拿旧税号去报关。
 */
export type VersionState = {
  key: 'DRAFT' | 'PUBLISHED_EFFECTIVE' | 'PUBLISHED_SUPERSEDED';
  statusLabel: string;
  effectiveLabel: string;
  /** 只用 Badge 的合法 tone（neutral / success）：历史版本刻意与草稿同色，
   *  因为两者的下一步动作都是「改内容」而不是「等审核」。 */
  tone: 'neutral' | 'success';
  /** 下一步该做什么 —— 状态标签旁边直接给出动作提示。 */
  next: string;
};

export function versionState(version: {
  status: string;
  effective: boolean;
}): VersionState {
  if (version.status === 'DRAFT') {
    return {
      key: 'DRAFT',
      statusLabel: '草稿',
      effectiveLabel: '未生效（草稿不可被报关引用）',
      tone: 'neutral',
      next: '草稿对 10 报关不可见；内容确认后发布。',
    };
  }
  if (version.status === 'PUBLISHED' && version.effective) {
    return {
      key: 'PUBLISHED_EFFECTIVE',
      statusLabel: '已发布',
      effectiveLabel: '此刻生效',
      tone: 'success',
      next: '10 报关现在引用这一版。',
    };
  }
  return {
    key: 'PUBLISHED_SUPERSEDED',
    statusLabel: '已发布',
    effectiveLabel: '历史版本（已被接替或区间未到）',
    tone: 'neutral',
    next: '这一版不再被引用；要换口径请发布新版本并让新版本接替。',
  };
}

/** 发布按钮可见性：只有草稿、且当前角色有发布权限。 */
export function canPublish(version: { status: string } | null): boolean {
  return version !== null && version.status === 'DRAFT';
}

// ------------------------------------------------------------ 认证要求

/** 强制项：{@code required=true} 的那些。16 合规关心的从来不是「列了几项」而是「几项强制」。 */
export function requiredRequirements(
  requirements: readonly DestinationRequirement[],
): DestinationRequirement[] {
  return requirements.filter((requirement) => requirement.required);
}

export function requirementLabel(requirement: {
  requirementCode: string;
  requirementName: string;
}): string {
  const code = requirement.requirementCode.trim();
  const name = requirement.requirementName.trim();
  if (name === '') return code;
  return code === '' ? name : `${code} ${name}`;
}

// ------------------------------------------------------------ 权限

/** 写目的地版本：`catalog.compliance.update`。 */
export function writable(permissions: readonly string[]): boolean {
  return permissions.includes('catalog.compliance.update');
}

/**
 * 发布：`catalog.compliance.publish`。
 *
 * <p>与 {@link writable} **刻意分开**：能编辑目的地版本的人不一定有权把它发布成
 * 10 报关清关时引用的那一份。合并成一个权限等于允许「自己填自己发」。
 */
export function publishable(permissions: readonly string[]): boolean {
  return permissions.includes('catalog.compliance.publish');
}

// ------------------------------------------------------------ 草稿与校验

/** 与后端 `MAX_REQUIREMENTS` 一致。 */
export const MAX_REQUIREMENTS = 50;

export type RequirementDraft = {
  requirementCode: string;
  requirementName: string;
  required: boolean;
  note: string;
};

export type DestinationVersionDraft = {
  destinationCountryCode: string;
  versionNo: string;
  /** 留空 = 沿用基础 HS 编码（这是合法值，不是漏填）。 */
  destinationHsCode: string;
  /** 留空 = 沿用基础申报品名。 */
  destinationDeclaredName: string;
  preferentialApplicable: boolean;
  /** ISO-8601 带时区字符串（`datetime-local` 控件给的是不带时区的，所以由页面补）。 */
  effectiveFrom: string;
  /** 空串 = 一直有效（+∞）。 */
  effectiveTo: string;
  note: string;
  requirements: RequirementDraft[];
  /**
   * 乐观锁凭据。
   *
   * <p><b>必须从服务端回填</b>：恒为 0 会让第一次保存把 version 推到 1，
   * 此后每次提交都拿 0 去 CAS，必然 409 —— 与 3.7.2.1 的 packagingVersion 同一个坑。
   */
  version: number;
};

/**
 * 新建一个草稿版本时的默认值。
 *
 * <p><b>默认覆盖字段留空</b>（= 沿用基础）：目的地版本是<b>声明差异</b>的，
 * 没有差异就不用覆盖。默认值填成基础值反而会让运营以为「不填也会生效」，
 * 而空串正是「沿用」这个含义的唯一表达。
 */
export function emptyDraft(): DestinationVersionDraft {
  return {
    destinationCountryCode: '',
    versionNo: '1',
    destinationHsCode: '',
    destinationDeclaredName: '',
    preferentialApplicable: false,
    effectiveFrom: '',
    effectiveTo: '',
    note: '',
    requirements: [],
    version: 0,
  };
}

/** 把一条已有版本读成草稿（编辑草稿时用）。 */
export function draftFromVersion(version: DestinationVersionRow): DestinationVersionDraft {
  return {
    destinationCountryCode: version.destinationCountryCode,
    versionNo: String(version.versionNo),
    destinationHsCode: version.destinationHsCode,
    destinationDeclaredName: version.destinationDeclaredName,
    preferentialApplicable: version.preferentialApplicable,
    effectiveFrom: toLocalInput(version.effectiveFrom),
    effectiveTo: version.effectiveTo === null ? '' : toLocalInput(version.effectiveTo),
    note: version.note,
    requirements: version.requirements.map((requirement) => ({
      requirementCode: requirement.requirementCode,
      requirementName: requirement.requirementName,
      required: requirement.required,
      note: requirement.note,
    })),
    version: version.version ?? 0,
  };
}

export function emptyRequirement(): RequirementDraft {
  return { requirementCode: '', requirementName: '', required: true, note: '' };
}

/**
 * 草稿校验 —— 与后端 `CustomsDestinationVersionService.validate` 同一套判定。
 *
 * <p>这里拦的是「一定会被后端拒绝的提交」，所以规则逐条对应后端，
 * 不做「前端更宽松」的美化：放宽只会把错误推迟到点保存那一刻。
 */
export function validateDraft(draft: DestinationVersionDraft): string[] {
  const errors: string[] = [
    ...validateDestination(draft.destinationCountryCode),
    ...validateDestinationHsCode(draft.destinationHsCode),
  ];

  const versionNo = Number(draft.versionNo.trim());
  if (!Number.isInteger(versionNo) || versionNo < 1)
    errors.push('版本号必须是从 1 开始的整数（同一 SKU 同一目的地内唯一）');

  const declaredName = draft.destinationDeclaredName.trim();
  if (declaredName.length > 500)
    errors.push('目的地申报品名最多 500 字：它会原样出现在报关单上，截断成另一个商品比报错糟得多');
  if (draft.note.trim().length > 1000) errors.push('备注最多 1000 字');

  const from = draft.effectiveFrom.trim();
  if (from === '') errors.push('请填写生效起点');
  const to = draft.effectiveTo.trim();
  if (from !== '' && to !== '') {
    const fromMs = Date.parse(from);
    const toMs = Date.parse(to);
    // 两端都可解析时才比较：拿 Invalid Date 做比较会静默得到 false，
    // 那样「终点 <= 起点」这条规则就会在一个非法输入上静默通过。
    if (!Number.isNaN(fromMs) && !Number.isNaN(toMs) && toMs <= fromMs)
      errors.push(
        '生效终点必须晚于生效起点：生效区间是左闭右开的 [起点, 终点)，空区间等于「从不生效」',
      );
  }

  if (draft.requirements.length > MAX_REQUIREMENTS)
    errors.push(`一次最多保存 ${MAX_REQUIREMENTS} 条认证要求`);
  const codes = draft.requirements.map((item) => item.requirementCode.trim().toUpperCase());
  draft.requirements.forEach((item, index) => {
    const at = `第 ${index + 1} 条认证要求`;
    if (item.requirementCode.trim() === '') errors.push(`${at}：请填写认证代码（例：CE / FCC / RoHS）`);
    else if (item.requirementCode.trim().length > 64) errors.push(`${at}：认证代码最多 64 个字符`);
    if (item.requirementName.trim().length > 200) errors.push(`${at}：认证名称最多 200 字`);
    if (item.note.length > 500) errors.push(`${at}：认证备注最多 500 字`);
  });
  if (new Set(codes).size !== codes.length)
    errors.push(
      '同一个版本里认证代码不能重复：两行时「这个目的地要不要 CE」无法回答，而 16 合规与清关仓会拿到两个互相矛盾的答案',
    );

  return errors;
}

/** ISO-8601 → {@code datetime-local} 需要的 {@code yyyy-MM-ddTHH:mm}。 */
function toLocalInput(iso: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return '';
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}T${pad(
    parsed.getHours(),
  )}:${pad(parsed.getMinutes())}`;
}

/**
 * 草稿 → 入参。
 *
 * <p><b>{@code datetime-local} 不带时区，而后端要的是带时区的 ISO-8601</b>，
 * 所以这里补上本机时区偏移（而不是当成 UTC）—— 当成 UTC 会让区间整体平移，
 * 而运营填的是当地时间。
 */
export function draftToPayload(draft: DestinationVersionDraft): DestinationVersionInput {
  return {
    destinationCountryCode: draft.destinationCountryCode.trim().toUpperCase(),
    versionNo: Number(draft.versionNo.trim()),
    destinationHsCode: draft.destinationHsCode.trim(),
    destinationDeclaredName: draft.destinationDeclaredName.trim(),
    preferentialApplicable: draft.preferentialApplicable,
    effectiveFrom: toIsoWithOffset(draft.effectiveFrom.trim()),
    // 留空 = 「一直有效」，发的是 **null 而不是省略该键**：契约里 effectiveTo 是
    // `string | null`（键必在、值可空），与 V25 BomVersionInput / BundleVersionInput
    // 同一口径。省略键会被当成「没传这个字段」，而 null 才是 +∞（规则 R1）。
    effectiveTo:
      draft.effectiveTo.trim() === '' ? null : toIsoWithOffset(draft.effectiveTo.trim()),
    note: draft.note.trim(),
    requirements: draft.requirements.map((item) => ({
      requirementCode: item.requirementCode.trim().toUpperCase(),
      requirementName: item.requirementName.trim(),
      required: item.required,
      note: item.note.trim(),
    })),
    version: draft.version,
  };
}

/** 本地时间 → 带时区偏移的 ISO-8601（后端按 {@code ?::timestamptz} 绑定）。 */
function toIsoWithOffset(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  const pad = (part: number) => String(part).padStart(2, '0');
  const offset = -parsed.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  const absolute = Math.abs(offset);
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}T${pad(
    parsed.getHours(),
  )}:${pad(parsed.getMinutes())}:${pad(parsed.getSeconds())}${sign}${pad(Math.floor(absolute / 60))}:${pad(
    absolute % 60,
  )}`;
}

// ------------------------------------------------------------ 错误处理

export function errorText(error: unknown, fallback = '操作失败'): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

/** 404：SKU 不存在，或当前租户看不到它。RLS 已过滤，不区分两者。 */
export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/** 409：乐观锁撞了，或已发布内容不可直接改，或**该目的地无生效版本**。 */
export function isConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}

/**
 * 「该目的地无生效版本」这个 409 要与其它 409 分开显示。
 *
 * <p>它们的下一步动作完全不同：乐观锁是「刷新重试」，无生效版本是
 * 「去维护这个目的地」。混成一句「操作冲突」会让运营反复重试一个永远不会自愈的状态。
 */
export function isNoEffectiveVersion(error: unknown): boolean {
  return isConflict(error) && errorText(error).includes('没有生效的报关版本');
}

/** 无生效版本时页面要显示的一句话（说清后果与下一步）。 */
export const NO_EFFECTIVE_HINT =
  '该目的地没有生效版本：10 报关现在取不到这个目的地的申报资料。' +
  '系统不会退回基础报关属性 —— 那正是「用一套申报资料打通全球」。请为该目的地发布一个覆盖当前时刻的版本。';
