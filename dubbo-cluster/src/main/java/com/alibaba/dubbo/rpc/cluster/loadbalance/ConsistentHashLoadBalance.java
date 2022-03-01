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
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ConsistentHashLoadBalance
 *
 * 一致性hash，相同的请求总是发送到同一提供者。
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {

    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        String methodName = RpcUtils.getMethodName(invocation);
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;
        int identityHashCode = System.identityHashCode(invokers);
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        if (selector == null || selector.identityHashCode != identityHashCode) {
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, identityHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
        return selector.select(invocation);
    }

    private static final class ConsistentHashSelector<T> {

        // TreeMap数据结构具有排序功能，可以利用它的排序来维持hash环的单调性
        private final TreeMap<Long, Invoker<T>> virtualInvokers;    // 用于缓存 Invoker 的虚拟节点，即多个 hash 值映射到同一个 Invoker 。

        private final int replicaNumber;        // 用于记录每个 Invoker 虚拟节点的个数。

        private final int identityHashCode;     // 用于记录请求涉及的 Invoker 集合的 HashCode 值。

        private final int[] argumentIndex;      // 用于存储参与 Hash 计算的参数索引，用于请求负载均衡时对请求参数进行匹配，确定哪些参数参与 Hash 计算。

        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            // 初始化 virtualInvokers 字段，用于缓存 Invoker 的虚拟节点
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();
            // 记录 Invoker 集合的 hashCode，用该 hashCode 值可以判断 Provider 列表是否发生了变化
            this.identityHashCode = identityHashCode;
            // 获取消费端 Invoker 的 URL
            URL url = invokers.get(0).getUrl();
            // 从配置中获取虚拟节点数（hash.nodes 参数）以及参与 hash 计算的参数下标（hash.arguments 参数）
            this.replicaNumber = url.getMethodParameter(methodName, "hash.nodes", 160);     // 默认160个虚拟节点
            // 对参与 hash  计算的参数下标进行解析，然后存放到 argumentIndex 数组中
            String[] index = Constants.COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, "hash.arguments", "0"));
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            // 构建 Invoker 虚拟节点，默认 replicaNumber=160，相当于在 Hash 环上放 160 个槽位。外层轮询 40 次，内层轮询 4 次，
            // 共 40 * 4 = 160次，也就是同一个节点虚拟出 160 个槽位
            for (Invoker<T> invoker : invokers) {
                String address = invoker.getUrl().getAddress();
                for (int i = 0; i < replicaNumber / 4; i++) {
                    byte[] digest = md5(address + i);
                    for (int h = 0; h < 4; h++) {
                        long m = hash(digest, h);
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }

        public Invoker<T> select(Invocation invocation) {
            // 将参与一致性 Hash 的参数拼接到一起
            String key = toKey(invocation.getArguments());
            byte[] digest = md5(key);              // 对 key 进行 md5 运算
            return selectForKey(hash(digest, 0));       // 根据hash选择对应的服务提供者
        }

        // 将参与 Hash 计算的参数索引对应的参数值进行拼接。默认对第一个参数进行 Hash 运算。
        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        // 选择 Invoker
        private Invoker<T> selectForKey(long hash) {
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.tailMap(hash, true).firstEntry();
            // 如果传入的 hash 大于 Invoker 在 Hash 环上最大的位置，此时 entry = null，此时需要回到 Hash 环的开头返回第一个 Invoker 对象
            if (entry == null) {
                entry = virtualInvokers.firstEntry();
            }
            return entry.getValue();
        }

        // h = 0 时，取 digest 中下标为 0 ~ 3 的4个字节进行位运算
        // h = 1 时，取 digest 中下标为 4 ~ 7 的4个字节进行位运算
        // h = 2, h = 3 时过程同上
        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes;
            bytes = value.getBytes(StandardCharsets.UTF_8);
            md5.update(bytes);
            return md5.digest();
        }

    }

}
