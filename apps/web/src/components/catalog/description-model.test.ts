import { describe, expect, it } from 'vitest';
import {
  CONTENT_TYPES,
  ISSUE_KINDS,
  MAX_BODY_LENGTH,
  MAX_PHRASE_LENGTH,
  MAX_SKUS_PER_RENDER,
  PHRASE_TYPES,
  bodyProblem,
  contentTypeLabel,
  csvExportUrl,
  emptyPhraseInput,
  emptyRenderInput,
  emptyTemplateInput,
  emptyTranslationInput,
  errorText,
  issueFix,
  localeProblem,
  missingSummary,
  missingTone,
  normalizeBody,
  normalizeLocale,
  phraseCodeProblem,
  phraseEmptyHint,
  phraseTextProblem,
  phraseTypeLabel,
  placeholdersOf,
  renderEmptyHint,
  renderStatusLabel,
  renderStatusTone,
  sectionEmptyHint,
  sectionStatusTone,
  sectionText,
  sectionUsable,
  sourceLabel,
  templateCodeProblem,
  templateEmptyHint,
  templateSummary,
  translationEmptyHint,
  translationPlaceholdersProblem,
  unbalancedBraceIndex,
  usedPhrasesText,
  variableBindingProblem,
  variableNameProblem,
  variablesSizeProblem,
  skusSizeProblem,
} from './description-model';

/**
 * 3.4.2.2 前端模型单测。
 *
 * <p>校验函数与服务层 `DescriptionRules` 是同一份口径的 TS 镜像：
 * 用户在页面上看到的拦下原因，应当和服务端返回的 400 原因逐字一致，
 * 否则会出现「前端放过去了、后端才拒」这种最伤信任的错位。
 */
describe('content type / source labels', () => {
  it('exposes the four blueprint content types with Chinese labels', () => {
    expect(CONTENT_TYPES.map((t) => t.value)).toEqual([
      'DESCRIPTION',
      'INSTRUCTION',
      'CAUTION',
      'SELLING_POINT',
    ]);
    expect(contentTypeLabel('DESCRIPTION')).toBe('详情描述');
    expect(contentTypeLabel('INSTRUCTION')).toBe('使用说明');
    expect(contentTypeLabel('CAUTION')).toBe('注意事项');
    expect(contentTypeLabel('SELLING_POINT')).toBe('卖点段落');
    expect(contentTypeLabel('NOPE')).toBe('NOPE');
    expect(contentTypeLabel(null)).toBe('未知');
  });

  it('labels every variable binding source', () => {
    expect(sourceLabel('SPU_BRAND')).toBe('SPU 品牌');
    expect(sourceLabel('PHRASE_SELLING')).toBe('卖点短语池');
    // 第五种绑定源：同 SPU 型号清单（使用说明里的「适配型号」那行）
    expect(sourceLabel('SKU_SIBLING_LIST')).toBe('同 SPU 型号清单');
    expect(sourceLabel(null)).toBe('未知');
  });

  it('labels every phrase type', () => {
    expect(PHRASE_TYPES.map((t) => t.value)).toEqual(['SELLING', 'FEATURE', 'SCENE', 'CAUTION']);
    expect(phraseTypeLabel('SCENE')).toBe('适用场景');
  });
});

describe('status tone', () => {
  it('treats a partially filled batch as a warning, not a failure', () => {
    expect(renderStatusTone('SUCCEEDED')).toBe('success');
    // PARTIAL 是最该被看见的状态：正文产出了，但有变量没填上。
    expect(renderStatusTone('PARTIAL')).toBe('warning');
    expect(renderStatusTone('FAILED')).toBe('danger');
    expect(renderStatusTone(undefined)).toBe('neutral');
    expect(renderStatusLabel('PARTIAL')).toBe('部分段落缺变量');
  });

  it('marks an incomplete section as a warning', () => {
    expect(sectionStatusTone('COMPLETE')).toBe('success');
    expect(sectionStatusTone('INCOMPLETE')).toBe('warning');
  });

  it('escalates the missing-variable tone as the count grows', () => {
    expect(missingTone(0)).toBe('neutral');
    expect(missingTone(2)).toBe('warning');
    expect(missingTone(9)).toBe('danger');
  });
});

