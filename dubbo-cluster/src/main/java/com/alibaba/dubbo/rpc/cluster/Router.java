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
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.List;

/**
 * Router. (SPI, Prototype, ThreadSafe)
 *
 * 根据用户配置的路由规则以及请求携带信息，过滤出符合条件的 Invoker 集合，供后续负载均衡逻辑使用。
 *
 * Router 决定了一次 Dubbo 调用的目标服务，该接口的每个实现类都代表一个路由规则。
 *
 * 当消费方调用服务提供方时，Dubbo 根据路由规则从服务目录中筛选出符合条件的服务列表，之后通过负载均衡算法再次进行筛选。
 *
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Routing">Routing</a>
 *
 * @see com.alibaba.dubbo.rpc.cluster.Cluster#join(Directory)
 * @see com.alibaba.dubbo.rpc.cluster.Directory#list(Invocation)
 */
public interface Router extends Comparable<Router>{

    /**
     * get the router url.
     *
     * 获取路由规则URL.
     *
     * @return url
     */
    URL getUrl();

    /**
     * route.
     *
     * 路由，筛选匹配的Invoker 集合。
     *
     * @param invokers
     * @param url        refer url
     * @param invocation
     * @return routed invokers
     * @throws RpcException
     */
    <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException;

    /**
     * Router's priority, used to sort routers.
     *
     * @return router's priority
     */
    int getPriority();

}