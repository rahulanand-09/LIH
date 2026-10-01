package com.rupeek.lender.dto.sib;

import com.rupeek.lender.constants.PrecheckStatus;
import com.rupeek.lender.dto.LenderStpStatus;
import com.rupeek.lender.entity.ResponseMap;
import com.rupeek.lender.entity.Retry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LenderPrecheckResponse {

    private String loanRequestId;

    private String customerId;

    private PrecheckStatus precheckStatus;

    private String lenderSlug;

    private String lenderCustomerId;

    private String rejectionReason;

    private ResponseMap responseMap;

    private boolean approved;

    private Retry retry;

    private String overallstpstatus;

    private String unsecurestpstatus;

    private String stpstatus;

    private List<LenderStpStatus> lenderstpstatus = new ArrayList<>();

    private String autofundtransferstatus;

    private String loanProcess;
}
