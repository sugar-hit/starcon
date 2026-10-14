import { describe, expect, it } from 'vitest';
import {
  BATCH_ACTIONS,
  BATCH_OUTCOMES,
  BATCH_STATUSES,
  LOCALE_SOURCES,
  MAX_ITEMS_PER_BATCH,
  MAX_LOCALES_PER_BATCH,
  SUGGESTION_STATUSES,
  TARGET_TYPES,
  TRANSLATION_ORIGINS,
  TRANSLATION_STATUSES,
  batchActionHint,
  batchActionLabel,
  batchLocalesSizeProblem,
  batchStatusLabel,
  batchStatusTone,
  completionOf,
  csvExportUrl,
  emptyBatchInput,
  emptyEntryEdit,
  emptyEntryInput,
  emptyLocaleInput,
  errorText,
  itemsSizeProblem,
  localeProblem,
  localeSourceLabel,
  missingRequiredLocales,
  normalizeLocale,
  originLabel,
  outcomeLabel,
  outcomeTone,
  publishableTone,
  rejectReasonProblem,
  statusHint,
  statusLabel,
  statusTone,
  suggestionStatusLabel,
  syncFromSites,
  textProblem,
} from './multilingual-model';

describe('3.4.3.1 多语言内容 · 语言标签', () => {
  it('规范化入库与查询必须是同一份口径', () => {
    // 入库走 normalizeLocale、查询也走它，否则筛选永远匹配不到，
    // 页面上表现为「明明有数据却查不出来」。
    expect(normalizeLocale('ZH-cn')).toBe('zh-CN');
    expect(normalizeLocale('EN-us')).toBe('en-US');
    expect(normalizeLocale('ja')).toBe('ja');
    expect(normalizeLocale('  ')).toBe('');
  });

  it('拒绝下划线与中文名写法', () => {
    // 下划线会让同一个语言的两条拼法各建一行，locale 唯一键形同虚设。
    expect(localeProblem('en_US')).toContain('BCP-47');
    expect(localeProblem('中文')).toContain('BCP-47');
    expect(localeProblem('e')).toContain('BCP-47');
    expect(localeProblem('')).toContain('不能为空');
    expect(localeProblem('zh-CN')).toBeNull();
    expect(localeProblem('pt-BR')).toBeNull();
  });
});

describe('3.4.3.1 多语言内容 · 三态与正文一致性', () => {
  it('待翻要求名称与描述都为空', () => {
    // 「待翻」是一等状态而不是「行不存在」：它有语言、有商品，只是没有文本。
    expect(textProblem('', '', 'PENDING')).toBeNull();
    expect(textProblem('Kaffee', '', 'PENDING')).toContain('待翻');
  });

  it('已校对要求名称非空', () => {
    expect(textProblem('Kaffee', 'Beschreibung', 'REVIEWED')).toBeNull();
    // 空名称进不了刊登，标成已校对是自欺。
    expect(textProblem('', 'Beschreibung', 'REVIEWED')).toContain('名称');
  });

  it('已翻至少要有一种文本', () => {
    expect(textProblem('Kaffee', '', 'TRANSLATED')).toBeNull();
    expect(textProblem('', '', 'TRANSLATED')).toContain('已翻');
  });

  it('商品多语言名称与描述里不许出现占位符', () => {
    // 占位符是 3.4.2.2 描述模板的机制，这里必须是已填好的成品。
    expect(textProblem('Kaffee {brand}', '', 'TRANSLATED')).toContain('花括号');
    expect(textProblem('', 'Material: {material}', 'TRANSLATED')).toContain('花括号');
  });

  it('长度上限与服务端一致', () => {
    expect(textProblem('n'.repeat(201), '', 'TRANSLATED')).toContain('200');
    expect(textProblem('n'.repeat(200), '', 'TRANSLATED')).toBeNull();
    expect(textProblem('', 'd'.repeat(8001), 'TRANSLATED')).toContain('8000');
    expect(textProblem('', 'd'.repeat(8000), 'TRANSLATED')).toBeNull();
  });
});

