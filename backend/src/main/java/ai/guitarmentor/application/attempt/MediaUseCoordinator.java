package ai.guitarmentor.application.attempt;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/** Single JVM / single local storage owner; not a distributed lease. */
@Component
public class MediaUseCoordinator {
    private final ReentrantLock lock = new ReentrantLock(true);
    public void acquire() { lock.lock(); }
    public boolean tryAcquire() { return lock.tryLock(); }
    public void release() { lock.unlock(); }
}
