package com.learnhub.marketing.coupon;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface CouponClaimMapper extends BaseMapper<CouponClaimEntity> {
    @Select("""
            SELECT cc.id AS claim_id, c.id AS coupon_id, c.name, c.type, c.discount_amount, c.threshold_amount,
                   c.use_start_at, c.use_end_at, cc.claimed_at,
                   CASE WHEN cc.status = 'AVAILABLE' AND c.use_end_at <= NOW(3) THEN 'EXPIRED'
                        WHEN cc.status = 'AVAILABLE' AND c.use_start_at > NOW(3) THEN 'UPCOMING'
                        ELSE cc.status END AS status
            FROM coupon_claim cc JOIN coupon c ON c.id = cc.coupon_id
            WHERE cc.user_id = #{userId}
            UNION ALL
            SELECT NULL AS claim_id, c.id AS coupon_id, c.name, c.type, c.discount_amount, c.threshold_amount,
                   c.use_start_at, c.use_end_at, so.reserved_at AS claimed_at,
                   CASE WHEN so.status = 'PENDING' THEN 'RESERVED' ELSE 'REJECTED' END AS status
            FROM seckill_order so JOIN coupon c ON c.id = so.coupon_id
            WHERE so.user_id = #{userId} AND so.status IN ('PENDING', 'FAILED')
              AND NOT EXISTS (SELECT 1 FROM coupon_claim cc
                              WHERE cc.coupon_id = so.coupon_id AND cc.user_id = so.user_id)
            ORDER BY claimed_at DESC, coupon_id DESC
            """)
    List<MyCoupon> selectMyCoupons(@Param("userId") Long userId);

    @Select("""
            SELECT * FROM coupon_claim
            WHERE id = #{claimId} AND user_id = #{userId}
            FOR UPDATE
            """)
    CouponClaimEntity selectOwnedForUpdate(@Param("claimId") Long claimId, @Param("userId") Long userId);
}
