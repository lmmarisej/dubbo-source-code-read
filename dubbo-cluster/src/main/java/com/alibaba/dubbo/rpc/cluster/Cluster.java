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

import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.support.FailoverCluster;

/**
 * Cluster. (SPI, Singleton, ThreadSafe)
 *
 * Cluster，容错接口。提供错误重试、快速失败等容错策略。
 *
 * 在微服务环境中，很少存在单点服务，服务通常是以集群出现的。 集群，给服务提供容错能力。
 *
 * 将多个服务提供者对应的 Invoker 合并为一个 Cluster Invoker，并将这个 Invoker 暴露给服务消费者。
 *
 * 服务消费者只需通过这个 Invoker 进行远程调用即可，至于具体调用哪个服务提供者，以及调用失败后如何处理等问题，现在都交给集群模块去处理。
 * 集群模块是服务提供者和服务消费者的中间层，为服务消费者屏蔽了服务提供者的情况，这样服务消费者就可以专心处理远程调用相关事宜。
 *
 * 比如发请求，接受服务提供者返回的数据等。这就是集群的作用。
 *
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Computer_cluster">Cluster</a>
 * <a href="http://en.wikipedia.org/wiki/Fault-tolerant_system">Fault-Tolerant</a>
 *
 * Dubbo 默认内置了若干容错策略，每种容错策略都有独特的应用场景，使用方可以根据具体需要配置不同的容错策略，如果这些内置容错策略不能满足需求，还可以自定义容错策略。
 */
@SPI(FailoverCluster.NAME)
public interface Cluster {

    /**
     * Merge the directory invokers to a virtual invoker.
     *
     * @param <T>
     * @param directory
     * @return cluster invoker
     * @throws RpcException
     *
     * 不同的Cluster生成不同的invoker，调用invoke方法，正式开始服务调用流程。
     */
    @Adaptive
    <T> Invoker<T> join(Directory<T> directory) throws RpcException;

}