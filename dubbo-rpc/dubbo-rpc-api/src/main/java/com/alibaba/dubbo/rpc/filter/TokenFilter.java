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
package com.alibaba.dubbo.rpc.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.Map;

/**
 * TokenInvokerFilter
 *
 * 如果某些服务提供者不想让消费者绕过注册中心直连自己 ， 则可以使用令牌验证 。
 *
 *  TokenFilter 是在服务提供者端生效的过滤器 ， 它的工作就是对请求的令牌做校验 。
 */
@Activate(group = Constants.PROVIDER, value = Constants.TOKEN_KEY)
public class TokenFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv)
            throws RpcException {
        // 消费者从注册中心获得服务提供者包含令牌的 URL
        String token = invoker.getUrl().getParameter(Constants.TOKEN_KEY);
        if (ConfigUtils.isNotEmpty(token)) {
            Class<?> serviceType = invoker.getInterface();
            // 消费者在 RPC 时设置的令牌
            // 在 RpcInvocation 的构造方法中 ， 把服务提供者的令牌设置到附件 (attachments) 中一起请求服务提供者 。
            Map<String, String> attachments = inv.getAttachments();
            String remoteToken = attachments == null ? null : attachments.get(Constants.TOKEN_KEY);
            // 服务提供者认证令牌
            if (!token.equals(remoteToken)) {
                // 认证失败抛出异常
                throw new RpcException("Invalid token! Forbid invoke remote service " + serviceType + " method " + inv.getMethodName() + "() from consumer " + RpcContext.getContext().getRemoteHost() + " to provider " + RpcContext.getContext().getLocalHost());
            }
        }
        return invoker.invoke(inv);
    }

}
