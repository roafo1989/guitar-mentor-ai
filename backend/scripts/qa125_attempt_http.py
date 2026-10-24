#!/usr/bin/env python3
"""#125: live HTTP contract checks against the disposable Java CI PostgreSQL app."""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

base, attempt_id, learner_id, session_id, task_id = sys.argv[1:]
prefix = base.split("/learners/")[0] + "/learners/"

def get(path):
    req = urllib.request.Request(path, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=15) as reply:
            return reply.status, dict(reply.headers), json.load(reply)
    except urllib.error.HTTPError as failure:
        raw = failure.read()
        try:
            body = json.loads(raw)
        except ValueError:
            raise AssertionError("Unsafe non-JSON error response") from None
        return failure.code, dict(failure.headers), body

def check(url, expected_status, expected_code=None):
    status, headers, body = get(url)
    assert status == expected_status, (url, status, expected_status, body)
    assert "no-store" in headers.get("Cache-Control", "").lower(), (url, headers)
    if expected_code:
        assert body == {"code": expected_code}, (url, body)
        assert not any(word in json.dumps(body).lower() for word in ["trace", "media_key", "stack"])
    return body

page = check(base + "/page", 200)
assert list(page) == ["items", "nextBeforeNumber", "hasMore"], page
assert len(page["items"]) == 2
assert [a["number"] for a in page["items"]] == [2, 1]
assert page["nextBeforeNumber"] is None and page["hasMore"] is False
assert all(isinstance(a["assessmentAvailable"], bool) for a in page["items"])

first = check(base + "/page?limit=1", 200)
assert [a["number"] for a in first["items"]] == [2]
assert first["nextBeforeNumber"] == 2 and first["hasMore"]
older = check(base + "/page?limit=1&beforeNumber=2", 200)
assert [a["number"] for a in older["items"]] == [1]
assert older["nextBeforeNumber"] is None and not older["hasMore"]
empty = check(base + "/page?beforeNumber=1", 200)
assert empty == {"items": [], "nextBeforeNumber": None, "hasMore": False}
gap = check(base + "/page?beforeNumber=2147483647&limit=50", 200)
assert [a["number"] for a in gap["items"]] == [2, 1]

detail = check(base + "/" + attempt_id, 200)
assert detail["id"] == attempt_id and detail["taskId"] == task_id
assert any(a["id"] == detail["id"] and a["number"] == detail["number"] for a in page["items"])

caps = check(base + "/capabilities", 200)
assert caps["enabled"] and caps["maxBytes"] > 0 and "wav" in caps["formats"]

for query in [
    "limit=", "limit=0", "limit=51", "limit=2.5", "limit=abc",
    "limit=2147483648", "limit=1&limit=2",
    "beforeNumber=", "beforeNumber=0", "beforeNumber=1.1",
    "beforeNumber=abc", "beforeNumber=2147483648",
    "beforeNumber=2&beforeNumber=3",
]:
    check(base + "/page?" + query, 400, "INVALID_ATTEMPT_PAGE")

check(base + "/not-a-uuid", 400, "INVALID_ATTEMPT_REFERENCE")
check(base + "/00000000-0000-4000-8000-000000000000", 404, "RESOURCE_NOT_FOUND")
foreign_learner = prefix + "11111111-1111-4111-8111-111111111111" + "/diagnostic-sessions/" + session_id + "/tasks/" + task_id + "/attempts"
foreign_task = prefix + learner_id + "/diagnostic-sessions/" + session_id + "/tasks/11111111-1111-4111-8111-111111111111/attempts"
check(foreign_learner + "/page", 404, "RESOURCE_NOT_FOUND")
check(foreign_task + "/page", 404, "RESOURCE_NOT_FOUND")
check(foreign_learner + "/" + attempt_id, 404, "RESOURCE_NOT_FOUND")
check(foreign_task + "/" + attempt_id, 404, "RESOURCE_NOT_FOUND")

print("QA #125 live HTTP contract: page/detail/capabilities, cursor, safe 400/404 and no-store PASS")
