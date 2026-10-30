package com.starcon.reporting;

import java.io.IOException;

/**
 * 13.6.2.1 导出文件的**存储端口**（蓝图 13.6.2.1 的「输出：文件下载链接」）。
 *
 * <p>⚠️ **本接口是 reporting 服务自己的**，**不跨服务依赖** catalog 的
 * {@code MediaStorage}（AGENTS.md：跨服务禁止直接依赖对方的代码 / 表）。
 * 语义与 catalog 的那个**完全同构**，照抄它的安全约束：
 *
 * <ul>
 *   <li>键里带**租户段** ⇒ 一个租户算不出另一个租户的键（实际隔离仍由 RLS 保证，这里是第二道）；</li>
 *   <li>实现方必须做**路径穿越防护**（键先归一化，再校验解析结果确实落在根目录内）；</li>
 *   <li>**真的写盘、真读回**，不用内存 Map 替身 —— 失效检测要能验出「文件确实不在了」，
 *       用内存 Map 存就永远验不出 MISSING，那样这条能力等于没做。</li>
 * </ul>
 *
 * <p>生产换对象存储（S3 / OSS）时**只换实现类**，表结构与端点不动。
 */
public interface ReportingExportStorage {

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
