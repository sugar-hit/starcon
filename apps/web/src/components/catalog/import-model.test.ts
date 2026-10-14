import { describe, expect, it } from 'vitest';
import type { ImportJobRow, ImportRowErrorLine, ImportTemplateDetail } from './import-model';
import {
  ACTIVE_STATUSES,
  FORMAT_HINT,
  IMPORT_MODES,
  QUOTE_BOUNDARY_HINT,
  REPORT_BOUNDARY_HINT,
  TARGET_ENTITIES,
  emptyTemplateInput,
  entityLabel,
  executeHint,
  groupErrors,
  ignoredHeaders,
  isRunning,
  isSettled,
  mappingProblem,
  modeLabel,
  outcomeLabel,
  outcomeTone,
  progressPercent,
  progressText,
  severityLabel,
  statusLabel,
  statusTone,
  templateProblem,
  toTemplateInput,
  unmappedRequiredKeys,
} from './import-model';

function job(over: Partial<ImportJobRow> = {}): ImportJobRow {
  return {
    id: 'j1',
    templateId: 't1',
    templateName: '商品导入',
    targetEntity: 'SPU',
    importMode: 'CREATE',
    fileName: 'a.csv',
    fileFormat: 'CSV',
    byteSize: 100,
    contentSha256: '0'.repeat(64),
    rowCount: 10,
    mapping: {},
    status: 'PENDING',
    validCount: 0,
    invalidCount: 0,
    appliedCount: 0,
    failedCount: 0,
    skippedCount: 0,
    error: '',
    actorId: 'u1',
    // 契约把这三列标成非空 string，但 Java 侧在「该阶段还没发生」时返回 null。
    // 这里如实写成 null 并断言它不会被当成字符串用 —— 契约与实现的这处偏差是
    // codegen 的已知口径（Jackson record 无 @Nullable 时按非空生成），
    // 页面因此不能用字符串方法直接操作这三个时刻。
    prevalidatedAt: null as unknown as string,
    startedAt: null as unknown as string,
    finishedAt: null as unknown as string,
    createdAt: '2026-10-07T00:00:00Z',
    updatedAt: '2026-10-07T00:00:00Z',
    ...over,
  };
}

function template(): ImportTemplateDetail {
  return {
    template: {
      id: 't1',
      code: 'SPU',
      name: '商品',
      note: '',
      targetEntity: 'SPU',
      enabled: true,
      columnCount: 2,
      version: 0,
      createdAt: '2026-10-07T00:00:00Z',
      updatedAt: '2026-10-07T00:00:00Z',
    },
    columns: [
      {
        fieldKey: 'spu_code',
        header: '商品编码',
        dataType: 'TEXT',
        required: true,
        matchKey: true,
        sampleValue: 'SPU-1001',
        help: '',
        ordinal: 0,
      },
      {
        fieldKey: 'spu_name',
        header: '商品名称',
        dataType: 'TEXT',
        required: true,
        matchKey: false,
        sampleValue: '蓝牙耳机',
        help: '',
        ordinal: 1,
      },
      {
        fieldKey: 'brand',
        header: '品牌',
        dataType: 'TEXT',
        required: false,
        matchKey: false,
        sampleValue: '',
        help: '',
        ordinal: 2,
      },
    ],
  };
}

function errorLine(over: Partial<ImportRowErrorLine> = {}): ImportRowErrorLine {
  return {
    id: 'e1',
    rowNo: 2,
    fieldKey: 'spu_code',
    code: 'REQUIRED',
    message: '必填列没有值',
    severity: 'ERROR',
    ...over,
  };
}