describe('3.4.3.1 多语言内容 · 状态标签与色调', () => {
  it('三态标签与迁移文件同口径', () => {
    expect(statusLabel('PENDING')).toBe('待翻');
    expect(statusLabel('TRANSLATED')).toBe('已翻');
    expect(statusLabel('REVIEWED')).toBe('已校对');
    // 「未登记」不是「待翻」：前者是压根没有这一行，后者是登记了但没文本。
    expect(statusLabel(null)).toBe('未登记');
    expect(statusLabel(undefined)).toBe('未登记');
  });

  it('已校对是唯一 success 的状态', () => {
    // 页面上必须一眼挑出可刊登的那一态。
    expect(statusTone('REVIEWED')).toBe('success');
    expect(statusTone('TRANSLATED')).toBe('warning');
    expect(statusTone('PENDING')).toBe('neutral');
    expect(statusTone(null)).toBe('neutral');
  });

  it('publishable 与 status 分开显示', () => {
    expect(publishableTone(true)).toBe('success');
    expect(publishableTone(false)).toBe('warning');
  });

  it('状态提示说清每一步能做什么', () => {
    expect(statusHint('REVIEWED')).toContain('刊登');
    expect(statusHint('TRANSLATED')).toContain('不可刊登');
  });
});

describe('3.4.3.1 多语言内容 · 翻译完成度', () => {
  it('没有需求语言时返回 null 而不是 0%', () => {
    // 「0% 完成」会被读成「试过了都没成功」，那是另一种误导。
    expect(completionOf([])).toBeNull();
    expect(completionOf([{ required: false, status: null }])).toBeNull();
  });

  it('只按需求语言算完成度', () => {
    const result = completionOf([
      { required: true, status: 'REVIEWED' },
      { required: true, status: 'TRANSLATED' },
      { required: true, status: 'PENDING' },
      { required: false, status: 'REVIEWED' },
    ]);
    expect(result).toEqual({ required: 3, reviewed: 1, pending: 1, missing: 1, percent: 33 });
  });

  it('全部校对完是 100%', () => {
    const result = completionOf([
      { required: true, status: 'REVIEWED' },
      { required: true, status: 'REVIEWED' },
    ]);
    expect(result?.percent).toBe(100);
    expect(result?.missing).toBe(0);
  });
});

describe('3.4.3.1 多语言内容 · 「还差哪些语言」', () => {
  const requirements = [
    { locale: 'de-DE', required: true },
    { locale: 'ja-JP', required: true },
    { locale: 'fr-FR', required: false },
  ] as never[];

  it('没有覆盖数据时列出全部需求语言', () => {
    expect(missingRequiredLocales(requirements, null)).toEqual(['de-DE', 'ja-JP']);
  });

  it('有覆盖数据时只列还不能刊登的', () => {
    // 这正是 04 刊登填充时会取到空的那几行。
    const coverage = {
      rows: [
        { locale: 'de-DE', required: true, publishable: true },
        { locale: 'ja-JP', required: true, publishable: false },
      ],
    } as never;
    expect(missingRequiredLocales(requirements, coverage)).toEqual(['ja-JP']);
  });
});

describe('3.4.3.1 多语言内容 · AI 建议处置', () => {
  it('驳回理由必填', () => {
    // 不给理由的驳回让下一轮 AI 翻译无从改进。
    expect(rejectReasonProblem('')).toContain('理由');
    expect(rejectReasonProblem('  ')).toContain('理由');
    expect(rejectReasonProblem('r'.repeat(501))).toContain('500');
    expect(rejectReasonProblem('机翻把型号 M8 也译了')).toBeNull();
  });

  it('处置状态标签覆盖三态', () => {
    expect(suggestionStatusLabel('PENDING')).toBe('待处置');
    expect(suggestionStatusLabel('ADOPTED')).toBe('已采纳');
    expect(suggestionStatusLabel('REJECTED')).toBe('已驳回');
  });
});

describe('3.4.3.1 多语言内容 · 批量操作', () => {
  it('动作都带说明，避免运营选错', () => {
    expect(BATCH_ACTIONS.map((a) => a.value)).toEqual([
      'CREATE_PENDING',
      'ADOPT_SUGGESTIONS',
      'MARK_REVIEWED',
    ]);
    for (const action of BATCH_ACTIONS) {
      expect(batchActionHint(action.value)).not.toBe('');
      expect(batchActionLabel(action.value)).not.toBe(action.value);
    }
  });

  it('单次数量上限', () => {
    expect(itemsSizeProblem(0)).toContain('至少');
    expect(itemsSizeProblem(MAX_ITEMS_PER_BATCH + 1)).toContain(String(MAX_ITEMS_PER_BATCH));
    expect(itemsSizeProblem(MAX_ITEMS_PER_BATCH)).toBeNull();
    expect(batchLocalesSizeProblem(MAX_LOCALES_PER_BATCH + 1)).toContain(
      String(MAX_LOCALES_PER_BATCH),
    );
    expect(batchLocalesSizeProblem(2)).toBeNull();
  });

  it('批次汇总色调', () => {
    expect(batchStatusTone('SUCCEEDED')).toBe('success');
    expect(batchStatusTone('PARTIAL')).toBe('warning');
    expect(batchStatusTone('FAILED')).toBe('danger');
    expect(batchStatusLabel('PARTIAL')).toBe('部分未成功');
  });

  it('逐条结果标签覆盖三值', () => {
    expect(BATCH_OUTCOMES.map((o) => o.value)).toEqual(['SUCCEEDED', 'SKIPPED', 'REJECTED']);
    expect(outcomeLabel('SKIPPED')).toBe('已跳过');
    expect(outcomeLabel('REJECTED')).toBe('未执行');
    expect(outcomeTone('REJECTED')).toBe('danger');
    expect(outcomeTone('SKIPPED')).toBe('warning');
  });
});

