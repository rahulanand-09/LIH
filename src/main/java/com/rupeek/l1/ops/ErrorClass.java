package com.rupeek.l1.ops;

import com.rupeek.l1.api.L1FailureType;

/** How a failed operation is classified. Recipes route on these names in {@code onError}. */
public enum ErrorClass {
    /** Safe to retry: nothing reached the lender, or the call is idempotent. */
    TECHNICAL(L1FailureType.L1_TECHNICAL),
    /** The lender answered and said no. */
    BUSINESS(L1FailureType.L1_BUSINESS),
    /** A non-idempotent call may have reached the lender; never retried blindly. */
    UNKNOWN(L1FailureType.L1_UNKNOWN),
    /** Config or data error; the same input fails the same way. */
    PERMANENT(L1FailureType.L1_PERMANENT);

    private final L1FailureType failureType;

    ErrorClass(L1FailureType failureType) {
        this.failureType = failureType;
    }

    public L1FailureType failureType() {
        return failureType;
    }
}
