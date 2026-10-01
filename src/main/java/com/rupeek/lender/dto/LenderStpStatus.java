package com.rupeek.lender.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LenderStpStatus {
    private String lenderSlug;
    private String stpstatus;
    private String loanType;
    private List<String> stprejectionreason = new ArrayList<>();
}
