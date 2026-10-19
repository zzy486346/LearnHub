-- 管理员在独立记忆数据库执行；不得交给 MySQL 初始化。
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS public.learning_preferences_bge512_v1 (
    id UUID PRIMARY KEY,
    vector vector(512),
    payload JSONB
);
COMMENT ON TABLE public.learning_preferences_bge512_v1 IS 'Mem0 学习偏好向量投影，独立于课程事实';
COMMENT ON COLUMN public.learning_preferences_bge512_v1.id IS 'Mem0 内部记忆标识';
COMMENT ON COLUMN public.learning_preferences_bge512_v1.vector IS '本地 BGE 中文无前缀归一化 512 维向量';
COMMENT ON COLUMN public.learning_preferences_bge512_v1.payload IS '服务端限定的偏好正文、用户摘要和版本元数据';
CREATE INDEX IF NOT EXISTS learning_preferences_bge512_v1_owner_idx
    ON public.learning_preferences_bge512_v1 ((payload->>'user_id'), (payload->>'preference_id'));
-- 实际账号名由部署脚本安全绑定；运行账号仅授 schema USAGE 与本表 SELECT/INSERT/UPDATE/DELETE。
