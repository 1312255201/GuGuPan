package cn.gugupan;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RequestLimiter {
    private record Window(long start, int count) {}
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private static final long WINDOW_MS = Duration.ofMinutes(10).toMillis();

    public void check(String key, int limit) {
        long now = System.currentTimeMillis();
        // The capacity guard bounds memory even when clients use arbitrary session IDs.
        if (windows.size() >= 20_000 && !windows.containsKey(key)) {
            throw new ApiException(429, "请求较多，请稍后再试");
        }
        Window result = windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.start() >= WINDOW_MS) {
                return new Window(now, 1);
            }
            return new Window(current.start(), Math.min(current.count() + 1, limit + 1));
        });
        if (result.count() > limit) {
            throw new ApiException(429, "操作过于频繁，请稍后再试");
        }
    }

    @Scheduled(fixedDelay = 600_000)
    void prune() {
        long before = System.currentTimeMillis() - WINDOW_MS;
        windows.entrySet().removeIf(entry -> entry.getValue().start() < before);
    }
}
