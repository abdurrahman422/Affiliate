import re
from urllib.parse import quote, urlparse

_SAFE_FILENAME = re.compile(r"[^A-Za-z0-9._ -]+")
_UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)


def is_uuid(value: str) -> bool:
    return bool(_UUID_RE.match(value or ""))


def normalize_base_url(url: str) -> str:
    return (url or "").strip().rstrip("/").lower()


def host_of(url: str) -> str:
    parsed = urlparse(url)
    return (parsed.hostname or "").lower()


def content_disposition(filename: str) -> str:
    cleaned = sanitize_filename(filename)
    ascii_name = cleaned.encode("ascii", "ignore").decode("ascii") or "download"
    return f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{quote(cleaned)}"


def sanitize_filename(filename: str) -> str:
    name = (filename or "download").replace("\\", "/").split("/")[-1]
    name = name.replace("\x00", "")
    name = _SAFE_FILENAME.sub("_", name).strip(" .")
    return name[:180] or "download"


def pairing_token_entropy_ok(token: str) -> bool:
    if not token or not isinstance(token, str):
        return False
    compact = token.strip()
    if len(compact) < 20:
        return False
    return True
