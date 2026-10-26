-- 追加迁移；保留原本地512维表及全部投影。使用PG迁移身份执行，不由Java运行账号建表。
CREATE TABLE IF NOT EXISTS learnhub_rag.course_embeddings_sf_bge_large_zh_v1 (
    embedding_id UUID PRIMARY KEY,
    embedding public.vector(1024) NOT NULL,
    text TEXT NOT NULL,
    metadata JSON NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE learnhub_rag.course_embeddings_sf_bge_large_zh_v1 IS 'SiliconFlow BAAI/bge-large-zh-v1.5课程向量投影，可由MySQL事实源重建';
COMMENT ON COLUMN learnhub_rag.course_embeddings_sf_bge_large_zh_v1.embedding_id IS '远程模型身份、文档、版本、知识块和分片派生的幂等UUID';
COMMENT ON COLUMN learnhub_rag.course_embeddings_sf_bge_large_zh_v1.embedding IS '远程BGE中文large模型1024维L2归一化向量，使用精确余弦距离';
COMMENT ON COLUMN learnhub_rag.course_embeddings_sf_bge_large_zh_v1.text IS '分片投影正文，回答仍须回MySQL复核';
COMMENT ON COLUMN learnhub_rag.course_embeddings_sf_bge_large_zh_v1.metadata IS '服务端身份字段，含模型、课程、课时、文档、来源版本和分片序号';
COMMENT ON COLUMN learnhub_rag.course_embeddings_sf_bge_large_zh_v1.created_at IS '投影首次创建时间';
COMMENT ON COLUMN learnhub_rag.course_embeddings_sf_bge_large_zh_v1.updated_at IS '投影最后写入时间';
CREATE INDEX IF NOT EXISTS course_embeddings_sf_bge_large_zh_v1_scope
    ON learnhub_rag.course_embeddings_sf_bge_large_zh_v1 ((metadata->>'modelId'),
        ((metadata->>'courseId')::BIGINT), ((metadata->>'lessonId')::BIGINT));
CREATE INDEX IF NOT EXISTS course_embeddings_sf_bge_large_zh_v1_generation
    ON learnhub_rag.course_embeddings_sf_bge_large_zh_v1 ((metadata->>'modelId'),
        ((metadata->>'documentId')::BIGINT), ((metadata->>'sourceVersion')::BIGINT));
DROP TRIGGER IF EXISTS touch_projection_updated_at ON learnhub_rag.course_embeddings_sf_bge_large_zh_v1;
CREATE TRIGGER touch_projection_updated_at BEFORE UPDATE ON learnhub_rag.course_embeddings_sf_bge_large_zh_v1
FOR EACH ROW EXECUTE FUNCTION learnhub_rag.touch_projection_updated_at();
-- 已有数据库执行本脚本后，另以部署配置中的运行身份授予此表SELECT/INSERT/UPDATE/DELETE。
