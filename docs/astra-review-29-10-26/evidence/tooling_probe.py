import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[3]
    bash = Path('C:/Program Files/Git/bin/bash.exe')
    if not bash.exists():
        bash = Path('/bin/bash')
    mock = r'''
adb() {
    case "$*" in
        "get-state") echo device ;;
        "shell dumpsys package "*) printf 'versionName=4.9.1\nlastUpdateTime=2026-10-29 00:00:00\n' ;;
        "shell settings get secure enabled_accessibility_services") echo com.thomaswcode.decrastination/com.thomaswcode.decrastination.block.FocusService ;;
        "shell dumpsys accessibility") printf 'Bound services:\nService[label=Focus, id=com.thomaswcode.decrastination/.block.FocusService, x=y]\nEnabled services:\n' ;;
        *"cat files/settings.json") echo '{"calendarWriteId":null}' ;;
        "logcat "*) return 0 ;;
        *"files/crashes"*) echo 'run-as: could not read crash directory: Permission denied' >&2; return 1 ;;
        *"files/crashes/"*) echo 'run-as: could not read crash directory: Permission denied' >&2; return 1 ;;
        "shell dumpsys window") echo 'mCurrentFocus=Window{abc u0 com.thomaswcode.decrastination/.ui.AskActivity}' ;;
        *"files/*.json"*) echo '-rw------- 1 u0_a100 u0_a100 200 2026-10-29 00:00 files/tasks.json' ;;
        *) echo 'unexpected mock command' >&2; return 9 ;;
    esac
}
export -f adb
bash "$REVIEW_PHONE_SCRIPT"
'''
    with tempfile.TemporaryDirectory(prefix='astra-tooling-') as temp:
        subprocess.run(['git', 'init', '-q', temp], check=True)
        Path(temp, 'private').mkdir()
        env = dict(os.environ, REVIEW_PHONE_SCRIPT=(root / 'scripts/phone-check.sh').as_posix())
        run = subprocess.run([str(bash), '-c', mock], cwd=temp, env=env, capture_output=True, text=True)
        result = {'case': 'crash records cannot be read', 'exit': run.returncode, 'stdout': run.stdout, 'stderr': run.stderr}
        print(json.dumps(result, indent=2))
        assert 'Permission denied' in run.stderr
        assert run.returncode == 0 and '== All well' in run.stdout


if __name__ == '__main__':
    main()
