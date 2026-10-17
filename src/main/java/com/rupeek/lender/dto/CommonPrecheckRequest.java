package com.rupeek.lender.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CommonPrecheckRequest {

	@NotNull
	private String loanreqID;

	@NotNull
	private String lenderSlug;

	private String loanProcess;

}
