/*
 * Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.tools.generator.generic.contributor.thread;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class LazyScheduledExecutor implements ScheduledExecutorService, AutoCloseable {
    private final Supplier<ScheduledExecutorService> factory;
    private volatile ScheduledExecutorService delegate;

    public LazyScheduledExecutor(final Supplier<ScheduledExecutorService> factory) {
        this.factory = factory;
    }

    private ScheduledExecutorService delegate() {
        if (delegate != null) {
            return delegate;
        }
        synchronized (this) {
            if (delegate != null) {
                return delegate;
            }
            delegate = factory.get();
        }
        return delegate;
    }

    @Override
    public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
        return delegate().schedule(command, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(final Callable<V> callable, final long delay, final TimeUnit unit) {
        return delegate().schedule(callable, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command, final long initialDelay, final long period, final TimeUnit unit) {
        return delegate().scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command, final long initialDelay, final long delay, final TimeUnit unit) {
        return delegate().scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    @Override
    public void shutdown() {
        if (delegate != null) {
            delegate().shutdown();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        if (delegate == null) {
            return List.of();
        }
        return delegate().shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        if (delegate == null) {
            return false;
        }
        return delegate().isShutdown();
    }

    @Override
    public boolean isTerminated() {
        if (delegate == null) {
            return false;
        }
        return delegate().isTerminated();
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
        if (delegate == null) {
            return true;
        }
        return delegate().awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(final Callable<T> task) {
        return delegate().submit(task);
    }

    @Override
    public <T> Future<T> submit(final Runnable task, final T result) {
        return delegate().submit(task, result);
    }

    @Override
    public Future<?> submit(final Runnable task) {
        return delegate().submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate().invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) throws InterruptedException {
        return delegate().invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(final Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate().invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate().invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(final Runnable command) {
        delegate().execute(command);
    }

    @Override
    public void close() throws Exception {
        if (delegate == null) {
            return;
        }
        if (delegate instanceof AutoCloseable a) {
            a.close();
        } else {
            shutdown();
            awaitTermination(1, TimeUnit.HOURS);
        }
    }
}
