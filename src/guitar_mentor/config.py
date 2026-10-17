"""Configuration read from environment variables, not from source-code secrets."""

from dataclasses import dataclass
from os import environ
from pathlib import Path


@dataclass(frozen=True, slots=True)
class Settings:
    """Small bootstrap configuration; persistence and Telegram are added later."""

    data_dir: Path
    telegram_bot_token: str | None
    openai_api_key: str | None

    @classmethod
    def from_env(cls) -> "Settings":
        """Read runtime configuration; unset API keys are allowed during bootstrap."""
        return cls(
            data_dir=Path(environ.get("GUITAR_MENTOR_DATA_DIR", "./data")).expanduser(),
            telegram_bot_token=environ.get("TELEGRAM_BOT_TOKEN") or None,
            openai_api_key=environ.get("OPENAI_API_KEY") or None,
        )
