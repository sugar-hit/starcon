import type {
  PartyCarrierProfile,
  PartyCarrierProfileInput,
  PartyCustomerProfile,
  PartyCustomerProfileInput,
  PartyRoleProfileOptions,
  PartyRoleProfileOverview,
  PartyRoleProfilePage,
  PartyRoleProfileRow,
  PartyRoleProfiles,
  PartySupplierProfile,
  PartySupplierProfileInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.6.1.2 角色档案前端模型。
 *
 * <p>这个页面上最容易出错的三处：
 *
 * <ul>
 *   <li><b>「没指派身份」「指派了但没建档案」「建了」是三种状态，必须长得不一样</b>。
 *       后端刻意让读端点在「没有对应角色」时返回 200 + `profileFound=false` 而不是 404，
 *       就是为了让前端能把这三种状态分开显示 —— 404 只能表达成「什么都没了」。
 *       所以这里有 {@link ProfileSlotState} 三态，而不是一个 boolean。
 *   <li><b>「没设额度」与「额度为 0」在业务上是相反的两种默认</b>。
 *       不设额度 = 不限制赊账；额度 0 = 完全不许赊。表单里把「不设额度」做成**留空**
 *       而不是 0，服务端也把 0 判为非法（规则 R2）。前端照同一口径判，
 *       不能在输入框里默默把空值变成 0 —— 那会把「不限制」变成「不许赊」。
 *   <li><b>结算方式与账期互相牵制（规则 R1）</b>。账期结算必须大于 0 天，
 *       预付 / 货到付款 / 信用证一律 0 天。这条在前端就判是为了少一次无谓的往返，
 *       但服务层那份才是权威 —— 复制一份口径不是为了绕过它。
 * </ul>
 *
 * <p>本文件的校验与 `PartyRoleProfileRules` 逐字同口径。
 */

/** 角色档案对应的主体身份。蓝图职责点名的三个身份。 */
export const ROLE_PROFILE_KINDS = [
  {
    value: 'SUPPLIER',
    label: '供应商',
    endpoint: 'supplier-profile',
    hint: '账期、MOQ、合作等级、结算方式 —— 06 采购单与 12 应付的默认取值来源',
  },
  {
    value: 'CUSTOMER',
    label: '客户',
    endpoint: 'customer-profile',
    hint: '客户分级、默认币种、信用额度、账期 —— 09 订单与 12 应收的默认取值来源',
  },
  {
    value: 'CARRIER',
    label: '承运商',
    endpoint: 'carrier-profile',
    hint: '接口类型、账号、服务能力 —— 10 物流按它决定运单能否自动跟踪',
  },
] as const;

export type RoleProfileKind = (typeof ROLE_PROFILE_KINDS)[number]['value'];

export function kindOf(value: string | null | undefined): (typeof ROLE_PROFILE_KINDS)[number] {
  return ROLE_PROFILE_KINDS.find((item) => item.value === value) ?? ROLE_PROFILE_KINDS[0];
}

export function kindLabel(value: string | null | undefined): string {
  return kindOf(value).label;
}

/**
 * 三态槽位：后端用 `roleId === null`（没指派身份）与 `profileFound === false`
 * （指派了但没建档案）区分这两种「没有」，前端把它们原样保留下来显示，
 * 不合并成一个「没有档案」。
 */
export type ProfileSlotState = 'not-assigned' | 'no-profile' | 'saved';

export function slotState(
  roleId: string | null | undefined,
  profileFound: boolean,
): ProfileSlotState {
  if (!roleId) return 'not-assigned';
  return profileFound ? 'saved' : 'no-profile';
}

export const SLOT_STATE_TEXT: Record<ProfileSlotState, { label: string; hint: string }> = {
  'not-assigned': {
    label: '未指派身份',
    hint: '先在上方指派这个身份，才能维护它的角色档案 —— 档案只能挂在已指派的身份上',
  },
  'no-profile': {
    label: '尚未建档案',
    hint: '下游取不到默认值，先建一份档案再下单',
  },
  saved: { label: '已建档案', hint: '下游会带出这里的默认值作为单据初值' },
};

export function slotStateText(state: ProfileSlotState): { label: string; hint: string } {
  return SLOT_STATE_TEXT[state];
}

// ------------------------------------------------------------ 接口调用

export function fetchProfileOverview() {
  return api<PartyRoleProfileOverview>('catalog/role-profiles/overview');
}

export function fetchProfileOptions(roleType: string) {
  return api<PartyRoleProfileOptions>(
    `catalog/role-profiles/options?roleType=${encodeURIComponent(roleType)}`,
  );
}

export type RoleProfileListQuery = {
  roleType: RoleProfileKind;
  keyword: string;
  level: string;
  capability: string;
  settlementMethod: string;
  defaultCurrency: string;
  missingOnly: boolean;
  includeInactive: boolean;
  page: number;
  size: number;
};

export function emptyProfileQuery(roleType: RoleProfileKind = 'SUPPLIER'): RoleProfileListQuery {
  return {
    roleType,
    keyword: '',
    level: '',
    capability: '',
    settlementMethod: '',
    defaultCurrency: '',
    missingOnly: false,
    includeInactive: false,
    page: 0,
    size: 20,
  };
}

export function fetchRoleProfiles(query: RoleProfileListQuery) {
  const params = new URLSearchParams();
  params.set('roleType', query.roleType);
  if (query.keyword.trim()) params.set('keyword', query.keyword.trim());
  if (query.level) params.set('level', query.level);
  if (query.capability) params.set('capability', query.capability);
  if (query.settlementMethod) params.set('settlementMethod', query.settlementMethod);
  if (query.defaultCurrency.trim()) params.set('defaultCurrency', query.defaultCurrency.trim());
  if (query.missingOnly) params.set('missingProfile', 'true');
  if (query.includeInactive) params.set('activeOnly', 'false');
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<PartyRoleProfilePage>(`catalog/role-profiles?${params.toString()}`);
}

export function fetchRoleProfilesOfParty(partyId: string) {
  return api<PartyRoleProfiles>(`catalog/parties/${partyId}/role-profiles`);
}

export function fetchSupplierProfile(partyId: string) {
  return api<PartySupplierProfile>(`catalog/parties/${partyId}/supplier-profile`);
}

export function fetchCustomerProfile(partyId: string) {
  return api<PartyCustomerProfile>(`catalog/parties/${partyId}/customer-profile`);
}

export function fetchCarrierProfile(partyId: string) {
  return api<PartyCarrierProfile>(`catalog/parties/${partyId}/carrier-profile`);
}

export function saveSupplierProfile(partyId: string, input: PartySupplierProfileInput) {
  return api<PartySupplierProfile>(`catalog/parties/${partyId}/supplier-profile`, 'PUT', input);
}

export function saveCustomerProfile(partyId: string, input: PartyCustomerProfileInput) {
  return api<PartyCustomerProfile>(`catalog/parties/${partyId}/customer-profile`, 'PUT', input);
}

export function saveCarrierProfile(partyId: string, input: PartyCarrierProfileInput) {
  return api<PartyCarrierProfile>(`catalog/parties/${partyId}/carrier-profile`, 'PUT', input);
}

/**
 * 删掉角色档案（角色指派本身保留 —— 解指派是 3.6.1.1 的动作）。
 *
 * <p>删掉之后下游退回「没设过」（额度不设 = 不限制、账期 0 = 现结），
 * 这是一个可逆且有意义的状态，不是需要历史追溯的凭证。
 */
export function deleteRoleProfile(partyId: string, roleType: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/parties/${partyId}/${roleType.toLowerCase()}-profile?version=${version}`,
    'DELETE',
  );
}

// ------------------------------------------------------------ 表单与校验（与服务层同口径）

export type SupplierProfileForm = {
  paymentTermsDays: string;
  moqQuantity: string;
  moqUnitCode: string;
  cooperationLevel: string;
  settlementMethod: string;
  note: string;
  version: number;
};

/**
 * 空表单：**账期默认空而不是 0**。
 *
 * <p>账期 0 在业务上有确切含义（现结），与「还没填」不是一回事；
 * 提交时空串被服务端归成 0（= 现结），这与「不填 = 现结」的业务口径一致，
 * 但界面上要让用户看见自己填了什么。
 */
export function emptySupplierForm(): SupplierProfileForm {
  return {
    paymentTermsDays: '',
    moqQuantity: '',
    moqUnitCode: '',
    cooperationLevel: 'STANDARD',
    settlementMethod: 'PREPAID',
    note: '',
    version: 0,
  };
}

export function toSupplierForm(profile: PartySupplierProfile): SupplierProfileForm {
  return {
    paymentTermsDays: profile.paymentTermsDays > 0 ? String(profile.paymentTermsDays) : '',
    moqQuantity: profile.moqQuantity === null ? '' : String(profile.moqQuantity),
    moqUnitCode: profile.moqUnitCode ?? '',
    cooperationLevel: profile.cooperationLevel,
    settlementMethod: profile.settlementMethod,
    note: profile.note ?? '',
    version: profile.version,
  };
}

export function toSupplierInput(form: SupplierProfileForm): PartySupplierProfileInput {
  return {
    paymentTermsDays: form.paymentTermsDays.trim() === '' ? null : Number(form.paymentTermsDays),
    moqQuantity: form.moqQuantity.trim() === '' ? null : Number(form.moqQuantity),
    moqUnitCode: form.moqUnitCode.trim() === '' ? null : form.moqUnitCode.trim(),
    cooperationLevel: form.cooperationLevel,
    settlementMethod: form.settlementMethod,
    note: form.note,
    version: form.version,
  };
}

/**
 * 供应商档案校验（规则 **R1** 与 MOQ 成对规则）。
 *
 * <p>与 `PartyRoleProfileRules` 逐字同口径。抄一份到前端是为了少一次往返，
 * 服务层那份才是权威。
 */
export function supplierFormProblem(form: SupplierProfileForm): string | null {
  const terms = parseTerms(form.paymentTermsDays);
  if (terms === null) return '账期请填 0~365 的整数天数（留空 = 现结）';
  const method = form.settlementMethod || 'PREPAID';
  if (method === 'CREDIT' && terms <= 0)
    return '结算方式是「账期结算」时账期必须大于 0 天（0 天等于预付，与所选的结算方式矛盾）';
  if (method !== 'CREDIT' && terms !== 0)
    return `结算方式是「${settlementLabel(method)}」时不占用账期，请把账期改为 0 天（要用账期结算请选「账期结算」）`;

  const quantity = form.moqQuantity.trim();
  const unit = form.moqUnitCode.trim();
  if (quantity && !unit)
    return '填了最小起订量就必须填采购单位（同样是「100」，在「件」和「箱」下差几十倍）';
  if (!quantity && unit) return '填了采购单位就必须填最小起订量，只给单位没有数量说不清起订门槛';
  if (quantity) {
    const value = Number(quantity);
    if (!Number.isFinite(value) || value <= 0)
      return '最小起订量必须大于 0（不设起订量请留空，不要填 0）';
    const decimals = quantity.includes('.') ? (quantity.split('.')[1]?.length ?? 0) : 0;
    if (decimals > 6) return '最小起订量最多 6 位小数';
  }
  if (form.note.length > 2000) return '备注最多 2000 个字符';
  return null;
}

export type CustomerProfileForm = {
  customerLevel: string;
  defaultCurrency: string;
  defaultCreditLimit: string;
  paymentTermsDays: string;
  note: string;
  version: number;
};

export function emptyCustomerForm(): CustomerProfileForm {
  return {
    customerLevel: 'STANDARD',
    defaultCurrency: '',
    defaultCreditLimit: '',
    paymentTermsDays: '',
    note: '',
    version: 0,
  };
}

export function toCustomerForm(profile: PartyCustomerProfile): CustomerProfileForm {
  return {
    customerLevel: profile.customerLevel,
    defaultCurrency: profile.defaultCurrency ?? '',
    // 额度为空就是「不设额度」，表单里保持空串而不是 0
    defaultCreditLimit:
      profile.defaultCreditLimit === null ? '' : String(profile.defaultCreditLimit),
    paymentTermsDays: profile.paymentTermsDays > 0 ? String(profile.paymentTermsDays) : '',
    note: profile.note ?? '',
    version: profile.version,
  };
}

export function toCustomerInput(form: CustomerProfileForm): PartyCustomerProfileInput {
  return {
    customerLevel: form.customerLevel,
    // 币种统一大写：库里存 ISO 4217 三位大写，大小写混用会让「按币种分组」出现同一币种两行。
    defaultCurrency:
      form.defaultCurrency.trim() === '' ? null : form.defaultCurrency.trim().toUpperCase(),
    defaultCreditLimit:
      form.defaultCreditLimit.trim() === '' ? null : Number(form.defaultCreditLimit),
    paymentTermsDays: form.paymentTermsDays.trim() === '' ? null : Number(form.paymentTermsDays),
    note: form.note,
    version: form.version,
  };
}

/**
 * 客户档案校验（规则 **R2** / **R4**）。
 *
 * <p>额度留空 = 不限制赊账；0 是非法值。这两个在业务上**相反**，
 * 所以表单绝不能把空值默默变成 0。
 */
export function customerFormProblem(form: CustomerProfileForm): string | null {
  const terms = parseTerms(form.paymentTermsDays);
  if (terms === null) return '账期请填 0~365 的整数天数（留空 = 现结）';
  const currency = form.defaultCurrency.trim();
  if (currency && !/^[A-Za-z]{3}$/.test(currency))
    return '币种请用 ISO 4217 三位代码，如 USD / EUR / CNY';

  const limitText = form.defaultCreditLimit.trim();
  if (limitText) {
    const value = Number(limitText);
    if (!Number.isFinite(value) || value <= 0)
      return '信用额度必须大于 0（不设额度请留空 —— 「没设额度」表示不限制赊账，而「额度为 0」表示完全不许赊）';
    const decimals = limitText.includes('.') ? (limitText.split('.')[1]?.length ?? 0) : 0;
    if (decimals > 4) return '信用额度最多 4 位小数';
    if (!currency)
      return '填了信用额度就必须填默认币种 —— 12 应收拿它和发票币种对比，没币种的额度只能靠假设，而假设错了不会报错';
    if (form.customerLevel === 'RISK')
      return '风险客户的信用额度请留空（先清掉逾期记录、把分级改回正常档，再恢复额度）';
    if (form.customerLevel === 'PROSPECT')
      return '潜在客户设置了信用额度，请确认这不是在成交之前提前发放的额度（仍可保存，仅提醒）';
  }
  if (form.note.length > 2000) return '备注最多 2000 个字符';
  return null;
}

export type CarrierProfileForm = {
  interfaceType: string;
  accountRef: string;
  serviceCapabilities: string[];
  serviceScopeNote: string;
  note: string;
  version: number;
};

export function emptyCarrierForm(): CarrierProfileForm {
  return {
    interfaceType: 'API',
    accountRef: '',
    serviceCapabilities: [],
    serviceScopeNote: '',
    note: '',
    version: 0,
  };
}

export function toCarrierForm(profile: PartyCarrierProfile): CarrierProfileForm {
  return {
    interfaceType: profile.interfaceType,
    accountRef: profile.accountRef ?? '',
    serviceCapabilities: [...profile.serviceCapabilities],
    serviceScopeNote: profile.serviceScopeNote ?? '',
    note: profile.note ?? '',
    version: profile.version,
  };
}

export function toCarrierInput(form: CarrierProfileForm): PartyCarrierProfileInput {
  return {
    interfaceType: form.interfaceType,
    accountRef: form.accountRef,
    serviceCapabilities: form.serviceCapabilities,
    serviceScopeNote: form.serviceScopeNote,
    note: form.note,
    version: form.version,
  };
}

/**
 * 承运商档案校验。
 *
 * <p>账号必填：没有账号的档案在 10 物流里取不到凭据、也登不进平台
 * （与 3.6.1.1 规则 P3「联系方式至少给一项」同源）。
 * 这里只判空白，**不判是不是密钥** —— 档案按 AGENTS.md 的秘密管理口径只存账号标识，
 * 凭据由部署侧从 secret manager 注入。
 */
export function carrierFormProblem(form: CarrierProfileForm): string | null {
  if (!form.accountRef.trim())
    return '承运商账号标识不能为空（没有账号的档案在 10 物流里取不到凭据、也登不进平台）';
  if (form.accountRef.trim().length > 128) return '账号标识最多 128 个字符';
  if (form.serviceScopeNote.length > 2000) return '服务范围说明最多 2000 个字符';
  if (form.note.length > 2000) return '备注最多 2000 个字符';
  return null;
}

/** 账期解析：空串合法（= 现结），其余必须是 0~365 的整数。 */
function parseTerms(value: string): number | null {
  const text = value.trim();
  if (text === '') return 0;
  if (!/^\d+$/.test(text)) return null;
  const parsed = Number(text);
  return parsed > 365 ? null : parsed;
}

// ------------------------------------------------------------ 词表标签（与 V30 / 服务层同一份）

export function cooperationLabel(value: string | null | undefined): string {
  switch (value) {
    case 'STRATEGIC':
      return '战略供应商';
    case 'PREFERRED':
      return '优选供应商';
    case 'STANDARD':
      return '常规供应商';
    case 'TRIAL':
      return '试单供应商';
    case 'RISK':
      return '风险供应商';
    default:
      return value ?? '';
  }
}

export function settlementLabel(value: string | null | undefined): string {
  switch (value) {
    case 'PREPAID':
      return '预付';
    case 'ON_DELIVERY':
      return '货到付款';
    case 'CREDIT':
      return '账期结算';
    case 'LETTER_OF_CREDIT':
      return '信用证';
    default:
      return value ?? '';
  }
}

export function customerLevelLabel(value: string | null | undefined): string {
  switch (value) {
    case 'STRATEGIC':
      return '战略客户';
    case 'KEY':
      return '重点客户';
    case 'STANDARD':
      return '普通客户';
    case 'PROSPECT':
      return '潜在客户';
    case 'RISK':
      return '风险客户';
    default:
      return value ?? '';
  }
}

export function interfaceLabel(value: string | null | undefined): string {
  switch (value) {
    case 'API':
      return '系统对接';
    case 'SFTP_FILE':
      return '文件交换';
    case 'WEB_PORTAL':
      return '平台下单';
    case 'EMAIL':
      return '邮件往来';
    default:
      return value ?? '';
  }
}

export function capabilityLabel(value: string | null | undefined): string {
  switch (value) {
    case 'AIR':
      return '空运';
    case 'SEA':
      return '海运';
    case 'RAIL':
      return '铁路';
    case 'TRUCK':
      return '陆运';
    case 'EXPRESS':
      return '国际快递';
    case 'DOOR_TO_DOOR':
      return '门到门';
    case 'CUSTOMS':
      return '代理清关';
    case 'INSURED':
      return '保价';
    case 'URGENT':
      return '加急';
    default:
      return value ?? '';
  }
}

/** 合作等级说明：下拉里的 hint 用，解释每个取值在业务上意味着什么。 */
export const COOPERATION_HINTS: Record<string, string> = {
  STRATEGIC: '绑定多年、量价一议；缺货直接影响主推 listing，采购拆单优先保它',
  PREFERRED: '同品类第二选择，有返点或账期优惠；战略供应商断供时切换到它',
  STANDARD: '按目录价与标准账期走，新建供应商的默认档',
  TRIAL: '正在验证质量或交期，通常不给账期',
  RISK: '有质量或交期问题待观察，采购单必须人工确认才能下',
};

export const SETTLEMENT_HINTS: Record<string, string> = {
  PREPAID: '先款后货，账期必须 0 天',
  ON_DELIVERY: '货到即付（自提 / 专线），账期必须 0 天',
  CREDIT: '先货后款，到期日 = 单据日 + 账期天数，账期必须大于 0',
  LETTER_OF_CREDIT: '开证付款，由银行承兑，不占用供应商账期，账期必须 0 天',
};

export const CUSTOMER_LEVEL_HINTS: Record<string, string> = {
  STRATEGIC: '年度采购额最高的一档，账期与额度单独谈，逾期要上报',
  KEY: '稳定复购，逾期只催不锁额度',
  STANDARD: '新建客户的默认档',
  PROSPECT: '已建档但尚未成交；信用额度应当留空 —— 它意味着「还没开始给钱」',
  RISK: '有逾期或纠纷记录；信用额度必须留空，新单应要求预付',
};

export const INTERFACE_HINTS: Record<string, string> = {
  API: '对方有可用 API（FedEx / UPS / DHL 这类），10 物流直连取运单与轨迹',
  SFTP_FILE: '对方只给 EDI / CSV 报文，按约定目录投递',
  WEB_PORTAL: '我们登录对方后台手工录单（账号仍然要登记）',
  EMAIL: '发邮件报关、对方回邮件发运单号，轨迹需要人工回填',
};

/** 列表行的角色状态标签。 */
export function roleStatusLabel(value: string | null | undefined): string {
  return value === 'ACTIVE' ? '使用中' : value === 'INACTIVE' ? '已停用' : '未知';
}

/** 概览里「应为零」的两个探针的说明。 */
export const PROBE_HINTS: Record<string, string> = {
  riskyCustomerWithCreditCount: '风险客户里仍留着信用额度的条数（规则 R4 生效后应为 0）',
  termsWithoutCreditCount:
    '有账期但结算方式不是账期结算的供应商条数（规则 R1 的 DB 兜底生效后应为 0）',
};

/** 列表行摘要为空时的兜底文案（三种「没有」各有各的说法）。 */
export function rowHeadline(row: PartyRoleProfileRow): string {
  if (!row.profileFound) return '尚未建档案';
  return row.headline ?? '';
}

/** 服务层返回的错误文案：直接显示，不要在前端再翻译一遍。 */
export function profileErrorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '操作失败，请稍后重试';
}
