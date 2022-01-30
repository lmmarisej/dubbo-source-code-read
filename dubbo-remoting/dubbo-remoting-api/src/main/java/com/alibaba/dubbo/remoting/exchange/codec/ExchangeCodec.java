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
package com.alibaba.dubbo.remoting.exchange.codec;

import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.io.Bytes;
import com.alibaba.dubbo.common.io.StreamUtils;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.serialize.Serialization;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferInputStream;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferOutputStream;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.support.DefaultFuture;
import com.alibaba.dubbo.remoting.telnet.codec.TelnetCodec;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * ExchangeCodec.
 *
 * 将 Java 对象编码成字节流发送给对端，该过程涉及两部分事情，一个是构造协议头，另一个是对消息体进行序列化处理。
 */
public class ExchangeCodec extends TelnetCodec {

    // header length.
    protected static final int HEADER_LENGTH = 16;
    // magic header.
    protected static final short MAGIC = (short) 0xdabb;
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];
    // message flag.
    protected static final byte FLAG_REQUEST = (byte) 0x80;
    protected static final byte FLAG_TWOWAY = (byte) 0x40;
    protected static final byte FLAG_EVENT = (byte) 0x20;
    protected static final int SERIALIZATION_MASK = 0x1f;
    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() {
        return MAGIC;
    }

    /**
     * 会根据需要编码的消息类型进行分类，下面我们依次来看对请求对象和响应对象的编码，关于编码 Telnet 命令暂不说明。
     */
    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        // 对请求对象进行编码
        if (msg instanceof Request) {
            encodeRequest(channel, buffer, (Request) msg);
        }
        // 对响应对象进行编码
        else if (msg instanceof Response) {
            encodeResponse(channel, buffer, (Response) msg);
        }
        // 提交给父类(Telnet)处理， 编码Telnet命令的结果
        else {
            super.encode(channel, buffer, msg);
        }
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        // 从Buffer 中读取字节数
        int readable = buffer.readableBytes();
        // 创建协议头字节数组，优先解析 Dubbo 协议，而不是 Telnet 命令
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];
        // 从管道中取出header.length个字节
        buffer.readBytes(header);
        // 解码
        return decode(channel, buffer, readable, header);
    }

    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        // check magic number.
        // 通过魔数判断是否 Dubbo 消息,不是的情况下目前是 Telnet 命令行发出的数据包
        if (readable > 0 && header[0] != MAGIC_HIGH
                || readable > 1 && header[1] != MAGIC_LOW) {
            int length = header.length;
            // 如果 header.length < readable 成立，说明 buffer 中数据没有读完，因此需要将数据全部读取出来。因为这不是 Dubbo 协议。
            if (header.length < readable) {
                header = Bytes.copyOf(header, readable);
                buffer.readBytes(header, length, readable - length);
            }
            for (int i = 1; i < header.length - 1; i++) {
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            // 通过telnet命令行发送的数据包不包含消息头，所以这里调用TelnetCodec的decode方法对数据包进行解码
            return super.decode(channel, buffer, readable, header);
        }
        // check length.
        // 检查可读数据字节数是否少于固定长度 ，若小于则返回需要更多的输入。因为Dubbo协议采用 协议头 + payload  的方式
        if (readable < HEADER_LENGTH) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // get data length.
        // 从消息头中获取消息体的长度 - [96 - 127]，通过该长度读取消息体。
        int len = Bytes.bytes2int(header, 12);
        // 检测消息体长度是否超出限制，超出则抛出异常
        checkPayload(channel, len);

        // 检测可读的字节数是否小于实际的字节数【消息头 + 消息体 的字节长度和】，如果是则返回需要更多的输入
        int tt = len + HEADER_LENGTH;
        if (readable < tt) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // limit input stream.  根据消息体长度创建输入流对象
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            // 解析 Header + Body,根据情况，根据具体数据包类型返回 Request 或 Reponse
            return decodeBody(channel, is, header);
        } finally {
            // 跳过未读完的流，并打印错误日志
            if (is.available() > 0) {
                try {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }

    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.
        long id = Bytes.bytes2long(header, 4);
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(Response.HEARTBEAT_EVENT);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                if (status == Response.OK) {
                    Object data;
                    if (res.isHeartbeat()) {
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        data = decodeHeartbeatData(channel, CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                    } else if (res.isEvent()) {
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        data = decodeEventData(channel,
                                CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                    } else {
                        data = decodeResponseData(channel, in, getRequestData(id));
                    }
                    res.setResult(data);
                } else {
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(Request.HEARTBEAT_EVENT);
            }
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                Object data;
                if (req.isHeartbeat()) {
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    data = decodeHeartbeatData(channel,
                            CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                } else if (req.isEvent()) {
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    data = decodeEventData(channel,
                            CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                } else {
                    data = decodeRequestData(channel, in);
                }
                req.setData(data);
            } catch (Throwable t) {
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null)
            return null;
        Request req = future.getRequest();
        if (req == null)
            return null;
        return req.getData();
    }

    /**
     * 主要逻辑是编码协议头，并且对事件和普通请求消息分别做处理。
     */
    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        // 获取序列化方式，如果没有配置，默认是 hessian2
        Serialization serialization = getSerialization(channel);
        // header.
        // 创建协议头字节数组，长度为16
        byte[] header = new byte[HEADER_LENGTH];
        // set magic number.
        // 设置魔数，占用2个字节: [0-7] -> 魔数高位 -38，【8-15】 -> 魔数低位 -69
        Bytes.short2bytes(MAGIC, header);

        // set request and serialization flag.
        // 设置数据包类型（Request/Response，0 - Response  1- Request）[16]   和序列化器编号 [19,23]
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        // 设置通信方式 (twoWay)，即是双向传输还是单向，0 - 单向调用，1 - 双向调用 [17]
        if (req.isTwoWay()) header[2] |= FLAG_TWOWAY;
        // 是否为事件（event）[18]
        if (req.isEvent()) header[2] |= FLAG_EVENT;

        // set request id.
        // 设置请求编号，8个字节，从第5个字节开始设置 [32 - 95] 请求 id 编号，Long 型。
        Bytes.long2bytes(req.getId(), header, 4);

        // encode request data.
        // 获取 buffer 当前的写位置
        int savedWriteIndex = buffer.writerIndex();
        // 更新 writerIndex，为协议头预留 16 个字节的空间
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
        // 获取序列化器，如 Hessian2ObjectOutput
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
        // 对事件进行序列化
        if (req.isEvent()) {
            encodeEventData(channel, out, req.getData());
        } else {
            // 对普通请求的数据进行序列化，即将req.data（一般是RpcInvocation） 写入到输出流out中，ChannelBufferOutputStream进行接收，然后存储到ChannelBuffer。
            encodeRequestData(channel, out, req.getData(), req.getVersion());
        }
        out.flushBuffer();
        if (out instanceof Cleanable) {
            ((Cleanable) out).cleanup();
        }
        bos.flush();
        bos.close();
        // 按照字节计数请求的数据序列化后大小，是否超过消息上限。默认最大为 8M，可以通过 payload 参数配置。
        int len = bos.writtenBytes();
        // 可能会抛出异常，即使抛出异常也无需复位 buffer，每一个请求挂载的 ChannelBuffer 都是新建的。
        checkPayload(channel, len);
        // 将 消息体长度写入到消息头中 [96 - 127]。这也是为什么 ChannelBuffer 要先写入消息体的原因。
        Bytes.int2bytes(len, header, 12);

        // write
        // 将 buffer 指针移动到 savedWriteIndex，为写消息头做准备
        buffer.writerIndex(savedWriteIndex);
        // 从 savedWriteIndex 下标处写入消息头
        buffer.writeBytes(header); // write header.
        // 调整 ChannelBuffer 写入位置，即writerIndex = 原写下标 + 消息头长度 + 消息体长度
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        // 获取写的位置
        int savedWriteIndex = buffer.writerIndex();
        try {
            // 获取序列化器
            Serialization serialization = getSerialization(channel);
            // header.
            // 创建消息头字节数组，长度为16
            byte[] header = new byte[HEADER_LENGTH];
            // set magic number.
            // 设置魔数，占2个字节 [0-15]
            Bytes.short2bytes(MAGIC, header);
            // set request and serialization flag.
            // 设置序列化器编号，占header第3个字节的后5位 [19 -23]。
            // 数据包类型（Request/Response，0 - Response  1- Request）[16] 这里使用默认 0 ，因为是响应包
            header[2] = serialization.getContentTypeId();
            // 如果心跳数据包，就设置header第3个字节的第3位 [18]
            if (res.isHeartbeat()) header[2] |= FLAG_EVENT;
            // set response status.
            // 设置响应状态，占1个字节，[24-31]
            byte status = res.getStatus();
            header[3] = status;
            // set request id.
            // 设置请求编号，注意Response中的id就是Request的编号，占8个字节 [32-95]
            Bytes.long2bytes(res.getId(), header, 4);

            // 更新 writerIndex，为消息头预留 16 个字节的空间
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
            // encode response data or error message.
            // 编码响应数据或错误信息
            if (status == Response.OK) {
                if (res.isHeartbeat()) {
                    // 对心跳响应结果进行序列化
                    encodeHeartbeatData(channel, out, res.getResult());
                } else {
                    // 对响应消息进行序列化，res.getResult() 一般是 Result 对象
                    encodeResponseData(channel, out, res.getResult(), res.getVersion());
                }
            }
            // 对错误信息进行序列化
            else out.writeUTF(res.getErrorMessage());
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
            bos.flush();
            bos.close();

            // 获取消息体长度
            int len = bos.writtenBytes();
            // 校验消息长度有没有超出当前设置的上限
            checkPayload(channel, len);
            // 将消息体长度写入到消息头中，占4个字节  [96-127]
            Bytes.int2bytes(len, header, 12);
            // write
            // 将 buffer 指针移动到 savedWriteIndex，为写消息头做准备
            buffer.writerIndex(savedWriteIndex);
            // 从 savedWriteIndex 下标处写入消息头
            buffer.writeBytes(header); // write header.
            // 设置新的 writerIndex，writerIndex = 原写下标 + 消息头长度 + 消息体长度
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // clear buffer 复位 buffer
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {       // 注意和编码请求的不同
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                // 消息内容过大
                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in, byte[] eventPayload) throws IOException {
        try {
            int dataLen = eventPayload.length;
            int threshold = Integer.parseInt(System.getProperty("deserialization.event.size", "50"));
            if (dataLen > threshold) {
                throw new IllegalArgumentException("Event data too long, actual size " + dataLen + ", threshold " + threshold + " rejected for security consideration.");
            }
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Deprecated
    protected Object decodeHeartbeatData(Channel channel, ObjectInput in, byte[] eventPayload) throws IOException {
        return decodeEventData(channel, in, eventPayload);
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeResponseData(out, data);
    }


}
