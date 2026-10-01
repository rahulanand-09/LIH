package com.rupeek.lih.l2;

import java.util.HashMap;
import java.util.Map;

public final class Adapters {
    private Adapters() {}

    /** REST is real; SOAP and SFTP are registered names that fail loudly until implemented. */
    public static Map<String, ProtocolAdapter> defaults() {
        Map<String, ProtocolAdapter> m = new HashMap<>();
        m.put("REST", new RestAdapter());
        m.put("SOAP", unsupported("SOAP"));
        m.put("SFTP", unsupported("SFTP"));
        return m;
    }

    private static ProtocolAdapter unsupported(String name) {
        return (op, req) -> { throw new UnsupportedOperationException(name + " adapter not implemented yet"); };
    }
}
