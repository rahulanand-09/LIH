package com.rupeek.lih.l2;

import java.util.Map;

public interface ProtocolAdapter {
    /** Executes one operation and returns the response as a map. Throw only for transport failures. */
    Map<String, Object> execute(OperationDef op, Map<String, Object> request) throws Exception;
}
