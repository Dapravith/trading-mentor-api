package kh.mentor.trading.news;

import java.util.List;

/** Structured Hermes submission: links/times are screened, content is not independently verified. */
public record NewsCandidate(
    String eventKey,
    String headline,
    String summary,
    String eventAtUtc,
    String publishedAtUtc,
    String impact,
    List<String> assets,
    String risk,
    String primarySourceUrl,
    String corroboratingSourceUrl
) {}
