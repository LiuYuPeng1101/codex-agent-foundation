package com.example.hanresstest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 企业订单系统 MCP Adapter 启动入口。 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrderMcpAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderMcpAdapterApplication.class, args);
    }
}
