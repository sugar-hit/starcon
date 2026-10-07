'use client';

import { ExportWorkspace } from '@/components/catalog/export-workspace';

/**
 * 3.8.2.1 导出（L3 锚点路由 `/products/export`）。
 *
 * <p>路由与 `/products/import`（3.8.1.1 导入向导）、`/products/bom`、`/products/bundles`
 * **并列**，不塞进任何一个已有列表页：导出服务的是「所有列表页按当前条件导出」，
 * 它有自己的字段选择器与导出记录页，挂进某一个列表页会让「这一页到底管什么」变含糊。
 *
 * <p>四条必须写在页面上的纪律：
 *
 * <ul>
 *   <li><b>大文件异步导出，不阻塞页面。</b>提交后是「已受理」，页面轮询真实进度；
 *       在响应里显示「导出完成」会让用户关掉页面，而后台一个字节都还没写。</li>
 *   <li><b>导出服从数据范围。</b>行来自带数据范围策略的表，所以导出的就是你看得见的那些行；
 *       导出记录本身还保留了条件、字段、行数、时间与操作人，可追溯到人。</li>
 *   <li><b>含联系方式等敏感字段的导出需显式授权并留审计。</b>勾选敏感字段必须填写导出用途，
 *       用途与操作人、时间、条件、字段清单一并留档（16.6.1 审计未开发，本模块内自行落地）。</li>
 *   <li><b>导出文件带下载有效期。</b>过期由后端清理并不可下载，作废可立即中断下载。</li>
 * </ul>
 */
export default function ExportPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">导出</h1>
        <p className="text-muted-foreground text-copy-13">
          按列表页当前的筛选条件导出结果数据，支持<strong>字段选择</strong>与
          <strong>排序还原</strong>，大文件走<strong>异步任务</strong>。
          导出记录保留<strong>条件、字段、行数、时间与操作人</strong>，可追溯到人；
          文件带<strong>下载有效期</strong>，过期由后端清理。
          含联系方式等<strong>敏感字段的导出需显式授权</strong>并填写用途留档。
          导出<strong>服从数据范围</strong>：导出的就是你当前看得见的那些行。
        </p>
      </header>

      <ExportWorkspace />
    </div>
  );
}