package com.learnhub.interaction.like;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

@Mapper
public interface LikeSyncBatchMapper extends BaseMapper<LikeSyncBatchEntity> {
    @Update("UPDATE like_sync_batch SET status = 'SUCCESS', updated_at = #{updatedAt} WHERE batch_id = #{batchId}")
    int markSuccess(@Param("batchId") String batchId, @Param("updatedAt") LocalDateTime updatedAt);
}
