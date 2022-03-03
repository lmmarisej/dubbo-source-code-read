package org.apache.dubbo.demo.consumer;

import org.apache.dubbo.common.beanutil.JavaBeanDescriptor;
import org.apache.dubbo.common.beanutil.JavaBeanSerializeUtil;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.rpc.service.GenericService;

import java.io.IOException;

public class APiGenericConsumerForBean {
    public static void main(String[] args) throws IOException {
        ReferenceConfig<GenericService> referenceConfig = new ReferenceConfig<>();
        referenceConfig.setApplication(new ApplicationConfig("first-dubbo-consumer"));
        referenceConfig.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
        referenceConfig.setVersion("1.0.0");
        referenceConfig.setGroup("dubbo");
        referenceConfig.setInterface("com.books.dubbo.demo.api.GreetingService");
        referenceConfig.setGeneric("bean");     // 设置为泛化引用，类型为bean，对参数使用JavaBean方式序列化
        GenericService greetingService = referenceConfig.get();
        // 参数不再是Map，而是用JavaBeanDescriptor实例包裹
        JavaBeanDescriptor param = JavaBeanSerializeUtil.serialize("world");
        Object result = greetingService.$invoke("sayHello", new String[]{"java.lang.String"}, new Object[]{param});
        result = JavaBeanSerializeUtil.deserialize((JavaBeanDescriptor) result);        // 结果反序列化
        System.err.println(result);
    }
}