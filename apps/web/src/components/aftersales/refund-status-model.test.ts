import { describe, expect, it } from 'vitest';
import {
  actionButton,
  attemptKindLabels,
  blockingText,
  buttonVariant,
  discrepancyKindLabels,
  discrepancyPair,
  failureText,
  formatAmount,
  formatTime,
  platformMethodLabel,
  platformMethodLabels,
  platformSyncLabel,
  platformSyncLabels,
  refundListQuery,
  reportOutcomeLabels,
  reportSourceLabels,
  resolutionLabels,
  stateBadgeClass,
  stateTone,
  transactionText,
  unavailableCell,
  unknownWarning,
  verdictLabels,
  verdictTone,
} from './refund-status-model';

/**
 * 11.3.1.2 退款状态前端模型。
 *
 * <p>这些断言守的是**界面纪律**，不是渲染细节。本模块是整仓里**最容易造成资损**的一层
 * （蓝图加粗句：「外部退款超时不等于失败，重复发起退款会造成实质资损」），所以断言重心
 * 压在「界面会不会诱导人点错按钮 / 显示错状态」上：
 *
 * <ul>
 *   <li><b>UNKNOWN 必须是与 FAILED、SUCCEEDED 都不同的独立语气</b>，且排序权重最靠前 ——
 *       回落成 danger 诱导重试（资损），回落成 success 会让一笔可能已付钱的退款显示成已完成。</li>
 *   <li><b>VERIFY 与 RETRY 的按钮形态必须不同</b>：「先查证交易」绝不能长得像「再发一次」。</li>
 *   <li><b>存在 UNKNOWN 时固定打出风险提示</b>，且 count≤0 时不打扰（不能对每个租户都喊狼来了）。</li>
 *   <li><b>金额不跨币种相加</b>；缺值显示「—」而不是 0。</li>
 *   <li><b>不可得维度显示「—」并带归属模块与原因</b>，不是空白。</li>
 *   <li><b>未知状态码不得回落成「正常」</b>。</li>
 * </ul>
 */

// ================================================================ 筛选 / 分页：查询串

describe('refundListQuery 筛选与分页', () => {
  it('无参数时返回空串，不产生裸问号', () => {
    expect(refundListQuery({})).toBe('');
    expect(refundListQuery({ page: 0, size: 20 })).toBe('?page=0&size=20');
  });

  it('空串与纯空白视为未设置，不会把 ?executionState= 发给后端', () => {
    expect(refundListQuery({ executionState: '', platformSyncState: '   ', currency: '' })).toBe('');
  });

  it('值两侧空白先 trim 再进 query', () => {
    expect(refundListQuery({ orderNo: '  MO-1  ' })).toBe('?orderNo=MO-1');
  });

  it('page=0 是真实取值，不能被当成「未设置」丢掉（翻回第一页会失效）', () => {
    expect(refundListQuery({ page: 0 })).toBe('?page=0');
    expect(refundListQuery({ page: 3 })).toBe('?page=3');
  });

  it('多维筛选全部带上，顺序跟模型里的 put 顺序一致（query 顺序无语义，但稳定才能被断言）', () => {
    const q = refundListQuery({
      executionState: 'UNKNOWN',
      currency: 'USD',
      onlyNeedsAttention: 'true',
      minAmount: '10',
      maxAmount: '90',
      sort: 'ATTENTION_FIRST',
      page: 2,
      size: 20,
    });
    expect(q).toBe(
      '?executionState=UNKNOWN&currency=USD&minAmount=10&maxAmount=90'
      + '&onlyNeedsAttention=true&sort=ATTENTION_FIRST&page=2&size=20',
    );
  });

  it('onlyNeedsAttention=false 也要发出去（关掉筛选与没设是两回事）', () => {
    expect(refundListQuery({ onlyNeedsAttention: 'false' })).toBe('?onlyNeedsAttention=false');
  });

  it('订单号按 URL 编码，不把 & 之类的字符当成分隔符', () => {
    expect(refundListQuery({ orderNo: 'MO&size=999' })).toBe('?orderNo=MO%26size%3D999');
  });
});

// ================================================================ 状态映射：六态语气

