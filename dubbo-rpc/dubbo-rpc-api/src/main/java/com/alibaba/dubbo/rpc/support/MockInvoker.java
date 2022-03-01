/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.PojoUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.fastjson.JSON;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 逻辑的执行是通过 MockInvoker 来完成的。
 *
 * MockInvoker 用于解析各类 mock 配置，以及根据不同 mock 配置进行不同的处理。即 MockInvoker 实现最终的 Invoke 逻辑。
 */
final public class MockInvoker<T> implements Invoker<T> {
    // ProxyFactory 自适应扩展实现
    private final static ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    // mock 与 Invoker 对象的映射缓存
    private final static Map<String, Invoker<?>> mocks = new ConcurrentHashMap<String, Invoker<?>>();
    // mock 与 Throwable 对象的映射缓存
    private final static Map<String, Throwable> throwables = new ConcurrentHashMap<String, Throwable>();

    private final URL url;      // URL 对象 - 注册中心 URL

    public MockInvoker(URL url) {
        this.url = url;
    }

    public static Object parseMockValue(String mock) throws Exception {
        return parseMockValue(mock, null);
    }

    /**
     * 用于解析 mock 值，并转换成对应的返回类型。
     * 目前支持以下类型的参数：
     *      - mock 值等于 empty，则根据返回类型返回 new xxx() 空对象。
     *      - mock 值为 null、true、false ，则直接返回这些值。
     *      - 如果是字符串，则返回字符串。
     *      - 如果是数字、List、Map 类型的 JSON 串，则解析为对应的对象。
     *      - 如果都没有匹配上，则直接返回 Mock 的参数值。
     */
    public static Object parseMockValue(String mock, Type[] returnTypes) throws Exception {
        Object value = null;
        if ("empty".equals(mock)) {
            value = ReflectUtils.getEmptyObject(returnTypes != null && returnTypes.length > 0 ? (Class<?>) returnTypes[0] : null);
        } else if ("null".equals(mock)) {
            value = null;
        } else if ("true".equals(mock)) {
            value = true;
        } else if ("false".equals(mock)) {
            value = false;
        } else if (mock.length() >= 2 && (mock.startsWith("\"") && mock.endsWith("\"")
                || mock.startsWith("\'") && mock.endsWith("\'"))) {
            value = mock.subSequence(1, mock.length() - 1);
        } else if (returnTypes != null && returnTypes.length > 0 && returnTypes[0] == String.class) {
            value = mock;
        } else if (StringUtils.isNumeric(mock)) {
            value = JSON.parse(mock);
        } else if (mock.startsWith("{")) {
            value = JSON.parseObject(mock, Map.class);
        } else if (mock.startsWith("[")) {
            value = JSON.parseObject(mock, List.class);
        } else {
            value = mock;
        }
        if (returnTypes != null && returnTypes.length > 0) {
            value = PojoUtils.realize(value, (Class<?>) returnTypes[0], returnTypes.length > 1 ? returnTypes[1] : null);
        }
        return value;
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        // 1 获取 mock 配置项，方法级别 > 接口级别
        // 1.1 从URL中的 methodName.mock 参数获取 (方法级别设置 mock)
        String mock = getUrl().getParameter(invocation.getMethodName() + "." + Constants.MOCK_KEY);
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(this);
        }
        if (StringUtils.isBlank(mock)) {
            // 1.2 从URL中的 mock 参数获取
            mock = getUrl().getParameter(Constants.MOCK_KEY);
        }

