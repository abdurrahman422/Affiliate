import base64
import hashlib
import hmac

import pytest

from app.bootstrap import validate_runtime_settings
from app.turn import create_ice_configuration
from tests.conftest import make_settings


def test_stun_only_configuration_has_no_credentials(tmp_path):
    settings = make_settings(tmp_path)
    value = create_ice_configuration(settings, "contact", now=1000)
    assert value["expires_at"] == 0
    assert value["ice_servers"] == [{"urls": ["stun:stun.l.google.com:19302"]}]


def test_coturn_temporary_credentials_are_hmac_and_short_lived(tmp_path):
    settings = make_settings(tmp_path)
    settings.TURN_URLS = "turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp,turns:turn.example.com:5349"
    settings.TURN_SHARED_SECRET = "server-only-shared-secret"
    settings.TURN_CREDENTIAL_TTL = 600
    value = create_ice_configuration(settings, "alice/contact", now=1000)
    relay = value["ice_servers"][1]
    assert value["expires_at"] == 1600
    assert relay["username"] == "1600:alicecontact"
    expected = base64.b64encode(hmac.new(settings.TURN_SHARED_SECRET.encode(), relay["username"].encode(), hashlib.sha1).digest()).decode()
    assert hmac.compare_digest(relay["credential"], expected)
    assert len(relay["urls"]) == 3


def test_turn_urls_require_secret(tmp_path):
    settings = make_settings(tmp_path)
    settings.TURN_URLS = "turn:turn.example.com:3478"
    with pytest.raises(SystemExit):
        validate_runtime_settings(settings)
