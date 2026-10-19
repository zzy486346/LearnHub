-- 由 PostgreSQL 迁移身份执行，禁止交给 MySQL 初始化。
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE SCHEMA IF NOT EXISTS learnhub_rag;
CREATE TABLE IF NOT EXISTS learnhub_rag.course_embeddings_bge_zh_v1 (
    embedding_id UUID PRIMARY KEY,
    embedding public.vector(512) NOT NULL,
    text TEXT NOT NULL,
    metadata JSON NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE learnhub_rag.course_embeddings_bge_zh_v1 IS '课程材料向量投影，可由MySQL事实源重建';
COMMENT ON COLUMN learnhub_rag.course_embeddings_bge_zh_v1.embedding_id IS '模型、文档、版本、知识块和分片共同派生的幂等UUID';
COMMENT ON COLUMN learnhub_rag.course_embeddings_bge_zh_v1.embedding IS 'BGE中文量化模型的512维L2归一化向量，使用余弦距离';
COMMENT ON COLUMN learnhub_rag.course_embeddings_bge_zh_v1.text IS '向量分片正文，仅供投影检索，不作为回答事实源';
COMMENT ON COLUMN learnhub_rag.course_embeddings_bge_zh_v1.metadata IS '服务端白名单身份字段，包含模型、课程、课时、文档和版本';
COMMENT ON COLUMN learnhub_rag.course_embeddings_bge_zh_v1.created_at IS '投影首次创建时间';
COMMENT ON COLUMN learnhub_rag.course_embeddings_bge_zh_v1.updated_at IS '投影最后写入时间';
CREATE INDEX IF NOT EXISTS course_embeddings_bge_zh_v1_scope
    ON learnhub_rag.course_embeddings_bge_zh_v1 ((metadata->>'modelId'),
        ((metadata->>'courseId')::BIGINT), ((metadata->>'lessonId')::BIGINT));
CREATE INDEX IF NOT EXISTS course_embeddings_bge_zh_v1_generation
    ON learnhub_rag.course_embeddings_bge_zh_v1 ((metadata->>'modelId'),
        ((metadata->>'documentId')::BIGINT), ((metadata->>'sourceVersion')::BIGINT));
-- 小规模课程材料使用精确余弦检索；避免 ANN 过滤后召回不足。规模变化需单独评测。
CREATE OR REPLACE FUNCTION learnhub_rag.touch_projection_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN NEW.updated_at = CURRENT_TIMESTAMP; RETURN NEW; END $$;
DROP TRIGGER IF EXISTS touch_projection_updated_at ON learnhub_rag.course_embeddings_bge_zh_v1;
CREATE TRIGGER touch_projection_updated_at BEFORE UPDATE ON learnhub_rag.course_embeddings_bge_zh_v1
FOR EACH ROW EXECUTE FUNCTION learnhub_rag.touch_projection_updated_at();
-- 运行账号由部署方以本地凭据创建，随后授予：
-- GRANT USAGE ON SCHEMA learnhub_rag, public TO learnhub_vector_app;
-- GRANT SELECT, INSERT, UPDATE, DELETE ON learnhub_rag.course_embeddings_bge_zh_v1 TO learnhub_vector_app;
-- JDBC currentSchema=learnhub_rag,public；应用配置 skipCreateVectorExtension=true。
