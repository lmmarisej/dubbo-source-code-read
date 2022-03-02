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
package com.alibaba.dubbo.remoting.exchange.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.ResponseCallback;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DefaultFuture.
 *
 * 当客户端多个线程并发请求时，框架内部会调用DefaultFuture对象的get方法进行等待。
 */
public class DefaultFuture implements ResponseFuture {

    private static final Logger logger = LoggerFactory.getLogger(DefaultFuture.class);
    // 缓存通道
    private static final Map<Long, Channel> CHANNELS = new ConcurrentHashMap<Long, Channel>();
    // 缓存Future
    private static final Map<Long, DefaultFuture> FUTURES = new ConcurrentHashMap<Long, DefaultFuture>();

    static {
        // 超时扫描线程
        Thread th = new Thread(new RemotingInvocationTimeoutScan(), "DubboResponseTimeoutScanTimer");
        th.setDaemon(true);
        th.start();
    }

    // invoke id.
    private final long id;          // 请求id
    private final Channel channel;      // 保存通信通道
    private final Request request;      // 请求内容
    private final int timeout;      // 请求超时时间
    private final Lock lock = new ReentrantLock();          // 实现通知等待模型
    private final Condition done = lock.newCondition();
    private final long start = System.currentTimeMillis();
    private volatile long sent;
    private volatile Response response;
    private volatile ResponseCallback callback;

    public DefaultFuture(Channel channel, Request request, int timeout) {
        this.channel = channel;
        this.request = request;
        this.id = request.getId();
        this.timeout = timeout > 0 ? timeout : channel.getUrl().getPositiveParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
        // put into waiting map.
        FUTURES.put(id, this);          // 缓存当前对象
        CHANNELS.put(id, channel);      // 缓存当前连接通道
    }

    public static DefaultFuture getFuture(long id) {
        return FUTURES.get(id);
    }

    public static boolean hasFuture(Channel channel) {
        return CHANNELS.containsValue(channel);
    }

    public static void sent(Channel channel, Request request) {
        DefaultFuture future = FUTURES.get(request.getId());
        if (future != null) {
            future.doSent();
        }
    }

    /**
     * close a channel when a channel is inactive
     * directly return the unfinished requests.
     *
     * @param channel channel to close
     */
    public static void closeChannel(Channel channel) {
        for (long id : CHANNELS.keySet()) {
            if (channel.equals(CHANNELS.get(id))) {
                DefaultFuture future = getFuture(id);
                if (future != null && !future.isDone()) {
                    Response disconnectResponse = new Response(future.getId());
                    disconnectResponse.setStatus(Response.CHANNEL_INACTIVE);
                    disconnectResponse.setErrorMessage("Channel " + channel +
                            " is inactive. Directly return the unFinished request : " + future.getRequest());
                    DefaultFuture.received(channel, disconnectResponse);
                }
            }
        }
    }

