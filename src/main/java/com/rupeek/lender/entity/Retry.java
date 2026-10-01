package com.rupeek.lender.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Retry {
    private int counter = 1;
    private boolean available = true;
}
