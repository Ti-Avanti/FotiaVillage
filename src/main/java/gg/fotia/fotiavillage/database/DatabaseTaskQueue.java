package gg.fotia.fotiavillage.database;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 有界串行队列：写入失败保留队首重试，后续读写不会越过失败的事务。 */
final class DatabaseTaskQueue {
    private final Logger logger;
    private final Runnable closeStore;
    private final ArrayDeque<Job<?>> jobs = new ArrayDeque<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "FotiaVillage-database");
        thread.setDaemon(true);
        return thread;
    });
    private final Thread shutdownHook;
    private volatile int capacity;
    private volatile long retryMillis;
    private volatile long shutdownMillis;
    private volatile Throwable failure;
    private boolean draining;
    private CompletableFuture<Void> closing;
    private long closeDeadline;

    DatabaseTaskQueue(Logger logger, Runnable closeStore, int capacity, long retryMillis, long shutdownMillis) {
        this.logger = logger;
        this.closeStore = closeStore;
        configure(capacity, retryMillis, shutdownMillis);
        shutdownHook = new Thread(() -> {
            try {
                close().get(this.shutdownMillis + 1000L, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Database shutdown did not finish; check pending writes before restarting", ex);
            }
        }, "FotiaVillage-database-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    void configure(int capacity, long retryMillis, long shutdownMillis) {
        this.capacity = Math.max(1, capacity);
        this.retryMillis = Math.max(100L, retryMillis);
        this.shutdownMillis = Math.max(1000L, shutdownMillis);
    }

    synchronized <T> CompletableFuture<T> submit(Callable<T> action, boolean retry) {
        if (closing != null || jobs.size() >= capacity) {
            throw new RejectedExecutionException("FotiaVillage database queue is closed or full");
        }
        Job<T> job = new Job<>(action, retry);
        jobs.add(job);
        startDraining();
        return job.result;
    }

    boolean healthy() { return failure == null; }

    synchronized CompletableFuture<Void> close() {
        if (closing != null) return closing;
        closing = new CompletableFuture<>();
        closeDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownMillis);
        Job<Void> closeJob = new Job<>(() -> { closeStore.run(); return null; }, false);
        closeJob.result.whenComplete((value, error) -> {
            worker.shutdown();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM 正在退出，由当前 shutdown hook 等待写入完成。
            }
            if (error == null) closing.complete(null);
            else closing.completeExceptionally(error);
        });
        jobs.add(closeJob);
        startDraining();
        return closing;
    }

    private void startDraining() {
        if (!draining) {
            draining = true;
            worker.execute(this::drain);
        }
    }

    private void drain() {
        Job<?> job;
        synchronized (this) {
            job = jobs.peek();
            if (job == null) {
                draining = false;
                return;
            }
        }
        try {
            Object value = job.action.call();
            synchronized (this) { jobs.removeFirst(); }
            failure = null;
            job.complete(value);
        } catch (Exception ex) {
            if (job.retry) {
                if (failure == null) logger.log(Level.SEVERE, "Database write failed; new trades are paused while the queued transaction is retried", ex);
                failure = ex;
                if (shutdownTimedOut()) {
                    abortShutdown(ex);
                } else {
                    worker.schedule(this::drain, retryMillis, TimeUnit.MILLISECONDS);
                }
                return;
            }
            synchronized (this) { jobs.removeFirst(); }
            logger.log(Level.WARNING, "Database operation failed", ex);
            job.result.completeExceptionally(ex);
        }
        if (!worker.isShutdown()) worker.execute(this::drain);
    }

    private synchronized boolean shutdownTimedOut() {
        return closing != null && System.nanoTime() >= closeDeadline;
    }

    private void abortShutdown(Exception error) {
        List<Job<?>> pending;
        synchronized (this) {
            pending = new ArrayList<>(jobs);
            jobs.clear();
        }
        logger.severe("Database shutdown timed out with " + Math.max(0, pending.size() - 1) + " pending operations; data could not be saved");
        try {
            closeStore.run();
        } catch (RuntimeException closeError) {
            error.addSuppressed(closeError);
        }
        pending.forEach(job -> job.result.completeExceptionally(error));
        worker.shutdown();
    }

    private static final class Job<T> {
        final Callable<T> action;
        final boolean retry;
        final CompletableFuture<T> result = new CompletableFuture<>();

        Job(Callable<T> action, boolean retry) {
            this.action = action;
            this.retry = retry;
        }

        @SuppressWarnings("unchecked")
        void complete(Object value) { result.complete((T) value); }
    }
}
