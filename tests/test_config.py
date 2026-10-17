"""Smoke tests for secure environment-based settings."""

from pathlib import Path

from guitar_mentor.config import Settings


def test_missing_tokens_do_not_block_bootstrap(monkeypatch):
    monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.delenv("GUITAR_MENTOR_DATA_DIR", raising=False)
    settings = Settings.from_env()
    assert settings.telegram_bot_token is None
    assert settings.openai_api_key is None
    assert settings.data_dir == Path("data")


def test_environment_values_are_loaded(monkeypatch):
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "test-telegram-token")
    monkeypatch.setenv("OPENAI_API_KEY", "test-openai-key")
    monkeypatch.setenv("GUITAR_MENTOR_DATA_DIR", "temporary-data")
    settings = Settings.from_env()
    assert settings.telegram_bot_token == "test-telegram-token"
    assert settings.openai_api_key == "test-openai-key"
    assert settings.data_dir == Path("temporary-data")
