export class RequestCoordinator {
  constructor() {
    this.requests = new Map();
    this.sequences = new Map();
    this.disposed = false;
  }

  run(resourceKey, executor, options = {}) {
    if (this.disposed) {
      return Promise.resolve({ applied: false, disposed: true, value: null });
    }
    const previous = this.requests.get(resourceKey);
    if (options.abortPrevious !== false && previous) {
      previous.controller.abort();
    }
    const sequence = (this.sequences.get(resourceKey) || 0) + 1;
    this.sequences.set(resourceKey, sequence);
    const controller = new AbortController();
    const record = { sequence, controller };
    this.requests.set(resourceKey, record);

    const context = {
      signal: controller.signal,
      sequence,
      isCurrent: () => this.isCurrent(resourceKey, sequence)
    };

    return Promise.resolve()
      .then(() => executor(context))
      .then((value) => {
        if (!this.isCurrent(resourceKey, sequence)) {
          return { applied: false, stale: true, value };
        }
        return { applied: true, value, sequence };
      })
      .catch((error) => {
        if (error?.name === 'AbortError' || !this.isCurrent(resourceKey, sequence)) {
          return { applied: false, aborted: error?.name === 'AbortError', stale: true, error };
        }
        throw error;
      })
      .finally(() => {
        const current = this.requests.get(resourceKey);
        if (current === record) {
          this.requests.delete(resourceKey);
        }
      });
  }

  isCurrent(resourceKey, sequence) {
    if (this.disposed) {
      return false;
    }
    return this.requests.get(resourceKey)?.sequence === sequence;
  }

  abort(resourceKey) {
    const current = this.requests.get(resourceKey);
    if (current) {
      current.controller.abort();
      this.requests.delete(resourceKey);
    }
  }

  dispose() {
    this.disposed = true;
    this.requests.forEach((request) => request.controller.abort());
    this.requests.clear();
    this.sequences.clear();
  }
}
