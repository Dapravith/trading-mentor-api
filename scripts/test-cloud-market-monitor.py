#!/usr/bin/env python3
"""Offline tests: no Binance, GitHub or Telegram network requests."""
import datetime as dt
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("cloud-market-monitor.py")
spec = importlib.util.spec_from_file_location("cloud_market_monitor", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
NOW = dt.datetime(2026, 10, 15, 12, 0, tzinfo=dt.timezone.utc)
CLOSE = int((NOW - dt.timedelta(hours=1)).timestamp() * 1000)


def fixture(signals=("BTCUSDT",)):
    def market(symbol):
        active = symbol in signals
        return {
            "symbol": symbol, "interval": "4h",
            "candleCloseMs": CLOSE, "lastCompletedClose": 100.0,
            "rsi14": 55, "atr14": 3, "volumeRatio": 1.5,
            "status": "PAPER_SETUP_WATCH" if active else "NO_TRADE",
            "rationale": ["Prior 20-candle breakout"] if active else ["RSI weak"],
            "paperRisk": {
                "valid": True, "entry": 100, "stop": 94, "target": 118,
                "paperBalanceUsdt": 1000, "positionUsdt": 100,
                "estimatedLossUsdt": 0.7, "quantity": 1,
                "netRewardRisk": 2.3
            } if active else None
        }
    return {
        "schema": 1, "generatedAtUtc": NOW.isoformat(),
        "strategyVersion": "breakout-v1",
        "analyses": [market("BTCUSDT"), market("ETHUSDT")]
    }


class CloudMonitorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.snapshot = root / "snapshot.json"
        self.state = root / "monitor-state.json"
        self.state.write_text(json.dumps({"schema": 1, "sent": {}}))
        self.receipts = []
        self.sent_messages = []

    def tearDown(self):
        self.tmp.cleanup()

    def sender(self, body, token, chat):
        self.sent_messages.append(body)
        return len(self.sent_messages) + 100

    def persist(self, path, state):
        self.receipts.append(json.loads(json.dumps(state)))
        path.write_text(json.dumps(state))

    def execute(self, signals=("BTCUSDT",), dry_run=False):
        self.snapshot.write_text(json.dumps(fixture(signals)))
        return mod.execute(self.snapshot, self.state, dry_run=dry_run, now=NOW,
                           sender=self.sender, persist=self.persist,
                           token="dummy", chat_id="@test")

    def test_qualified_signal_and_daily_sent_once_each(self):
        self.assertEqual(2, self.execute())
        self.assertEqual(2, len(self.sent_messages))
        self.assertEqual(2, len(self.receipts))
        self.assertIn("PAPER ENTRY WATCH", self.sent_messages[0])
        self.assertIn("EXIT if invalidated", self.sent_messages[0])
        self.assertIn("DAILY PAPER MARKET CHECK", self.sent_messages[1])
        self.assertEqual(0, self.execute())
        self.assertEqual(2, len(self.sent_messages))

    def test_no_trade_never_generates_entry(self):
        self.assertEqual(1, self.execute(signals=()))
        self.assertNotIn("PAPER ENTRY WATCH", self.sent_messages[0])
        self.assertIn("NO TRADE", self.sent_messages[0])

    def test_dry_run_does_not_send_or_update_state(self):
        self.assertEqual(2, self.execute(dry_run=True))
        self.assertEqual(0, len(self.sent_messages))
        self.assertEqual({}, json.loads(self.state.read_text())["sent"])

    def test_invalid_risk_and_stale_candle_fail_closed(self):
        s = fixture()
        s["analyses"][0]["paperRisk"]["netRewardRisk"] = 0.8
        with self.assertRaises(mod.MonitorError):
            mod.validate_snapshot(s, NOW)
        s = fixture()
        s["analyses"][0]["candleCloseMs"] = CLOSE - 12 * 60 * 60 * 1000
        with self.assertRaises(mod.MonitorError):
            mod.validate_snapshot(s, NOW)
        self.assertEqual(0, len(self.sent_messages))

    def test_duplicate_symbols_and_missing_state_do_not_send(self):
        s = fixture()
        s["analyses"][1]["symbol"] = "BTCUSDT"
        with self.assertRaises(mod.MonitorError):
            mod.validate_snapshot(s, NOW)
        self.state.unlink()
        with self.assertRaises(mod.MonitorError):
            self.execute()
        self.assertFalse(self.sent_messages)

    def test_bad_provider_ack_never_persists(self):
        self.snapshot.write_text(json.dumps(fixture()))
        def fail(_body, _token, _chat):
            raise mod.MonitorError("Telegram HTTP status 403")
        with self.assertRaises(mod.MonitorError):
            mod.execute(self.snapshot, self.state, now=NOW,
                        sender=fail, persist=self.persist,
                        token="dummy",chat_id="@test")
        self.assertEqual(0, len(self.receipts))
        self.assertEqual({}, json.loads(self.state.read_text())["sent"])

    def test_same_candle_different_run_is_not_resent(self):
        self.execute()
        # Simulate later scan within same bar: identical candle remains deduped.
        self.assertEqual(0,self.execute())
        self.assertEqual(2,len(self.sent_messages))


if __name__ == "__main__":
    unittest.main()
