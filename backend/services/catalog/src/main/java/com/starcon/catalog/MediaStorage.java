package com.starcon.catalog;

import java.io.IOException;
import java.util.Optional;

/**
 * 3.4.1.1 媒体库的**二进制存储端口**。
 *
 * <p>蓝图 3.4.1.1 写死了「媒体用对象存储 + 元数据本地管理的组合，**不把二进制存业务库**」。
 * 所以业务侧（本模块的 Service / Controller / 测试）只认「一个存储键 + 一段字节」，
 * 不认文件路径：换存储后端时只有本接口的实现需要变，调用方一行都不用改。
 *
 * <p>当前实现是 {@link LocalMediaStorage}（本地文件系统），根目录由
 * {@code MEDIA_STORAGE_ROOT} 配置。上生产应当换成 S3 / OSS 实现——
 * 届时 {@link #put} / {@link #read} 映射成 PutObject / GetObject，
 * {@link #exists} / {@link #size} 映射成 HeadObject。
 *
 * <p>租户隔离由**键的前缀**承担，不靠端口自己判断：{@link MediaRules#storageKey}
 * 生成 {@code media/<tenant>/<年月>/<uuid>.<ext>}，键里带着租户段，
 * 一个租户算不出另一个租户的键。
 */
public interface MediaStorage {
  /** 写入一个对象并返回实际落盘的字节数。同一键重复写入视为覆盖（版本号不同则键也不同）。 */
  long put(String key, byte[] content) throws IOException;

  /** 读回字节。键不存在时抛 {@link java.nio.file.NoSuchFileException} 的等价异常。 */
  byte[] read(String key) throws IOException;

  /** 对象是否存在。失效检测的第一判据：键在不在。 */
  boolean exists(String key);

  /** 对象字节数；键不存在时返回空。 */
  Optional<Long> size(String key);

  /** 删除对象。键不存在时静默成功（幂等），否则重试删除会一直失败。 */
  void delete(String key) throws IOException;
}