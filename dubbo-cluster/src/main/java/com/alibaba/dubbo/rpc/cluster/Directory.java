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

import com.alibaba.dubbo.common.Node;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.List;

/**
 * Directory. (SPI, Prototype, ThreadSafe)
 *
 * 目录映射到网络资源。
 *
 * 主要负责拉取和订阅服务提供者、动态配置和路由项。
 *
 * 集群 Cluster 实现类为服务消费者基于 Directory 创建 Cluster Invoker 实例。
 *
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Directory_service">Directory Service</a>
 *
 * @see com.alibaba.dubbo.rpc.cluster.Cluster#join(Directory)
 */
public interface Directory<T> extends Node {

    /**
     * get service type.
     *
     * @return service type.
     */
    Class<T> getInterface();

    /**
     * list invokers.
     *
     * 获得所有可用的服务列表。
     *
     * @return invokers
     */
    List<Invoker<T>> list(Invocation invocation) throws RpcException;

}