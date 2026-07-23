from pathlib import Path

import pytest

from ar_signal_analysis import Trace, load_trace


@pytest.fixture
def fixture_path() -> Path:
    return Path(__file__).parent / "fixtures" / "synthetic_minimal.ndjson"


@pytest.fixture
def trace(fixture_path: Path) -> Trace:
    return load_trace(fixture_path)
