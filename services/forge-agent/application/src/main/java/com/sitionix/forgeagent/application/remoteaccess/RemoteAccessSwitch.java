package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.RemoteAccessSwitchState;
import com.sitionix.forgeagent.domain.model.RemoteAccessSwitchStatus;
import com.sitionix.forgeagent.domain.port.RemoteAccessSwitchRepository;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Serializes local admission with a persisted disable fence. */
@Service
@RequiredArgsConstructor
public class RemoteAccessSwitch {
    private final RemoteAccessSwitchRepository repository;
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);

    public RemoteAccessSwitchState status() { return repository.get(); }

    public <T> T admit(Supplier<T> action) {
        gate.readLock().lock();
        try {
            if (repository.get().status() != RemoteAccessSwitchStatus.ENABLED) {
                throw new ConflictException("REMOTE_ACCESS_DISABLED", "Enable Remote Access before starting a session");
            }
            return action.get();
        } finally {
            gate.readLock().unlock();
        }
    }

    public void admit(Runnable action) { admit(() -> { action.run(); return null; }); }

    public RemoteAccessSwitchState enable() {
        return change(RemoteAccessSwitchStatus.DISABLED, RemoteAccessSwitchState::enable);
    }

    public RemoteAccessSwitchState beginDisable() {
        return change(RemoteAccessSwitchStatus.ENABLED, RemoteAccessSwitchState::beginDisable);
    }

    public RemoteAccessSwitchState finishDisable() {
        return change(RemoteAccessSwitchStatus.DISABLING, RemoteAccessSwitchState::finishDisable);
    }

    private RemoteAccessSwitchState change(RemoteAccessSwitchStatus from,
                                           Function<RemoteAccessSwitchState, RemoteAccessSwitchState> transition) {
        gate.writeLock().lock();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                var current = repository.get();
                if (current.status() != from) return current;
                var next = transition.apply(current);
                if (repository.transition(current, next)) return next;
            }
            throw new IllegalStateException("Remote Access state changed concurrently");
        } finally {
            gate.writeLock().unlock();
        }
    }
}
