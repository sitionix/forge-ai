import { RequestCoordinator } from './request-coordinator.js';

/** Owns one project/revision generation; provider selection remains in Knowledge. */
export class AiLibraryController {
  constructor({ api, onChange }) {
    this.api = api; this.onChange = onChange;
    this.requests = new RequestCoordinator(); this.generation = 0; this.profileRevision = undefined;
    this.state = { projectId: null, snapshot: null, loading: false, error: '', generation: 0 };
  }
  invalidate() {
    this.generation += 1; this.requests.abort('library'); this.pending = null;
    this.state = { ...this.state, snapshot: null, loading: false, error: '', generation: this.generation };
    this.onChange(this.state);
  }
  observeProfileRevision(revision) {
    const changed = this.profileRevision !== undefined && this.profileRevision !== revision
      || Boolean(this.state.snapshot && this.state.snapshot.profileRevision !== revision);
    this.profileRevision = revision;
    if (changed) this.invalidate();
    return changed;
  }
  load(projectId, { force = false } = {}) {
    if (this.disposed) return Promise.resolve(null);
    if (this.state.projectId !== projectId || force) this.invalidate();
    if (this.pending && this.state.projectId === projectId) return this.pending;
    if (this.state.snapshot && Date.now() - this.loadedAt < 30000) return Promise.resolve(this.state.snapshot);
    const generation = this.generation;
    this.state = { ...this.state, projectId, loading: true, error: '', snapshot: null };
    this.onChange(this.state);
    this.pending = this.requests.run('library', ({ signal }) => this.api.getAiLibrary(projectId, { signal }))
      .then((result) => {
        if (!result.applied || this.disposed || generation !== this.generation) return null;
        const snapshot = result.value;
        if (snapshot?.projectId !== projectId) throw new Error('Inventory context changed. Refresh to try again.');
        if (this.profileRevision !== undefined && snapshot.profileRevision !== this.profileRevision) {
          throw new Error('Active profile changed. Refresh inventory.');
        }
        this.state = { ...this.state, snapshot }; this.loadedAt = Date.now(); return snapshot;
      })
      .catch(() => {
        if (!this.disposed && generation === this.generation) this.state = { ...this.state, error: 'Inventory unavailable. Refresh to try again.', snapshot: null };
        return null;
      })
      .finally(() => {
        if (!this.disposed && generation === this.generation) {
          this.pending = null; this.state = { ...this.state, loading: false }; this.onChange(this.state);
        }
      });
    return this.pending;
  }
  dispose() { this.disposed = true; this.requests.dispose(); this.pending = null; }
}
