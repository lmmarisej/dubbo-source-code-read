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

/**
 * RouterFactory. (SPI, Singleton, ThreadSafe)
 *
 * 是 Dubbo 的扩展点，没有默认扩展实现，用于创建 Router。
 *
 * 其中 getRouter 方法动态生成的自适应扩展实现会根据 protocol 参数选择具体扩展实现，然后使用具体的路由工厂创建对应的路由对象。
 *
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Routing">Routing</a>
 *
 * @see Cluster#join(Directory)
 * @see Directory#list(com.alibaba.dubbo.rpc.Invocation)
 */
@SPI
public interface RouterFactory {

    /**
     * Create router.
     *
     * @param url
     * @return router
     *
     * 根据url决定初始化哪个Router的实现。
     */
    @Adaptive("protocol")
    Router getRouter(URL url);

}