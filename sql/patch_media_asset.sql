SET NAMES utf8mb4;
USE learnhub;

CREATE TABLE IF NOT EXISTS media_asset (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    owner_id BIGINT NOT NULL COMMENT '媒体资源所属用户编号，关联用户表',
    provider VARCHAR(32) NOT NULL COMMENT '媒体存储服务提供商编码，如ALIYUN_OSS',
    bucket_name VARCHAR(128) NOT NULL COMMENT '对象存储桶名称',
    object_key VARCHAR(512) NOT NULL COMMENT '对象存储文件键，在资源表内唯一',
    original_name VARCHAR(255) NOT NULL COMMENT '上传文件原始名称',
    content_type VARCHAR(128) NOT NULL COMMENT '文件媒体类型，即MIME类型',
    file_size BIGINT NOT NULL COMMENT '文件大小，单位为字节',
    etag VARCHAR(128) COMMENT '对象存储返回的文件实体标识',
    status VARCHAR(20) NOT NULL COMMENT '媒体资源状态：UPLOADING上传中、READY可用、FAILED失败',
    biz_type VARCHAR(32) NOT NULL COMMENT '媒体关联业务类型，如AVATAR头像、COURSE_LESSON课时视频',
    biz_id BIGINT COMMENT '媒体关联业务记录编号，可为空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',
    UNIQUE KEY uk_media_object_key (object_key),
    KEY idx_media_owner_created (owner_id, created_at),
    KEY idx_media_biz (biz_type, biz_id)
) COMMENT='媒体文件资源表';
