package com.rupeek.lender.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CommonBaseResponse {

    public CommonBaseResponse() {}

    private String message;

    private String status;
}
