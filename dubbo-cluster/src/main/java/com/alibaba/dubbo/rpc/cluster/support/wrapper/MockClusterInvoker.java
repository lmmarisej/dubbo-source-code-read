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
package com.alibaba.dubbo.rpc.cluster.support.wrapper;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.support.MockInvoker;

import java.util.List;

public class MockClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(MockClusterInvoker.class);

    private final Directory<T> directory;       // 服务目录

    private final Invoker<T> invoker;       // 真正的 Invoker 对象 -> ClusterInvoker

    public MockClusterInvoker(Directory<T> directory, Invoker<T> invoker) {
        this.directory = directory;
        this.invoker = invoker;
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        this.invoker.destroy();
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    /**
     * 主要处理无 Mock、强制 Mock 以及失败后返回 Mock 结果等逻辑，具体的 Mock 逻辑被封装到了 doMockInvoke 方法中了。
     */
    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        Result result = null;

        // 1 获取 mock 配置项的值，默认是 false （getUrl() 是提供方URL合并处理的值，其中提供方的参数配置项优先级最低）
        // 会先方法级别的 mock 配置项，如 <dubbo:parameter key="sayHello.mock" value="force:return"/>
        // 没有再获取参数级别，如<dubbo:reference interface="com.foo.BarService" mock="force:return"/>
        String value = directory.getUrl().getMethodParameter(invocation.getMethodName(), Constants.MOCK_KEY, Boolean.FALSE.toString()).trim();
        if (value.length() == 0 || value.equalsIgnoreCase("false")) {
            //no mock
            // 2 mock 配置项未设置或设置为 false ，则不会开启 Mock 机制，直接调用底层的 ClusterInvoker
            result = this.invoker.invoke(invocation);
        } else if (value.startsWith("force")) {
            if (logger.isWarnEnabled()) {
                logger.info("force-mock: " + invocation.getMethodName() + " force-mock enabled , url : " + directory.getUrl());
            }
            //force:direct mock
            // 3 mock 配置项为 force，表示强制 Mock，直接调用 doMockInvoke() 方法执行 Mock 机制
            result = doMockInvoke(invocation, null);
        } else {
            //fail-mock
            try {
                // 4 如果 mock 配置项配置的不是 force，则执行 默认行为。即先调用 Invoker，调用失败再执行 Mock 行为。
                result = this.invoker.invoke(invocation);
            } catch (RpcException e) {
                if (e.isBiz()) {
                    throw e;               // 如果是业务异常，则直接抛出
                } else {
                    if (logger.isWarnEnabled()) {
                        logger.warn("fail-mock: " + invocation.getMethodName() + " fail-mock enabled , url : " + directory.getUrl(), e);
                    }
                    // 如果是非业务异常，则调用 doMockInvoke() 方法返执行 Mock 机制
                    result = doMockInvoke(invocation, e);
                }
            }
        }
        return result;
    }

    // 具体的 Mock 逻辑
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Result doMockInvoke(Invocation invocation, RpcException e) {
        Result result = null;
        Invoker<T> minvoker;

        // 1 调用 selectMockInvoker() 方法过滤得到的 MockInvoker，即使用路由 MockInvokersSelector 匹配 MockInvoker
        List<Invoker<T>> mockInvokers = selectMockInvoker(invocation);
        // 2 如果 selectMockInvoker() 方法未返回 MockInvoker 对象，则创建一个 MockInvoker
        // 通过 selectMockInvoker 方法获取的是 MockProtocol 协议引用的 Invoker，也就是通过向注册中心的 providers 目录写入 Mock URL 的情况
        if (mockInvokers == null || mockInvokers.isEmpty()) {
            minvoker = (Invoker<T>) new MockInvoker(directory.getUrl());
        } else {
            // 存在则选择第一个 MockInvoker
            minvoker = mockInvokers.get(0);
        }
        try {
            // 3 调用 MockInvoker.invoke() 方法进行 Mock 逻辑
            result = minvoker.invoke(invocation);
        } catch (RpcException me) {
            // 如果是业务异常，则在 Result 中设置该异常
            if (me.isBiz()) {
                result = new RpcResult(me.getCause());
            } else {
                throw new RpcException(me.getCode(), getMockExceptionMessage(e, me), me.getCause());
            }
        } catch (Throwable me) {
            throw new RpcException(getMockExceptionMessage(e, me), me.getCause());
        }
        return result;
    }

    private String getMockExceptionMessage(Throwable t, Throwable mt) {
        String msg = "mock error : " + mt.getMessage();
        if (t != null) {
            msg = msg + ", invoke error is :" + StringUtils.toString(t);
        }
        return msg;
    }

    /**
     * Return MockInvoker
     * Contract：
     * directory.list() will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is present in invocation, otherwise, a list of mock invokers will return.
     * if directory.list() returns more than one mock invoker, only one of them will be used.
     *
     * @param invocation
     * @return
     *
     * 会在调用信息 Invocation 的隐式参数中设置 invocation.need.mock=true 的标识，这样的情况下，从服务目录中获取 Invoker 列表时，
     * MockInvokersSelector 路由如果检测到标识则过滤出 MockInvoker 。
     *
     * 其中 MockInvokersSelector 实现了 Router 接口，具有服务路由的功能。
     */
    private List<Invoker<T>> selectMockInvoker(Invocation invocation) {
        List<Invoker<T>> invokers = null;
        //TODO generic invoker？
        if (invocation instanceof RpcInvocation) {
            //Note the implicit contract (although the description is added to the interface declaration, but extensibility is a problem. The practice placed in the attachement needs to be improved)
            ((RpcInvocation) invocation).setAttachment(Constants.INVOCATION_NEED_MOCK, Boolean.TRUE.toString());
            //directory will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is present in invocation, otherwise, a list of mock invokers will return.
            try {
                invokers = directory.list(invocation);
            } catch (RpcException e) {
                if (logger.isInfoEnabled()) {
                    logger.info("Exception when try to invoke mock. Get mock invokers error for service:"
                            + directory.getUrl().getServiceInterface() + ", method:" + invocation.getMethodName()
                            + ", will contruct a new mock with 'new MockInvoker()'.", e);
                }
            }
        }
        return invokers;
    }

    @Override
    public String toString() {
        return "invoker :" + this.invoker + ",directory: " + this.directory;
    }
}
