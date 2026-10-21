package com.rupeek.l1.ops;

import com.rupeek.l1.model.OperationDef;

/**
 * An operation after its executor validated it and compiled its kind-specific templates. Built once per
 * operation version and reused for every call.
 */
public interface PreparedOperation {

    OperationDef def();
}