describe('missing variable is a reported result, not a failure', () => {
  it('tells the operator how to fix each cause', () => {
    expect(ISSUE_KINDS.map((k) => k.value)).toEqual([
      'EMPTY_FACT',
      'EMPTY_PHRASE_POOL',
      'TRANSLATION_MISSING',
      'UNBOUND_PLACEHOLDER',
    ]);
    // 每种成因的修法必须不同，否则「报告」等于没说。
    expect(issueFix('EMPTY_FACT')).toContain('补上这个字段');
    expect(issueFix('EMPTY_PHRASE_POOL')).toContain('建至少一条');
    expect(issueFix('TRANSLATION_MISSING')).toContain('补一条');
    expect(issueFix('UNBOUND_PLACEHOLDER')).toContain('绑定');
    expect(issueFix('NOPE')).toBe('请检查模板变量绑定与短语池');
  });

  it('only lets a section be published when it is complete and issue-free', () => {
    expect(sectionUsable({ complete: true, issues: [] })).toBe('usable');
    // complete 为 true 但仍有 issue 同样不可发布：两个条件都要满足。
    expect(sectionUsable({ complete: true, issues: [{ id: '1' }] })).toBe('blocked');
    expect(sectionUsable({ complete: false, issues: [] })).toBe('blocked');
  });

  it('marks an incomplete section as not publishable in its display text', () => {
    expect(sectionText({ text: '星云 蓝牙音箱。', complete: true })).toBe('星云 蓝牙音箱。');
    // 缺变量时正文里本来就没有占位符，这里只追加「不能发布」的提醒——
    // 前端**不做**任何占位符替换。
    expect(sectionText({ text: '蓝牙音箱，。', complete: false })).toContain('不能直接发布');
  });

  it('summarizes the whole library in one sentence', () => {
    expect(missingSummary(12, 0, 0)).toBe('12 段全部已填好，可以直接被 04 刊登引用。');
    expect(missingSummary(10, 4, 9)).toContain('4 段缺变量');
    expect(missingSummary(10, 4, 9)).toContain('9 次');
    expect(missingSummary(10, 4, 9)).toContain('没有占位符残留');
  });
});

describe('body validation', () => {
  it('normalizes whitespace so one template has one form', () => {
    expect(normalizeBody('  本产品\n采用   {brand} 材质  ')).toBe('本产品 采用 {brand} 材质');
  });

  it('accepts a fixed caution with no variables at all', () => {
    // 与 3.4.2.1 相反：不强制至少一个占位符。「请勿进水」这种句子完全成立。
    expect(bodyProblem('请勿在充电时拆解本机')).toBeNull();
    expect(bodyProblem('')).toBe('模板正文不能为空');
    expect(bodyProblem('  ')).toBe('模板正文不能为空');
    expect(bodyProblem('文'.repeat(MAX_BODY_LENGTH + 1))).toContain(`最多 ${MAX_BODY_LENGTH}`);
  });

  it('rejects unbalanced braces because they swallow the following text', () => {
    expect(bodyProblem('本产品采用 {brand 材质')).toContain('花括号没有配平');
    expect(bodyProblem('本产品采用 } 材质')).toContain('花括号没有配平');
    expect(unbalancedBraceIndex('本产品采用 {brand 材质')).toBe(6);
    expect(unbalancedBraceIndex('本产品采用 } 材质')).toBe(6);
    expect(unbalancedBraceIndex('本产品采用 {brand} 材质')).toBe(-1);
  });

  it('rejects a placeholder that is not a valid variable name', () => {
    expect(bodyProblem('{1brand} 材质')).toContain('英文字母开头');
  });

  it('extracts placeholders in order and dedupes repeats', () => {
    expect(placeholdersOf('{brand} 搭配 {selling}，{brand} 更好')).toEqual(['brand', 'selling']);
    expect(placeholdersOf('没有变量的固定句子')).toEqual([]);
  });
});

