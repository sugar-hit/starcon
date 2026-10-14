package com.starcon.trade;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 供应商下单失败（未接线 / 平台不可达 / 平台报错）。
 *
 * <p>刻意<b>不是</b> {@code UnsupportedOperationException}：那正是「半成品」的标志物， 而且它会绕过本模块最要紧的一条 ——
 * 调用方要靠这个异常把差异落成 {@code ORDER_FAILED} 告警。走 HTTP 异常则 {@code ApiErrorHandler} 会如实返回 503，
 * 界面上看到的是「供应商未接线」，而不是一个空指针或 500。
 */
public class SupplierOrderException extends RuntimeException {

  private final HttpStatus status;

  public SupplierOrderException(String message) {
    this(HttpStatus.SERVICE_UNAVAILABLE, message);
  }

  public SupplierOrderException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }

  /** 转成服务层的 HTTP 异常，让错误码与文案对外一致。 */
  public ResponseStatusException asResponseStatus() {
    return new ResponseStatusException(status, getMessage());
  }
}
