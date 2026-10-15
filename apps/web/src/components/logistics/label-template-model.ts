import { api } from '../inventory/api';

/**
 * 10.2.2.2 面单模板前端模型。
 *
 * <p>蓝图：「维护面单模板与打印规格（尺寸、条码类型、附加声明、电池标识、语言版本），
 * 支持按渠道与国家配置。」输出「模板 + 打印任务（经 8.3.4.2）」，并加粗
 * 「**附加声明（如电池、液体）漏打会导致清关问题**，模板配置要与渠道规则强绑定」。
 *
 * <p>🔴 这个模块的界面上最容易做成假活的两件事，这里都刻意避开：
 *
 * <ol>
 *   <li><b>拿渠道限制自己判断</b>。渠道要求（电池 / 液体 / 磁力）由
 *       {@link judgeLabelBinding} 的**服务端**结论给出（POST /binding），
 *       页面里**没有**任何一处拿声明数组自己比对「这一票能不能用这张模板」——
 *       那样做的规则会散落到每个调用方，而总有一个调用方会漏掉「液体」这一条。
 *   <li><b>预览画个示意图</b>。{@link previewLabelTemplate} 返回的是后端**真渲染**的
 *       ZPL 指令流（含真条码编码与校验位）与逐条 findings，页面把它原样贴出来，
 *       不在浏览器里另画一张「预览图」冒充渲染结果。
 * </ol>
 *
 * <p>尺寸一律按 <b>mm</b> 原样提交与显示：页面**不做**任何单位换算，
 * 换算与取整是后端 {@code LabelRenderEngine} 的单一实现（毫米 → dot，HALF_UP）。
 * 前端再算一遍就出现「界面上的位置」与「打印机上的位置」两份真相。
 */

export interface LabelCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface LabelTemplateCapabilities {
  dimensions: LabelCapabilityDimension[];
  labelFormats: string[];
  symbologies: string[];
  barcodeKinds: string[];
  elementKinds: string[];
  declarationKinds: string[];
  batteryTypes: string[];
  languageCodes: string[];
  statusCodes: string[];
  storedReadinessCodes: string[];
  effectiveReadinessCodes: string[];
  verdictCodes: string[];
  boundaryNote: string;
  bindingNote: string;
}

/** 打印元素：纸上印什么、印在哪（mm）。 */
export interface LabelElement {
  elementKey: string;
  elementKind: string;
  symbology: string | null;
  xMm: number;
  yMm: number;
  wMm: number;
  hMm: number;
  fontPt: number | null;
  rotation: number;
  mandatory: boolean;
  sequence: number;
  note: string;
}

/** 附加声明。**必须**指向一个存在的 elementKey（漏打 = 清关问题）。 */
export interface LabelDeclaration {
  declarationKind: string;
  declarationCode: string;
  displayText: string;
  elementKey: string;
  mandatory: boolean;
  note: string;
}

export interface LabelBatteryMark {
  batteryType: string;
  /** 瓦时 / 重量一律 number，**页面不做浮点运算**，原样提交（换算在后端）。 */
  wattHours: number | null;
  batteryWeightKg: number | null;
  un38_3Attested: boolean;
  markingSymbol: string;
  elementKey: string;
  note: string;
}

