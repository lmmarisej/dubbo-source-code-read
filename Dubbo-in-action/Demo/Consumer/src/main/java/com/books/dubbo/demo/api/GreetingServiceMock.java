package com.books.dubbo.demo.api;

public class GreetingServiceMock implements GreetingService {
    @Override
    public String sayHello(String name) {
        return "mock value";
    }

    @Override
    public Result<String> testGeneric(PoJo poJo) {
        return null;
    }
}
