"""CLI entry point; no network calls or external credentials needed at this stage."""

import argparse
from collections.abc import Sequence

from guitar_mentor import __version__
from guitar_mentor.config import Settings


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Guitar Mentor AI bootstrap")
    parser.add_argument(
        "--version",
        action="version",
        version=f"%(prog)s {__version__}",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="check that the local application can start",
    )
    args = parser.parse_args(argv)

    # Intentionally never print tokens or full settings.
    Settings.from_env()

    if args.check:
        print("OK: Guitar Mentor AI bootstrap is ready.")
    else:
        print("Guitar Mentor AI is initialized; Telegram integration comes in DEV-02.")
    return 0
