package kh.mentor.trading.paper;

public record ClosePaperRequest(double exitPrice, String exitReason,
    String reflection, boolean confirmPaperOnly) {}
