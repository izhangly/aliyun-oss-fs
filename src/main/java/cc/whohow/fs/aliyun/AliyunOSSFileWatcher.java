package cc.whohow.fs.aliyun;

import com.aliyun.oss.OSSErrorCode;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.SimplifiedObjectMeta;

import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * 轻量级文件监听器
 */
public class AliyunOSSFileWatcher implements Runnable {
    private final AliyunOSSFile file; // 文件
    private final BiFunction<WatchEvent.Kind<?>, AliyunOSSFileWatcher, Boolean> listener; // 回调
    private volatile Predicate<AliyunOSSFileWatcher> stopCondition; // 停止运行条件

    private volatile ScheduledFuture<?> future; // 任务
    private volatile long startTime; // 开始时间
    private final AtomicInteger runningCounter = new AtomicInteger(0); // 监听次数

    private volatile SimplifiedObjectMeta objectMeta; // 文件元数据

    public AliyunOSSFileWatcher(AliyunOSSFile file, BiFunction<WatchEvent.Kind<?>, AliyunOSSFileWatcher, Boolean> listener) {
        this.file = file;
        this.listener = listener;
    }

    /**
     * 监听文件
     */
    public AliyunOSSFile watchable() {
        return file;
    }

    /**
     * 设置停止条件
     */
    public AliyunOSSFileWatcher setStopCondition(Predicate<AliyunOSSFileWatcher> stopCondition) {
        if (this.stopCondition == null) {
            this.stopCondition = stopCondition;
        } else {
            this.stopCondition = this.stopCondition.or(stopCondition);
        }
        return this;
    }

    /**
     * 设置最大运行时间
     */
    public AliyunOSSFileWatcher setMaxRunningTime(long max, TimeUnit unit) {
        return setMaxRunningTime(unit.toMillis(max));
    }

    /**
     * 设置最大运行时间
     */
    public AliyunOSSFileWatcher setMaxRunningTime(long max) {
        return setStopCondition((watcher) -> watcher.getRunningTime() >= max);
    }

    /**
     * 设置最大运行次数
     */
    public AliyunOSSFileWatcher setMaxRunningCount(int max) {
        return setStopCondition((watcher) -> watcher.getRunningCounter() >= max);
    }

    /**
     * 开始
     */
    public AliyunOSSFileWatcher start(ScheduledExecutorService executor, long interval, TimeUnit unit) {
        if (this.stopCondition == null) {
            this.stopCondition = (watcher) -> false;
        }

        this.objectMeta = getObjectMeta();
        this.startTime = System.currentTimeMillis();
        this.future = executor.scheduleWithFixedDelay(this, 0, interval, unit);
        return this;
    }

    /**
     * 停止
     */
    public AliyunOSSFileWatcher stop() {
        this.future.cancel(true);
        return this;
    }

    @Override
    public void run() {
        if (stopCondition.test(this)) {
            stopAndClose();
            return;
        }

        runningCounter.getAndIncrement();

        SimplifiedObjectMeta prevObjectMeta = objectMeta;
        objectMeta = getObjectMeta();
        if (prevObjectMeta == null && objectMeta != null) {
            if(Boolean.FALSE.equals(listener.apply(StandardWatchEventKinds.ENTRY_CREATE, this))) {
                stopAndClose();
            }
        } else if (prevObjectMeta != null && objectMeta == null) {
            if (Boolean.FALSE.equals(listener.apply(StandardWatchEventKinds.ENTRY_DELETE, this))) {
                stopAndClose();
            }
        } else if (prevObjectMeta != null && objectMeta != null) {
            if (!Objects.equals(prevObjectMeta.getETag(), objectMeta.getETag())) {
                if (Boolean.FALSE.equals(listener.apply(StandardWatchEventKinds.ENTRY_MODIFY, this))) {
                    stopAndClose();
                }
            }
        }
    }

    /**
     * 运行时间
     */
    public long getRunningTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * 监听次数
     */
    public int getRunningCounter() {
        return runningCounter.get();
    }

    private SimplifiedObjectMeta getObjectMeta() {
        try {
            return file.getObjectMeta();
        } catch (OSSException e) {
            if (OSSErrorCode.NO_SUCH_BUCKET.equals(e.getErrorCode())
                    ||OSSErrorCode.NO_SUCH_KEY.equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private void stopAndClose() {
        try {
            stop();
        } finally {
            file.close();
        }
    }
}
