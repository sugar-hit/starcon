package com.starcon.platform;

import java.io.IOException;

/**
 * 18.1.3.1「导入导出任务」的文件**存储端口**。
 *
 * <p>蓝图输出是「任务记录 + <b>文件（带有效期）</b> + 错误报告」——
 * 文件本体是这条链的终点（下游 = 用户本地文件）。
 *
 * <p>⚠️ <b>本接口是 platform 服务自己的</b>，**不跨服务依赖** catalog / reporting 的存储类
 * （AGENTS.md：跨服务禁止直接依赖对方的代码 / 表）。语义与 reporting 的
 * {@code ReportingExportStorage} **完全同构**。
 *
 * <p>⚠️ <b>为什么不把文件内容落进 PostgreSQL</b>：一次全量导出的业务数据可能是几十 MB，
 * 落进 TOAST 会把库撑大、备份变慢、查询变慢，而且导出文件是**一次性产物**
 * （到期即删），本来就不该长期躺在事实表里。库里只留**元信息 + sha256 + 字节数**。
 *
 * <p>三条安全约束（实现方必须遵守）：
 *
 * <ul>
 *   <li>键里带<b>租户段</b> ⇒ 一个租户算不出另一个租户的键（实际隔离仍由 RLS 保证，这里是第二道）；</li>
 *   <li>实现方必须做<b>路径穿越防护</b>（键先归一化，再校验解析结果确实落在根目录内）；</li>
 *   <li><b>真的写盘、真读回</b>，不用内存 Map 替身 —— 失效检测要能验出「文件确实不在了」，
 *       用内存 Map 存就永远验不出 MISSING，那样这条能力等于没做。</li>
 * </ul>
 *
 * <p>生产换对象存储（S3 / OSS）时<b>只换实现类</b>，表结构与端点不动。
 */
public interface ImportExportStorage {

  /**
   * 写入一份文件。
   *
   * @param key 存储键（调用方给，含租户段）
   * @param content 文件字节
   * @return 写入的字节数
   */
  long put(String key, byte[] content) throws IOException;

  /** 读回一份文件。 */
  byte[] read(String key) throws IOException;

  /** 文件在不在（**真的去磁盘上判**，不查任何缓存）。 */
  boolean exists(String key);

  /** 删掉一份文件（不存在时静默成功 —— 过期清理是幂等的）。 */
  void delete(String key) throws IOException;
}
