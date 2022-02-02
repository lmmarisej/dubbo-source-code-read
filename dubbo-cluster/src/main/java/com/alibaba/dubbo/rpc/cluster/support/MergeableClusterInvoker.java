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
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.Merger;
import com.alibaba.dubbo.rpc.cluster.merger.MergerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 在 MergeableClusterInvoker 使用默认 Merger 实现的时候，
 * 会通过 MergerFactory 以及服务接口返回值类型（returnType）选择合适的 Merger 实现。
 */
@SuppressWarnings("unchecked")
public class MergeableClusterInvoker<T> implements Invoker<T> {

    private static final Logger log = LoggerFactory.getLogger(MergeableClusterInvoker.class);
    private final Directory<T> directory;
    private ExecutorService executor = Executors.newCachedThreadPool(new NamedThreadFactory("mergeable-cluster-executor", true));

    public MergeableClusterInvoker(Directory<T> directory) {
        this.directory = directory;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Result invoke(final Invocation invocation) throws RpcException {
        List<Invoker<T>> invokers = directory.list(invocation);

        // 获取 merger 配置项的值
        String merger = getUrl().getMethodParameter(invocation.getMethodName(), Constants.MERGER_KEY);
        // 判断调用的目标方法是否有 Merger 合并器
        // 在引用多个分组时，可以不指定 merger 合并调用结果。不指定的话，只会调用其中一个可用的 Invoker（这个 Invoker 是通过 Cluster 合并后的）
        if (ConfigUtils.isEmpty(merger)) { // If a method doesn't have a merger, only invoke one Group
            for (final Invoker<T> invoker : invokers) {
                if (invoker.isAvailable()) {
                    return invoker.invoke(invocation);
                }
            }
            // 兜底，没有可用的，就调用第一个 Invoker
            return invokers.iterator().next().invoke(invocation);
        }

        // 确定调用方法的返回值类型
        Class<?> returnType;
        try {
            returnType = getInterface().getMethod(
                    invocation.getMethodName(), invocation.getParameterTypes()).getReturnType();
        } catch (NoSuchMethodException e) {
            returnType = null;
        }

        // 以异步方式调用每个 Invoker 对象，并将调用结果记录到 results 中。
        // results ：key 是 服务键，value 是 AsyncRpcResult
        Map<String, Future<Result>> results = new HashMap<String, Future<Result>>();
        for (final Invoker<T> invoker : invokers) {
            // 异步调用
            Future<Result> future = executor.submit(new Callable<Result>() {
                @Override
                public Result call() throws Exception {
                    return invoker.invoke(new RpcInvocation(invocation, invoker));
                }
            });
            results.put(invoker.getUrl().getServiceKey(), future);
        }

        Object result = null;

        List<Result> resultList = new ArrayList<Result>(results.size());

        int timeout = getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
        // 等待结果返回
        for (Map.Entry<String, Future<Result>> entry : results.entrySet()) {
            Future<Result> future = entry.getValue();
            try {
                Result r = future.get(timeout, TimeUnit.MILLISECONDS);
                if (r.hasException()) {
                    log.error("Invoke " + getGroupDescFromServiceKey(entry.getKey()) + 
                                    " failed: " + r.getException().getMessage(), 
                            r.getException());
                } else {
                    // 将调用成功的结果保存起来
                    resultList.add(r);
                }
            } catch (Exception e) {
                throw new RpcException("Failed to invoke service " + entry.getKey() + ": " + e.getMessage(), e);
            }
        }

        // 对调用结果集为空或只有一个的情况处理，对应调用空结果集或方法返回值类型是 void ，则返回一个空结果，不需要合并
        if (resultList.isEmpty()) {
            return new RpcResult((Object) null);
        } else if (resultList.size() == 1) {
            return resultList.iterator().next();
        }

        if (returnType == void.class) {
            return new RpcResult((Object) null);
        }

        // 不同合并方式的处理
        // 基于指定的返回类型的方法合并结果（该方法的参数必须是返回结果的类型）
        // merger 以 . 开头，则直接使用 . 后的方法合并结果。如 merger=".addAll" ，则调用的是结果类型的原生方法，
        // 如服务方法返回类型是 List，则就调用 List.addAll 方法来合并结果。
        if (merger.startsWith(".")) {
            merger = merger.substring(1);
            Method method;
            try {
                method = returnType.getMethod(merger, returnType);
            } catch (NoSuchMethodException e) {
                throw new RpcException("Can not merge result because missing method [ " + merger + " ] in class [ " + 
                        returnType.getClass().getName() + " ]");
            }
            if (!Modifier.isPublic(method.getModifiers())) {
                method.setAccessible(true);
            }
            // 获取方法的返回结果
            result = resultList.remove(0).getValue();
            // 反射调用合并方法进行结果的合并
            try {
                // 如果返回类型不是 void，且合并方法返回类型和服务方法返回类型相同，则调用合并方法合并结果，并修改 result
                if (method.getReturnType() != void.class        // 服务方法返回类型为 void ，则返回一个空的结果并结束逻辑。
                        && method.getReturnType().isAssignableFrom(result.getClass())) {
                    for (Result r : resultList) {
                        result = method.invoke(result, r.getValue());
                    }
                }
                // 合并方法返回类型和服务方法返回类型不同，则调用合并方法把结果合并进去即可
                else {
                    for (Result r : resultList) {
                        method.invoke(result, r.getValue());
                    }
                }
            } catch (Exception e) {
                throw new RpcException("Can not merge result: " + e.getMessage(), e);
            }
        } else {
            Merger resultMerger;
            if (ConfigUtils.isDefault(merger)) {
                // merger 参数为 true 或者 default，表示使用内置的 Merger 扩展实现完成合并，即调用 MergerFactory
                resultMerger = MergerFactory.getMerger(returnType);
            } else {
                // merger参数指定了Merger的扩展名称，则使用SPI查找对应的Merger扩展实现对象
                resultMerger = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(merger);
            }
            // 使用 Merger 进行结果合并
            if (resultMerger != null) {
                // 这里将结果同一转成为 Object 类型
                List<Object> rets = new ArrayList<Object>(resultList.size());
                for (Result r : resultList) {
                    rets.add(r.getValue());
                }
                // 执行合并操作
                result = resultMerger.merge(
                        rets.toArray((Object[]) Array.newInstance(returnType, 0)));
            } else {
                throw new RpcException("There is no merger to merge result.");
            }
        }
        return new RpcResult(result);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
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
        directory.destroy();
    }

    private String getGroupDescFromServiceKey(String key) {
        int index = key.indexOf("/");
        if (index > 0) {
            return "group [ " + key.substring(0, index) + " ]";
        }
        return key;
    }
}
