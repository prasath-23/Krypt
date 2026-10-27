"""Run chosen cases of krypt_e2e.py in order, e.g. `python tests/e2e/run_cases.py 30e 30f`.

Later cases build on what earlier ones leave on the device (see README.md), so a chosen case
expects the state the full run would have reached by then. The suite's STATE (the request, the
approval and when it was granted) is kept in <temp>/krypt_e2e_state.json between runs, so a
long run can be split into shorter ones that behave like one. `--reset` starts it empty: use it
with case 01.
"""
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import krypt_e2e as e2e  # noqa: E402

STATE_FILE = os.path.join(tempfile.gettempdir(), "krypt_e2e_state.json")

cases = {}
for value in vars(e2e).values():
    name = getattr(value, "case_name", None)
    if name:
        cases[name.split()[0]] = value

wanted = [a for a in sys.argv[1:] if not a.startswith("--")]
if "--reset" in sys.argv and os.path.exists(STATE_FILE):
    os.remove(STATE_FILE)
missing = [w for w in wanted if w not in cases]
if missing:
    sys.exit(f"no such case: {missing}; known: {sorted(cases)}")
if os.path.exists(STATE_FILE):
    with open(STATE_FILE) as f:
        e2e.STATE.update(json.load(f))
print(f"Krypt e2e cases {wanted} on {e2e.SERIAL}", flush=True)
try:
    for w in wanted:
        cases[w]()
finally:
    with open(STATE_FILE, "w") as f:
        json.dump(e2e.STATE, f)
failed = [r for r in e2e.RESULTS if not r[1]]
print(f"\n{len(e2e.RESULTS) - len(failed)}/{len(e2e.RESULTS)} passed")
for name, _, detail in failed:
    print(f"  FAILED {name}: {detail}")
sys.exit(1 if failed else 0)
