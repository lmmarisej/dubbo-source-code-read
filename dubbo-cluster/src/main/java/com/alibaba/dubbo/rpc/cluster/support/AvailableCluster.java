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

import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * AvailableCluster
 *
 * 遍历所有的可用节点，使用第一个进行调用，如果没有可用节点，抛出异常。
 */
public class AvailableCluster implements Cluster {

    public static final String NAME = "available";

    @Override
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {

        return new AbstractClusterInvoker<T>(directory) {
            @Override
            public Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
                for (Invoker<T> invoker : invokers) {       // 使用注册中心进行消费，先配置先消费
                    if (invoker.isAvailable()) {
                        return invoker.invoke(invocation);
                    }
                }
                throw new RpcException("No provider available in " + invokers);
            }
        };

    }

}
