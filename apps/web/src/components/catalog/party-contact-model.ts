import type {
  PartyAddress,
  PartyAddressInput,
  PartyAddressPage,
  PartyAddressUsageInput,
  PartyAddressVersion,
  PartyContact,
  PartyContactInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.6.3.1 地址与联系人前端模型。
 *
 * <p>这个页面上最容易出错的三处：
 *
 * <ul>
 *   <li><b>「默认地址」是按用途分别有的，不是一个布尔列</b>。同一个主体可以有默认收货地址、
 *       默认发货地址、默认提货地址三个，它们是三条不同的东西 —— 而
 *       10 物流填的是「提货地址」，06 采购填的是「发货 / 收货地址」。所以界面与模型都是
 *       `defaultUsages: string[]`，前端不把它压成一个 `isDefault`。
 *   <li><b>改用途不产生地址版本</b>。把默认提货地址改到 3 号仓不是「地址变了」，
 *       版本列表要回答的是「这个地址被改过几次、每次改成什么样」。所以只改标签走
 *       `saveAddressUsages`，改内容走 `saveAddress` —— 两条路径不能混。
 *   <li><b>「停用 / 拉黑主体」与「地址已删除」是两件事</b>，而且阻断判定一律来自后端
 *       （`PartyAddressRules.selectionBlock` 是唯一实现）。前端只把 `selectionBlockCode`
 *       翻成要显示的话，不在浏览器里再判一次。
 * </ul>
 *
 * <p>本文件的校验与 `PartyAddressRules` / `PartyContactRules` 逐字同口径。抄一份到前端的理由是
 * 「提交前就少一次无谓的往返」，而不是为了绕过服务层 —— 服务层那份才是权威。
 */

/** 地址用途词表：收货 / 发货 / 提货。与 V31 第三节的 CHECK 和后端 `USAGES` 同一口径。 */
export const ADDRESS_USAGES = [
  {
    value: 'SHIP_TO',
    label: '收货地址',
    hint: '06 采购单上的收货地址：货送到这里',
  },
  {
    value: 'SHIP_FROM',
    label: '发货地址',
    hint: '06 采购单上的发货地址：货从这里发出（供应商的仓库）',
  },
  {
    value: 'PICKUP',
    label: '提货地址',
    hint: '10 物流单上的提货地址：承运商到这里提货',
  },
] as const;

export type AddressUsage = (typeof ADDRESS_USAGES)[number]['value'];

export function usageLabel(value: string | null | undefined): string {
  return ADDRESS_USAGES.find((item) => item.value === value)?.label ?? value ?? '未知用途';
}

export function usageHint(value: string | null | undefined): string {
  return ADDRESS_USAGES.find((item) => item.value === value)?.hint ?? '';
}

/**
 * 常用地址国家的下拉项。
 *
 * <p>刻意只给跨境电商里真正常用的那几十个，而不是让运营手打两位代码 ——
 * 手打「UK」而不是「GB」会在报关与运费分区上静默算错国家，而界面上看不出来。
 */
export const ADDRESS_COUNTRIES = [
  { value: 'CN', label: '中国大陆' },
  { value: 'HK', label: '中国香港' },
  { value: 'TW', label: '中国台湾' },
  { value: 'US', label: '美国' },
  { value: 'GB', label: '英国' },
  { value: 'DE', label: '德国' },
  { value: 'FR', label: '法国' },
  { value: 'IT', label: '意大利' },
  { value: 'ES', label: '西班牙' },
  { value: 'NL', label: '荷兰' },
  { value: 'PL', label: '波兰' },
  { value: 'JP', label: '日本' },
  { value: 'KR', label: '韩国' },
  { value: 'SG', label: '新加坡' },
  { value: 'MY', label: '马来西亚' },
  { value: 'TH', label: '泰国' },
  { value: 'VN', label: '越南' },
  { value: 'ID', label: '印度尼西亚' },
  { value: 'PH', label: '菲律宾' },
  { value: 'IN', label: '印度' },
  { value: 'AU', label: '澳大利亚' },
  { value: 'NZ', label: '新西兰' },
  { value: 'CA', label: '加拿大' },
  { value: 'MX', label: '墨西哥' },
  { value: 'BR', label: '巴西' },
  { value: 'AE', label: '阿联酋' },
  { value: 'SA', label: '沙特阿拉伯' },
  { value: 'TR', label: '土耳其' },
  { value: 'RU', label: '俄罗斯' },
] as const;

/** 选中阻断原因码 → 中文标签。与后端 `selectionBlockLabel` 逐字同口径。 */
export const SELECTION_BLOCK_LABELS: Record<string, string> = {
  ADDRESS_DELETED: '地址已删除',
  PARTY_DELETED: '主体已删除',
  PARTY_INACTIVE: '主体已停用',
  PARTY_BLACKLISTED: '主体已拉黑',
};

export function selectionBlockLabel(code: string | null | undefined): string {
  if (!code) return '可选';
  return SELECTION_BLOCK_LABELS[code] ?? '不可选';
}

/** 联系人阻断少了「地址已删除」那一档（联系人没有自己的软删除）。 */
export const CONTACT_BLOCK_LABELS: Record<string, string> = {
  PARTY_DELETED: '主体已删除',
  PARTY_INACTIVE: '主体已停用',
  PARTY_BLACKLISTED: '主体已拉黑',
};

export function contactBlockLabel(code: string | null | undefined): string {
  if (!code) return '可选';
  return CONTACT_BLOCK_LABELS[code] ?? '不可选';
}

// ------------------------------------------------------------ 接口

export type AddressListQuery = {
  keyword: string;
  usage: string;
  countryCode: string;
  includeDeleted: boolean;
  page: number;
  size: number;
};

function addressParams(query: AddressListQuery): string {
  const params = new URLSearchParams();
  if (query.keyword.trim()) params.set('keyword', query.keyword.trim());
  if (query.usage) params.set('usage', query.usage);
  if (query.countryCode.trim()) params.set('countryCode', query.countryCode.trim());
  if (query.includeDeleted) params.set('includeDeleted', 'true');
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return params.toString();
}

export function fetchAddresses(partyId: string, query: AddressListQuery) {
  return api<PartyAddressPage>(`catalog/parties/${partyId}/addresses?${addressParams(query)}`);
}

/**
 * 某个用途的默认地址 —— 06 采购 / 10 物流取默认地址走的就是它。
 *
 * <p>没有默认时后端返回 404，调用方要能区分「有人但没人设默认」与「一个地址都没有」，
 * 所以这里把 404 留给上层的空态文案去解释。
 */
export function fetchDefaultAddress(partyId: string, usage: string) {
  return api<PartyAddress>(`catalog/parties/${partyId}/addresses/defaults?usage=${usage}`);
}

/** 引用候选：下游单据的下拉数据源（本页面用它展示「这条地址能不能被新单据选中」）。 */
export function fetchAddressCandidates(partyId: string, usage: string, includeBlocked: boolean) {
  const params = new URLSearchParams();
  if (usage) params.set('usage', usage);
  if (includeBlocked) params.set('includeBlocked', 'true');
  const query = params.toString();
  return api<PartyAddress[]>(
    `catalog/parties/${partyId}/addresses/candidates${query ? `?${query}` : ''}`,
  );
}

export function createAddress(partyId: string, input: PartyAddressInput) {
  return api<{ id: string; version: number; latestVersionNo: number }>(
    `catalog/parties/${partyId}/addresses`,
    'POST',
    input,
  );
}

/**
 * 改地址内容（可能产生一条新版本）。
 *
 * <p>提交时带上完整用途集合（整体替换语义），但**只改用途**时请改用
 * {@link saveAddressUsages} —— 那条路径不会在版本链上留下一条内容没变的行。
 */
export function saveAddress(partyId: string, addressId: string, input: PartyAddressInput) {
  return api<{ id: string; version: number; latestVersionNo: number }>(
    `catalog/parties/${partyId}/addresses/${addressId}`,
    'PUT',
    input,
  );
}

/** 只改用途与默认标记：不碰地址内容，所以不产生版本行。 */
export function saveAddressUsages(
  partyId: string,
  addressId: string,
  usages: (PartyAddressUsageInput | null)[],
  version: number,
) {
  return api<{ id: string; version: number; latestVersionNo: number }>(
    `catalog/parties/${partyId}/addresses/${addressId}/usages`,
    'PUT',
    { usages, version },
  );
}

/** 设 / 取消某个用途的默认地址。取消时传 isDefault=false。 */
export function setAddressDefault(
  partyId: string,
  addressId: string,
  usage: string,
  isDefault: boolean,
  version: number,
) {
  return api<{ id: string; version: number; latestVersionNo: number }>(
    `catalog/parties/${partyId}/addresses/${addressId}/default`,
    'PUT',
    { usage, isDefault, version },
  );
}

/** 软删除地址。删除前必须先取消它的默认标记，后端会点名是哪个用途。 */
export function deleteAddress(partyId: string, addressId: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/parties/${partyId}/addresses/${addressId}?version=${version}`,
    'DELETE',
  );
}

export function fetchAddressVersions(partyId: string, addressId: string) {
  return api<PartyAddressVersion[]>(`catalog/parties/${partyId}/addresses/${addressId}/versions`);
}

/** 取回某一版（冻结快照回放）：单据上的 (address_id, version_no) 指的就是它。 */
export function fetchAddressVersion(partyId: string, addressId: string, versionNo: number) {
  return api<PartyAddressVersion>(
    `catalog/parties/${partyId}/addresses/${addressId}/versions/${versionNo}`,
  );
}

export function fetchContacts(partyId: string, keyword: string, primaryOnly: boolean) {
  const params = new URLSearchParams();
  if (keyword.trim()) params.set('keyword', keyword.trim());
  if (primaryOnly) params.set('primaryOnly', 'true');
  const query = params.toString();
  return api<PartyContact[]>(`catalog/parties/${partyId}/contacts${query ? `?${query}` : ''}`);
}

/** 默认联系人：下游单据「找谁签字」取的就是它。 */
export function fetchPrimaryContact(partyId: string) {
  return api<PartyContact>(`catalog/parties/${partyId}/contacts/default`);
}

export function createContact(partyId: string, input: PartyContactInput) {
  return api<{ id: string; version: number; isPrimary: boolean }>(
    `catalog/parties/${partyId}/contacts`,
    'POST',
    input,
  );
}

export function saveContact(partyId: string, contactId: string, input: PartyContactInput) {
  return api<{ id: string; version: number; isPrimary: boolean }>(
    `catalog/parties/${partyId}/contacts/${contactId}`,
    'PUT',
    input,
  );
}

/** 设 / 取消默认联系人。日常换签字人走这个独立端点，不混在编辑别的字段里。 */
export function setPrimaryContact(
  partyId: string,
  contactId: string,
  isPrimary: boolean,
  version: number,
) {
  return api<{ id: string; version: number; isPrimary: boolean }>(
    `catalog/parties/${partyId}/contacts/${contactId}/primary`,
    'PUT',
    { isPrimary, version },
  );
}

/** 删除联系人（物理删除）。删之前必须先改默认。 */
export function deleteContact(partyId: string, contactId: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/parties/${partyId}/contacts/${contactId}?version=${version}`,
    'DELETE',
  );
}