describe('stateTone 六态映射', () => {
  it('UNKNOWN 是独立的 warning 语气，不复用 FAILED 的 danger 也不复用 SUCCEEDED 的 success', () => {
    expect(stateTone('UNKNOWN').tone).toBe('warning');
    expect(stateTone('UNKNOWN').tone).not.toBe(stateTone('FAILED').tone);
    expect(stateTone('UNKNOWN').tone).not.toBe(stateTone('SUCCEEDED').tone);
  });

  it('UNKNOWN 的标签必须带上「超时不等于失败」这层意思', () => {
    expect(stateTone('UNKNOWN').label).toContain('超时不等于失败');
  });

  it('未知状态码不回落成「正常」——回落中性可以，回落 success/danger 才是掩盖', () => {
    const unknown = stateTone('SOMETHING_NEW');
    expect(unknown.tone).toBe('neutral');
    expect(unknown.label).toBe('未知状态');
    expect(['success', 'danger']).not.toContain(unknown.tone);
  });

  it('空值与 null 走同一条未知分支，不抛异常', () => {
    expect(stateTone(null).tone).toBe('neutral');
    expect(stateTone(undefined).tone).toBe('neutral');
    expect(stateTone('').label).toBe('未知状态');
  });

  it('排序权重：未知最前，其次失败，再次差异 —— 页面默认「待处置优先」靠它', () => {
    const weights = [
      stateTone('UNKNOWN').weight,
      stateTone('FAILED').weight,
      stateTone('DISCREPANCY').weight,
      stateTone('IN_FLIGHT').weight,
      stateTone('SUCCEEDED').weight,
      stateTone('NOT_INITIATED').weight,
    ];
    expect(weights).toEqual([...weights].sort((a, b) => a - b));
    expect(stateTone('UNKNOWN').weight).toBeLessThan(stateTone('FAILED').weight);
    expect(stateTone('SOMETHING_NEW').weight).toBeGreaterThan(stateTone('NOT_INITIATED').weight);
  });

  it('stateBadgeClass 与 tone 一致，类名不靠手拼', () => {
    expect(stateBadgeClass('FAILED')).toBe(`tone-${stateTone('FAILED').tone}`);
    expect(stateBadgeClass('UNKNOWN')).not.toBe(stateBadgeClass('FAILED'));
  });
});

// ================================================================ 建议动作：按钮形态（资损防线）

describe('actionButton 与 buttonVariant 建议动作', () => {
  it('VERIFY 与 RETRY 的按钮形态必须不同：误点一次就是重复退款', () => {
    const verify = actionButton('VERIFY');
    const retry = actionButton('RETRY');
    expect(verify.variant).not.toBe(retry.variant);
    expect(buttonVariant(verify.variant)).not.toBe(buttonVariant(retry.variant));
  });

  it('VERIFY 的文案是「先查证交易」，不是任何含「重试/再发」的措辞', () => {
    expect(actionButton('VERIFY').label).toBe('先查证交易');
    expect(actionButton('VERIFY').label).not.toMatch(/重试|再发|重新/);
  });

  it('AWAIT（等结果）是禁用形态：等待期不许出现可点的重发按钮', () => {
    const await_ = actionButton('AWAIT');
    expect(await_.variant).toBe('disabled');
    expect(await_.show).toBe(true);
  });

  it('NONE 与未知动作都不展示按钮', () => {
    expect(actionButton('NONE').show).toBe(false);
    expect(actionButton('WHAT').show).toBe(false);
    expect(actionButton(null).show).toBe(false);
  });

  it('每个语义变体都能翻译成 Button 真实存在的 token', () => {
    const allowed = ['brand', 'outline', 'ghost', 'destructive'];
    for (const action of ['DISPATCH', 'VERIFY', 'RETRY', 'MANUAL', 'AWAIT', 'NONE', 'ZZZ']) {
      const v = buttonVariant(actionButton(action).variant);
      expect(allowed).toContain(v);
    }
  });

  it('disabled 语义翻译成 ghost（真正的禁用靠 disabled 属性，不靠画一个假的禁用按钮）', () => {
    expect(buttonVariant('disabled')).toBe('ghost');
    expect(buttonVariant('ghost')).toBe('ghost');
  });
});

// ================================================================ 风险提示

describe('unknownWarning 未知态提示', () => {
  it('没有未知态时不打扰（不是每页都喊狼来了）', () => {
    expect(unknownWarning(0)).toBe('');
    expect(unknownWarning(-1)).toBe('');
  });

  it('有未知态时把「超时不等于失败」与「重复发起=资损」写进界面', () => {
    const w = unknownWarning(3);
    expect(w).toContain('3');
    expect(w).toContain('超时不等于失败');
    expect(w).toContain('查证');
  });
});

