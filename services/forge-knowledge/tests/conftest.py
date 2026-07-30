from __future__ import annotations

import asyncio
import gc

import pytest

from support import drain_built_test_dependencies


@pytest.fixture(autouse=True)
def close_built_test_dependencies():
    yield
    for dependency in reversed(drain_built_test_dependencies()):
        asyncio.run(dependency.aclose())
    gc.collect()