describe('variable binding', () => {
  it('requires every placeholder to be declared', () => {
    const body = '{brand} 采用 {material} 材质';
    expect(variableBindingProblem(body, [{ name: 'brand' }, { name: 'material' }])).toBeNull();
    // 少声明 → 填充时会被报成「缺变量」，那正是蓝图禁止的静默占位符，所以这里要拦。
    const missing = variableBindingProblem(body, [{ name: 'brand' }]);
    expect(missing).toContain('{material}');
    expect(missing).toContain('缺变量');
  });

  it('rejects a declared variable that never appears in the body', () => {
    const dead = variableBindingProblem('{brand}', [{ name: 'brand' }, { name: 'material' }]);
    expect(dead).toContain('material');
    expect(dead).toContain('死配置');
  });

  it('treats a null variable list as no declarations', () => {
    expect(variableBindingProblem('{brand}', null)).toContain('没有声明绑定源');
  });

  it('validates variable names and count', () => {
    expect(variableNameProblem('1brand')).toContain('英文字母开头');
    expect(variableNameProblem('brand-name')).toContain('英文字母开头');
    expect(variableNameProblem('brand_2')).toBeNull();
    expect(variablesSizeProblem(16)).toBeNull();
    expect(variablesSizeProblem(17)).toContain('最多 16 个变量占位');
  });
});

describe('locale', () => {
  it('requires BCP-47 so one language has one spelling', () => {
    expect(localeProblem('zh-CN')).toBeNull();
    expect(localeProblem('ja')).toBeNull();
    // 下划线写法会让同一个语言建出两条译文，唯一键形同虚设。
    expect(localeProblem('en_US')).toContain('BCP-47');
    expect(localeProblem('中文')).toContain('BCP-47');
    expect(localeProblem('e')).toContain('BCP-47');
    expect(localeProblem('')).toContain('语言不能为空');
  });

  it('normalizes to lowercase language + uppercase region', () => {
    // 入库与查询必须同一份规范化，否则「ZH-CN」查不到「zh-CN」的行。
    expect(normalizeLocale(' zh-cn ')).toBe('zh-CN');
    expect(normalizeLocale('EN-us')).toBe('en-US');
    expect(normalizeLocale('ja')).toBe('ja');
    expect(normalizeLocale('EN')).toBe('en');
  });
});

describe('translation placeholders', () => {
  const source = '{brand} 采用 {material} 材质';

  it('accepts a translation that keeps every placeholder', () => {
    expect(translationPlaceholdersProblem(source, '{brand} uses {material}')).toBeNull();
  });

  it('rejects a translation that drops a placeholder', () => {
    // 漏掉的占位符会让那句话对所有 SKU 都一样，而变量其实没参与填充。
    const leaked = translationPlaceholdersProblem(source, '{brand} uses material');
    expect(leaked).toContain('{material}');
    expect(leaked).toContain('漏掉');
  });

  it('rejects a translation that invents or misspells a placeholder', () => {
    // AI 预填最典型的失败模式。
    expect(translationPlaceholdersProblem(source, '{brand} {material} {color}')).toContain(
      '{color}',
    );
    expect(translationPlaceholdersProblem(source, '{brnad} {material}')).toContain('{brnad}');
  });
});

describe('phrase validation', () => {
  it('accepts a long sentence but not a nested template', () => {
    expect(phraseTextProblem('双单元立体声，低音下潜到 20Hz')).toBeNull();
    expect(phraseTextProblem('')).toBe('短语内容不能为空');
    expect(phraseTextProblem('句'.repeat(MAX_PHRASE_LENGTH + 1))).toContain(
      `最多 ${MAX_PHRASE_LENGTH}`,
    );
    // 短语是直接填进正文的文本，不能再带占位符。
    expect(phraseTextProblem('采用 {material} 材质')).toContain('不能包含花括号');
  });

  it('validates phrase codes', () => {
    expect(phraseCodeProblem('')).toBe('短语编码不能为空');
    expect(phraseCodeProblem('中文')).toContain('英文字母');
    expect(phraseCodeProblem('PH-01')).toBeNull();
  });
});