// ------------------------------------------------------------ 校验（与服务层同口径）

export function countryProblem(value: string | null | undefined): string | null {
  const code = (value ?? '').trim().toUpperCase();
  // 后端顺序：空 → 长度 → 格式；这里同口径，前端红字与后端 400 说的是同一件事。
  if (!code) return '国家不能为空：跨境地址的国家决定报关、运费分区与税务辖区';
  if (code.length > 2) return '国家代码最多 2 个字符';
  if (!/^[A-Z]{2}$/.test(code))
    return '国家代码请用 ISO 3166-1 alpha-2 两位代码，如 CN / DE / US（英国是 GB 而不是 UK）';
  return null;
}

export function cityProblem(value: string): string | null {
  const city = value.trim();
  if (!city) return '城市不能为空：跨国地址里城市是运费分区与税务辖区的主要输入';
  if (city.length > 100) return '城市最多 100 个字符';
  return null;
}

export function streetLine1Problem(value: string): string | null {
  const street = value.trim();
  if (!street) return '街道地址不能为空';
  if (street.length > 200) return '街道地址最多 200 个字符';
  return null;
}

/** 可空列：空着没问题，填了但只有空格是脏数据。与后端 `optionalProblem` 同一口径。 */
function optionalProblem(
  value: string | null | undefined,
  max: number,
  label: string,
): string | null {
  if (value === null || value === undefined || value === '') return null;
  if (value.trim() === '') return `${label}填了但只有空格，请清空或填写内容`;
  if (value.length > max) return `${label}最多 ${max} 个字符`;
  return null;
}

