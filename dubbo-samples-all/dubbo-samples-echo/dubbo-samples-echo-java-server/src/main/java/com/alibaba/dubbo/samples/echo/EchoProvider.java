package com.alibaba.dubbo.samples.echo;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.ServiceConfig;
import com.alibaba.dubbo.samples.echo.api.EchoService;
import com.alibaba.dubbo.samples.echo.impl.EchoServiceImpl;

import java.io.IOException;

/**
 * @author yiji@apache.org
 *
 * 通过JavaSe启动项目暴露服务。
 */
public class EchoProvider {
    public static void main(String[] args) throws IOException {
        // dubbo提供的配置类
        ServiceConfig<EchoService> service = new ServiceConfig<>();     // 一个service代表一个暴露出去的Bean
        service.setApplication(new ApplicationConfig("java-echo-provider"));
        // 注册自己到注册中心
        service.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
        // 提供者和消费者之间使用的服务之Java层的标准
        service.setInterface(EchoService.class);
        // 准备将要暴露的服务实现
        service.setRef(new EchoServiceImpl());
        // 暴露服务
        service.export();
        System.out.println("java-echo-provider is running.");
        System.in.read();
    }
}
