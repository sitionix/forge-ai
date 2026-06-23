export class PollingCoordinator {
  constructor(options) {
    this.poll = options.poll;
    this.isActive = options.isActive || (() => false);
    this.activeIntervalMs = options.activeIntervalMs ?? 2000;
    this.idleIntervalMs = options.idleIntervalMs ?? null;
    this.hiddenIntervalMs = options.hiddenIntervalMs ?? null;
    this.document = options.document || document;
    this.setTimeout = options.setTimeout || setTimeout;
    this.clearTimeout = options.clearTimeout || clearTimeout;
    this.timerId = null;
    this.inFlight = false;
    this.running = false;
    this.disposed = false;
    this.lastResult = null;
    this.timerCount = 0;
    this.maxConcurrent = 0;
    this.activeCount = 0;
    this.visibilityListener = () => this.handleVisibilityChange();
  }

  start(options = {}) {
    if (this.disposed || this.running) {
      return;
    }
    this.running = true;
    this.document.addEventListener?.('visibilitychange', this.visibilityListener);
    if (options.immediate !== false) {
      this.tick('initial');
    } else {
      this.schedule();
    }
  }

  stop() {
    this.running = false;
    this.clearTimer();
    this.document.removeEventListener?.('visibilitychange', this.visibilityListener);
  }

  dispose() {
    this.disposed = true;
    this.stop();
  }

  async tick(caller = 'poll') {
    if (!this.running || this.disposed || this.inFlight || this.isHiddenPaused()) {
      return null;
    }
    this.clearTimer();
    this.inFlight = true;
    this.activeCount += 1;
    this.maxConcurrent = Math.max(this.maxConcurrent, this.activeCount);
    try {
      const result = await this.poll({ caller });
      this.lastResult = result;
      return result;
    } finally {
      this.activeCount = Math.max(0, this.activeCount - 1);
      this.inFlight = false;
      if (this.running && !this.disposed) {
        this.schedule();
      }
    }
  }

  schedule() {
    this.clearTimer();
    if (!this.running || this.disposed) {
      return;
    }
    const interval = this.nextInterval();
    if (!Number.isFinite(interval) || interval < 0) {
      return;
    }
    this.timerCount += 1;
    this.timerId = this.setTimeout(() => {
      this.timerId = null;
      this.tick('scheduled');
    }, interval);
  }

  nextInterval() {
    if (this.document.visibilityState === 'hidden') {
      return this.hiddenIntervalMs;
    }
    return this.isActive(this.lastResult) ? this.activeIntervalMs : this.idleIntervalMs;
  }

  handleVisibilityChange() {
    if (!this.running || this.disposed) {
      return;
    }
    if (this.document.visibilityState === 'hidden') {
      this.clearTimer();
      if (Number.isFinite(this.hiddenIntervalMs)) {
        this.schedule();
      }
      return;
    }
    if (!this.inFlight) {
      this.tick('visible');
    }
  }

  isHiddenPaused() {
    return this.document.visibilityState === 'hidden' && !Number.isFinite(this.hiddenIntervalMs);
  }

  clearTimer() {
    if (this.timerId) {
      this.clearTimeout(this.timerId);
      this.timerId = null;
    }
  }
}

