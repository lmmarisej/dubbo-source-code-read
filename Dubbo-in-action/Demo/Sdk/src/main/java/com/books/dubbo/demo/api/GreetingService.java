package com.books.dubbo.demo.api;

/**
 * 主要用来演示同步调用。
 */
public interface GreetingService {
    String sayHello(String name);

    Result<String> testGeneric(PoJo poJo);
}
