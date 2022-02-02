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

import com.alibaba.dubbo.common.extension.SPI;

/**
 * Merger是合并器， 可以对并行调用的结果集进行合并。
 *
 * 默认已经支持map、set、 list、 byte等11种类型的返回值。 用户可以基于该扩展点， 添加自定义类型的合并器。
 */
@SPI
public interface Merger<T> {

    // 合并 T 数组，返回合并后的 T 对象
    T merge(T... items);

}
