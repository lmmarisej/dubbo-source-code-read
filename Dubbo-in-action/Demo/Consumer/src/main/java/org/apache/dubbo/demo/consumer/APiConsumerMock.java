package org.apache.dubbo.demo.consumer;

import com.books.dubbo.demo.api.GreetingService;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.rpc.RpcContext;

public class APiConsumerMock {
    public static void main(String[] args) throws InterruptedException {
        ReferenceConfig<GreetingService> referenceConfig = new ReferenceConfig<>();
        referenceConfig.setApplication(new ApplicationConfig("first-dubbo-consumer"));
        referenceConfig.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
        referenceConfig.setInterface(GreetingService.class);
        referenceConfig.setTimeout(5000);
        referenceConfig.setVersion("1.0.0");
        referenceConfig.setGroup("dubbo");
        // 启动时候不检查服务提供者是否可用
        referenceConfig.setCheck(false);
        referenceConfig.setMock("true");        // 当远程不可用，将加载本地GreetingService后缀为Mock类的实现类，作为Provider来调用
        GreetingService greetingService = referenceConfig.get();
        // 设置隐式参数
        RpcContext.getContext().setAttachment("company", "alibaba");
        System.err.println(greetingService.sayHello("world"));
    }
}