package com.rupeek.lender.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(content = JsonInclude.Include.NON_NULL, value = JsonInclude.Include.NON_EMPTY)
public class ResponseMap {
    private String customerAcceptanceStatus;
    private String customerBlacklistStatus;
    private ResponseMapStatus axisPreCheckStatus;
    private ResponseMapStatus dedupe;
    private ResponseMapStatus pan;
    private ResponseMapStatus ramp;
    private ResponseMapStatus hunter;
    private ResponseMapStatus timble;
}
