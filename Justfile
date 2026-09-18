set shell := ["bash", "-euo", "pipefail", "-c"]

start:
    @scripts/runtime/control.sh start

stop:
    @scripts/runtime/control.sh stop

status:
    @scripts/runtime/control.sh status

logs service="all":
    @scripts/runtime/control.sh logs "{{service}}"

test:
    @scripts/test.sh

lint:
    @scripts/lint.sh

typecheck:
    @scripts/typecheck.sh
