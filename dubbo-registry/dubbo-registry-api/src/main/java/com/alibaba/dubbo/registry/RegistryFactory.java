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
package com.alibaba.dubbo.registry;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * RegistryFactory. (SPI, Singleton, ThreadSafe)
 *
 * 整个框架的注册与服务发现客户端都是由这个扩展点负责创建的。
 *
 * @see com.alibaba.dubbo.registry.support.AbstractRegistryFactory
 */
@SPI("dubbo")       // 从cl下加载key为dubbo的所有RegistryFactory接口的实现，实现的获取方式为ExtensionLoader
public interface RegistryFactory {

    /**
     * Connect to the registry
     * <p>
     * Connecting the registry needs to support the contract: <br>
     * 1. When the check=false is set, the connection is not checked, otherwise the exception is thrown when disconnection <br>
     * 2. Support username:password authority authentication on URL.<br>
     * 3. Support the backup=10.20.153.10 candidate registry cluster address.<br>
     * 4. Support file=registry.cache local disk file cache.<br>
     * 5. Support the timeout=1000 request timeout setting.<br>
     * 6. Support session=60000 session timeout or expiration settings.<br>
     *
     * @param url Registry address, is not allowed to be empty
     * @return Registry reference, never return empty value
     *
     * @ Adaptive({"protocol"}) 是为了动态查找注册中心的实现。
     */
    @Adaptive({"protocol"})     // 从url url.protocol=xxx 中取value，根据value从RegistryFactory返回对应实现类的Registry实例
    Registry getRegistry(URL url);

}