describe('3.4.3.1 多语言内容 · 词表与服务端一致', () => {
  it('词表逐字对齐 V22 的 CHECK', () => {
    expect(TRANSLATION_STATUSES.map((s) => s.value)).toEqual(['PENDING', 'TRANSLATED', 'REVIEWED']);
    expect(TRANSLATION_ORIGINS.map((o) => o.value)).toEqual(['MANUAL', 'AI', 'IMPORTED']);
    expect(SUGGESTION_STATUSES.map((s) => s.value)).toEqual(['PENDING', 'ADOPTED', 'REJECTED']);
    expect(LOCALE_SOURCES.map((s) => s.value)).toEqual(['MANUAL', 'SHOP_SITE']);
    expect(BATCH_STATUSES.map((s) => s.value)).toEqual(['SUCCEEDED', 'PARTIAL', 'FAILED']);
    expect(TARGET_TYPES.map((t) => t.value)).toEqual(['SPU', 'SKU']);
  });

  it('来源标签可读且能落到未知值', () => {
    expect(originLabel('AI')).toBe('AI 翻译');
    expect(originLabel('WAT')).toBe('WAT');
    expect(localeSourceLabel('SHOP_SITE')).toBe('店铺站点推导');
  });
});

describe('3.4.3.1 多语言内容 · 空表单与导出地址', () => {
  it('空表单带合法默认值', () => {
    expect(emptyLocaleInput('de-DE', '德语')).toEqual({
      locale: 'de-DE',
      localeName: '德语',
      enabled: true,
      note: '',
      version: 0,
    });
    expect(emptyEntryInput('ja-JP')).toEqual({
      locale: 'ja-JP',
      name: '',
      description: '',
      origin: 'MANUAL',
      status: 'TRANSLATED',
    });
    expect(emptyBatchInput().action).toBe('CREATE_PENDING');
  });

  it('编辑表单带回当前版本（乐观锁要靠它）', () => {
    const edit = emptyEntryEdit({
      id: 'e1',
      targetType: 'SPU',
      spuId: 's1',
      skuId: null,
      locale: 'de-DE',
      name: 'Kaffeemaschine',
      description: 'Beschreibung',
      origin: 'AI',
      status: 'TRANSLATED',
      reviewedBy: null,
      reviewedAt: null,
      version: 7,
      createdAt: '',
      updatedAt: '',
      translatedAt: null,
    });
    expect(edit.version).toBe(7);
    expect(edit.name).toBe('Kaffeemaschine');
    expect(edit.origin).toBe('AI');
  });

  it('导出地址带上当前筛选，且只读筛选不带分页', () => {
    const url = csvExportUrl({
      targetType: 'SPU',
      status: 'REVIEWED',
      locale: 'de-DE',
      spuId: null,
      skuId: null,
      keyword: ' Kaffee ',
      page: 3,
      size: 20,
    });
    expect(url).toContain('/api/catalog/translations/entries/export?');
    expect(url).toContain('targetType=SPU');
    expect(url).toContain('status=REVIEWED');
    expect(url).toContain('locale=de-DE');
    expect(url).toContain('keyword=Kaffee');
    // 导出结果与当前筛选完全一致，所以不该带分页参数。
    expect(url).not.toContain('page=');
  });

  it('站点同步是 POST，且打到 sync 端点', () => {
    // 02 渠道未接入时后端返回 409 并写明归属 —— 前端原样把那句话显示出来。
    expect(syncFromSites).toBeInstanceOf(Function);
  });
});

describe('3.4.3.1 多语言内容 · 错误文案', () => {
  it('把后端的人话原样带出来', () => {
    // 后端返回的是「仍被 8 条商品译文使用，不可删除」这种人话，
    // 前端不能替换成「操作失败」——那就等于没写那些中文校验。
    expect(errorText(new Error('这条记录刚被别人改过'))).toBe('这条记录刚被别人改过');
    expect(errorText(new Error('x'), '兜底')).toBe('x');
    expect(errorText('not-an-error', '兜底')).toBe('兜底');
  });
});
