package com.learnhub.marketing.coupon;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CouponMapper extends BaseMapper<CouponEntity> {
    @Update("""
            UPDATE coupon
            SET available_stock = available_stock - 1,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{couponId}
              AND status = 'ACTIVE'
              AND available_stock > 0
            """)
    int decrementAvailableStock(@Param("couponId") Long couponId);
}
