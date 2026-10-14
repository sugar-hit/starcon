'use client';

import { ValidationReportWorkspace } from '@/components/catalog/validation-report-workspace';

/**
 * 3.8.1.2 校验报告（路由 `/products/import/report`）。
 *
 * <p>与 3.8.1.1 的导入向导（`/products/import`）**并列**而不是嵌进向导的某一步：
 * 报告是对某一个导入任务那一轮预校验结论的<b>反复查看与核对</b>的地方，
 * 一次核对可能来回翻几十页明细、导出两三版文件，
 * 与「下载模板 → 上传 → 映射 → 预校验 → 执行」那条一次性的引导链节奏完全不同。
 * 塞进向导会让「现在我在第几步」这个问题在报告页上无法回答。
 *
 * <p>页面上必须写清的四条纪律：
 *
 * <ul>
 *   <li><b>校验失败的行不落库。</b>不允许「尽力导入」产生半截数据 ——
 *       所以「必须修正」说的是「这些行不会进库」，不是「先导进去再改」。</li>
 *   <li><b>报告是某一轮预校验结论的快照。</b>上游预校验可重跑，重跑会覆盖结论；
 *       报告因此可能已过期，页面必须显式标出来并引导重新生成。</li>
 *   <li><b>可跳过 ≠ 可忽略。</b>可跳过的行照常落库（只是有提示），
 *       必须修正的行永不落库 —— 这条分界决定数据进不进库，所以它是数据而不是文案。</li>
 *   <li><b>报告读的是预校验结果，不是上传的文件。</b>没跑预校验的任务没有报告，
 *       页面给的是空态而不是空报告。</li>
 * </ul>
 */
export default function ImportValidationReportPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">校验报告</h1>
        <p className="text-muted-foreground text-copy-13">
          按任务查看<strong>逐行逐列</strong>的校验结果：
          <strong>必填缺失 / 格式错误 / 重复编码 / 引用不存在 / 唯一冲突</strong>。 区分
          <strong>可跳过</strong>（行照常落库）与<strong>必须修正</strong>（行永不落库）， 并可导出
          <strong>带标注的错误文件</strong>或<strong>修正版模板</strong>， 改完后重新上传。
          报告是某一轮预校验结论的快照，预校验重跑后它会被标记为<strong>已过期</strong>。
        </p>
      </header>

      <ValidationReportWorkspace />
    </div>
  );
}