describe('3.8.1.1 导入向导 · 模型', () => {
  describe('五种导入模式各自的行为提示', () => {
    it('三个模式的文案必须互相区分（新建不覆盖 / 更新不存在报错 / 匹配有则改无则建）', () => {
      expect(IMPORT_MODES.map((m) => m.value)).toEqual(['CREATE', 'UPDATE', 'MATCH_BY_CODE']);
      const hints = IMPORT_MODES.map((m) => m.hint);
      expect(new Set(hints).size).toBe(3);
      // CREATE 必须说明「不会覆盖」—— 否则用户以为新增模式能改数据。
      expect(modeLabel('CREATE')).toBe('新增');
      expect(IMPORT_MODES[0]!.hint).toContain('不会覆盖');
      expect(IMPORT_MODES[1]!.hint).toContain('不存在就报错');
      expect(IMPORT_MODES[2]!.hint).toContain('找到了就更新');
    });

    it('未知模式原样返回而不是显示 undefined', () => {
      expect(modeLabel('NOT_A_MODE')).toBe('NOT_A_MODE');
    });
  });

  describe('目标对象不含报价', () => {
    it('报价模块（3.6.2.1）未交付，词表里不能有 QUOTE', () => {
      expect(TARGET_ENTITIES.map((e) => e.value)).toEqual([
        'SPU',
        'SKU',
        'ATTRIBUTE',
        'PACKAGE',
        'BARCODE',
      ]);
      // 与 V29 import_job.target_entity 的 CHECK 同一词表：catalog 不建报价表，
      // 否则会出现两个价格事实来源（定价服务才是价格的唯一写入所有者）。
      expect(TARGET_ENTITIES.some((e) => (e.value as string) === 'QUOTE')).toBe(false);
      expect(entityLabel('SPU')).toBe('商品 SPU');
      // 界面上必须把这条边界说出来，而不是默默少一类对象。
      expect(QUOTE_BOUNDARY_HINT).toContain('3.6.2.1');
      expect(REPORT_BOUNDARY_HINT).toContain('3.8.1.2');
    });
  });

  describe('活跃态必须与 DB 部分唯一索引的活跃态一致', () => {
    it('这四个状态占用互斥槽位', () => {
      // 与 V29 import_job_one_active_per_entity 的 WHERE 条件逐字一致：
      // 页面的「还在跑」与数据库的「占着槽位」若是两套词表，用户会看到
      // 「任务已结束」却仍然传不上去下一次导入。
      expect([...ACTIVE_STATUSES]).toEqual(['PENDING', 'PREVALIDATING', 'PREVALIDATED', 'RUNNING']);
      for (const s of ACTIVE_STATUSES) expect(isSettled(s)).toBe(false);
    });

    it('终态不再轮询', () => {
      for (const s of ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED']) {
        expect(isSettled(s)).toBe(true);
        expect(isRunning(s)).toBe(false);
      }
      expect(isRunning('PREVALIDATING')).toBe(true);
      expect(isRunning('RUNNING')).toBe(true);
    });
  });

  describe('进度：分母为 0 不能画出 NaN', () => {
    it('空文件不给除零', () => {
      expect(progressPercent(job({ rowCount: 0 }))).toBe(0);
      expect(progressText(job({ rowCount: 0, status: 'PREVALIDATING' }))).toContain('正在校验');
    });

    it('预校验按已判定行数推进', () => {
      const running = job({
        status: 'PREVALIDATING',
        rowCount: 40,
        validCount: 10,
        invalidCount: 2,
      });
      expect(progressText(running)).toBe('正在校验，已完成 12 / 40 行');
      expect(progressPercent(running)).toBe(30);
    });

    it('执行按已落库/失败/跳过推进，分母是校验通过的行数', () => {
      const running = job({
        status: 'RUNNING',
        rowCount: 40,
        validCount: 10,
        appliedCount: 4,
        failedCount: 1,
        skippedCount: 2,
      });
      expect(progressText(running)).toBe('正在导入，已完成 7 / 10 行');
      expect(progressPercent(running)).toBe(70);
    });

    it('百分比被夹在 0..100（计数与分母不一致时不许画出溢出条）', () => {
      expect(progressPercent(job({ status: 'RUNNING', validCount: 1, appliedCount: 99 }))).toBe(
        100,
      );
      expect(progressPercent(job({ status: 'PREVALIDATING', rowCount: 10 }))).toBe(0);
    });
  });

  describe('「有错误的行不落库」必须在执行前说清', () => {
    it('全部通过时给出可执行的提示', () => {
      const hint = executeHint(job({ status: 'PREVALIDATED', validCount: 10, invalidCount: 0 }));
      expect(hint).toContain('10 行全部通过校验');
      expect(hint).toContain('异步');
    });

    it('有错误行时明确说明它们不落库，而不是「尽力导入大部分」', () => {
      const hint = executeHint(job({ status: 'PREVALIDATED', validCount: 8, invalidCount: 2 }));
      expect(hint).toContain('将导入 8 行');
      expect(hint).toContain('2 行');
      expect(hint).toContain('不会落库');
      expect(hint).toContain('半截数据');
    });

    it('非预校验完成态不给执行提示（免得用户对着一个还在跑的任务点执行）', () => {
      expect(executeHint(job({ status: 'RUNNING' }))).toBe('');
      expect(executeHint(null)).toBe('');
    });
  });

  describe('字段映射：缺列在映射这一步就拦下', () => {
    it('必填列没有对应文件列时点名到列头', () => {
      const missing = unmappedRequiredKeys(template(), { spu_code: 'item_code' });
      expect(missing).toHaveLength(1);
      expect(missing[0]).toContain('商品名称');

      const problem = mappingProblem(template(), { spu_code: 'item_code' });
      expect(problem).toContain('必填列');
      expect(problem).toContain('商品名称');
    });

    it('全映射上就没有问题', () => {
      const mapping = { spu_code: '商品编码', spu_name: '商品名称' };
      expect(unmappedRequiredKeys(template(), mapping)).toEqual([]);
      expect(mappingProblem(template(), mapping)).toBeNull();
    });

    it('可选列不映射不算「缺」，但要作为「将被忽略」提示出来', () => {
      const mapping = { spu_code: '商品编码', spu_name: '商品名称' };
      // 「品牌」是模板里的可选列，运营可以不填 → 它既不算缺列，
      // 也不该被悄悄吞掉：它出现在 ignoredHeaders 里，页面上会写明「这一列不会被导入」。
      expect(unmappedRequiredKeys(template(), mapping)).toEqual([]);
      expect(mappingProblem(template(), mapping)).toBeNull();
      expect(
        ignoredHeaders(template(), ['商品编码', '商品名称', '品牌', '内部备注'], mapping),
      ).toEqual(['品牌', '内部备注']);
    });

    it('没有对应模板字段的列同样被列为忽略', () => {
      const mapping = { spu_code: '商品编码', spu_name: '商品名称', brand: '品牌' };
      expect(
        ignoredHeaders(template(), ['商品编码', '商品名称', '品牌', '内部备注'], mapping),
      ).toEqual(['内部备注']);
    });
  });

  describe('逐行错误按行分组（3.8.1.2 报告页未交付前的可读底线）', () => {
    it('同一行的多条错误归到一起并按行号升序', () => {
      const groups = groupErrors([
        errorLine({ id: 'b', rowNo: 5, fieldKey: 'net_weight_g', code: 'INVALID_TYPE' }),
        errorLine({ id: 'a', rowNo: 2, fieldKey: 'spu_code' }),
        errorLine({ id: 'c', rowNo: 5, fieldKey: 'gross_weight_g', code: 'GROSS_LT_NET' }),
      ]);
      expect(groups.map((g) => g.rowNo)).toEqual([2, 5]);
      expect(groups[1]!.items).toHaveLength(2);
      expect(groups[1]!.errorCount).toBe(2);
    });

    it('空输入不炸', () => {
      expect(groupErrors([])).toEqual([]);
    });

    it('必须修正与可跳过是两种措辞（它直接决定这行落不落库）', () => {
      expect(severityLabel('ERROR')).toBe('必须修正');
      expect(severityLabel('WARNING')).toBe('可跳过');
    });
  });

  describe('结论色调：失败必须显眼', () => {
    it('不通过与写入失败是 warning 调，成功是 success', () => {
      expect(statusTone('SUCCEEDED')).toBe('success');
      expect(statusTone('FAILED')).toBe('warning');
      expect(statusTone('PARTIAL')).toBe('warning');
      expect(outcomeTone('IMPORTED')).toBe('success');
      expect(outcomeTone('INVALID')).toBe('warning');
      expect(outcomeTone('FAILED')).toBe('warning');
      expect(outcomeLabel('SKIPPED')).toBe('已跳过');
    });
  });

  describe('模板表单', () => {
    it('空表单与非法目标对象都被拦下', () => {
      expect(templateProblem(emptyTemplateInput())).toBe('模板编码不能为空');
      const problem = templateProblem({
        code: 'X',
        name: 'Y',
        targetEntity: 'QUOTE',
        note: '',
        enabled: true,
      });
      expect(problem).toContain('目标对象不合法');
    });

    it('合法的表单可以提交，且列留空由后端套标准列', () => {
      const input = toTemplateInput({
        code: '  SPU-STD ',
        name: ' 商品导入 ',
        targetEntity: 'SPU',
        note: ' 标准 ',
        enabled: true,
      });
      expect(input.code).toBe('SPU-STD');
      expect(input.name).toBe('商品导入');
      expect(input.columns).toEqual([]);
      expect(
        templateProblem({
          code: 'SPU-STD',
          name: '商品导入',
          targetEntity: 'SPU',
          note: '',
          enabled: true,
        }),
      ).toBeNull();
    });

    it('超长编码被拦下（与后端 @Size(max=64) 一致）', () => {
      expect(
        templateProblem({
          code: 'X'.repeat(65),
          name: 'n',
          targetEntity: 'SPU',
          note: '',
          enabled: true,
        }),
      ).toContain('64');
    });
  });

  describe('状态文案', () => {
    it('未知状态原样返回', () => {
      expect(statusLabel('WHATEVER')).toBe('WHATEVER');
    });
  });

  describe('格式与边界说明必须写在界面上', () => {
    it('CSV-only 与报价未交付都要说清，不许静默省略', () => {
      expect(FORMAT_HINT).toContain('只支持 CSV');
      expect(FORMAT_HINT).toContain('POI');
    });
  });
});