        if (StringUtils.isBlank(mock)) {
            // 1.3 没有配置 mock 则直接抛出异常
            throw new RpcException(new IllegalAccessException("mock can not be null. url :" + url));
        }
        // 2 标准化 mock 配置项
        mock = normalizeMock(URL.decode(mock));
        // 根据不同的mock策略，返回不同的值
        // 3 mock 值以 return 开头，返回对应值的 RpcResult 对象
        if (mock.startsWith(Constants.RETURN_PREFIX)) {
            mock = mock.substring(Constants.RETURN_PREFIX.length()).trim();
            try {
                // 获取响应结果的类型
                Type[] returnTypes = RpcUtils.getReturnTypes(invocation);
                // 根据响应结果类型，对 mock 值中结果进行转换
                Object value = parseMockValue(mock, returnTypes);
                // 将固定的 mock 值设置到 Result 中
                return new RpcResult(value);
            } catch (Exception ew) {
                throw new RpcException("mock return invoke error. method :" + invocation.getMethodName()
                        + ", mock:" + mock + ", url: " + url, ew);
            }
        }
        // 4 mock 值以 throw 开头
        else if (mock.startsWith(Constants.THROW_PREFIX)) {
            mock = mock.substring(Constants.THROW_PREFIX.length()).trim();
            // 未指定异常类型，直接抛出RpcException
            if (StringUtils.isBlank(mock)) {
                throw new RpcException("mocked exception for service degradation.");
            }
            // 抛出自定义异常
            else { // user customized class
                Throwable t = getThrowable(mock);
                throw new RpcException(RpcException.BIZ_EXCEPTION, t);
            }
        }
        // 5 自定义 Mock 实现，执行自定义逻辑
        else { //impl mock
            try {
                // 创建 Invoker 对象（因为调用信息被封装在 invocation 中，因此需要统一使用 Invoker 处理）
                Invoker<T> invoker = getInvoker(mock);
                // 执行 Invoker 对象的调用逻辑
                return invoker.invoke(invocation);
            } catch (Throwable t) {
                throw new RpcException("Failed to create mock implementation class " + mock, t);
            }
        }
    }

    public static Throwable getThrowable(String throwstr) {
        Throwable throwable = throwables.get(throwstr);
        if (throwable != null) {
            return throwable;
        }

        try {
            Throwable t;
            Class<?> bizException = ReflectUtils.forName(throwstr);
            Constructor<?> constructor;
            constructor = ReflectUtils.findConstructor(bizException, String.class);
            t = (Throwable) constructor.newInstance(new Object[]{"mocked exception for service degradation."});
            if (throwables.size() < 1000) {
                throwables.put(throwstr, t);
            }
            return t;
        } catch (Exception e) {
            throw new RpcException("mock throw error :" + throwstr + " argument error.", e);
        }
    }

    // 缓存含有mock实现类对应invoker对象，直接返回，否则使用getMockObject方法创建对象实例并缓存，最后返回
    // 获取 mock (处理后的) 对应的 Invoker
    @SuppressWarnings("unchecked")
    private Invoker<T> getInvoker(String mock) {
        // 获得接口类
        Class<T> serviceType = (Class<T>) ReflectUtils.forName(url.getServiceInterface());
        String mockService = ConfigUtils.isDefault(mock) ? serviceType.getName() + "Mock" : mock;
        Invoker<T> invoker = (Invoker<T>) mocks.get(mockService);
        if (invoker != null) {
            return invoker;
        }

        // 根据 serviceType 查找 mock 的实现对象
        T mockObject = (T) getMockObject(mock, serviceType);
        // 通过 ProxyFactory 创建 mock 对应的 Invoker 对象，因为调用信息被封装在 Invocation 中
        invoker = proxyFactory.getInvoker(mockObject, serviceType, url);
        if (mocks.size() < 10000) {
            mocks.put(mockService, invoker);
        }
        return invoker;
    }

    @SuppressWarnings("unchecked")
    public static Object getMockObject(String mockService, Class serviceType) {
        // 如果 mockService 为 true 或 default ，则在服务接口后添加 Mock 字符串，作为服务接口的 Mock 实现
        if (ConfigUtils.isDefault(mockService)) {
            mockService = serviceType.getName() + "Mock";       // 也就是说，实现类的命名规则有限制
        }

        // 反射获取 Mock 实现类
        Class<?> mockClass = ReflectUtils.forName(mockService);
        if (!serviceType.isAssignableFrom(mockClass)) {
            throw new IllegalStateException("The mock class " + mockClass.getName() +
                    " not implement interface " + serviceType.getName());
        }

        try {
            //  创建 Mock 对象
            return mockClass.newInstance();
        } catch (InstantiationException e) {
            throw new IllegalStateException("No default constructor from mock class " + mockClass.getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }


    /**
     * Normalize mock string:
     *
     * <ol>
     * <li>return => return null</li>
     * <li>fail => default</li>
     * <li>force => default</li>
     * <li>fail:throw/return foo => throw/return foo</li>
     * <li>force:throw/return foo => throw/return foo</li>
     * </ol>
     *
     * @param mock mock string
     * @return normalized mock string
     */
    public static String normalizeMock(String mock) {
        if (mock == null) {
            return mock;
        }

        mock = mock.trim();        // mock 去空格

        if (mock.length() == 0) {
            return mock;
        }

        if (Constants.RETURN_KEY.equalsIgnoreCase(mock)) {
            return Constants.RETURN_PREFIX + "null";         // 1 等于 return ，则返回 return null
        }

        if (ConfigUtils.isDefault(mock) || "fail".equalsIgnoreCase(mock) || "force".equalsIgnoreCase(mock)) {
            return "default";        // 2 为 "true" "default" "fail" "force" 四种字符串，则直接返回 default
        }

        if (mock.startsWith(Constants.FAIL_PREFIX)) {           // 3 以 fail: 开头，则去掉该开头
            mock = mock.substring(Constants.FAIL_PREFIX.length()).trim();
        }

        if (mock.startsWith(Constants.FORCE_PREFIX)) {      // 4 以 force: 开头，则去掉该开头
            mock = mock.substring(Constants.FORCE_PREFIX.length()).trim();
        }

        if (mock.startsWith(Constants.RETURN_PREFIX) || mock.startsWith(Constants.THROW_PREFIX)) {
            mock = mock.replace('`', '"');
        }

        return mock;
    }

    @Override
    public URL getUrl() {
        return this.url;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void destroy() {
        //do nothing
    }

    @Override
    public Class<T> getInterface() {
        //FIXME
        return null;
    }
}
