package com.books.dubbo.demo.broadcast;

import org.apache.dubbo.common.json.JSON;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.cluster.support.AbstractClusterInvoker;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;


public class BroadcastClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(BroadcastClusterInvoker.class);
    private final ThreadPoolExecutor paramCallPool;

    public BroadcastClusterInvoker(Directory<T> directory) {
        super(directory);
        int poolSize = directory.getUrl().getParameter("broadCastPoolSize", 8);
        paramCallPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize, new NamedThreadFactory("PARA-CALL-POOL", true));
    }

    public void close() {
        try {
            if (paramCallPool != null) {
                ((ExecutorService) paramCallPool).shutdown();
            }
        } catch (Throwable t) {
            logger.warn("fail to destroy thread pool of server: " + t.getMessage(), t);
        }
    }


    public Result doInvokeParaCountDown(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        Map<String, Object> allResult = new ConcurrentHashMap<>();
        int machineNum = invokers.size();
        CountDownLatch countDownLatch = new CountDownLatch(machineNum);
        for (Invoker<T> invoker : invokers) {
            try {
                paramCallPool.execute(() -> {
                    try {
                        Result result = invoker.invoke(invocation);
                        String url = invoker.getUrl().getAddress();
                        allResult.put(url, result.getResult());
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    } finally {
                        countDownLatch.countDown();
                    }
                });
            } catch (Exception e) {
                logger.warn(e.getMessage(), e);
            }
        }
        // 等所有的完成
        try {
            countDownLatch.await(5000, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
            logger.error("wait sub thread over error:" + e.getLocalizedMessage());
        }

        Map finalResult = new HashMap<String, Result>();
        finalResult.put("machineNum", machineNum);
        finalResult.put("result", allResult);

        Result result;
        try {
            result = new RpcResult(JSON.json(finalResult));
        } catch (IOException e) {
            e.printStackTrace();
            result = new RpcResult(e);
        }

        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        checkInvokers(invokers, invocation);
        RpcContext.getContext().setInvokers((List) invokers);
        return doInvokeParaCountDown(invocation, invokers, loadbalance);
    }
}