export interface LabelTemplateRow {
  id: string;
  channelId: string;
  channelCode: string;
  channelName: string;
  carrierName: string;
  carrierCode: string;
  channelReadiness: string;
  channelStatus: string;
  templateCode: string;
  templateName: string;
  countryCode: string;
  languageCode: string;
  status: string;
  paperWidthMm: number;
  paperHeightMm: number;
  dpi: number;
  orientation: string;
  originOffsetXMm: number;
  originOffsetYMm: number;
  primaryBarcodeSymbology: string;
  primaryBarcodeHeightMm: number;
  humanReadable: boolean;
  elements: LabelElement[];
  declarations: LabelDeclaration[];
  batteryMarks: LabelBatteryMark[];
  readiness: string;
  readinessMessage: string;
  /** 「现在能不能用」：落库的齐备度 + 上游渠道可用性在**读取时**合成。 */
  effectiveReadiness: string;
  effectiveReadinessMessage: string;
  activatable: boolean;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface LabelTemplateSummary {
  total: number;
  ready: number;
  noLayout: number;
  noBarcode: number;
  declarationGap: number;
  noChannel: number;
  draft: number;
  active: number;
  withBatteryMark: number;
  multiLanguage: number;
}

export interface LabelTemplatePage {
  items: LabelTemplateRow[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  summary: LabelTemplateSummary;
}

export interface LabelTemplateCount {
  code: string;
  label: string;
  count: number;
}

export interface LabelTemplateOverview {
  summary: LabelTemplateSummary;
  bySymbology: LabelTemplateCount[];
  byLanguage: LabelTemplateCount[];
  /**
   * 附加声明分布 —— 刻意**没有** byFormat：模板表没有输出格式列，渲染产物的格式
   * 记在 render 行上。加一个永远为 0 的 byFormat 比没有更糟。
   *
   * <p>这一栏是加粗句在概览层面的落点：「一张强制声明都没登记」比任何汇总数字
   * 都更值得运营立刻看见。
   */
  byDeclarationKind: LabelTemplateCount[];
  note: string;
}

/** 一条判定明细：severity 分色，code 给程序判断，message 给人看。 */
export interface LabelFinding {
  severity: string;
  code: string;
  message: string;
}

/** 🔴 真渲染结果：zplContent 是后端算出来的真指令流，不��浏览器画的示意图。 */
export interface LabelRenderPreview {
  templateId: string;
  templateCode: string;
  verdict: string;
  verdictMessage: string;
  findings: LabelFinding[];
  blockCount: number;
  reviewCount: number;
  labelFormat: string;
  symbology: string;
  encodedValue: string;
  barcodeModules: string;
  zplContent: string;
  contentSha256: string;
  persisted: boolean;
  previewNote: string;
}

export interface LabelRenderRow {
  id: string;
  triggerSource: string;
  symbology: string;
  verdict: string;
  findingCount: number;
  contentSha256: string;
  zplContent: string;
  renderedAt: string;
}

export interface LabelRenderPage {
  items: LabelRenderRow[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface LabelSampleValue {
  elementKey: string;
  value: string;
}

export interface SaveLabelTemplateInput {
  templateId: string | null;
  channelId: string | null;
  templateCode: string;
  templateName: string;
  countryCode: string;
  languageCode: string;
  status: string;
  paperWidthMm: number;
  paperHeightMm: number;
  dpi: number;
  orientation: string;
  originOffsetXMm: number | null;
  originOffsetYMm: number | null;
  primaryBarcodeSymbology: string;
  primaryBarcodeHeightMm: number;
  humanReadable: boolean;
  elements: LabelElement[];
  declarations: LabelDeclaration[];
  batteryMarks: LabelBatteryMark[];
  note: string;
  version: number;
}

// ================================================================== 表单

export interface LabelTemplateForm {
  templateId: string | null;
  channelId: string;
  templateCode: string;
  templateName: string;
  countryCode: string;
  languageCode: string;
  status: string;
  paperWidthMm: string;
  paperHeightMm: string;
  dpi: string;
  orientation: string;
  originOffsetXMm: string;
  originOffsetYMm: string;
  primaryBarcodeSymbology: string;
  primaryBarcodeHeightMm: string;
  humanReadable: boolean;
  elements: LabelElement[];
  declarations: LabelDeclaration[];
  batteryMarks: LabelBatteryMark[];
  note: string;
  version: number;
}

export const EMPTY_LABEL_FORM: LabelTemplateForm = {
  templateId: null,
  channelId: '',
  templateCode: '',
  templateName: '',
  countryCode: 'DE',
  languageCode: 'de-DE',
  status: 'DRAFT',
  paperWidthMm: '100',
  paperHeightMm: '150',
  dpi: '203',
  orientation: 'PORTRAIT',
  originOffsetXMm: '0',
  originOffsetYMm: '0',
  primaryBarcodeSymbology: 'CODE128_B',
  primaryBarcodeHeightMm: '12',
  humanReadable: true,
  elements: [],
  declarations: [],
  batteryMarks: [],
  note: '',
  version: 0,
};

export interface LabelFilters {
  keyword: string;
  channelId: string;
  countryCode: string;
  status: string;
  readiness: string;
  symbology: string;
  language: string;
  sort: string;
  page: number;
  size: number;
}

export const EMPTY_LABEL_FILTERS: LabelFilters = {
  keyword: '',
  channelId: '',
  countryCode: '',
  status: '',
  readiness: '',
  symbology: '',
  language: '',
  sort: 'NAME_ASC',
  page: 0,
  size: 50,
};

/** 🔴 渠道规则绑定核对的请求与结论（服务端判定，前端只负责把缺口念出来）。 */
export interface LabelBindingInput {
  channelId: string | null;
  countryCode: string;
  categoryCodes: string[];
  containsBattery: boolean;
  containsLiquid: boolean;
  containsMagnet: boolean;
  templateIds: string[] | null;
}

export interface LabelBindingVerdict {
  templateId: string;
  templateCode: string;
  templateName: string;
  status: string;
  effectiveReadiness: string;
  verdict: string;
  missingDeclarations: string[];
  missingBatteryMarks: string[];
  redundantDeclarations: string[];
  findings: LabelFinding[];
}

export interface LabelBindingResult {
  verdicts: LabelBindingVerdict[];
  pass: number;
  review: number;
  block: number;
  note: string;
}

// ================================================================== 字典

export function symbologyLabel(code: string): string {
  return (
    {
      NONE: '不带条码',
      CODE128_B: 'Code 128 B',
      CODE128_A: 'Code 128 A',
      CODE39: 'Code 39',
      QR: 'QR 二维码',
      DATAMATRIX: 'DataMatrix',
    }[code] ?? code
  );
}

export function elementKindLabel(code: string): string {
  return (
    {
      SHIP_TO_NAME: '收件人',
      SHIP_TO_ADDRESS: '收件地址',
      SHIP_FROM_ADDRESS: '发件地址',
      WAYBILL_NUMBER: '运单号',
      WAYBILL_BARCODE: '运单条码',
      SERVICE_NAME: '服务类型',
      ROUTING_CODE: '路由码',
      CUSTOMS_DECLARATION: '报关信息',
      DECLARATION_TEXT: '声明文字',
      BATTERY_MARK: '电池标识',
      LOGO: '标识',
      TEXT: '文本',
      QR_CODE: '二维码',
    }[code] ?? code
  );
}

export function declarationKindLabel(code: string): string {
  return (
    {
      BATTERY: '电池',
      LIQUID: '液体',
      MAGNET: '磁力',
      DANGEROUS_GOODS: '危险品',
      BIOHAZARD: '生物危害',
      RETURN_DECLARATION: '退货申报',
      OTHER: '其他',
    }[code] ?? code
  );
}

export function batteryTypeLabel(code: string): string {
  return (
    {
      LITHIUM_ION: '锂离子',
      LITHIUM_METAL: '锂金属',
      LITHIUM_POLYMER: '锂聚合物',
      DRY_CELL: '干电池',
      OTHER: '其他',
    }[code] ?? code
  );
}

export function templateStatusLabel(code: string): string {
  return { DRAFT: '草稿', ACTIVE: '启用中', ARCHIVED: '已归档' }[code] ?? code;
}

export function triggerSourceLabel(code: string): string {
  return (
    {
      PREVIEW: '人工预览',
      ACTIVATION: '启用前渲染',
      BINDING: '渠道规则绑定核对',
    }[code] ?? code
  );
}

export interface Tone {
  label: string;
  /** 徽章色：BLOCK 红 / REVIEW 琥珀 / PASS 绿 / 中性灰。 */
  variant: 'danger' | 'warning' | 'success' | 'neutral';
}

/** 判定三档的分色。与后端 LabelVerdict 三档一一对应，不猜。 */
export function verdictMeta(code: string): Tone {
  if (code === 'BLOCK') return { label: '不可用', variant: 'danger' };
  if (code === 'REVIEW') return { label: '需人工确认', variant: 'warning' };
  return { label: '可用', variant: 'success' };
}

/**
 * 「现在能不能用」的分色。
 *
 * <p>🔴 NO_CHANNEL 与其它三档**不同色**：它不是「模板没排好」，
 * 而是「你绑的渠道本身停了」—— 改这张模板一点用都没有，运营要去改渠道。
 * 混进同一个红色里，现场会去反复调版式，而真正的原因在另一页。
 */
export function readinessMeta(code: string): Tone {
  if (code === 'READY') return { label: '可用', variant: 'success' };
  if (code === 'NO_CHANNEL') return { label: '渠道不可用', variant: 'warning' };
  if (code === 'DECLARATION_GAP') return { label: '声明漏打风险', variant: 'danger' };
  if (code === 'NO_BARCODE') return { label: '缺条码元素', variant: 'danger' };
  return { label: '未排版', variant: 'neutral' };
}

export function findingTone(severity: string): Tone {
  return severity === 'BLOCK'
    ? { label: '阻断', variant: 'danger' }
    : { label: '需确认', variant: 'warning' };
}

// ================================================================== 表单辅助

function num(raw: string, fallback: number): number {
  const value = Number(raw);
  return Number.isFinite(value) ? value : fallback;
}

function blank(raw: string | null): string {
  return raw === null || raw === undefined ? '' : String(raw);
}

/** 从详情行填回表单（编辑与新建共用一份形状）。 */
export function toLabelForm(row: LabelTemplateRow): LabelTemplateForm {
  return {
    templateId: row.id,
    channelId: row.channelId,
    templateCode: row.templateCode,
    templateName: row.templateName,
    countryCode: row.countryCode,
    languageCode: row.languageCode,
    status: row.status,
    paperWidthMm: String(row.paperWidthMm),
    paperHeightMm: String(row.paperHeightMm),
    dpi: String(row.dpi),
    orientation: row.orientation,
    originOffsetXMm: String(row.originOffsetXMm),
    originOffsetYMm: String(row.originOffsetYMm),
    primaryBarcodeSymbology: row.primaryBarcodeSymbology,
    primaryBarcodeHeightMm: String(row.primaryBarcodeHeightMm),
    humanReadable: row.humanReadable,
    elements: row.elements,
    declarations: row.declarations,
    batteryMarks: row.batteryMarks,
    note: row.note,
    version: row.version,
  };
}

export function labelFormToInput(form: LabelTemplateForm): SaveLabelTemplateInput {
  return {
    templateId: form.templateId,
    channelId: form.channelId === '' ? null : form.channelId,
    templateCode: form.templateCode.trim(),
    templateName: form.templateName.trim(),
    countryCode: form.countryCode.trim().toUpperCase(),
    languageCode: form.languageCode,
    status: form.status,
    paperWidthMm: num(form.paperWidthMm, 0),
    paperHeightMm: num(form.paperHeightMm, 0),
    dpi: num(form.dpi, 203),
    orientation: form.orientation,
    originOffsetXMm: num(form.originOffsetXMm, 0),
    originOffsetYMm: num(form.originOffsetYMm, 0),
    primaryBarcodeSymbology: form.primaryBarcodeSymbology,
    primaryBarcodeHeightMm: num(form.primaryBarcodeHeightMm, 0),
    humanReadable: form.humanReadable,
    elements: form.elements,
    declarations: form.declarations,
    batteryMarks: form.batteryMarks,
    note: form.note,
    version: form.version,
  };
}

/**
 * 提交前的本地校验。
 *
 * <p>刻意<b>不</b>在这里做「渠道要求是否满足」的判断 —— 那是服务端
 * {@link judgeLabelBinding} 的职责，页面自己判一遍就又成了第二份规则。
 * 这里只挡**结构性**错误（必填、越界、锚定），让用户在一次往返里少踩几个明显的坑；
 * 真正的合规结论仍然只有服务端那一份。
 */
export function validateLabelForm(form: LabelTemplateForm): string[] {
  const errors: string[] = [];
  if (form.templateCode.trim() === '') errors.push('模板代码不能为空');
  if (form.templateName.trim() === '') errors.push('模板名称不能为空');
  if (form.templateId === null && form.channelId === '') errors.push('新建必须指定所属渠道');
  if (!/^[A-Z]{2}$/.test(form.countryCode.trim().toUpperCase()))
    errors.push('国家必须是两位大写字母（例如 DE）');

  const width = num(form.paperWidthMm, 0);
  const height = num(form.paperHeightMm, 0);
  if (width <= 0) errors.push('纸张宽度必须大于 0mm');
  if (height <= 0) errors.push('纸张高度必须大于 0mm');

  const keys = new Set<string>();
  for (const element of form.elements) {
    const key = element.elementKey.trim();
    if (key === '') errors.push('打印位编码不能为空');
    else if (keys.has(key)) errors.push(`打印位「${key}」重复`);
    keys.add(key);
    // 越界只在这里给一句人话；服务端与 DB 触发器才是硬边界（真值以 mm 为准，不猜）
    if (element.xMm < 0 || element.yMm < 0)
      errors.push(`打印位「${key}」坐标为负：负坐标只能来自 origin_offset 校准`);
    else if (element.xMm + element.wMm > width || element.yMm + element.hMm > height)
      errors.push(`打印位「${key}」越出纸张（${width} × ${height} mm）`);
  }

  for (const declaration of form.declarations) {
    if (!keys.has(declaration.elementKey.trim()))
      errors.push(
        `附加声明「${declarationKindLabel(declaration.declarationKind)}」指向的打印位「${declaration.elementKey}」不存在：漏打会导致清关问题`,
      );
    if (declaration.displayText.trim() === '' && declaration.declarationCode.trim() === '')
      errors.push(
        `附加声明「${declarationKindLabel(declaration.declarationKind)}」既没有声明代码也没有印面文字，等于没声明`,
      );
  }
  for (const mark of form.batteryMarks) {
    if (!keys.has(mark.elementKey.trim()))
      errors.push(
        `电池标识「${batteryTypeLabel(mark.batteryType)}」指向的打印位「${mark.elementKey}」不存在：漏打会导致清关问题`,
      );
  }
  return errors;
}

/** 预览用的样例值：按形状自动填好，让运营改数字而不是从空白开始。 */
export function sampleValuesFor(elements: LabelElement[]): LabelSampleValue[] {
  return elements.map((element) => ({
    elementKey: element.elementKey,
    value:
      element.elementKind === 'WAYBILL_BARCODE' || element.elementKind === 'QR_CODE'
        ? 'DE1234567890123'
        : elementKindLabel(element.elementKind),
  }));
}

export function blankElement(sequence: number): LabelElement {
  return {
    elementKey: '',
    elementKind: 'TEXT',
    symbology: null,
    xMm: 4,
    yMm: 4,
    wMm: 40,
    hMm: 10,
    fontPt: 10,
    rotation: 0,
    mandatory: false,
    sequence,
    note: '',
  };
}

export function blankDeclaration(): LabelDeclaration {
  return {
    declarationKind: 'BATTERY',
    declarationCode: '',
    displayText: '',
    elementKey: '',
    mandatory: true,
    note: '',
  };
}

export function blankBatteryMark(): LabelBatteryMark {
  return {
    batteryType: 'LITHIUM_ION',
    wattHours: null,
    batteryWeightKg: null,
    un38_3Attested: false,
    markingSymbol: '',
    elementKey: '',
    note: '',
  };
}

// ================================================================== 端点

export function fetchLabelCapabilities(): Promise<LabelTemplateCapabilities> {
  return api<LabelTemplateCapabilities>('channel/label-templates/capabilities');
}

export function fetchLabelOverview(): Promise<LabelTemplateOverview> {
  return api<LabelTemplateOverview>('channel/label-templates/overview');
}

export function fetchLabelTemplates(filters: LabelFilters): Promise<LabelTemplatePage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.channelId) query.set('channelId', filters.channelId);
  if (filters.countryCode) query.set('countryCode', filters.countryCode);
  if (filters.status) query.set('status', filters.status);
  if (filters.readiness) query.set('readiness', filters.readiness);
  if (filters.symbology) query.set('symbology', filters.symbology);
  if (filters.language) query.set('language', filters.language);
  if (filters.sort) query.set('sort', filters.sort);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<LabelTemplatePage>(`channel/label-templates?${query.toString()}`);
}

export function fetchLabelTemplate(id: string): Promise<LabelTemplateRow> {
  return api<LabelTemplateRow>(`channel/label-templates/${id}`);
}

export function saveLabelTemplate(input: SaveLabelTemplateInput): Promise<LabelTemplateRow> {
  return api<LabelTemplateRow>('channel/label-templates', 'POST', input);
}

export function removeLabelTemplate(id: string): Promise<void> {
  return api<void>(`channel/label-templates/${id}`, 'DELETE');
}

export function setLabelTemplateStatus(
  id: string,
  status: string,
  version: number,
): Promise<LabelTemplateRow> {
  return api<LabelTemplateRow>(`channel/label-templates/${id}/status`, 'POST', {
    status,
    version,
  });
}

/** 🔴 真渲染预览 —— 返回后端算出来的真 ZPL 与逐条 findings，不是浏览器画的示意图。 */
export function previewLabelTemplate(
  id: string,
  elementValues: LabelSampleValue[],
  wattHours: number | null,
  persist: boolean,
): Promise<LabelRenderPreview> {
  return api<LabelRenderPreview>(`channel/label-templates/${id}/preview`, 'POST', {
    elementValues,
    wattHours,
    persist,
  });
}

export function fetchLabelRenders(
  id: string,
  page: number,
  size: number,
): Promise<LabelRenderPage> {
  return api<LabelRenderPage>(
    `channel/label-templates/${id}/renders?page=${page}&size=${size}`,
  );
}

/**
 * 🔴 渠道规则绑定核对 —— **服务端**结论。
 *
 * <p>这个函数是界面上唯一的「这一票能不能用这张模板」判定入口。
 * **不要**在组件里拿 `declarations` 自己比对渠道要求 —— 那正是蓝图加粗句
 * 「附加声明漏打会导致清关问题」要防的事：规则只有服务端那一份是对的。
 */
export function judgeLabelBinding(input: LabelBindingInput): Promise<LabelBindingResult> {
  return api<LabelBindingResult>('channel/label-templates/binding', 'POST', input);
}
