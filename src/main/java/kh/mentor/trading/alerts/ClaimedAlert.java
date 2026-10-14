package kh.mentor.trading.alerts;

/** A short-lived claim protected by a DB compare-and-swap on attempt count. */
public record ClaimedAlert(String id, String payload, int attempts) {}
