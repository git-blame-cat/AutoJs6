#!/bin/sh

set -eu

if [ "${1##*/}" = "gen_opencl_code.py" ]; then
    source_script="$1"
    shift
    copied_script="$AUTOJS6_PYTHON_SHIM_DIR/gen_opencl_code.py"
    cp "$source_script" "$copied_script"
    exec "$AUTOJS6_PYTHON_BIN" "$copied_script" "$@"
fi

exec "$AUTOJS6_PYTHON_BIN" "$@"
