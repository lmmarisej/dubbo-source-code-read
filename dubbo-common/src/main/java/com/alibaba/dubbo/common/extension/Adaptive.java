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
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.URL;

import java.lang.annotation.*;

/**
 * Provide helpful information for {@link ExtensionLoader} to inject dependency extension instance.
 *
 * @see ExtensionLoader
 * @see URL    Adaptive注解动态地通过URL中的参数来确定要使用哪个具体的实现类。
 *
 * 扩展点自适应。
 *
 * 标注在方法上：可以通过参数动态获取实现类。
 * 标注在类上：作为接口的默认实现。如果同一个接口类型有多个类都标注了，将抛出异常。
 *      用在类上，固定了接口的实现，避免了动态匹配的代码生成
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Adaptive {
    /**
     * Decide which target extension to be injected. The name of the target extension is decided by the parameter passed
     * in the URL, and the parameter names are given by this method.
     * <p>
     * If the specified parameters are not found from {@link URL}, then the default extension will be used for
     * dependency injection (specified in its interface's {@link SPI}).
     * <p>
     * For examples, given <code>String[] {"key1", "key2"}</code>:
     * <ol>
     * <li>find parameter 'key1' in URL, use its value as the extension's name</li>
     * <li>try 'key2' for extension's name if 'key1' is not found (or its value is empty) in URL</li>
     * <li>use default extension if 'key2' doesn't appear either</li>
     * <li>otherwise, throw {@link IllegalStateException}</li>
     * </ol>
     * If default extension's name is not give on interface's {@link SPI}, then a name is generated from interface's
     * class name with the rule: divide classname from capital char into several parts, and separate the parts with
     * dot '.', for example: for {@code com.alibaba.dubbo.xxx.YyyInvokerWrapper}, its default name is
     * <code>String[] {"yyy.invoker.wrapper"}</code>. This name will be used to search for parameter from URL.
     *
     * @return parameter key names in URL
     *
     * 可以传入多个参数，会按照参数顺序依次匹配，任意一个匹配成功就返回其对应的实例。
     *
     * 如果没有找到，使用SPI接口中值进行匹配。若还是未找到，将抛出异常。
     */
    String[] value() default {};

}