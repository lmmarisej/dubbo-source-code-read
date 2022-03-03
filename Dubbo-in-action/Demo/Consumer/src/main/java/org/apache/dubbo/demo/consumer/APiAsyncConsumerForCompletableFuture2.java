package org.apache.dubbo.demo.consumer;

import com.books.dubbo.demo.api.GreetingService;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.rpc.RpcContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class APiAsyncConsumerForCompletableFuture2 {
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        ReferenceConfig<GreetingService> referenceConfig = new ReferenceConfig<>();
        referenceConfig.setApplication(new ApplicationConfig("first-dubbo-consumer"));
        referenceConfig.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
        referenceConfig.setInterface(GreetingService.class);
        referenceConfig.setTimeout(30000);
        referenceConfig.setVersion("1.0.0");
        referenceConfig.setGroup("dubbo");
        referenceConfig.setAsync(true);                                                         // 2. 设置为异步
        GreetingService greetingService = referenceConfig.get();                                // 3. 直接返回null
        System.out.println(greetingService.sayHello("world1"));
        System.out.println(greetingService.sayHello("world2"));
        CompletableFuture<String> future = RpcContext.getContext().getCompletableFuture();      // 4.异步执行回调
        future.whenComplete((v, t) -> {
            if (t != null) {
                t.printStackTrace();
            } else {
                System.out.println(v);
            }
        });
        System.out.println("over");
        Thread.currentThread().join();
    }
}