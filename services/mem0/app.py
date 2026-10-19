"""私网 Mem0 偏好服务。仅单 worker；业务身份来自 Java 的用户 HMAC。"""
import hashlib
import hmac
import json
import logging
import os
import sqlite3
import threading
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID

os.environ["MEM0_TELEMETRY"] = "false"

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ConfigDict, Field, field_validator
from starlette.responses import JSONResponse

Owner = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
Version = Annotated[int, Field(strict=True, ge=1, le=2147483647)]
Category = Literal["STYLE", "PACE", "LEVEL", "GOAL"]
COLLECTION = "learning_preferences_bge512_v1"
MAX_BODY_BYTES = 16 * 1024


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


class BoundedBodyMiddleware:
    """在 FastAPI 解析/认证依赖之前限制实际接收字节，不信任 Content-Length。"""
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = dict(scope.get("headers", []))
        length = headers.get(b"content-length")
        if length is not None:
            try:
                if int(length) < 0 or int(length) > MAX_BODY_BYTES:
                    return await JSONResponse({"detail": "MEMORY_BODY_LIMIT"}, status_code=413)(scope, receive, send)
            except ValueError:
                return await JSONResponse({"detail": "MEMORY_INVALID_REQUEST"}, status_code=422)(scope, receive, send)
        chunks, size = [], 0
        while True:
            event = await receive()
            if event["type"] == "http.disconnect":
                return
            chunk = event.get("body", b"")
            size += len(chunk)
            if size > MAX_BODY_BYTES:
                return await JSONResponse({"detail": "MEMORY_BODY_LIMIT"}, status_code=413)(scope, receive, send)
            chunks.append(chunk)
            if not event.get("more_body", False):
                break
        body = b"".join(chunks)
        if body and headers.get(b"content-type", b"").split(b";", 1)[0].strip().lower() == b"application/json":
            try:
                json.loads(body, object_pairs_hook=strict_object,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Invalid JSON constant")))
            except (ValueError, UnicodeError, RecursionError):
                return await JSONResponse({"detail": "MEMORY_INVALID_REQUEST"}, status_code=422)(scope, receive, send)
        delivered = False

        async def bounded_receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()

        await self.app(scope, bounded_receive, send)


class StrictBody(BaseModel):
    model_config = ConfigDict(extra="forbid")


class PutPreference(StrictBody):
    ownerKey: Owner
    version: Version
    category: Category
    text: str = Field(min_length=1, max_length=200)

    @field_validator("text")
    @classmethod
    def valid_text(cls, value):
        if not value.strip() or any(0xD800 <= ord(char) <= 0xDFFF for char in value):
            raise ValueError("Invalid preference text")
        return value


class DeletePreference(StrictBody):
    ownerKey: Owner
    version: Version


class SearchPreferences(StrictBody):
    ownerKey: Owner
    query: str = Field(min_length=1, max_length=200)
    limit: int = Field(default=5, strict=True, ge=1, le=12)

    @field_validator("query")
    @classmethod
    def valid_query(cls, value):
        return PutPreference.valid_text(value)


class Conflict(Exception):
    pass


class OwnershipError(Exception):
    pass


class RejectingLlm:
    def __init__(self):
        self.calls = 0

    def generate_response(self, *args, **kwargs):
        self.calls += 1
        raise RuntimeError("MEMORY_EXTRACTION_DISABLED")


class PreferenceService:
    def __init__(self, memory, journal_path, resources=()):
        self.memory = memory
        self.resources = resources
        self.lock = threading.RLock()
        self.llm = RejectingLlm()
        memory.llm = self.llm
        self.counts = {"writes": 0, "searches": 0, "lists": 0, "sdkAdds": 0, "sdkDeletes": 0}
        self.db = sqlite3.connect(str(journal_path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        try:
            self.db.execute("PRAGMA journal_mode=WAL")
            self.db.execute("PRAGMA synchronous=FULL")
            with self.transaction():
                self.db.execute("""CREATE TABLE IF NOT EXISTS preference_operation (
            preference_id TEXT PRIMARY KEY, owner_key TEXT NOT NULL,
            max_version INTEGER NOT NULL, payload_hash TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('PENDING','ACTIVE','DELETED')),
            remote_id TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)""")
        except Exception:
            self.db.close()
            raise

    @contextmanager
    def transaction(self):
        try:
            yield
            self.db.commit()
        except Exception:
            # COMMIT 的 I/O 故障也不能让同一连接读到未持久化的 ACTIVE 后确认成功。
            try:
                self.db.rollback()
            except Exception:
                self.db.close()  # 回滚也失败时连接不可继续用于确认意图。
            raise

    @staticmethod
    def digest(category, text):
        return hashlib.sha256(json.dumps([category, text], ensure_ascii=False,
                                        separators=(",", ":")).encode("utf-8")).hexdigest()

    @staticmethod
    def filters(owner, preference_id=None):
        result = {"user_id": owner, "app_id": "learnhub", "kind": "learning_preference"}
        if preference_id is not None:
            result["preference_id"] = preference_id
        return result

    def row(self, preference_id, owner):
        row = self.db.execute("SELECT * FROM preference_operation WHERE preference_id=?",
                              (preference_id,)).fetchone()
        if row and row["owner_key"] != owner:
            raise OwnershipError()
        return row

    def intent(self, preference_id, owner, version, payload_hash, status):
        with self.transaction():
            self.db.execute("""INSERT INTO preference_operation
            (preference_id,owner_key,max_version,payload_hash,status) VALUES (?,?,?,?,?)
            ON CONFLICT(preference_id) DO UPDATE SET max_version=excluded.max_version,
            payload_hash=excluded.payload_hash,status=excluded.status,remote_id=NULL,
            updated_at=CURRENT_TIMESTAMP""", (preference_id, owner, version, payload_hash, status))

    @staticmethod
    def owned(item, owner, preference_id=None):
        meta = item.get("metadata") or {}
        return (item.get("user_id") == owner and meta.get("app_id") == "learnhub"
                and meta.get("kind") == "learning_preference"
                and (preference_id is None or meta.get("preference_id") == preference_id))

    def remote_rows(self, owner, preference_id=None):
        # 固定 SDK 默认排除过期数据会额外抓取 4 倍；本服务不接受过期字段，明确关闭 over-fetch。
        result = self.memory.get_all(filters=self.filters(owner, preference_id), top_k=1000, show_expired=True)
        rows = result.get("results", [])
        if not isinstance(rows, list) or len(rows) >= 1000:
            raise RuntimeError("MEMORY_SCAN_LIMIT")
        return [item for item in rows if self.owned(item, owner, preference_id)]

    def remove_remote(self, remote_id, owner, preference_id):
        # SDK 按内部 ID 删除没有租户参数，必须先核对实际记录。
        actual = self.memory.get(remote_id)
        if actual is None:
            return
        if not self.owned(actual, owner, preference_id):
            raise OwnershipError()
        self.counts["sdkDeletes"] += 1
        self.memory.delete(remote_id)

    def put(self, preference_id, body):
        with self.lock:
            self.counts["writes"] += 1
            owner, version = body.ownerKey, body.version
            payload_hash = self.digest(body.category, body.text)
            previous = self.row(preference_id, owner)
            if previous:
                if previous["status"] == "DELETED" or version < previous["max_version"]:
                    raise Conflict()
                if version == previous["max_version"]:
                    if payload_hash != previous["payload_hash"]:
                        raise Conflict()
                    if previous["status"] == "ACTIVE":
                        return {"preferenceId": preference_id, "version": version}
            if not previous or version > previous["max_version"]:
                self.intent(preference_id, owner, version, payload_hash, "PENDING")
            existing = self.remote_rows(owner, preference_id)
            candidates = [item for item in existing
                          if (item.get("metadata") or {}).get("version") == version
                          and (item.get("metadata") or {}).get("category") == body.category
                          and self.digest(body.category, item.get("memory", "")) == payload_hash]
            if candidates:
                remote_id = candidates[0]["id"]
            else:
                metadata = {"app_id": "learnhub", "kind": "learning_preference",
                            "preference_id": preference_id, "version": version, "category": body.category}
                self.counts["sdkAdds"] += 1
                result = self.memory.add(messages=[{"role": "user", "content": body.text}],
                                         user_id=owner, metadata=metadata, infer=False)
                added = result.get("results", [])
                if len(added) != 1 or not added[0].get("id"):
                    raise RuntimeError("MEMORY_ADD_FAILED")
                remote_id = added[0]["id"]
            # 再次扫描兼容 add 已提交但 HTTP 响应丢失，也清除同版本重复记录。
            for item in self.remote_rows(owner, preference_id):
                if item["id"] != remote_id:
                    self.remove_remote(item["id"], owner, preference_id)
            actual = self.memory.get(remote_id)
            actual_meta = (actual or {}).get("metadata") or {}
            if (not actual or not self.owned(actual, owner, preference_id)
                    or type(actual_meta.get("version")) is not int or actual_meta.get("version") != version
                    or actual_meta.get("category") != body.category
                    or not isinstance(actual.get("memory"), str)
                    or self.digest(body.category, actual["memory"]) != payload_hash):
                raise RuntimeError("MEMORY_ADD_FAILED")
            with self.transaction():
                self.db.execute("""UPDATE preference_operation SET status='ACTIVE',remote_id=?,
                updated_at=CURRENT_TIMESTAMP WHERE preference_id=? AND max_version=? AND status='PENDING'""",
                            (remote_id, preference_id, version))
            return {"preferenceId": preference_id, "version": version}

    def delete(self, preference_id, body):
        with self.lock:
            self.counts["writes"] += 1
            previous = self.row(preference_id, body.ownerKey)
            if previous:
                if body.version < previous["max_version"]:
                    raise Conflict()
                if body.version == previous["max_version"] and previous["status"] != "DELETED":
                    raise Conflict()
            # 删除意图先持久化。远端故障也不会再参与召回，重试继续清理。
            self.intent(preference_id, body.ownerKey, body.version, "", "DELETED")
            for item in self.remote_rows(body.ownerKey, preference_id):
                self.remove_remote(item["id"], body.ownerKey, preference_id)
            return {"preferenceId": preference_id, "version": body.version, "deleted": True}

    def active_item(self, item, owner):
        if not self.owned(item, owner):
            return None
        meta = item.get("metadata") or {}
        preference_id, version = meta.get("preference_id"), meta.get("version")
        if not isinstance(preference_id, str) or type(version) is not int:
            return None
        try:
            row = self.row(preference_id, owner)
        except OwnershipError:
            return None
        if not row or row["status"] != "ACTIVE" or row["max_version"] != version or row["remote_id"] != item.get("id"):
            return None
        category, text = meta.get("category"), item.get("memory")
        if category not in ("STYLE", "PACE", "LEVEL", "GOAL") or not isinstance(text, str):
            return None
        if self.digest(category, text) != row["payload_hash"]:
            return None
        return {"preferenceId": preference_id, "version": version, "category": category, "text": text}

    def list(self, owner, limit):
        with self.lock:
            self.counts["lists"] += 1
            items = [active for item in self.remote_rows(owner)
                     if (active := self.active_item(item, owner)) is not None]
            return {"items": items[:limit]}

    def search(self, body):
        with self.lock:
            self.counts["searches"] += 1
            result = self.memory.search(query=body.query, filters=self.filters(body.ownerKey),
                                        top_k=body.limit, rerank=False)
            items = []
            for item in result.get("results", [])[:body.limit]:
                active = self.active_item(item, body.ownerKey)
                if active:
                    items.append({"preferenceId": active["preferenceId"], "version": active["version"]})
            return {"items": items}

    def stats(self):
        with self.lock:
            return {**self.counts, "llmAttempts": self.llm.calls}

    def close(self):
        with self.lock:
            # 某个客户端关闭失败也继续释放其余资源；不输出可能带凭据的异常。
            for resource in (self.memory, *self.resources, self.db):
                try:
                    resource.close()
                except Exception:
                    pass


def required_env(name):
    value = os.environ.get(name, "")
    if not value:
        raise RuntimeError("Missing configuration: " + name)
    return value


def build_service():
    # 容器不继承供应商凭据，仍明确移除可能改变 SDK 分支的变量。
    for name in ("OPENAI_API_KEY", "OPENROUTER_API_KEY", "DEEPSEEK_API_KEY", "OPENAI_BASE_URL", "OPENAI_API_BASE"):
        os.environ.pop(name, None)
    logging.getLogger("mem0").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    from mem0 import Memory
    from psycopg_pool import ConnectionPool
    from psycopg.conninfo import make_conninfo

    data_dir = Path(os.environ.get("LEARNHUB_MEMORY_DATA_DIR", "/data"))
    data_dir.mkdir(parents=True, exist_ok=True)
    conninfo = make_conninfo(host=required_env("LEARNHUB_MEMORY_PG_HOST"),
                            port=os.environ.get("LEARNHUB_MEMORY_PG_PORT", "5432"),
                            dbname=required_env("LEARNHUB_MEMORY_PG_DATABASE"),
                            user=required_env("LEARNHUB_MEMORY_PG_USER"),
                            password=required_env("LEARNHUB_MEMORY_PG_PASSWORD"), connect_timeout=3,
                            options="-c statement_timeout=2000 -c lock_timeout=1000 -c idle_in_transaction_session_timeout=3000")
    pool = ConnectionPool(conninfo=conninfo, min_size=0, max_size=3, timeout=3, open=False)
    created = [pool]
    try:
        pool.open(wait=False)
        # 只读检查管理员预建表与维度。配置错误不能让 SDK 自动创建新表/扩展。
        with pool.connection() as connection:
            with connection.cursor() as cursor:
                cursor.execute("""SELECT format_type(a.atttypid,a.atttypmod)
                    FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                    JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public' AND c.relname=%s AND a.attname='vector'""", (COLLECTION,))
                if cursor.fetchone() != ("vector(512)",):
                    raise RuntimeError("Prebuilt memory collection required")
        memory = Memory.from_config({
            "version": "v1.1", "history_db_path": str(data_dir / "mem0_history.sqlite"),
            "vector_store": {"provider": "pgvector", "config": {
                "dbname": required_env("LEARNHUB_MEMORY_PG_DATABASE"), "collection_name": COLLECTION,
                "embedding_model_dims": 512, "connection_pool": pool, "hnsw": False, "diskann": False}},
            "embedder": {"provider": "openai", "config": {
                "model": "learnhub-bge-zh-v1", "embedding_dims": 512,
                "openai_base_url": required_env("LEARNHUB_MEMORY_EMBEDDING_BASE_URL"),
                "api_key": required_env("LEARNHUB_MEMORY_EMBEDDING_TOKEN")}},
            "llm": {"provider": "openai", "config": {
                "model": "disabled", "api_key": "disabled-local-only",
                "openai_base_url": "http://127.0.0.1:1/v1", "max_tokens": 1, "enable_vision": False}}})
        created.append(memory)
        embedding_client = memory.embedding_model.client
        disabled_client = memory.llm.client
        created.extend((embedding_client, disabled_client))
        # SDK 默认可能重试本地桥；显式关闭隐藏重试并限制超时。
        replacement = embedding_client.with_options(max_retries=0, timeout=5)
        created.append(replacement)
        memory.embedding_model.client = replacement
        return PreferenceService(memory, data_dir / "service_state.sqlite",
                                 (replacement, embedding_client, disabled_client, pool))
    except Exception:
        for resource in reversed(created):
            try:
                resource.close()
            except Exception:
                pass
        raise RuntimeError("Memory initialization failed") from None


def create_app(service=None, token=None):
    @asynccontextmanager
    async def lifespan(application):
        application.state.token = token or required_env("LEARNHUB_MEMORY_SERVICE_TOKEN")
        if len(application.state.token) < 32:
            raise RuntimeError("Memory service token must contain at least 32 characters")
        application.state.service = service or build_service()
        try:
            yield
        finally:
            application.state.service.close()

    application = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    application.add_middleware(BoundedBodyMiddleware)

    @application.exception_handler(RequestValidationError)
    async def invalid_request(_request, _failure):
        # FastAPI 默认 detail 含 input，不能将偏好正文或未知字段值附在错误响应中。
        return JSONResponse({"detail": "MEMORY_INVALID_REQUEST"}, status_code=422)

    def authenticated(authorization: str = Header(default="")):
        expected = "Bearer " + application.state.token
        if not hmac.compare_digest(authorization.encode(), expected.encode()):
            raise HTTPException(status_code=401, detail="MEMORY_UNAUTHORIZED")
        return application.state.service

    def execute(action):
        try:
            return action()
        except Conflict:
            raise HTTPException(status_code=409, detail="MEMORY_VERSION_CONFLICT") from None
        except OwnershipError:
            raise HTTPException(status_code=404, detail="MEMORY_NOT_FOUND") from None
        except Exception:
            raise HTTPException(status_code=503, detail="MEMORY_UNAVAILABLE") from None

    @application.get("/health")
    def health(_service=Depends(authenticated)):
        return {"status": "UP", "extractionEnabled": False}

    @application.get("/stats")
    def stats(current=Depends(authenticated)):
        return current.stats()

    @application.put("/preferences/{preference_id}")
    def put(preference_id: UUID, body: PutPreference, current=Depends(authenticated)):
        return execute(lambda: current.put(str(preference_id), body))

    @application.delete("/preferences/{preference_id}")
    def delete(preference_id: UUID, body: DeletePreference, current=Depends(authenticated)):
        return execute(lambda: current.delete(str(preference_id), body))

    @application.post("/preferences/search")
    def search(body: SearchPreferences, current=Depends(authenticated)):
        return execute(lambda: current.search(body))

    @application.get("/preferences")
    def list_preferences(ownerKey: Annotated[str, Query(pattern=r"^[0-9a-f]{64}$")],
                         limit: Annotated[int, Query(ge=1, le=50)] = 50,
                         current=Depends(authenticated)):
        return execute(lambda: current.list(ownerKey, limit))

    return application


app = create_app()
