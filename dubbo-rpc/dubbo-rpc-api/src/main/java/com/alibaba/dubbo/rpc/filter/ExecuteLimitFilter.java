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
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcStatus;

import java.util.concurrent.Semaphore;

/**
 * ThreadLimitInvokerFilter
 *
 * 用于限制每个服务中每个方法的最大并发数，有接口级别和方法级别的配置方式。
 */
@Activate(group = Constants.PROVIDER, value = Constants.EXECUTES_KEY)
public class ExecuteLimitFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        URL url = invoker.getUrl();
        String methodName = invocation.getMethodName();
        Semaphore executesLimit = null;
        boolean acquireResult = false;
        // 获取配置
        int max = url.getMethodParameter(methodName, Constants.EXECUTES_KEY, 0);
        //  > 0 表示配置了限流
        if (max > 0) {
            // 允许多少个线程同时调用
            RpcStatus count = RpcStatus.getStatus(url, invocation.getMethodName());
//            if (count.getActive() >= max) {           // 这样写多线程程序有bug，多线程程序就应该加锁，于是下面用到了共享锁
            /**
             * http://manzhizhen.iteye.com/blog/2386408
             * use semaphore for concurrency control (to limit thread number)
             */
            executesLimit = count.getSemaphore(max);        // 构建一个具有max个线程能同时持有的共享锁Semaphore
            // 发现当前计数比设置的最大并发数大时 ， 就会抛出异常 ， 提示己经超过最大并发数 ， 请求就会被终止并直接返回
            if(executesLimit != null && !(acquireResult = executesLimit.tryAcquire())) {
                throw new RpcException("Failed to invoke method " + invocation.getMethodName() + " in provider " + url + ", cause: The service using threads greater than <dubbo:service executes=\"" + max + "\" /> limited.");
            }
        }
        long begin = System.currentTimeMillis();
        boolean isSuccess = true;
        // 并发数计数器原子 +1
        RpcStatus.beginCount(url, methodName);
        try {
            Result result = invoker.invoke(invocation);
            return result;
        } catch (Throwable t) {
            isSuccess = false;
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else {
                throw new RpcException("unexpected exception when ExecuteLimitFilter", t);
            }
        } finally {
            // 将原子 -1
            RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, isSuccess);
            if(acquireResult) {
                executesLimit.release();        // 释放信号量
            }
        }
    }

}
