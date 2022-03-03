package com.books.dubbo.demo.provider;

import com.books.dubbo.demo.api.GreetingService;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.rpc.RpcContext;

public class APiConsumerInJvm {

    static public void exportService() {
        ServiceConfig<GreetingService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setApplication(new ApplicationConfig("first-dubbo-provider"));
        RegistryConfig registryConfig = new RegistryConfig("zookeeper://127.0.0.1:2181");
        serviceConfig.setRegistry(registryConfig);
        serviceConfig.setInterface(GreetingService.class);
        serviceConfig.setRef(new GreetingServiceImpl());
        serviceConfig.setVersion("1.0.0");
        serviceConfig.setGroup("dubbo");
        serviceConfig.export();
        System.out.println("server is started");
    }

    static public void referService() {
        ReferenceConfig<GreetingService> referenceConfig = new ReferenceConfig<>();
        referenceConfig.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
        referenceConfig.setInterface(GreetingService.class);
        referenceConfig.setTimeout(5000);
        referenceConfig.setAsync(true);
        referenceConfig.setVersion("1.0.0");
        referenceConfig.setGroup("dubbo");
        referenceConfig.setCheck(false);
        GreetingService greetingService = referenceConfig.get();
        RpcContext.getContext().setAttachment("company", "alibaba");
        System.out.println(greetingService.sayHello("world"));
    }

    public static void main(String[] args) throws InterruptedException {
        exportService();
        referService();
        Thread.currentThread().join();
    }
}