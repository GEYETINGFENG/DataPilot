import pytest

from helpers import FakeBackend


@pytest.fixture
def backend():
    fake = FakeBackend()
    yield fake
    fake.close()
