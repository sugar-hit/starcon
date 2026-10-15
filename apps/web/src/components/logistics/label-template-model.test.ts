import { describe, expect, it } from 'vitest';
import {
  batteryTypeLabel,
  blankBatteryMark,
  blankDeclaration,
  blankElement,
  declarationKindLabel,
  elementKindLabel,
  EMPTY_LABEL_FILTERS,
  EMPTY_LABEL_FORM,
  findingTone,
  labelFormToInput,
  readinessMeta,
  sampleValuesFor,
  symbologyLabel,
  templateStatusLabel,
  toLabelForm,
  triggerSourceLabel,
  validateLabelForm,
  verdictMeta,
} from './label-template-model';
import type { LabelElement, LabelTemplateRow } from './label-template-model';

/**
 * 10.2.2.2 面单模板前端模型。
 *
 * <p>本文件钉的是**页面最容易做错的那几处**，不是渲染快照：
 *
 * <ul>
 *   <li>🔴 NO_CHANNEL 与其它三档**不同色**：它不是「模板没排好」，而是「你绑的渠道停了」——
 *       混进同一个红色里，现场会去反复调版式，而真正的原因在另一页；
 *   <li>本地校验**不做**「渠道要求是否满足」的判断（那是服务端 /binding 的职责，
 *       页面自己判一遍就又成了第二份规则），只挡结构性错误；
 *   <li>提交时尺寸**原样**传给后端（页面不做毫米→点换算）；
 *   <li>填表失败要能指出**是哪条声明指向哪个打印位**，而不是「保存失败」。
 * </ul>
 */

function row(overrides: Partial<LabelTemplateRow> = {}): LabelTemplateRow {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    channelId: '22222222-2222-2222-2222-222222222222',
    channelCode: 'SFT-DE',
    channelName: '顺丰德国',
    carrierName: '顺丰速运',
    carrierCode: 'SF',
    channelReadiness: 'READY',
    channelStatus: 'ACTIVE',
    templateCode: 'SFT-100-70',
    templateName: '顺丰 100×70',
    countryCode: 'DE',
    languageCode: 'de-DE',
    status: 'DRAFT',
    paperWidthMm: 100,
    paperHeightMm: 150,
    dpi: 203,
    orientation: 'PORTRAIT',
    originOffsetXMm: 0,
    originOffsetYMm: 0,
    primaryBarcodeSymbology: 'CODE128_B',
    primaryBarcodeHeightMm: 12,
    humanReadable: true,
    elements: [],
    declarations: [],
    batteryMarks: [],
    readiness: 'READY',
    readinessMessage: '本地齐备：可进入 ACTIVE',
    effectiveReadiness: 'READY',
    effectiveReadinessMessage: '本地齐备：可进入 ACTIVE',
    activatable: true,
    note: '',
    version: 3,
    createdAt: '2026-10-15T00:00:00Z',
    updatedAt: '2026-10-15T00:00:00Z',
    ...overrides,
  };
}

function element(overrides: Partial<LabelElement> = {}): LabelElement {
  return {
    elementKey: 'SHIP_TO',
    elementKind: 'SHIP_TO_ADDRESS',
    symbology: null,
    xMm: 4,
    yMm: 4,
    wMm: 92,
    hMm: 30,
    fontPt: 12,
    rotation: 0,
    mandatory: true,
    sequence: 0,
    note: '',
    ...overrides,
  };
}

describe('10.2.2.2 判定分色', () => {
  it('三档判定与三档可用性分色互不重叠', () => {
    expect(verdictMeta('PASS').variant).toBe('success');
    expect(verdictMeta('REVIEW').variant).toBe('warning');
    expect(verdictMeta('BLOCK').variant).toBe('danger');

    expect(readinessMeta('READY').variant).toBe('success');
    expect(readinessMeta('NO_LAYOUT').variant).toBe('neutral');
    expect(readinessMeta('NO_BARCODE').variant).toBe('danger');
    expect(readinessMeta('DECLARATION_GAP').variant).toBe('danger');
  });

  it('🔴 NO_CHANNEL 不与「模板没排好」同一个色 —— 它要去改渠道，不是改版式', () => {
    expect(readinessMeta('NO_CHANNEL').variant).toBe('warning');
    expect(readinessMeta('NO_CHANNEL').label).toContain('渠道');
    // 关键是**不等于** danger：现场看到红色会去调版式，而真正的原因在另一页
    expect(readinessMeta('NO_CHANNEL').variant).not.toBe(readinessMeta('DECLARATION_GAP').variant);
  });

  it('findings 的 severity 只分两档，且与 verdict 三档不是同一套东西', () => {
    expect(findingTone('BLOCK').variant).toBe('danger');
    expect(findingTone('REVIEW').variant).toBe('warning');
  });
});

