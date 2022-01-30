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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.utils.Assert;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec;
import com.alibaba.dubbo.remoting.Decodeable;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.rpc.RpcInvocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import static com.alibaba.dubbo.common.Constants.SERIALIZATION_ID_KEY;
import static com.alibaba.dubbo.common.Constants.SERIALIZATION_SECURITY_CHECK_KEY;
import static com.alibaba.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.decodeInvocationArgument;

/**
 * 是一个可解码的 Invocation 实现类。服务消费方是对 RpcInvocation 对象进行序列化，也就是将请求体 RpcInvocation 中相关信息写入字节流。
 * 服务提供方对字节流反序列化，将读取到的信息放入到 DecodeableRpcInvocation 对象，作为 Request 的请求体数据。
 *
 * 是一个支持解码功能的实现类，并不支持编码功能。
 */
public class DecodeableRpcInvocation extends RpcInvocation implements Codec, Decodeable {

    private static final Logger log = LoggerFactory.getLogger(DecodeableRpcInvocation.class);

    private Channel channel;        //  Dubbo 的通道

    private byte serializationType;     // Serialization 类型编号

    private InputStream inputStream;        // 消息字节流

    private Request request;

    private volatile boolean hasDecoded;        // 是否已经解码完成

    // 可解码 Invocation
    public DecodeableRpcInvocation(Channel channel, Request request, InputStream is, byte id) {
        Assert.notNull(channel, "channel == null");
        Assert.notNull(request, "request == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.request = request;
        this.inputStream = is;
        this.serializationType = id;
    }

    @Override
    public void decode() throws Exception {
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode rpc invocation failed: " + e.getMessage(), e);
                }
                // 解码失败，设置失败标志
                request.setBroken(true);
                request.setData(e);
            } finally {
                hasDecoded = true;
            }
        }
    }

    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {
        // 获取序列化方式，然后通过反序列化得到所需的调用信息
        ObjectInput in = CodecSupport.getSerialization(channel.getUrl(), serializationType)
                .deserialize(channel.getUrl(), input);
        this.put(SERIALIZATION_ID_KEY, serializationType);

        // 框架版本
        String dubboVersion = in.readUTF();
        request.setVersion(dubboVersion);
        setAttachment(Constants.DUBBO_VERSION_KEY, dubboVersion);

        String path = in.readUTF();
        setAttachment(Constants.PATH_KEY, path);
        String version = in.readUTF();
        setAttachment(Constants.VERSION_KEY, version);

        setMethodName(in.readUTF());
        try {
            if (Boolean.parseBoolean(System.getProperty(SERIALIZATION_SECURITY_CHECK_KEY, "false"))) {
                CodecSupport.checkSerialization(path, version, serializationType);
            }

            Object[] args;
            Class<?>[] pts;
            String desc = in.readUTF();
            if (desc.length() == 0) {
                pts = DubboCodec.EMPTY_CLASS_ARRAY;
                args = DubboCodec.EMPTY_OBJECT_ARRAY;
            } else {
                pts = ReflectUtils.desc2classArray(desc);
                args = new Object[pts.length];
                for (int i = 0; i < args.length; i++) {
                    try {
                        args[i] = in.readObject(pts[i]);
                    } catch (Exception e) {
                        if (log.isWarnEnabled()) {
                            log.warn("Decode argument failed: " + e.getMessage(), e);
                        }
                    }
                }
            }
            setParameterTypes(pts);

            Map<String, String> map = (Map<String, String>) in.readObject(Map.class);
            if (map != null && map.size() > 0) {
                Map<String, String> attachment = getAttachments();
                if (attachment == null) {
                    attachment = new HashMap<String, String>();
                }
                attachment.putAll(map);
                setAttachments(attachment);
            }
            //decode argument ,may be callback
            for (int i = 0; i < args.length; i++) {
                args[i] = decodeInvocationArgument(channel, this, pts, i, args[i]);
            }

            setArguments(args);

        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read invocation data failed.", e));
        } finally {
            if (in instanceof Cleanable) {
                ((Cleanable) in).cleanup();
            }
        }
        return this;
    }

}
