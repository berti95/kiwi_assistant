import hmac

from .settings import settings


def is_valid_api_key(candidate: str | None) -> bool:
    """Constant-time check against the configured KIWI_API_KEY.

    If `KIWI_API_KEY` is unset we deny every request rather than allowing
    any key — this keeps a misconfigured deployment closed.
    """
    expected = settings.kiwi_api_key
    if not expected or not candidate:
        return False
    return hmac.compare_digest(candidate, expected)