describe('10.2.2.2 字典', () => {
  it('枚举标签有中文，不是把 code 直接甩给人看', () => {
    expect(symbologyLabel('CODE128_B')).toBe('Code 128 B');
    expect(symbologyLabel('BATTERY' as never)).not.toBe('');
    expect(declarationKindLabel('LIQUID')).toBe('液体');
    expect(elementKindLabel('WAYBILL_BARCODE')).toBe('运单条码');
    expect(batteryTypeLabel('LITHIUM_ION')).toBe('锂离子');
    expect(templateStatusLabel('ACTIVE')).toBe('启用中');
    expect(triggerSourceLabel('ACTIVATION')).toBe('启用前渲染');
    // 未在字典里的 code 原样返回而不是 blank
    expect(symbologyLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('10.2.2.2 表单往返', () => {
  it('详情 → 表单 → 提交载荷 不丢字段，也不做单位换算', () => {
    const source = row({
      elements: [
        element(),
        element({
          elementKey: 'WB_BARCODE',
          elementKind: 'WAYBILL_BARCODE',
          symbology: 'CODE128_B',
          xMm: 4,
          yMm: 38,
          wMm: 92,
          hMm: 20,
          fontPt: null,
          sequence: 1,
        }),
      ],
    });
    const form = toLabelForm(source);
    expect(form.templateId).toBe(source.id);
    expect(form.version).toBe(3);
    // 尺寸原样保留 —— 页面不做 mm→dot 换算（那是后端 LabelRenderEngine 的单一实现）
    expect(form.paperWidthMm).toBe('100');

    // 🔴 模板表刻意**没有** label_format 列：预选 PDF/HTML 而渲染器只出 ZPL，
    //    等于给现场一个会骗人的下拉。格式记在渲染产物上（记录实际产出）。
    expect(form).not.toHaveProperty('labelFormat');
    expect(labelFormToInput(form)).not.toHaveProperty('labelFormat');

    const input = labelFormToInput(form);
    expect(input.templateId).toBe(source.id);
    expect(input.channelId).toBe(source.channelId);
    expect(input.paperWidthMm).toBe(100);
    expect(input.countryCode).toBe('DE');
    expect(input.elements).toHaveLength(2);
    expect(input.elements[1]?.symbology).toBe('CODE128_B');
  });

  it('新建时空渠道提交为 null，而不是空串', () => {
    const input = labelFormToInput({ ...EMPTY_LABEL_FORM, templateCode: 'X', templateName: 'X' });
    expect(input.templateId).toBeNull();
    expect(input.channelId).toBeNull();
    expect(input.version).toBe(0);
  });
});

describe('10.2.2.2 本地校验只挡结构性错误', () => {
  it('必填与纸张尺寸', () => {
    const errors = validateLabelForm({ ...EMPTY_LABEL_FORM, paperWidthMm: '0' });
    expect(errors).toContain('模板代码不能为空');
    expect(errors).toContain('模板名称不能为空');
    expect(errors).toContain('新建必须指定所属渠道');
    expect(errors).toContain('纸张宽度必须大于 0mm');
  });

  it('国家必须是两位大写字母', () => {
    const errors = validateLabelForm({
      ...EMPTY_LABEL_FORM,
      templateCode: 'X',
      templateName: 'X',
      channelId: 'c',
      countryCode: 'deu',
    });
    expect(errors.some((message) => message.includes('两位大写'))).toBe(true);
  });

  it('🔴 声明指向不存在的打印位要指名道姓，而不是「保存失败」', () => {
    const errors = validateLabelForm({
      ...EMPTY_LABEL_FORM,
      templateCode: 'X',
      templateName: 'X',
      channelId: 'c',
      elements: [element()],
      declarations: [
        {
          declarationKind: 'LIQUID',
          declarationCode: '',
          displayText: 'Liquid',
          elementKey: 'GHOST',
          mandatory: true,
          note: '',
        },
      ],
    });
    expect(errors).toContain(
      '附加声明「液体」指向的打印位「GHOST」不存在：漏打会导致清关问题',
    );
  });

  it('既没代码也没文字的声明等于没声明，当场拒', () => {
    const errors = validateLabelForm({
      ...EMPTY_LABEL_FORM,
      templateCode: 'X',
      templateName: 'X',
      channelId: 'c',
      elements: [element()],
      declarations: [
        {
          declarationKind: 'BATTERY',
          declarationCode: '',
          displayText: '  ',
          elementKey: 'SHIP_TO',
          mandatory: true,
          note: '',
        },
      ],
    });
    expect(errors.some((message) => message.includes('等于没声明'))).toBe(true);
  });

  it('越界与负坐标各给一句人话', () => {
    const overflow = validateLabelForm({
      ...EMPTY_LABEL_FORM,
      templateCode: 'X',
      templateName: 'X',
      channelId: 'c',
      elements: [element({ yMm: 144, hMm: 20 })],
    });
    expect(overflow.some((message) => message.includes('越出纸张'))).toBe(true);

    const negative = validateLabelForm({
      ...EMPTY_LABEL_FORM,
      templateCode: 'X',
      templateName: 'X',
      channelId: 'c',
      elements: [element({ xMm: -2 })],
    });
    expect(negative.some((message) => message.includes('坐标为负'))).toBe(true);
  });

  it('打印位编码重复要报错（同一位置只能有一个元素）', () => {
    const errors = validateLabelForm({
      ...EMPTY_LABEL_FORM,
      templateCode: 'X',
      templateName: 'X',
      channelId: 'c',
      elements: [element(), element()],
    });
    expect(errors.some((message) => message.includes('重复'))).toBe(true);
  });

  it('🔴 本地校验**不**判「渠道要求是否满足」—— 那是服务端 /binding 的职责', () => {
    // 一张没有任何声明的模板在本地校验里是**干净的**：
    // 它没说自己违规，只是没配声明；合不合规由服务端对着渠道限制判。
    const errors = validateLabelForm({
      ...EMPTY_LABEL_FORM,
      templateCode: 'X',
      templateName: 'X',
      channelId: 'c',
      elements: [element()],
      declarations: [],
      batteryMarks: [],
    });
    expect(errors).toEqual([]);
  });

  it('配置齐了就是零错误', () => {
    const errors = validateLabelForm({
      ...EMPTY_LABEL_FORM,
      templateCode: 'SFT-100-70',
      templateName: '顺丰 100×70',
      channelId: 'c',
      elements: [element()],
      declarations: [
        {
          declarationKind: 'BATTERY',
          declarationCode: 'UN3481',
          displayText: 'Lithium',
          elementKey: 'SHIP_TO',
          mandatory: true,
          note: '',
        },
      ],
      batteryMarks: [
        {
          batteryType: 'LITHIUM_ION',
          wattHours: 12,
          batteryWeightKg: 0.1,
          un38_3Attested: true,
          markingSymbol: 'LI-ION',
          elementKey: 'SHIP_TO',
          note: '',
        },
      ],
    });
    expect(errors).toEqual([]);
  });
});

describe('10.2.2.2 预览样例与空行', () => {
  it('条码位自动给一个可编码的样例值（Code39 编不进中文）', () => {
    const values = sampleValuesFor([
      element(),
      element({ elementKey: 'WB', elementKind: 'WAYBILL_BARCODE', symbology: 'CODE128_B' }),
      element({ elementKey: 'QR', elementKind: 'QR_CODE', symbology: 'QR' }),
    ]);
    expect(values.map((value) => value.elementKey)).toEqual(['SHIP_TO', 'WB', 'QR']);
    expect(values[1]?.value).toBe('DE1234567890123');
    expect(values[2]?.value).toBe('DE1234567890123');
  });

  it('空行有安全的默认值（新加的一行不会是三个空串）', () => {
    expect(blankElement(3).sequence).toBe(3);
    expect(blankElement(3).wMm).toBeGreaterThan(0);
    expect(blankDeclaration().mandatory).toBe(true);
    expect(blankBatteryMark().batteryType).toBe('LITHIUM_ION');
    expect(blankBatteryMark().wattHours).toBeNull();
  });

  it('筛选默认值是「全部」而不是预选某一档', () => {
    expect(EMPTY_LABEL_FILTERS).toEqual({
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
    });
  });
});
