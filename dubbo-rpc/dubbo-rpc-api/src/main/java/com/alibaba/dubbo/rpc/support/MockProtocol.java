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
package com.alibaba.dubbo.rpc.support;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractProtocol;

/**
 * MockProtocol is used for generating a mock invoker by URL and type on consumer side
 *
 * 协议的一种实现，扩展名为 mock， 主要是把注册中心的 Mock URL 转换为 MockInvoker 对象。
 *
 * Mock URL 可以通过服务治理平台或其它方式写入注册中心，它被定义为只能引用，不能暴露。
 */
final public class MockProtocol extends AbstractProtocol {

    @Override
    public int getDefaultPort() {
        return 0;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 直接抛出异常，无法暴露服务
        throw new UnsupportedOperationException();
    }

    // 支持通过 refer() 方法创建 MockInvoker 对象
    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        // 直接创建MockInvoker对象
        return new MockInvoker<T>(url);
    }
}
