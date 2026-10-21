'use client';

import { BrandCredentialWorkspace } from '@/components/brand-credential/brand-credential-workspace';

/**
 * 授权凭证：L3「16.4.2 品牌与授权」下的 16.4.2.2。
 *
 * <p>蓝图：「集中存储与核验授权凭证文件：真伪验证记录、文件版本、签署方信息，支持一键核验。
 * 输入：凭证上传；核验操作。输出：凭证档案 + 核验记录。
 * 关系：上游 = 16.4.2.1；下游 = 16.4.1.2。<strong>凭证需保留原始文件与元数据，争议时是唯一证据。</strong>」
 *
 * <p>🔴 与 16.4.2.1「品牌授权」是<strong>两个模块</strong>：16.4.2.1 管的是「哪一段范围、哪一段时间、
 * 谁可以用这个品牌」，它只登记授权文件的<strong>引用与元数据</strong>；本页管的是
 * <strong>凭证文件本体、文件版本、签署方信息与真伪核验记录</strong>。两边不共用表、不共用端点。
 *
 * <p>🔴 本页的「真伪」是<strong>本地重算 sha256</strong>：把受管存储里的文件本体读回来，
 * 与登记时记下的摘要逐字比对。这不是「连品牌方服务器验签」—— 那需要品牌方提供接口，
 * 不在本模块范围内；但它本地可复算、可审计，争议时拿得出来。
 */
export default function BrandCredentialPage() {
  return (
    <div className="space-y-4">
      <BrandCredentialWorkspace />
    </div>
  );
}
