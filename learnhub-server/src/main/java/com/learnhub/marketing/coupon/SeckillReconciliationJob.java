package com.learnhub.marketing.coupon;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "learnhub.seckill.reconciliation-enabled", havingValue = "true", matchIfMissing = true)
public class SeckillReconciliationJob {
    private final CouponService service;

    public SeckillReconciliationJob(CouponService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${learnhub.seckill.reconciliation-delay:10000}")
    public void reconcile() {
        service.reconcile(100);
    }
}
