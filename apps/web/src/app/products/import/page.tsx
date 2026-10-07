'use client';

import { ImportWizardWorkspace } from '@/components/catalog/import-wizard-workspace';

/**
 * 3.8.1.1 导入向导（L3 锚点路由 `/products/import`）。
 *
 * <p>路由与 `/products/bundles`（3.5.1 套装与组件）、`/products/bom`（3.5.2 BOM）
 * **并列**，不塞进任何一个已有页面：导入写的是商品主数据本身
 * （SPU / SKU / 属性 / 包装 / 条码），把它挂在别的工作区里会让「这一页到底管什么」变得含糊。
 *
 * <p>标题写「四步引导」而正文列了五个环节（下载模板 → 上传文件 → 字段映射 → 预校验 → 执行），
 * 这里按**正文这五个**实现 —— 少一个环节，向导就有一段路走不通，而不是少一步少一层皮。
 *
 * <p>四条必须写在页面上的纪律：
 *
 * <ul>
 *   <li><b>导入是异步任务，大文件不阻塞页面。</b>预校验与执行都是「已受理」，
 *       页面轮询真实进度 —— 提交后立刻显示「导入完成」会让用户关掉页面，
 *       而后台一个字节都还没写。
 *   <li><b>校验失败的行不落库。</b>不允许「尽力导入」产生半截数据：有错误的行会被
 *       明确列出并永不进库（3.8.1.2 蓝图原话）。
 *   <li><b>同一租户同时只允许一个同类导入任务。</b>避免并发覆盖 —— 这条由数据库的
 *       部分唯一索引裁决，不是前端禁用按钮；两个标签页同时点也拦得住。
 *   <li><b>只支持 CSV，不支持 .xlsx / .xls。</b>仓库里没有 Excel 解析依赖（Apache POI 等），
 *       要支持需先引入。当前支持 UTF-8 与 GBK，自动识别 BOM。</li>
 * </ul>
 */
export default function ImportPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">导入向导</h1>
        <p className="text-muted-foreground text-copy-13">
          批量导入商品主数据：<strong>商品 SPU / SKU / 属性值 / 包装档案 / 条码</strong>五类。
          五个环节依次是<strong>下载模板 → 上传文件 → 字段映射 → 预校验 → 执行</strong>。
          导入是<strong>异步任务</strong>，大文件不阻塞页面；提交后本页持续轮询真实进度。
          <strong>同一租户同时只允许一个同类导入任务</strong>，避免并发覆盖（由数据库约束裁决）。
          <strong>校验失败的行不落库</strong> —— 不允许「尽力导入」产生半截数据。
          当前<strong>只支持 CSV</strong>（UTF-8 / GBK 自动识别），不支持 .xlsx。
        </p>
      </header>

      <ImportWizardWorkspace />
    </div>
  );
}