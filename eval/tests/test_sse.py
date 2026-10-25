from datapilot_eval.runners.agent import iter_sse


def test_events_and_data():
    lines = [
        "event: conversation", 'data: {"conversationId": 1}', "",
        ": keep-alive comment", "",
        "event:token", "data:hello", "",
        "event: done", 'data: {"finalSql": "SELECT 1",', 'data:  "conclusion": "x"}', "",
    ]
    assert list(iter_sse(lines)) == [
        ("conversation", '{"conversationId": 1}'),
        ("token", "hello"),
        ("done", '{"finalSql": "SELECT 1",\n "conclusion": "x"}'),
    ]


def test_crlf_and_missing_final_blank_line():
    assert list(iter_sse(["event: done\r\n", "data: {}\r\n"])) == [("done", "{}")]


def test_default_event_name_and_empty_blocks():
    assert list(iter_sse(["", "data: x", "", "event: ignored", ""])) == [("message", "x")]