export function postalCodeProblem(value: string | null | undefined): string | null {
  return optionalProblem(value, 32, '邮编');
}

export function addressInputProblem(input: PartyAddressInput): string | null {
  return (
    countryProblem(input.countryCode) ??
    cityProblem(input.city) ??
    streetLine1Problem(input.streetLine1) ??
    optionalProblem(input.stateProvince, 100, '省 / 州') ??
    optionalProblem(input.district, 100, '区 / 县') ??
    postalCodeProblem(input.postalCode) ??
    optionalProblem(input.streetLine2, 200, '街道地址行 2') ??
    optionalProblem(input.label, 100, '地址名') ??
    ((input.changeNote ?? '').length > 200 ? '变更说明最多 200 个字符' : null)
  );
}

/** 用途集合的校验：词表 + 同一用途不重复。与后端 `usagesProblem` 同一口径。 */
export function addressUsagesProblem(usages: (PartyAddressUsageInput | null)[]): string | null {
  if (usages.length > ADDRESS_USAGES.length)
    return `一条地址最多承担 ${ADDRESS_USAGES.length} 个用途（收货 / 发货 / 提货）`;
  const seen = new Set<string>();
  for (const usage of usages) {
    if (usage === null) continue;
    const value = usage.usage.trim().toUpperCase();
    if (!ADDRESS_USAGES.some((item) => item.value === value))
      return '地址用途只能是收货（SHIP_TO）、发货（SHIP_FROM）或提货（PICKUP）';
    if (seen.has(value)) return `同一个用途只能标一次：${usageLabel(value)}`;
    seen.add(value);
  }
  return null;
}

