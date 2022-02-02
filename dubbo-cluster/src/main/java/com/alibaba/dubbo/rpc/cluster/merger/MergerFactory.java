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

package com.alibaba.dubbo.rpc.cluster.merger;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.rpc.cluster.Merger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MergerFactory {

    /**
     * Merger 实现缓存
     * key: 服务接口返回值类型
     * value: Merger 实现
     */
    private static final ConcurrentMap<Class<?>, Merger<?>> mergerCache =
            new ConcurrentHashMap<Class<?>, Merger<?>>();

    // 根据返回值类型获取 Merger ，用于将一个 returnType 数组合并为一个
    public static <T> Merger<T> getMerger(Class<T> returnType) {
        Merger result;
        // returnType 为数组类型
        if (returnType.isArray()) {
            // 获取数组中元素的类型
            Class type = returnType.getComponentType();
            // 获取元素类型对应的 Merger 实现
            result = mergerCache.get(type);
            if (result == null) {
                loadMergers();
                result = mergerCache.get(type);
            }
            // 如果Dubbo 没有提供元素类型对应的Merger实现，则使用 ArrayMerger
            if (result == null && !type.isPrimitive()) {
                result = ArrayMerger.INSTANCE;
            }
        }
        // 如果returnType不是数组类型，则直接从MERGER_CACHE缓存查找对应的Merger实例
        else {
            result = mergerCache.get(returnType);
            if (result == null) {
                loadMergers();
                result = mergerCache.get(returnType);
            }
        }
        return result;
    }

    // 通过 Dubbo SPI 的方式 加载 Merger 接口全部扩展实现
    static void loadMergers() {
        // 获取 Merger 接口的所有扩展名称
        Set<String> names = ExtensionLoader.getExtensionLoader(Merger.class)
                .getSupportedExtensions();
        for (String name : names) {
            // 将 Merger 扩展实现 与对应 returnType 的映射关系记录到MERGER_CACHE集合中
            // 读取泛型参数类型，即为返回值类型
            Merger m = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(name);
            mergerCache.putIfAbsent(ReflectUtils.getGenericClass(m.getClass()), m);
        }
    }

}
