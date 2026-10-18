"""Prints one workflow's history as a readable timeline: L1 tasks with outcomes, signals, timers.

usage: python3 dev/l1-local/timeline.py <workflow-id>     (needs the `temporal` CLI)
"""
import base64
import json
import subprocess
import sys
from datetime import datetime


def payload(p):
    try:
        return json.loads(base64.b64decode(p["payloads"][0]["data"]))
    except Exception:
        return None


def main(wf):
    raw = subprocess.run(["temporal", "workflow", "show", "--workflow-id", wf, "--output", "json"],
                         capture_output=True, text=True, check=True).stdout
    events = json.loads(raw)["events"]
    t0 = None
    scheduled = {}
    print(f"Timeline of workflow {wf}\n")
    for e in events:
        ts = datetime.fromisoformat(e["eventTime"].replace("Z", "+00:00"))
        t0 = t0 or ts
        at = f"+{(ts - t0).total_seconds():7.2f}s"
        kind = e["eventType"].replace("EVENT_TYPE_", "")
        if kind == "WORKFLOW_EXECUTION_STARTED":
            a = e["workflowExecutionStartedEventAttributes"]
            print(f"{at}  ▶ workflow {a['workflowType']['name']} on queue {a['taskQueue']['name']} input={payload(a.get('input', {}))}")
        elif kind == "ACTIVITY_TASK_SCHEDULED":
            a = e["activityTaskScheduledEventAttributes"]
            arg = payload(a.get("input", {}))
            label = arg.get("recipeRef") + "@" + str(arg.get("recipeVersion")) if isinstance(arg, dict) else ""
            scheduled[e["eventId"]] = (a["activityType"]["name"], label, ts)
            print(f"{at}    → {a['activityType']['name']:<15} {label:<34} (queue {a['taskQueue']['name']})")
        elif kind == "ACTIVITY_TASK_COMPLETED":
            a = e["activityTaskCompletedEventAttributes"]
            name, label, st = scheduled.get(a["scheduledEventId"], ("?", "", ts))
            r = payload(a.get("result", {}))
            shown = f"{r['outcome']}  {r['outputs'] if r.get('outputs') else ''}" if isinstance(r, dict) and "outcome" in r else r
            print(f"{at}    ← {name:<15} {label:<34} {shown}   [{(ts - st).total_seconds() * 1000:.0f} ms]")
        elif kind == "ACTIVITY_TASK_FAILED":
            a = e["activityTaskFailedEventAttributes"]
            f = a["failure"]
            print(f"{at}    ✖ activity failed: {f.get('applicationFailureInfo', {}).get('type', '')} {f.get('message', '')}")
        elif kind == "WORKFLOW_EXECUTION_SIGNALED":
            a = e["workflowExecutionSignaledEventAttributes"]
            print(f"{at}  ✉ signal {a['signalName']} {payload(a.get('input', {}))}")
        elif kind == "TIMER_STARTED":
            a = e["timerStartedEventAttributes"]
            print(f"{at}  ⏸ timer {a['startToFireTimeout']}")
        elif kind == "TIMER_FIRED":
            print(f"{at}  ⏵ timer fired")
        elif kind == "WORKFLOW_EXECUTION_COMPLETED":
            a = e["workflowExecutionCompletedEventAttributes"]
            print(f"{at}  ■ workflow completed: {payload(a.get('result', {}))}")
        elif kind in ("WORKFLOW_EXECUTION_FAILED", "WORKFLOW_EXECUTION_TIMED_OUT", "WORKFLOW_EXECUTION_TERMINATED"):
            print(f"{at}  ■ workflow {kind.split('_')[-1].lower()}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1])
