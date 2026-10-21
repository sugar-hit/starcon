'use client';

import { ProductCertificationWorkspace } from '@/components/product-certification/product-certification-workspace';

/**
 * 认证与资质：L3「16.2.2 商品合规」下的 16.2.2.2。
 *
 * <p>蓝图：「管理产品认证与资质（CE、FCC、RoHS、PSE、FDA、CCC 等）：认证编号、有效期、适用 SKU、
 * 对应类目要求，支持上传扫描件与到期提醒。输入：认证录入；文件上传；有效期。输出：认证档案 +
 * 到期提醒 + 覆盖情况统计（哪些 SKU 缺认证）。关系：上游 = 6.1.2.3 供应商资质；下游 = 16.2.2.1 预检、
 * 4 刊登。<strong>认证缺失是 listing 被抑制与下架的高频原因</strong>，且属于"上架时不管、出事才补"的典型滞后项。」
 *
 * <p>🔴 本页与 16.2.2.1「合规预检」同域但**不同页**：那边在 {@code /compliance/precheck}
 * （体检报告：分维度得分 + 问题清单 + 整改建议），本页在 {@code /compliance/certifications}
 * （认证档案 + 到期提醒 + 覆盖情况统计）。两边不共用表、不共用端点、不共用页面。
 *
 * <p>🔴 与 6.1.2.3「供应商资质」是**两件事**：那边是供应商侧的资质（营业执照 / 授权书），
 * 在 {@code /supply/qualifications}；本页是**产品侧**的认证（CE / FCC / RoHS）。
 */
export default function ProductCertificationPage() {
  return (
    <div className="space-y-4">
      <ProductCertificationWorkspace />
    </div>
  );
}
