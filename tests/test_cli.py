"""Tests for the CLI bootstrap without Telegram or API credentials."""

from guitar_mentor.cli import main


def test_check_reports_ready(capsys):
    assert main(["--check"]) == 0
    out = capsys.readouterr().out
    assert "OK: Guitar Mentor AI bootstrap is ready." in out


def test_default_command_explains_next_step(capsys):
    assert main([]) == 0
    out = capsys.readouterr().out
    assert "DEV-02" in out
