package gg.fotia.fotiavillage.database;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** 合并同一查询的并发请求；失效前发出的旧请求不会重新填充缓存。 */
final class AsyncQueryCache<K, V> {
    private final TimedCache<K, V> values = new TimedCache<>();
    private final TimedCache<K, V> latest = new TimedCache<>();
    private final Map<K, CompletableFuture<V>> pending = new HashMap<>();
    private long ttlMillis;
    private long snapshotMillis;

    synchronized void configure(int maximum, long ttlMillis, long snapshotMillis) {
        this.ttlMillis = ttlMillis;
        this.snapshotMillis = Math.max(ttlMillis, snapshotMillis);
        values.configure(maximum);
        latest.configure(maximum);
        clear();
    }

    synchronized CompletableFuture<V> load(K key, Supplier<CompletableFuture<V>> loader) {
        V cached = values.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<V> existing = pending.get(key);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<V> result = new CompletableFuture<>();
        pending.put(key, result);
        try {
            loader.get().whenComplete((value, error) -> {
                synchronized (this) {
                    if (pending.remove(key, result) && error == null) {
                        values.put(key, value, ttlMillis);
                        latest.put(key, value, snapshotMillis);
                    }
                }
                if (error == null) result.complete(value);
                else result.completeExceptionally(error);
            });
        } catch (RuntimeException ex) {
            pending.remove(key, result);
            result.completeExceptionally(ex);
        }
        return result;
    }

    V peekOrLoad(K key, Supplier<CompletableFuture<V>> loader, V fallback) {
        V previous = latest.get(key);
        V placeholder = previous == null ? fallback : previous;
        CompletableFuture<V> future = load(key, loader);
        try {
            return future.getNow(placeholder);
        } catch (CompletionException ex) {
            return placeholder;
        }
    }

    synchronized void invalidate(K key) {
        values.invalidate(key);
        // 异步刷新期间继续显示上一份结果；管理重置仍通过 clear 清除所有快照。
        pending.remove(key);
    }

    synchronized void clear() {
        values.clear();
        latest.clear();
        pending.clear();
    }

    void purgeExpired() {
        values.purgeExpired();
        latest.purgeExpired();
    }
}
