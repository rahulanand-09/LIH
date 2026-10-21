package com.rupeek.l1.intercept;

/** Persists audit records. Must not block the call for long and must never throw into it. */
public interface AuditWriter {

    void write(AuditRecord record);
}
