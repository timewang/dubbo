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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.FORKS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_FORKS;

/**
 * NOTICE! This implementation does not work well with async call.
 *
 * Invoke a specific number of invokers concurrently, usually used for demanding real-time operations, but need to waste more service resources.
 *ForkingClusterInvoker 会在运行时通过线程池创建多个线程，并发调用多个服务提供者。只要有一个服务提供者成功返回了结果，doInvoke 方法就会立即结束运行。
 * ForkingClusterInvoker 的应用场景是在一些对实时性要求比较高读操作（注意是读操作，并行写操作可能不安全）下使用，但这将会耗费更多的资源。
 * <a href="http://en.wikipedia.org/wiki/Fork_(topology)">Fork</a>
 */
public class ForkingClusterInvoker<T> extends AbstractClusterInvoker<T> {

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link org.apache.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link org.apache.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(
            new NamedInternalThreadFactory("forking-cluster-timer", true));

    public ForkingClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            checkInvokers(invokers, invocation);
            final List<Invoker<T>> selected;
            final int forks = getUrl().getParameter(FORKS_KEY, DEFAULT_FORKS);// 获取 forks 配置
            final int timeout = getUrl().getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);// 获取超时配置
            if (forks <= 0 || forks >= invokers.size()) {// 如果 forks 配置不合理，则直接将 invokers 赋值给 selected
                selected = invokers;
            } else {
                selected = new ArrayList<>();
                // 循环选出 forks 个 Invoker，并添加到 selected 中
                for (int i = 0; i < forks; i++) {
                    Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);
                    if (!selected.contains(invoker)) {
                        //Avoid add the same invoker several times.
                        selected.add(invoker);
                    }
                }
            }
            RpcContext.getContext().setInvokers((List) selected);
            final AtomicInteger count = new AtomicInteger();
            final BlockingQueue<Object> ref = new LinkedBlockingQueue<>();
            for (final Invoker<T> invoker : selected) {
                executor.execute(() -> {// 为每个 Invoker 创建一个执行线程
                    try {
                        Result result = invoker.invoke(invocation);
                        ref.offer(result);// 将结果存到阻塞队列中
                    } catch (Throwable e) {
                        int value = count.incrementAndGet();
                        // 仅在 value 大于等于 selected.size() 时，才将异常对象
                        // 放入阻塞队列中，请大家思考一下为什么要这样做。
                        if (value >= selected.size()) {
                            ref.offer(e);
                        }
                    }
                });
            }
            try {
                // 从阻塞队列中取出远程调用结果
                Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);
                if (ret instanceof Throwable) {// 如果结果类型为 Throwable，则抛出异常
                    Throwable e = (Throwable) ret;
                    throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
                }
                return (Result) ret;
            } catch (InterruptedException e) {
                throw new RpcException("Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e);
            }
        } finally {
            // clear attachments which is binding to current thread.
            RpcContext.getContext().clearAttachments();
        }
    }
}
