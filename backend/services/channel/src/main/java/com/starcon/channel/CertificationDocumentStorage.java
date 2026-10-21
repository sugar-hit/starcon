package com.starcon.channel;

import java.io.IOException;
import java.util.Optional;

/**
 * 16.2.2.2 认证扫描件的<b>存储端口</b>。
 *
 * <p>🔴 文件本体**不进业务库**：二进制进业务库会让备份、迁移与 RLS 审计都变成搬文件的工作。
 * 业务表里只存 key + 文件名 + MIME + 字节数 + 上传时刻（{@code product_certification_document}）。
 *
 * <p>本接口存在是为了让「开发机本地目录」与「生产对象存储」可以互换（与 catalog 的
 * {@code MediaStorage} 同一手法）。实现必须**真的写盘、真的读回**：失效检测要能验出
 * 「文件确实不在了」，用内存 Map 存就永远验不出 MISSING，那样这条能力等于没做。
 */
public interface CertificationDocumentStorage {

  /**
   * 写入一份文件。
   *
   * @param key 受管存储里的键（由服务层生成，不接受调用方原样透传）
   * @param content 文件本体
   * @return 实际写入的字节数
   */
  long put(String key, byte[] content) throws IOException;

  /**
   * 读回一份文件。
   *
   * @param key 受管存储里的键
   */
  byte[] read(String key) throws IOException;

  /**
   * 文件是否还在。
   *
   * @param key 受管存储里的键
   */
  boolean exists(String key);

  /**
   * 文件大小（字节）。
   *
   * @param key 受管存储里的键
   */
  Optional<Long> size(String key);
}
