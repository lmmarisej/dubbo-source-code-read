package com.books.dubbo.demo.api;

import java.util.concurrent.CompletableFuture;

/**
 * 演示如何实现异步。
 */
public interface GrettingServiceAsync {
    CompletableFuture<String> sayHello(String name);
}