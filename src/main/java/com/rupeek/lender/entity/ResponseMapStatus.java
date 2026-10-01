package com.rupeek.lender.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseMapStatus {
    private String status;
    private String statusMessage;
}
