package com.starcon.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 07 库存服务。warehouse 是库存余额、库存业务单与在途的唯一所有者。
 *
 * <p>端口 8084：identity 8081 / catalog 8082 / channel 8083 之后。
 */
@SpringBootApplication
public class WarehouseApplication {
  public static void main(String[] args) {
    SpringApplication.run(WarehouseApplication.class, args);
  }
}