describe('capacity', () => {
  it('bounds a single render run with an actionable reason', () => {
    expect(skusSizeProblem(0)).toContain('至少选择一个');
    expect(skusSizeProblem(MAX_SKUS_PER_RENDER)).toBeNull();
    expect(skusSizeProblem(MAX_SKUS_PER_RENDER + 1)).toContain(`单次最多为 ${MAX_SKUS_PER_RENDER}`);
  });
});

describe('form defaults', () => {
  it('starts a template as a zh-CN detail description with no variables', () => {
    const input = emptyTemplateInput();
    expect(input.contentType).toBe('DESCRIPTION');
    expect(input.sourceLocale).toBe('zh-CN');
    expect(input.variables).toEqual([]);
    expect(input.enabled).toBe(true);
    expect(input.version).toBe(0);
  });

  it('seeds a translation with the source body so placeholders are not lost', () => {
    // 运营改成目标语言时占位符天然在位，不会因为「手打一遍忘了打 {brand}」而漏掉。
    const input = emptyTranslationInput('{brand} 采用 {material} 材质');
    expect(input.body).toBe('{brand} 采用 {material} 材质');
    expect(input.origin).toBe('MANUAL');
    expect(input.status).toBe('DRAFT');
    expect(placeholdersOf(input.body)).toEqual(['brand', 'material']);
  });

  it('starts a phrase as a selling point', () => {
    expect(emptyPhraseInput().phraseType).toBe('SELLING');
    expect(emptyRenderInput().locale).toBe('zh-CN');
  });
});

describe('presentation', () => {
  it('summarizes a template row in one line', () => {
    expect(
      templateSummary({ contentType: 'DESCRIPTION', variables: [{ id: '1' }], locales: ['en-US'] }),
    ).toBe('详情描述 · 1 个变量 · 已配 1 种语言');
    // 没有变量的固定文本要如实说「固定文本」，而不是显示成「0 个变量」。
    expect(templateSummary({ contentType: 'CAUTION', variables: [], locales: [] })).toBe(
      '注意事项 · 固定文本 · 仅源语言',
    );
  });

  it('says "no phrase used" instead of leaving it blank', () => {
    expect(usedPhrasesText([])).toBe('未使用短语');
    expect(usedPhrasesText(null)).toBe('未使用短语');
    expect(usedPhrasesText(['卖点一', '卖点二'])).toBe('卖点一 / 卖点二');
  });

  it('distinguishes an empty library from an empty filtered result', () => {
    expect(templateEmptyHint()).toContain('四类内容');
    expect(phraseEmptyHint()).toContain('卖点短语库还是空的');
    expect(translationEmptyHint('zh-CN')).toContain('缺译文时不会悄悄退回源语言正文');
    expect(renderEmptyHint(false)).toContain('几十毫秒');
    expect(renderEmptyHint(true)).toContain('换个关键词');
    expect(sectionEmptyHint(false)).toContain('缺变量的段落也会列出来');
    expect(sectionEmptyHint(true)).toContain('换个筛选条件');
  });
});

describe('csv export url', () => {
  it('carries exactly the active filters', () => {
    const url = csvExportUrl({
      skuId: 'sku-1',
      locale: 'en-US',
      contentType: 'DESCRIPTION',
      status: 'COMPLETE',
    });
    expect(url).toBe(
      '/api/catalog/descriptions/sections/export?skuId=sku-1&locale=en-US&contentType=DESCRIPTION&status=COMPLETE',
    );
  });

  it('omits empty filters so the export matches the visible list', () => {
    const url = csvExportUrl({ skuId: null, locale: null, contentType: null, status: null });
    expect(url).toBe('/api/catalog/descriptions/sections/export?');
  });
});

describe('error text', () => {
  it('passes the server reason through and never swallows it', () => {
    expect(errorText(new Error('模板没有语言 en-US 的译文，不能用这个语言填充'))).toContain(
      '不能用这个语言填充',
    );
    expect(errorText('随便一个字符串')).toBe('请求失败，请稍后重试');
  });
});
