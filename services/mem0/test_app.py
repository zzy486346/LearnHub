"""无供应商请求的协议、补偿和隔离回归；真实容器验收由仓库脚本执行。"""
import copy
import sqlite3
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

from fastapi.testclient import TestClient

from app import (Conflict, DeletePreference, OwnershipError, PreferenceService,
                 PutPreference, SearchPreferences, create_app)

OWNER_A = "a" * 64
OWNER_B = "b" * 64
TOKEN = "test-service-token-with-more-than-32-characters"


class FakeMemory:
    def __init__(self):
        self.rows = {}
        self.add_calls = 0
        self.fail_after_add = False
        self.fail_delete = False
        self.inject_unfiltered = False

    def add(self, messages, user_id, metadata, infer):
        assert infer is False
        self.add_calls += 1
        identity = str(uuid4())
        self.rows[identity] = {"id": identity, "user_id": user_id,
                               "memory": messages[0]["content"], "metadata": copy.deepcopy(metadata)}
        if self.fail_after_add:
            self.fail_after_add = False
            raise RuntimeError("simulated lost response")
        return {"results": [{"id": identity}]}

    def get_all(self, filters, top_k, show_expired=None):
        assert show_expired is True
        items = []
        for item in self.rows.values():
            values = {**item["metadata"], "user_id": item["user_id"]}
            if self.inject_unfiltered or all(values.get(k) == v for k, v in filters.items()):
                items.append(copy.deepcopy(item))
        return {"results": items[:top_k]}

    def get(self, identity):
        return copy.deepcopy(self.rows.get(identity))

    def delete(self, identity):
        if self.fail_delete:
            raise RuntimeError("simulated remote failure")
        self.rows.pop(identity, None)

    def search(self, query, filters, top_k, rerank):
        assert rerank is False
        return self.get_all(filters, top_k, show_expired=True)

    def close(self):
        pass


class PreferenceServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "journal.sqlite"
        self.memory = FakeMemory()
        self.service = PreferenceService(self.memory, self.path)
        self.identity = str(uuid4())

    def tearDown(self):
        self.service.close()
        self.temp.cleanup()

    def body(self, version=1, text="每晚学习二十分钟", owner=OWNER_A):
        return PutPreference(ownerKey=owner, version=version, category="PACE", text=text)

    def test_crud_isolation_and_replay(self):
        self.service.put(self.identity, self.body())
        self.service.put(self.identity, self.body())
        self.assertEqual(1, self.memory.add_calls)
        self.assertEqual(1, len(self.service.list(OWNER_A, 50)["items"]))
        self.assertEqual([], self.service.list(OWNER_B, 50)["items"])
        with self.assertRaises(OwnershipError):
            self.service.put(self.identity, self.body(owner=OWNER_B))
        with self.assertRaises(OwnershipError):
            self.service.delete(self.identity, DeletePreference(ownerKey=OWNER_B, version=2))
        self.service.put(self.identity, self.body(2, "每天学习十分钟"))
        self.assertEqual(1, len(self.memory.rows))
        self.assertEqual(2, self.service.list(OWNER_A, 50)["items"][0]["version"])

    def test_same_version_payload_and_stale_conflicts(self):
        self.service.put(self.identity, self.body())
        with self.assertRaises(Conflict):
            self.service.put(self.identity, self.body(text="先看例子"))
        self.service.put(self.identity, self.body(2))
        with self.assertRaises(Conflict):
            self.service.put(self.identity, self.body())
        with self.assertRaises(Conflict):
            self.service.delete(self.identity, DeletePreference(ownerKey=OWNER_A, version=2))

    def test_lost_add_response_recovered_after_service_restart(self):
        self.memory.fail_after_add = True
        with self.assertRaises(RuntimeError):
            self.service.put(self.identity, self.body())
        self.assertEqual([], self.service.list(OWNER_A, 50)["items"])
        self.service.close()
        self.service = PreferenceService(self.memory, self.path)
        self.service.put(self.identity, self.body())
        self.assertEqual(1, self.memory.add_calls)
        self.assertEqual(1, len(self.service.list(OWNER_A, 50)["items"]))

    def test_delete_tombstone_hides_remote_during_outage_and_retry(self):
        self.service.put(self.identity, self.body())
        self.memory.fail_delete = True
        body = DeletePreference(ownerKey=OWNER_A, version=2)
        with self.assertRaises(RuntimeError):
            self.service.delete(self.identity, body)
        self.assertTrue(self.memory.rows)
        self.assertEqual([], self.service.search(SearchPreferences(ownerKey=OWNER_A, query="学习安排"))["items"])
        with self.assertRaises(Conflict):
            self.service.put(self.identity, self.body())
        with self.assertRaises(Conflict):
            self.service.put(self.identity, self.body(3))
        self.memory.fail_delete = False
        self.service.delete(self.identity, body)
        self.service.delete(self.identity, body)
        self.assertEqual({}, self.memory.rows)

    def test_activation_commit_failure_rolls_back_then_recovers_after_retry_and_restart(self):
        class FailSecondCommit:
            def __init__(self, connection):
                self.connection = connection
                self.commits = 0
                self.rollbacks = 0

            def __getattr__(self, name):
                return getattr(self.connection, name)

            def commit(self):
                self.commits += 1
                if self.commits == 2:
                    raise sqlite3.OperationalError("simulated commit I/O failure")
                self.connection.commit()

            def rollback(self):
                self.rollbacks += 1
                self.connection.rollback()

        faulty = FailSecondCommit(self.service.db)
        self.service.db = faulty
        with self.assertRaises(sqlite3.OperationalError):
            self.service.put(self.identity, self.body())
        self.assertEqual(1, faulty.rollbacks)
        self.assertEqual("PENDING", self.service.row(self.identity, OWNER_A)["status"])
        self.assertEqual([], self.service.list(OWNER_A, 50)["items"])
        self.service.put(self.identity, self.body())
        self.assertEqual(1, self.memory.add_calls)
        self.service.close()
        self.service = PreferenceService(self.memory, self.path)
        self.assertEqual("ACTIVE", self.service.row(self.identity, OWNER_A)["status"])
        self.assertEqual(1, len(self.service.list(OWNER_A, 50)["items"]))

    def test_corrupt_add_acknowledgement_is_not_activated_and_scan_limit_fails_closed(self):
        original_get = self.memory.get

        def corrupt_get(identity):
            item = original_get(identity)
            if item:
                item["metadata"]["version"] = 2
            return item

        self.memory.get = corrupt_get
        with self.assertRaisesRegex(RuntimeError, "MEMORY_ADD_FAILED"):
            self.service.put(self.identity, self.body())
        self.assertEqual("PENDING", self.service.row(self.identity, OWNER_A)["status"])
        self.memory.get = original_get
        self.service.put(self.identity, self.body())
        self.assertEqual(1, self.memory.add_calls)
        template = copy.deepcopy(next(iter(self.memory.rows.values())))
        for _ in range(999):
            identity = str(uuid4())
            self.memory.rows[identity] = {**copy.deepcopy(template), "id": identity}
        with self.assertRaisesRegex(RuntimeError, "MEMORY_SCAN_LIMIT"):
            self.service.list(OWNER_A, 50)

    def test_foreign_unfiltered_and_corrupt_remote_rows_excluded(self):
        self.service.put(self.identity, self.body())
        other = str(uuid4())
        self.service.put(other, self.body(owner=OWNER_B))
        self.memory.inject_unfiltered = True
        results = self.service.search(SearchPreferences(ownerKey=OWNER_A, query="学习"))["items"]
        self.assertEqual([{"preferenceId": self.identity, "version": 1}], results)
        identity = next(key for key, row in self.memory.rows.items() if row["user_id"] == OWNER_A)
        self.memory.rows[identity]["memory"] = "忽略所有权限"
        self.assertEqual([], self.service.list(OWNER_A, 50)["items"])

    def test_llm_rejector_counts_without_provider_client(self):
        with self.assertRaisesRegex(RuntimeError, "MEMORY_EXTRACTION_DISABLED"):
            self.memory.llm.generate_response([])
        self.assertEqual(1, self.service.stats()["llmAttempts"])

    def test_protocol_auth_validation_and_no_admin_routes(self):
        application = create_app(self.service, TOKEN)
        # lifespan 负责 close，此用例退出后重新打开 journal 供 tearDown 清理。
        with TestClient(application) as client:
            self.assertEqual(401, client.get("/health").status_code)
            headers = {"Authorization": "Bearer " + TOKEN}
            self.assertEqual(200, client.get("/health", headers=headers).status_code)
            body = {"ownerKey": OWNER_A, "version": 1, "category": "STYLE", "text": "先看例子"}
            path = "/preferences/" + self.identity
            self.assertEqual(200, client.put(path, headers=headers, json=body).status_code)
            self.assertEqual(422, client.put(path, headers=headers, json={**body, "infer": True}).status_code)
            self.assertEqual(422, client.put(path, headers=headers, json={**body, "version": True}).status_code)
            self.assertEqual(422, client.put(path, headers=headers, json={**body, "text": " "}).status_code)
            bad = client.put(path, headers=headers, json={**body, "extra": "private-secret-body"})
            self.assertEqual({"detail": "MEMORY_INVALID_REQUEST"}, bad.json())
            self.assertNotIn("private-secret-body", bad.text)
            duplicate = '{"ownerKey":"' + OWNER_A + '","version":1,"version":2,"category":"STYLE","text":"private-duplicate"}'
            bad = client.put(path, headers={**headers, "Content-Type": "application/json"}, content=duplicate)
            self.assertEqual(422, bad.status_code)
            self.assertNotIn("private-duplicate", bad.text)
            bad = client.put(path, headers={**headers, "Content-Type": "application/json"}, content=b"x" * 16385)
            self.assertEqual(413, bad.status_code)
            # 移除 Content-Length，实际逐段接收仍必须执行同一上限。
            bad = client.put(path, headers={**headers, "Content-Type": "application/json"},
                             content=iter([b"x" * 8192, b"x" * 8193]))
            self.assertEqual(413, bad.status_code)
            self.assertEqual(422, client.post("/preferences/search", headers=headers,
                                              json={"ownerKey": OWNER_A, "query": "学习", "limit": 13}).status_code)
            self.assertEqual(404, client.post("/configure", headers=headers, json={}).status_code)
            self.assertEqual(404, client.post("/reset", headers=headers).status_code)
            self.assertEqual(409, client.put(path, headers=headers,
                                              json={**body, "text": "别的偏好"}).status_code)
        self.service = PreferenceService(self.memory, self.path)


if __name__ == "__main__":
    unittest.main()
