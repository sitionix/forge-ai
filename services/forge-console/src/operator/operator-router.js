export class OperatorRouter {
  constructor(registry, options = {}) {
    this.registry = registry;
    this.document = options.document || document;
    this.currentPage = null;
  }

  mount(pageName) {
    this.dispose();
    const factory = this.registry[pageName];
    if (!factory) {
      return null;
    }
    this.currentPage = factory();
    this.currentPage.mount();
    return this.currentPage;
  }

  dispose() {
    if (this.currentPage?.dispose) {
      this.currentPage.dispose();
    }
    this.currentPage = null;
  }
}