// ================================================================ 阻断说明

describe('blockingText 不可直接重试的理由', () => {
  it('有 blocking 时带上前缀，运营才知道按钮为什么是灰的', () => {
    expect(blockingText({ actionBlocking: '外部超时，未查证交易' }))
      .toBe('不能直接重试：外部超时，未查证交易');
  });

  it('只有 actionReason 时改用「判定依据」，不是硬套「不能直接重试」', () => {
    expect(blockingText({ actionReason: '查证结论为确认未付出' }))
      .toBe('判定依据：查证结论为确认未付出');
  });

  it('blocking 优先于 reason', () => {
    expect(blockingText({ actionBlocking: 'B', actionReason: 'R' })).toBe('不能直接重试：B');
  });

  it('两者都没有时返回空串，界面上不显示这一行（不是显示「无」）', () => {
    expect(blockingText({})).toBe('');
    expect(blockingText({ actionBlocking: '  ', actionReason: null })).toBe('');
  });
});

// ================================================================ 查证结论

describe('verdictTone 查证结论语气', () => {
  it('「查不出」与「查出已付出」必须不同 —— 两者对重试的结论正相反', () => {
    expect(verdictTone('INCONCLUSIVE')).not.toBe(verdictTone('CONFIRMED_SETTLED'));
    expect(verdictTone('INCONCLUSIVE')).toBe('warning');
    expect(verdictTone('CONFIRMED_NOT_SETTLED')).toBe('success');
  });

  it('未知结论回落中性，不回落 success（否则会显示成「可以重试」）', () => {
    expect(verdictTone('SOMETHING')).toBe('neutral');
    expect(verdictTone(null)).toBe('neutral');
  });

  it('三个已知结论都有人话，且「已付出」那条明确写着禁止重试', () => {
    expect(verdictLabels.CONFIRMED_NOT_SETTLED).toContain('可重试');
    expect(verdictLabels.CONFIRMED_SETTLED).toContain('禁止重试');
    expect(verdictLabels.INCONCLUSIVE).toContain('禁止重试');
  });
});

// ================================================================ 金额

describe('formatAmount 金额展示', () => {
  it('缺值显示「—」，绝不显示 0（0 会被读成「退了 0 元」）', () => {
    expect(formatAmount(null)).toBe('—');
    expect(formatAmount(undefined)).toBe('—');
    expect(formatAmount(null, 'USD')).toBe('—');
  });

  it('0 是真实金额，必须显示成 0.0000 而不是「—」', () => {
    expect(formatAmount(0, 'USD')).toBe('0.0000 USD');
    expect(formatAmount(0)).toBe('0.0000');
  });

  it('固定四位小数，不做浮点运算（金额全程 BigDecimal）', () => {
    expect(formatAmount(100, 'USD')).toBe('100.0000 USD');
    expect(formatAmount(99.5, 'EUR')).toBe('99.5000 EUR');
  });
});

describe('discrepancyPair 差异两边对比', () => {
  it('两边都有时分别报出方向，合成净额会把差异抹成 0', () => {
    expect(discrepancyPair({
      systemAmount: 100,
      systemCurrency: 'USD',
      platformAmount: 90,
      platformCurrency: 'USD',
    })).toBe('系统 100.0000 USD / 平台 90.0000 USD');
  });

  it('两边都没有时说明是存在性差异', () => {
    expect(discrepancyPair({})).toBe('存在性差异（一边有、一边无）');
    expect(discrepancyPair({ systemAmount: null, platformAmount: null })).toBe('存在性差异（一边有、一边无）');
  });

  it('只有一边有时不谎称两边都有', () => {
    expect(discrepancyPair({ systemAmount: 100, systemCurrency: 'USD' }))
      .toBe('系统 100.0000 USD / 平台 —');
  });
});

// ================================================================ 文本兜底

