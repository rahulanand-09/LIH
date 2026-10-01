package com.rupeek.lih.l2;

import java.util.Map;

/** L1 stub: one lender API operation. Real L1 (config store, validation) comes later. */
public class OperationDef {
    public String protocol = "REST";       // REST | SOAP | SFTP
    public String method = "POST";
    public String url;
    public Map<String, String> query;
    public Map<String, String> headers;
    public String transformer;             // named code bean that builds the request body from flow vars
    public Map<String, String> extract;    // name -> SpEL over the response map; absent = whole response
}
