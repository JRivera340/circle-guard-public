import pytest
import os

# ── Fixtures globales para E2E tests ─────────────────────────────────────────

def pytest_addoption(parser):
    parser.addoption("--base-url",    default="http://localhost:8180", help="Auth service base URL")
    parser.addoption("--gateway-url", default="http://localhost:8087", help="Gateway service base URL")
    parser.addoption("--form-url",    default="http://localhost:8086", help="Form service base URL")
    parser.addoption("--promotion-url", default="http://localhost:8088", help="Promotion service base URL")


@pytest.fixture(scope="session", autouse=True)
def set_env_from_options(request):
    """Inyecta URLs como variables de entorno desde CLI o defaults"""
    os.environ.setdefault("AUTH_URL",      request.config.getoption("--base-url"))
    os.environ.setdefault("GATEWAY_URL",   request.config.getoption("--gateway-url"))
    os.environ.setdefault("FORM_URL",      request.config.getoption("--form-url"))
    os.environ.setdefault("PROMOTION_URL", request.config.getoption("--promotion-url"))


@pytest.fixture(scope="session")
def base_urls():
    return {
        "auth":      os.getenv("AUTH_URL",      "http://localhost:8180"),
        "gateway":   os.getenv("GATEWAY_URL",   "http://localhost:8087"),
        "form":      os.getenv("FORM_URL",      "http://localhost:8086"),
        "promotion": os.getenv("PROMOTION_URL", "http://localhost:8088"),
    }