export function contactNameProblem(value: string): string | null {
  const name = value.trim();
  if (!name) return '联系人姓名不能为空：往来单位与对接人是两回事，主体名不能替代这里';
  if (name.length > 100) return '联系人姓名最多 100 个字符';
  return null;
}

export function contactEmailProblem(value: string | null | undefined): string | null {
  const email = (value ?? '').trim();
  if (!email) return null;
  if (email.length > 254) return '邮箱最多 254 个字符';
  if (email.includes(' ')) return '邮箱不能包含空格';
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email))
    return '邮箱格式不正确，请填写类似 purchasing@example.com 的地址';
  return null;
}

export function contactPhoneProblem(
  value: string | null | undefined,
  label: '电话' | '手机号',
): string | null {
  const phone = (value ?? '').trim().replace(/ /g, '');
  if (!phone) return null;
  if (phone.length > 64) return `${label}最多 64 个字符`;
  if (!/^[+0-9()\-.]+$/.test(phone))
    return `${label}只能包含数字与 + - ( ) . ，请填写如 +86-138-0000-0000（分机请用 - 连接）`;
  return null;
}

/**
 * 联系方式整体校验：邮箱 / 电话 / 手机**至少给一项**。
 *
 * <p>与后端 `PartyContactRules.reachProblem` 同一口径，包括**判定的先后**：
 * 「填了但只有空格」要先报出来 —— 否则下面那些格式校验会把 "   " 报成
 * 「邮箱格式不正确」，让运营以为自己格式写错了。
 */
export function contactReachProblem(
  email: string | null | undefined,
  phone: string | null | undefined,
  mobile: string | null | undefined,
): string | null {
  const dirty = [
    [email, '邮箱'],
    [phone, '电话'],
    [mobile, '手机号'],
  ].find(
    ([value]) =>
      value !== null && value !== undefined && value !== '' && (value as string).trim() === '',
  );
  if (dirty) return `${dirty[1]}填了但只有空格，请清空或填写内容`;
  return (
    contactEmailProblem(email) ??
    contactPhoneProblem(phone, '电话') ??
    contactPhoneProblem(mobile, '手机号') ??
    ([email, phone, mobile].every((value) => (value ?? '').trim() === '')
      ? '邮箱、电话、手机号至少填写一项：06 采购问交期、10 物流约提货、11 售后约退货都要靠它' +
        '联系到这个人，三项都空的联系人在任何一张单据上都无法使用'
      : null)
  );
}

