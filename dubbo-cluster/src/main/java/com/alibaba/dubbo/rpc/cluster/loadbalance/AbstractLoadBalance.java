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
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * AbstractLoadBalance
 *
 */
public abstract class AbstractLoadBalance implements LoadBalance {

    /**
     * 对还在预热状态的 Provider 节点进行降权，避免 Provider 一启动就有大量请求涌进来。
     */
    static int calculateWarmupWeight(int uptime, int warmup, int weight) {
        // 计算权重，简化为： (uptime/warmup) * weight。随着服务运行时间 uptime 增大，权重计算值 ww 会慢慢接近配置值 weight。
        int ww = (int) ((float) uptime / ((float) warmup / (float) weight));
        // 权重范围为 [0,weight] 之间
        return ww < 1 ? 1 : (Math.min(ww, weight));
    }

    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        if (invokers == null || invokers.isEmpty())
            return null;
        if (invokers.size() == 1)       // 只包含一个Invoker，则直接返回该Invoker对象
            return invokers.get(0);
        return doSelect(invokers, url, invocation);     // 交给doSelect()方法处理，这是个抽象方法，留给子类具体实现
    }

    protected abstract <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation);

    protected int getWeight(Invoker<?> invoker, Invocation invocation) {
        // 如果是RegistryService接口的话，直接根据配置项 registry.weight 获取权重即可，默认是 100
        int weight = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.WEIGHT_KEY, Constants.DEFAULT_WEIGHT);
        if (weight > 0) {
            // 获取服务提供者的启动时间戳
            long timestamp = invoker.getUrl().getParameter(Constants.REMOTE_TIMESTAMP_KEY, 0L);
            if (timestamp > 0L) {
                // 计算Provider运行时长
                int uptime = (int) (System.currentTimeMillis() - timestamp);
                // 从 url 中获取 Provider 预热时间配置值，默认为10分钟，即 10 * 60 * 1000
                int warmup = invoker.getUrl().getParameter(Constants.WARMUP_KEY, Constants.DEFAULT_WARMUP);
                // 如果 Provider 运行时间小于预热时间，则该Provider节点可能还在预热阶段，需要降低其权重
                // 如果还在预热阶段需要对其进行降权处理，目的是避免服务提供者一启动就有大量请求涌来，处于高负载状态。服务预热是一个优化手段，
                // 一般在服务启动后，让其在小流量状态下运行一段时间，然后再逐步放大流量。
                if (uptime > 0 && uptime < warmup) {
                    weight = calculateWarmupWeight(uptime, warmup, weight);
                }
            }
        }
        return weight;
    }

}
