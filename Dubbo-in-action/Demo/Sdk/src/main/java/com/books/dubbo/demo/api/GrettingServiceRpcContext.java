package com.books.dubbo.demo.api;

/**
 * 演示RpcContext如何实现异步。
 */
public interface GrettingServiceRpcContext {
    String sayHello(String name);
}