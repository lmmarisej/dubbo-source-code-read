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
package com.alibaba.dubbo.rpc.cluster;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.loadbalance.RandomLoadBalance;

import java.util.List;

/**
 * LoadBalance. (SPI, Singleton, ThreadSafe)
 *
 * Dubbo框架中的负载均衡策略扩展点， 框架中已经内置随机(Random)、轮询(RoundRobin)、 最小连接数(LeastActive)、
 * 一致性 Hash（ConsistentHash）这几种负载均衡的方式， 默认使用随机负载均衡策略。
 *
 * 将网络请求或者其它形式的负载 "均摊" 到不同的服务节点上，从而避免服务集群中部分节点压力过大，而另一部分节点比较空闲的情况。
 *
 * Dubbo 需要对服务消费者的调用请求进行分配，避免少数提供者节点负载过大，而其它提供者节点处于空闲状态。
 * 服务提供者负载过大，会导致部分请求超时、甚至丢失等一系列问题，造成线上故障。因此将负载均衡到每个服务提供者是非常有必要的。
 *
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Load_balancing_(computing)">Load-Balancing</a>
 *
 * @see com.alibaba.dubbo.rpc.cluster.Cluster#join(Directory)
 */
@SPI(RandomLoadBalance.NAME)
public interface LoadBalance {

    /**
     * select one invoker in list.
     *
     * 根据传入的 URL 和 Invocation ，以及负载均衡算法从 Invoker 集合中选择一个 Invoker。
     *
     * @param invokers   invokers.
     * @param url        refer url
     * @param invocation invocation.
     * @return selected invoker.
     */
    @Adaptive("loadbalance")
    <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException;

}