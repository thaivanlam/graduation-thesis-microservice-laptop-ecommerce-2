package com.ecommerce.order_service.clientpayload;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockLine {
    private Long productId;
    private int quantity;
}