export function contactInputProblem(input: PartyContactInput): string | null {
  return (
    contactNameProblem(input.name) ??
    optionalProblem(input.position, 100, '职务') ??
    contactReachProblem(input.email, input.phone, input.mobile) ??
    ((input.note ?? '').length > 2000 ? '联系人备注最多 2000 个字符' : null)
  );
}

// ------------------------------------------------------------ 表单

/** 空地址表单初值。 */
export function emptyAddressInput(): PartyAddressInput {
  return {
    countryCode: 'CN',
    stateProvince: null,
    city: '',
    district: null,
    postalCode: null,
    streetLine1: '',
    streetLine2: null,
    label: null,
    changeNote: null,
    usages: [],
    version: 0,
  };
}

/** 详情 → 表单初值（编辑时必须带全用途：整体替换语义下少发一条就是静默地取消一个用途）。 */
export function toAddressInput(address: PartyAddress): PartyAddressInput {
  return {
    countryCode: address.countryCode,
    stateProvince: address.stateProvince,
    city: address.city,
    district: address.district,
    postalCode: address.postalCode,
    streetLine1: address.streetLine1,
    streetLine2: address.streetLine2,
    label: address.label,
    changeNote: null,
    usages: (address.usages ?? []).map((usage) => ({
      usage,
      isDefault: address.defaultUsages.includes(usage),
    })),
    version: address.version,
  };
}

export function emptyContactInput(): PartyContactInput {
  return {
    name: '',
    position: null,
    email: null,
    phone: null,
    mobile: null,
    isPrimary: false,
    note: null,
    version: 0,
  };
}

export function toContactInput(contact: PartyContact): PartyContactInput {
  return {
    name: contact.name,
    position: contact.position,
    email: contact.email,
    phone: contact.phone,
    mobile: contact.mobile,
    isPrimary: contact.isPrimary,
    note: contact.note,
    version: contact.version,
  };
}

/**
 * 已标用途但提交集合里没有的用途 = 这次保存会被取消的用途。
 *
 * <p>界面上必须显式提示：用途是**整体替换**语义，少发一条就是静默地取消一个用途 ——
 * 而「我明明在改门牌号，怎么提货地址没了」是最难排查的一类问题。
 */
export function removedUsages(
  current: string[],
  submitted: (PartyAddressUsageInput | null)[],
): string[] {
  const kept = new Set(
    submitted
      .filter((usage): usage is NonNullable<typeof usage> => usage !== null)
      .map((usage) => usage.usage.trim().toUpperCase()),
  );
  return current.filter((usage) => !kept.has(usage));
}

/** 联系人展示名：有职务时带上职务（跨境场景里「采购经理」比名字本身更能定位对接人）。 */
export function contactDisplayName(contact: Pick<PartyContact, 'name' | 'position'>): string {
  const title = (contact.position ?? '').trim();
  return title ? `${contact.name}（${title}）` : contact.name;
}

/** 空态文案：两种空态不同 —— 无数据 vs 有筛选但没命中。 */
export function addressEmptyHint(filtered: boolean) {
  return filtered
    ? '当前筛选条件下没有地址。地址是 06 采购发货、10 物流提货的数据来源，先建一条并标上用途。'
    : '这个往来单位还没有地址。一个供应商通常有不止一处（工厂仓、办公室、海外仓），' +
        '都建下来并标好用途，其中每个用途会有一个默认地址。';
}

export function contactEmptyHint(filtered: boolean) {
  return filtered
    ? '当前筛选条件下没有联系人。试试清掉关键词或只看默认联系人。'
    : '这个往来单位还没有联系人。至少建一个，并指定谁是默认联系人 —— ' +
        '06 采购问交期、11 售后约退货都要靠它找到人。';
}

export function addressVersionEmptyHint() {
  return (
    '这条地址还没有历史版本。地址内容每改一次会留下一版，' +
    '已发生单据上的地址是冻结快照，改地址不会影响它们。'
  );
}

/** 错误文案：后端给的是可执行的中文原因，这里只补上上下文。 */
export function errorText(error: unknown, fallback = '地址与联系人数据加载失败') {
  if (error instanceof ApiError && error.message) return error.message;
  return fallback;
}

export type { PartyAddress, PartyAddressVersion, PartyContact };