describe('文本兜底：留空要说明，不要留白', () => {
  it('外部交易号缺失时说明「尚未拿到」，不是空串也不是「—」（那会被读成没有交易）', () => {
    expect(transactionText(null)).toBe('尚未拿到外部交易号');
    expect(transactionText('  ')).toBe('尚未拿到外部交易号');
    expect(transactionText('TXN-1')).toBe('TXN-1');
  });

  it('时间缺失或不可解析都显示「—」', () => {
    expect(formatTime(null)).toBe('—');
    expect(formatTime('not-a-date')).toBe('—');
  });

  it('时间按本地时刻格式化到分钟', () => {
    expect(formatTime('2026-03-01T08:05:00+08:00')).toMatch(/^2026-03-01 \d{2}:\d{2}$/);
  });

  it('无失败原因时明说「无失败信息」，不留白', () => {
    expect(failureText({})).toBe('无失败信息');
    expect(failureText({ failureReason: '  ' })).toBe('无失败信息');
  });

  it('失败原因带上码与定性，不确定与明确要分得开', () => {
    expect(failureText({ failureCode: 'GATEWAY_TIMEOUT', failureClass: 'INDETERMINATE', failureReason: '网关超时' }))
      .toBe('[GATEWAY_TIMEOUT·不确定] 网关超时');
    expect(failureText({ failureCode: 'X', failureClass: 'DEFINITIVE', failureReason: 'R' }))
      .toBe('[X·明确] R');
    expect(failureText({ failureCode: 'X', failureReason: 'R' })).toBe('[X] R');
  });
});

// ================================================================ 不可得维度

describe('unavailableCell 不可得维度', () => {
  it('有值时原样显示，不加解释性 title', () => {
    expect(unavailableCell('ACME', { label: '店铺', ownerModule: '02', reason: '未交付' }))
      .toEqual({ text: 'ACME', title: '' });
  });

  it('缺失时显示「—」并把归属模块与原因写进 title（不是空白单元格）', () => {
    const c = unavailableCell(null, { label: '店铺', ownerModule: '11.3.1.2', reason: '跨服务不可读' });
    expect(c.text).toBe('—');
    expect(c.title).toContain('11.3.1.2');
    expect(c.title).toContain('跨服务不可读');
  });

  it('没有维度说明时也给一句可解释的兜底，不返回空 title', () => {
    expect(unavailableCell(undefined).title).not.toBe('');
    expect(unavailableCell('').text).toBe('—');
  });
});

// ================================================================ 词表兜底

describe('词表：未知码原样透出，不回落成别的意思', () => {
  it('已知码给中文，未知码原样透出而不是显示 undefined', () => {
    expect(platformMethodLabel('ORIGINAL')).toBe('原路退回');
    expect(platformMethodLabel('SOMETHING')).toBe('SOMETHING');
    expect(platformMethodLabel(null)).toBe('—');
    expect(platformSyncLabel('SYNCED')).toBe('平台已同步');
    expect(platformSyncLabel('ZZZ')).toBe('ZZZ');
  });

  it('三处平台退款方式、四处平台同步态都有中文', () => {
    expect(Object.keys(platformMethodLabels)).toHaveLength(3);
    expect(Object.keys(platformSyncLabels)).toHaveLength(4);
    for (const v of Object.values(platformMethodLabels)) expect(v).not.toBe('');
  });

  it('TIMEOUT 的人话是「外部超时」，不是「失败」', () => {
    expect(reportOutcomeLabels.TIMEOUT).toBe('外部超时');
    expect(reportOutcomeLabels.FAILED).toBe('明确失败');
    expect(reportOutcomeLabels.TIMEOUT).not.toBe(reportOutcomeLabels.FAILED);
  });

  it('12 财务回报 / 平台查询 / 人工登记三来源都能说明出处', () => {
    expect(reportSourceLabels.FINANCE_MODULE_12).toContain('12');
    expect(reportSourceLabels.PLATFORM_SYNC).toContain('平台');
    expect(reportSourceLabels.MANUAL_OPERATOR).toContain('人工');
  });

  it('四类差异与三种裁定态齐全', () => {
    expect(Object.keys(discrepancyKindLabels)).toHaveLength(4);
    expect(Object.keys(resolutionLabels)).toHaveLength(3);
    expect(resolutionLabels.OPEN).toBe('待裁定');
  });

  it('重试派发与首次派发在词表上分得开（查证后的重试不是第一次）', () => {
    expect(attemptKindLabels.INITIAL).not.toBe(attemptKindLabels.RETRY);
    expect(attemptKindLabels.RETRY).toContain('查证');
  });
});
