package com.rupeek.lender.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rupeek.lender.constants.PrecheckStatus;
import com.rupeek.lender.dto.LenderStpStatus;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.ArrayList;
import java.util.List;


@Builder
@Data
@Document(collection = "LenderCustomerPrecheck")
public class PrecheckEntity extends AbstractEntity{

    @Indexed(unique=true)
    private String loanRequestId;

    @Indexed(unique=true)
    private String customerId;

    @Field("precheckStatus")
    @Builder.Default
    private PrecheckStatus precheckStatus = PrecheckStatus.no_action;

    private String lenderSlug;

    private String lenderCustomerId;

    private String rejectionReason;

    @Field("responseMap")
    private ResponseMap responseMap;

    private boolean approved;

    private Retry retry;

    private String overallstpstatus;

    private String unsecurestpstatus;

    private String stpstatus;

    private List<LenderStpStatus> lenderstpstatus = new ArrayList<>();

    private String autofundtransferstatus;
    private String applicantId;

    @JsonProperty("client_loan_id")
    private String clientLoanId;

    @Field("loan_process")
    private String loanProcess;

    @Field("reference_id")
    private String timbleReferenceId ;

}
