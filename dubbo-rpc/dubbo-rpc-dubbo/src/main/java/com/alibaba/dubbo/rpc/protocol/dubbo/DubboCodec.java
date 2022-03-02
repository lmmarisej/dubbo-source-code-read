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
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.io.Bytes;
import com.alibaba.dubbo.common.io.UnsafeByteArrayInputStream;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.serialize.Serialization;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.codec.ExchangeCodec;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcInvocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static com.alibaba.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.encodeInvocationArgument;

/**
 * Dubbo codec.
 *
 * 都是对 ExchangeCodec 方法的重写。
 *
 * 流中可能包含多个RPC请求，Dubbo框架尝试一次性读取更多完整报文编解码生成对象。
 */
public class DubboCodec extends ExchangeCodec implements Codec2 {

    // 主要是对 Dubbo 响应标记的定义，外加默认方法参数及类型。
    public static final String NAME = "dubbo";  // 协议名
    public static final String DUBBO_VERSION = Version.getProtocolVersion();        // 协议版本
    public static final byte RESPONSE_WITH_EXCEPTION = 0;       // 异常响应
    public static final byte RESPONSE_VALUE = 1;                // 正常响应，有结果
    public static final byte RESPONSE_NULL_VALUE = 2;           // 正常响应，无结果
    public static final byte RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS = 3;  // 异常返回包含隐藏参数
    public static final byte RESPONSE_VALUE_WITH_ATTACHMENTS = 4;           // 响应结果包含隐藏参数
    public static final byte RESPONSE_NULL_VALUE_WITH_ATTACHMENTS = 5;      // 响应空值包含隐藏参数
    public static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];        // 方法参数
    public static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];     // 方法参数类型
    private static final Logger log = LoggerFactory.getLogger(DubboCodec.class);

    // 将请求包解析成 Request 模型。
    @Override
    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        // 协议头第 3 个字节
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK); // 获取序列化器编号
        // get request id.
        long id = Bytes.bytes2long(header, 4);      // 解析请求id
        if ((flag & FLAG_REQUEST) == 0) {               // 解码响应
            // decode response.   响应类型
            Response res = new Response(id);
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(Response.HEARTBEAT_EVENT);     // 事件类型
            }
            // get status.
            byte status = header[3];            // 获取响应码
            res.setStatus(status);
            try {
                if (status == Response.OK) {
                    Object data;
                    if (res.isHeartbeat()) {
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        data = decodeHeartbeatData(channel,
                                // 使用与消费端一致的反序列化类型对数据部分进行解码
                                CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                    } else if (res.isEvent()) {
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        data = decodeEventData(channel,
                                CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                    } else {
                        DecodeableRpcResult result;
                        if (channel.getUrl().getParameter(
                                Constants.DECODE_IN_IO_THREAD_KEY,
                                Constants.DEFAULT_DECODE_IN_IO_THREAD)) {
                            result = new DecodeableRpcResult(channel, res, is,
                                    (Invocation) getRequestData(id), proto);
                            result.decode();
                        } else {
                            result = new DecodeableRpcResult(channel, res,
                                    new UnsafeByteArrayInputStream(readMessageData(is)),
                                    (Invocation) getRequestData(id), proto);
                        }
                        data = result;
                    }
                    res.setResult(data);
                } else {
                    res.setErrorMessage(CodecSupport.deserialize(channel.getUrl(), is, proto).readUTF());
                }
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode response failed: " + t.getMessage(), t);
                }
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.    请求类型
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(Request.HEARTBEAT_EVENT);
            }
            try {
                Object data;
                if (req.isHeartbeat()) {            // 心跳数据
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    data = decodeHeartbeatData(channel,
                            // 使用与消费端序列化一致的反序列化类型对数据部分进行解码
                            CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                } else if (req.isEvent()) {         // 事件
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    data = decodeEventData(channel,
                            CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                } else {            // 请求数据
                    DecodeableRpcInvocation inv;
                    if (channel.getUrl().getParameter(Constants.DECODE_IN_IO_THREAD_KEY, Constants.DEFAULT_DECODE_IN_IO_THREAD)) {
                        inv = new DecodeableRpcInvocation(channel, req, is, proto);
                        inv.decode();
                    } else {
                        inv = new DecodeableRpcInvocation(channel, req, new UnsafeByteArrayInputStream(readMessageData(is)), proto);
                    }
                    data = inv;
                }
                req.setData(data);
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode request failed: " + t.getMessage(), t);
                }
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    private byte[] readMessageData(InputStream is) throws IOException {
        if (is.available() > 0) {
            byte[] result = new byte[is.available()];
            is.read(result);
            return result;
        }
        return new byte[]{};
    }

    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data, DUBBO_VERSION);
    }

    @Override
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(channel, out, data, DUBBO_VERSION);
    }

    // 按照顺序依次将所需字段编码成字节流，对应的解码在 DecodeableRpcInvocation 对象中。
    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        // 将请求消息转成 RpcInvocation 对象
        RpcInvocation inv = (RpcInvocation) data;

        // 1 写入 `dubbo`协议、`path`、`version`
        out.writeUTF(version);      // 写入框架版本
        out.writeUTF(inv.getAttachment(Constants.PATH_KEY));        // 写入调用接口
        out.writeUTF(inv.getAttachment(Constants.VERSION_KEY));     // 写入接口指定的版本，默认为 0.0.0

        // 2 写入方法名、参数类型、参数值
        out.writeUTF(inv.getMethodName());
        out.writeUTF(ReflectUtils.getDesc(inv.getParameterTypes()));
        // 获取方法参数，依次写入方法参数值
        Object[] args = inv.getArguments();
        if (args != null)
            for (int i = 0; i < args.length; i++) {
                // 调用 CallbackServiceCodec#encodeInvocationArgument(...) 方法编码参数，主要用于参数回调功能
                out.writeObject(encodeInvocationArgument(channel, inv, i));
            }
        // 3 写入隐式参数 Map
        out.writeObject(inv.getAttachments());
    }

    // 根据 Dubbo 协议的格式编码响应体，主要将 Dubbo 响应状态和响应值编码成字节流
    @Override
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        // 将响应转为 Result 对象
        Result result = (Result) data;
        // 检测当前协议版本是否支持隐式参数
        // currently, the version value in Response records the version of Request
        boolean attach = Version.isSupportResponseAttatchment(version);
        // 响应结果没有异常信息
        Throwable th = result.getException();
        if (th == null) {
            Object ret = result.getValue();     // 提取正常返回结果
            if (ret == null) {      // 调用结果为空
                // 序列化响应类型
                out.writeByte(attach ? RESPONSE_NULL_VALUE_WITH_ATTACHMENTS : RESPONSE_NULL_VALUE);
            } else {
                // 序列化响应类型
                out.writeByte(attach ? RESPONSE_VALUE_WITH_ATTACHMENTS : RESPONSE_VALUE);
                // 序列化异常对象
                out.writeObject(ret);
            }
        } else {
            out.writeByte(attach ? RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS : RESPONSE_WITH_EXCEPTION);
            out.writeObject(th);
        }

        // 当前协议版本支持 Response 带有 attachments 集合
        if (attach) {
            // returns current version of Response to consumer side.
            // 记录 Dubbo 协议版本，返回给服务消费端
            result.getAttachments().put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
            // 序列化 attachments 集合
            out.writeObject(result.getAttachments());
        }
    }

    @Override
    protected Serialization getSerialization(Channel channel, Request req) {
        if (!(req.getData() instanceof Invocation)) {
            return super.getSerialization(channel, req);
        }
        return DubboCodecSupport.getRequestSerialization(channel.getUrl(), (Invocation) req.getData());
    }

    @Override
    protected Serialization getSerialization(Channel channel, Response res) {
        if (!(res.getResult() instanceof DecodeableRpcResult)) {
            return super.getSerialization(channel, res);
        }
        return DubboCodecSupport.getResponseSerialization(channel.getUrl(), (DecodeableRpcResult) res.getResult());
    }


}