    // 当发起的请求返回结果，或超时后，将触发调用
    public static void received(Channel channel, Response response) {
        try {
            DefaultFuture future = FUTURES.remove(response.getId());        // 移除等待的句柄
            if (future != null) {
                future.doReceived(response);        // 存在则调用业务逻辑处理接收
            } else {
                logger.warn("The timeout response finally returned at "
                        + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()))
                        + ", response " + response
                        + (channel == null ? "" : ", channel: " + channel.getLocalAddress()
                        + " -> " + channel.getRemoteAddress()));
            }
        } finally {
            CHANNELS.remove(response.getId());
        }
    }

    @Override
    public Object get() throws RemotingException {
        return get(timeout);
    }

    @Override
    public Object get(int timeout) throws RemotingException {
        if (timeout <= 0) {
            timeout = Constants.DEFAULT_TIMEOUT;        // 超时时间不合法，设置一个默认值
        }
        if (!isDone()) {
            long start = System.currentTimeMillis();
            lock.lock();
            try {
                while (!isDone()) {     // 一直等待响应结果
                    done.await(timeout, TimeUnit.MILLISECONDS);
                    if (isDone() || System.currentTimeMillis() - start > timeout) {     // 计算完成或等待超时
                        break;
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                lock.unlock();
            }
            if (!isDone()) {        // 超时退出
                throw new TimeoutException(sent > 0, channel, getTimeoutMessage(false));
            }
        }
        return returnFromResponse();        // 任务完成，直接返回响应结果
    }

    public void cancel() {
        Response errorResult = new Response(id);
        errorResult.setErrorMessage("request future has been canceled.");
        response = errorResult;
        FUTURES.remove(id);
        CHANNELS.remove(id);
    }

    @Override
    public boolean isDone() {       // 具备可见性
        return response != null;
    }

    @Override
    public void setCallback(ResponseCallback callback) {
        if (isDone()) {
            invokeCallback(callback);
        } else {
            boolean isdone = false;
            // 避免在并发时设置回调不生效
            lock.lock();        // 加锁，避免并发修改
            try {
                if (!isDone()) {            // 任务未完成，存储回调
                    this.callback = callback;
                } else {
                    isdone = true;          // 任务已完成，需要在setCallback时，直接执行回调
                }
            } finally {
                lock.unlock();
            }
            if (isdone) {       // 任务已完成，处理回调
                invokeCallback(callback);
            }
        }
    }

    private void invokeCallback(ResponseCallback c) {
        ResponseCallback callbackCopy = c;
        if (callbackCopy == null) {
            throw new NullPointerException("callback cannot be null.");
        }
        c = null;
        Response res = response;
        if (res == null) {
            throw new IllegalStateException("response cannot be null. url:" + channel.getUrl());
        }

        // 正常返回
        if (res.getStatus() == Response.OK) {
            try {
                callbackCopy.done(res.getResult());
            } catch (Exception e) {
                logger.error("callback invoke error .reasult:" + res.getResult() + ",url:" + channel.getUrl(), e);
            }
        }
        // 异常返回
        else if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            try {
                TimeoutException te = new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage());
                callbackCopy.caught(te);
            } catch (Exception e) {
                logger.error("callback invoke error ,url:" + channel.getUrl(), e);
            }
        }
        // 异常调用
        else {
            try {
                RuntimeException re = new RuntimeException(res.getErrorMessage());
                callbackCopy.caught(re);
            } catch (Exception e) {
                logger.error("callback invoke error ,url:" + channel.getUrl(), e);
            }
        }
    }

    private Object returnFromResponse() throws RemotingException {
        Response res = response;
        if (res == null) {          // 没有返回结果却走到了这里
            throw new IllegalStateException("response cannot be null");
        }
        if (res.getStatus() == Response.OK) {       // 成功返回
            return res.getResult();     // 从传输对象中取出结果
        }
        // 失败返回
        if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            throw new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage());
        }
        // 其它情况
        throw new RemotingException(channel, res.getErrorMessage());
    }

    private long getId() {
        return id;
    }

    private Channel getChannel() {
        return channel;
    }

    private boolean isSent() {
        return sent > 0;
    }

    public Request getRequest() {
        return request;
    }

    private int getTimeout() {
        return timeout;
    }

    private long getStartTimestamp() {
        return start;
    }

    private void doSent() {
        sent = System.currentTimeMillis();
    }

    private void doReceived(Response res) {
        lock.lock();
        try {
            response = res;         // 记录调用结果
            if (done != null) {
                done.signal();          // 唤醒阻塞的线程，只唤醒一个，利用了FIFO唤醒机制
            }
        } finally {
            lock.unlock();
        }
        if (callback != null) {
            invokeCallback(callback);       // 每次doReceived被触发，只有有回调就会被调用
        }
    }

    // 超时信息
    private String getTimeoutMessage(boolean scan) {
        long nowTimestamp = System.currentTimeMillis();
        return (sent > 0 ? "Waiting server-side response timeout" : "Sending request timeout in client-side")
                + (scan ? " by scan timer" : "") + ". start time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(start))) + ", end time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date())) + ","
                + (sent > 0 ? " client elapsed: " + (sent - start)
                + " ms, server elapsed: " + (nowTimestamp - sent)
                : " elapsed: " + (nowTimestamp - start)) + " ms, timeout: "
                + timeout + " ms, request: " + request + ", channel: " + channel.getLocalAddress()
                + " -> " + channel.getRemoteAddress();
    }

    private static class RemotingInvocationTimeoutScan implements Runnable {

        @Override
        public void run() {
            while (true) {      // 启动定时器，检查当前进程内的
                try {
                    for (DefaultFuture future : FUTURES.values()) {
                        if (future == null || future.isDone()) {
                            continue;
                        }
                        // 超时
                        if (System.currentTimeMillis() - future.getStartTimestamp() > future.getTimeout()) {
                            // create exception response.    创建响应对象
                            Response timeoutResponse = new Response(future.getId());
                            // set timeout status. 超时状态
                            timeoutResponse.setStatus(future.isSent() ? Response.SERVER_TIMEOUT : Response.CLIENT_TIMEOUT);
                            timeoutResponse.setErrorMessage(future.getTimeoutMessage(true));        // 超时信息写入
                            // handle response.
                            DefaultFuture.received(future.getChannel(), timeoutResponse);       // 唤醒阻塞在Future.get()的线程
                        }
                    }
                    Thread.sleep(30);
                } catch (Throwable e) {
                    logger.error("Exception when scan the timeout invocation of remoting.", e);
                }
            }
        }
    }

}
