package org.apache.dubbo.demo.consumer;

import com.books.dubbo.demo.api.GreetingService;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.rpc.RpcContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;


public class APiAsyncConsumerForCompletableFuture {
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        ReferenceConfig<GreetingService> referenceConfig = new ReferenceConfig<>();
        referenceConfig.setApplication(new ApplicationConfig("first-dubbo-consumer"));
        referenceConfig.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
        referenceConfig.setInterface(GreetingService.class);
        referenceConfig.setTimeout(30000);
        referenceConfig.setVersion("1.0.0");
        referenceConfig.setGroup("dubbo");
        // 2. 设置为异步
        referenceConfig.setAsync(true);
        // 3. 直接返回null
        GreetingService greetingService = referenceConfig.get();
        System.out.println(greetingService.sayHello("world1"));
        System.out.println(greetingService.sayHello("world2"));
        System.out.println(greetingService.sayHello("world3"));
        // 4.异步执行回调
        CompletableFuture<String> f = RpcContext.getContext().getCompletableFuture();
        CompletableFuture<String> result = f.thenApplyAsync(t -> t);
        result.thenAcceptAsync(System.out::println);
        System.out.println("over");
        Thread.sleep(6000);
    }
}