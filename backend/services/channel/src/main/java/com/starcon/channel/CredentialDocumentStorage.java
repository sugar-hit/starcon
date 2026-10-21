package com.starcon.channel;

import java.io.IOException;
import java.util.Optional;

/**
 * 16.4.2.2 授权凭证文件的<b>存储端口</b>。
 *
 * <p>🔴 文件本体**不进业务库**：二进制进业务库会让备份、迁移与 RLS 审计都变成搬文件的工作。
 * 业务表 {@code brand_cred_document} 里只存 key + 文件名 + MIME + 字节数 +
 * <b>sha256 摘要</b> + 上传时刻。
 *
 * <p>🔴 蓝图加粗句「<b>凭证需保留原始文件与元数据，争议时是唯一证据</b>」把**存原件**这件事
 * 落在本模块头上（16.4.2.1 只登记了文件的引用与元数据，文件本体的去向当时是空的）。
 * 所以这个端口的实现必须**真的写盘、真的读回**：核验要把文件读回来重算摘要，
 * 用内存 Map 存就永远验不出「文件不在了」与「文件被改过」，那样这条能力等于没做。
 *
 * <p>本接口存在是为了让「开发机本地目录」与「生产对象存储」可以互换
 * （与 16.2.2.2 的 {@code CertificationDocumentStorage} 同一手法）。
 */
public interface CredentialDocumentStorage {

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
   * 文件是否还在（失效检测的第一判据：键在不在）。
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
