package org.apache.dubbo.demo.consumer;

import com.books.dubbo.demo.api.GreetingService;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.remoting.exchange.ResponseCallback;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.protocol.dubbo.FutureAdapter;

import java.util.concurrent.ExecutionException;

public class APiAsyncConsumerForCallBack {
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        ReferenceConfig<GreetingService> referenceConfig = new ReferenceConfig<>();     // 1.创建引用实例，并设置属性
        referenceConfig.setApplication(new ApplicationConfig("first-dubbo-consumer"));
        referenceConfig.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
        referenceConfig.setInterface(GreetingService.class);
        referenceConfig.setTimeout(5000);
        referenceConfig.setVersion("1.0.0");
        referenceConfig.setGroup("dubbo");
        referenceConfig.setAsync(true);     // 2. 设置为异步
        GreetingService greetingService = referenceConfig.get();        // 3. 直接返回null
        System.err.println(greetingService.sayHello("world1"));
        System.err.println(greetingService.sayHello("world2"));     // 每次异步调用，RpcContext都会覆盖上一次调用方法的句柄
        // 多次调用会成功，但是在通过RpcContext获取结果时，只能获取到最后一次调用的结果
        // 远程调用和RpcContext.setCallback总是两两成对
        System.err.println(greetingService.sayHello("world3"));
        // 4.异步执行回调
        ((FutureAdapter<?>) RpcContext.getContext().getFuture()).getFuture().setCallback(new ResponseCallback() {
            @Override
            public void done(Object response) {
                System.err.println("result:" + response);
            }

            @Override
            public void caught(Throwable exception) {
                System.err.println("error:" + exception.getLocalizedMessage());
            }
        });
        Thread.currentThread().join();
    }
}