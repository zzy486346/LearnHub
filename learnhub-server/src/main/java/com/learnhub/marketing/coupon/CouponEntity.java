package com.learnhub.marketing.coupon;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("coupon")
public class CouponEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String type;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private Integer totalStock;
    private Integer availableStock;
    private LocalDateTime claimStartAt;
    private LocalDateTime claimEndAt;
    private LocalDateTime useStartAt;
    private LocalDateTime useEndAt;
    private Integer perUserLimit;
    private String status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public BigDecimal getThresholdAmount() { return thresholdAmount; }
    public void setThresholdAmount(BigDecimal thresholdAmount) { this.thresholdAmount = thresholdAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public Integer getTotalStock() { return totalStock; }
    public void setTotalStock(Integer totalStock) { this.totalStock = totalStock; }
    public Integer getAvailableStock() { return availableStock; }
    public void setAvailableStock(Integer availableStock) { this.availableStock = availableStock; }
    public LocalDateTime getClaimStartAt() { return claimStartAt; }
    public void setClaimStartAt(LocalDateTime claimStartAt) { this.claimStartAt = claimStartAt; }
    public LocalDateTime getClaimEndAt() { return claimEndAt; }
    public void setClaimEndAt(LocalDateTime claimEndAt) { this.claimEndAt = claimEndAt; }
    public LocalDateTime getUseStartAt() { return useStartAt; }
    public void setUseStartAt(LocalDateTime useStartAt) { this.useStartAt = useStartAt; }
    public LocalDateTime getUseEndAt() { return useEndAt; }
    public void setUseEndAt(LocalDateTime useEndAt) { this.useEndAt = useEndAt; }
    public Integer getPerUserLimit() { return perUserLimit; }
    public void setPerUserLimit(Integer perUserLimit) { this.perUserLimit = perUserLimit; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
