package com.rupeek.lih;

import com.rupeek.lender.entity.PrecheckEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** Same LenderCustomerPrecheck collection lender-core reads and writes. */
public interface PrecheckRepository extends MongoRepository<PrecheckEntity, String> {
    Optional<PrecheckEntity> findByLoanRequestIdAndLenderSlug(String loanRequestId, String lenderSlug);

    Optional<PrecheckEntity> findByLoanRequestIdAndLenderSlugAndLoanProcess(String loanRequestId, String lenderSlug, String loanProcess);
}
