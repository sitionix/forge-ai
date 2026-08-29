set shell := ["bash", "-euo", "pipefail", "-c"]

start:
    @scripts/runtime/control.sh start

stop:
    @scripts/runtime/control.sh stop

restart:
    @scripts/runtime/control.sh restart

status:
    @scripts/runtime/control.sh status

logs service="all":
    @scripts/runtime/control.sh logs "{{service}}"

systemd-install:
    @scripts/runtime/prepare.sh
    @scripts/systemd/install.sh

test:
    @scripts/test.sh

lint:
    @scripts/lint.sh

typecheck:
    @scripts/typecheck.sh
