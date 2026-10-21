package com.rupeek.l1.registry;

import java.time.Instant;

/**
 * Forces cases pinned to {@code ref@fromVersion} onto {@code toVersion} (e.g. to stop a broken version). Append
 * only: the newest redirect for a version wins; a redirect to the same version undoes earlier ones.
 */
public record Redirect(String ref, int fromVersion, int toVersion, String by, Instant at, String reason) {
}
